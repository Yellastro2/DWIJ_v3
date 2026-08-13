package com.yellastrodev.dwij.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Полностью Compose-экран авторизации, кэша и платформенных настроек.
 *
 * Экран не читает платформенные сведения и не запускает OAuth самостоятельно: хост передаёт
 * версию сборки, готовое состояние и обработчики, поэтому интерфейс можно превьюить отдельно.
 */
@Composable
fun SettingsScreen(
    appVersion: String,
    yandexLogin: String?,
    isAuthInProgress: Boolean,
    cacheLimitMb: Int,
    minCacheMb: Int,
    maxCacheMb: Int,
    occupiedCacheSize: String,
    musicDirectories: List<String>?,
    onBackClick: () -> Unit,
    onAuthClick: () -> Unit,
    onCacheLimitCommitted: (megabytes: Int) -> Unit,
    onProxyClick: () -> Unit,
    onMusicDirectoriesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val shrinkWarning = stringResource(Res.string.settings_cache_shrink_warning)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DwijColors.Background)
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            SettingsHeader(onBackClick = onBackClick)
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(
                        rememberScrollState(),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SettingsYandexCard(
                    login = yandexLogin,
                    isAuthInProgress = isAuthInProgress,
                    onAuthClick = onAuthClick,
                )
                SettingsProxyCard(
                    onClick =
                        onProxyClick,
                )
                SettingsCacheCard(
                    cacheLimitMb = cacheLimitMb,
                    minCacheMb = minCacheMb,
                    maxCacheMb = maxCacheMb,
                    occupiedCacheSize = occupiedCacheSize,
                    onCacheLimitCommitted = { newLimitMb ->
                        val shrinksCache = newLimitMb < cacheLimitMb
                        onCacheLimitCommitted(newLimitMb)
                        if (shrinksCache) {
                            snackbarScope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(shrinkWarning)
                            }
                        }
                    },
                )
                musicDirectories?.let { directories ->
                    SettingsMusicDirectoriesCard(
                        directoryCount =
                            directories.size,
                        onClick =
                            onMusicDirectoriesClick,
                    )
                }
            }
            Text(
                text =
                    stringResource(
                        Res.string.settings_version,
                        appVersion,
                    ),
                color =
                    Color.White.copy(
                        alpha = 0.42f,
                    ),
                fontSize =
                    12.sp,
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(
                            horizontal = 14.dp,
                            vertical = 10.dp,
                        ),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Открывает диалог прокси отдельных внешних сервисов. */
@Composable
private fun SettingsProxyCard(
    onClick: () -> Unit,
) {
    SettingsTextureCard(
        textureRes =
            Res.drawable.bg_focus_texture,
        accent =
            DwijColors.Pink,
        modifier =
            Modifier
                .height(92.dp)
                .clickable(
                    onClick =
                        onClick,
                ),
    ) {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = 18.dp,
                    ),
        ) {
            Column(
                modifier =
                    Modifier.weight(1f),
            ) {
                Text(
                    text =
                        stringResource(
                            Res.string.settings_proxy_title,
                        ),
                    color =
                        DwijColors.White,
                    fontSize =
                        18.sp,
                    fontWeight =
                        FontWeight.Bold,
                )
                Text(
                    text =
                        stringResource(
                            Res.string.settings_proxy_description,
                        ),
                    color =
                        DwijColors.SecondaryText,
                    fontSize =
                        12.sp,
                    modifier =
                        Modifier.padding(
                            top = 5.dp,
                        ),
                )
            }
            Text(
                text =
                    "›",
                color =
                    DwijColors.Pink,
                fontSize =
                    30.sp,
                fontWeight =
                    FontWeight.Light,
            )
        }
    }
}

