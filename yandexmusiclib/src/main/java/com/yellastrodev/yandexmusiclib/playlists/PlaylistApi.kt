package com.yellastrodev.yandexmusiclib.playlists

import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrackList
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpBody
import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamResponseDecoder
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

internal class PlaylistApi(
    private val transport: YamTransport
) {
    suspend fun playlist(userId: String, kind: String): YamResult<PlaylistDetails> {
        if (userId.isBlank() || kind.isBlank()) {
            return invalidArguments("userId и kind не должны быть пустыми")
        }

        return executeAndDecodePlaylist(
            YamHttpRequest(
                method = YamHttpMethod.GET,
                path = "/users/$userId/playlists/$kind"
            )
        )
    }

    suspend fun playlists(userId: String): YamResult<List<YaPlaylist>> {
        if (userId.isBlank()) {
            return invalidArguments("userId не должен быть пустым")
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/users/$userId/playlists/list"
                )
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response.value,
                ListSerializer(YaPlaylist.serializer())
            )
            is YamResult.Failure -> response
        }
    }

    suspend fun create(
        userId: String,
        title: String,
        visibility: PlaylistVisibility
    ): YamResult<YaPlaylist> {
        if (userId.isBlank() || title.isBlank()) {
            return invalidArguments("userId и title не должны быть пустыми")
        }

        return executeAndDecodePlaylistModel(
            YamHttpRequest(
                method = YamHttpMethod.POST,
                path = "/users/$userId/playlists/create",
                body = YamHttpBody.Form(
                    mapOf(
                        "title" to title,
                        "visibility" to visibility.apiValue
                    )
                )
            )
        )
    }

    suspend fun delete(userId: String, kind: String): YamResult<Unit> {
        if (userId.isBlank() || kind.isBlank()) {
            return invalidArguments("userId и kind не должны быть пустыми")
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.POST,
                    path = "/users/$userId/playlists/$kind/delete"
                )
            )
        ) {
            is YamResult.Success -> when (
                val decoded = YamResponseDecoder.decodeResult(
                    response.value,
                    String.serializer()
                )
            ) {
                is YamResult.Success -> if (decoded.value == "ok") {
                    YamResult.Success(Unit)
                } else {
                    YamResult.Failure(
                        YamError.InvalidResponse(
                            IllegalArgumentException(
                                "Ожидался результат ok, получено ${decoded.value}"
                            )
                        )
                    )
                }
                is YamResult.Failure -> decoded
            }
            is YamResult.Failure -> response
        }
    }

    suspend fun insertTrack(
        userId: String,
        kind: String,
        revision: Int,
        trackId: String,
        albumId: String,
        at: Int = 0
    ): YamResult<YaPlaylist> {
        if (
            userId.isBlank() ||
            kind.isBlank() ||
            trackId.isBlank() ||
            albumId.isBlank() ||
            revision < 0 ||
            at < 0
        ) {
            return invalidArguments("Некорректные параметры вставки трека")
        }
        return change(
            userId = userId,
            kind = kind,
            revision = revision,
            difference = PlaylistDifference.insert(at, trackId, albumId)
        )
    }

    suspend fun deleteTrack(
        userId: String,
        kind: String,
        revision: Int,
        fromIndex: Int,
        toIndex: Int
    ): YamResult<YaPlaylist> {
        if (
            userId.isBlank() ||
            kind.isBlank() ||
            revision < 0 ||
            fromIndex < 0 ||
            toIndex <= fromIndex
        ) {
            return invalidArguments("Некорректный диапазон удаления трека")
        }
        return change(
            userId = userId,
            kind = kind,
            revision = revision,
            difference = PlaylistDifference.delete(fromIndex, toIndex)
        )
    }

    private suspend fun change(
        userId: String,
        kind: String,
        revision: Int,
        difference: String
    ): YamResult<YaPlaylist> = executeAndDecodePlaylistModel(
        YamHttpRequest(
            method = YamHttpMethod.POST,
            path = "/users/$userId/playlists/$kind/change",
            body = YamHttpBody.Form(
                mapOf(
                    "kind" to kind,
                    "revision" to revision.toString(),
                    "diff" to difference
                )
            )
        )
    )

    private suspend fun executeAndDecodePlaylist(
        request: YamHttpRequest
    ): YamResult<PlaylistDetails> = when (val response = transport.execute(request)) {
        is YamResult.Success -> when (
            val resultElement = YamResponseDecoder.decodeResultElement(response.value)
        ) {
            is YamResult.Success -> {
                val playlist = YamResponseDecoder.decodeElement(
                    resultElement.value,
                    YaPlaylist.serializer()
                )
                val trackList = YamResponseDecoder.decodeElement(
                    resultElement.value,
                    YaTrackList.serializer()
                )
                when {
                    playlist is YamResult.Failure -> playlist
                    trackList is YamResult.Failure -> trackList
                    playlist is YamResult.Success && trackList is YamResult.Success ->
                        YamResult.Success(
                            PlaylistDetails(
                                playlist = playlist.value,
                                tracks = trackList.value.tracks.map { it.track }
                            )
                        )
                    else -> invalidArguments("Не удалось декодировать плейлист")
                }
            }
            is YamResult.Failure -> resultElement
        }
        is YamResult.Failure -> response
    }

    private suspend fun executeAndDecodePlaylistModel(
        request: YamHttpRequest
    ): YamResult<YaPlaylist> = when (val response = transport.execute(request)) {
        is YamResult.Success -> YamResponseDecoder.decodeResult(
            response.value,
            YaPlaylist.serializer()
        )
        is YamResult.Failure -> response
    }

    private fun invalidArguments(message: String): YamResult.Failure =
        YamResult.Failure(
            YamError.InvalidResponse(
                IllegalArgumentException(message)
            )
        )
}
