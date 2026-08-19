package com.yellastrodev.dwij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import com.yellastrodev.dwij.ui.theme.DwijColors

/** Общий список библиотечных артистов или альбомов на готовых entity-строках. */
@Composable
fun CatalogEntityListScreen(
    title: String,
    items: List<SearchResultItemUiModel.Entity>,
    emptyMessage: String,
    loadCover: suspend (key: String, uri: String) -> ImageBitmap?,
    onBackClick: () -> Unit,
    onItemClick: (SearchResultItemUiModel.Entity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DwijColors.Background)
            .statusBarsPadding(),
    ) {
        CatalogEntityListHeader(
            title = title,
            onBackClick = onBackClick,
        )

        if (items.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
            ) {
                Text(
                    text = emptyMessage,
                    color = DwijColors.ListSecondaryText,
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = items,
                    key = SearchResultItemUiModel.Entity::key,
                ) { item ->
                    SearchEntityItem(
                        item = item,
                        loadCover = loadCover,
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogEntityListHeader(
    title: String,
    onBackClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .padding(horizontal = 8.dp),
    ) {
        DwijBackButton(onClick = onBackClick)
        Text(
            text = title,
            color = DwijColors.White,
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
