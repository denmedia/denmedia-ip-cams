package com.example.denmediaipcams.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import com.example.denmediaipcams.DownloadNotifier
import com.example.denmediaipcams.data.ArchiveListCache
import com.example.denmediaipcams.data.RecordingSaver
import com.example.denmediaipcams.data.SnapshotSaver
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.ArchiveException
import com.example.denmediaipcams.player.ArchiveAudio
import java.io.OutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Скачивание записи в «Загрузки»: [write] пишет её в поток в нужном формате и возвращает число байт */
internal class ArchiveDownloader<R>(
    val extension: String,
    val mimeType: String,
    /** Подпись кнопки скачивания */
    val label: String,
    val write: suspend (Context, IpCamera, R, OutputStream, (Float) -> Unit) -> Long
)

/**
 * Всё, что в окне архива зависит от камеры: откуда берутся дни и записи, как записи проигрываются и какие из действий
 * над ними камера поддерживает ([download] null — скачивать нельзя, кнопка в окне не показывается).
 */
internal class ArchiveProtocol<R : Any>(
    /** Ключ кэша списков (см. ArchiveListCache) */
    val cacheProtocol: String,
    val listDays: suspend (IpCamera) -> List<LocalDate>,
    /** Записи дня по возрастанию времени начала */
    val listRecordings: suspend (IpCamera, LocalDate) -> List<R>,
    val start: (R) -> LocalDateTime,
    val end: (R) -> LocalDateTime,
    /** Одна и та же запись в двух обновлениях списка (идущая запись растёт, но остаётся той же) */
    val same: (R, R) -> Boolean,
    /** Размер, причина записи и прочее под временем записи в строке списка */
    val details: (R) -> String,
    val cachedThumbnail: suspend (Context, IpCamera, R) -> Bitmap?,
    val loadThumbnail: suspend (Context, IpCamera, R) -> Bitmap?,
    /** Запускает воспроизведение записи; [onEnded] вызывается, когда она доиграна */
    val openPlayback: (Context, IpCamera, R, onEnded: () -> Unit) -> ArchiveSession,
    val download: ArchiveDownloader<R>? = null,
    /** Строятся ли превью с камеры, пока идёт просмотр (камере, которая не терпит параллельных сеансов, они мешают) */
    val thumbnailsWhilePlaying: Boolean = true,
    /** Список дней получен с камеры (успешный вход) */
    val onDaysListed: (Context) -> Unit = {}
)

/** [ArchiveSession] для плеера Media3 (ExoPlayer и обёрток над ним): запись доиграна — вызывается [onEnded] */
internal class Media3ArchiveSession(
    override val player: Player,
    onEnded: () -> Unit
) : ArchiveSession {

    override val playback: ArchivePlayback = Media3ArchivePlayback(player)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEnded()
        }
    }

    init {
        player.addListener(listener)
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun release() {
        player.removeListener(listener)
        player.release()
    }
}

/** Дни архива камеры: загрузка, сообщение (нет записей, ошибка) или сами дни */
private sealed class DaysState {
    object Loading : DaysState()
    data class Message(val text: String) : DaysState()
    data class Loaded(val days: List<LocalDate>) : DaysState()
}

/** Записи выбранного дня */
private sealed class RecordingsState<out R> {
    object Loading : RecordingsState<Nothing>()
    data class Message(val text: String) : RecordingsState<Nothing>()
    data class Loaded<R>(val recordings: List<R>) : RecordingsState<R>()
}

/**
 * Окно архива SD-карты камеры (Hipcam, Hikvision, XMEye/DVRIP): день выбирается стрелками или календарём, запись из
 * списка воспроизводится в окне, по окончании включается следующая. Одно окно на все эти камеры: разница между ними
 * описана в [ArchiveProtocol]. [topContent] — то, что нужно показать над видео (например, предупреждение о протоколе).
 */
