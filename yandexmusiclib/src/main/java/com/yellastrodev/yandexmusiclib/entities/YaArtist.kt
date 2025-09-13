package com.yellastrodev.yandexmusiclib.entities

import kotlinx.serialization.Serializable

@Serializable
data class YaArtist (
    val id: Int? = null,
    val name: String,
//    val cover: Cover? = null,
    val various: Boolean? = null,
    val composer: Boolean? = null,
    val genres: List<String>? = null
)