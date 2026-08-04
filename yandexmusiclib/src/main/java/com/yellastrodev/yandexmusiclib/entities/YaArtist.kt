package com.yellastrodev.yandexmusiclib.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YaArtist (
    val id: Int? = null,
    val name: String,
    val cover: YaCover? = null,
    @SerialName("ogImage")
    val ogImageUri: String? = null,
    val counts: YaArtistCounts? = null,
    val likesCount: Long? = null,
    val various: Boolean? = null,
    val composer: Boolean? = null,
    val genres: List<String>? = null
)

/** Количество контента артиста, возвращаемое общим поиском. */
@Serializable
data class YaArtistCounts(
    val tracks: Int? = null,
    val directAlbums: Int? = null,
    val alsoAlbums: Int? = null,
    val alsoTracks: Int? = null,
)
