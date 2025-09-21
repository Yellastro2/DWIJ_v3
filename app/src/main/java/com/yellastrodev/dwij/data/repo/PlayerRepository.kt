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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.yellastrodev.dwij.TRACK_ID
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.service.PlayerEvent
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.service.PlayerState
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

@OptIn(UnstableApi::class)
class PlayerRepository(
    private val context: Context,
    private val trackCacheRepo: TrackCacheRepository
) {
    val TAG = "PlayerRepository"

    private var service: PlayerService? = null

    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    // это пошлый дубликат стейта из PlayerService, но подругому я не придумал потому что здесь в репо
    // мне надо сравнивать их currentIndex что бы менять есличо _currentTrack,
    // а PlayerService.state еще и не сразу доступен
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _playTitle = MutableStateFlow("")
    val playTitle: StateFlow<String> = _playTitle

    var currentTrackList: List<String> = listOf()

    private val _currentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = _currentTrack

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events: SharedFlow<PlayerEvent> = _events

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlayerService.PlayerBinder).getService()
            applySavedModes()
            // Подписываемся на state сервиса
            service?.state?.onEach { playerState ->
                if (playerState.currentIndex != _state.value.currentIndex) {
                    _currentTrack.value = currentTrackList[playerState.currentIndex]
//                    loanNextTracks(playerState, _state.value.currentIndex - playerState.currentIndex)
                }
                _state.value = playerState
            }
                ?.launchIn(GlobalScope) // лучше передать свой scope
            service?.events
                ?.onEach { event ->
                    _events.emit(event) // пробрасываем в репозиторий
                }
                ?.launchIn(GlobalScope) // лучше свой scope
        }



        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bind() {
        val intent = Intent(context, PlayerService::class.java)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        context.unbindService(serviceConnection)
    }

    var tracksAndUrls: Map<String,dYaTrack> = mapOf()
    var relativeIndex = 0

    /**
     * @param tracks список треков и их урл ссылок (на скачивание, либо на кеш файл)
     * @param startIndex индекс трека в списке, который будет проигран
     */
    suspend fun playQueue(
        tracks: List<dYaTrack>,
        startIndex: Int = 0,
        title: String = "noTitle"
    ) {
        Log.d(TAG,"set playQueue()")

        if (tracks[startIndex].id == _currentTrack.value && _playTitle.value == title)
            return

        if (playTitle.value == title){
            _currentTrack.value = tracks[startIndex].id
            relativeIndex = startIndex
            service?.playTrack(startIndex)
            return
        }

        tracksAndUrls = tracks.associate { track -> track.id to track  }
        currentTrackList = tracks.map { track -> track.id }
        _currentTrack.value = tracks[startIndex].id
        _playTitle.value = title

        relativeIndex = startIndex

        Log.d(TAG, "playQueue called: startIndex=$startIndex, tracks=${tracks.size}")

        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id) // без URI — фабрика подставит на лету
                .setUri("ya://${track.id}") // фейковый URI
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setExtras(Bundle().apply { putString(TRACK_ID, track.id) })
                        .setTitle(track.title ?: "Unknown title")
                        .setArtist(track.artists.joinToString(", ") { it.name } ?: "Unknown artist")
                        .build()
                )
                .build()
        }

        Log.d(TAG, "playQueue ready: startIndex=$startIndex, tracks=${tracks.size}")

        service?.playQueue(mediaItems, startIndex)


//        // Абсолютные индексы соседей
//        val prevIndex = startIndex - 1
//        val nextIndex = startIndex + 1
//
//        // 1️⃣ Загружаем текущий трек синхронно
//        val currentTrack = tracks[startIndex]
//        val currentUri = trackCacheRepo.getOrDownload(currentTrack.id)
//        val currentItem = MediaItem.Builder()
//            .setUri(currentUri)
//            .setMediaMetadata(
//                MediaMetadata.Builder()
//                    .setExtras(Bundle().apply { putString(TRACK_ID, currentTrack.id) })
//                    .setTitle(currentTrack.title ?: "Unknown title")
//                    .setArtist(currentTrack.artists.joinToString(", ") { it.name } ?: "Unknown artist")
//                    .build()
//            )
//            .build()
//
//        // Отправляем в сервис сразу для воспроизведения
//        service?.playTrack(currentItem)
//
//        // 2️⃣ Подгружаем соседние треки асинхронно
//        GlobalScope.launch {
//            if (prevIndex >= 0) {
//                val prevTrack = tracks[prevIndex]
//                trackCacheRepo.getOrDownload(prevTrack.id) // просто кешируем
//            }
//            if (nextIndex < tracks.size) {
//                val nextTrack = tracks[nextIndex]
//                trackCacheRepo.getOrDownload(nextTrack.id) // просто кешируем
//            }
//        }
    }

    fun pause() = service?.pause()
    suspend fun skipNext() {
//        loanNextTracks(1)
        service?.skipNext()
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

}
