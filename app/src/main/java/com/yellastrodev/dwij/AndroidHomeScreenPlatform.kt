package com.yellastrodev.dwij

import androidx.activity.compose.BackHandler as AndroidBackHandler
import androidx.compose.runtime.Composable
import com.yellastrodev.dwij.ui.HomeScreenPlatform

/** Android-реализация платформенных действий домашнего экрана. */
object AndroidHomeScreenPlatform : HomeScreenPlatform {
    @Composable
    override fun BackHandler(
        enabled: Boolean,
        onBack: () -> Unit,
    ) {
        AndroidBackHandler(
            enabled = enabled,
            onBack = onBack,
        )
    }
}
