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
 * 蓝牙设备信息解析。
 *
 * 音频栈对蓝牙设备只有一份泛化描述：AudioDeviceInfo.productName 在部分设备上返回的是本机蓝牙名称
 * 而非远端设备名称，不能作为设备名来源，故设备名、链路附加信息与编解码器参数一律经官方蓝牙接口
 * 读取——名称取别名（用户自定义名）优先、其次广播名，链路类型与设备类别同样取自蓝牙栈。
 *
 * BLUETOOTH_CONNECT 属运行时权限，未授权时直接返回 null，此时设备名与地址都无从取得，
 * 展示层只保留音频栈给出的支持格式。部分 ROM（如小米）不支持按 Profile 查询已连接设备，
 * 逐个 Profile 容错。编解码器参数另有系统接口的权限限制，取不到时单独留空，不影响其余各项。
 */
internal object BluetoothDeviceResolver {

    /** 与音频输出相关的 BluetoothProfile：A2DP 承载媒体音频，HEADSET 覆盖 SCO/HFP 通路 */
    private val AUDIO_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /** 解析指定地址的远端设备信息；权限不足或定位不到设备时返回 null */
    suspend fun resolve(context: Context, address: String?): BluetoothDeviceFacts? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        // 编解码器参数的读取会挂起，采集协程取消时要放行取消信号，不能与查询异常一并吞掉
        return try {
            resolveFacts(context, manager, adapter, address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("BluetoothDeviceResolver", "解析蓝牙设备信息失败", e)
            null
        }
    }

    // 各蓝牙查询接口均受 BLUETOOTH_CONNECT 保护，入口已校验权限，故此处标注 SuppressLint
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

    /**
     * 按地址定位远端设备。
     *
     * 先在已连接音频设备与已配对设备中按地址匹配：当前正在输出的设备必在其中，且这两处的名称缓存
     * 一定存在。地址缺失或被隐私策略匿名化时匹配不上，此时只有已连接音频设备唯一才可确定身份，
     * 多个并存宁可留空，避免把别的设备名安到当前输出上。仍未命中则按地址直接取远端设备对象。
     */
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

    // 合并各音频 Profile 的已连接设备：不支持该 Profile 查询的 ROM 会抛异常，逐个忽略
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

    // 别名为用户为设备设置的名称，未设置时为空；广播名由设备在配对与广播过程中声明
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.displayName(): String? =
        alias?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

    // 链路类型：经典蓝牙/低功耗蓝牙/双模；平台未上报类型时留空
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

// 蓝牙栈读到的远端设备信息：除编解码器参数外均为未加工的平台取值，由采集侧映射为快照字段
internal data class BluetoothDeviceFacts(
    val name: String?,
    val linkType: BluetoothLinkType?,
    val deviceClass: Int?,
    val codec: BluetoothCodecInfo?,
)
