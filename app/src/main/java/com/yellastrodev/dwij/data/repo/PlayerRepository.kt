package com.yellastrodev.dwij.data.repo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.yellastrodev.dwij.TRACK_ID
import com.yellastrodev.dwij.entities.dYaTrack
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.service.PlayerState
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerRepository(
    private val context: Context,
    private val trackCacheRepo: TrackCacheRepository
) {
    val TAG = "PlayerRepository"

    private var service: PlayerService? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    var currentTrackList: List<String> = listOf()

    private val _currentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = _currentTrack

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlayerService.PlayerBinder).getService()
            // Подписываемся на state сервиса
            service?.state?.onEach { playerState ->
                if (playerState.currentIndex != _state.value.currentIndex) {
                    _currentTrack.value = currentTrackList[playerState.currentIndex]
//                    loanNextTracks(playerState, _state.value.currentIndex - playerState.currentIndex)
                }
                _state.value = playerState
            }
                ?.launchIn(GlobalScope) // лучше передать свой scope
        }



        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bind() {
        val intent = Intent(context, PlayerService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(context, intent)
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
    suspend fun playQueue(tracks: List<dYaTrack>, startIndex: Int = 0) {
        tracksAndUrls = tracks.associate { track -> track.id to track  }
        currentTrackList = tracks.map { track -> track.id }
        _currentTrack.value = tracks[startIndex].id

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

    /**
     * Подгружаем следующий трек заранее, если он есть
     * @param stepTo 1 - следующий, -1 - предыдущий
     */
    private suspend fun loanNextTracks( stepTo: Int) {
        // Подгружаем следующий трек заранее, если он есть
        val nextIndex = relativeIndex + stepTo
        if (nextIndex < currentTrackList.size && nextIndex >= 0) {

            val nextTrackId = currentTrackList[nextIndex]
            val track = tracksAndUrls[nextTrackId]!!
            val nextUri = trackCacheRepo.getOrDownload(nextTrackId)
            val trackMedia = MediaItem.Builder()
                .setUri(nextUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setExtras(Bundle().apply { putString(TRACK_ID, track.id) })
                        .setTitle(track.title ?: "Unknown title")
                        .setArtist(track.artists.joinToString(", ") { it.name } ?: "Unknown artist")
                        //                            .setArtworkUri(Uri.parse("https://example.com/cover.jpg"))
                        .build()
                )
                .build()
            service?.playTrack(trackMedia) // добавляем в очередь ExoPlayer
            _currentTrack.value = currentTrackList[nextIndex]
            relativeIndex = nextIndex
        }
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
}
