package com.example.denmediaipcams.ui.components

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.denmediaipcams.player.DahuaThumbnails
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.ArchiveException
import com.example.denmediaipcams.network.ArchiveResult
import com.example.denmediaipcams.network.DahuaApi
import com.example.denmediaipcams.network.DahuaArchiveSession
import com.example.denmediaipcams.network.DahuaRecording
import com.example.denmediaipcams.network.H265ParamSets
import com.example.denmediaipcams.player.ArchiveAudio
import com.example.denmediaipcams.player.StreamSession
import java.time.YearMonth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.denmediaipcams.DownloadNotifier
import com.example.denmediaipcams.data.ArchiveListCache
import com.example.denmediaipcams.data.RecordingSaver
import com.example.denmediaipcams.data.SnapshotSaver
import com.example.denmediaipcams.network.DahuaArchive
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

// Название папки с записями дня на накопителе (по правилу камеры «%y-%M-%d»)
private val FOLDER_DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private enum class ArchiveMode(val title: String) {
    ByDate("По датам"),
    ByFolder("По папкам")
}

/** Список записей за выбранный день */
private sealed class DayState {
    object Loading : DayState()
    data class Loaded(val recordings: List<DahuaRecording>) : DayState()
    /** isError — камера не ответила; иначе это ответ «записей нет» */
    data class Message(val text: String, val isError: Boolean = true) : DayState()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f ГБ", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.1f МБ", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.getDefault(), "%d КБ", bytes / 1024)
}

