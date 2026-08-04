package com.yellastrodev.dwij.data.repo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.yellastrodev.dwij.TRACK_ID
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.dwij.service.DEFAULT_PLAY_AUDIO_SOURCE
import com.yellastrodev.dwij.service.PLAY_AUDIO_ALBUM_ID
import com.yellastrodev.dwij.service.PLAY_AUDIO_DURATION_MS
import com.yellastrodev.dwij.service.PLAY_AUDIO_ITEM_ID
import com.yellastrodev.dwij.service.PLAY_AUDIO_PLAYLIST_ID
import com.yellastrodev.dwij.service.PLAY_AUDIO_SOURCE
import com.yellastrodev.dwij.service.PLAYBACK_MUSIC_SOURCE
import com.yellastrodev.dwij.service.PLAYBACK_SOURCE_LOCAL
import com.yellastrodev.dwij.service.PLAYBACK_SOURCE_YANDEX
import com.yellastrodev.dwij.service.PlayerEvent
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.service.PlayerState
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(UnstableApi::class)
class PlayerRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isTrackCached: (trackId: String) -> Boolean,
) {
    val TAG = "PlayerRepository"

    private var service: PlayerService? = null

    private val playerService: PlayerService?
        get() = (context.applicationContext as yApplication).playerServiceRef?.get()

    lateinit var waveRepository: WaveRepository

    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    // это пошлый дубликат стейта из PlayerService, но подругому я не придумал потому что здесь в репо
    // мне надо сравнивать их currentIndex что бы менять есличо _currentTrack,
    // а PlayerService.state еще и не сразу доступен
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _dtracklist = MutableStateFlow(null as dTracklist?)
    val dtracklist: StateFlow<dTracklist?> = _dtracklist

    var currentTrackList: List<String> = listOf()
    private var currentSongQueue: List<Song> = emptyList()
    private var currentPlaybackQueue: List<PlaybackTrack> = emptyList()

    private val _currentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = _currentTrack

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _currentPlaybackTrack = MutableStateFlow<PlaybackTrack?>(null)
    val currentPlaybackTrack: StateFlow<PlaybackTrack?> = _currentPlaybackTrack

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events: SharedFlow<PlayerEvent> = _events

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlayerService.PlayerBinder).getService()
            applySavedModes()
            // Подписываемся на state сервиса
            service?.state?.onEach { playerState ->
                if (playerState.currentIndex != _state.value.currentIndex) {
                    updateCurrentTrack(playerState.currentIndex)
//                    loanNextTracks(playerState, _state.value.currentIndex - playerState.currentIndex)
                }
                _state.value = playerState
            }
                ?.launchIn(scope)
            service?.events
                ?.onEach { event ->
                if (event is PlayerEvent.TrackListEnd){
                    val tracklist = dtracklist.value
                    if (tracklist != null && tracklist.getType() != LocalTracklist.TYPE) {
                        waveRepository.playWave(tracklist)
                    } else {
                        _events.emit(event)
                    }
                    }else
                        _events.emit(event) // пробрасываем в репозиторий
                }
                ?.launchIn(scope)
        }



        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    suspend fun waitForService(): PlayerService {
        while (true) {
            val service = (context.applicationContext as yApplication).playerServiceRef?.get()
            if (service != null) return service
            delay(100) // не заблокирует основной поток
        }
    }

    suspend fun bind() {
        Log.d(TAG, "bind called")
        val intent = Intent(context, PlayerService::class.java)
//        ContextCompat.startForegroundService(context, intent)
        context.startService(intent)
//        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            Log.d(TAG, "waitForService called")
            service = waitForService()
            Log.d(TAG, "waitForService returned")
            service?.let { service ->
                Log.d(TAG, "waitForService returned ненулевой плеер, привязываем флоуы")
            service.state.onEach { playerState ->
                if (playerState.currentIndex != _state.value.currentIndex) {
                    updateCurrentTrack(playerState.currentIndex)
                }
                _state.value = playerState
            }.launchIn(scope)

            service.events.onEach { event ->
                if (event is PlayerEvent.TrackListEnd) {
                    val tracklist = dtracklist.value
                    if (tracklist != null && tracklist.getType() != LocalTracklist.TYPE) {
                        waveRepository.playWave(tracklist)
                    } else {
                        _events.emit(event)
                    }
                } else {
                    _events.emit(event)
                }
            }.launchIn(scope)

                withContext(Dispatchers.Main) {
                    applySavedModes()
                }
            }
    }

    fun unbind() {
        context.unbindService(serviceConnection)
    }

