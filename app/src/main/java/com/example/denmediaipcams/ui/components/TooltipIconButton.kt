package com.example.denmediaipcams.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** С какой стороны от кнопки показывать подсказку */
enum class TooltipSide { Above, Below }

/**
 * Подсказка с назначением кнопки: появляется рядом с кнопкой при долгом нажатии.
 * Нужна кнопкам без текстовой подписи (только значок).
 * @param position с какой стороны от кнопки показывать подсказку (у верхнего края экрана — снизу)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(
    text: String,
    modifier: Modifier = Modifier,
    position: TooltipSide = TooltipSide.Above,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (position == TooltipSide.Above) TooltipAnchorPosition.Above else TooltipAnchorPosition.Below
        ),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content
    )
}

/** IconButton с подсказкой по долгому нажатию */
@Composable
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    position: TooltipSide = TooltipSide.Above,
    content: @Composable () -> Unit
) {
    WithTooltip(text = tooltip, position = position) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}