/**
 * Архив записей камеры Dahua: список по дням (выбор стрелками или календарём) или просмотр папок
 * SD-карты (день → папка → файл); выбранная запись воспроизводится по RTSP прямо в окне.
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DahuaArchiveDialog(camera: IpCamera, initialStart: LocalDateTime? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(ArchiveMode.ByDate) }
    var selected by remember { mutableStateOf<DahuaRecording?>(null) }

    // Одна сессия с камерой на всё окно: камера не терпит параллельных запросов, поэтому они идут по очереди.
    // Индекс знает дни с записями: только их можно выбрать стрелками, в календаре и в списке папок
    val archiveSession = remember { DahuaArchiveSession(camera) }
    val index = remember { ArchiveIndex(archiveSession, camera.id) }
    DisposableEffect(Unit) {
        onDispose { archiveSession.close() }
    }

    // Увеличивается после удаления записи: списки дня перечитываются
    var refreshKey by remember { mutableStateOf(0) }

    // Режим «По датам»: выбранный день (пока не найден ни один день с записями — null)
    // Если окно открыто с конкретной записи (клик в блоке «Архив видео»), сразу выбран её день
    var day by remember { mutableStateOf<LocalDate?>(initialStart?.toLocalDate()) }
    // Запись, которую надо включить, как только загрузится её день
    var pendingStart by remember { mutableStateOf(initialStart) }
    var dayState by remember { mutableStateOf<DayState>(DayState.Loading) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showSdSettings by remember { mutableStateOf(false) }
    var gridMode by rememberSaveable { mutableStateOf(false) }

    // Режим «По папкам»: текущая папка (день, затем подпапка дня) и записи дня
    var folderDay by remember { mutableStateOf<LocalDate?>(null) }
    var folderDir by remember { mutableStateOf<String?>(null) }
    var folderState by remember { mutableStateOf<DayState>(DayState.Loading) }

    // Отдельная сессия воспроизведения: не трогает живую трансляцию камеры в основном окне
    var playback by remember { mutableStateOf<StreamSession?>(null) }
    DisposableEffect(Unit) {
        onDispose { playback?.release() }
    }

    var videoFullscreen by remember { mutableStateOf(false) }
    // Сессия берёт звук из настройки живой трансляции этой камеры, а у архива свой общий выключатель звука
    LaunchedEffect(Unit) { ArchiveAudio.load(context) }
    LaunchedEffect(playback, ArchiveAudio.isMuted) {
        playback?.player?.volume = ArchiveAudio.volume
    }

    // Обёртка над плеером сессии: перемотка и повтор после конца записи (камера новый PLAY в той же сессии не принимает)
    val archivePlayer = remember(playback) { playback?.let { ArchivePlayer(it) } }
    DisposableEffect(archivePlayer) {
        onDispose { archivePlayer?.dispose() }
    }
    // Запись доиграна: плеер стоит на паузе до нажатия «воспроизвести», а не вечно «грузится»
    LaunchedEffect(archivePlayer) {
        val player = archivePlayer ?: return@LaunchedEffect
        while (true) {
            delay(300)
            player.pauseIfEnded()
        }
    }
    val archivePlayback = remember(archivePlayer) {
        val session = playback
        archivePlayer?.let { player ->
            Media3ArchivePlayback(
                player,
                bufferingOf = { session != null && session.isBuffering && !session.hasError && !player.isEnded },
                errorOf = { if (session != null && session.hasError) player.playerError?.localizedMessage ?: "ошибка воспроизведения" else null }
            )
        }
    }

    fun recordingsState(recordings: List<DahuaRecording>, target: LocalDate): DayState =
        if (recordings.isEmpty()) DayState.Message("За ${target.format(DAY_FORMAT)} записей нет", isError = false) else DayState.Loaded(recordings)

    suspend fun loadDay(target: LocalDate): DayState =
        if (index.rpcAvailable) {
            try {
                recordingsState(archiveSession.recordings(target), target)
            } catch (e: ArchiveException) {
                DayState.Message(e.message.orEmpty())
            }
        } else {
            // Старые прошивки: список записей только по CGI
            when (val result = DahuaApi.findRecordings(camera, target)) {
                is ArchiveResult.Found -> recordingsState(result.recordings, target)
                is ArchiveResult.Unsupported -> DayState.Message(result.message)
                is ArchiveResult.Failed -> DayState.Message(result.message)
            }
        }

    // Список записей дня: из кэша показывается мгновенно, а камера опрашивается заново каждую минуту,
    // пока окно открыто. Ошибку опроса поверх уже показанного списка не выводим
    suspend fun watchDay(target: LocalDate, current: () -> DayState, update: (DayState) -> Unit) {
        val key = ArchiveListCache.dayKey("dahua", camera.id, target)
        val cached = ArchiveListCache.get<List<DahuaRecording>>(key)
        update(if (cached != null) DayState.Loaded(cached) else DayState.Loading)
        while (true) {
            val state = loadDay(target)
            when {
                state is DayState.Loaded -> ArchiveListCache.put(key, state.recordings)
                state is DayState.Message && !state.isError -> ArchiveListCache.remove(key)
            }
            val showsList = current() is DayState.Loaded
            if (state != current() && !(state is DayState.Message && state.isError && showsList)) update(state)
            if (state is DayState.Loaded) {
                // Идущая запись растёт от обновления к обновлению: выбранная остаётся той же по пути и началу
                selected?.let { open ->
                    state.recordings.firstOrNull { it.filePath == open.filePath && it.start == open.start && it != open }?.let { selected = it }
                }
            }
            delay(ArchiveListCache.REFRESH_INTERVAL_MS)
        }
    }

    LaunchedEffect(day, refreshKey) {
        val target = day ?: return@LaunchedEffect
        selected = null
        watchDay(target, { dayState }, { dayState = it })
    }

    // Дни с записями ищутся сразу при открытии окна, от самого свежего: он и становится выбранным днём.
    // Если камера не отдаёт архив через JSON-RPC, календарь не ограничивается и открывается сегодняшний день
    LaunchedEffect(Unit) {
        index.run { newest -> if (day == null) day = newest }
        if (!index.rpcAvailable && day == null) day = LocalDate.now()
        index.keepFresh()
    }

    LaunchedEffect(folderDay, refreshKey) {
        val target = folderDay ?: return@LaunchedEffect
        watchDay(target, { folderState }, { folderState = it })
    }

    val scope = rememberCoroutineScope()
    // Идёт подготовка воспроизведения (чтение параметров кодека) и задача, которая её выполняет
    var preparing by remember { mutableStateOf(false) }
    var playJob by remember { mutableStateOf<Job?>(null) }

    fun play(recording: DahuaRecording) {
        selected = recording
        // Запись — это тот же RTSP-порт, но другой путь; потоки живой трансляции в нём не участвуют
        val playbackCamera = camera.copy(rtspPath = recording.playbackPath, streamProfiles = emptyList(), activeStreamProfileId = null)
        playJob?.cancel()
        playJob = scope.launch {
            // Архив Dahua хранится в H.265, а в SDP камера параметры кодека не присылает: берём их из потока заранее
            preparing = true
            try {
                H265ParamSets.prepare(playbackCamera)
            } finally {
                preparing = false
            }
            val session = playback
            if (session == null) {
                playback = StreamSession(context.applicationContext, playbackCamera)
            } else {
                session.updateCamera(playbackCamera)
            }
        }
    }

    // Открыто с конкретной записи: включаем её (или ближайшую по времени начала), когда загрузился день
    LaunchedEffect(dayState) {
        val wanted = pendingStart ?: return@LaunchedEffect
        val list = (dayState as? DayState.Loaded)?.recordings ?: return@LaunchedEffect
        pendingStart = null
        list.minByOrNull { kotlin.math.abs(Duration.between(it.start, wanted).seconds) }?.let { play(it) }
    }

    // Скачивание и удаление записей
    var downloading by remember { mutableStateOf<DahuaRecording?>(null) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var deleteTarget by remember { mutableStateOf<DahuaRecording?>(null) }
    var deleteNotice by remember { mutableStateOf<String?>(null) }

    fun download(recording: DahuaRecording) {
        if (downloading != null) return
        downloading = recording
        downloadedBytes = 0
        scope.launch {
            try {
                // Имя файла: камера и время записи, а не служебное имя вида 10.46.11-10.46.13[M][0@0][0].dav
                val safeCamera = camera.name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').ifBlank { "camera" }
                val time = recording.start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                val saved = RecordingSaver.save(context, "${safeCamera}_$time.dav") { out ->
                    DahuaArchive.download(camera, recording, out) { downloadedBytes = it }
                }
                DownloadNotifier.notifyDownloaded(context.applicationContext, saved)
                Toast.makeText(context, "Сохранено: Загрузки/${SnapshotSaver.FOLDER_NAME}/${saved.name}", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось скачать: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } finally {
                downloading = null
            }
        }
    }

    fun deleteRecording(recording: DahuaRecording) {
        scope.launch {
            when (val result = DahuaArchive.delete(camera, recording)) {
                DahuaArchive.DeleteResult.Deleted -> {
                    Toast.makeText(context, "Запись удалена", Toast.LENGTH_SHORT).show()
                    ArchiveListCache.clearCamera(camera.id)
                    refreshKey++
                }
                is DahuaArchive.DeleteResult.NotSupported -> deleteNotice = result.message
                is DahuaArchive.DeleteResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val recordingActions = remember(downloading, downloadedBytes) {
        RecordingActions(
            downloading = downloading,
            downloadedBytes = downloadedBytes,
            onDownload = ::download,
            onDelete = { deleteTarget = it }
        )
    }

    // Настройки SD-карты: вкладка SD сама применяет изменения к камере. После форматирования индекс дней и списки
    // записей окна устарели: окно архива закрывается и откроется заново
    if (showSdSettings) {
        ArchiveSdSettings(camera, onClose = { showSdSettings = false }, onCloseArchive = onDismiss)
    }

    val phone = isPhoneScreen()
    val split = isPhoneLandscape()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !phone)
    ) {
        // Превью записей строятся отдельными подключениями к камере; пока идёт воспроизведение, новые не запрашиваются
        val thumbnailSource = remember(camera, playback == null && !preparing) { ThumbnailSource(camera, enabled = playback == null && !preparing) }
        CompositionLocalProvider(
            LocalRecordingActions provides recordingActions,
            LocalThumbnailSource provides thumbnailSource,
            LocalGridMode provides gridMode
        ) {
        // Смартфон: окно архива на весь экран
        if (phone) HideSystemBarsInDialog()
        Surface(
            shape = if (phone) RectangleShape else MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = if (phone) Modifier.fillMaxSize()
            else Modifier
                .padding(16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
        ) {
            Column(modifier = if (phone) Modifier.fillMaxSize().archivePhoneInsets() else Modifier.padding(bottom = 16.dp)) {
                ArchiveHeader(
                    camera = camera,
                    gridMode = gridMode,
                    onGridModeChange = { gridMode = it },
                    onSettings = { showSdSettings = true },
                    onClose = onDismiss
                )

                ArchiveContent(
                    split = split,
                    pinVideo = phone,
                    modifier = if (phone) Modifier.weight(1f) else Modifier,
                    video = { videoModifier ->
                        // Видео выбранной записи: тот же плеер, что у всех камер; отдельная громкость архива,
                        // звук живой трансляции камеры этим не меняется.
                        // Пока запись не выбрана — плейсхолдер того же размера
                        val videoSession = playback
                        if (videoSession == null && !preparing) {
                            ArchivePlaceholder(videoModifier)
                        } else if (videoSession != null && videoFullscreen) {
                            // Само видео сейчас в полноэкранном окне
                            Box(
                                modifier = videoModifier.background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Полноэкранный режим", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            DahuaVideo(
                                session = videoSession,
                                archivePlayer = archivePlayer,
                                playback = archivePlayback,
                                preparing = preparing,
                                isFullscreen = false,
                                onFullscreenChange = { videoFullscreen = it },
                                modifier = videoModifier
                            )
                        }
                    }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        ArchiveMode.entries.forEach { entry ->
                            FilterChip(
                                selected = mode == entry,
                                onClick = { mode = entry },
                                label = { Text(entry.title) }
                            )
                        }
                    }

                    when (mode) {
                        ArchiveMode.ByDate -> {
                            val current = day
                            val today = LocalDate.now()
                            // Стрелки ведут к соседним дням, в которые есть записи (без JSON-RPC — просто к соседним суткам)
                            val hasPrevious = current != null && (!index.rpcAvailable || index.hasPrevious(current))
                            val hasNext = current != null &&
                                if (index.rpcAvailable) index.hasNext(current) else current.isBefore(today)
                            // Нажатие на дату открывает календарь с днями, в которые есть записи
                            ArchiveDayBar(
                                day = current,
                                canGoBack = hasPrevious,
                                canGoForward = hasNext,
                                onBack = {
                                    val from = current ?: return@ArchiveDayBar
                                    scope.launch {
                                        val target = if (index.rpcAvailable) index.previousDay(from) else from.minusDays(1)
                                        if (target != null) day = target
                                    }
                                },
                                onForward = {
                                    val from = current ?: return@ArchiveDayBar
                                    scope.launch {
                                        val target = if (index.rpcAvailable) index.nextDay(from) else from.plusDays(1)
                                        if (target != null) day = target
                                    }
                                },
                                onPickDate = { showDatePicker = true }
                            )
                            when {
                                current != null -> DayContent(
                                    state = dayState,
                                    selected = selected,
                                    showFileName = false,
                                    onPlay = ::play
                                )
                                index.status == ArchiveIndex.Status.Scanning -> ArchiveLoadingRow("Поиск дней с записями…")
                                index.failure != null -> ArchiveMessage(index.failure?.message.orEmpty())
                                else -> ArchiveMessage("На SD-карте нет записей")
                            }
                            if (current != null && index.rpcAvailable && index.isLoading) {
                                Text(
                                    "Ищу остальные дни с записями…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        ArchiveMode.ByFolder -> FolderContent(
                            index = index,
                            folderDay = folderDay,
                            folderDir = folderDir,
                            folderState = folderState,
                            selected = selected,
                            onOpenDay = {
                                folderDay = it
                                folderDir = null
                            },
                            onOpenDir = { folderDir = it },
                            onBack = {
                                if (folderDir != null) folderDir = null else folderDay = null
                            },
                            onPlay = ::play
                        )
                    }
                }
            }
        }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить запись?") },
            text = {
                Text(
                    "Запись ${target.start.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))} " +
                        "(${target.lengthBytes?.let { formatBytes(it) } ?: "размер неизвестен"}) будет удалена с карты без возможности восстановления."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        deleteRecording(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } }
        )
    }

    deleteNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteNotice = null },
            title = { Text("Удаление недоступно") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { deleteNotice = null }) { Text("Понятно") } }
        )
    }

    // Полноэкранный плеер записи: то же видео и те же элементы управления на весь экран
    val fullscreenSession = playback
    if (videoFullscreen && fullscreenSession != null) {
        ArchiveFullscreenDialog(onDismiss = { videoFullscreen = false }) {
            DahuaVideo(
                session = fullscreenSession,
                archivePlayer = archivePlayer,
                playback = archivePlayback,
                preparing = false,
                isFullscreen = true,
                onFullscreenChange = { videoFullscreen = it },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showDatePicker) {
        val today = LocalDate.now()
        ArchiveDatePicker(
            selected = day,
            // Годы поиска — те же, что просматривает камера; выбрать можно только год, месяц и день с записями.
            // Индекс пополняется по ходу поиска, поэтому условия читают его при каждой проверке.
            // Без JSON-RPC дни с записями неизвестны — доступны все прошедшие
            yearRange = (today.year - 3)..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = utcTimeMillis.toLocalDateUtc()
                    return if (index.rpcAvailable) index.hasRecordings(date) else !date.isAfter(today)
                }

                override fun isSelectableYear(year: Int): Boolean = if (index.rpcAvailable) index.hasYear(year) else year <= today.year
            },
            onPick = { day = it },
            onDismiss = { showDatePicker = false },
            // Месяц, который открыл пользователь, загружается вне очереди фоновой подгрузки
            onMonthShown = { month ->
                if (index.rpcAvailable && month in index.months) index.loadMonth(month)
            }
        )
    }
}

/**
 * Видео записи: тот же плеер, что у остальных камер. Своя только поверхность: сессия Dahua рисует кадры в SurfaceView,
 * который ей передаёт PlayerView.
 */
