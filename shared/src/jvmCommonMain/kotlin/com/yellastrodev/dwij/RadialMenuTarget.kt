package com.yellastrodev.dwij

import com.yellastrodev.dwij.data.entities.MusicSource

/** Повторяемое назначение сектора: при запуске получает актуальное содержимое, без снимка очереди. */
sealed interface RadialMenuTarget {
    /** Встроенная коллекция текущего аккаунта или устройства, заново разрешаемая при запуске. */
    data class Collection(val kind: RadialMenuCollection) : RadialMenuTarget

    /**
     * Ссылка на существующий плейлист: LOCAL — playlistId, YANDEX — playlistUuid, VK — fullId.
     * Это ключ исходного объекта, а не временный getdId() запущенной VK-очереди.
     * VK-ключи доступа получает репозиторий при загрузке плейлиста из библиотеки.
     * title — последняя известная подпись, она не определяет состав списка.
     */
    data class Playlist(val source: MusicSource, val key: String, val title: String? = null) : RadialMenuTarget {
        init {
            require(key.isNotBlank()) { "Не указан ключ плейлиста" }
        }
    }

    /**
     * Исходный seed Rotor: user:onyourwave, ID станции, track:id, artist:id или playlist:uid_kind.
     * Назначение запускает новую волну; batchId и состояние прежней сессии не сохраняются.
     * title сохраняется только для подписи сектора.
     */
    data class YandexWave(val seed: String, val title: String? = null) : RadialMenuTarget {
        init {
            require(seed.isNotBlank()) { "Не указан seed Яндекс-волны" }
        }
    }
}

/** Встроенные сценарии приложения; сохранённые имена являются частью формата настроек. */
enum class RadialMenuCollection {
    LOCAL_TRACKS,
    YANDEX_LIKED,
    YANDEX_TRACKS,
    YANDEX_DAILY,
    VK_MY_TRACKS,
    VK_ALL_TRACKS,
    VK_RECOMMENDATIONS,
    MIXED_RECOMMENDATIONS,
}

/** Число назначаемых секторов; фиксированный пункт «Настроить» в хранилище не входит. */
internal const val RADIAL_MENU_TARGET_COUNT = 5

/** Индекс центральной кнопки только в режиме выбора; не входит в список пяти секторов. */
internal const val RADIAL_MENU_PRIMARY_SELECTION = -1

/** Начальное действие короткого нажатия при пустом плеере — личная Яндекс-волна. */
internal fun defaultRadialPrimaryTarget(): RadialMenuTarget =
    RadialMenuTarget.YandexWave("user:onyourwave", "Моя волна")

/** Возвращает пять начальных назначений в порядке секторов, без обращений к аккаунтам и сети. */
internal fun defaultRadialMenuTargets(): List<RadialMenuTarget> = listOf(
    RadialMenuTarget.Collection(RadialMenuCollection.LOCAL_TRACKS),
    RadialMenuTarget.Collection(RadialMenuCollection.YANDEX_DAILY),
    RadialMenuTarget.Collection(RadialMenuCollection.YANDEX_LIKED),
    RadialMenuTarget.Collection(RadialMenuCollection.VK_RECOMMENDATIONS),
    RadialMenuTarget.Collection(RadialMenuCollection.VK_ALL_TRACKS),
)
