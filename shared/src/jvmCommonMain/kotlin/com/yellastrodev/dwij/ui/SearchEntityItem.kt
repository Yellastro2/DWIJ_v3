package com.yellastrodev.dwij.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.models.SearchEntityKind
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors

/** Компактная строка альбома или артиста с круглой обложкой и одной строкой метаданных. */
@Composable
fun SearchEntityItem(
    item: SearchResultItemUiModel.Entity,
    loadCover: suspend (key: String, uri: String) -> ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        SearchEntityAvatar(item = item, loadCover = loadCover)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        ) {
            Text(
                text = item.title,
                color = DwijColors.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = searchEntityMeta(item),
                color = DwijColors.SearchEntityMeta,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun SearchEntityAvatar(
    item: SearchResultItemUiModel.Entity,
    loadCover: suspend (key: String, uri: String) -> ImageBitmap?,
) {
    var bitmap by remember(item.key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.key, item.coverUri) {
        bitmap = null
        val uri = item.coverUri ?: return@LaunchedEffect
        bitmap = try {
            loadCover(item.key, uri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(DwijColors.SearchEntityAvatarBackground),
    ) {
        bitmap?.let { loaded ->
            Image(
                bitmap = loaded,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            text = item.title.take(1).uppercase(),
            color = DwijColors.SearchEntityAvatarInitial,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun searchEntityMeta(item: SearchResultItemUiModel.Entity): String = when {
    item.artistNames.isNotEmpty() -> item.artistNames.joinToString(", ")
    item.kind == SearchEntityKind.Artist && item.trackCount != null ->
        stringResource(Res.string.search_artist_track_count, item.trackCount!!)
    item.genres.isNotEmpty() -> item.genres.take(2).joinToString(" · ")
    item.trackCount != null -> stringResource(Res.string.search_album_track_count, item.trackCount!!)
    item.likesCount != null -> stringResource(Res.string.search_entity_likes_count, item.likesCount!!)
    item.kind == SearchEntityKind.Album -> stringResource(Res.string.search_entity_album)
    else -> stringResource(Res.string.search_entity_artist)
}

private const val TAG = "SearchEntityItem"
