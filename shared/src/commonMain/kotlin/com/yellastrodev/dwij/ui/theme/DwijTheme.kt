package com.yellastrodev.dwij.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Цвета Material3 для фирменного тёмного интерфейса без системной и динамической палитры. */
private val DwijColorScheme = darkColorScheme(
    primary = DwijColors.Pink,
    onPrimary = DwijColors.Black,
    primaryContainer = DwijColors.PrimaryContainer,
    onPrimaryContainer = DwijColors.White,
    inversePrimary = DwijColors.InversePrimary,
    secondary = DwijColors.Cyan,
    onSecondary = DwijColors.Black,
    secondaryContainer = DwijColors.SecondaryContainer,
    onSecondaryContainer = DwijColors.White,
    tertiary = DwijColors.PlayerPlaylistPurple,
    onTertiary = DwijColors.Black,
    tertiaryContainer = DwijColors.TertiaryContainer,
    onTertiaryContainer = DwijColors.White,
    background = DwijColors.Background,
    onBackground = DwijColors.White,
    surface = DwijColors.Surface,
    onSurface = DwijColors.White,
    surfaceVariant = DwijColors.LoadingPlaceholder,
    onSurfaceVariant = DwijColors.SecondaryText,
    surfaceTint = DwijColors.Transparent,
    inverseSurface = DwijColors.White,
    inverseOnSurface = DwijColors.Background,
    error = DwijColors.Error,
    onError = DwijColors.Black,
    errorContainer = DwijColors.ErrorContainer,
    onErrorContainer = DwijColors.White,
    outline = DwijColors.SecondaryText,
    outlineVariant = DwijColors.LocalLibraryDivider,
    scrim = DwijColors.Black,
    surfaceBright = DwijColors.LoadingPlaceholder,
    surfaceDim = DwijColors.Background,
    surfaceContainer = DwijColors.Surface,
    surfaceContainerHigh = DwijColors.Surface,
    surfaceContainerHighest = DwijColors.LoadingPlaceholder,
    surfaceContainerLow = DwijColors.SettingsCardBackground,
    surfaceContainerLowest = DwijColors.Background,
)

/**
 * Задаёт общие цвета текста, иконок и компонентов Android/desktop независимо от темы ОС.
 * Material3 рассчитывает отключённые состояния от нашей палитры; обычный Text вне Surface
 * получает белый цвет через LocalContentColor. Цвета отдельных дизайнерских элементов сохраняются.
 */
@Composable
fun DwijTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DwijColorScheme) {
        CompositionLocalProvider(LocalContentColor provides DwijColors.White, content = content)
    }
}