//    var tracksAndUrls: Map<String,dYaTrack> = mapOf()
    var relativeIndex = 0

    /**
     * Запускает очередь логических песен, выбирая для каждой воспроизводимый экземпляр.
     * [startIndex] переводится из исходного списка в индекс уже отфильтрованной очереди.
     */
    suspend fun playQueue(
        songs: List<Song>,
        startIndex: Int = 0,
        tracklist: dTracklist
    ) {
        if (songs.isEmpty() || startIndex !in songs.indices) {
            Log.w(
                TAG,
                "[playQueue] Некорректная исходная очередь: size=${songs.size}, " +
                    "startIndex=$startIndex",
            )
            return
        }
        val playableSongs = withContext(Dispatchers.IO) {
            songs.mapIndexedNotNull { index, song ->
                song.toPlaybackTrack(isTrackCached)?.let { track ->
                    IndexedValue(index, song to track)
                }
            }
        }
        if (playableSongs.isEmpty()) {
            Log.w(
                TAG,
                "[playQueue] В очереди нет воспроизводимых экземпляров",
            )
            return
        }
        val resolvedStartIndex = playableSongs
            .indexOfFirst { indexedSong -> indexedSong.index == startIndex }
            .takeIf { index -> index >= 0 }
            ?: playableSongs.indexOfFirst { indexedSong -> indexedSong.index > startIndex }
                .takeIf { index -> index >= 0 }
            ?: 0
        val skippedCount = songs.size - playableSongs.size
        if (skippedCount > 0) {
            Log.d(
                TAG,
                "[playQueue] Пропущено песен без воспроизводимых экземпляров=$skippedCount, " +
                    "исходный index=$startIndex, итоговый index=$resolvedStartIndex",
            )
        }
        playPreparedQueue(
            songs = playableSongs.map { it.value.first },
            tracks = playableSongs.map { it.value.second },
            startIndex = resolvedStartIndex,
            tracklist = tracklist,
        )
    }

    private suspend fun playPreparedQueue(
        songs: List<Song>,
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist,
    ) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) {
            Log.w(
                TAG,
                "[playQueue] Некорректная очередь: size=${tracks.size}, " +
                    "startIndex=$startIndex"
            )
            return
        }
        Log.d(TAG, "[playQueue] Подготовка очереди")

        if (service == null){

            Log.d(TAG, "[playQueue] Сервис плеера ещё не подключён")
            bind()
        }

        if (dtracklist.value?.getdId() == tracklist.getdId()){
            Log.d(TAG, "[playQueue] Треклист не изменился")
            val newIds = songs.map { it.id }
            val sameInstances = currentPlaybackQueue.map(PlaybackTrack::instanceId) ==
                tracks.map(PlaybackTrack::instanceId)
            if (currentTrackList == newIds && sameInstances) {
                if (_state.value.currentIndex == startIndex) {
                    Log.d(TAG, "[playQueue] Выбранная позиция очереди уже играет")
                    return
                }
                _currentTrack.value = songs[startIndex].id
                _currentSong.value = songs[startIndex]
                _currentPlaybackTrack.value = currentPlaybackQueue.getOrNull(startIndex)
                relativeIndex = startIndex
                service?.playTrack(startIndex)
                Log.d(
                    TAG,
                    "[playQueue] Переключаем текущую очередь на index=$startIndex, " +
                        "songId=${songs[startIndex].id}"
                )
                return
            }
            Log.d(TAG, "[playQueue] Состав текущего треклиста изменился")
        }
        // сюда доходит логика ток если треклист сменился.
        blockShuffle(tracklist.getType() == dYaWave.YA_WAVE)

        currentTrackList = songs.map(Song::id)
        currentSongQueue = songs
        currentPlaybackQueue = tracks
        _currentTrack.value = songs[startIndex].id
        _currentSong.value = songs[startIndex]
        _currentPlaybackTrack.value = tracks[startIndex]
        _dtracklist.value = tracklist

        relativeIndex = startIndex

        Log.d(
            TAG,
            "[playQueue] Формируем очередь: size=${tracks.size}, " +
                "startIndex=$startIndex, songId=${songs[startIndex].id}, " +
                "instanceId=${tracks[startIndex].instanceId}"
        )

        val mediaItems = tracks.map { track ->
            track.toMediaItem(tracklist)
        }

        Log.d(TAG, "[playQueue] Очередь готова, передаём в сервис")

        service?.playQueue(mediaItems, startIndex)
    }

