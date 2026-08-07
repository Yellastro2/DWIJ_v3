package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.MusicSourceSettings

/**
 * Постоянное хранение выбранного источника музыки.
 */
class StoredMusicSourceSettings(
    private val storage: LocalKeyValueStore,
) : MusicSourceSettings {

    override fun load(): HomeMusicSource {
        val savedValue =
            storage.getString(KEY_MUSIC_SOURCE)
                ?: return DEFAULT_SOURCE

        return runCatching {
            HomeMusicSource.valueOf(savedValue)
        }.getOrDefault(DEFAULT_SOURCE)
    }

    override fun save(
        source: HomeMusicSource,
    ) {
        storage.edit {
            putString(
                KEY_MUSIC_SOURCE,
                source.name,
            )
        }
    }

    private companion object {
        const val KEY_MUSIC_SOURCE =
            "home_music_source"

        val DEFAULT_SOURCE =
            HomeMusicSource.Yandex
    }
}