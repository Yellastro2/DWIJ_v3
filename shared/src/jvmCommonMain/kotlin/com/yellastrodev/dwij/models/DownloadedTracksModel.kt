package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.SongRepository
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.repo.VkMusicRepository
import com.yellastrodev.dwij.data.repo.WaveRepository
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Снимок готовых постоянных загрузок; при повторной проверке уже показанные строки сохраняются. */
data class DownloadedTracksState(
    val entries: List<MixedTracklistEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Следит за постоянным хранением ЯМ/VK и готовит очередь только из сохранённых экземпляров. */
class DownloadedTracksModel(
    private val source: HomeMusicSource,
    songs: SongRepository,
    private val cache: TrackCacheRepository,
    private val vk: VkMusicRepository,
    private val player: PlayerRepository,
    private val wave: WaveRepository,
    private val logger: YamLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DownloadedTracksState())
    val state = mutableState.asStateFlow()
    private val reload = MutableStateFlow(0L)
    private val mutablePlaying = MutableStateFlow(false)
    val playing = mutablePlaying.asStateFlow()

    init {
        viewModelScope.launch {
            combine(songs.songs, cache.localStorageRevision, vk.localStorageRevision, reload) { library, _, _, _ -> library }
                .flowOn(Dispatchers.IO)
                .collect { library ->
                    mutableState.update { it.copy(loading = true, failed = false) }
                    try {
                        val entries = withContext(Dispatchers.IO) { savedEntries(library) }
                        mutableState.value = DownloadedTracksState(entries, loading = false)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.error("DownloadedTracksModel", "[load] Не удалось проверить скачанные треки", error)
                        mutableState.update { it.copy(loading = false, failed = true) }
                    }
                }
        }
    }

    /** Повторно проверяет файлы на устройстве без запросов к музыкальным сервисам. */
    fun refresh() { reload.update { it + 1L } }

    /** Исключает LRU-кеш, пустые ЯМ-файлы и незавершённые VK-bundle. Вызывается на IO. */
    private fun savedEntries(library: List<Song>): List<MixedTracklistEntry> = downloadedTrackEntries(
        library, source,
        isYandexSaved = { id -> cache.persistentFile(id).let { it.isFile && it.length() > 0L } },
        isVkSaved = vk::isSavedLocally,
    )

    /** Перепроверяет файлы и запускает сохранённый снимок по Song.id, не меняя предпочтения в Room. */
    suspend fun play(songId: String?, title: String): Boolean {
        if (mutablePlaying.value) return false
        mutablePlaying.value = true
        try {
            val snapshot = withContext(Dispatchers.IO) { savedEntries(state.value.entries.map { it.song }) }
            val index = if (songId == null) 0 else snapshot.indexOfFirst { it.song.id == songId }
            if (index !in snapshot.indices) return false
            wave.stopObserving()
            player.playQueue(snapshot.map { it.song }, index, DownloadedTracklist(source, title, snapshot))
            return true
        } finally {
            mutablePlaying.value = false
        }
    }
}

/** Отбирает готовые загрузки выбранного источника и удаляет сетевые/MediaStore-экземпляры из снимка очереди. */
internal fun downloadedTrackEntries(
    songs: List<Song>,
    source: HomeMusicSource,
    isYandexSaved: (String) -> Boolean,
    isVkSaved: (String) -> Boolean,
): List<MixedTracklistEntry> {
    val yandexSaved = mutableMapOf<String, Boolean>()
    val vkSaved = mutableMapOf<String, Boolean>()
    return songs.distinctBy(Song::id).mapNotNull { song ->
        val instances = song.instances.filter { instance ->
            when (instance) {
                is TrackInstance.Yandex -> (source == HomeMusicSource.Yandex || source == HomeMusicSource.All) &&
                    yandexSaved.getOrPut(instance.track.id) { isYandexSaved(instance.track.id) }
                is TrackInstance.Vk -> (source == HomeMusicSource.Vk || source == HomeMusicSource.All) &&
                    vkSaved.getOrPut(instance.track.fullId) { isVkSaved(instance.track.fullId) }
                is TrackInstance.Local -> false
            }
        }
        val preferred = instances.firstOrNull { it.id == song.preferredInstanceId } ?: instances.firstOrNull()
            ?: return@mapNotNull null
        val origin = if (preferred is TrackInstance.Yandex) MusicSource.YANDEX else MusicSource.VK
        MixedTracklistEntry(song.copy(instances = instances, preferredInstanceId = preferred.id), origin)
    }.sortedWith(compareBy<MixedTracklistEntry> { it.song.title.lowercase() }.thenBy { it.song.id })
}

/** Внутренняя очередь скачанных треков: доступна без seed ЯМ, сохраняет происхождение после shuffle. */
internal class DownloadedTracklist(source: HomeMusicSource, private val title: String, entries: List<MixedTracklistEntry>) : dTracklist {
    private val id = "downloaded:${source.name}"
    private val origins = entries.associate { it.song.id to it.origin }
    /** Идентичность очереди отделена от серверных плейлистов и обычной фонотеки. */
    override fun getdId(): String = id
    /** Название скачанной коллекции. */
    override fun getDTitle(): String = title
    /** Тип внутреннего списка без операций музыкальных API. */
    override fun getType(): String = "downloaded"
    /** У локально сохранённого списка нет серверной волны. */
    override fun getWaveId(): String = ""
    /** Возвращает источник выбранного сохранённого экземпляра. */
    override fun originOf(songId: String): MusicSource? = origins[songId]
}
