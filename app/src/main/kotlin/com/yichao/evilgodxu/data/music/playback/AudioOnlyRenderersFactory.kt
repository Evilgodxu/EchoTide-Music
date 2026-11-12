package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.yichao.evilgodxu.data.music.playback.alac.AlacAudioRenderer

/**
 * 纯音频渲染器装配：只产出音频渲染器，不引用 DefaultRenderersFactory。
 *
 * 默认工厂的 createRenderers 会无条件构建视频、字幕、元数据、相机、图片五类渲染器，
 * 即使播放器已禁用视频轨，这些类仍因被引用而无法被 R8 裁掉。本类只保留实际播放的那条链路，
 * 让上述引用整条消失（media3 的 Builder 三参构造器正是为这种裁剪留的口子）。
 *
 * 与默认工厂的差异仅限"少建渲染器"：音频侧沿用同一套适配器工厂、选择器与关闭解码器回退的默认值。
 */
@OptIn(UnstableApi::class)
internal class AudioOnlyRenderersFactory(
    private val context: Context,
    private val audioSink: AudioSink,
) : RenderersFactory {

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> = arrayOf(
        MediaCodecAudioRenderer(
            context,
            DefaultMediaCodecAdapterFactory(context),
            MediaCodecSelector.DEFAULT,
            // 与 DefaultRenderersFactory 的字段默认值一致：不做解码器回退
            false,
            eventHandler,
            audioRendererEventListener,
            audioSink,
        ),
        // 补上系统缺失的 ALAC 解码渲染器。追加在系统渲染器之后，优先级更低：
        // 厂商自带的 audio/alac 解码器仍被优先选中；都没有时媒体3 会把整条音频轨判为不可解码、
        // 播放器不选中该轨——表现为静音走完且不报错，故这一条不能省
        AlacAudioRenderer(eventHandler, audioRendererEventListener, audioSink),
    )
}
