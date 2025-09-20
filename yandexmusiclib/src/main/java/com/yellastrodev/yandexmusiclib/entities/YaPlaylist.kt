package com.yellastrodev.yandexmusiclib.entities

import com.yellastrodev.yandexmusiclib.yUtils.IntOrStringAsStringSerializer
import org.json.JSONObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable()
open class YaPlaylist(
    open val playlistUuid: String,
    val backgroundImageUrl: String? = null,
    val uid: Int,
	@Serializable(with = IntOrStringAsStringSerializer::class)
    val kind: String,
    val title: String,
    val description: String? = null,
    val trackCount: Int,
    val revision: Int,
    val snapshot: Int,
    val visibility: String,
    val collective: Boolean,
    val isBanner: Boolean,
    val isPremiere: Boolean,
    val durationMs: Int? = null,
    @SerialName("ogImage")
	val ogImageUri: String? = null,
    val tracks: List<TrackShort> = listOf(),
	val tags: List<String> = listOf(),
//	val owner: User? = null,
//	val cover: YaCover? = null,
//	val coverWithoutText: YaCover? = null,
//	val madeFor: MadeFor? = null,
	val playCounter: PlayCounter? = null,
//	val idForFrom: GeneratedPlaylistType? = null,
	val urlPart: String? = null,
	val descriptionFormatted: String? = null,
	val backgroundVideoUrl: String? = null
) {
//	private var fullTracks: List<TrackData>? = null
//
//	suspend fun fetchTracks(client: YamApiClient): List<TrackData>? {
//		fullTracks = fullTracks ?: client.tracks(*tracks.map { it.id }.toTypedArray())
//		return fullTracks
//	}

	fun getUrlOgImage(size: CoverSize) = "https://${ogImageUri?.replace("%%", size.toString())}"
	fun getUrlBackgroundImage(size: CoverSize) = "https://${backgroundImageUrl?.replace("%%", size.toString())}"
}


@Serializable
data class PlayCounter(val value: Long, val description: String, val updated: Boolean)

//@Serializable
//data class MadeFor(val userInfo: User, val caseForms: CaseForms)


@Serializable
data class CaseForms(
	@SerialName("accusative")
	val accusative: String,
	@SerialName("dative")
	val dative: String,
	@SerialName("genitive")
	val genitive: String,
	@SerialName("instrumental")
	val instrumental: String,
	@SerialName("nominative")
	val nominative: String,
	@SerialName("prepositional")
	val prepositional: String
)

@Serializable
data class PlaylistId(val uid: Int, val kind: Int)

@Serializable
data class TagResult(val tag: Tag, val ids: List<PlaylistId>)

@Serializable
data class Tag(val id: String, val value: String, val name: String, val ogDescription: String)


@Serializable
data class TrackShort(
	@Serializable(with = IntOrStringAsStringSerializer::class)
	val id: String
)