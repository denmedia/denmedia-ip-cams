package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.model.DayNightMode
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripClient
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Управление режимом день / ночь (IR-cut фильтр / ИК-подсветка) */
@Composable
fun DayNightControl(
    currentMode: DayNightMode?,
    isSupported: Boolean,
    onModeSelected: (DayNightMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        if (!isSupported) {
            Text(
                text = "Управление режимом день/ночь не поддерживается этой камерой",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            return
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            DayNightOptionButton(
                label = "Авто",
                icon = Icons.Default.AutoMode,
                isSelected = currentMode == DayNightMode.AUTO,
                onClick = { onModeSelected(DayNightMode.AUTO) },
                modifier = Modifier.weight(1f)
            )
            DayNightOptionButton(
                label = "День",
                icon = Icons.Default.WbSunny,
                isSelected = currentMode == DayNightMode.DAY,
                onClick = { onModeSelected(DayNightMode.DAY) },
                modifier = Modifier.weight(1f)
            )
            DayNightOptionButton(
                label = "Ночь",
                icon = Icons.Default.Bedtime,
                isSelected = currentMode == DayNightMode.NIGHT,
                onClick = { onModeSelected(DayNightMode.NIGHT) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Дополнительные настройки ночной подсветки камер XMEye/DVRIP (белые LED + ИК): режим белой подсветки, её яркость
 * и режим ИК-фильтра. Показывается только для камер с включённым DVRIP, значения читаются из камеры.
 * Режимы ИК-фильтра (0/1/2) камера не описывает — подписаны номерами, как в её конфиге (IRCUTMode).
 */
@Composable
fun DvripNightLightControl(camera: IpCamera, refreshKey: Any? = null, modifier: Modifier = Modifier) {
    if (!camera.dvripArchive) return

    var state by remember(camera.id, camera.ipAddress) { mutableStateOf<DvripClient.NightLight?>(null) }
    var failed by remember(camera.id, camera.ipAddress) { mutableStateOf(false) }
    var brightness by remember(camera.id, camera.ipAddress) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    suspend fun <T> withCamera(block: suspend (DvripClient) -> T): T? {
        val client = DvripClient(camera)
        return try {
            client.login()
            block(client)
        } catch (e: Exception) {
            null
        } finally {
            client.close()
        }
    }

    // refreshKey — режим, выбранный кнопками «Авто/День/Ночь» выше: они меняют и эти настройки, их нужно перечитать
    LaunchedEffect(camera.id, camera.ipAddress, refreshKey) {
        val loaded = withCamera { it.nightLight() }
        failed = loaded == null
        state = loaded
        loaded?.brightness?.let { brightness = it.toFloat() }
    }

    val current = state
    if (current == null) {
        if (failed) {
            Text(
                text = "Подсветка камеры недоступна (нет ответа по DVRIP)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = modifier.padding(vertical = 8.dp)
            )
        }
        return
    }

    fun apply(change: suspend (DvripClient) -> Boolean, onApplied: (DvripClient.NightLight) -> DvripClient.NightLight) {
        scope.launch {
            val ok = withCamera(change) == true
            if (ok) state = state?.let(onApplied)
            failed = !ok
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        if (current.whiteMode != null) {
            SectionLabel("Белая подсветка")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    Triple("Выкл", Icons.Default.PowerSettingsNew, "Close"),
                    Triple("Авто", Icons.Default.AutoMode, "Auto"),
                    Triple("Умная", Icons.Default.AutoAwesome, "Intelligent"),
                    Triple("Вкл", Icons.Default.Lightbulb, "KeepOpen")
                ).forEach { (label, icon, mode) ->
                    DayNightOptionButton(
                        label = label,
                        icon = icon,
                        isSelected = current.whiteMode == mode,
                        onClick = { apply({ it.setWhiteLightMode(mode) }) { s -> s.copy(whiteMode = mode) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (current.brightness != null) {
            SectionLabel("Яркость белой подсветки: ${brightness.roundToInt()}%")
            Slider(
                value = brightness,
                onValueChange = { brightness = it },
                onValueChangeFinished = {
                    val percent = brightness.roundToInt()
                    apply({ it.setWhiteLightBrightness(percent) }) { s -> s.copy(brightness = percent) }
                },
                valueRange = 0f..100f
            )
        }
        if (current.irCutMode != null) {
            SectionLabel("ИК-фильтр")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // IRCUTMode камеры: 0 — фильтр вставлен (дневной режим), 1 — убран (ночной, ИК), 2 — решает камера
                listOf(0 to "Вкл (день)", 1 to "Выкл (ночь)", 2 to "Авто").forEach { (mode, label) ->
                    DayNightOptionButton(
                        label = label,
                        icon = Icons.Default.Visibility,
                        isSelected = current.irCutMode == mode,
                        onClick = { apply({ it.setIrCutMode(mode) }) { s -> s.copy(irCutMode = mode) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (failed) {
            Text(
                text = "Камера не приняла изменение",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DayNightOptionButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        modifier = modifier.height(58.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
