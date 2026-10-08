package com.yellastrodev.dwij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.RadialMenuTarget
import com.yellastrodev.dwij.defaultRadialMenuTargets
import com.yellastrodev.dwij.defaultRadialPrimaryTarget
import com.yellastrodev.dwij.resources.ic_home_player_play
import com.yellastrodev.dwij.resources.radial_menu_primary_title
import com.yellastrodev.dwij.resources.radial_menu_primary_assignment
import org.jetbrains.compose.resources.painterResource
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.player_back_content_description
import com.yellastrodev.dwij.resources.radial_menu_settings_description
import com.yellastrodev.dwij.resources.radial_menu_settings_title
import com.yellastrodev.dwij.resources.radial_menu_reset
import com.yellastrodev.dwij.ui.theme.DwijColors
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Настраивает пять секторов и центральный Play; служебный сектор настройки здесь скрыт. */
@Composable
fun RadialMenuSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    targets: List<RadialMenuTarget> = defaultRadialMenuTargets(),
    onSelectSlot: (Int) -> Unit = {},
    onReset: () -> Unit = {},
    primaryTarget: RadialMenuTarget = defaultRadialPrimaryTarget(),
    onSelectPrimary: () -> Unit = {},
) {
    val items = homeRadialMenuItems(targets, includeSettings = false)
    val primaryTitle = homeRadialMenuItems(listOf(primaryTarget), includeSettings = false).first().title.replace('\n', ' ')
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DwijColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onBackClick) {
                Text(
                    text = stringResource(Res.string.player_back_content_description),
                    color = DwijColors.CyanBright,
                )
            }
            Text(
                text = stringResource(Res.string.radial_menu_settings_title),
                color = DwijColors.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        Text(
            text = stringResource(Res.string.radial_menu_settings_description),
            color = DwijColors.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(Res.string.radial_menu_reset), color = DwijColors.CyanBright)
        }
        Text(
            text = stringResource(Res.string.radial_menu_primary_assignment, primaryTitle),
            color = DwijColors.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
        ) {
            val menuSize = minOf(maxWidth, maxHeight, 560.dp)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(menuSize)) {
                RadialMenu(
                    items = items,
                    visible = true,
                    onPrimaryClick = {},
                    onVisualActivation = {},
                    onPressChange = {},
                    onItemClick = { item ->
                        if (item.target != null) onSelectSlot(items.indexOf(item))
                    },
                    onDismiss = {},
                    animationStyle = RadialMenuAnimationStyle.StaticPreview,
                    previewInteractive = true,
                    innerRadiusFraction = 0.14f,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = onSelectPrimary,
                    modifier = Modifier
                        .size(menuSize * 0.24f)
                        .background(DwijColors.Background, CircleShape)
                        .border(1.dp, DwijColors.CyanBright, CircleShape),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_home_player_play),
                        contentDescription = stringResource(Res.string.radial_menu_primary_title),
                        tint = DwijColors.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/** Предпросмотр раскрытого меню без плеера, репозиториев и навигации. */
@Preview
@Composable
private fun RadialMenuSettingsScreenPreview() {
    RadialMenuSettingsScreen(onBackClick = {})
}
