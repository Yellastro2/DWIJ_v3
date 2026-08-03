package com.yellastrodev.yandexmusiclib

import android.util.Log
import com.yellastrodev.yandexmusiclib.account.AccountApi
import com.yellastrodev.yandexmusiclib.account.AccountStatus
import com.yellastrodev.yandexmusiclib.covers.CoverApi
import com.yellastrodev.yandexmusiclib.download.DownloadApi
import com.yellastrodev.yandexmusiclib.download.DownloadInfo
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.entities.YaLikeTracklist
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.likes.LikeActionResult
import com.yellastrodev.yandexmusiclib.likes.LikesApi
import com.yellastrodev.yandexmusiclib.network.YamHttpTransport
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.playlists.PlaylistApi
import com.yellastrodev.yandexmusiclib.playlists.PlaylistDetails
import com.yellastrodev.yandexmusiclib.playlists.PlaylistVisibility
import com.yellastrodev.yandexmusiclib.rotor.RotorApi
import com.yellastrodev.yandexmusiclib.rotor.RotorBatch
import com.yellastrodev.yandexmusiclib.rotor.RotorFeedbackType
import com.yellastrodev.yandexmusiclib.tracks.TrackApi
import com.yellastrodev.yandexmusiclib.tracks.PlayAudioRequest

/**
 * Корутино-ориентированный клиент API Яндекс Музыки для Android/Kotlin.
 *
 * Публичные сетевые операции возвращают [YamResult] и не отдают JSON наружу.
 */
