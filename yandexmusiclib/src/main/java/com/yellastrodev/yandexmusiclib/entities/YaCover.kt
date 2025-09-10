package com.yellastrodev.yandexmusiclib.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YaCover(
    val type: CoverType,
    val uri: String? = null,
    val prefix: String? = null,
    val dir: String? = null,
    val version: String? = null,
    val custom: Boolean? = null
) {


    fun getUrl(size: CoverSize) = "https://${uri!!.replace("%%", size.toString())}"
}

enum class CoverSize {
    `50x50`, `100x100`, `200x200`, `400x400`, `600x600`, `800x800`, `1000x1000`
}

@Serializable
enum class CoverType {
    @SerialName("pic")
    Picture,

    @SerialName("from-artist-photos")
    FromArtistPhotos,

    @SerialName("from-album-cover")
    FromAlbumCover,

    @SerialName("mosaic")
    Mosaic
}