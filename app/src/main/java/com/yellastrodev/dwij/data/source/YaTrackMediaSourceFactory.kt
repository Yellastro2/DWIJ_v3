package com.yellastrodev.dwij.data.source

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import kotlinx.coroutines.runBlocking

@OptIn(UnstableApi::class)
class YaTrackMediaSourceFactory(
    private val trackCacheRepo: TrackCacheRepository
) : MediaSource.Factory {

    private val defaultFactory = DefaultMediaSourceFactory(trackCacheRepo.context)


    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val trackId = mediaItem.mediaId
        val uri = runBlocking { trackCacheRepo.getOrDownload(trackId) }
        val resolvedItem = mediaItem.buildUpon().setUri(uri).build()
        return defaultFactory.createMediaSource(resolvedItem)
    }

    // Обязательные методы интерфейса
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        defaultFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        defaultFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray {
        return defaultFactory.supportedTypes
    }
}
