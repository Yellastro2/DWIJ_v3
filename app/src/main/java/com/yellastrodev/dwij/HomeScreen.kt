package com.yellastrodev.dwij

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay

/**
 * Верхняя Compose-часть домашнего экрана на общем цвете фона приложения:
 * квадратная кнопка плеера и сетка основных разделов, которая постепенно
 * заменяет прежний XML GridLayout.
 */
@Composable
fun HomeScreen(
    onPlayerClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onTracksClick: () -> Unit,
    onWaveClick: () -> Unit,
    onAllTracksClick: () -> Unit,
    onRadialMenuItemClick: (RadialMenuItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isRadialMenuVisible by remember { mutableStateOf(false) }
    var isPlayerPressed by remember { mutableStateOf(false) }
    val radialMenuItems = homeRadialMenuItems()

    LaunchedEffect(isPlayerPressed) {
        if (isPlayerPressed) {
            delay(RADIAL_MENU_START_DELAY_MILLIS)
            if (isPlayerPressed) {
                isRadialMenuVisible = true
            }
        } else {
            isRadialMenuVisible = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colorResource(R.color.background)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            PlayerIconButton(
                modifier = Modifier.fillMaxSize(),
                expanded = isRadialMenuVisible,
                expandedAccentOuterRadiusFraction = HOME_RADIAL_MENU_OUTER_RADIUS_FRACTION,
                onLongClick = {
                    // Отделяет long-press от обычного клика.
                },
                onPressedChange = { isPressed ->
                    isPlayerPressed = isPressed
                },
                onClick = onPlayerClick,
            )
            RadialMenu(
                items = radialMenuItems,
                visible = isRadialMenuVisible,
                onItemClick = { item ->
                    isRadialMenuVisible = false
                    onRadialMenuItemClick(item)
                },
                onDismiss = {
                    isRadialMenuVisible = false
                },
                outerRadiusFraction = HOME_RADIAL_MENU_OUTER_RADIUS_FRACTION,
                animationStyle = RadialMenuAnimationStyle.GlitchFlicker,
                modifier = Modifier.fillMaxSize(),
            )
        }
        HomeMenuGrid(
            onPlaylistsClick = onPlaylistsClick,
            onTracksClick = onTracksClick,
            onWaveClick = onWaveClick,
            onAllTracksClick = onAllTracksClick,
        )
    }
}

private const val RADIAL_MENU_START_DELAY_MILLIS = 5L
private const val HOME_RADIAL_MENU_OUTER_RADIUS_FRACTION = 0.49f

@Composable
private fun homeRadialMenuItems(): List<RadialMenuItem> {
    val roadTitle = stringResource(R.string.radial_menu_road)
    val focusTitle = stringResource(R.string.radial_menu_focus)
    val calmTitle = stringResource(R.string.radial_menu_calm)
    val favoriteTitle = stringResource(R.string.radial_menu_favorite)
    val radioTitle = stringResource(R.string.radial_menu_radio)
    val partyTitle = stringResource(R.string.radial_menu_party)

    return remember(
        roadTitle,
        focusTitle,
        calmTitle,
        favoriteTitle,
        radioTitle,
        partyTitle,
    ) {
        listOf(
            RadialMenuItem("road", roadTitle, Color(0xFFFF2D82)),
            RadialMenuItem("focus", focusTitle, Color(0xFF00BEFF)),
//            RadialMenuItem("calm", calmTitle, Color(0xFFB737FF)),
            RadialMenuItem("favorite", favoriteTitle, Color(0xFFFF2D96)),
            RadialMenuItem("radio", radioTitle, Color(0xFF00E6DC)),
            RadialMenuItem("party", partyTitle, Color(0xFFFF9100)),
        )
    }
}

@Composable
private fun HomeMenuGrid(
    onPlaylistsClick: () -> Unit,
    onTracksClick: () -> Unit,
    onWaveClick: () -> Unit,
    onAllTracksClick: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .height(236.dp),
        contentPadding = PaddingValues(8.dp),
        userScrollEnabled = false,
    ) {
        item {
            HomeMenuCard(
                textureRes = R.drawable.bg_calm_texture,
                frameRes = R.drawable.dvizh_calm_glitch_frame_contour,
                title = stringResource(R.string.home_playlists),
                onClick = onPlaylistsClick,
                modifier = Modifier.height(110.dp),
            )
        }
        item {
            HomeMenuCard(
                textureRes = R.drawable.bg_drive_texture,
                frameRes = R.drawable.dvizh_drive_glitch_frame_contour,
                title = stringResource(R.string.home_tracks),
                onClick = onTracksClick,
                modifier = Modifier.height(110.dp),
            )
        }
        item {
            HomeMenuCard(
                textureRes = R.drawable.bg_focus_texture,
                frameRes = R.drawable.dvizh_focus_glitch_frame_contour,
                title = stringResource(R.string.home_wave),
                onClick = onWaveClick,
                modifier = Modifier.height(110.dp),
            )
        }
        item {
            HomeMenuCard(
                textureRes = R.drawable.bg_party_texture,
                frameRes = R.drawable.dvizh_orange_glitch_frame_contour,
                title = stringResource(R.string.home_all_tracks),
                onClick = onAllTracksClick,
                modifier = Modifier.height(110.dp),
            )
        }
    }
}

/** Накладывает совместимые с Compose векторные слои текстуры и рамки. */
@Composable
private fun HomeMenuCard(
    @DrawableRes textureRes: Int,
    @DrawableRes frameRes: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(5.dp)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Image(
            painter = painterResource(textureRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            painter = painterResource(frameRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 14.dp),
        )
    }
}

@Preview(
    name = "Home screen",
    widthDp = 360,
    heightDp = 596,
)
@Composable
private fun HomeScreenPreview() {
    HomeScreen(
        modifier = Modifier.fillMaxSize(),
        onPlayerClick = {},
        onPlaylistsClick = {},
        onTracksClick = {},
        onWaveClick = {},
        onAllTracksClick = {},
    )
}
