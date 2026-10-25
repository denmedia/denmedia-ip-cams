package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraDetails
import com.example.denmediaipcams.network.ImagingParam
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Яркость, контраст, насыщенность и резкость изображения камеры (ONVIF Imaging).
 * Значение применяется на камере, когда ползунок отпущен; прежние настройки камеры не сохраняются отдельно.
 */
@Composable
fun ImagingDialog(camera: IpCamera, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var params by remember(camera.id) { mutableStateOf<List<ImagingParam>?>(null) }
    var loadFailed by remember(camera.id) { mutableStateOf(false) }
    var message by remember(camera.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(camera.id) {
        val loaded = CameraDetails.imagingParams(camera)
        loadFailed = loaded == null
        params = loaded
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изображение: ${camera.name}") },
        text = {
            val current = params
            when {
                loadFailed -> Text(
                    "Камера не отдаёт настройки изображения по ONVIF (сервис Imaging недоступен или логин и пароль не подошли).",
                    style = MaterialTheme.typography.bodyMedium
                )
                current == null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(24.dp))
                    Text("Чтение настроек…", style = MaterialTheme.typography.bodyMedium)
                }
                current.isEmpty() -> Text(
                    "Камера не сообщила ни яркость, ни контраст, ни насыщенность, ни резкость.",
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    current.forEachIndexed { index, param ->
                        var value by remember(param.key, param.value) { mutableStateOf(param.value) }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(param.label, style = MaterialTheme.typography.bodyMedium)
                            Text(value.roundToInt().toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            valueRange = param.min..param.max,
                            onValueChangeFinished = {
                                scope.launch {
                                    val ok = CameraDetails.setImagingParam(camera, param.key, value)
                                    message = if (ok) null else "Камера не приняла значение «${param.label}»"
                                    if (ok) params = current.toMutableList().also { it[index] = param.copy(value = value) }
                                }
                            }
                        )
                    }
                    message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        "Изменения применяются на камере сразу.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
