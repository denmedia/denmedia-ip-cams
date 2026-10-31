package com.example.denmediaipcams.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import com.example.denmediaipcams.data.CameraShareCode
import com.example.denmediaipcams.model.IpCamera
import java.io.File

/**
 * «Поделиться камерой»: QR код со всеми настройками камеры. На другом устройстве его сканируют кнопкой
 * «Добавить по QR коду» или отправляют картинкой (мессенджер, почта) и открывают там же из изображения.
 *
 * Код с планшета плохо читался смартфоном: в узком окне диалога он был мелким (десятки модулей на 320dp).
 * Поэтому окно на весь экран, код занимает почти всю меньшую сторону, а по нажатию на него открывается
 * полноэкранный режим: камера другого устройства ловит код, только когда на модуль приходится несколько точек.
 */
@Composable
fun CameraShareDialog(camera: IpCamera, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hasCredentials = camera.username.isNotBlank() || camera.password.isNotBlank()
    var includeCredentials by remember { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(false) }
    val code = remember(camera, includeCredentials) {
        CameraShareCode.encode(camera, includeCredentials && hasCredentials)
    }
    val qr = remember(code) { QrCodes.encode(code, QR_SIZE_PX) }
    // На смартфоне панели и так скрыты (приложение работает без них), на планшете диалог окно на весь экран
    val hideBars = isPhoneScreen()

    if (fullScreen) {
        QrFullScreenDialog(qr = qr, title = camera.name, onDismiss = { fullScreen = false })
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        if (hideBars) HideSystemBarsInDialog()
        Surface(
            shape = RoundedCornerShape(if (hideBars) 0.dp else 24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = if (hideBars) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .padding(24.dp)
                    .fillMaxSize()
            }
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Окно почти на весь экран: код крупный, но кнопки и пояснения остаются под ним
                val codeLimit = minOf(maxWidth, maxHeight) - if (hideBars) 24.dp else 32.dp
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text("Поделиться камерой", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = camera.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        QrCodeView(
                            qr = qr,
                            title = camera.name,
                            modifier = Modifier
                                .size(codeLimit * 0.62f)
                                .clickable { fullScreen = true }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "На другом устройстве нажмите «Добавить по QR коду» и наведите камеру на этот код " +
                                "или выберите присланную картинку с ним.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // Главное действие для сканирования с планшета: код на весь экран
                        Button(
                            onClick = { fullScreen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Крупный QR на весь экран")
                        }
                        if (hasCredentials) {
                            Spacer(modifier = Modifier.height(12.dp))
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        TextButton(onClick = onDismiss) { Text("Закрыть") }
                        FilledTonalButton(onClick = { shareQrImage(context, camera, qr) }) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отправить картинкой")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Полноэкранный QR код для сканирования другим устройством: максимум места под код и максимальная яркость
 * экрана — с приглушённым экраном камера телефона видит вместо модулей серое пятно. Закрытие — нажатием
 * на любое место или кнопкой.
 */
@Composable
private fun QrFullScreenDialog(qr: Bitmap, title: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        HideSystemBarsInDialog()
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Яркость — только этого окна: настройки экрана устройства не меняются
                it.attributes = it.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                }
            }
            onDispose {
                window?.let {
                    it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    it.attributes = it.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            BoxWithConstraints(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
                // Код — по меньшей стороне экрана, минус место под две строки подсказки снизу
                val side = minOf(maxWidth, maxHeight) - 120.dp
                QrCodeView(qr = qr, title = title, modifier = Modifier.size(minOf(side, maxWidth)))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Наведите камеру другого устройства на код",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) { Text("Закрыть") }
                }
            }
        }
    }
}

/** QR код крупным планом: белая подложка (камера читает только тёмный код на светлом фоне) и без обрезки полей */
@Composable
private fun QrCodeView(qr: Bitmap, title: String, modifier: Modifier) {
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = "QR код камеры $title",
        // Целочисленное увеличение без сглаживания: модули остаются квадратными и с резкими краями
        filterQuality = FilterQuality.None,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
    )
}

/** Сторона картинки QR кода в пикселях: крупнее, чем нужно экрану, чтобы при увеличении не было размытия */
private const val QR_SIZE_PX = 1200

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
