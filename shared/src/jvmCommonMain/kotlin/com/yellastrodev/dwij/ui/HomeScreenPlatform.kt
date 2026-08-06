package com.yellastrodev.dwij.ui

import androidx.compose.runtime.Composable

/** Платформенные действия, необходимые домашнему экрану. */
interface HomeScreenPlatform {
    /** Подписывает экран на системное действие «Назад». */
    @Composable
    fun BackHandler(
        enabled: Boolean,
        onBack: () -> Unit,
    )
}

/** Пустая реализация для preview и платформ без системного back-handler. */
object NoOpHomeScreenPlatform : HomeScreenPlatform {
    @Composable
    override fun BackHandler(
        enabled: Boolean,
        onBack: () -> Unit,
    ) = Unit
}
