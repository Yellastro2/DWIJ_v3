package com.yellastrodev.dwij.ui

import androidx.compose.runtime.Composable
import java.util.logging.Level
import java.util.logging.Logger

@Composable
internal actual fun DwijBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit

internal actual fun uiLogDebug(
    tag: String,
    message: String,
) {
    Logger.getLogger(tag).fine(message)
}

internal actual fun uiLogWarning(
    tag: String,
    message: String,
    cause: Throwable?,
) {
    val logger = Logger.getLogger(tag)
    if (cause == null) {
        logger.warning(message)
    } else {
        logger.log(Level.WARNING, message, cause)
    }
}
