package com.yellastrodev.dwij.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.yellastrodev.dwij.playback.PlayerVolumeControl

/**
 * Доступ Compose UI к опциональному платформенному управлению громкостью.
 *
 * Desktop-хост предоставляет реальную реализацию.
 * Android и preview по умолчанию получают null.
 *
 * Экран должен показывать desktop-only элементы громкости только если
 * значение не null.
 */
val LocalPlayerVolumeControl =
    staticCompositionLocalOf<PlayerVolumeControl?> {
        null
    }