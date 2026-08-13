package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.toDataError
import com.yellastrodev.yamusicsdk.YamApiClient
import com.yellastrodev.yamusicsdk.artists.ArtistBriefInfo
import com.yellastrodev.yamusicsdk.entities.YaAlbum
import com.yellastrodev.yamusicsdk.network.YamResult

/** Загружает страницы артистов и альбомов из Яндекс Музыки. */
class CatalogRemoteSource(
    private val client: YamApiClient,
) {
    suspend fun artist(artistId: Int): DataResult<ArtistBriefInfo> =
        when (val result = client.artistBriefInfo(artistId)) {
            is YamResult.Success -> DataResult.Success(result.value)
            is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
        }

    suspend fun album(albumId: Int): DataResult<YaAlbum> =
        when (val result = client.albumWithTracks(albumId)) {
            is YamResult.Success -> DataResult.Success(result.value)
            is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
        }
}
