package com.yellastrodev.dwij

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalTrackEntity

/** Показывает локальные плейлисты либо общий Compose-список локальных треков. */
@Composable
fun LocalLibraryScreen(
    title: String,
    playlists: List<LocalPlaylistEntity>?,
    tracks: List<LocalTrackEntity>?,
    onPlaylistClick: (LocalPlaylistEntity) -> Unit,
    onTrackClick: (Int, LocalTrackEntity) -> Unit,
    loadTrackCover: suspend (LocalTrackEntity) -> ImageBitmap? = { null },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(18.dp))
        when {
            playlists != null -> LocalPlaylistList(playlists, onPlaylistClick)
            tracks != null -> LocalTrackList(tracks, onTrackClick, loadTrackCover)
        }
    }
}

/**
 * Показывает локальный плейлист через общую шапку музыкального объекта.
 *
 * У локального плейлиста пока нет собственной обложки, поэтому [ObjectScreen] получает `null`
 * и рисует штатную фирменную заглушку. Очередь и загрузка миниатюр остаются локальными.
 */
@Composable
fun LocalPlaylistObjectScreen(
    title: String,
    tracks: List<LocalTrackEntity>,
    onBackClick: () -> Unit,
    onPlayClick: () -> Unit,
    onTrackClick: (Int, LocalTrackEntity) -> Unit,
    loadTrackCover: suspend (LocalTrackEntity) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)
    val tracksById = remember(tracks) { tracks.associateBy(LocalTrackEntity::instanceId) }
    val items = remember(tracks, unknownArtist) {
        tracks.toTrackListItems(unknownArtist)
    }
    ObjectScreen(
        title = title,
        subtitle = pluralStringResource(
            R.plurals.object_track_count,
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
        loadTrackCover = { trackId ->
            tracksById[trackId]?.let { track -> loadTrackCover(track) }
        },
        showShare = false,
        showWave = false,
        emptyMessage = stringResource(R.string.local_tracks_empty),
        modifier = modifier,
    )
}

@Composable
private fun LocalPlaylistList(
    playlists: List<LocalPlaylistEntity>,
    onClick: (LocalPlaylistEntity) -> Unit,
) {
    if (playlists.isEmpty()) {
        EmptyLocalLibraryText(stringResource(R.string.local_playlists_empty))
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
                    color = Color.White,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (playlist.origin) {
                        LocalPlaylistOrigin.DWIJ.name -> stringResource(R.string.local_playlist_dwij)
                        LocalPlaylistOrigin.MEDIA_STORE.name ->
                            stringResource(R.string.local_playlist_media_store)
                        else -> stringResource(R.string.local_playlist_m3u)
                    },
                    color = Color(0xFF969BAD),
                    fontSize = 12.sp,
                )
            }
            HorizontalDivider(color = Color(0xFF282B35))
        }
    }
}

@Composable
private fun LocalTrackList(
    tracks: List<LocalTrackEntity>,
    onClick: (Int, LocalTrackEntity) -> Unit,
    loadCover: suspend (LocalTrackEntity) -> ImageBitmap?,
) {
    if (tracks.isEmpty()) {
        EmptyLocalLibraryText(stringResource(R.string.local_tracks_empty))
        return
    }
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)
    val tracksById = remember(tracks) { tracks.associateBy(LocalTrackEntity::instanceId) }
    val items = remember(tracks, unknownArtist) {
        tracks.toTrackListItems(unknownArtist)
    }
    TrackList(
        items = items,
        emptyMessage = stringResource(R.string.local_tracks_empty),
        loadCover = { trackId ->
            tracksById[trackId]?.let { track -> loadCover(track) }
        },
        onItemClick = { index, _ ->
            tracks.getOrNull(index)?.let { track -> onClick(index, track) }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Преобразует локальные сущности в независимые от источника строки Compose-списка. */
private fun List<LocalTrackEntity>.toTrackListItems(
    unknownArtist: String,
): List<TrackListItemUiModel> = mapIndexed { index, track ->
    TrackListItemUiModel(
        key = "${track.instanceId}:$index",
        trackId = track.instanceId,
        title = track.title,
        artist = track.artist?.takeIf { artist -> artist.isNotBlank() } ?: unknownArtist,
    )
}

@Composable
private fun EmptyLocalLibraryText(text: String) {
    Text(
        text = text,
        color = Color(0xFF969BAD),
        style = MaterialTheme.typography.bodyLarge,
    )
}
