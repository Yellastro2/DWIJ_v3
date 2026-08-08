package com.yellastrodev.dwij.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import com.yellastrodev.yamusicsdk.YamLogger

/**
 * Доступ к универсальному логгеру из Compose UI.
 *
 * Корневое приложение предоставляет платформенную реализацию; no-op значение
 * оставляет preview и другие хосты shared UI независимыми от Android.
 */
val LocalYamLogger = staticCompositionLocalOf<YamLogger> {
    NoOpYamLogger
}
