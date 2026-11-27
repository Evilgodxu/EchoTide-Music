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
 * 曲库白名单提取器，顺序即嗅探优先级
 * 三个容器显式关闭字幕解析，相关实现可被裁掉。
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
        // m4a/m4b/mp4/3gp/alac
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