@OptIn(UnstableApi::class)
@Composable
private fun DahuaVideo(
    session: StreamSession?,
    archivePlayer: Player?,
    playback: ArchivePlayback?,
    preparing: Boolean,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ArchiveVideo(
        player = archivePlayer,
        playback = playback,
        isFullscreen = isFullscreen,
        onFullscreenChange = onFullscreenChange,
        modifier = modifier,
        preparing = preparing,
        onViewCreated = { view -> session?.attachSurfaceView(view.videoSurfaceView as? SurfaceView) },
        onViewReleased = { view -> session?.detachSurfaceView(view.videoSurfaceView as? SurfaceView) }
    )
}

/**
 * Плеер записи для кнопок управления. Камера не принимает новый PLAY в той же сессии после конца записи
 * (воспроизведение зависает на буферизации), поэтому повтор и перемотка после конца переподключают поток,
 * а нужная позиция выставляется, когда поток снова готов.
 */
@OptIn(UnstableApi::class)
private class ArchivePlayer(private val session: StreamSession) : ForwardingPlayer(session.player) {

    // Переподключение запущено и ещё не закончилось: повторные команды в это время не нужны
    private var restarting = false
    private var pendingSeekMs: Long? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!restarting) return
            when (playbackState) {
                Player.STATE_READY -> {
                    restarting = false
                    pendingSeekMs?.let { session.player.seekTo(it) }
                    pendingSeekMs = null
                }
                Player.STATE_IDLE -> Unit
                Player.STATE_ENDED -> restarting = false
            }
        }
    }

    init {
        session.player.addListener(listener)
    }

    fun dispose() {
        session.player.removeListener(listener)
    }

    /**
     * Запись доиграна. Камера в конце записи не завершает поток, поэтому плеер не переходит в «конец»,
     * а повисает на буферизации у самой границы длительности — это тоже конец
     */
    val isEnded: Boolean
        get() {
            val player = session.player
            if (player.playbackState == Player.STATE_ENDED) return true
            val duration = player.duration
            return player.playbackState == Player.STATE_BUFFERING && duration != C.TIME_UNSET && duration > 0 &&
                player.currentPosition >= duration - END_TOLERANCE_MS
        }

    /** Ставит доигранную запись на паузу: иначе плеер вечно «грузится», хотя данных больше не будет */
    fun pauseIfEnded() {
        if (!restarting && isEnded && session.player.playWhenReady) session.player.pause()
    }

    private fun restartAt(positionMs: Long) {
        if (restarting) {
            pendingSeekMs = positionMs.takeIf { it > 0 }
            return
        }
        restarting = true
        pendingSeekMs = positionMs.takeIf { it > 0 }
        session.reconnect()
    }

    override fun play() {
        if (restarting) return
        if (isEnded) restartAt(0) else super.play()
    }

    override fun seekToDefaultPosition() {
        if (isEnded || restarting) restartAt(0) else super.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (isEnded || restarting) restartAt(0) else super.seekToDefaultPosition(mediaItemIndex)
    }

    override fun seekTo(positionMs: Long) {
        if (isEnded || restarting) restartAt(positionMs) else super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (isEnded || restarting) restartAt(positionMs) else super.seekTo(mediaItemIndex, positionMs)
    }

    private companion object {
        const val END_TOLERANCE_MS = 500L
    }
}

