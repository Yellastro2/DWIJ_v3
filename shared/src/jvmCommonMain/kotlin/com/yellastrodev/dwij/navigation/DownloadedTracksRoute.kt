package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.DownloadedTracksModel
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.ObjectScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Показывает все постоянные загрузки ЯМ, VK либо обоих источников, независимо от состава плейлистов. */
@Composable
internal fun DownloadedTracksRoute(
    component: DwijComponent,
    playerModel: PlayerModel,
    source: HomeMusicSource,
    onBackClick: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = viewModel(key = "downloaded-tracks:${source.name}") {
        DownloadedTracksModel(
            source = source,
            songs = component.songRepository,
            cache = component.trackCacheRepo,
            vk = component.vkMusicRepository,
            player = component.playerRepo,
            wave = component.waveRepository,
            logger = component.logger,
        )
    }
    val state by model.state.collectAsState()
    val playing by model.playing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sourceTitle = stringResource(when (source) {
        HomeMusicSource.Yandex -> Res.string.home_source_yandex_music
        HomeMusicSource.Vk -> Res.string.home_source_vk_music
        else -> Res.string.home_source_all
    })
    val title = stringResource(Res.string.downloaded_tracks_title, sourceTitle)
    val failureMessage = stringResource(Res.string.downloaded_tracks_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) snackbar.showSnackbar(failureMessage)
    }
    // Возврат из плеера также проверяет внешнее удаление файлов, сохраняя состояние списка.
    LaunchedEffect(model) { model.refresh() }

    /** Включает выбранную сохранённую песню и сообщает о пропавшем файле или ошибке плеера. */
    fun play(songId: String?) {
        if (playing) return
        scope.launch {
            try {
                if (model.play(songId, title)) onOpenPlayer()
                else snackbar.showSnackbar(failureMessage)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                component.logger.error("DownloadedTracksRoute", "[play] Не удалось включить скачанные треки", error)
                snackbar.showSnackbar(failureMessage)
            }
        }
    }

    val songsById = remember(state.entries) { state.entries.associate { it.song.id to it.song } }
    val items = remember(state.entries) {
        state.entries.map { (song, _) ->
            TrackListItemUiModel(
                key = song.id,
                trackId = song.id,
                title = song.title,
                artist = song.artistNames.joinToString(", "),
                yandexTrackId = song.yandexInstances.firstOrNull()?.track?.id,
                isYandexUnavailable = song.yandexInstances.isNotEmpty() && song.yandexInstances.none { it.track.available },
                hasMultipleSources = song.instances.size > 1,
                hasUnresolvedMatchCandidate = song.hasPendingMatchCandidate,
                isSavedLocally = true,
            )
        }
    }
    Box(modifier.fillMaxSize()) {
        ObjectScreen(
            title = title,
            subtitle = pluralStringResource(Res.plurals.object_track_count, items.size, items.size),
            description = null,
            cover = null,
            tracks = items,
            onBackClick = onBackClick,
            onPlayClick = { play(null) },
            onTrackClick = { _, item -> play(item.trackId) },
            loadTrackCover = { id -> songsById[id]?.let { playerModel.cover(it, maxEdgePx = 180).firstOrNull() } },
            showShare = false,
            showWave = false,
            emptyMessage = stringResource(if (state.failed) Res.string.downloaded_tracks_failed else Res.string.downloaded_tracks_empty),
            isLoading = state.loading && items.isEmpty(),
            isRefreshing = state.loading && items.isNotEmpty(),
            onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}
