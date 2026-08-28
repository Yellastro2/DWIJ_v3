package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.ic_player_play_v2
import com.yellastrodev.dwij.resources.list_loading_placeholder
import com.yellastrodev.dwij.resources.waves_category_activity
import com.yellastrodev.dwij.resources.waves_category_artist
import com.yellastrodev.dwij.resources.waves_category_genre
import com.yellastrodev.dwij.resources.waves_category_mood
import com.yellastrodev.dwij.resources.waves_category_personal
import com.yellastrodev.dwij.resources.waves_category_track
import com.yellastrodev.dwij.resources.waves_empty
import com.yellastrodev.dwij.resources.waves_load_failed
import com.yellastrodev.dwij.resources.waves_title
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.ui.playlist.PlaylistCoverState
import com.yellastrodev.dwij.ui.playlist.PlaylistGridEntry
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItem
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItemUiModel
import com.yellastrodev.dwij.ui.playlist.PlaylistGridScreen
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import com.yellastrodev.yamusicsdk.entities.CoverSize
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.rotor.RotorStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Экран полного каталога Волн Яндекс Музыки. */
@Composable
fun WaveGridRoute(
    component: DwijComponent,
    onBackClick: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logger = LocalYamLogger.current
    val coroutineScope = rememberCoroutineScope()
    var stations by remember(component) {
        mutableStateOf<List<RotorStation>>(emptyList())
    }
    var isLoading by remember(component) { mutableStateOf(true) }
    var isRefreshing by remember(component) { mutableStateOf(false) }
    var loadFailed by remember(component) { mutableStateOf(false) }

    val loadStations: suspend (Boolean) -> Unit = { refreshing ->
        if (refreshing) {
            isRefreshing = true
        } else {
            isLoading = true
        }
        loadFailed = false

        when (val result = component.waveRepository.getStations()) {
            is YamResult.Success -> stations = result.value
            is YamResult.Failure -> {
                loadFailed = true
                logger.error(
                    WAVE_GRID_TAG,
                    "[loadStations] Не удалось загрузить каталог Волн: ${result.error}",
                )
            }
        }

        isLoading = false
        isRefreshing = false
    }

    LaunchedEffect(component) {
        loadStations(false)
    }

    val categoryPersonal = stringResource(Res.string.waves_category_personal)
    val categoryGenre = stringResource(Res.string.waves_category_genre)
    val categoryMood = stringResource(Res.string.waves_category_mood)
    val categoryActivity = stringResource(Res.string.waves_category_activity)
    val categoryArtist = stringResource(Res.string.waves_category_artist)
    val categoryTrack = stringResource(Res.string.waves_category_track)
    val items = stations.map { station ->
        WaveGridItem(
            station = station,
            title = station.name.ifBlank {
                station.customName?.takeIf(String::isNotBlank) ?: station.id
            },
            category = when (station.category.lowercase()) {
                "user" -> categoryPersonal
                "genre" -> categoryGenre
                "mood" -> categoryMood
                "activity" -> categoryActivity
                "artist" -> categoryArtist
                "track" -> categoryTrack
                else -> station.category
            },
        )
    }

    PlaylistGridScreen(
        title = stringResource(Res.string.waves_title),
        items = items,
        selectedSource = HomeMusicSource.Yandex,
        onSourceSelected = {},
        onBackClick = onBackClick,
        onItemClick = { item ->
            if (
                component.waveRepository.requestStationWave(
                    stationId = item.station.id,
                    stationTitle = item.title,
                )
            ) {
                onOpenPlayer()
            }
        },
        onItemLongClick = {},
        emptyMessage = stringResource(
            if (loadFailed) {
                Res.string.waves_load_failed
            } else {
                Res.string.waves_empty
            },
        ),
        loadingMessage = stringResource(Res.string.list_loading_placeholder),
        sourceSelector = { _, _ -> },
        itemContent = {
                item,
                coverState,
                onClick,
                _,
                itemModifier,
            ->
            WaveGridItemContent(
                item = item,
                coverState = coverState,
                onClick = onClick,
                modifier = itemModifier,
            )
        },
        loadCover = { stationId ->
            val station = stations.firstOrNull { it.id == stationId }
            val coverUri = station?.coverUri
            if (coverUri == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    component.coverRepository.getRemoteCover(
                        entityType = WAVE_COVER_TYPE,
                        entityId = stationId,
                        url = coverUri,
                        size = CoverSize.`200x200`,
                    )?.toImageBitmapOrNull()
                }
            }
        },
        showSourceSelector = false,
        modifier = modifier,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                loadStations(true)
            }
        },
    )
}

private data class WaveGridItem(
    val station: RotorStation,
    val title: String,
    val category: String,
) : PlaylistGridEntry {
    override val id: String = station.id
    override val isCreateAction: Boolean = false
    override val shouldLoadCover: Boolean = !station.coverUri.isNullOrBlank()
}

@Composable
private fun WaveGridItemContent(
    item: WaveGridItem,
    coverState: PlaylistCoverState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaylistGridItem(
        item = PlaylistGridItemUiModel(
            title = item.title,
            details = item.category,
        ),
        coverState = coverState,
        onClick = onClick,
        fallbackContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(DwijColors.Background),
            ) {
                Image(
                    painter = painterResource(Res.drawable.ic_player_play_v2),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(54.dp),
                )
            }
        },
        modifier = modifier,
    )
}

private const val WAVE_GRID_TAG = "WaveGridRoute"
private const val WAVE_COVER_TYPE = "wave"