/** Состояние списка записей дня: загрузка, сообщение или сами записи */
@Composable
private fun DayContent(
    state: DayState,
    selected: DahuaRecording?,
    showFileName: Boolean,
    onPlay: (DahuaRecording) -> Unit
) {
    when (state) {
        DayState.Loading -> ArchiveLoadingRow()
        is DayState.Message -> ArchiveMessage(state.text)
        is DayState.Loaded -> RecordingList(state.recordings, selected, showFileName, onPlay)
    }
}

@Composable
private fun FolderContent(
    index: ArchiveIndex,
    folderDay: LocalDate?,
    folderDir: String?,
    folderState: DayState,
    selected: DahuaRecording?,
    onOpenDay: (LocalDate) -> Unit,
    onOpenDir: (String) -> Unit,
    onBack: () -> Unit,
    onPlay: (DahuaRecording) -> Unit
) {
    val volumes = index.storage
    // Известные сейчас дни: список растёт по мере того, как камера отдаёт дни месяцев
    val days = index.knownDays()

    if (folderDay == null) {
        // Корень накопителя: сведения о карте и папки по дням
        volumes.firstOrNull()?.let { volume ->
            val total = volume.totalBytes
            val used = volume.usedBytes
            val usage = if (total != null && used != null && total > 0) {
                " · ${formatBytes(total)}, занято ${used * 100 / total}%"
            } else ""
            Text(
                "SD-карта ${volume.path}$usage",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        when {
            index.failure != null -> ArchiveMessage(index.failure?.message.orEmpty())
            days.isEmpty() && index.isLoading -> ArchiveLoadingRow("Поиск папок с записями…")
            days.isEmpty() -> ArchiveMessage("На SD-карте нет записей")
            else -> {
                if (index.isLoading) ArchiveLoadingRow("Поиск папок… найдено: ${days.size}")
                LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                    items(days) { date ->
                        val name = date.format(FOLDER_DAY_FORMAT)
                        FolderRow(name = name, subtitle = "${volumes.firstOrNull()?.path ?: ""}/$name", onClick = { onOpenDay(date) })
                    }
                }
            }
        }
        return
    }

    // Внутри дня: стрелка назад и путь текущей папки
    val volumePath = volumes.firstOrNull()?.path ?: "/mnt/sd"
    val dayName = folderDay.format(FOLDER_DAY_FORMAT)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
        }
        Text(
            text = folderDir ?: "$volumePath/$dayName",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }

    when (folderState) {
        DayState.Loading -> ArchiveLoadingRow()
        is DayState.Message -> ArchiveMessage(folderState.text)
        is DayState.Loaded -> {
            val recordings = folderState.recordings
            if (folderDir == null) {
                // Подпапки дня: файлы одного дня группируются по папке, в которой лежат
                val byDir = recordings.groupBy { it.directory ?: "—" }.toSortedMap()
                if (byDir.size == 1 && byDir.keys.first() == "—") {
                    // Камера не сообщила пути файлов — показываем записи списком
                    RecordingList(recordings, selected, showFileName = false, onPlay = onPlay)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                        items(byDir.entries.toList()) { (dir, files) ->
                            val total = files.sumOf { it.lengthBytes ?: 0L }
                            FolderRow(
                                name = dir.removePrefix("$volumePath/"),
                                subtitle = "Файлов: ${files.size}" + if (total > 0) " · ${formatBytes(total)}" else "",
                                onClick = { onOpenDir(dir) }
                            )
                        }
                    }
                }
            } else {
                RecordingList(recordings.filter { (it.directory ?: "—") == folderDir }, selected, showFileName = true, onPlay = onPlay)
            }
        }
    }
}

