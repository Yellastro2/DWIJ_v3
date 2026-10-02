package com.yellastrodev.dwij.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import com.yellastrodev.dwij.models.SearchTrackSource
import com.yellastrodev.dwij.models.SearchUiState
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.SearchEntityItem
import com.yellastrodev.dwij.ui.TrackCoverLoader
import com.yellastrodev.dwij.ui.TrackCoverState
import com.yellastrodev.dwij.ui.TrackListItem
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Единый ленивый поток результатов поиска: треки используют общий item списков,
 * альбомы и артисты — компактную строку с круглой обложкой. VK помечен отдельно;
 * ошибка предлагает повтор поиска или вход, подготовка аудио показывает ожидание.
 * Контекстное меню сохраняет Яндекс/VK; множества статусов используют source-id очереди загрузки.
 */
@Composable
fun SearchResult(
    state: SearchUiState,
    loadTrackCover: suspend (SearchResultItemUiModel.Track) -> ImageBitmap?,
    loadEntityCover: suspend (key: String, uri: String) -> ImageBitmap?,
    onItemClick: (SearchResultItemUiModel) -> Unit,
    savedYandexTrackIds: Set<String>,
    savingYandexTrackIds: Set<String>,
    onRequestLocalTrackDownload: (trackId: String, title: String) -> Unit,
    onShareYandexTrack: (trackId: String) -> Unit,
    onErrorAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when {
        state.query.isBlank() -> SearchResultMessage(
            text = stringResource(Res.string.search_results_start_hint),
            color = Color(0xFF596175),
            fontWeight = FontWeight.Normal,
            modifier = modifier,
        )
        state.isPreparingPlayback -> SearchResultMessage(
            text = "Подготавливаем трек ВК Музыки…",
            color = Color(0xFF5B9BFF), fontWeight = FontWeight.Normal, modifier = modifier,
        )
        state.isLoading -> SearchResultMessage(
            text = stringResource(Res.string.search_results_loading),
            color = Color(0xFF737C91),
            fontWeight = FontWeight.Normal,
            modifier = modifier,
        )
        state.error != null -> SearchResultMessage(
            actionLabel = if (state.error == com.yellastrodev.dwij.data.DataError.Unauthorized) "Открыть настройки" else "Повторить поиск",
            onAction = onErrorAction,
            text = when (val error = state.error) {
                com.yellastrodev.dwij.data.DataError.Unauthorized -> "Войдите в выбранный сервис в настройках"
                is com.yellastrodev.dwij.data.DataError.Remote -> "Сервис вернул ошибку ${error.code ?: error.statusCode}. Повторите поиск"
                is com.yellastrodev.dwij.data.DataError.InvalidData -> error.message
                else -> stringResource(Res.string.search_results_error)
            },
            color = Color(0xFFFF8DBE),
            fontWeight = FontWeight.Medium,
            modifier = modifier,
        )
        state.hasSearched && state.results.isEmpty() -> SearchResultMessage(
            text = stringResource(Res.string.search_results_empty),
            color = Color(0xFFE1E4EC),
            fontWeight = FontWeight.Medium,
            modifier = modifier,
        )
        else -> LazyColumn(
            contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            items(
                items = state.results,
                key = SearchResultItemUiModel::key,
                contentType = { item ->
                    when (item) {
                        is SearchResultItemUiModel.Track -> "track"
                        is SearchResultItemUiModel.Entity -> item.kind
                    }
                },
            ) { item ->
                when (item) {
                    is SearchResultItemUiModel.Track -> {
                        val coverState = remember(item.key) { TrackCoverState() }
                        val yandexTrackId =
                            (item.source as? SearchTrackSource.Yandex)?.track?.id
                        val downloadTrackId = yandexTrackId ?: (item.source as? SearchTrackSource.Vk)
                            ?.track?.fullId?.let { "vk:$it" }
                        val row = item.row.copy(
                            yandexTrackId = yandexTrackId,
                            isSavedLocally = downloadTrackId != null &&
                                downloadTrackId in savedYandexTrackIds,
                            isSavingLocally = downloadTrackId != null &&
                                downloadTrackId in savingYandexTrackIds,
                        )
                        var isContextMenuExpanded by remember(item.key) {
                            mutableStateOf(false)
                        }
                        TrackCoverLoader(
                            trackId = item.key,
                            coverState = coverState,
                            loadCover = { loadTrackCover(item) },
                        )
                        Box {
                            TrackListItem(
                                sourceIndicator = if (item.source is SearchTrackSource.Vk) com.yellastrodev.dwij.ui.TrackSourceIndicator.VK else null,
                                item = row,
                                coverState = coverState,
                                onClick = { onItemClick(item) },
                                onLongClick = downloadTrackId?.let {
                                    { isContextMenuExpanded = true }
                                },
                            )
                            DropdownMenu(
                                expanded = isContextMenuExpanded,
                                onDismissRequest = { isContextMenuExpanded = false },
                            ) {
                                yandexTrackId?.let { trackId ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(Res.string.object_share))
                                        },
                                        leadingIcon = {
                                            Image(
                                                painter = painterResource(Res.drawable.ic_share),
                                                contentDescription = null,
                                                modifier = Modifier.size(22.dp),
                                            )
                                        },
                                        onClick = {
                                            isContextMenuExpanded = false
                                            onShareYandexTrack(trackId)
                                        },
                                    )
                                }
                                downloadTrackId?.let { trackId ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when {
                                                        row.isSavedLocally -> Res.string.track_saved_locally
                                                        row.isSavingLocally -> Res.string.track_saving_locally
                                                        else -> Res.string.track_save_locally
                                                    },
                                                ),
                                            )
                                        },
                                        enabled = !row.isSavedLocally && !row.isSavingLocally,
                                        onClick = {
                                            isContextMenuExpanded = false
                                            onRequestLocalTrackDownload(trackId, row.title)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    is SearchResultItemUiModel.Entity -> SearchEntityItem(
                        item = item,
                        loadCover = loadEntityCover,
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

/** Сообщение выдачи с необязательным действием для повторного поиска или входа. */
@Composable
private fun SearchResultMessage(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = text,
                color = color,
                fontSize = 16.sp,
                fontWeight = fontWeight,
                textAlign = TextAlign.Center,
            )
            actionLabel?.let { label -> TextButton(onClick = onAction) { Text(label) } }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF020611, heightDp = 300)
@Composable
private fun SearchResultAwaitingQueryPreview() {
    SearchResult(
        state = SearchUiState(),
        loadTrackCover = { null },
        loadEntityCover = { _, _ -> null },
        onItemClick = {},
        savedYandexTrackIds = emptySet(),
        savingYandexTrackIds = emptySet(),
        onRequestLocalTrackDownload = { _, _ -> },
        onShareYandexTrack = {},
    )
}
