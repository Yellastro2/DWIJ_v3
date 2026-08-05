package com.yellastrodev.dwij

import android.content.Context
import android.preference.PreferenceManager

@Suppress("DEPRECATION")
class AndroidMusicSourceSettings(
    context: Context,
) : MusicSourceSettings {

    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(
            context.applicationContext,
        )

    override fun load(): HomeMusicSource {
        val savedValue = preferences.getString(
            PREFERENCE_KEY,
            HomeMusicSource.Yandex.name,
        )

        return runCatching {
            HomeMusicSource.valueOf(
                savedValue.orEmpty(),
            )
        }.getOrDefault(
            HomeMusicSource.Yandex,
        )
    }

    override fun save(
        source: HomeMusicSource,
    ) {
        preferences
            .edit()
            .putString(
                PREFERENCE_KEY,
                source.name,
            )
            .apply()
    }

    private companion object {
        const val PREFERENCE_KEY =
            "home_music_source"
    }
}