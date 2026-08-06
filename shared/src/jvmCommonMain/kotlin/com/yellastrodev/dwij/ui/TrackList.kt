package com.yellastrodev.dwij.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Линейный ленивый список треков для плейлистов, каталога и локальной медиатеки.
 * Обложки запрашиваются только для скомпонованных строк. Если задан [contextMenuContent],
 * long tap открывает привязанное к строке выпадающее меню.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TrackList(
    items: List<TrackListItemUiModel>,
    onItemClick: (index: Int, item: TrackListItemUiModel) -> Unit,
    contextMenuContent: (@Composable (
        index: Int,
        item: TrackListItemUiModel,
        onDismiss: () -> Unit,
    ) -> Unit)? = null,
    loadCover: suspend (trackId: String) -> ImageBitmap? = { null },
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    emptyMessage: String = "",
    isLoading: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val coverStates = remember { mutableMapOf<String, TrackCoverState>() }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        header?.let { headerContent ->
            item(key = "track_list_header", contentType = "header") {
                headerContent()
            }
        }

        if (items.isEmpty() && isLoading) {
            items(
                count = TRACK_LOADING_PLACEHOLDER_COUNT,
                key = { index -> "track_list_loading_$index" },
                contentType = { "loading" },
            ) { index ->
                TrackListLoadingPlaceholder(showMessage = index == 0)
            }
        } else if (items.isEmpty()) {
            item(key = "track_list_empty", contentType = "empty") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                ) {
                    if (emptyMessage.isNotBlank()) {
                        Text(
                            text = emptyMessage,
                            color = TrackSecondaryText,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(
                items = items,
                key = { _, item -> item.key },
                contentType = { _, item ->
                    if (item.shouldLoadCover) "cover" else "plain"
                },
            ) { index, item ->
                val coverState = remember(item.key) {
                    coverStates.getOrPut(item.key) { TrackCoverState() }
                }
                var isContextMenuExpanded by remember(item.key) {
                    mutableStateOf(false)
                }

                if (item.shouldLoadCover) {
                    TrackCoverLoader(
                        trackId = item.trackId,
                        coverState = coverState,
                        loadCover = loadCover,
                    )
                }

                Box {
                    TrackListItem(
                        item = item,
                        coverState = coverState,
                        onClick = { onItemClick(index, item) },
                        onLongClick = contextMenuContent?.let {
                            { isContextMenuExpanded = true }
                        },
                    )

                    contextMenuContent?.let { menuContent ->
                        DropdownMenu(
                            expanded = isContextMenuExpanded,
                            onDismissRequest = { isContextMenuExpanded = false },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            menuContent(index, item) {
                                isContextMenuExpanded = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Имитирует форму готовой строки, пока Room или сеть ещё не отдали первый снимок. */
@Composable
private fun TrackListLoadingPlaceholder(showMessage: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(TrackLoadingPlaceholder),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        ) {
            if (showMessage) {
                Text(
                    text = stringResource(Res.string.list_loading_placeholder),
                    color = TrackSecondaryText.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                    maxLines = 1,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.64f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TrackLoadingPlaceholder),
                )
            }

            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .fillMaxWidth(if (showMessage) 0.38f else 0.43f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TrackLoadingPlaceholder.copy(alpha = 0.72f)),
            )
        }
    }
}

@Preview(
    name = "Track list",
    widthDp = 360,
    heightDp = 360,
    showBackground = true,
    backgroundColor = 0xFF03040F,
)
@Composable
private fun TrackListPreview() {
    TrackList(
        items = listOf(
            TrackListItemUiModel(
                key = "1:0",
                trackId = "1",
                title = "Ночной город",
                artist = "Три дня дождя",
                shouldLoadCover = false,
            ),
            TrackListItemUiModel(
                key = "2:0",
                trackId = "2",
                title = "MARDI GRAS",
                artist = "Scriptz",
                shouldLoadCover = false,
                isYandexUnavailable = true,
                hasMultipleSources = true,
            ),
            TrackListItemUiModel(
                key = "3:0",
                trackId = "3",
                title = "FROSTSURGE",
                artist = "qõke, N:GHT",
                shouldLoadCover = false,
                isYandexUnavailable = true,
                isPlaybackBlocked = true,
                hasUnresolvedMatchCandidate = true,
            ),
        ),
        onItemClick = { _, _ -> },
        modifier = Modifier.background(TrackListBackground),
    )
}

private val TrackListBackground = Color(0xFF03040F)
private val TrackLoadingPlaceholder = Color(0xFF202635).copy(alpha = 0.62f)
private const val TRACK_LOADING_PLACEHOLDER_COUNT = 5