@Composable
private fun RecordingList(
    recordings: List<DahuaRecording>,
    selected: DahuaRecording?,
    showFileName: Boolean,
    onPlay: (DahuaRecording) -> Unit
) {
    // От новых к старым: так запись, которая только что закончилась (обычно самая свежая), видно сразу сверху
    ArchiveRecordingList(
        items = recordings.asReversed(),
        gridMode = LocalGridMode.current,
        row = { recording ->
            RecordingRow(recording, isSelected = recording == selected, showFileName = showFileName, onClick = { onPlay(recording) })
        },
        cell = { recording ->
            RecordingCell(recording, isSelected = recording == selected, onClick = { onPlay(recording) })
        }
    )
}

/** Превью записи: из кэша всегда; с камеры — только пока не идёт воспроизведение (и только для видимых строк) */
@Composable
private fun rememberRecordingThumbnail(recording: DahuaRecording): Bitmap? {
    val context = LocalContext.current
    val source = LocalThumbnailSource.current
    return rememberArchiveThumbnail(
        key = recording,
        buildEnabled = source?.enabled == true,
        cached = { source?.let { DahuaThumbnails.cached(context, it.camera, recording) } },
        load = { source?.let { DahuaThumbnails.load(context, it.camera, recording) } }
    )
}

/** Скачать и удалить запись; пока файл скачивается, вместо кнопки — процент (null — окно без таких действий) */
@Composable
private fun recordingActions(recording: DahuaRecording): ArchiveRecordActions? {
    val actions = LocalRecordingActions.current ?: return null
    val total = recording.lengthBytes
    return ArchiveRecordActions(
        download = ArchiveDownloadAction(
            active = actions.downloading == recording,
            progress = if (total != null && total > 0) (actions.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) else null,
            enabled = actions.downloading == null && recording.filePath != null,
            label = "Скачать запись",
            onClick = { actions.onDownload(recording) }
        ),
        onDelete = { actions.onDelete(recording) },
        deleteEnabled = recording.filePath != null
    )
}

