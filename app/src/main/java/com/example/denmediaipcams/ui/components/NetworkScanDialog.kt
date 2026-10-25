package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.network.DiscoveredCamera
import com.example.denmediaipcams.ui.NetworkScanState

/**
 * Ход и результат поиска камер в локальной сети.
 * Камеры отмечаются галочками и добавляются сразу несколько; значок карандаша
 * открывает форму добавления одной камеры с заполненными адресом и портами.
 */
@Composable
fun NetworkScanDialog(
    state: NetworkScanState,
    existingHosts: Set<String>,
    onCameraPick: (DiscoveredCamera) -> Unit,
    onAddSelected: (List<DiscoveredCamera>) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    // Отмеченные для добавления камеры (по адресу)
    var selectedHosts by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedCameras = (state as? NetworkScanState.Done)?.cameras
        .orEmpty()
        .filter { it.host in selectedHosts && it.host !in existingHosts }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null) },
        title = { Text("Поиск камер в локальной сети") },
        text = {
            when (state) {
                is NetworkScanState.Scanning -> Column {
                    Text(state.progress, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.padding(top = 12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is NetworkScanState.Failed -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error
                )
                is NetworkScanState.Done -> if (state.cameras.isEmpty()) {
                    Text(
                        "Камеры не найдены. Убедитесь, что устройство подключено к той же сети, что и камеры, " +
                            "и что на камерах включены RTSP или ONVIF."
                    )
                } else {
                    Column {
                        Text(
                            text = "Найдено: ${state.cameras.size}. Отметьте камеры и добавьте их сразу " +
                                "(логин admin, пароль нужно будет указать в настройках камеры) или откройте настройки одной камеры.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.padding(top = 8.dp))
                        LazyColumnWithBar(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 400.dp)
                        ) {
                            items(state.cameras, key = { it.host }) { camera ->
                                DiscoveredCameraItem(
                                    camera = camera,
                                    isAdded = camera.host in existingHosts,
                                    isSelected = camera.host in selectedHosts,
                                    onToggle = {
                                        selectedHosts = if (camera.host in selectedHosts) {
                                            selectedHosts - camera.host
                                        } else {
                                            selectedHosts + camera.host
                                        }
                                    },
                                    onEdit = { onCameraPick(camera) }
                                )
                            }
                        }
                    }
                }
                NetworkScanState.Idle -> {}
            }
        },
        confirmButton = {
            if (selectedCameras.isNotEmpty()) {
                TextButton(onClick = { onAddSelected(selectedCameras) }) {
                    Text("Добавить (${selectedCameras.size})")
                }
            } else if (state is NetworkScanState.Done || state is NetworkScanState.Failed) {
                TextButton(onClick = onRescan) { Text("Искать снова") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state is NetworkScanState.Scanning) "Отмена" else "Закрыть")
            }
        }
    )
}

@Composable
private fun DiscoveredCameraItem(
    camera: DiscoveredCamera,
    isAdded: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isAdded) 0.4f else 1f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isAdded, onClick = onToggle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            if (isAdded) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Уже добавлена",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp)
                )
            } else {
                Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = camera.name ?: camera.host,
                    style = MaterialTheme.typography.titleSmall
                )
                val details = buildList {
                    if (camera.name != null) add(camera.host)
                    camera.hardware?.let { add(it) }
                    camera.rtspPort?.let { add("RTSP :$it") }
                    camera.onvifPort?.let { add("ONVIF :$it") }
                }.joinToString(" · ")
                Text(text = details, style = MaterialTheme.typography.bodySmall)
            }
            if (!isAdded) {
                TooltipIconButton(tooltip = "Настроить и добавить", onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Настроить и добавить",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
