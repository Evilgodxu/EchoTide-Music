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

/** 纯音频渲染器装配：只产出音频渲染器，其余类可被 R8 裁掉 */
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
            // 不做解码器回退，与默认值一致
            false,
            eventHandler,
            audioRendererEventListener,
            audioSink,
        ),
        // 追加 ALAC 渲染器，优先级低于厂商解码器
        AlacAudioRenderer(eventHandler, audioRendererEventListener, audioSink),
    )
}
