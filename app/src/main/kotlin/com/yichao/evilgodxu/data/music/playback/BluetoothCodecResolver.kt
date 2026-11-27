package com.yichao.evilgodxu.data.music.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// A2DP 代理等待上限，超时说明栈不可用
private const val PROXY_TIMEOUT_MS = 1_000L

/**
 * 蓝牙编解码参数：A2DP 协商结果，反射读取。
 * 权限不足属预期，仅成员缺失才记日志。
 */
internal object BluetoothCodecResolver {

    /** 反射的系统接口：按远端设备返回 A2DP 状态 */
    private const val CODEC_STATUS_METHOD = "getCodecStatus"

    // 位掩码对照表，按最低有效位优先
    private val SAMPLE_RATE_FLAGS = listOf(
        BluetoothCodecConfig.SAMPLE_RATE_44100 to 44_100,
        BluetoothCodecConfig.SAMPLE_RATE_48000 to 48_000,
        BluetoothCodecConfig.SAMPLE_RATE_88200 to 88_200,
        BluetoothCodecConfig.SAMPLE_RATE_96000 to 96_000,
        BluetoothCodecConfig.SAMPLE_RATE_176400 to 176_400,
        BluetoothCodecConfig.SAMPLE_RATE_192000 to 192_000,
    )

    private val BITS_PER_SAMPLE_FLAGS = listOf(
        BluetoothCodecConfig.BITS_PER_SAMPLE_16 to 16,
        BluetoothCodecConfig.BITS_PER_SAMPLE_24 to 24,
        BluetoothCodecConfig.BITS_PER_SAMPLE_32 to 32,
    )

    /** 解析当前编解码器参数，不可用时 null */
    suspend fun resolve(context: Context, device: BluetoothDevice): BluetoothCodecInfo? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            val proxy = awaitA2dpProxy(context, adapter) ?: return@withTimeoutOrNull null
            try {
                readCodecInfo(proxy, device)
            } finally {
                runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
            }
        }
    }

    /** 取 A2DP 代理；超时后就地关闭防泄漏 */
    private suspend fun awaitA2dpProxy(
        context: Context,
        adapter: BluetoothAdapter,
    ): BluetoothA2dp? = suspendCancellableCoroutine { continuation ->
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                if (continuation.isActive) {
                    continuation.resume(proxy as? BluetoothA2dp)
                } else {
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }
        val requested = runCatching {
            adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!requested && continuation.isActive) continuation.resume(null)
    }

    // 需 BLUETOOTH_CONNECT，入口已校验
    @SuppressLint("MissingPermission")
    private fun readCodecInfo(proxy: BluetoothA2dp, device: BluetoothDevice): BluetoothCodecInfo? =
        try {
            val status = BluetoothA2dp::class.java
                .getMethod(CODEC_STATUS_METHOD, BluetoothDevice::class.java)
                .invoke(proxy, device) as? BluetoothCodecStatus
            status?.codecConfig?.let(::codecInfo)
        } catch (e: LinkageError) {
            // 成员缺失说明版本不符，记日志定位
            CrashLogManager.logException("BluetoothCodecResolver", "读取蓝牙编解码器参数失败", e)
            null
        } catch (e: Exception) {
            // 权限不足等属预期，留空跳过
            null
        }

    // 全空视为尚未协商，不产出
    private fun codecInfo(config: BluetoothCodecConfig): BluetoothCodecInfo? = BluetoothCodecInfo(
        name = codecName(config),
        sampleRateHz = sampleRateHz(config.sampleRate),
        bitsPerSample = bitsPerSample(config.bitsPerSample),
        channelMode = channelMode(config.channelMode),
    ).takeIf {
        it.name != null || it.sampleRateHz != null ||
            it.bitsPerSample != null || it.channelMode != null
    }

    /** 编解码器名：Android 15 取平台名，否则查对照表 */
    @Suppress("DEPRECATION")
    private fun codecName(config: BluetoothCodecConfig): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            extendedCodecName(config) ?: legacyCodecName(config.codecType)
        } else {
            legacyCodecName(config.codecType)
        }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun extendedCodecName(config: BluetoothCodecConfig): String? =
        config.extendedCodecType?.codecName?.takeIf { it.isNotBlank() }

    // 整数标识 13 公开、15 弃用
    @Suppress("DEPRECATION")
    internal fun legacyCodecName(codecType: Int): String? = when (codecType) {
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS -> "Opus"
        else -> null
    }

    internal fun sampleRateHz(mask: Int): Int? = lowestFlagValue(mask, SAMPLE_RATE_FLAGS)

    internal fun bitsPerSample(mask: Int): Int? = lowestFlagValue(mask, BITS_PER_SAMPLE_FLAGS)

    internal fun channelMode(mode: Int): BluetoothChannelMode? = when (mode) {
        BluetoothCodecConfig.CHANNEL_MODE_MONO -> BluetoothChannelMode.MONO
        BluetoothCodecConfig.CHANNEL_MODE_STEREO -> BluetoothChannelMode.STEREO
        else -> null
    }

    // 位掩码按对照表取最低有效位
    private fun lowestFlagValue(mask: Int, flags: List<Pair<Int, Int>>): Int? =
        flags.firstOrNull { mask and it.first != 0 }?.second
}
