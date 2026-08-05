package com.yellastrodev.dwij.playback

import android.content.Context
import android.preference.PreferenceManager

class AndroidPlaybackSettings(
    context: Context,
) : PlaybackSettings {

    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(
            context.applicationContext,
        )

    override var shuffleEnabled: Boolean
        get() = preferences.getBoolean(
            KEY_SHUFFLE_MODE,
            false,
        )
        set(value) {
            preferences.edit()
                .putBoolean(KEY_SHUFFLE_MODE, value)
                .apply()
        }

    override var repeatMode: RepeatMode
        get() {
            return when (
                preferences.getString(
                    KEY_REPEAT_MODE,
                    RepeatMode.OFF.name,
                )
            ) {
                RepeatMode.ALL.name -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
        }
        set(value) {
            preferences.edit()
                .putString(KEY_REPEAT_MODE, value.name)
                .apply()
        }

    private companion object {
        const val KEY_SHUFFLE_MODE = "shuffle_mode"
        const val KEY_REPEAT_MODE = "repeat_mode_v2"
    }
}