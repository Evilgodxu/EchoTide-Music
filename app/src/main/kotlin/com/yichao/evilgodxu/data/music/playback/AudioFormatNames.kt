package com.yichao.evilgodxu.data.music.playback

import android.media.AudioFormat
import android.media.AudioMixerAttributes

/** 音频格式与混音器行为的展示名，全局统一措辞 */
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

/** 混音器属性描述：行为、采样率、声道、编码 */
internal fun describeMixerAttributes(attributes: AudioMixerAttributes): String =
    "${audioMixerBehaviorName(attributes.mixerBehavior)} " +
        "${attributes.format.sampleRate}Hz/${attributes.format.channelCount}ch/" +
        audioEncodingName(attributes.format.encoding)