/** Открывает desktop-диалог управления каталогами локальной музыки. */
@Composable
private fun SettingsMusicDirectoriesCard(
    directoryCount: Int,
    onClick: () -> Unit,
) {
    SettingsTextureCard(
        textureRes =
            Res.drawable.bg_focus_texture,
        accent =
            DwijColors.CyanBright,
        modifier =
            Modifier
                .height(92.dp)
                .clickable(
                    onClick =
                        onClick,
                ),
    ) {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = 18.dp,
                    ),
        ) {
            Column(
                modifier =
                    Modifier.weight(1f),
            ) {
                Text(
                    text =
                        stringResource(
                            Res.string.settings_music_directories_title,
                        ),
                    color =
                        DwijColors.White,
                    fontSize =
                        18.sp,
                    fontWeight =
                        FontWeight.Bold,
                )
                Text(
                    text =
                        stringResource(
                            Res.string.settings_music_directories_description,
                            directoryCount,
                        ),
                    color =
                        DwijColors.SecondaryText,
                    fontSize =
                        12.sp,
                    modifier =
                        Modifier.padding(
                            top = 5.dp,
                        ),
                )
            }
            Text(
                text =
                    "›",
                color =
                    DwijColors.CyanBright,
                fontSize =
                    30.sp,
                fontWeight =
                    FontWeight.Light,
            )
        }
    }
}

