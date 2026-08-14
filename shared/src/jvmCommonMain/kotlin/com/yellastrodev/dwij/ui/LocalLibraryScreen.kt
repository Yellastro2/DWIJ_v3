package com.yellastrodev.dwij.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Показывает локальные плейлисты либо общий Compose-список локальных треков. */
@Composable
fun LocalLibraryScreen(
    title: String,
    playlists: List<LocalPlaylistEntity>?,
    tracks: List<Song>?,
    onBackClick: () -> Unit,
    onPlaylistClick: (LocalPlaylistEntity) -> Unit,
    onTrackClick: (Int, Song) -> Unit,
    onTrackHideRequest: (Song) -> Unit = {},
    loadTrackCover: suspend (Song) -> ImageBitmap? = { null },
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DwijBackButton(
                onClick = onBackClick,
            )

            Text(
                text = title,
                color = DwijColors.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        when {
            playlists != null -> LocalPlaylistList(playlists, isLoading, onPlaylistClick)
            tracks != null -> LocalTrackList(
                tracks = tracks,
                isLoading = isLoading,
                onClick = onTrackClick,
                onTrackHideRequest = onTrackHideRequest,
                loadCover = loadTrackCover,
            )
        }
    }
}

/**
 * Показывает локальную коллекцию треков через общий экран музыкального объекта.
 *
 * У локальной коллекции пока нет собственной обложки, поэтому [ObjectScreen] получает `null`
 * и рисует штатную фирменную заглушку. Очередь и загрузка миниатюр остаются локальными.
 */
@Composable
fun LocalTrackCollectionObjectScreen(
    title: String,
    tracks: List<Song>,
    onBackClick: () -> Unit,
    onPlayClick: () -> Unit,
    onTrackClick: (Int, Song) -> Unit,
    onTrackHideRequest: (Song) -> Unit = {},
    loadTrackCover: suspend (Song) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    val unknownArtist = stringResource(Res.string.home_player_unknown_artist)
    val tracksById = remember(tracks) { tracks.associateBy(Song::id) }
    val items = remember(tracks, unknownArtist) {
        tracks.toTrackListItems(unknownArtist)
    }
    ObjectScreen(
        title = title,
        subtitle = pluralStringResource(
            Res.plurals.object_track_count,
            tracks.size,
            tracks.size,
        ),
        description = null,
        cover = null,
        tracks = items,
        onBackClick = onBackClick,
        onPlayClick = onPlayClick,
        onTrackClick = { index, _ ->
            tracks.getOrNull(index)?.let { track -> onTrackClick(index, track) }
        },
        trackContextMenuContent = { index, _, onDismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.local_track_hide_action)) },
                onClick = {
                    onDismiss()
                    tracks.getOrNull(index)?.let(onTrackHideRequest)
                },
            )
        },
        loadTrackCover = { trackId ->
            tracksById[trackId]?.let { track -> loadTrackCover(track) }
        },
        showShare = false,
        showWave = false,
        emptyMessage = stringResource(Res.string.local_tracks_empty),
        isLoading = isLoading,
        modifier = modifier,
    )
}

@Composable
private fun LocalPlaylistList(
    playlists: List<LocalPlaylistEntity>,
    isLoading: Boolean,
    onClick: (LocalPlaylistEntity) -> Unit,
) {
    if (playlists.isEmpty()) {
        if (isLoading) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(LOCAL_PLAYLIST_PLACEHOLDER_COUNT) { index ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                    ) {
                        if (index == 0) {
                            Text(
                                text = stringResource(Res.string.list_loading_placeholder),
                                color = DwijColors.ListSecondaryText.copy(alpha = 0.72f),
                                fontSize = 15.sp,
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth(0.58f)
                                    .height(16.dp)
                                    .background(
                                        DwijColors.LocalLibraryProgress.copy(alpha = 0.5f),
                                        RoundedCornerShape(4.dp),
                                    ),
                            )
                        }
                        Spacer(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .fillMaxWidth(0.34f)
                                .height(10.dp)
                                .background(
                                    DwijColors.LocalLibraryProgress.copy(alpha = 0.36f),
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                    HorizontalDivider(
                        color = DwijColors.LocalLibraryDivider.copy(alpha = 0.55f),
                    )
                }
            }
            return
        }
        EmptyLocalLibraryText(stringResource(Res.string.local_playlists_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(playlists, key = LocalPlaylistEntity::playlistId) { playlist ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(playlist) }
                    .padding(vertical = 14.dp),
            ) {
                Text(
                    text = playlist.name,
                    color = DwijColors.White,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (playlist.origin) {
                        LocalPlaylistOrigin.DWIJ.name -> stringResource(Res.string.local_playlist_dwij)
                        LocalPlaylistOrigin.MEDIA_STORE.name ->
                            stringResource(Res.string.local_playlist_media_store)
                        else -> stringResource(Res.string.local_playlist_m3u)
                    },
                    color = DwijColors.ListSecondaryText,
                    fontSize = 12.sp,
                )
            }
            HorizontalDivider(color = DwijColors.LocalLibraryDivider)
        }
    }
}

@Composable
private fun LocalTrackList(
    tracks: List<Song>,
    isLoading: Boolean,
    onClick: (Int, Song) -> Unit,
    onTrackHideRequest: (Song) -> Unit,
    loadCover: suspend (Song) -> ImageBitmap?,
) {
    val unknownArtist = stringResource(Res.string.home_player_unknown_artist)
    val tracksById = remember(tracks) { tracks.associateBy(Song::id) }
    val items = remember(tracks, unknownArtist) {
        tracks.toTrackListItems(unknownArtist)
    }
    TrackList(
        items = items,
        emptyMessage = stringResource(Res.string.local_tracks_empty),
        isLoading = isLoading,
        loadCover = { trackId ->
            tracksById[trackId]?.let { track -> loadCover(track) }
        },
        onItemClick = { index, _ ->
            tracks.getOrNull(index)?.let { track -> onClick(index, track) }
        },
        contextMenuContent = { index, _, onDismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.local_track_hide_action)) },
                onClick = {
                    onDismiss()
                    tracks.getOrNull(index)?.let(onTrackHideRequest)
                },
            )
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Преобразует локальные сущности в независимые от источника строки Compose-списка. */
private fun List<Song>.toTrackListItems(
    unknownArtist: String,
): List<TrackListItemUiModel> = mapIndexed { index, song ->
    TrackListItemUiModel(
        key = "${song.id}:$index",
        trackId = song.id,
        title = song.title,
        artist = song.artistNames.joinToString(", ").ifBlank { unknownArtist },
        hasMultipleSources = song.instances.size > 1,
        hasUnresolvedMatchCandidate = song.hasPendingMatchCandidate,
    )
}

@Composable
private fun EmptyLocalLibraryText(text: String) {
    Text(
        text = text,
        color = DwijColors.ListSecondaryText,
        style = MaterialTheme.typography.bodyLarge,
    )
}

private const val LOCAL_PLAYLIST_PLACEHOLDER_COUNT = 5
