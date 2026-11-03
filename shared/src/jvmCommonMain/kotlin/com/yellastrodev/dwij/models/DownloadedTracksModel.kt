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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Снимок доступных без сети треков; при повторной проверке уже показанные строки сохраняются. */
data class DownloadedTracksState(
    val entries: List<MixedTracklistEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Собирает локальные файлы и полный кеш ЯМ/VK; очередь запускается исключительно без сети. */
class DownloadedTracksModel(
    private val source: HomeMusicSource,
    songs: SongRepository,
    private val cache: TrackCacheRepository,
    private val vk: VkMusicRepository,
    private val player: PlayerRepository,
    private val wave: WaveRepository,
    private val logger: YamLogger,
    private val isLocalReadable: (LocalTrackEntity) -> Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DownloadedTracksState())
    val state = mutableState.asStateFlow()
    private val reload = MutableStateFlow(0L)
    private val mutablePlaying = MutableStateFlow(false)
    val playing = mutablePlaying.asStateFlow()

    init {
        viewModelScope.launch {
            reload.collectLatest {
                combine(songs.songs, cache.localStorageRevision, vk.localStorageRevision) { library, _, _ -> library }
                    .flowOn(Dispatchers.IO)
                    .onEach { library ->
                        mutableState.update { it.copy(loading = true, failed = false) }
                        val entries = withContext(Dispatchers.IO) { savedEntries(library) }
                        val instances = entries.flatMap { it.song.instances }
                        logger.info("DownloadedTracksModel", "[load] Источник=$source, в индексе=${library.size}, " +
                            "доступно=${entries.size}, локальных=${instances.count { it is TrackInstance.Local }}, " +
                            "ЯМ=${instances.count { it is TrackInstance.Yandex }}, VK=${instances.count { it is TrackInstance.Vk }}")
                        mutableState.value = DownloadedTracksState(entries, loading = false)
                    }
                    .catch { error ->
                        logger.error("DownloadedTracksModel", "[load] Не удалось прочитать индекс или проверить файлы", error)
                        mutableState.update { it.copy(loading = false, failed = true) }
                    }
                    .collect()
            }
        }
    }

    /** Повторно читает индекс и файлы без сети, в том числе после ошибки предыдущей проверки. */
    fun refresh() { reload.update { it + 1L } }

    /** Проверяет локальные файлы, постоянные загрузки и полный кеш; не открывает relay и не обновляет LRU. */
    private fun savedEntries(library: List<Song>): List<MixedTracklistEntry> = downloadedTrackEntries(
        library, source,
        isYandexSaved = cache::isReadyLocally,
        isVkSaved = { id -> vk.isSavedLocally(id) || vk.isCachedLocally(id) },
        isLocalReadable = isLocalReadable,
    )

    /** Перепроверяет файлы и запускает сохранённый снимок по Song.id, не меняя предпочтения в Room. */
    suspend fun play(songId: String?, title: String): Boolean {
        if (mutablePlaying.value || state.value.entries.isEmpty()) return false
        mutablePlaying.value = true
        try {
            val displayed = state.value.entries
            val snapshot = withContext(Dispatchers.IO) { savedEntries(displayed.map { it.song }) }
            mutableState.update { if (it.entries === displayed) it.copy(entries = snapshot) else it }
            val index = if (songId == null) 0 else snapshot.indexOfFirst { it.song.id == songId }
            if (index !in snapshot.indices) {
                logger.warning("DownloadedTracksModel", "[play] Файлы выбранной песни недоступны: источник=$source, осталось=${snapshot.size}")
                return false
            }
            wave.stopObserving()
            val started = player.tryPlayQueue(snapshot.map { it.song }, index, DownloadedTracklist(source, title, snapshot))
            logger.info("DownloadedTracksModel", "[play] Источник=$source, треков=${snapshot.size}, индекс=$index, запущено=$started")
            if (!started) refresh()
            return started
        } finally {
            mutablePlaying.value = false
        }
    }
}

/** Отбирает доступные без сети экземпляры; «Всё сразу» включает видимые локальные файлы. */
internal fun downloadedTrackEntries(
    songs: List<Song>,
    source: HomeMusicSource,
    isYandexSaved: (String) -> Boolean,
    isVkSaved: (String) -> Boolean,
    isLocalReadable: (LocalTrackEntity) -> Boolean = { false },
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
                is TrackInstance.Local -> (source == HomeMusicSource.Local || source == HomeMusicSource.All) &&
                    !instance.track.isHidden && isLocalReadable(instance.track)
            }
        }
        val preferred = instances.firstOrNull { it.id == song.preferredInstanceId } ?: instances.firstOrNull()
            ?: return@mapNotNull null
        val origin = when (preferred) {
            is TrackInstance.Yandex -> MusicSource.YANDEX
            is TrackInstance.Vk -> MusicSource.VK
            is TrackInstance.Local -> MusicSource.LOCAL
        }
        MixedTracklistEntry(song.copy(instances = instances, preferredInstanceId = preferred.id), origin)
    }.sortedWith(compareBy<MixedTracklistEntry> { it.song.title.lowercase() }.thenBy { it.song.id })
}

/** Офлайн-очередь файлов и полного кеша: запрещает сеть, сохраняет происхождение после shuffle. */
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
    /** Сеть не используется даже до автоматического перехода плеера в офлайн. */
    override fun offlineOnly(): Boolean = true
    /** Возвращает источник выбранного сохранённого экземпляра. */
    override fun originOf(songId: String): MusicSource? = origins[songId]
}
