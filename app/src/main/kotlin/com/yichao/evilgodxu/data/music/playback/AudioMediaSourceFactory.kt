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

/** 媒体源工厂：一律渐进式，并自行补上片段裁剪 */
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
