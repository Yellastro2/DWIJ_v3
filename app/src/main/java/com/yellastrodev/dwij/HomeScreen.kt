package com.yellastrodev.dwij

import android.os.SystemClock
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Полный Compose-интерфейс домашнего экрана: радиальное меню, сетка разделов,
 * компактный плеер текущего трека и закреплённая нижняя навигация.
 */
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onTracksClick: () -> Unit,
    onWaveClick: () -> Unit,
    onAllTracksClick: () -> Unit,
    onCatalogClick: () -> Unit,
    onPlayerOpenClick: () -> Unit,
    onPlayerPlayPauseClick: () -> Unit,
    onPlayerNextClick: () -> Unit,
    player: HomeCompactPlayerUiState?,
    modifier: Modifier = Modifier,
) {
    var isRadialMenuVisible by remember { mutableStateOf(false) }
    var isPlayerPressed by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(HomeNavigationTab.Main) }
    val navigationTimingTracker = remember { HomeNavigationTimingTracker() }
    val radialMenuItems = homeRadialMenuItems()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val playerActionMessage = stringResource(
        R.string.home_radial_action_triggered,
        1,
        stringResource(R.string.home_radial_action_player),
    )
    val radialActionMessages = radialMenuItems.mapIndexed { index, item ->
        item.id to stringResource(
            R.string.home_radial_action_triggered,
            index + 2,
            item.title,
        )
    }.toMap()
    fun showActionSnackbar(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    BackHandler(enabled = selectedTab == HomeNavigationTab.Search) {
        navigationTimingTracker.onClick(
            targetLabel = "Главная",
            targetTab = HomeNavigationTab.Main,
            currentTab = selectedTab,
        )
        selectedTab = HomeNavigationTab.Main
        navigationTimingTracker.onStateAssigned(HomeNavigationTab.Main)
    }

    SideEffect {
        navigationTimingTracker.onCompositionCommitted(selectedTab)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize(),
        containerColor = colorResource(R.color.background),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorResource(R.color.background)),
            ) {
                player?.let { playerState ->
                    HomeCompactPlayer(
                        player = playerState,
                        onOpenClick = onPlayerOpenClick,
                        onPlayPauseClick = onPlayerPlayPauseClick,
                        onNextClick = onPlayerNextClick,
                    )
                }
                HomeBottomNavigation(
                    selectedTab = selectedTab,
                    navigationTimingTracker = navigationTimingTracker,
                    onCatalogClick = {
                        navigationTimingTracker.onClick(
                            targetLabel = "Каталог",
                            targetTab = null,
                            currentTab = selectedTab,
                        )
                        onCatalogClick()
                    },
                    onMainClick = {
                        navigationTimingTracker.onClick(
                            targetLabel = "Главная",
                            targetTab = HomeNavigationTab.Main,
                            currentTab = selectedTab,
                        )
                        selectedTab = HomeNavigationTab.Main
                        navigationTimingTracker.onStateAssigned(HomeNavigationTab.Main)
                    },
                    onSearchClick = {
                        navigationTimingTracker.onClick(
                            targetLabel = "Поиск",
                            targetTab = HomeNavigationTab.Search,
                            currentTab = selectedTab,
                        )
                        isRadialMenuVisible = false
                        isPlayerPressed = false
                        selectedTab = HomeNavigationTab.Search
                        navigationTimingTracker.onStateAssigned(HomeNavigationTab.Search)
                    },
                )
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .drawWithContent {
                    drawContent()
                    navigationTimingTracker.onFirstDraw(selectedTab)
                },
        ) {
            when (selectedTab) {
                HomeNavigationTab.Main -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    ) {
                        PlayerIconButton(
                            modifier = Modifier.fillMaxSize(),
                            expanded = isRadialMenuVisible,
                            pressed = isPlayerPressed,
                            gesturesEnabled = false,
                            expandedAccentOuterRadiusFraction =
                                HOME_RADIAL_MENU_OUTER_RADIUS_FRACTION,
                            onClick = {},
                        )
                        RadialMenu(
                            items = radialMenuItems,
                            visible = isRadialMenuVisible,
                            onPrimaryClick = {
                                showActionSnackbar(playerActionMessage)
                            },
                            onVisualActivation = {
                                isRadialMenuVisible = true
                            },
                            onPressChange = { isPressed ->
                                isPlayerPressed = isPressed
                            },
                            onItemClick = { item ->
                                isRadialMenuVisible = false
                                radialActionMessages[item.id]?.let(::showActionSnackbar)
                            },
                            onDismiss = {
                                isRadialMenuVisible = false
                            },
                            outerRadiusFraction = HOME_RADIAL_MENU_OUTER_RADIUS_FRACTION,
                            animationStyle = RadialMenuAnimationStyle.GlitchFlicker,
                            modifier = Modifier.fillMaxSize(),
                        )
                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 15.dp, end = 15.dp)
                                .size(50.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(
                                    R.string.settings_button_content_description,
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    HomeMenuGrid(
                        onPlaylistsClick = onPlaylistsClick,
                        onTracksClick = onTracksClick,
                        onWaveClick = onWaveClick,
                        onAllTracksClick = onAllTracksClick,
                    )
                }
                HomeNavigationTab.Search -> SearchPlaceholderScreen()
            }
        }
    }
}

