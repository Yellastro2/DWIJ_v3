package com.yellastrodev.dwij.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.resources.*
import org.jetbrains.compose.resources.painterResource
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import com.yellastrodev.dwij.models.SearchUiState
import com.yellastrodev.dwij.ui.MusicSourceSelector
import com.yellastrodev.dwij.ui.theme.DwijColors
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Экран поиска: выбор источника, поле запроса, недавние запросы и место для будущей выдачи.
 */
@Composable
fun SearchScreen(
    selectedSource: HomeMusicSource,
    onSourceSelected: (HomeMusicSource) -> Unit,
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    loadTrackCover: suspend (SearchResultItemUiModel.Track) -> ImageBitmap?,
    loadEntityCover: suspend (key: String, uri: String) -> ImageBitmap?,
    onResultClick: (SearchResultItemUiModel) -> Unit,
    savedYandexTrackIds: Set<String>,
    savingYandexTrackIds: Set<String>,
    onRequestLocalTrackDownload: (trackId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recentQueries = listOf(
        stringResource(Res.string.search_recent_query_city),
        stringResource(Res.string.search_recent_query_road),
        stringResource(Res.string.search_recent_query_synthwave),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 24.dp),
    ) {
        SearchHeader()
        MusicSourceSelector(
            selectedSource = selectedSource,
            onSourceSelected = onSourceSelected,
            modifier = Modifier.padding(top = 4.dp),
        )
        SearchInput(
            query = state.query,
            onQueryChange = onQueryChange,
        )
        RecentQueries(
            queries = recentQueries,
            onQueryClick = onQueryChange,
        )
        SearchResult(
            state = state,
            loadTrackCover = loadTrackCover,
            loadEntityCover = loadEntityCover,
            onItemClick = onResultClick,
            savedYandexTrackIds = savedYandexTrackIds,
            savingYandexTrackIds = savingYandexTrackIds,
            onRequestLocalTrackDownload = onRequestLocalTrackDownload,
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp),
        )
    }
}

@Composable
private fun SearchHeader() {
    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
        Text(
            text = stringResource(Res.string.search_title),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(Res.string.search_subtitle),
            color = Color(0xFF9298AC),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE60A0E1B))
            .border(
                width = 1.dp,
                color = Color(0x99FF178F),
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        Image(
            painter = painterResource(Res.drawable.ic_home_nav_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color(0xFFFF178F)),
            modifier = Modifier
                .padding(start = 16.dp)
                .size(23.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            cursorBrush = SolidColor(Color(0xFFFF178F)),
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 15.sp,
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.search_hint),
                            color = Color(0xFF777E92),
                            fontSize = 15.sp,
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, end = 16.dp),
        )
    }
}

@Composable
private fun RecentQueries(
    queries: List<String>,
    onQueryClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        queries.forEachIndexed { index, query ->
            SearchQueryChip(
                text = query,
                onClick = { onQueryClick(query) },
            )
            if (index != queries.lastIndex) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun SearchQueryChip(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = Color(0xFFD8DAE4),
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111727))
            .border(1.dp, Color(0x554E8BFF), RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Preview(widthDp = 360, heightDp = 720)
@Composable
private fun SearchScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DwijColors.Background),
    ) {
        SearchScreen(
            selectedSource = HomeMusicSource.Yandex,
            onSourceSelected = {},
            state = SearchUiState(),
            onQueryChange = {},
            loadTrackCover = { null },
            loadEntityCover = { _, _ -> null },
            onResultClick = {},
            savedYandexTrackIds = emptySet(),
            savingYandexTrackIds = emptySet(),
            onRequestLocalTrackDownload = { _, _ -> },
        )
    }
}
