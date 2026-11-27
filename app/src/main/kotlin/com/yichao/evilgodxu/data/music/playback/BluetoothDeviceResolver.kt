package com.yichao.evilgodxu.data.music.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import com.yichao.evilgodxu.log.CrashLogManager
import kotlin.coroutines.cancellation.CancellationException

/**
 * 蓝牙设备信息解析：设备名、链路与编解码器一律取蓝牙栈。
 * 设备名不可靠；缺权限返回 null
 */
internal object BluetoothDeviceResolver {

    /** 音频 Profile：A2DP 媒体、HEADSET 通话 */
    private val AUDIO_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /** 解析远端设备信息；权限不足或定位不到为 null */
    suspend fun resolve(context: Context, address: String?): BluetoothDeviceFacts? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        // 编解码器读取会挂起，取消须放行
        return try {
            resolveFacts(context, manager, adapter, address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("BluetoothDeviceResolver", "解析蓝牙设备信息失败", e)
            null
        }
    }

    // 查询需 BLUETOOTH_CONNECT，入口已校验
    @SuppressLint("MissingPermission")
    private suspend fun resolveFacts(
        context: Context,
        manager: BluetoothManager,
        adapter: BluetoothAdapter,
        address: String?,
    ): BluetoothDeviceFacts? {
        val device = findDevice(manager, adapter, address) ?: return null
        return BluetoothDeviceFacts(
            name = device.displayName(),
            linkType = device.linkType(),
            deviceClass = device.bluetoothClass?.deviceClass,
            codec = BluetoothCodecResolver.resolve(context, device),
        )
    }

    /** 按地址定位设备；地址缺失时仅在唯一已连接设备上确定 */
    @SuppressLint("MissingPermission")
    private fun findDevice(
        manager: BluetoothManager,
        adapter: BluetoothAdapter,
        address: String?,
    ): BluetoothDevice? {
        val known = connectedAudioDevices(manager) + bondedDevices(adapter)
        if (address.isNullOrBlank()) return known.singleOrNull()
        known.firstOrNull { it.address.equals(address, ignoreCase = true) }?.let { return it }
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    // 合并已连接设备，个别 ROM 抛异常则忽略
    @SuppressLint("MissingPermission")
    private fun connectedAudioDevices(manager: BluetoothManager): List<BluetoothDevice> {
        val devices = mutableListOf<BluetoothDevice>()
        AUDIO_PROFILES.forEach { profile ->
            runCatching { manager.getConnectedDevices(profile) }.onSuccess { devices += it }
        }
        return devices
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
        runCatching { adapter.bondedDevices }.getOrNull().orEmpty().toList()

    // 别名优先，其次广播名
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.displayName(): String? =
        alias?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

    // 链路类型，未上报留空
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.linkType(): BluetoothLinkType? = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothLinkType.CLASSIC
        BluetoothDevice.DEVICE_TYPE_LE -> BluetoothLinkType.LE
        BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothLinkType.DUAL
        else -> null
    }

    private fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

// 蓝牙栈读到的原始设备信息
internal data class BluetoothDeviceFacts(
    val name: String?,
    val linkType: BluetoothLinkType?,
    val deviceClass: Int?,
    val codec: BluetoothCodecInfo?,
)
