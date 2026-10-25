package com.example.denmediaipcams.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

const val NETWORK_SCAN_PROMPT = "Найти IP-камеры в локальной сети автоматически?"

/** Кнопка-лупа «поиск камер в сети»; подсказка появляется при долгом нажатии */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(NETWORK_SCAN_PROMPT) } },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        FilledTonalIconButton(onClick = onClick) {
            Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = NETWORK_SCAN_PROMPT)
        }
    }
}
