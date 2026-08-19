package com.yellastrodev.dwij.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.bg_calm_texture
import com.yellastrodev.dwij.resources.bg_drive_texture
import com.yellastrodev.dwij.resources.bg_focus_texture
import com.yellastrodev.dwij.resources.bg_party_texture
import com.yellastrodev.dwij.resources.bg_home_source_chip
import com.yellastrodev.dwij.resources.bg_home_source_chip_selected
import com.yellastrodev.dwij.resources.catalog_albums_subtitle
import com.yellastrodev.dwij.resources.catalog_albums_title
import com.yellastrodev.dwij.resources.catalog_artists_subtitle
import com.yellastrodev.dwij.resources.catalog_artists_title
import com.yellastrodev.dwij.resources.catalog_genres_subtitle
import com.yellastrodev.dwij.resources.catalog_genres_title
import com.yellastrodev.dwij.resources.catalog_liked_subtitle
import com.yellastrodev.dwij.resources.catalog_liked_title
import com.yellastrodev.dwij.resources.catalog_playlists_subtitle
import com.yellastrodev.dwij.resources.catalog_playlists_title
import com.yellastrodev.dwij.resources.catalog_recent_subtitle
import com.yellastrodev.dwij.resources.catalog_recent_title
import com.yellastrodev.dwij.resources.catalog_title
import com.yellastrodev.dwij.resources.dvizh_calm_glitch_frame_contour
import com.yellastrodev.dwij.resources.dvizh_drive_glitch_frame_contour
import com.yellastrodev.dwij.resources.dvizh_focus_glitch_frame_contour
import com.yellastrodev.dwij.resources.dvizh_orange_glitch_frame_contour
import com.yellastrodev.dwij.resources.home_source_local
import com.yellastrodev.dwij.resources.home_source_yandex_music
import com.yellastrodev.dwij.resources.img_albums
import com.yellastrodev.dwij.resources.img_artists
import com.yellastrodev.dwij.resources.img_gengers
import com.yellastrodev.dwij.resources.img_history
import com.yellastrodev.dwij.resources.img_likes
import com.yellastrodev.dwij.resources.img_playlists
import com.yellastrodev.dwij.ui.theme.DwijColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Экран каталога с переключателем источника и плитками разделов.
 * Каждая плитка получает действие только после подключения соответствующего экрана.
 */