/** Рисует компактную верхнюю панель с глич-стрелкой возврата. */
@Composable
private fun SettingsHeader(onBackClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
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
                    color = DwijColors.CyanBright,
                    start = Offset(size.width * 0.72f + 1.2.dp.toPx(), size.height * 0.17f),
                    end = Offset(size.width * 0.29f + 1.2.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = DwijColors.Pink,
                    start = Offset(size.width * 0.72f - 1.2.dp.toPx(), size.height * 0.83f),
                    end = Offset(size.width * 0.29f - 1.2.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = DwijColors.White,
                    start = Offset(size.width * 0.72f, size.height * 0.17f),
                    end = Offset(size.width * 0.29f, size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = DwijColors.White,
                    start = Offset(size.width * 0.29f, size.height * 0.5f),
                    end = Offset(size.width * 0.72f, size.height * 0.83f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
        Text(
            text = stringResource(Res.string.settings_title),
            color = DwijColors.White,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Показывает состояние аккаунта и запускает вход либо выход из Яндекс Музыки. */
@Composable
private fun SettingsYandexCard(
    login: String?,
    isAuthInProgress: Boolean,
    onAuthClick: () -> Unit,
) {
    SettingsTextureCard(
        textureRes = Res.drawable.bg_party_texture,
        accent = DwijColors.Pink,
        modifier = Modifier.height(142.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Image(
                painter = painterResource(Res.drawable.ya_m_ico),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_yandex_music),
                    color = DwijColors.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = login ?: stringResource(Res.string.no_auth),
                    color = if (login == null) DwijColors.SecondaryText else DwijColors.CyanBright,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            SettingsActionButton(
                text = stringResource(
                    when {
                        isAuthInProgress -> Res.string.auth_btn_waiting
                        login != null -> Res.string.auth_btn_exit
                        else -> Res.string.auth_btn
                    },
                ),
                enabled = !isAuthInProgress,
                isLoading = isAuthInProgress,
                accent = DwijColors.Pink,
                onClick = onAuthClick,
            )
        }
    }
}

/** Показывает занятый объём и сохраняемый предел общего кэша. */
@Composable
private fun SettingsCacheCard(
    cacheLimitMb: Int,
    minCacheMb: Int,
    maxCacheMb: Int,
    occupiedCacheSize: String,
    onCacheLimitCommitted: (megabytes: Int) -> Unit,
) {
    val safeMaxMb = maxCacheMb.coerceAtLeast(minCacheMb + 1)
    val safeLimitMb = cacheLimitMb.coerceIn(minCacheMb, safeMaxMb)
    var sliderValue by remember(safeLimitMb, minCacheMb, safeMaxMb) {
        mutableStateOf(safeLimitMb.toFloat())
    }

    SettingsTextureCard(
        textureRes = Res.drawable.bg_focus_texture,
        accent = DwijColors.CyanBright,
        modifier = Modifier.height(248.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 17.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_cache_title),
                color = DwijColors.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(Res.string.settings_cache_description),
                color = DwijColors.SecondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                SettingsCacheValue(
                    label = stringResource(Res.string.settings_cache_limit),
                    value = formatCacheMegabytes(sliderValue.roundToInt()),
                    accent = DwijColors.Pink,
                )
                SettingsCacheValue(
                    label = stringResource(Res.string.settings_cache_occupied),
                    value = occupiedCacheSize,
                    accent = DwijColors.CyanBright,
                    alignEnd = true,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { value -> sliderValue = value.roundToInt().toFloat() },
                onValueChangeFinished = {
                    onCacheLimitCommitted(sliderValue.roundToInt())
                },
                valueRange = minCacheMb.toFloat()..safeMaxMb.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = DwijColors.White,
                    activeTrackColor = DwijColors.Pink,
                    inactiveTrackColor = DwijColors.CyanBright.copy(alpha = 0.28f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = formatCacheMegabytes(minCacheMb),
                    color = DwijColors.SecondaryText,
                    fontSize = 11.sp,
                )
                Text(
                    text = formatCacheMegabytes(safeMaxMb),
                    color = DwijColors.SecondaryText,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Рисует одно значение хранилища с подписью. */
@Composable
private fun SettingsCacheValue(
    label: String,
    value: String,
    accent: Color,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            text = label,
            color = DwijColors.SecondaryText,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = value,
            color = accent,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Накладывает существующую текстуру на тёмную карточку с тонкой неоновой рамкой. */
@Composable
private fun SettingsTextureCard(
    textureRes: DrawableResource,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DwijColors.SettingsCardBackground)
            .border(1.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(18.dp)),
    ) {
        Image(
            painter = painterResource(textureRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.2f,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DwijColors.Black.copy(alpha = 0.28f)),
        ) {
            content()
        }
    }
}

/** Рисует компактное действие аккаунта с состоянием ожидания OAuth. */
@Composable
private fun SettingsActionButton(
    text: String,
    enabled: Boolean,
    isLoading: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(92.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(DwijColors.SettingsActionBackground)
            .border(1.dp, accent.copy(alpha = if (enabled) 0.85f else 0.32f), RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = text,
                color = DwijColors.White.copy(alpha = if (enabled) 1f else 0.42f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** Форматирует предел слайдера компактно в МБ или ГБ. */
@Composable
private fun formatCacheMegabytes(
    megabytes: Int,
): String = if (megabytes >= 1024) {
    val gigabytes =
        String.format(
            Locale.getDefault(),
            "%.1f",
            megabytes / 1024.0,
        )

    stringResource(
        Res.string.settings_cache_size_gb,
        gigabytes,
    )
} else {
    stringResource(
        Res.string.settings_cache_size_mb,
        megabytes,
    )
}

/** Показывает экран без Activity, SharedPreferences и реальной авторизации. */
@Preview(
    name = "Settings",
    widthDp = 360,
    heightDp = 780,
    showBackground = true,
    backgroundColor = DwijColors.BackgroundArgb,
)
@Composable
private fun SettingsScreenPreview() {
    SettingsScreen(
        appVersion = "0.1.4",
        yandexLogin = "night_driver",
        isAuthInProgress = false,
        cacheLimitMb = 2048,
        minCacheMb = 200,
        maxCacheMb = 8192,
        occupiedCacheSize = "386 МБ",
        musicDirectories =
            listOf(
                "C:\\Music",
            ),
        onBackClick = {},
        onAuthClick = {},
        onCacheLimitCommitted = {},
        onProxyClick = {},
        onMusicDirectoriesClick = {},
    )
}
