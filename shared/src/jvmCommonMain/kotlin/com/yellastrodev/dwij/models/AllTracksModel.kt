package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.WaveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Объединяет готовые фонотеки и удерживает случайный порядок на время жизни маршрута. */
class AllTracksModel(
    localSongs: Flow<List<Song>>,
    yandexSongs: Flow<List<Song>>,
    vkSongs: Flow<List<Song>>,
    private val player: PlayerRepository,
    private val wave: WaveRepository,
) : ViewModel() {
    private val order = LibraryShuffleOrder()
    private val mutableEntries = MutableStateFlow<List<MixedTracklistEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutablePlaying = MutableStateFlow(false)
    val playing = mutablePlaying.asStateFlow()

    init {
        viewModelScope.launch {
            combine(localSongs, yandexSongs, vkSongs) { local, yandex, vk ->
                Triple(local, yandex, vk)
            }.collect { (local, yandex, vk) ->
                mutableEntries.value = withContext(Dispatchers.Default) { order.update(local, yandex, vk) }
                mutableReady.value = true
            }
        }
    }

    /** Запускает снимок показанного списка по Song.id; дальнейшая догрузка не меняет очередь. */
    suspend fun play(songId: String?, title: String): Boolean {
        if (mutablePlaying.value) return false
        val snapshot = entries.value
        val index = if (songId == null) 0 else snapshot.indexOfFirst { it.song.id == songId }
        if (index !in snapshot.indices) return false
        mutablePlaying.value = true
        try {
            wave.stopObserving()
            player.playQueue(snapshot.map { it.song }, index, LibraryTracklist(title, snapshot))
            return true
        } finally {
            mutablePlaying.value = false
        }
    }
}

/** Выдаёт песне случайный ранг один раз; обновление метаданных и догрузка не тасуют старые строки. */
internal class LibraryShuffleOrder(private val random: Random = Random.Default) {
    private val ranks = mutableMapOf<String, Long>()

    /** Собирает полное объединение, убирая только уже связанные дубли по каноническому Song.id. */
    fun update(local: List<Song>, yandex: List<Song>, vk: List<Song>): List<MixedTracklistEntry> {
        val entries = (local.map { MixedTracklistEntry(it, MusicSource.LOCAL) } +
            yandex.map { MixedTracklistEntry(it, MusicSource.YANDEX) } +
            vk.map { MixedTracklistEntry(it, MusicSource.VK) }).distinctBy { it.song.id }
        entries.forEach { ranks.getOrPut(it.song.id) { random.nextLong() } }
        return entries.sortedWith(compareBy<MixedTracklistEntry> { ranks.getValue(it.song.id) }.thenBy { it.song.id })
    }
}

/** Внутренняя очередь фонотеки с происхождением песен; не запускает ЯМ-волну и допускает обычный shuffle/repeat. */
private class LibraryTracklist(private val title: String, entries: List<MixedTracklistEntry>) : dTracklist {
    private val origins = entries.associate { it.song.id to it.origin }
    /** Отделяет общую фонотеку от серверных плейлистов. */
    override fun getdId(): String = "mixed:library"
    /** Название общей фонотеки для плеера. */
    override fun getDTitle(): String = title
    /** Внутренний тип смешанной очереди. */
    override fun getType(): String = "mixed"
    /** Общая фонотека не имеет станции Яндекса. */
    override fun getWaveId(): String = ""
    /** Происхождение остаётся связано с Song.id после перестановки очереди. */
    override fun originOf(songId: String): MusicSource? = origins[songId]
}
