package com.yellastrodev.dwij.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun DwijBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(
        enabled = enabled,
        onBack = onBack,
    )
}

internal actual fun uiLogDebug(
    tag: String,
    message: String,
) {
    Log.d(tag, message)
}

internal actual fun uiLogWarning(
    tag: String,
    message: String,
    cause: Throwable?,
) {
    if (cause == null) {
        Log.w(tag, message)
    } else {
        Log.w(tag, message, cause)
    }
}
