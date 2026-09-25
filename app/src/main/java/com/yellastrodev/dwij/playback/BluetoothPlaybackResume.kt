package com.yellastrodev.dwij.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Продолжает звук только после возврата Bluetooth-выхода, прервавшего активное воспроизведение. */
class BluetoothPlaybackResume(
    context: Context,
    private val player: ExoPlayer,
) : Player.Listener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var playingOutput: String? = null
    private var interruptedOutput: String? = null
    private var lastRemovedOutput: String? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        /** Запускает отложенную проверку, когда вернулся прежний аудиовыход. */
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val interrupted = interruptedOutput ?: return
            if (addedDevices.none { key(it) == interrupted }) return
            handler.postDelayed({ resumeIfReady(interrupted) }, ROUTE_SETTLE_DELAY_MS)
        }

        /** Помнит исчезнувший выход, даже если callback пришёл раньше события noisy. */
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val active = playingOutput ?: return
            if (removedDevices.any { key(it) == active }) lastRemovedOutput = active
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        player.addListener(this)
        if (player.isPlaying) playingOutput = currentBluetoothOutput()
    }

    /** Сохраняет выход во время игры и отличает отключение от ручной паузы. */
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            interruptedOutput = null
            lastRemovedOutput = null
            playingOutput = currentBluetoothOutput()
            return
        }
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            interruptedOutput = lastRemovedOutput ?: playingOutput
            Log.d(TAG, "[onPlayWhenReadyChanged] Аудиовыход исчез, автопродолжение ожидает его возврата")
        } else {
            interruptedOutput = null
            lastRemovedOutput = null
        }
    }

    /** Обновляет выход после фактического старта, когда Android завершил маршрутизацию. */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) playingOutput = currentBluetoothOutput()
    }

    /** Освобождает callback вместе с экземпляром сервиса. */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        player.removeListener(this)
    }

    /** Продолжает только подготовленную очередь и только через вернувшийся выход. */
    private fun resumeIfReady(expectedOutput: String) {
        if (interruptedOutput != expectedOutput || player.playWhenReady ||
            player.mediaItemCount == 0 || currentBluetoothOutput() != expectedOutput
        ) return
        interruptedOutput = null
        Log.d(TAG, "[resumeIfReady] Вернулся прежний Bluetooth-аудиовыход, продолжаем воспроизведение")
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /** Находит единственный доступный Bluetooth-выход, чтобы не выбирать устройство наугад. */
    private fun currentBluetoothOutput(): String? = audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull(::key)
        .singleOrNull()

    /** Составляет устойчивый ключ Bluetooth-выхода для текущей версии Android. */
    private fun key(device: AudioDeviceInfo): String? {
        if (!device.isSink || device.type !in BLUETOOTH_OUTPUT_TYPES) return null
        val identity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address.takeIf(String::isNotBlank)
        } else null
        return "${device.type}:${identity ?: device.productName}"
    }

    private companion object {
        const val TAG = "BluetoothPlaybackResume"
        const val ROUTE_SETTLE_DELAY_MS = 800L
        val BLUETOOTH_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
