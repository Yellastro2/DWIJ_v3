package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.*
import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.dwij.data.repo.*
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.network.YamResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** Запускает конечную подборку ЯМ/VK с сохранением строгого чередования и происхождения песен. */
class MixedRecommendationPlayback(
    private val daily: DailyPlaylistPlayback,
    private val vk: VkMusicRepository,
    private val tracks: TrackRepository,
    private val songs: SongRepository,
    private val player: PlayerRepository,
    private val stopWave: () -> Unit,
    private val isWaveLoading: () -> Boolean,
    private val isCached: (String) -> Boolean,
    private val logger: YamLogger,
) {
    private val mutex = Mutex()
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()

    /** Запрашивает оба списка, индексирует метаданные и запускает только сбалансированные пары ЯМ → VK. */
    suspend fun play(title: String): DataResult<Unit> {
        if (!mutex.tryLock()) return DataResult.Failure(DataError.InvalidData("Подборка уже загружается"))
        mutableLoading.value = true
        try {
            if (isWaveLoading()) return DataResult.Failure(DataError.InvalidData("Дождитесь загрузки волны"))
            val revision = vk.sessionRevision.value
            return coroutineScope {
                val yaRequest = async { daily.load() }
                val vkRequest = async { vk.getPlaylist("vkrec:recommendations") }
                val yaResult = yaRequest.await()
                val vkResult = vkRequest.await()
                if (yaResult is YamResult.Failure) return@coroutineScope DataResult.Failure(yaResult.error.toDataError())
                if (vkResult is DataResult.Failure) return@coroutineScope vkResult
                val details = (yaResult as YamResult.Success).value
                    ?: return@coroutineScope DataResult.Failure(DataError.InvalidData("Плейлист дня ЯМ недоступен"))
                val yaTracks = details.tracks.filter { it.available || isCached(it.id) }.map { it.toEntity() }
                tracks.putTracks(yaTracks)
                val yaSongs = songs.songsForYandexTracks(yaTracks)
                val vkSongs = songs.songsForVkTracks((vkResult as DataResult.Success).value.tracks)
                val entries = alternateRecommendationSongs(yaSongs, vkSongs)
                if (entries.isEmpty()) return@coroutineScope DataResult.Failure(DataError.InvalidData("Недостаточно треков для смешанной подборки"))
                check(revision == vk.sessionRevision.value) { "Сессия VK изменилась при загрузке подборки" }
                val list = MixedTracklist(UUID.randomUUID().toString(), title, entries)
                stopWave()
                player.playQueue(list.songs, 0, list)
                logger.info("MixedRecommendationPlayback", "[play] Запущена подборка: ЯМ=${entries.size / 2}, VK=${entries.size / 2}")
                DataResult.Success(Unit)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warning("MixedRecommendationPlayback", "[play] Не удалось запустить подборку: ${error.javaClass.simpleName}")
            return DataResult.Failure(DataError.InvalidData("Не удалось загрузить смешанную подборку"))
        } finally {
            mutableLoading.value = false
            mutex.unlock()
        }
    }
}

/** Чередует уникальные песни парами, не дописывая одноисточниковый хвост после конца короткого списка. */
internal fun alternateRecommendationSongs(yandex: List<Song>, vk: List<Song>): List<MixedTracklistEntry> {
    val left = yandex.distinctBy(Song::id).iterator()
    val right = vk.distinctBy(Song::id).iterator()
    val used = mutableSetOf<String>()
    val result = mutableListOf<MixedTracklistEntry>()
    while (left.hasNext() && right.hasNext()) {
        var ya: Song? = null
        while (left.hasNext() && ya == null) left.next().takeIf { it.id !in used }?.let { ya = it }
        if (ya == null) break
        var other: Song? = null
        while (right.hasNext() && other == null) right.next().takeIf { it.id !in used && it.id != ya?.id }?.let { other = it }
        if (other == null) break
        val first = requireNotNull(ya)
        val second = requireNotNull(other)
        used.add(first.id); used.add(second.id)
        result += MixedTracklistEntry(first.copy(preferredInstanceId = first.yandexInstances.firstOrNull()?.id ?: first.preferredInstanceId), MusicSource.YANDEX)
        result += MixedTracklistEntry(second.copy(preferredInstanceId = second.vkInstances.firstOrNull()?.id ?: second.preferredInstanceId), MusicSource.VK)
    }
    return result
}
