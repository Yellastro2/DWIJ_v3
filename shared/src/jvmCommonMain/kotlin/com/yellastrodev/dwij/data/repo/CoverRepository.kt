package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.cache.FileCacheStore
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.utils.DwLruCache
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.network.YamResult

data class CoverData(
    val bytes: ByteArray,
    val source: CoverSource,
)

enum class CoverSource {
    Memory,
    Disk,
    Network,
}

class CoverRepository(
    private val yamClient: YamApiClient,
    private val fileCache: FileCacheStore,
) {

    private val memoryCache = object :
        DwLruCache<String, ByteArray>(COVER_MEMORY_CACHE_BYTES) {

        override fun sizeOf(
            key: String,
            value: ByteArray,
        ): Int = value.size
    }

    suspend fun getTrackCover(
        track: dYaTrack,
        size: CoverSize = CoverSize.`200x200`,
    ): CoverData? {
        val url = track.getCoverUriAny()
            ?: return null

        return getRemoteCover(
            entityType = "track",
            entityId = track.id,
            url = url,
            size = size,
        )
    }

    /**
     * Загружает крупную обложку для Android-плеера,
     * не раскрывая CoverSize платформенному модулю.
     */
    suspend fun getPlayerTrackCover(
        track: dYaTrack,
    ): CoverData? =
        getTrackCover(
            track = track,
            size = CoverSize.`400x400`,
        )

    suspend fun getPlaylistCover(
        playlist: dYaPlaylist,
        size: CoverSize = CoverSize.`200x200`,
    ): CoverData? {
        val url = playlist.ogImageUri
            ?: return null

        return getRemoteCover(
            entityType = "playlist",
            entityId = playlist.playlistUuid,
            url = url,
            size = size,
        )
    }

    suspend fun getRemoteCover(
        entityType: String,
        entityId: String,
        url: String,
        size: CoverSize,
    ): CoverData? {
        val key = coverKey(
            entityType = entityType,
            entityId = entityId,
            url = url,
            size = size,
        )

        memoryCache[key]?.let { bytes ->
            return CoverData(
                bytes = bytes,
                source = CoverSource.Memory,
            )
        }

        fileCache.read(key)?.let { bytes ->
            memoryCache.put(key, bytes)

            return CoverData(
                bytes = bytes,
                source = CoverSource.Disk,
            )
        }

        val downloadedBytes = downloadCover(
            url = url,
            size = size,
        ) ?: return null

        memoryCache.put(key, downloadedBytes)
        fileCache.write(key, downloadedBytes)

        return CoverData(
            bytes = downloadedBytes,
            source = CoverSource.Network,
        )
    }

    private suspend fun downloadCover(
        url: String,
        size: CoverSize,
    ): ByteArray? {
        return when (
            val result = yamClient.coverBytes(
                uri = url,
                size = size,
            )
        ) {
            is YamResult.Success -> {
                result.value.takeIf { bytes ->
                    bytes.isNotEmpty()
                }
            }

            is YamResult.Failure -> null
        }
    }

    private fun coverKey(
        entityType: String,
        entityId: String,
        url: String,
        size: CoverSize,
    ): String {
        return "$entityType|$entityId|$url|${size.name}"
    }

    private companion object {
        const val COVER_MEMORY_CACHE_BYTES =
            16 * 1024 * 1024
    }
}
