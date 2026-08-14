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

        /**
         * Отделяет внутренний ID артиста от external ID ЯМ и связывает известных артистов
         * с конкретными Yandex-инстансами песен. Локальные строки артистов не разбираются.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE migration_catalog_artist_ids (" +
                        "oldArtistId TEXT NOT NULL PRIMARY KEY, " +
                        "newArtistId TEXT NOT NULL UNIQUE)",
                )
                connection.execSQL(
                    "INSERT INTO migration_catalog_artist_ids(oldArtistId, newArtistId) " +
                        "SELECT artistId, lower(hex(randomblob(16))) FROM catalog_artists",
                )
                connection.execSQL(
                    "CREATE TABLE catalog_artists_new (" +
                        "artistId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE catalog_artist_metadata_new (
                        artistId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        externalId TEXT NOT NULL,
                        coverUri TEXT,
                        genres TEXT NOT NULL,
                        likesCount INTEGER,
                        trackCount INTEGER,
                        lastMonthListeners INTEGER,
                        lastMonthListenersDelta INTEGER,
                        refreshedAt INTEGER NOT NULL,
                        PRIMARY KEY(artistId, source),
                        FOREIGN KEY(artistId) REFERENCES catalog_artists_new(artistId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT INTO catalog_artists_new(artistId, name) " +
                        "SELECT ids.newArtistId, artists.name FROM catalog_artists artists " +
                        "INNER JOIN migration_catalog_artist_ids ids " +
                        "ON ids.oldArtistId = artists.artistId",
                )
                connection.execSQL(
                    """
                    INSERT INTO catalog_artist_metadata_new(
                        artistId, source, externalId, coverUri, genres, likesCount,
                        trackCount, lastMonthListeners, lastMonthListenersDelta, refreshedAt
                    )
                    SELECT ids.newArtistId, metadata.source, metadata.externalId,
                        metadata.coverUri, metadata.genres, metadata.likesCount,
                        metadata.trackCount, metadata.lastMonthListeners,
                        metadata.lastMonthListenersDelta, metadata.refreshedAt
                    FROM catalog_artist_metadata metadata
                    INNER JOIN migration_catalog_artist_ids ids
                        ON ids.oldArtistId = metadata.artistId
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE TABLE migration_yandex_artist_ids (" +
                        "externalId TEXT NOT NULL PRIMARY KEY, " +
                        "artistId TEXT NOT NULL UNIQUE, name TEXT NOT NULL)",
                )
                connection.execSQL(
                    """
                    INSERT INTO migration_yandex_artist_ids(externalId, artistId, name)
                    SELECT metadata.externalId, ids.newArtistId, artists.name
                    FROM catalog_artist_metadata metadata
                    INNER JOIN migration_catalog_artist_ids ids
                        ON ids.oldArtistId = metadata.artistId
                    INNER JOIN catalog_artists artists
                        ON artists.artistId = metadata.artistId
                    WHERE metadata.source = 'YANDEX'
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT OR IGNORE INTO migration_yandex_artist_ids(externalId, artistId, name)
                    SELECT CAST(id AS TEXT), lower(hex(randomblob(16))), MIN(name)
                    FROM artists
                    WHERE id IS NOT NULL
                    GROUP BY id
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT OR IGNORE INTO catalog_artists_new(artistId, name) " +
                        "SELECT artistId, name FROM migration_yandex_artist_ids",
                )
                connection.execSQL(
                    """
                    INSERT INTO catalog_artist_metadata_new(
                        artistId, source, externalId, coverUri, genres, likesCount,
                        trackCount, lastMonthListeners, lastMonthListenersDelta, refreshedAt
                    )
                    SELECT identities.artistId, 'YANDEX', identities.externalId,
                        NULL, '', NULL, NULL, NULL, NULL, 0
                    FROM migration_yandex_artist_ids identities
                    WHERE NOT EXISTS (
                        SELECT 1 FROM catalog_artist_metadata_new metadata
                        WHERE metadata.source = 'YANDEX'
                            AND metadata.externalId = identities.externalId
                    )
                    """.trimIndent(),
                )

                connection.execSQL("DROP TABLE catalog_artist_metadata")
                connection.execSQL("DROP TABLE catalog_artists")
                connection.execSQL("ALTER TABLE catalog_artists_new RENAME TO catalog_artists")
                connection.execSQL(
                    "ALTER TABLE catalog_artist_metadata_new " +
                        "RENAME TO catalog_artist_metadata",
                )
                connection.execSQL(
                    "CREATE INDEX index_catalog_artist_metadata_artistId " +
                        "ON catalog_artist_metadata(artistId)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX index_catalog_artist_metadata_source_externalId " +
                        "ON catalog_artist_metadata(source, externalId)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE track_instance_artists (
                        instanceId TEXT NOT NULL,
                        artistId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(instanceId, artistId),
                        FOREIGN KEY(instanceId) REFERENCES track_instances(instanceId)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(artistId) REFERENCES catalog_artists(artistId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX index_track_instance_artists_instanceId " +
                        "ON track_instance_artists(instanceId)",
                )
                connection.execSQL(
                    "CREATE INDEX index_track_instance_artists_artistId " +
                        "ON track_instance_artists(artistId)",
                )
                connection.execSQL(
                    """
                    INSERT OR IGNORE INTO track_instance_artists(instanceId, artistId, position)
                    SELECT instances.instanceId, identities.artistId, MIN(relations.artistLocalId)
                    FROM track_instances instances
                    INNER JOIN track_artists relations
                        ON relations.trackId = instances.sourceTrackId
                    INNER JOIN artists source_artists
                        ON source_artists.localId = relations.artistLocalId
                    INNER JOIN migration_yandex_artist_ids identities
                        ON identities.externalId = CAST(source_artists.id AS TEXT)
                    WHERE instances.source = 'YANDEX' AND source_artists.id IS NOT NULL
                    GROUP BY instances.instanceId, identities.artistId
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE migration_yandex_artist_ids")
                connection.execSQL("DROP TABLE migration_catalog_artist_ids")
            }
        }

        /** Разделяет локальный хеш метадаты и состояние завершённого онлайн-резолвинга. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE local_tracks ADD COLUMN currentHash " +
                        "TEXT NOT NULL DEFAULT ''",
                )
                connection.execSQL(
                    "ALTER TABLE local_tracks ADD COLUMN onlineSyncedHash TEXT",
                )
                connection.execSQL(
                    "ALTER TABLE local_tracks ADD COLUMN onlineResolverVersion " +
                        "INTEGER NOT NULL DEFAULT 0",
                )

                connection.execSQL(
                    "CREATE TABLE migration_catalog_album_ids (" +
                        "oldAlbumId TEXT NOT NULL PRIMARY KEY, " +
                        "newAlbumId TEXT NOT NULL UNIQUE)",
                )
                connection.execSQL(
                    "INSERT INTO migration_catalog_album_ids(oldAlbumId, newAlbumId) " +
                        "SELECT albumId, lower(hex(randomblob(16))) FROM catalog_albums",
                )
                connection.execSQL(
                    "CREATE TABLE catalog_albums_new (" +
                        "albumId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)",
                )
                connection.execSQL(
                    "INSERT INTO catalog_albums_new(albumId, title) " +
                        "SELECT ids.newAlbumId, albums.title FROM catalog_albums albums " +
                        "INNER JOIN migration_catalog_album_ids ids " +
                        "ON ids.oldAlbumId = albums.albumId",
                )
                connection.execSQL(
                    """
                    CREATE TABLE catalog_album_metadata_new (
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
                        FOREIGN KEY(albumId) REFERENCES catalog_albums_new(albumId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO catalog_album_metadata_new(
                        albumId, source, externalId, coverUri, artistNames, genre,
                        releaseDate, year, type, description, likesCount, trackCount, refreshedAt
                    )
                    SELECT ids.newAlbumId, metadata.source, metadata.externalId,
                        metadata.coverUri, metadata.artistNames, metadata.genre,
                        metadata.releaseDate, metadata.year, metadata.type,
                        metadata.description, metadata.likesCount, metadata.trackCount,
                        metadata.refreshedAt
                    FROM catalog_album_metadata metadata
                    INNER JOIN migration_catalog_album_ids ids
                        ON ids.oldAlbumId = metadata.albumId
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE TABLE catalog_album_tracks_new (
                        albumId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        sourceTrackId TEXT NOT NULL,
                        discNumber INTEGER,
                        trackNumber INTEGER,
                        PRIMARY KEY(albumId, source, position),
                        FOREIGN KEY(albumId) REFERENCES catalog_albums_new(albumId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO catalog_album_tracks_new(
                        albumId, source, position, sourceTrackId, discNumber, trackNumber
                    )
                    SELECT ids.newAlbumId, tracks.source, tracks.position,
                        tracks.sourceTrackId, tracks.discNumber, tracks.trackNumber
                    FROM catalog_album_tracks tracks
                    INNER JOIN migration_catalog_album_ids ids
                        ON ids.oldAlbumId = tracks.albumId
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE catalog_album_tracks")
                connection.execSQL("DROP TABLE catalog_album_metadata")
                connection.execSQL("DROP TABLE catalog_albums")
                connection.execSQL("ALTER TABLE catalog_albums_new RENAME TO catalog_albums")
                connection.execSQL(
                    "ALTER TABLE catalog_album_metadata_new RENAME TO catalog_album_metadata",
                )
                connection.execSQL(
                    "ALTER TABLE catalog_album_tracks_new RENAME TO catalog_album_tracks",
                )
                connection.execSQL(
                    "CREATE INDEX index_catalog_album_metadata_albumId " +
                        "ON catalog_album_metadata(albumId)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX index_catalog_album_metadata_source_externalId " +
                        "ON catalog_album_metadata(source, externalId)",
                )
                connection.execSQL(
                    "CREATE INDEX index_catalog_album_tracks_albumId " +
                        "ON catalog_album_tracks(albumId)",
                )
                connection.execSQL(
                    "CREATE INDEX index_catalog_album_tracks_sourceTrackId " +
                        "ON catalog_album_tracks(sourceTrackId)",
                )
                connection.execSQL(
                    """
                    CREATE TABLE track_instance_albums (
                        instanceId TEXT NOT NULL,
                        albumId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(instanceId, albumId),
                        FOREIGN KEY(instanceId) REFERENCES track_instances(instanceId)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(albumId) REFERENCES catalog_albums(albumId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX index_track_instance_albums_instanceId " +
                        "ON track_instance_albums(instanceId)",
                )
                connection.execSQL(
                    "CREATE INDEX index_track_instance_albums_albumId " +
                        "ON track_instance_albums(albumId)",
                )
                connection.execSQL(
                    """
                    INSERT OR IGNORE INTO track_instance_albums(instanceId, albumId, position)
                    SELECT instances.instanceId, metadata.albumId, MIN(relations.albumId)
                    FROM track_instances instances
                    INNER JOIN track_albums relations
                        ON relations.trackId = instances.sourceTrackId
                    INNER JOIN catalog_album_metadata metadata
                        ON metadata.source = 'YANDEX'
                        AND metadata.externalId = CAST(relations.albumId AS TEXT)
                    WHERE instances.source = 'YANDEX'
                    GROUP BY instances.instanceId, metadata.albumId
                    """.trimIndent(),
                )
                connection.execSQL("DROP TABLE migration_catalog_album_ids")
            }
        }
    }
}
