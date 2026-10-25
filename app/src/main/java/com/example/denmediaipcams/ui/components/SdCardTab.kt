package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.example.denmediaipcams.network.ArchiveException
import com.example.denmediaipcams.network.DahuaArchiveSession
import com.example.denmediaipcams.network.DvripClient
import com.example.denmediaipcams.network.HikvisionStorage
import com.example.denmediaipcams.network.HipcamStorage
import com.example.denmediaipcams.network.StorageInfo
import com.example.denmediaipcams.network.StorageVolume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class SdState {
    object Loading : SdState()
    data class Loaded(val info: StorageInfo, val notice: String? = null) : SdState()
    data class Failed(val message: String) : SdState()
    object Formatting : SdState()
}

/** Сколько ждём возвращения камеры после форматирования: она может перезагрузиться */
private const val FORMAT_WAIT_ATTEMPTS = 36
private const val FORMAT_WAIT_STEP_MS = 5_000L

/** Откуда вкладка берёт сведения о карте: у Dahua это JSON-RPC, у Hipcam — CGI камеры */
private interface SdBackend {
    suspend fun info(): StorageInfo
    suspend fun format(volume: StorageVolume)
    fun close() = Unit
}

private class DahuaSdBackend(private val session: DahuaArchiveSession) : SdBackend {
    override suspend fun info() = session.storageInfo()
    override suspend fun format(volume: StorageVolume) = session.format(volume)
    override fun close() = session.close()
}

private class HipcamSdBackend(private val camera: IpCamera) : SdBackend {
    override suspend fun info() = HipcamStorage.info(camera)
    override suspend fun format(volume: StorageVolume) = HipcamStorage.format(camera)
}

private class HikvisionSdBackend(private val camera: IpCamera) : SdBackend {
    override suspend fun info() = HikvisionStorage.info(camera)
    override suspend fun format(volume: StorageVolume) = HikvisionStorage.format(camera, volume)
}

/** XMEye/DVRIP: на каждый запрос своя короткая сессия (вход, команда, закрытие) */
private class DvripSdBackend(private val camera: IpCamera) : SdBackend {
    private suspend fun <T> withClient(block: suspend (DvripClient) -> T): T {
        val client = DvripClient(camera)
        try {
            client.login()
            return block(client)
        } finally {
            client.close()
        }
    }

    override suspend fun info() = withClient { it.storageInfo() }
    override suspend fun format(volume: StorageVolume) = withClient { it.formatStorage(volume.device ?: "0:0") }
}

/**
 * Вкладка «SD карта» в настройках камеры Dahua или Hipcam: состояние и объём карты, кнопка форматирования.
 * Форматирование стирает все записи, поэтому выполняется только после подтверждения в отдельном окне.
 */