//    var isShuffleBlock = false
    private val _isShuffleBlock = MutableStateFlow(false)
    val isShuffleBlock: StateFlow<Boolean> = _isShuffleBlock
    private fun blockShuffle(isWave: Boolean) {
        if (isShuffleBlock.value != isWave) {
            _isShuffleBlock.value = isWave
            if (isWave) {
                service?.player?.shuffleModeEnabled = false
                service?.player?.repeatMode = Player.REPEAT_MODE_OFF
            } else {
                applySavedModes()
            }
        }
    }


    /** Догружает в текущую очередь песни, для которых удалось выбрать экземпляр. */
    suspend fun addTracks(songs: List<Song>) {
        val resolved = withContext(Dispatchers.IO) {
            songs.mapNotNull { song ->
                song.toPlaybackTrack(isTrackCached)?.let { track -> song to track }
            }
        }
        val skippedCount = songs.size - resolved.size
        Log.d(
            TAG,
            "[addTracks] Получено=${songs.size}, добавляем=${resolved.size}, " +
                "пропущено=$skippedCount",
        )
        if (resolved.isEmpty()) return
        val resolvedSongs = resolved.map { it.first }
        val playbackTracks = resolved.map { it.second }
        currentTrackList = currentTrackList + resolvedSongs.map(Song::id)
        currentSongQueue = currentSongQueue + resolvedSongs

        currentPlaybackQueue = currentPlaybackQueue + playbackTracks
        val mediaItems = playbackTracks.map { track ->
            track.toMediaItem(_dtracklist.value)
        }

        withContext(Dispatchers.Main) {
            service?.addTracks(mediaItems)
        }
    }

    fun pause() = service?.pause()
    suspend fun skipNext() {
//        loanNextTracks(1)
        withContext(Dispatchers.Main) {
            service?.skipNext()
        }
    }
    suspend fun skipPrev() {
//        loanNextTracks(-1)
        service?.skipPrev()
    }

    fun seekTo(lng: Long) {
        service?.seekTo(lng)
    }

    fun shuffle() {
        service?.player?.let { player ->
            val newValue = !player.shuffleModeEnabled
            player.shuffleModeEnabled = newValue
            prefs.edit().putBoolean("shuffle_mode", newValue).apply()
        }
    }

    fun rotate() {
        service?.player?.let { player ->
            val newMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_ALL
            }
            player.repeatMode = newMode
            prefs.edit().putInt("repeat_mode", newMode).apply()
        }
    }

    fun applySavedModes() {
        service?.player?.let { player ->
            player.shuffleModeEnabled = prefs.getBoolean("shuffle_mode", false)
            player.repeatMode = prefs.getInt("repeat_mode", Player.REPEAT_MODE_OFF)
        }
    }

    fun List<Song>.stableHash(): Int =
        fold(1) { acc, song -> 31 * acc + song.id.hashCode() }

    /** Добавляет source-aware метаданные и только для Яндекса — данные `/play-audio`. */
    private fun PlaybackTrack.toMediaItem(tracklist: dTracklist?): MediaItem {
        val extras = Bundle().apply {
            putString(TRACK_ID, id)
            putString(
                PLAYBACK_MUSIC_SOURCE,
                if (source == MusicSource.LOCAL) PLAYBACK_SOURCE_LOCAL else PLAYBACK_SOURCE_YANDEX,
            )
            if (source == MusicSource.YANDEX) {
                putString(PLAY_AUDIO_ITEM_ID, UUID.randomUUID().toString())
                yandexTrack?.albums?.firstOrNull()?.id?.let {
                    putString(PLAY_AUDIO_ALBUM_ID, it.toString())
                }
                durationMs?.let { putLong(PLAY_AUDIO_DURATION_MS, it) }
                (tracklist as? dYaPlaylist)?.playlistUuid?.let {
                    putString(PLAY_AUDIO_PLAYLIST_ID, it)
                }
                putString(PLAY_AUDIO_SOURCE, DEFAULT_PLAY_AUDIO_SOURCE)
            }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(playbackUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(extras)
                    .setTitle(title)
                    .setArtist(artistNames.joinToString(", "))
                    .apply {
                        if (source == MusicSource.LOCAL) {
                            artworkUri?.let { setArtworkUri(android.net.Uri.parse(it)) }
                        }
                    }
                    .build()
            )
            .build()
    }

    private fun updateCurrentTrack(index: Int) {
        val song = currentSongQueue.getOrNull(index)
        val current = currentPlaybackQueue.getOrNull(index)
        _currentTrack.value = song?.id
        _currentSong.value = song
        _currentPlaybackTrack.value = current
    }

    /** Заменяет объединённые Song в уже играющей очереди, не перезапуская Media3. */
    fun applyMergedSong(sourceSongIds: Set<String>, mergedSong: Song) {
        if (sourceSongIds.isEmpty()) return
        currentSongQueue = currentSongQueue.map { song ->
            if (song.id in sourceSongIds) mergedSong else song
        }
        currentTrackList = currentTrackList.map { songId ->
            if (songId in sourceSongIds) mergedSong.id else songId
        }
        if (_currentSong.value?.id in sourceSongIds) {
            _currentSong.value = mergedSong
            _currentTrack.value = mergedSong.id
        }
    }

}
