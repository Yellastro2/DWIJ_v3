package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.entities.Album
import com.yellastrodev.dwij.data.entities.Artist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.repo.PlaybackQueue
import com.yellastrodev.yamusicsdk.YamApiClient
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.entities.YaTrack
import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.playlists.PlaylistDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Загружает рекомендацию и запускает временную очередь без записи метаданных и связей в БД. */
class DailyPlaylistPlayback(
    private val client: YamApiClient,
    private val player: PlaybackQueue,
    private val scope: CoroutineScope,
    private val isWaveLoading: () -> Boolean,
    private val stopWave: () -> Unit,
    private val isTrackCached: (String) -> Boolean,
    private val onAuthorizationRequired: () -> Unit,
    private val logger: YamLogger,
) {
    private val starting = AtomicBoolean(false)
    val isStarting: Boolean get() = starting.get()

    /** Каждый вызов получает свежий список API; результат хранит только вызывающий экран. */
    suspend fun load(): YamResult<PlaylistDetails?> {
        val result = client.playlistOfTheDay()
        if (result is YamResult.Failure && result.error == YamError.Unauthorized) {
            onAuthorizationRequired()
        }
        return result
    }

    /** Запускает уже загруженные треки в scope приложения, переживая закрытие экрана волн. */
    fun play(details: PlaylistDetails): Boolean {
        if (isWaveLoading() || !starting.compareAndSet(false, true)) return false
        val songs = details.tracks
            .filter { it.available || isTrackCached(it.id) }
            .map { it.toDailyPlaylistSong() }
        if (songs.isEmpty()) {
            starting.set(false)
            return false
        }
        scope.launch(Dispatchers.Main) {
            try {
                stopWave()
                player.playQueue(songs, 0, DailyPlaylistTracklist(details.playlist.title))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error("DailyPlaylistPlayback", "[play] Не удалось запустить плейлист дня", error)
            } finally {
                starting.set(false)
            }
        }
        return true
    }
}

/** Конечная рекомендация в памяти; не является сущностью сохранённого ЯМ-плейлиста. */
data class DailyPlaylistTracklist(val title: String) : dTracklist {
    /** Отдельное пространство идентификаторов исключает совпадение с фонотекой. */
    override fun getdId(): String = "recommendation:daily"
    /** Название контекста воспроизведения. */
    override fun getDTitle(): String = title
    /** Тип конечной временной очереди. */
    override fun getType(): String = "daily_recommendation"
    /** Rotor для этой очереди не используется. */
    override fun getWaveId(): String = ""
}

/** Создаёт только снимок для плеера, не добавляя песню, артистов или связи в Room. */
internal fun YaTrack.toDailyPlaylistSong(): Song {
    val instanceId = "daily:yandex:$id"
    return Song(
        id = "daily:song:$id",
        title = title,
        artists = artists.map { Artist("daily:artist:${it.id}", it.name) },
        albums = albums.map { Album("daily:album:${it.id}", it.title) },
        durationMs = durationMs?.toLong(),
        coverUri = ogImageUri ?: coverUri,
        instances = listOf(TrackInstance.Yandex(instanceId, toEntity())),
        preferredInstanceId = instanceId,
        hasPendingMatchCandidate = false,
        isLocalOnlyInLibrary = false,
        isLiked = false,
    )
}
