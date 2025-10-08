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
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.service.PlayerEvent
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.service.PlayerState
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerRepository(
    private val context: Context
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
                    if (event is PlayerEvent.TrackListEnd){
                        waveRepository.playWave(dtracklist.value!!)
                    }else
                        _events.emit(event) // пробрасываем в репозиторий
                }
                ?.launchIn(GlobalScope) // лучше свой scope
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

    fun bind() {
        val intent = Intent(context, PlayerService::class.java)
        ContextCompat.startForegroundService(context, intent)
//        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        GlobalScope.launch {
            service = waitForService()
            service?.let { service ->
            service.state.onEach { playerState ->
                if (playerState.currentIndex != _state.value.currentIndex) {
                    _currentTrack.value = currentTrackList[playerState.currentIndex]
                }
                _state.value = playerState
            }.launchIn(this)

            service.events.onEach { event ->
                if (event is PlayerEvent.TrackListEnd) {
                    waveRepository.playWave(dtracklist.value!!)
                } else {
                    _events.emit(event)
                }
            }.launchIn(this)

                withContext(Dispatchers.Main) {
                    applySavedModes()
                }
            }
        }
    }

    fun unbind() {
        context.unbindService(serviceConnection)
    }

//    var tracksAndUrls: Map<String,dYaTrack> = mapOf()
    var relativeIndex = 0

    /**
     * @param tracks список треков и их урл ссылок (на скачивание, либо на кеш файл)
     * @param startIndex индекс трека в списке, который будет проигран
     */
    suspend fun playQueue(
        tracks: List<dYaTrack>,
        startIndex: Int = 0,
        tracklist: dTracklist
    ) {
        Log.d(TAG,"set playQueue()")

        if (tracks[startIndex].id == _currentTrack.value && dtracklist.value?.getdId() == tracklist.getdId())
            return

        if (dtracklist.value?.getdId() == tracklist.getdId()){
            _currentTrack.value = tracks[startIndex].id
            relativeIndex = startIndex
            service?.playTrack(startIndex)
            return
        }
        // сюда доходит логика ток если треклист сменился.
        blockShuffle(tracklist.getType() == dYaWave.YA_WAVE)

        currentTrackList = tracks.map { track -> track.id }
        _currentTrack.value = tracks[startIndex].id
        _dtracklist.value = tracklist

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


    suspend fun addTracks(tracks: List<dYaTrack>) {
        Log.d(TAG, "addTracks: ${tracks.size}")
//        tracksAndUrls = tracks.associate { track -> track.id to track  }
        currentTrackList = currentTrackList + tracks.map { track -> track.id }

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


}
