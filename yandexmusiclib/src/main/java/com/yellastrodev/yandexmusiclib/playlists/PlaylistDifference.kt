package com.yellastrodev.yandexmusiclib.playlists

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class PlaylistChangeOperation(
    val op: String,
    val at: Int? = null,
    @SerialName("from")
    val fromIndex: Int? = null,
    @SerialName("to")
    val toIndex: Int? = null,
    val tracks: List<PlaylistTrackReference>? = null
)

@Serializable
internal data class PlaylistTrackReference(
    val id: String,
    val albumId: String
)

/**
 * Формирует JSON diff в том же формате, который использует Python SDK.
 */
internal object PlaylistDifference {
    private val json = Json {
        explicitNulls = false
    }

    fun insert(
        at: Int,
        trackId: String,
        albumId: String
    ): String = json.encodeToString(
        listOf(
            PlaylistChangeOperation(
                op = "insert",
                at = at,
                tracks = listOf(
                    PlaylistTrackReference(
                        id = trackId,
                        albumId = albumId
                    )
                )
            )
        )
    )

    fun delete(fromIndex: Int, toIndex: Int): String = json.encodeToString(
        listOf(
            PlaylistChangeOperation(
                op = "delete",
                fromIndex = fromIndex,
                toIndex = toIndex
            )
        )
    )
}
