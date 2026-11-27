package com.yichao.evilgodxu.data.music.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.yichao.evilgodxu.log.CrashLogManager

// 日志前缀，仅建立失败时记录
private const val LOG_TAG = "SilentKeepAliveOutput"

// 取不到默认采样率时的兜底，只影响保活轨
private const val FALLBACK_SAMPLE_RATE = 48000

// 单声道 16 位每帧字节数，仅用于兜底缓冲
private const val PCM_16BIT_FRAME_BYTES = 2

// 与音乐同用媒体属性，落在同一路混音输出上
private val KEEP_ALIVE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/**
 * 直出期间的静音保活：在 USB 设备恒留一路输出
 * 平台按设备有无输出应答端口查询，缺落点则直出被拒
 * 只持有不播放；格式须与任一档位不同，避免被吸入专用流。
 * 线程：路由重算驱动，字段 volatile。
 */
internal class SilentKeepAliveOutput(private val audioManager: AudioManager) {

    /** 保活轨格式，null 为未保活；供调用方复核 */
    @Volatile
    var format: AudioFormat? = null
        private set

    /** 保活所在设备编号，null 为未保活 */
    @Volatile
    var heldDeviceId: Int? = null
        private set

    private var track: AudioTrack? = null

    /** 在 device 上保有一路输出；建轨失败按未保活 */
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
                // 从不写入，取平台下限，取不到按一秒兜底
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
        // 钉定路由是唯一动作，钉不上即释放
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
