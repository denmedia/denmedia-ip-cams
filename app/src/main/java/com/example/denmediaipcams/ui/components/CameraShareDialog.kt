package com.example.denmediaipcams.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.denmediaipcams.data.CameraShareCode
import com.example.denmediaipcams.model.IpCamera
import java.io.File

/**
 * «Поделиться камерой»: QR код со всеми настройками камеры. На другом устройстве его сканируют кнопкой
 * «Добавить по QR коду» или отправляют картинкой (мессенджер, почта) и открывают там же из изображения.
 */
@Composable
fun CameraShareDialog(camera: IpCamera, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hasCredentials = camera.username.isNotBlank() || camera.password.isNotBlank()
    var includeCredentials by remember { mutableStateOf(true) }
    val code = remember(camera, includeCredentials) { CameraShareCode.encode(camera, includeCredentials && hasCredentials) }
    val qr = remember(code) { QrCodes.encode(code, QR_SIZE_PX) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
        title = { Text("Поделиться камерой") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = camera.name,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(12.dp))
                // Белая подложка нужна при любой теме: камера телефона читает только тёмный код на светлом фоне
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR код камеры ${camera.name}",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "На другом устройстве нажмите «Добавить по QR коду» и наведите камеру на этот код " +
                        "или выберите присланную картинку с ним.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (hasCredentials) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Передать логин и пароль", modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(checked = includeCredentials, onCheckedChange = { includeCredentials = it })
                    }
                    Text(
                        text = if (includeCredentials) {
                            "Любой, у кого окажется этот QR код, сможет подключиться к камере. Отправляйте его только тем, кому доверяете."
                        } else {
                            "Логин и пароль камеры нужно будет ввести при добавлении."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (includeCredentials) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { shareQrImage(context, camera, qr) }) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Отправить картинкой")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

private const val QR_SIZE_PX = 800

/** Отправка QR кода картинкой через системное «Поделиться» (файл во временной папке, доступ через FileProvider) */
private fun shareQrImage(context: Context, camera: IpCamera, qr: Bitmap) {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Прежние картинки не копим: в папке только последний отправленный код
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "camera-qr.png")
        file.outputStream().use { qr.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "Камера «${camera.name}». Чтобы добавить её, в приложении нажмите «Добавить по QR коду» → «Из изображения» и выберите эту картинку."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Отправить QR код камеры"))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось отправить картинку: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
