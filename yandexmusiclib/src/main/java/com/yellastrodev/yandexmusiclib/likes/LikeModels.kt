package com.yellastrodev.yandexmusiclib.likes

import com.yellastrodev.yandexmusiclib.entities.YaLikeTracklist
import kotlinx.serialization.Serializable

/**
 * Новая ревизия списка любимых треков после добавления или удаления лайка.
 */
@Serializable
data class LikeActionResult(
    val revision: Int
)

/**
 * Вложенный результат endpoint списка понравившихся треков.
 */
@Serializable
internal data class LikedTracksResult(
    val library: YaLikeTracklist
)
