package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.toScanUpdate
import com.yellastrodev.yamusicsdk.entities.YaArtist
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LocalCatalogResolverTest {
    @Test
    fun inputHashIgnoresCosmeticDifferencesButTracksResolverInputs() {
        val first = localCatalogInputHash("  Моя песня ", "АРТИСТ")

        assertEquals(first, localCatalogInputHash("моя   песня", "артист"))
        kotlin.test.assertNotEquals(first, localCatalogInputHash("Другая песня", "артист"))
        kotlin.test.assertNotEquals(first, localCatalogInputHash("Моя песня", "другой артист"))
    }

    @Test
    fun scannerUpdateDoesNotContainOnlineResolutionState() {
        val scanned = localTrack(title = "Song", artist = "Artist").copy(
            currentHash = "current",
        )
        val resolved = scanned.copy(
            onlineSyncedHash = "current",
            onlineResolverVersion = 7,
            isHidden = true,
        )

        assertEquals(scanned.toScanUpdate(), resolved.toScanUpdate())
    }

    @Test
    fun resolvesTrackWithCreditDividerAndOneTitleTypo() = runBlocking {
        val yandexTrack = track(
            id = "42",
            title = "Большой трек",
            artists = listOf(artist(1, "Alpha"), artist(2, "Beta")),
        )
        val resolver = LocalCatalogResolver(
            FakeSearch(tracks = listOf(yandexTrack)),
        )

        val result = resolver.resolve(
            localTrack(title = "Большой трэк", artist = "Alpha feat. Beta"),
        )

        val resolution = result.successValue()
        assertEquals("42", assertIs<LocalCatalogResolution.Track>(resolution).track.id)
    }

    @Test
    fun prefersWholeArtistBeforeSplittingAmbiguousAmpersand() = runBlocking {
        val wholeArtist = artist(7, "Royal & the Serpent")
        val resolver = LocalCatalogResolver(
            FakeSearch(artistsByQuery = mapOf("Royal & the Serpent" to listOf(wholeArtist))),
        )

        val result = resolver.resolve(
            localTrack(title = "Overwhelmed", artist = "Royal & the Serpent"),
        )

        val resolution = result.successValue()
        val artists = assertIs<LocalCatalogResolution.Artists>(resolution)
        assertEquals(LocalCatalogResolutionStage.FULL_ARTIST, artists.stage)
        assertEquals(listOf(7), artists.artists.map { it.id })
    }

    @Test
    fun splitsCreditOnlyAfterWholeArtistWasNotFound() = runBlocking {
        val resolver = LocalCatalogResolver(
            FakeSearch(
                artistsByQuery = mapOf(
                    "Alpha" to listOf(artist(1, "Alpha")),
                    "Beta" to listOf(artist(2, "Beta")),
                ),
            ),
        )

        val result = resolver.resolve(localTrack(title = "Song", artist = "Alpha feat. Beta"))

        val resolution = result.successValue()
        val artists = assertIs<LocalCatalogResolution.Artists>(resolution)
        assertEquals(LocalCatalogResolutionStage.SPLIT_ARTISTS, artists.stage)
        assertEquals(listOf(1, 2), artists.artists.map { it.id })
    }

    @Test
    fun doesNotChooseBetweenEquallyGoodArtistIdentities() = runBlocking {
        val resolver = LocalCatalogResolver(
            FakeSearch(
                artistsByQuery = mapOf(
                    "Same Name" to listOf(
                        artist(1, "Same Name"),
                        artist(2, "Same Name"),
                    ),
                ),
            ),
        )

        val result = resolver.resolve(localTrack(title = "Song", artist = "Same Name"))

        val resolution = result.successValue()
        val ambiguous = assertIs<LocalCatalogResolution.Ambiguous>(resolution)
        assertEquals(LocalCatalogResolutionStage.FULL_ARTIST, ambiguous.stage)
    }

    private class FakeSearch(
        private val tracks: List<YaTrack> = emptyList(),
        private val artistsByQuery: Map<String, List<YaArtist>> = emptyMap(),
    ) : LocalCatalogSearch {
        override suspend fun searchTracks(query: String): DataResult<List<YaTrack>> =
            DataResult.Success(tracks)

        override suspend fun searchArtists(query: String): DataResult<List<YaArtist>> =
            DataResult.Success(artistsByQuery[query].orEmpty())
    }

    private fun DataResult<LocalCatalogResolution>.successValue(): LocalCatalogResolution =
        when (this) {
            is DataResult.Success -> value
            is DataResult.Failure -> error("Ожидался успешный результат, получено: $error")
        }

    private fun artist(id: Int, name: String): YaArtist = YaArtist(id = id, name = name)

    private fun track(
        id: String,
        title: String,
        artists: List<YaArtist>,
    ): YaTrack = YaTrack(
        id = id,
        title = title,
        available = true,
        durationMs = null,
        artists = artists,
        albums = emptyList(),
    )

    private fun localTrack(title: String, artist: String): LocalTrackEntity = LocalTrackEntity(
        instanceId = "local:1",
        mediaStoreId = 1L,
        volumeName = "external",
        contentUri = "content://media/1",
        displayName = "$title.mp3",
        title = title,
        artist = artist,
        album = null,
        albumId = null,
        durationMs = 180_000L,
        trackNumber = null,
        discNumber = null,
        year = null,
        mimeType = "audio/mpeg",
        sizeBytes = null,
        dateModifiedSeconds = 0L,
        relativePath = null,
        absolutePath = null,
    )
}
