package com.yellastrodev.dwij

import android.content.Context
import android.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единая память выбранного источника для всех экранов приложения.
 *
 * [selectedSource] синхронизирует уже открытые Compose-экраны, а SharedPreferences
 * восстанавливает тот же выбор после уничтожения процесса и нового запуска приложения.
 */
internal object MusicSourceSelectionStore {
    private const val PREFERENCE_KEY = "home_music_source"

    private val mutableSelectedSource = MutableStateFlow(HomeMusicSource.Yandex)
    val selectedSource: StateFlow<HomeMusicSource> = mutableSelectedSource.asStateFlow()

    /** Читает сохранённое значение и сразу публикует его всем активным экранам. */
    fun restore(context: Context): HomeMusicSource {
        val saved = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getString(PREFERENCE_KEY, HomeMusicSource.Yandex.name)
        val restored = runCatching { HomeMusicSource.valueOf(saved.orEmpty()) }
            .getOrDefault(HomeMusicSource.Yandex)
        mutableSelectedSource.value = restored
        return restored
    }

    /** Обновляет живое состояние и долговременную настройку одной атомарной операцией UI. */
    fun select(context: Context, source: HomeMusicSource) {
        mutableSelectedSource.value = source
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putString(PREFERENCE_KEY, source.name)
            .apply()
    }

    /** Временно отражает выбор до ответа системного диалога разрешений, не сохраняя его. */
    fun preview(source: HomeMusicSource) {
        mutableSelectedSource.value = source
    }
}
