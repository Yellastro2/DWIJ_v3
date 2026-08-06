package com.yellastrodev.dwij.ui

import androidx.compose.runtime.Composable

/** Обрабатывает системное действие «Назад» на платформах, где оно доступно. */
@Composable
internal expect fun DwijBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

/** Пишет диагностическое UI-сообщение через логгер текущей платформы. */
internal expect fun uiLogDebug(
    tag: String,
    message: String,
)

/** Пишет предупреждение UI через логгер текущей платформы. */
internal expect fun uiLogWarning(
    tag: String,
    message: String,
    cause: Throwable? = null,
)
