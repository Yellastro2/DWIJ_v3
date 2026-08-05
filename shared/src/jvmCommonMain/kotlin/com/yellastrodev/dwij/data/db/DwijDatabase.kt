package com.yellastrodev.dwij.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.yellastrodev.dwij.data.dao.LocalLibraryDao
import com.yellastrodev.dwij.data.dao.SongDao
import com.yellastrodev.dwij.data.dao.SongMatchDao
import com.yellastrodev.dwij.data.dao.dPlaylistDao
import com.yellastrodev.dwij.data.dao.dTrackDao
import com.yellastrodev.dwij.data.db.DatabaseMigrations.Companion.MIGRATION_3_4
import com.yellastrodev.dwij.data.db.DatabaseMigrations.Companion.MIGRATION_4_5
import com.yellastrodev.dwij.data.db.DatabaseMigrations.Companion.MIGRATION_5_6
import com.yellastrodev.dwij.data.db.DatabaseMigrations.Companion.MIGRATION_6_7
import com.yellastrodev.dwij.data.db.DatabaseMigrations.Companion.MIGRATION_7_8
import com.yellastrodev.dwij.data.db.DatabaseMigrations.Companion.MIGRATION_8_9
import com.yellastrodev.dwij.data.entities.LocalLibraryStateEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.SongEntity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstanceEntity
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dTrackAlbumCrossRef
import com.yellastrodev.dwij.data.entities.dTrackArtistCrossRef
import com.yellastrodev.dwij.data.entities.dYaAlbum
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        dYaPlaylist::class,
        dPlaylistTrack::class,
        dYaTrack::class,
        dYaAlbum::class,
        dYaArtist::class,
        dTrackAlbumCrossRef::class,
        dTrackArtistCrossRef::class,
        LocalTrackEntity::class,
        LocalPlaylistEntity::class,
        LocalPlaylistEntryEntity::class,
        LocalLibraryStateEntity::class,
        SongEntity::class,
        TrackInstanceEntity::class,
        SongMatchCandidateEntity::class,
    ],
    version = 9,
)
abstract class DwijDatabase : RoomDatabase() {
    abstract fun dPlaylistDao(): dPlaylistDao
    abstract fun dTrackDao(): dTrackDao
    abstract fun localLibraryDao(): LocalLibraryDao
    abstract fun songDao(): SongDao
    abstract fun songMatchDao(): SongMatchDao
}

fun buildDwijDatabase(
    builder: RoomDatabase.Builder<DwijDatabase>,
): DwijDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9
        )
        .build()
}