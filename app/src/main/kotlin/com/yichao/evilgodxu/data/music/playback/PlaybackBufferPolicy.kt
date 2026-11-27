package com.yichao.evilgodxu.data.music.playback

import kotlin.math.max

/**
 * 缓冲按时长折算帧数，直出与独占共用。
 * 下限抗欠载，上限是延迟；80ms 已是实得下限
 * 起播阈值取容量一半，仅影响起播与欠载恢复。
 */
internal object PlaybackBufferPolicy {

    /** 目标缓冲时长 80ms，平台路径实得下限，调小无效 */
    const val TARGET_BUFFER_MS = 80

    /** 按目标时长与采样率换算帧数 */
    fun targetBufferFrames(sampleRate: Int): Int = sampleRate * TARGET_BUFFER_MS / 1000

    /** 起播阈值取实得容量一半，至少 1 帧 */
    fun startThresholdFrames(grantedFrames: Int): Int = max(1, grantedFrames / 2)

    /** 按目标时长申请缓冲字节数，不低于平台下限 */
    fun trackBufferBytes(minBufferSizeInBytes: Int, pcmFrameSize: Int, sampleRate: Int): Int =
        max(minBufferSizeInBytes, targetBufferFrames(sampleRate) * pcmFrameSize)
}
