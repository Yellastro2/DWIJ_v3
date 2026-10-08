package com.yellastrodev.dwij.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.yellastrodev.dwij.RadialMenuTarget

/** Временный режим назначения: маршруты передают устойчивую точку входа вместо запуска музыки. */
class RadialMenuSelection(val onSelect: (RadialMenuTarget) -> Unit)

/** Контекст существует только внутри общего NavHost; null означает обычное воспроизведение. */
val LocalRadialMenuSelection = staticCompositionLocalOf<RadialMenuSelection?> { null }