@Composable
internal fun <R : Any> ArchiveDialog(
    camera: IpCamera,
    initialStart: LocalDateTime?,
    protocol: ArchiveProtocol<R>,
    onDismiss: () -> Unit,
    topContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var daysState by remember { mutableStateOf<DaysState>(DaysState.Loading) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var recordingsState by remember { mutableStateOf<RecordingsState<R>>(RecordingsState.Loading) }
    var selected by remember { mutableStateOf<R?>(null) }
    // Запись, которую надо включить, как только загрузится её день (клик в блоке «Архив видео»)
    var pendingStart by remember { mutableStateOf(initialStart) }
    var showDatePicker by remember { mutableStateOf(false) }
    var videoFullscreen by remember { mutableStateOf(false) }
    var showSdSettings by remember { mutableStateOf(false) }
    var gridMode by rememberSaveable { mutableStateOf(false) }

    // Плеер записи: свой на каждую запись, живая трансляция камеры в основном окне не затрагивается
    var session by remember { mutableStateOf<ArchiveSession?>(null) }
    DisposableEffect(Unit) {
        onDispose { session?.release() }
    }
    LaunchedEffect(Unit) { ArchiveAudio.load(context) }
    LaunchedEffect(session, ArchiveAudio.isMuted) { session?.setVolume(ArchiveAudio.volume) }

    val recordings = (recordingsState as? RecordingsState.Loaded<R>)?.recordings.orEmpty()
    val currentRecordings by rememberUpdatedState(recordings)

    // Скачивание: одновременно — одна запись
    var downloading by remember { mutableStateOf<R?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }

    fun download(recording: R) {
        val downloader = protocol.download ?: return
        if (downloading != null) return
        downloading = recording
        downloadProgress = 0f
        scope.launch {
            try {
                val safeCamera = camera.name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').ifBlank { "camera" }
                val time = protocol.start(recording).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                val saved = RecordingSaver.save(context, "${safeCamera}_$time.${downloader.extension}", mimeType = downloader.mimeType) { out ->
                    downloader.write(context, camera, recording, out) { downloadProgress = it }
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

    fun play(recording: R) {
        session?.release()
        selected = recording
        val next = protocol.openPlayback(context, camera, recording) {
            // Запись доиграна — следующая из списка дня (список хронологический, в отличие от показа). Включается
            // уже после выхода из обработчика плеера: нельзя освобождать плеер из его же события
            scope.launch {
                if (selected?.let { protocol.same(it, recording) } != true) return@launch
                val list = currentRecordings
                val index = list.indexOfFirst { protocol.same(it, recording) }
                list.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { play(it) }
            }
        }
        next.setVolume(ArchiveAudio.volume)
        session = next
    }

    // Дни с записями: самый свежий открывается сразу. Список из кэша показывается мгновенно, а камера
    // опрашивается заново каждую минуту, пока окно открыто
    LaunchedEffect(Unit) {
        val key = ArchiveListCache.daysKey(protocol.cacheProtocol, camera.id)
        fun show(days: List<LocalDate>) {
            if (days.isEmpty()) {
                daysState = DaysState.Message("На SD-карте нет записей")
                return
            }
            if ((daysState as? DaysState.Loaded)?.days != days) daysState = DaysState.Loaded(days)
            if (day == null) day = initialStart?.toLocalDate()?.takeIf { it in days } ?: days.last()
        }
        ArchiveListCache.get<List<LocalDate>>(key)?.let(::show)
        while (true) {
            try {
                val days = protocol.listDays(camera)
                protocol.onDaysListed(context.applicationContext)
                ArchiveListCache.put(key, days)
                show(days)
            } catch (e: ArchiveException) {
                // Показанный из кэша список не заменяем ошибкой: следующая попытка через минуту
                if (daysState !is DaysState.Loaded) daysState = DaysState.Message(e.message.orEmpty())
            }
            delay(ArchiveListCache.REFRESH_INTERVAL_MS)
        }
    }

    LaunchedEffect(day) {
        val target = day ?: return@LaunchedEffect
        val key = ArchiveListCache.dayKey(protocol.cacheProtocol, camera.id, target)
        fun show(list: List<R>) {
            val state: RecordingsState<R> =
                if (list.isEmpty()) RecordingsState.Message("За ${target.format(ARCHIVE_DAY_FORMAT)} записей нет") else RecordingsState.Loaded(list)
            if (state != recordingsState) recordingsState = state
            // Идущая запись растёт от обновления к обновлению: выбранная запись остаётся той же
            selected?.let { current -> list.firstOrNull { protocol.same(it, current) && it != current }?.let { selected = it } }
        }
        val cached = ArchiveListCache.get<List<R>>(key)
        if (cached != null) show(cached) else recordingsState = RecordingsState.Loading
        while (true) {
            try {
                val list = protocol.listRecordings(camera, target)
                ArchiveListCache.put(key, list)
                show(list)
            } catch (e: ArchiveException) {
                if (recordingsState !is RecordingsState.Loaded<*>) recordingsState = RecordingsState.Message(e.message.orEmpty())
            }
            // Пока скачивается запись, камеру лишний раз не тревожим
            delay(ArchiveListCache.REFRESH_INTERVAL_MS)
            while (downloading != null) delay(1_000)
        }
    }

    LaunchedEffect(recordingsState) {
        val wanted = pendingStart ?: return@LaunchedEffect
        val list = (recordingsState as? RecordingsState.Loaded<R>)?.recordings ?: return@LaunchedEffect
        pendingStart = null
        list.minByOrNull { kotlin.math.abs(Duration.between(protocol.start(it), wanted).seconds) }?.let { play(it) }
    }

    val days = (daysState as? DaysState.Loaded)?.days.orEmpty()
    val dayIndex = days.indexOf(day)

    if (showSdSettings) {
        ArchiveSdSettings(camera, onClose = { showSdSettings = false }, onCloseArchive = onDismiss)
    }

    val phone = isPhoneScreen()
    val split = isPhoneLandscape()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !phone)
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
                topContent()

                ArchiveContent(
                    split = split,
                    pinVideo = phone,
                    modifier = if (phone) Modifier.weight(1f) else Modifier,
                    video = { videoModifier ->
                        // Пока запись не выбрана — плейсхолдер того же размера, что и плеер
                        val active = session
                        if (selected == null || active == null) {
                            ArchivePlaceholder(videoModifier)
                        } else if (videoFullscreen) {
                            Box(
                                modifier = videoModifier.background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Полноэкранный режим", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            ArchiveVideo(
                                player = active.player,
                                playback = active.playback,
                                isFullscreen = false,
                                onFullscreenChange = { videoFullscreen = it },
                                modifier = videoModifier
                            )
                        }
                    }
                ) {
                    when (val state = daysState) {
                        DaysState.Loading -> ArchiveLoadingRow()
                        is DaysState.Message -> ArchiveMessage(state.text)
                        is DaysState.Loaded -> {
                            ArchiveDayBar(
                                day = day,
                                canGoBack = dayIndex > 0,
                                canGoForward = dayIndex in 0 until days.lastIndex,
                                onBack = { days.getOrNull(dayIndex - 1)?.let { day = it } },
                                onForward = { days.getOrNull(dayIndex + 1)?.let { day = it } },
                                onPickDate = { showDatePicker = true }
                            )
                            when (val list = recordingsState) {
                                RecordingsState.Loading -> ArchiveLoadingRow()
                                is RecordingsState.Message -> ArchiveMessage(list.text)
                                is RecordingsState.Loaded -> {
                                    val buildThumbnails = protocol.thumbnailsWhilePlaying || session == null
                                    // От новых к старым в списке/сетке; очередь «следующей» записи после конца
                                    // воспроизведения остаётся хронологической — см. recordings выше (без reversed)
                                    ArchiveRecordingList(
                                        items = list.recordings.asReversed(),
                                        gridMode = gridMode,
                                        row = { recording ->
                                            val thumbnail = rememberArchiveThumbnail(
                                                key = recording,
                                                buildEnabled = buildThumbnails,
                                                cached = { protocol.cachedThumbnail(context, camera, recording) },
                                                load = { protocol.loadThumbnail(context, camera, recording) }
                                            )
                                            ArchiveRecordingRow(
                                                start = protocol.start(recording),
                                                end = protocol.end(recording),
                                                durationMs = Duration.between(protocol.start(recording), protocol.end(recording)).toMillis(),
                                                details = protocol.details(recording),
                                                thumbnail = thumbnail,
                                                isSelected = selected?.let { protocol.same(it, recording) } == true,
                                                onClick = { play(recording) },
                                                actions = protocol.download?.let { downloader ->
                                                    downloadActions(downloader, downloading == recording, downloadProgress, downloading == null) {
                                                        download(recording)
                                                    }
                                                }
                                            )
                                        },
                                        cell = { recording ->
                                            val thumbnail = rememberArchiveThumbnail(
                                                key = recording,
                                                buildEnabled = buildThumbnails,
                                                cached = { protocol.cachedThumbnail(context, camera, recording) },
                                                load = { protocol.loadThumbnail(context, camera, recording) }
                                            )
                                            ArchiveRecordingCell(
                                                start = protocol.start(recording),
                                                thumbnail = thumbnail,
                                                isSelected = selected?.let { protocol.same(it, recording) } == true,
                                                onClick = { play(recording) },
                                                actions = protocol.download?.let { downloader ->
                                                    downloadActions(downloader, downloading == recording, downloadProgress, downloading == null) {
                                                        download(recording)
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Полноэкранный плеер: то же видео на весь экран
    val fullscreenSession = session
    if (videoFullscreen && selected != null && fullscreenSession != null) {
        ArchiveFullscreenDialog(onDismiss = { videoFullscreen = false }) {
            ArchiveVideo(
                player = fullscreenSession.player,
                playback = fullscreenSession.playback,
                isFullscreen = true,
                onFullscreenChange = { videoFullscreen = it },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showDatePicker) {
        ArchiveDatePicker(
            selected = day,
            yearRange = (days.firstOrNull()?.year ?: LocalDate.now().year)..(days.lastOrNull()?.year ?: LocalDate.now().year),
            selectableDates = selectableDays(days.toSet()),
            onPick = { day = it },
            onDismiss = { showDatePicker = false }
        )
    }
}

private fun <R> downloadActions(
    downloader: ArchiveDownloader<R>,
    active: Boolean,
    progress: Float,
    enabled: Boolean,
    onDownload: () -> Unit
): ArchiveRecordActions = ArchiveRecordActions(
    download = ArchiveDownloadAction(
        active = active,
        progress = progress,
        enabled = enabled,
        label = downloader.label,
        onClick = onDownload
    )
)
