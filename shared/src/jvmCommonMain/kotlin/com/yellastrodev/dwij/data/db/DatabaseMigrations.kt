package com.yellastrodev.dwij.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

class DatabaseMigrations {

    companion object {

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_tracks_v4 (
                        playlistUuid TEXT NOT NULL,
                        trackId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(playlistUuid, position),
                        FOREIGN KEY(playlistUuid)
                            REFERENCES playlists(playlistUuid)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    INSERT INTO playlist_tracks_v4 (
                        playlistUuid,
                        trackId,
                        position
                    )
                    SELECT
                        current.playlistUuid,
                        current.trackId,
                        CASE
                            WHEN current.position IS NOT NULL
                                THEN current.position
                            ELSE COALESCE((
                                SELECT MAX(position) + 1
                                FROM playlist_tracks positioned
                                WHERE positioned.playlistUuid = current.playlistUuid
                                  AND positioned.position IS NOT NULL
                            ), 0) + (
                                SELECT COUNT(*) - 1
                                FROM playlist_tracks previous
                                WHERE previous.playlistUuid = current.playlistUuid
                                  AND previous.position IS NULL
                                  AND previous.rowid <= current.rowid
                            )
                        END
                    FROM playlist_tracks current
                    """.trimIndent()
                )
                connection.execSQL("DROP TABLE playlist_tracks")
                connection.execSQL(
                    "ALTER TABLE playlist_tracks_v4 RENAME TO playlist_tracks"
                )
                connection.execSQL(
                    "CREATE INDEX index_playlist_tracks_playlistUuid " +
                            "ON playlist_tracks(playlistUuid)"
                )
                connection.execSQL(
                    "CREATE INDEX index_playlist_tracks_trackId " +
                            "ON playlist_tracks(trackId)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_tracks (
                        instanceId TEXT NOT NULL PRIMARY KEY,
                        mediaStoreId INTEGER NOT NULL,
                        volumeName TEXT NOT NULL,
                        contentUri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT,
                        album TEXT,
                        albumId INTEGER,
                        durationMs INTEGER NOT NULL,
                        trackNumber INTEGER,
                        discNumber INTEGER,
                        year INTEGER,
                        mimeType TEXT,
                        sizeBytes INTEGER,
                        dateModifiedSeconds INTEGER NOT NULL,
                        relativePath TEXT,
                        absolutePath TEXT
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_local_tracks_contentUri " +
                            "ON local_tracks(contentUri)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_local_tracks_volumeName_mediaStoreId " +
                            "ON local_tracks(volumeName, mediaStoreId)"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_playlists (
                        playlistId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        externalKey TEXT NOT NULL,
                        externalUri TEXT,
                        dateModifiedSeconds INTEGER NOT NULL,
                        editable INTEGER NOT NULL,
                        exportedHash TEXT
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_local_playlists_origin_externalKey " +
                            "ON local_playlists(origin, externalKey)"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_playlist_entries (
                        playlistId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        localTrackId TEXT,
                        rawReference TEXT,
                        PRIMARY KEY(playlistId, position),
                        FOREIGN KEY(playlistId) REFERENCES local_playlists(playlistId)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(localTrackId) REFERENCES local_tracks(instanceId)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_playlist_entries_playlistId " +
                            "ON local_playlist_entries(playlistId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_playlist_entries_localTrackId " +
                            "ON local_playlist_entries(localTrackId)"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_library_state (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS songs (
                        songId TEXT NOT NULL PRIMARY KEY,
                        matchKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artistNames TEXT NOT NULL,
                        albumTitle TEXT,
                        durationMs INTEGER,
                        coverUri TEXT,
                        preferredInstanceId TEXT
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_songs_matchKey " +
                            "ON songs(matchKey)"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_instances (
                        instanceId TEXT NOT NULL PRIMARY KEY,
                        songId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        sourceTrackId TEXT NOT NULL,
                        FOREIGN KEY(songId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_instances_songId " +
                            "ON track_instances(songId)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_track_instances_source_sourceTrackId " +
                            "ON track_instances(source, sourceTrackId)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE tracks ADD COLUMN availabilityCheckedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * `songs` и `track_instances` — производный индекс, поэтому безопасно пересоздаём его,
         * разлепляя все прежние автоматические совпадения. Source-таблицы не затрагиваются.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS song_match_candidates")
                connection.execSQL("DROP TABLE IF EXISTS track_instances")
                connection.execSQL("DROP TABLE IF EXISTS songs")
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS songs (
                        songId TEXT NOT NULL PRIMARY KEY,
                        matchKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artistNames TEXT NOT NULL,
                        albumTitle TEXT,
                        durationMs INTEGER,
                        coverUri TEXT,
                        preferredInstanceId TEXT,
                        matchResolverVersion INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_songs_matchKey ON songs(matchKey)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_songs_matchResolverVersion " +
                            "ON songs(matchResolverVersion)"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_instances (
                        instanceId TEXT NOT NULL PRIMARY KEY,
                        songId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        sourceTrackId TEXT NOT NULL,
                        FOREIGN KEY(songId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_instances_songId " +
                            "ON track_instances(songId)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_track_instances_source_sourceTrackId " +
                            "ON track_instances(source, sourceTrackId)"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS song_match_candidates (
                        firstSongId TEXT NOT NULL,
                        secondSongId TEXT NOT NULL,
                        titleSimilarity REAL NOT NULL,
                        artistSimilarity REAL NOT NULL,
                        score REAL NOT NULL,
                        resolverVersion INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(firstSongId, secondSongId),
                        FOREIGN KEY(firstSongId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(secondSongId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_song_match_candidates_firstSongId " +
                            "ON song_match_candidates(firstSongId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_song_match_candidates_secondSongId " +
                            "ON song_match_candidates(secondSongId)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_song_match_candidates_status " +
                            "ON song_match_candidates(status)"
                )
            }
        }

        /** Пользовательская видимость хранится вместе с локальным индексом и переживает sync. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE local_tracks ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Добавляет канонические объекты каталога и явно source-размеченную метадату ЯМ. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS catalog_artists (" +
                        "artistId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS catalog_artist_metadata (
                        artistId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        externalId TEXT NOT NULL,
                        coverUri TEXT,
                        genres TEXT NOT NULL,
                        likesCount INTEGER,
                        lastMonthListeners INTEGER,
                        lastMonthListenersDelta INTEGER,
                        refreshedAt INTEGER NOT NULL,
                        PRIMARY KEY(artistId, source),
                        FOREIGN KEY(artistId) REFERENCES catalog_artists(artistId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_catalog_artist_metadata_artistId " +
                        "ON catalog_artist_metadata(artistId)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_catalog_artist_metadata_source_externalId " +
                        "ON catalog_artist_metadata(source, externalId)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS catalog_albums (" +
                        "albumId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS catalog_album_metadata (
                        albumId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        externalId TEXT NOT NULL,
                        coverUri TEXT,
                        artistNames TEXT NOT NULL,
                        genre TEXT,
                        releaseDate TEXT,
                        year INTEGER,
                        type TEXT,
                        description TEXT,
                        likesCount INTEGER,
                        trackCount INTEGER,
                        refreshedAt INTEGER NOT NULL,
                        PRIMARY KEY(albumId, source),
                        FOREIGN KEY(albumId) REFERENCES catalog_albums(albumId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_catalog_album_metadata_albumId " +
                        "ON catalog_album_metadata(albumId)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_catalog_album_metadata_source_externalId " +
                        "ON catalog_album_metadata(source, externalId)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS catalog_album_tracks (
                        albumId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        sourceTrackId TEXT NOT NULL,
                        discNumber INTEGER,
                        trackNumber INTEGER,
                        PRIMARY KEY(albumId, source, position),
                        FOREIGN KEY(albumId) REFERENCES catalog_albums(albumId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_catalog_album_tracks_albumId " +
                        "ON catalog_album_tracks(albumId)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_catalog_album_tracks_sourceTrackId " +
                        "ON catalog_album_tracks(sourceTrackId)",
                )
            }
        }

        /** Добавляет известное ЯМ количество треков артиста в source-метадату. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE catalog_artist_metadata ADD COLUMN trackCount INTEGER",
                )
            }
        }
    }
}