/** Данные, необходимые компактному плееру без зависимости UI от ViewModel. */
data class HomeCompactPlayerUiState(
    val title: String,
    val artist: String,
    val cover: ImageBitmap?,
    val isPlaying: Boolean,
    val currentPositionMillis: Long,
    val durationMillis: Long,
)

private const val HOME_RADIAL_MENU_OUTER_RADIUS_FRACTION = 0.49f

private enum class HomeNavigationTab {
    Main,
    Search,
}

/** Собирает временные метки от касания вкладки до первого draw нового экрана. */
private class HomeNavigationTimingTracker {
    private var pressTarget: String? = null
    private var pressStartedAtNanos: Long = 0L
    private var clickAtNanos: Long = 0L
    private var pendingTarget: HomeNavigationTab? = null
    private var compositionLogged = false
    private var drawLogged = false

    fun onPress(targetLabel: String) {
        pressTarget = targetLabel
        pressStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        Log.d(
            HOME_NAVIGATION_TIMING_TAG,
            "[onPress] Палец нажат: цель=$targetLabel",
        )
    }

    fun onRelease(targetLabel: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        Log.d(
            HOME_NAVIGATION_TIMING_TAG,
            "[onRelease] Палец отпущен: цель=$targetLabel, " +
                "после DOWN=${elapsedMillis(pressStartedAtNanos, now)} мс",
        )
    }

    fun onCancel(targetLabel: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        Log.d(
            HOME_NAVIGATION_TIMING_TAG,
            "[onCancel] Нажатие отменено: цель=$targetLabel, " +
                "после DOWN=${elapsedMillis(pressStartedAtNanos, now)} мс",
        )
        pressTarget = null
        pressStartedAtNanos = 0L
    }

    fun onClick(
        targetLabel: String,
        targetTab: HomeNavigationTab?,
        currentTab: HomeNavigationTab,
    ) {
        val now = SystemClock.elapsedRealtimeNanos()
        val fromPress = if (pressTarget == targetLabel) {
            "${elapsedMillis(pressStartedAtNanos, now)} мс"
        } else {
            "нет DOWN"
        }
        Log.d(
            HOME_NAVIGATION_TIMING_TAG,
            "[onClick] onClick вызван: цель=$targetLabel, " +
                "после DOWN=$fromPress, текущая вкладка=$currentTab",
        )

        if (targetTab == null || targetTab == currentTab) {
            pendingTarget = null
            clickAtNanos = 0L
            return
        }

        pendingTarget = targetTab
        clickAtNanos = now
        compositionLogged = false
        drawLogged = false
    }

    fun onStateAssigned(targetTab: HomeNavigationTab) {
        if (pendingTarget != targetTab) return
        Log.d(
            HOME_NAVIGATION_TIMING_TAG,
            "[onStateAssigned] Вкладка записана в state: цель=$targetTab, " +
                "после onClick=${elapsedMillis(clickAtNanos)} мс",
        )
    }

