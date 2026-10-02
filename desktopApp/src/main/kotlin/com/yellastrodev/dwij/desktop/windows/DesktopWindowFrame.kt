package com.yellastrodev.dwij.desktop.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.yellastrodev.dwij.ui.theme.DwijColors
import dwij_v3.desktopapp.generated.resources.Res
import dwij_v3.desktopapp.generated.resources.dwij
import org.jetbrains.compose.resources.painterResource
import java.awt.event.WindowEvent

/** Обрамляет Windows-контент фирменной панелью; закрытие проходит через обработчик окна. */
@Composable
fun WindowScope.DesktopWindowFrame(state: WindowState, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(DwijColors.Background)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxSize().padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(painterResource(Res.drawable.dwij), null, Modifier.size(20.dp))
                    Text("DWIJ", color = DwijColors.White, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }
            WindowCaptionButton("Свернуть", CaptionAction.Minimize) { state.isMinimized = true }
            val maximized = state.placement == WindowPlacement.Maximized
            WindowCaptionButton(
                if (maximized) "Восстановить" else "Развернуть",
                if (maximized) CaptionAction.Restore else CaptionAction.Maximize,
            ) {
                state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }
            WindowCaptionButton("Закрыть", CaptionAction.Close) {
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(DwijColors.Cyan, DwijColors.Pink)),
        ))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** Тип геометрической иконки для действий с окном. */
private enum class CaptionAction { Minimize, Maximize, Restore, Close }

/** Кнопка заголовка с доступным названием и неоновой подсветкой при наведении. */
@Composable
private fun WindowCaptionButton(label: String, action: CaptionAction, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = if (action == CaptionAction.Close) DwijColors.Pink else DwijColors.Cyan
    Box(
        Modifier.width(40.dp).fillMaxHeight()
            .background(if (hovered) accent.copy(alpha = 0.18f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val color = if (hovered) accent else DwijColors.White
            val stroke = 1.dp.toPx()
            when (action) {
                CaptionAction.Minimize -> drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
                CaptionAction.Maximize -> drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                CaptionAction.Restore -> {
                    drawRect(color, Offset(size.width * 0.25f, 0f), Size(size.width * 0.75f, size.height * 0.75f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawRect(DwijColors.Background, Offset(0f, size.height * 0.25f), Size(size.width * 0.75f, size.height * 0.75f))
                    drawRect(color, Offset(0f, size.height * 0.25f), Size(size.width * 0.75f, size.height * 0.75f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                }
                CaptionAction.Close -> {
                    drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke)
                    drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke)
                }
            }
        }
    }
}
