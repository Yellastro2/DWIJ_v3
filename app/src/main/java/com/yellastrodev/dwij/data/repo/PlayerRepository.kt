package com.yellastrodev.dwij.data.repo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.yellastrodev.dwij.PlayerService
import com.yellastrodev.dwij.PlayerState
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

@OptIn(UnstableApi::class)
class PlayerRepository(
    private val context: Context
) {

    private var service: PlayerService? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlayerService.PlayerBinder).getService()
            // Подписываемся на state сервиса
            service?.state?.onEach { _state.value = it }
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

    // API для VM
    fun playQueue(tracks: List<MediaItem>, startIndex: Int = 0) {
        service?.playQueue(tracks, startIndex)
    }

    fun pause() = service?.pause()
    fun resume() = service?.resume()
    fun skipNext() = service?.skipNext()
    fun skipPrev() = service?.skipPrev()
}