@Composable
fun CatalogScreen(
    selectedSource: HomeMusicSource,
    onSourceSelected: (HomeMusicSource) -> Unit,
    onPlaylistsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onAlbumsClick: () -> Unit,
    onLikedClick: () -> Unit,
    onRecentClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DwijColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(top = 14.dp, bottom = 12.dp),
    ) {
        Text(
            text = stringResource(Res.string.catalog_title),
            color = DwijColors.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        CatalogSourceSelector(
            selectedSource = selectedSource,
            onSourceSelected = onSourceSelected,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CatalogCardRow(
            height = 144.dp,
            first = CatalogCardSpec(
                textureRes = Res.drawable.bg_calm_texture,
                frameRes = Res.drawable.dvizh_calm_glitch_frame_contour,
                artworkRes = Res.drawable.img_playlists,
                artworkWidthFraction = 0.58f,
                artworkHeightFraction = 0.72f,
                title = stringResource(Res.string.catalog_playlists_title),
                subtitle = stringResource(Res.string.catalog_playlists_subtitle),
                onClick = onPlaylistsClick,
            ),
            second = CatalogCardSpec(
                textureRes = Res.drawable.bg_focus_texture,
                frameRes = Res.drawable.dvizh_focus_glitch_frame_contour,
                artworkRes = Res.drawable.img_artists,
                artworkWidthFraction = 0.68f,
                artworkHeightFraction = 0.92f,
                title = stringResource(Res.string.catalog_artists_title),
                subtitle = stringResource(Res.string.catalog_artists_subtitle),
                onClick = onArtistsClick,
            ),
        )
        CatalogCardRow(
            height = 178.dp,
            firstWeight = 0.73f,
            spacing = 0.dp,
            first = CatalogCardSpec(
                textureRes = Res.drawable.bg_party_texture,
                frameRes = Res.drawable.dvizh_orange_glitch_frame_contour,
                artworkRes = Res.drawable.img_albums,
                artworkWidthFraction = 0.82f,
                artworkHeightFraction = 0.68f,
                title = stringResource(Res.string.catalog_albums_title),
                subtitle = stringResource(Res.string.catalog_albums_subtitle),
                onClick = onAlbumsClick,
            ),
            second = CatalogCardSpec(
                textureRes = Res.drawable.bg_drive_texture,
                frameRes = Res.drawable.dvizh_drive_glitch_frame_contour,
                artworkRes = Res.drawable.img_likes,
                artworkWidthFraction = 0.76f,
                artworkHeightFraction = 0.72f,
                title = stringResource(Res.string.catalog_liked_title),
                subtitle = stringResource(Res.string.catalog_liked_subtitle),
                onClick = onLikedClick,
            ),
        )
        CatalogCardRow(
            height = 92.dp,
            firstWeight = 0.9f,
            spacing = 3.dp,
            first = CatalogCardSpec(
                textureRes = Res.drawable.bg_focus_texture,
                frameRes = Res.drawable.dvizh_focus_glitch_frame_contour,
                artworkRes = Res.drawable.img_gengers,
                artworkWidthFraction = 0.48f,
                artworkHeightFraction = 0.68f,
                title = stringResource(Res.string.catalog_genres_title),
                subtitle = stringResource(Res.string.catalog_genres_subtitle),
                compact = true,
            ),
            second = CatalogCardSpec(
                textureRes = Res.drawable.bg_calm_texture,
                frameRes = Res.drawable.dvizh_calm_glitch_frame_contour,
                artworkRes = Res.drawable.img_history,
                artworkWidthFraction = 0.48f,
                artworkHeightFraction = 0.72f,
                title = stringResource(Res.string.catalog_recent_title),
                subtitle = stringResource(Res.string.catalog_recent_subtitle),
                compact = true,
                onClick = onRecentClick,
            ),
        )
    }
}

/** Два полностью видимых переключателя источника, как в макете каталога. */
@Composable
private fun CatalogSourceSelector(
    selectedSource: HomeMusicSource,
    onSourceSelected: (HomeMusicSource) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 12.dp),
    ) {
        CatalogSourceOption(
            title = stringResource(Res.string.home_source_local),
            source = HomeMusicSource.Local,
            selectedSource = selectedSource,
            onSourceSelected = onSourceSelected,
            modifier = Modifier.weight(1f),
        )
        CatalogSourceOption(
            title = stringResource(Res.string.home_source_yandex_music),
            source = HomeMusicSource.Yandex,
            selectedSource = selectedSource,
            onSourceSelected = onSourceSelected,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CatalogSourceOption(
    title: String,
    source: HomeMusicSource,
    selectedSource: HomeMusicSource,
    onSourceSelected: (HomeMusicSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = source == selectedSource
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .semantics { selected = isSelected }
            .clickable(role = Role.Tab) {
                if (!isSelected) onSourceSelected(source)
            },
    ) {
        Image(
            painter = painterResource(
                if (isSelected) {
                    Res.drawable.bg_home_source_chip_selected
                } else {
                    Res.drawable.bg_home_source_chip
                },
            ),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = title,
            color = if (isSelected) DwijColors.White else DwijColors.MutedText,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

private data class CatalogCardSpec(
    val textureRes: DrawableResource,
    val frameRes: DrawableResource,
    val artworkRes: DrawableResource? = null,
    val artworkWidthFraction: Float = 1f,
    val artworkHeightFraction: Float = 1f,
    val title: String,
    val subtitle: String,
    val compact: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

private const val CATALOG_ARTWORK_SCALE = 0.9f

@Composable
private fun CatalogCardRow(
    height: Dp,
    first: CatalogCardSpec,
    second: CatalogCardSpec,
    firstWeight: Float = 1f,
    secondWeight: Float = 1f,
    spacing: Dp = 2.dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 9.dp),
    ) {
        CatalogCard(
            spec = first,
            modifier = Modifier.weight(firstWeight),
        )
        CatalogCard(
            spec = second,
            modifier = Modifier.weight(secondWeight),
        )
    }
}

@Composable
private fun CatalogCard(
    spec: CatalogCardSpec,
    modifier: Modifier = Modifier,
) {
    val interactionModifier = spec.onClick?.let { onClick ->
        Modifier.clickable(
            role = Role.Button,
            onClick = onClick,
        )
    } ?: Modifier

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(interactionModifier),
    ) {
        Image(
            painter = painterResource(spec.textureRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        spec.artworkRes?.let { artworkRes ->
            Image(
                painter = painterResource(artworkRes),
                contentDescription = null,
                alignment = Alignment.BottomEnd,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(spec.artworkWidthFraction * CATALOG_ARTWORK_SCALE)
                    .fillMaxHeight(spec.artworkHeightFraction * CATALOG_ARTWORK_SCALE)
                    .padding(bottom = 8.dp),
            )
        }
        Image(
            painter = painterResource(spec.frameRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(if (spec.compact) 0.68f else 0.7f)
                .padding(
                    start = 16.dp,
                    top = if (spec.compact) 12.dp else 16.dp,
                ),
        ) {
            Text(
                text = spec.title,
                color = DwijColors.White,
                fontSize = if (spec.compact) 13.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(if (spec.compact) 4.dp else 14.dp))
            Text(
                text = spec.subtitle,
                color = DwijColors.MutedText,
                fontSize = if (spec.compact) 10.sp else 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = if (spec.compact) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(
    name = "Catalog screen",
    widthDp = 360,
    heightDp = 604,
    backgroundColor = DwijColors.BackgroundArgb,
    showBackground = true,
)
@Composable
private fun CatalogScreenPreview() {
    CatalogScreen(
        selectedSource = HomeMusicSource.Yandex,
        onSourceSelected = {},
        onPlaylistsClick = {},
        onArtistsClick = {},
        onAlbumsClick = {},
        onLikedClick = {},
        onRecentClick = {},
    )
}
