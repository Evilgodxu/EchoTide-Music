package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor

/**
 * 曲库白名单对应的提取器集合，顺序与 DefaultExtractorsFactory 中的相对次序一致。
 *
 * 默认工厂注册了 21 个提取器（含 TS/PS/FLV/AVI/AC3/AC4 与整组图片格式），它们的构造类因此全部可达。
 * 本应用只播音频，且曲库白名单就是这几种容器（见 MusicScanner 的 SUPPORTED_AUDIO_EXTENSIONS），
 * 其余提取器连入口都没有。
 *
 * 顺序即嗅探优先级：Matroska 先于 Mp4、Mp3 先于 FragmentedMp4，与默认工厂一致；
 * 调整顺序会让某些无扩展名文件的嗅探结果改变。
 *
 * 三个带字幕轨的容器显式关掉字幕解析（与各自无参构造的取值相同）：媒体项从不带字幕配置，
 * 播放器侧也没有文本渲染器，解析结果无处可去；关掉后那组字幕解析实现不再被引用，可一并裁掉。
 */
@OptIn(UnstableApi::class)
internal class AudioExtractorsFactory : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = arrayOf(
        // 裸 AAC 流（.aac）
        AdtsExtractor(),
        // .amr
        AmrExtractor(),
        // .flac
        FlacExtractor(),
        // .mka / .webm
        MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED,
            MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        ),
        // .mp3 / .mpeg
        Mp3Extractor(),
        // 分片 MP4：在线音源偶有给 fMP4 的
        FragmentedMp4Extractor(
            SubtitleParser.Factory.UNSUPPORTED,
            FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        ),
        // .m4a / .m4b / .mp4 / .3gp / .alac
        Mp4Extractor(
            SubtitleParser.Factory.UNSUPPORTED,
            Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        ),
        // .ogg / .oga / .opus
        OggExtractor(),
        // .wav / .wave
        WavExtractor(),
    )
}