    fun onCompositionCommitted(tab: HomeNavigationTab) {
        if (pendingTarget != tab || compositionLogged) return
        compositionLogged = true
        Log.d(
            HOME_NAVIGATION_TIMING_TAG,
            "[onCompositionCommitted] Композиция подтверждена: вкладка=$tab, " +
                "после onClick=${elapsedMillis(clickAtNanos)} мс",
        )
    }

    fun onFirstDraw(tab: HomeNavigationTab) {
        if (pendingTarget != tab || drawLogged) return
        drawLogged = true
        val now = SystemClock.elapsedRealtimeNanos()
        Log.d(
            HOME_NAVIGATION_TIMING_TAG,
            "[onFirstDraw] Первый draw: вкладка=$tab, " +
                "после onClick=${elapsedMillis(clickAtNanos, now)} мс, " +
                "после DOWN=${elapsedMillis(pressStartedAtNanos, now)} мс",
        )
        pendingTarget = null
        clickAtNanos = 0L
        pressTarget = null
        pressStartedAtNanos = 0L
    }

    private fun elapsedMillis(
        startedAtNanos: Long,
        finishedAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): String {
        if (startedAtNanos <= 0L) return "неизвестно"
        val durationMillis = (finishedAtNanos - startedAtNanos) / 1_000_000.0
        return String.format(Locale.US, "%.2f", durationMillis)
    }
}

private const val HOME_NAVIGATION_TIMING_TAG = "HomeNavigationTiming"

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
            RadialMenuItem("calm", calmTitle, Color(0xFFB737FF)),
            RadialMenuItem("favorite", favoriteTitle, Color(0xFFFF2D96)),
            RadialMenuItem("radio", radioTitle, Color(0xFF00E6DC)),
            RadialMenuItem("party", partyTitle, Color(0xFFFF9100)),
        )
    }
}