class YamApiClient(
    accessToken: String,
    userId: String,
    login: String = "",
    val logger: YamLogger
) {
    @Volatile
    private var accessToken: String = accessToken

    @Volatile
    var userId: String = userId
        private set

    @Volatile
    var login: String = login
        private set

    private val httpTransport by lazy {
        YamHttpTransport(accessToken = { accessToken }, logger=logger)
    }
    private val accountApi by lazy { AccountApi(httpTransport) }
    private val likesApi by lazy { LikesApi(httpTransport) }
    private val playlistApi by lazy { PlaylistApi(httpTransport) }
    private val trackApi by lazy { TrackApi(httpTransport) }
    private val rotorApi by lazy { RotorApi(httpTransport) }
    private val downloadApi by lazy {
        DownloadApi(httpTransport, httpTransport)
    }
    private val coverApi by lazy { CoverApi(httpTransport) }

    fun updateAuthorization(
        token: String,
        userId: String,
        login: String = ""
    ) {
        accessToken = token
        this.userId = userId
        this.login = login
        logger.info(TAG, "[updateAuthorization] Авторизация клиента обновлена")
    }

    fun clearAuthorization() {
        accessToken = ""
        userId = ""
        login = ""
        logger.info(TAG, "[clearAuthorization] Авторизация клиента очищена")
    }

    suspend fun accountStatus(): YamResult<AccountStatus> =
        accountApi.status()

    suspend fun setTrackLiked(
        trackId: String,
        liked: Boolean,
        userId: String = this.userId
    ): YamResult<LikeActionResult> = likesApi.setTrackLiked(
        userId = userId,
        trackId = trackId,
        liked = liked
    )

    suspend fun likedTracks(
        ifModifiedSinceRevision: Int = 0,
        userId: String = this.userId
    ): YamResult<YaLikeTracklist> = likesApi.likedTracks(
        userId = userId,
        ifModifiedSinceRevision = ifModifiedSinceRevision
    )

    suspend fun playlist(
        kind: Int,
        userId: String = this.userId
    ): YamResult<PlaylistDetails> =
        playlistApi.playlist(userId, kind.toString())

    suspend fun playlists(
        userId: String = this.userId
    ): YamResult<List<YaPlaylist>> = playlistApi.playlists(userId)

    suspend fun createPlaylist(
        title: String,
        isPublic: Boolean = true,
        userId: String = this.userId
    ): YamResult<YaPlaylist> = playlistApi.create(
        userId = userId,
        title = title,
        visibility = if (isPublic) {
            PlaylistVisibility.PUBLIC
        } else {
            PlaylistVisibility.PRIVATE
        }
    )

    suspend fun deletePlaylist(
        kind: String,
        userId: String = this.userId
    ): YamResult<Unit> = playlistApi.delete(userId, kind)

    suspend fun addTrack(
        playlistKind: Int,
        revision: Int,
        trackId: String,
        trackAlbum: String,
        at: Int = 0,
        userId: String = this.userId
    ): YamResult<YaPlaylist> = playlistApi.insertTrack(
        userId = userId,
        kind = playlistKind.toString(),
        revision = revision,
        trackId = trackId,
        albumId = trackAlbum,
        at = at
    )

    suspend fun removeTrack(
        playlistKind: Int,
        revision: Int,
        trackNumber: Int,
        userId: String = this.userId
    ): YamResult<YaPlaylist> = playlistApi.deleteTrack(
        userId = userId,
        kind = playlistKind.toString(),
        revision = revision,
        fromIndex = trackNumber,
        toIndex = trackNumber + 1
    )

    suspend fun tracks(
        trackIds: List<String>,
        withPositions: Boolean = true
    ): YamResult<List<YaTrack>> =
        trackApi.tracks(trackIds, withPositions)

    /**
     * Отправляет универсальную телеметрию прослушивания трека.
     * Для волны вызывается параллельно специализированным rotor feedback.
     */
    suspend fun playAudio(
        request: PlayAudioRequest
    ): YamResult<Unit> = trackApi.playAudio(
        request.copy(
            uid = request.uid ?: userId.toLongOrNull()
        )
    )

    suspend fun startWave(station: String): YamResult<RotorBatch> =
        rotorApi.tracks(station = station)

    suspend fun nextWaveTracks(
        station: String,
        previousTrackId: String
    ): YamResult<RotorBatch> = rotorApi.tracks(
        station = station,
        queue = previousTrackId
    )

    suspend fun sendWaveStarted(
        station: String,
        batchId: String? = null,
        from: String? = null
    ): YamResult<Unit> {
        val resolvedSource = from ?: when (
            val sourceResult = rotorApi.feedbackSource(station)
        ) {
            is YamResult.Success -> sourceResult.value
            is YamResult.Failure -> fallbackFeedbackSource(station)
        }
        return rotorApi.feedback(
            station = station,
            type = RotorFeedbackType.RADIO_STARTED,
            from = resolvedSource,
            batchId = batchId
        )
    }

    /**
     * Повторяет выбор `station.id_for_from` из radio-примера Python SDK.
     */
    private fun fallbackFeedbackSource(station: String): String {
        val stationType = station.substringBefore(':')
        return if (stationType == USER_STATION_TYPE) {
            "$USER_STATION_TYPE-$userId"
        } else {
            stationType
        }
    }

    suspend fun sendWaveTrackStarted(
        station: String,
        trackId: String,
        batchId: String? = null
    ): YamResult<Unit> = rotorApi.feedback(
        station = station,
        type = RotorFeedbackType.TRACK_STARTED,
        trackId = trackId,
        batchId = batchId
    )

    suspend fun sendWaveTrackFinished(
        station: String,
        trackId: String,
        totalPlayedSeconds: Float,
        batchId: String? = null
    ): YamResult<Unit> = rotorApi.feedback(
        station = station,
        type = RotorFeedbackType.TRACK_FINISHED,
        trackId = trackId,
        totalPlayedSeconds = totalPlayedSeconds,
        batchId = batchId
    )

    suspend fun sendWaveTrackSkipped(
        station: String,
        trackId: String,
        totalPlayedSeconds: Float,
        batchId: String? = null
    ): YamResult<Unit> = rotorApi.feedback(
        station = station,
        type = RotorFeedbackType.SKIP,
        trackId = trackId,
        totalPlayedSeconds = totalPlayedSeconds,
        batchId = batchId
    )

    suspend fun trackDownloadInfo(
        trackId: String
    ): YamResult<List<DownloadInfo>> =
        downloadApi.downloadInfo(trackId)

    suspend fun trackDownloadUrl(
        trackId: String
    ): YamResult<String> =
        downloadApi.directDownloadUrl(trackId)

    suspend fun trackDownloadBytes(
        trackId: String
    ): YamResult<ByteArray> =
        downloadApi.downloadBytes(trackId)

    suspend fun coverBytes(
        uri: String,
        size: CoverSize
    ): YamResult<ByteArray> = coverApi.bytes(uri, size)

    private companion object {
        const val TAG = "YamApiClient"
        const val USER_STATION_TYPE = "user"
    }
}
