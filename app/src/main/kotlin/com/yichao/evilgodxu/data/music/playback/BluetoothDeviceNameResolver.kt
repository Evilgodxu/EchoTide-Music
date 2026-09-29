package com.yichao.evilgodxu.data.music.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import com.yichao.evilgodxu.log.CrashLogManager

/**
 * 蓝牙音频设备名解析。
 *
 * AudioDeviceInfo.productName 在部分设备上返回的是本机蓝牙名称而非远端设备名称，不能作为设备名的来源，
 * 故经官方 BluetoothManager / BluetoothProfile 读取已连接设备的远端名称：别名（用户自定义名）优先，
 * 其次为设备广播名。
 *
 * BLUETOOTH_CONNECT 属运行时权限，未授权时直接返回 null，由展示层退回只展示无需权限的地址与设备类型。
 * 部分 ROM（如小米）不支持按 Profile 查询已连接设备，逐个 Profile 容错。
 */
internal object BluetoothDeviceNameResolver {

    /** BluetoothProfile 中与音频输出相关的类型：A2DP 为媒体音频，HEADSET 覆盖 SCO/HFP 通路 */
    private val AUDIO_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /** 解析指定地址的远端设备名；权限不足或解析不到时返回 null */
    fun resolve(context: Context, address: String): String? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        return runCatching { resolveName(manager, address) }
            .onFailure {
                CrashLogManager.logException("BluetoothDeviceNameResolver", "解析蓝牙设备名失败", it)
            }
            .getOrNull()
    }

    // 各蓝牙查询接口均受 BLUETOOTH_CONNECT 保护，入口已校验权限，故此处标注 SuppressLint
    @SuppressLint("MissingPermission")
    private fun resolveName(manager: BluetoothManager, address: String): String? {
        val connected = connectedDevices(manager)
        // 地址在部分 ROM 上可能为空或格式不一致，匹配失败时取首个已连接设备兜底
        val device = connected.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: connected.firstOrNull()
            ?: return null
        return device.displayName()
    }

    // 合并各音频 Profile 的已连接设备：不支持该 Profile 查询的 ROM 会抛异常，逐个忽略
    private fun connectedDevices(manager: BluetoothManager): List<BluetoothDevice> {
        val devices = mutableListOf<BluetoothDevice>()
        AUDIO_PROFILES.forEach { profile ->
            runCatching { manager.getConnectedDevices(profile) }
                .onSuccess { devices += it }
        }
        return devices
    }

    // 别名为用户为设备设置的名称，未设置时为空；广播名由设备在配对与广播过程中声明
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.displayName(): String? =
        alias?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

    private fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
