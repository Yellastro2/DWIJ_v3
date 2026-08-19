package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Источник конкретного экземпляра логической песни. */
enum class MusicSource {
    YANDEX,
    LOCAL,
}

/** Компактная каноническая метадата логической песни в Room. */
@Entity(
    tableName = "songs",
    indices = [
        Index("matchKey"),
        Index("matchResolverVersion"),
    ],
)
data class SongEntity(
    @PrimaryKey val songId: String,
    val matchKey: String,
    val title: String,
    val artistNames: String,
    val albumTitle: String?,
    val durationMs: Long?,
    val coverUri: String?,
    val preferredInstanceId: String?,
    val matchResolverVersion: Int = 0,
    /** Трек представлен в пользовательской фонотеке только локальным файлом. */
    val isLocalOnlyInLibrary: Boolean = false,
)

/** Связывает [SongEntity] с подробной записью конкретного музыкального источника. */
@Entity(
    tableName = "track_instances",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("songId"),
        Index(value = ["source", "sourceTrackId"], unique = true),
    ],
)
data class TrackInstanceEntity(
    @PrimaryKey val instanceId: String,
    val songId: String,
    val source: String,
    val sourceTrackId: String,
)

/** Полностью собранная песня, с которой работают экраны и очередь плеера. */
data class Song(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val albums: List<Album>,
    val durationMs: Long?,
    val coverUri: String?,
    val instances: List<TrackInstance>,
    val preferredInstanceId: String?,
    val hasPendingMatchCandidate: Boolean,
    /** ЯМ-инстанс известен как зеркало, но сам трек хранится в фонотеке локально. */
    val isLocalOnlyInLibrary: Boolean,
    /** Производный статус из локального Room-списка лайков. */
    val isLiked: Boolean,
) {
    val artistNames: List<String>
        get() = artists.map(Artist::name)

    val albumTitle: String?
        get() = albums.firstOrNull()?.title

    val yandexInstances: List<TrackInstance.Yandex>
        get() = instances.filterIsInstance<TrackInstance.Yandex>()

    val localInstances: List<TrackInstance.Local>
        get() = instances.filterIsInstance<TrackInstance.Local>()
}

/** Канонический артист внутри агрегированной музыкальной библиотеки. */
data class Artist(
    val id: String,
    val name: String,
)

/** Канонический альбом внутри агрегированной музыкальной библиотеки. */
data class Album(
    val id: String,
    val title: String,
    val year: Int? = null,
    val coverUri: String? = null,
)

/** Подробная source-сущность, привязанная к одной логической [Song]. */
sealed interface TrackInstance {
    val id: String

    data class Yandex(
        override val id: String,
        val track: dYaTrack,
    ) : TrackInstance

    data class Local(
        override val id: String,
        val track: LocalTrackEntity,
    ) : TrackInstance
}

const val SONG_ARTIST_SEPARATOR = "\u001F"
