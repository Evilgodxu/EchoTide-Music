package com.yichao.evilgodxu.data.music.playback

import android.media.AudioFormat
import android.media.AudioMixerAttributes

/**
 * 音频格式、声道掩码与混音器行为的展示名。
 *
 * 直出档位、诊断报告与面板都要给出同一套说法：各写一份时，同一台设备在不同位置会显示成
 * 不同措辞（「24位整型」与「编码20」），对照现场时反而多一层歧义。
 */
internal fun audioEncodingName(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "8位整型"
    AudioFormat.ENCODING_PCM_16BIT -> "16位整型"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24位整型"
    AudioFormat.ENCODING_PCM_32BIT -> "32位整型"
    AudioFormat.ENCODING_PCM_FLOAT -> "32位浮点"
    AudioFormat.ENCODING_AC3 -> "AC3"
    AudioFormat.ENCODING_E_AC3 -> "E-AC3"
    AudioFormat.ENCODING_DTS -> "DTS"
    AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
    AudioFormat.ENCODING_DTS_HD_MA -> "DTS-HD MA"
    AudioFormat.ENCODING_AC4 -> "AC4"
    AudioFormat.ENCODING_MP3 -> "MP3"
    AudioFormat.ENCODING_OPUS -> "OPUS"
    AudioFormat.ENCODING_DSD -> "DSD"
    AudioFormat.ENCODING_IEC61937 -> "IEC61937"
    else -> "编码$encoding"
}

internal fun audioMixerBehaviorName(behavior: Int): String = when (behavior) {
    AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> "默认行为"
    AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "位完美"
    else -> "行为$behavior"
}

/** 混音器属性的一行描述：行为、采样率、声道、编码，四项即直出匹配所用的全部字段 */
internal fun describeMixerAttributes(attributes: AudioMixerAttributes): String =
    "${audioMixerBehaviorName(attributes.mixerBehavior)} " +
        "${attributes.format.sampleRate}Hz/${attributes.format.channelCount}ch/" +
        audioEncodingName(attributes.format.encoding)
