package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory

/**
 * 媒体源工厂：音频一律走渐进式，不含默认工厂那层按类型分派的加载器。
 *
 * DefaultMediaSourceFactory 会按 MIME 与扩展名分派到 HLS/DASH/RTSP/平滑流四类媒体源，
 * 这些模块本项目一个都没引入，分派层与默认提取器工厂却因被引用而始终保留。
 * 本类只保留项目实际存在的两种地址（本地 content/file 与在线直链）共用的渐进式路径。
 *
 * 片段的裁剪包装必须自己补：默认工厂是在媒体源之外包 ClippingMediaSource 的，
 * 渐进式工厂本身不看 MediaItem 的裁剪配置（心动模式依赖它，漏掉就会播成整首）。
 */
@OptIn(UnstableApi::class)
internal class AudioMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
) : MediaSource.Factory {

    private val progressive = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        maybeClip(mediaItem, progressive.createMediaSource(mediaItem))

    private fun maybeClip(mediaItem: MediaItem, mediaSource: MediaSource): MediaSource {
        val clipping = mediaItem.clippingConfiguration
        val untouched = clipping.startPositionUs == 0L &&
            clipping.endPositionUs == C.TIME_END_OF_SOURCE &&
            !clipping.relativeToDefaultPosition
        if (untouched) return mediaSource
        return ClippingMediaSource.Builder(mediaSource)
            .setClippingConfiguration(clipping)
            .build()
    }
}
