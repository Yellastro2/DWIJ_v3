package com.yellastrodev.dwij

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единое состояние выбранного музыкального источника.
 *
 * Не знает ничего об Android и способе долговременного хранения.
 */
class MusicSourceSelectionStore(
    private val settings: MusicSourceSettings,
) {
    private val mutableSelectedSource = MutableStateFlow(
        settings.load(),
    )

    val selectedSource: StateFlow<HomeMusicSource> =
        mutableSelectedSource.asStateFlow()

    /**
     * Повторно читает сохранённое значение и публикует его.
     */
    fun restore(): HomeMusicSource {
        val restored = settings.load()

        mutableSelectedSource.value = restored

        return restored
    }

    /**
     * Меняет живое состояние и сохраняет выбор.
     */
    fun select(
        source: HomeMusicSource,
    ) {
        mutableSelectedSource.value = source
        settings.save(source)
    }

    /**
     * Временно меняет состояние, не сохраняя выбор.
     *
     * Используется перед системным запросом разрешения на локальную музыку.
     */
    fun preview(
        source: HomeMusicSource,
    ) {
        mutableSelectedSource.value = source
    }
}