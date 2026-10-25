package com.example.denmediaipcams.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraDetails
import com.example.denmediaipcams.network.OnvifInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Полная информация о камере и её функциях, полученная по ONVIF */
@Composable
fun OnvifInfoDialog(camera: IpCamera, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember(camera.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(camera.id) {
        val onvifReport = withContext(Dispatchers.IO) {
            try {
                OnvifInspector.inspect(camera.ipAddress, camera.effectiveOnvifPort, camera.username, camera.password)
            } catch (e: Exception) {
                "Ошибка опроса ONVIF: ${e.message}"
            }
        }
        // Настройки детекции движения камера отдаёт только своим HTTP-интерфейсом (Hipcam, Dahua), не по ONVIF
        val motion = CameraDetails.motionReport(camera)
        report = if (motion == null) onvifReport else onvifReport.trimEnd() + "\n\n━━ Детекция движения ━━\n" + motion.joinToString("\n")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ONVIF: ${camera.name}") },
        text = {
            val text = report
            if (text == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(24.dp))
                    Text("Опрос камеры…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScrollWithBar()
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            report?.let { text ->
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ONVIF", text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }) { Text("Копировать") }
            }
        }
    )
}
