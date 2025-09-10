package com.yellastrodev.yandexmusiclib.entities

import com.yellastrodev.yandexmusiclib.yUtils.IntOrStringAsStringSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
class YaTrack(
    @Serializable(with = IntOrStringAsStringSerializer::class)
    val id: String,
    val title: String,
    val available: Boolean,
    val availableForPremiumUsers: Boolean? = null,
    val availableFullWithoutPermission: Boolean? = null,
//    val availableForOptions: List<Options> = listOf(),
    val durationMs: Int? = null,
    val previewDurationMs: Int? = null,
    val storageDir: String? = null,
    val fileSize: Int? = null,
//    val r128: R128? = null,
    val artists: List<YaArtist>,
    val albums: List<YaAlbum>,
    val trackSource: String? = null,
//    val major: Major? = null,
    @SerialName("ogImage")
    val ogImageUri: String? = null,
    val coverUri: String? = null,
//    val lyricsAvailable: Boolean? = null,
//    val lyricsInfo: LyricsInfo? = null,
//    val derivedColors: DerivedColors? = null,
//    val type: AlbumType? = null,
//    val rememberPosition: Boolean? = null,
//    val trackSharingFlag: TrackSharingFlag? = null,
//    val contentWarning: String? = null
) {
}