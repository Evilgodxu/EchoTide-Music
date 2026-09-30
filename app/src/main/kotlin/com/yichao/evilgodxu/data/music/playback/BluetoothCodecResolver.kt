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

// A2DP 代理的等待上限：正常路径即时回调，超时说明蓝牙栈不可用，放弃本次采集
private const val PROXY_TIMEOUT_MS = 1_000L

/**
 * 蓝牙编解码器参数解析。
 *
 * 开发者选项展示的解码器、采样率、每样本位数与声道模式都来自 A2DP 的协商结果，而读取该结果的
 * BluetoothA2dp.getCodecStatus 属系统接口，未纳入公开 SDK，只能按方法名反射调用；其返回的
 * BluetoothCodecStatus 与 BluetoothCodecConfig 本身是公开类型，解析无需反射。
 *
 * 系统未授予特权、且应用未与本机建立「配套设备」关联时，该调用会因权限不足而失败；此类失败为
 * 预期情形，直接返回 null 交由展示层跳过对应行，仅平台成员缺失（本机版本与预期不符）才记日志。
 * 编解码器由手机与耳机协商决定，与开发者选项里的手动选择可能不同，故只上报协商结果。
 */
internal object BluetoothCodecResolver {

    /** 反射调用的系统接口：按远端设备返回当前 A2DP 编解码器状态 */
    private const val CODEC_STATUS_METHOD = "getCodecStatus"

    // 位掩码到实际取值的对照表，按最低有效位优先排列
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

    /** 解析远端设备当前使用的编解码器参数；接口不可用或未协商完成时返回 null */
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

    /**
     * 取 A2DP Profile 代理。
     *
     * 代理只能经蓝牙适配器异步获取，回调不来即说明蓝牙栈不可用，由超时兜底；
     * 超时后回调仍可能到达，此时就地关闭代理，避免连接泄漏。
     */
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

    // getCodecStatus 受 BLUETOOTH_CONNECT 保护，调用方已校验权限，故此处标注 SuppressLint
    @SuppressLint("MissingPermission")
    private fun readCodecInfo(proxy: BluetoothA2dp, device: BluetoothDevice): BluetoothCodecInfo? =
        try {
            val status = BluetoothA2dp::class.java
                .getMethod(CODEC_STATUS_METHOD, BluetoothDevice::class.java)
                .invoke(proxy, device) as? BluetoothCodecStatus
            status?.codecConfig?.let(::codecInfo)
        } catch (e: LinkageError) {
            // 平台成员缺失说明本机版本与预期不符，属缺陷而非运行时状况，记日志以便定位
            CrashLogManager.logException("BluetoothCodecResolver", "读取蓝牙编解码器参数失败", e)
            null
        } catch (e: Exception) {
            // 权限不足、接口不可用等属预期情形，留空交由展示层跳过对应行
            null
        }

    // 各项都读不到时视为尚未协商出结果，不产出条目
    private fun codecInfo(config: BluetoothCodecConfig): BluetoothCodecInfo? = BluetoothCodecInfo(
        name = codecName(config),
        sampleRateHz = sampleRateHz(config.sampleRate),
        bitsPerSample = bitsPerSample(config.bitsPerSample),
        channelMode = channelMode(config.channelMode),
    ).takeIf {
        it.name != null || it.sampleRateHz != null ||
            it.bitsPerSample != null || it.channelMode != null
    }

    /**
     * 编解码器名称。
     *
     * Android 15 起平台以标识对象上报编解码器并直接给出名称，厂商编解码器也能命名，故优先取用；
     * 更低版本只有整数标识，且公开接口不含名称，只能自行取名，未收录的标识留空。
     */
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

    // 整数标识自 Android 13 起公开、Android 15 起标注弃用，改名接口在 15 才提供，故此处保留标识对照
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

    // 采样率与位深均以位掩码上报，当前配置只置一个位；按对照表顺序取最低有效位对应的取值
    private fun lowestFlagValue(mask: Int, flags: List<Pair<Int, Int>>): Int? =
        flags.firstOrNull { mask and it.first != 0 }?.second
}
