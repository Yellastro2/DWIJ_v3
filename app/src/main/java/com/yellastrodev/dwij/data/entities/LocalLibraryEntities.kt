package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Источник локального плейлиста, сохранённого в индексе приложения. */
enum class LocalPlaylistOrigin {
    DWIJ,
    MEDIA_STORE,
    M3U,
}

/** Быстрое Room-зеркало аудиозаписи из Android MediaStore. */
@Entity(
    tableName = "local_tracks",
    indices = [
        Index(value = ["contentUri"], unique = true),
        Index(value = ["volumeName", "mediaStoreId"], unique = true),
    ],
)
data class LocalTrackEntity(
    @androidx.room.PrimaryKey
    val instanceId: String,
    val mediaStoreId: Long,
    val volumeName: String,
    val contentUri: String,
    val displayName: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val dateModifiedSeconds: Long,
    val relativePath: String?,
    val absolutePath: String?,
)

/** Плейлист из Движа, старого MediaStore API или M3U-файла. */
@Entity(
    tableName = "local_playlists",
    indices = [
        Index(value = ["origin", "externalKey"], unique = true),
    ],
)
data class LocalPlaylistEntity(
    @androidx.room.PrimaryKey
    val playlistId: String,
    val name: String,
    val origin: String,
    val externalKey: String,
    val externalUri: String?,
    val dateModifiedSeconds: Long,
    val editable: Boolean,
    val exportedHash: String? = null,
)

/**
 * Элемент локального плейлиста. [rawReference] не даёт потерять строку M3U,
 * даже когда соответствующий файл временно отсутствует в MediaStore.
 */
@Entity(
    tableName = "local_playlist_entries",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocalTrackEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["localTrackId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("playlistId"), Index("localTrackId")],
)
data class LocalPlaylistEntryEntity(
    val playlistId: String,
    val position: Int,
    val localTrackId: String?,
    val rawReference: String?,
)

/** Служебные метки последней успешной синхронизации MediaStore. */
@Entity(tableName = "local_library_state")
data class LocalLibraryStateEntity(
    @androidx.room.PrimaryKey
    val key: String,
    val value: String,
)

/** Локальный треклист для общей очереди Media3. */
data class LocalTracklist(
    val id: String,
    val name: String,
) : dTracklist {
    override fun getdId(): String = id
    override fun getDTitle(): String = name
    override fun getType(): String = TYPE
    override fun getWaveId(): String = ""

    companion object {
        const val TYPE = "local_tracklist"
    }
}
