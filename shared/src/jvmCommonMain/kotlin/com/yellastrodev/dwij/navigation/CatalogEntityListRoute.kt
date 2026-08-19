package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.SearchEntityKind
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.catalog_albums_empty
import com.yellastrodev.dwij.resources.catalog_albums_title
import com.yellastrodev.dwij.resources.catalog_artists_empty
import com.yellastrodev.dwij.resources.catalog_artists_title
import com.yellastrodev.dwij.ui.CatalogEntityListScreen
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import com.yellastrodev.yamusicsdk.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Вид библиотечного списка, открываемого из каталога. */
enum class CatalogEntityListKind {
    Artists,
    Albums,
}

/**
 * Собирает multisource-список из библиотечных DAO-проекций и открывает
 * source-страницу выбранного артиста или альбома.
 */
@Composable
fun CatalogEntityListRoute(
    component: DwijComponent,
    kind: CatalogEntityListKind,
    onBackClick: () -> Unit,
    onOpenCatalogObject: (type: String, externalId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemFlow: Flow<List<SearchResultItemUiModel.Entity>> =
        remember(component, kind) {
            when (kind) {
                CatalogEntityListKind.Artists ->
                    component.catalogRepository.libraryArtists.map { artists ->
                        artists.map { artist ->
                            SearchResultItemUiModel.Entity(
                                key = "library-artist:${artist.artistId}",
                                coverUri = artist.coverUri,
                                externalId = artist.yandexId,
                                title = artist.name,
                                kind = SearchEntityKind.Artist,
                                genres = artist.genres,
                                trackCount = artist.libraryTrackCount,
                                likesCount = artist.likesCount,
                            )
                        }
                    }

                CatalogEntityListKind.Albums ->
                    component.catalogRepository.libraryAlbums.map { albums ->
                        albums.map { album ->
                            SearchResultItemUiModel.Entity(
                                key = "library-album:${album.albumId}",
                                coverUri = album.coverUri,
                                externalId = album.yandexId,
                                title = album.title,
                                kind = SearchEntityKind.Album,
                                artistNames = album.artistNames,
                                trackCount = album.libraryTrackCount,
                                likesCount = album.likesCount,
                            )
                        }
                    }
            }
        }
    val items by itemFlow.collectAsState(initial = emptyList())

    val title = stringResource(
        when (kind) {
            CatalogEntityListKind.Artists -> Res.string.catalog_artists_title
            CatalogEntityListKind.Albums -> Res.string.catalog_albums_title
        },
    )
    val emptyMessage = stringResource(
        when (kind) {
            CatalogEntityListKind.Artists -> Res.string.catalog_artists_empty
            CatalogEntityListKind.Albums -> Res.string.catalog_albums_empty
        },
    )

    CatalogEntityListScreen(
        title = title,
        items = items,
        emptyMessage = emptyMessage,
        loadCover = { key, uri ->
            withContext(Dispatchers.IO) {
                component.coverRepository.getRemoteCover(
                    entityType = CATALOG_LIBRARY_COVER_TYPE,
                    entityId = key,
                    url = uri,
                    size = CoverSize.`100x100`,
                )?.toImageBitmapOrNull()
            }
        },
        onBackClick = onBackClick,
        onItemClick = { item ->
            val objectType = when (item.kind) {
                SearchEntityKind.Artist -> DwijDestination.OBJECT_TYPE_ARTIST
                SearchEntityKind.Album -> DwijDestination.OBJECT_TYPE_ALBUM
            }
            onOpenCatalogObject(objectType, item.externalId)
        },
        modifier = modifier,
    )
}

private const val CATALOG_LIBRARY_COVER_TYPE = "catalog-library"
