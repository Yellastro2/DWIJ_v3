package com.yellastrodev.dwij.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.song_match_candidates_empty
import com.yellastrodev.dwij.resources.song_match_candidates_title
import org.jetbrains.compose.resources.stringResource

/**
 * Показывает все пары, найденные SongMatch resolver-ом, через общий ленивый список треков.
 * Порядок уже приходит из Room, поэтому экран ничего не пересортировывает при рекомпозиции.
 */
@Composable
fun SongMatchCandidatesScreen(
    items: List<TrackListItemUiModel>,
    onBackClick: () -> Unit,
    onItemClick: (TrackListItemUiModel) -> Unit,
    loadCover: suspend (candidateKey: String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SongMatchBackground)
            .navigationBarsPadding()
            .statusBarsPadding(),
    ) {
        SongMatchCandidatesHeader(onBackClick = onBackClick)
        TrackList(
            items = items,
            onItemClick = { _, item -> onItemClick(item) },
            loadCover = loadCover,
            emptyMessage = stringResource(Res.string.song_match_candidates_empty),
            isLoading = isLoading,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Верхняя панель списка с той же глич-стрелкой и знаком мультисурса. */
@Composable
private fun SongMatchCandidatesHeader(onBackClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .padding(horizontal = 7.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clickable(onClick = onBackClick),
        ) {
            Canvas(modifier = Modifier.size(25.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = SongMatchCyan,
                    start = Offset(size.width * 0.72f + 1.2.dp.toPx(), size.height * 0.17f),
                    end = Offset(size.width * 0.29f + 1.2.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = SongMatchPink,
                    start = Offset(size.width * 0.72f - 1.2.dp.toPx(), size.height * 0.83f),
                    end = Offset(size.width * 0.29f - 1.2.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.72f, size.height * 0.17f),
                    end = Offset(size.width * 0.29f, size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.29f, size.height * 0.5f),
                    end = Offset(size.width * 0.72f, size.height * 0.83f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
        Text(
            text = stringResource(Res.string.song_match_candidates_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        MultipleSourcesIndicator(modifier = Modifier.size(32.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF03040F)
@Composable
private fun SongMatchCandidatesScreenPreview() {
    SongMatchCandidatesScreen(
        items = listOf(
            TrackListItemUiModel(
                key = "first:second",
                trackId = "first:second",
                title = "Ночной город  ↔  Ночной Город",
                artist = "ОЖИДАЕТ · Три дня дождя ↔ локальный файл",
                shouldLoadCover = false,
                hasUnresolvedMatchCandidate = true,
            ),
            TrackListItemUiModel(
                key = "third:fourth",
                trackId = "third:fourth",
                title = "Спортик  ↔  Sportik",
                artist = "ОТКЛОНЕНО · SxmPra ↔ Unknown",
                shouldLoadCover = false,
            ),
        ),
        onBackClick = {},
        onItemClick = {},
        loadCover = { null },
    )
}

private val SongMatchBackground = Color(0xFF03040F)
private val SongMatchPink = Color(0xFFFF00BF)
private val SongMatchCyan = Color(0xFF00DFFF)
