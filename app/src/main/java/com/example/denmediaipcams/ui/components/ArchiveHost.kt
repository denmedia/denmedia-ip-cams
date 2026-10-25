package com.example.denmediaipcams.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.LocalDateTime
import com.example.denmediaipcams.player.LocalStreamSessionManager

/** Какая камера сейчас показана в окне архива (null — окно закрыто) */
class ArchiveHostState {
    var cameraId by mutableStateOf<String?>(null)
        private set

    /** Запись, которую нужно сразу открыть (время её начала); null — окно открывается на последнем дне без выбора записи */
    var initialStart by mutableStateOf<LocalDateTime?>(null)
        private set

    fun open(id: String, start: LocalDateTime? = null) {
        initialStart = start
        cameraId = id
    }

    fun close() {
        cameraId = null
        initialStart = null
    }
}

val LocalArchiveHost = staticCompositionLocalOf<ArchiveHostState?> { null }

/**
 * Окно архива живёт выше всех раскладок экрана: при смене ориентации раскладка заменяется целиком,
 * а окно с запущенным видео остаётся на месте и не перезапускается.
 */
@Composable
fun ArchiveHost(content: @Composable () -> Unit) {
    val host = remember { ArchiveHostState() }
    val manager = LocalStreamSessionManager.current
    CompositionLocalProvider(LocalArchiveHost provides host) {
        content()
        val cameraId = host.cameraId
        val session = cameraId?.let { manager.sessionFor(it) }
        // Пока открыт архив, звук живой трансляции приглушён (если он был включён)
        DisposableEffect(session) {
            session?.setAudioSuspended(true)
            onDispose { session?.setAudioSuspended(false) }
        }
        val initialStart = host.initialStart
        session?.camera?.let { camera ->
            // У камеры, которая умеет и Dahua API, и каталог /sd/, архив Dahua полнее (удаление, настройки SD).
            // DVRIP — на случай, если у камеры нет ни того, ни другого, но включён явно пользователем
            when {
                camera.hasDahuaApi -> DahuaArchiveDialog(camera = camera, initialStart = initialStart, onDismiss = host::close)
                camera.hikvisionArchive -> HikvisionArchiveDialog(camera = camera, initialStart = initialStart, onDismiss = host::close)
                camera.dvripArchive -> DvripArchiveDialog(camera = camera, initialStart = initialStart, onDismiss = host::close)
                else -> HipcamArchiveDialog(camera = camera, initialStart = initialStart, onDismiss = host::close)
            }
        }
    }
}
