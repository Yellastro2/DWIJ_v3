package com.yellastrodev.dwij

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import com.yellastrodev.dwij.models.SearchUiState
import com.yellastrodev.dwij.ui.SearchEntityItem
import com.yellastrodev.dwij.ui.TrackCoverLoader
import com.yellastrodev.dwij.ui.TrackCoverState
import com.yellastrodev.dwij.ui.TrackListItem

/**
 * Единый ленивый поток результатов поиска: треки используют общий item списков,
 * альбомы и артисты — компактную строку с круглой обложкой.
 */
@Composable
fun SearchResult(
    state: SearchUiState,
    loadTrackCover: suspend (SearchResultItemUiModel.Track) -> ImageBitmap?,
    loadEntityCover: suspend (key: String, uri: String) -> ImageBitmap?,
    onItemClick: (SearchResultItemUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.query.isBlank() -> SearchResultMessage(
            text = stringResource(R.string.search_results_start_hint),
            color = Color(0xFF596175),
            fontWeight = FontWeight.Normal,
            modifier = modifier,
        )
        state.isLoading -> SearchResultMessage(
            text = stringResource(R.string.search_results_loading),
            color = Color(0xFF737C91),
            fontWeight = FontWeight.Normal,
            modifier = modifier,
        )
        state.error != null -> SearchResultMessage(
            text = stringResource(R.string.search_results_error),
            color = Color(0xFFFF8DBE),
            fontWeight = FontWeight.Medium,
            modifier = modifier,
        )
        state.hasSearched && state.results.isEmpty() -> SearchResultMessage(
            text = stringResource(R.string.search_results_empty),
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
                        TrackCoverLoader(
                            trackId = item.key,
                            coverState = coverState,
                            loadCover = { loadTrackCover(item) },
                        )
                        TrackListItem(
                            item = item.row,
                            coverState = coverState,
                            onClick = { onItemClick(item) },
                        )
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

@Composable
private fun SearchResultMessage(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 16.sp,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
        )
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
    )
}