/** Карточка текущего трека, закреплённая непосредственно над нижней навигацией. */
@Composable
private fun HomeCompactPlayer(
    player: HomeCompactPlayerUiState,
    onOpenClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(
                role = Role.Button,
                onClick = onOpenClick,
            ),
    ) {
        val detailsWidth = (maxWidth - 22.dp - 64.dp - 10.dp - 84.dp)
            .coerceAtLeast(72.dp)
        Image(
            painter = painterResource(R.drawable.bg_home_compact_player),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            Box(modifier = Modifier.size(64.dp)) {
                if (player.cover != null) {
                    Image(
                        bitmap = player.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(9.dp)),
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.ic_home_player_placeholder),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Image(
                    painter = painterResource(R.drawable.dvizh_album_thumb_glitch_frame_contour),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.width(detailsWidth)) {
                Text(
                    text = player.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = player.artist,
                    color = Color(0xFFAAAFC0),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(7.dp))
                HomePlayerProgress(
                    currentPositionMillis = player.currentPositionMillis,
                    durationMillis = player.durationMillis,
                )
            }

            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier.size(42.dp),
            ) {
                Image(
                    painter = painterResource(
                        if (player.isPlaying) {
                            R.drawable.ic_home_player_pause
                        } else {
                            R.drawable.ic_home_player_play
                        },
                    ),
                    contentDescription = stringResource(
                        if (player.isPlaying) {
                            R.string.home_player_pause_content_description
                        } else {
                            R.string.home_player_play_content_description
                        },
                    ),
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(
                onClick = onNextClick,
                modifier = Modifier.size(42.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_home_player_next),
                    contentDescription = stringResource(
                        R.string.home_player_next_content_description,
                    ),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Линейный прогресс с текущим и полным временем трека. */
@Composable
private fun HomePlayerProgress(
    currentPositionMillis: Long,
    durationMillis: Long,
) {
    val safeDuration = durationMillis.coerceAtLeast(0L)
    val safePosition = currentPositionMillis.coerceAtLeast(0L).let { position ->
        if (safeDuration > 0L) position.coerceAtMost(safeDuration) else position
    }
    val progress = if (safeDuration > 0L) {
        safePosition.toFloat() / safeDuration.toFloat()
    } else {
        0f
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val progressWidth = (maxWidth - 62.dp).coerceAtLeast(24.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatPlayerTime(safePosition),
                color = Color(0xFFCDD0DC),
                fontSize = 9.sp,
            )
            Spacer(modifier = Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .width(progressWidth)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF303543)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(Color(0xFFFF178F)),
                )
            }
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = formatPlayerTime(safeDuration),
                color = Color(0xFFCDD0DC),
                fontSize = 9.sp,
            )
        }
    }
}

/** Три пункта нижней навигации с переключением активной главной или поиска. */
@Composable
private fun HomeBottomNavigation(
    selectedTab: HomeNavigationTab,
    navigationTimingTracker: HomeNavigationTimingTracker,
    onCatalogClick: () -> Unit,
    onMainClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
    ) {
        val itemWidth = maxWidth / 3
        Image(
            painter = painterResource(R.drawable.bg_home_bottom_navigation),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            HomeBottomNavigationItem(
                iconRes = R.drawable.ic_home_nav_catalog,
                title = stringResource(R.string.home_navigation_catalog),
                selected = false,
                onPress = {
                    navigationTimingTracker.onPress("Каталог")
                },
                onRelease = {
                    navigationTimingTracker.onRelease("Каталог")
                },
                onCancel = {
                    navigationTimingTracker.onCancel("Каталог")
                },
                onClick = onCatalogClick,
                modifier = Modifier.width(itemWidth),
            )
            HomeBottomNavigationItem(
                iconRes = R.drawable.ic_home_nav_main,
                title = stringResource(R.string.home_navigation_main),
                selected = selectedTab == HomeNavigationTab.Main,
                onPress = {
                    navigationTimingTracker.onPress("Главная")
                },
                onRelease = {
                    navigationTimingTracker.onRelease("Главная")
                },
                onCancel = {
                    navigationTimingTracker.onCancel("Главная")
                },
                onClick = onMainClick,
                modifier = Modifier.width(itemWidth),
            )
            HomeBottomNavigationItem(
                iconRes = R.drawable.ic_home_nav_search,
                title = stringResource(R.string.home_navigation_search),
                selected = selectedTab == HomeNavigationTab.Search,
                onPress = {
                    navigationTimingTracker.onPress("Поиск")
                },
                onRelease = {
                    navigationTimingTracker.onRelease("Поиск")
                },
                onCancel = {
                    navigationTimingTracker.onCancel("Поиск")
                },
                onClick = onSearchClick,
                modifier = Modifier.width(itemWidth),
            )
        }
    }
}

@Composable
private fun HomeBottomNavigationItem(
    @DrawableRes iconRes: Int,
    title: String,
    selected: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) Color(0xFFFF178F) else Color(0xFF9095A7)
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnCancel by rememberUpdatedState(onCancel)

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> currentOnPress()
                is PressInteraction.Release -> currentOnRelease()
                is PressInteraction.Cancel -> currentOnCancel()
                else -> Unit
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(top = 12.dp, bottom = 7.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = title,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (selected) {
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(2.dp)
                    .background(Color(0xFFFF178F)),
            )
        }
    }
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
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
    heightDp = 780,
)
@Composable
private fun HomeScreenPreview() {
    HomeScreen(
        modifier = Modifier.fillMaxSize(),
        onSettingsClick = {},
        onPlaylistsClick = {},
        onTracksClick = {},
        onWaveClick = {},
        onAllTracksClick = {},
        onCatalogClick = {},
        onPlayerOpenClick = {},
        onPlayerPlayPauseClick = {},
        onPlayerNextClick = {},
        player = HomeCompactPlayerUiState(
            title = "Ночной город",
            artist = "Три дня дождя",
            cover = null,
            isPlaying = true,
            currentPositionMillis = 84_000L,
            durationMillis = 225_000L,
        ),
    )
}
