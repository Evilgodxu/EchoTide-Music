package com.yichao.evilgodxu.data.music.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.yichao.evilgodxu.log.CrashLogManager

// 异常日志的类名前缀：保活输出只有建立失败时才记（正常进出不写日志）
private const val LOG_TAG = "SilentKeepAliveOutput"

// 取不到设备默认输出采样率时的兜底：只影响保活轨自身的申报，与音乐写出无关
private const val FALLBACK_SAMPLE_RATE = 48000

// 单声道 16 位每帧字节数，仅用于按平台下限取不到时的兜底缓冲申请
private const val PCM_16BIT_FRAME_BYTES = 2

// 保活轨的属性：与音乐同用媒体属性，才落在系统为「媒体」这条策略开的同一路普通混音输出上——
// 其它应用的媒体播放同样落在那里，该路输出因此不会因无人使用而被关掉
private val KEEP_ALIVE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/**
 * 直出期间的静音保活输出：在 USB 设备上恒保有一路本应用的输出。
 *
 * 要解决的问题（真机上踩过）：`getSupportedMixerAttributes` 按「该设备上此刻有没有输出」应答，
 * 应用没有输出时一律得空表，而空表有两种含义（厂商未声明动态混音端口，或设备此刻没有输出）。
 * 后一种会把直出锁在门外且从此不再回来：撤销直出会一并解除路由钉定，此后新起的输出不再落在该设备上，
 * 查询于是永远得空表——现象是后几首一路系统混音，且设备侧看不出任何异常。
 * 保活输出即为此而设：设备上始终有一路本应用的输出，查询便有描述符可应答。
 * 同一状态下系统与其它应用的音频也正缺一个落点，该路输出同时承接它们（官方对 USB 直出的指引
 * 正是「改用混音器属性而非直接抢占 USB 设备」，以免限制其它应用的媒体播放、丢失设备上的闹钟与铃声）。
 *
 * **只持有，不播放**：一个字节也不写入，因而既不产生音频、也不参与混音；换到的只是「该设备上有一路
 * 输出」这一事实。建轨本身即完成登记（媒体3 的默认输出在建轨失败时抛出的「原生建轨失败」正是出自
 * `Builder.build()`），故无需写入即可占位。
 *
 * **格式必须与本应用的任一档位都不同**：平台按「编码、声道掩码、采样率三项一致」把播放挂到偏好混音器
 * 属性的那条流上（官方 improve-audio-playback 明确写明），同格式的保活轨会被一并挂到我们那条专用输出上，
 * 那条输出就不再是只承载音乐的单客户端流。故取单声道 16 位：本应用的档位一律与解码
 * 声道数相同，单声道只在单声道源上出现，与面向双声道的档位不会同名。调用方另按设备声明的档位复核一次
 * （见 [format]），同名时放弃保活——宁可不要保活，也不能动摇直出。
 *
 * 线程：与直出同处一条链路，由直出的路由重算驱动，而那处主线程与播放线程都会走到，故字段按
 * [Volatile] 发布，建立与释放另加锁——两线程同时走到保活分支时，无锁会让其中一个建的轨道再无人释放，
 * 那条轨道会连同它占住的输出一直留在设备上。
 */
internal class SilentKeepAliveOutput(private val audioManager: AudioManager) {

    /** 保活轨的格式，null 表示当前未保活；供调用方复核是否与设备声明的档位同名 */
    @Volatile
    var format: AudioFormat? = null
        private set

    /** 当前保活输出所在的设备编号，null 表示未保活 */
    @Volatile
    var heldDeviceId: Int? = null
        private set

    private var track: AudioTrack? = null

    /**
     * 在 [device] 上保有一路输出；已在同一设备上保有时不做任何事。
     *
     * 建轨失败按未保活处理：保活是尽力而为，它的缺席只会让路由重算退回今天的行为，不该影响播放。
     */
    @Synchronized
    fun hold(device: AudioDeviceInfo) {
        if (heldDeviceId == device.id) return
        release()
        create(device)
    }

    /** 释放保活输出；未保有时为无操作 */
    @Synchronized
    fun release() {
        val held = track ?: return
        track = null
        heldDeviceId = null
        format = null
        runCatching { held.release() }
            .onFailure { CrashLogManager.logException(LOG_TAG, "释放保活输出失败", it) }
    }

    private fun create(device: AudioDeviceInfo) {
        val sampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: FALLBACK_SAMPLE_RATE
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(KEEP_ALIVE_ATTRIBUTES)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // 从不写入，缓冲尺寸只用于建轨：取平台下限，取不到时按一秒单声道 16 位兜底
                .setBufferSizeInBytes(
                    minBufferSize.takeIf { it > 0 } ?: (sampleRate * PCM_16BIT_FRAME_BYTES)
                )
                .build()
        }.onFailure {
            CrashLogManager.logException(LOG_TAG, "建立保活输出失败：${deviceLabel(device)}", it)
        }.getOrNull() ?: return
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            return
        }
        // 钉定路由是本输出唯一的动作：钉不上说明它落在别处，占不到该设备上的输出，留着只会白耗一条输出
        val pinned = runCatching { created.setPreferredDevice(device) }.getOrDefault(false)
        if (!pinned) {
            created.release()
            return
        }
        track = created
        format = audioFormat
        heldDeviceId = device.id
    }

    private fun deviceLabel(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "id=${device.id}"

}
