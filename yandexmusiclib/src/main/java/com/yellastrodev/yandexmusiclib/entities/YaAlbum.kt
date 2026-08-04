package com.yellastrodev.yandexmusiclib.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class YaAlbum(
    val id: Int,
    val title: String,
    val artists: List<YaArtist> = emptyList(),
    val genre: String? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
    val likesCount: Long? = null,
    @SerialName("ogImage")
    val ogImageUri: String? = null,
    val coverUri: String? = null,
)
