package com.yellastrodev.dwij

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Состояние будущего ленивого списка результатов поиска. */
enum class SearchResultState {
    AwaitingQuery,
    NothingFound,
}

/**
 * Контейнер для поисковой выдачи.
 *
 * Сейчас отображает только пустые состояния; позже сюда будет добавлен ленивый список результатов.
 */
@Composable
fun SearchResult(
    state: SearchResultState,
    modifier: Modifier = Modifier,
) {
    val (textRes, color, weight) = when (state) {
        SearchResultState.AwaitingQuery -> Triple(
            R.string.search_results_start_hint,
            Color(0xFF596175),
            FontWeight.Normal,
        )
        SearchResultState.NothingFound -> Triple(
            R.string.search_results_empty,
            Color(0xFFE1E4EC),
            FontWeight.Medium,
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp),
    ) {
        androidx.compose.material3.Text(
            text = stringResource(textRes),
            color = color,
            fontSize = 16.sp,
            fontWeight = weight,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF020611)
@Composable
private fun SearchResultAwaitingQueryPreview() {
    SearchResult(state = SearchResultState.AwaitingQuery)
}

@Preview(showBackground = true, backgroundColor = 0xFF020611)
@Composable
private fun SearchResultNothingFoundPreview() {
    SearchResult(state = SearchResultState.NothingFound)
}