/** Запись в сетке: превью с временем начала, кнопки скачивания и удаления поверх */
@Composable
private fun RecordingCell(recording: DahuaRecording, isSelected: Boolean, onClick: () -> Unit) {
    ArchiveRecordingCell(
        start = recording.start,
        thumbnail = rememberRecordingThumbnail(recording),
        isSelected = isSelected,
        onClick = onClick,
        actions = recordingActions(recording)
    )
}

private val LocalGridMode = staticCompositionLocalOf { false }


@Composable
private fun FolderRow(name: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecordingRow(recording: DahuaRecording, isSelected: Boolean, showFileName: Boolean, onClick: () -> Unit) {
    // «Движение · 3,8 МБ»: по какому событию сделана запись и сколько она весит
    val details = buildList {
        recording.events.mapNotNull { DahuaApi.eventTitle(it) ?: it.takeIf { code -> code.isNotBlank() } }.distinct()
            .takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
        recording.lengthBytes?.let { add(formatBytes(it)) }
        if (showFileName) recording.fileName?.let { add(it) }
    }.joinToString(" · ")
    ArchiveRecordingRow(
        start = recording.start,
        end = recording.end,
        durationMs = Duration.between(recording.start, recording.end).toMillis(),
        details = details,
        thumbnail = rememberRecordingThumbnail(recording),
        isSelected = isSelected,
        onClick = onClick,
        actions = recordingActions(recording)
    )
}

/** Действия над записью в списке: скачать и удалить (null — недоступны) и состояние текущего скачивания */
private class RecordingActions(
    val downloading: DahuaRecording?,
    val downloadedBytes: Long,
    val onDownload: (DahuaRecording) -> Unit,
    val onDelete: (DahuaRecording) -> Unit
)

private val LocalRecordingActions = staticCompositionLocalOf<RecordingActions?> { null }

/** Откуда строки списка берут превью записей и можно ли сейчас обращаться за ними к камере */
private class ThumbnailSource(val camera: IpCamera, val enabled: Boolean)

private val LocalThumbnailSource = staticCompositionLocalOf<ThumbnailSource?> { null }