@Composable
fun SdCardTab(camera: IpCamera, onFormatted: () -> Unit = {}) {
    // Свой источник на вкладку; пересоздаётся, если в форме поменяли адрес, порт или учётные данные
    val session = remember(
        camera.hasDahuaApi, camera.sdHttpArchive, camera.hikvisionArchive, camera.dvripArchive, camera.ipAddress, camera.effectiveHttpPort,
        camera.effectiveHipcamPort, camera.effectiveDvripPort, camera.username, camera.password
    ) {
        when {
            camera.hasDahuaApi -> DahuaSdBackend(DahuaArchiveSession(camera))
            camera.sdHttpArchive -> HipcamSdBackend(camera)
            camera.hikvisionArchive -> HikvisionSdBackend(camera)
            camera.dvripArchive -> DvripSdBackend(camera)
            else -> HipcamSdBackend(camera)
        }
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    val scope = rememberCoroutineScope()
    var state by remember(session) { mutableStateOf<SdState>(SdState.Loading) }
    var volumeToFormat by remember { mutableStateOf<StorageVolume?>(null) }

    suspend fun load(notice: String? = null) {
        state = SdState.Loading
        state = try {
            SdState.Loaded(session.info(), notice)
        } catch (e: ArchiveException) {
            SdState.Failed(e.message.orEmpty())
        }
    }

    LaunchedEffect(session) { load() }

    fun format(volume: StorageVolume) {
        scope.launch {
            state = SdState.Formatting
            val failure = try {
                session.format(volume)
                null
            } catch (e: ArchiveException) {
                // Камера может оборвать ответ, перезагружаясь после форматирования: тогда состояние проверяем ниже
                if (e.kind == ArchiveException.Kind.NETWORK) null else e.message.orEmpty()
            }
            if (failure != null) {
                state = SdState.Failed(failure)
                return@launch
            }
            // Ждём, пока камера снова отдаст сведения о карте И занятое место реально уменьшится: сразу после команды
            // камера ещё отвечает прежними цифрами, а форматирование заканчивается позже, обычно с перезагрузкой
            val usedBefore = volume.usedBytes
            var lastInfo: StorageInfo? = null
            for (attempt in 0 until FORMAT_WAIT_ATTEMPTS) {
                delay(FORMAT_WAIT_STEP_MS)
                try {
                    val info = session.info()
                    lastInfo = info
                    val used = info.volumes.firstOrNull { it.device == volume.device }?.usedBytes
                    if (usedBefore == null || used == null || used < usedBefore * 9 / 10) {
                        state = SdState.Loaded(info, "Карта отформатирована.")
                        onFormatted()
                        return@launch
                    }
                } catch (_: ArchiveException) {
                    // камера ещё перезагружается
                }
            }
            state = lastInfo?.let {
                SdState.Loaded(it, "Камера приняла команду, но занятое место пока не изменилось. Подождите и нажмите «Обновить».")
            } ?: SdState.Failed("Камера не вернулась на связь за 3 минуты. Проверьте её позже и нажмите «Обновить».")
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (val current = state) {
            SdState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Читаю сведения о карте…")
            }

            SdState.Formatting -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text("Форматирование… Не закрывайте окно и не отключайте камеру.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Камера может перезагрузиться, это нормально.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is SdState.Failed -> {
                Text(current.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { load() } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Обновить")
                }
            }

            is SdState.Loaded -> {
                val volumes = current.info.volumes
                current.notice?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (volumes.isEmpty()) {
                    Text(
                        "SD-карта не найдена: она не установлена или не определилась камерой.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                volumes.forEach { volume ->
                    VolumeCard(volume, current.info.fileSystem)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { scope.launch { load() } }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Обновить")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = { volumeToFormat = volume },
                        enabled = volume.device != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Форматировать SD-карту…")
                    }
                    Text(
                        "Форматирование удалит все записи на карте и остановит запись на время операции.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    volumeToFormat?.let { volume ->
        FormatConfirmDialog(
            volume = volume,
            onConfirm = {
                volumeToFormat = null
                format(volume)
            },
            onDismiss = { volumeToFormat = null }
        )
    }
}

/** Настройки блока «Архив видео» на главном экране: показ, число роликов и отказ от показа на лимитной сети */
@Composable
fun ArchiveBlockSettings(
    active: Boolean = true,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    countText: String,
    onCountChange: (String) -> Unit,
    hideOnMetered: Boolean,
    onHideOnMeteredChange: (Boolean) -> Unit
) {
    val maxCount = IpCamera.MAX_ARCHIVE_BLOCK_COUNT
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = active) { onEnabledChange(!enabled) }
        ) {
            Checkbox(checked = enabled, onCheckedChange = onEnabledChange, enabled = active)
            Text("Выводить блок последнее видео из архива на главном экране")
        }
        if (enabled) {
            val count = countText.toIntOrNull()
            OutlinedTextField(
                value = countText,
                onValueChange = onCountChange,
                label = { Text("Количество роликов") },
                supportingText = { Text("От 1 до $maxCount") },
                isError = count == null || count !in 1..maxCount,
                singleLine = true,
                enabled = active,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = active) { onHideOnMeteredChange(!hideOnMetered) }
            ) {
                Checkbox(checked = hideOnMetered, onCheckedChange = onHideOnMeteredChange, enabled = active)
                Text("Не выводить блок во время использования «Лимитного подключения к сети»")
            }
        }
    }
}

/** Карточка карты: состояние, путь, объём и заполненность */
@Composable
private fun VolumeCard(volume: StorageVolume, fileSystem: String?) {
    val total = volume.totalBytes
    val used = volume.usedBytes
    val healthy = volume.state.equals("Success", ignoreCase = true)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SdCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SD-карта", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))
        val stateText = when {
            healthy -> "Исправна"
            volume.state == "NoPartition" -> "Файловая система не распознана"
            volume.state.equals("unformatted", ignoreCase = true) -> "Не отформатирована"
            volume.state.equals("formating", ignoreCase = true) -> "Форматируется"
            volume.state?.startsWith("Status") == true -> "Ошибка раздела (код ${volume.state.removePrefix("Status")})"
            else -> volume.state ?: "Неизвестно"
        }
        InfoLine("Состояние", stateText, if (healthy) null else MaterialTheme.colorScheme.error)
        volume.device?.let { InfoLine("Устройство", it) }
        InfoLine("Путь", volume.path)
        volume.type?.let { InfoLine("Режим", if (it == "ReadWrite") "Чтение и запись" else it) }
        fileSystem?.let { InfoLine("Файловая система", it) }
        if (total != null) InfoLine("Объём", formatSize(total))
        if (total != null && used != null && total > 0) {
            InfoLine("Занято", "${formatSize(used)} (${used * 100 / total}%)")
            InfoLine("Свободно", formatSize((total - used).coerceAtLeast(0)))
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (used.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (!healthy || total == 0L) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Карта вставлена, но камера не может ей пользоваться: запись не ведётся. " +
                    "Частые причины: файловая система не распознана (карта новая, из другого устройства или " +
                    "в формате, который камера не читает — например exFAT/NTFS), раздел повреждён или карта " +
                    "изношена. Обычно помогает форматирование ниже; если после него объём остаётся нулевым, " +
                    "проверьте карту в компьютере или замените её (подходят карты до 128 ГБ, класс 10).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun InfoLine(title: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** Подтверждение: без явного согласия кнопка форматирования неактивна */
@Composable
private fun FormatConfirmDialog(volume: StorageVolume, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    val used = volume.usedBytes?.let { formatSize(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Форматировать SD-карту?") },
        text = {
            Column {
                Text(
                    "Все записи на карте${used?.let { " (занято $it)" }.orEmpty()} будут удалены без возможности восстановления. " +
                        "На время форматирования камера остановит запись и может перезагрузиться."
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { understood = !understood }
                ) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("Я понимаю, что данные будут потеряны", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = understood,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Форматировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.1f ГБ", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.getDefault(), "%d КБ", bytes / 1024)
}
