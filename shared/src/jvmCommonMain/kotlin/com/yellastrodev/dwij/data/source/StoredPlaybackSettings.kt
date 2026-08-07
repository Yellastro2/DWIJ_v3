package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.playback.PlaybackSettings
import com.yellastrodev.dwij.playback.RepeatMode

/**
 * Постоянное хранение настроек воспроизведения.
 */
class StoredPlaybackSettings(
    private val storage: LocalKeyValueStore,
) : PlaybackSettings {

    override var shuffleEnabled: Boolean
        get() =
            storage.getBoolean(KEY_SHUFFLE_MODE)
                ?: false

        set(value) {
            storage.edit {
                putBoolean(
                    KEY_SHUFFLE_MODE,
                    value,
                )
            }
        }

    override var repeatMode: RepeatMode
        get() =
            when (
                storage.getString(KEY_REPEAT_MODE)
            ) {
                RepeatMode.ALL.name ->
                    RepeatMode.ALL

                else ->
                    RepeatMode.OFF
            }

        set(value) {
            storage.edit {
                putString(
                    KEY_REPEAT_MODE,
                    value.name,
                )
            }
        }

    private companion object {
        const val KEY_SHUFFLE_MODE =
            "shuffle_mode"

        const val KEY_REPEAT_MODE =
            "repeat_mode_v2"
    }
}