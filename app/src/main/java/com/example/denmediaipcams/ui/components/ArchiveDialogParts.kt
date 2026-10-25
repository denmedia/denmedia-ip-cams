package com.example.denmediaipcams.ui.components

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView
import com.example.denmediaipcams.R
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.player.ArchiveAudio
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/*
 * Общие части окон архива всех камер (Dahua, Hipcam, Hikvision, XMEye/DVRIP): окна выглядят и работают одинаково,
 * а то, чего камера не умеет (скачать, удалить, перемотать), в окне просто не показывается.
 */

internal val ARCHIVE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
internal val ARCHIVE_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toLocalDateUtc(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

internal fun formatArchiveSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f ГБ", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.1f МБ", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.getDefault(), "%d КБ", bytes / 1024)
}

/** «5 мин 52 с» или «14 с» */
internal fun formatArchiveDuration(durationMs: Long): String {
    val minutes = durationMs / 60_000
    val seconds = durationMs / 1000 % 60
    return if (minutes >= 1) "$minutes мин $seconds с" else "$seconds с"
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

// ---------------------------------------------------------------------------------------------------------------------
// Плеер

/** Что нужно панели управления от плеера записи; у каждой камеры плеер свой, панель одна */
internal interface ArchivePlayback {
    val isPlaying: Boolean
    val isBuffering: Boolean
    val error: String?

    /** Длительность записи; 0 — неизвестна (полосы перемотки тогда нет) */
    val durationMs: Long

    fun positionMs(): Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

/** Плеер записи, который держит окно архива: картинка идёт из [player], управление — через [playback] */
internal interface ArchiveSession {
    val player: Player
    val playback: ArchivePlayback
    fun setVolume(volume: Float)
    fun release()
}

/** [ArchivePlayback] поверх обычного плеера Media3 (в том числе обёрток, которые сами перематывают запись) */
@OptIn(UnstableApi::class)
internal class Media3ArchivePlayback(
    private val player: Player,
    private val bufferingOf: () -> Boolean = { player.playbackState == Player.STATE_BUFFERING },
    private val errorOf: () -> String? = {
        player.playerError?.let { it.localizedMessage ?: "Не удалось воспроизвести запись" }
    }
) : ArchivePlayback {

    override val isPlaying: Boolean get() = !Util.shouldShowPlayButton(player)

    override val isBuffering: Boolean get() = bufferingOf()

    override val error: String? get() = errorOf()

    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    override fun positionMs(): Long = player.currentPosition.coerceAtLeast(0)

    override fun play() {
        if (player.playerError != null) player.prepare()
        Util.handlePlayButtonAction(player)
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
}

/**
 * Видео записи: картинка плеера и своя нижняя панель управления (пауза, время, полоса перемотки, звук, полный экран).
 * [player] и [playback] — null, пока плеер ещё готовится (виден только индикатор загрузки).
 * [onViewCreated] / [onViewReleased] — для плееров, которым нужна своя поверхность (см. StreamSession).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun ArchiveVideo(
    player: Player?,
    playback: ArchivePlayback?,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    preparing: Boolean = false,
    onViewCreated: (PlayerView) -> Unit = {},
    onViewReleased: (PlayerView) -> Unit = {}
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        this.player = player
                        onViewCreated(this)
                    }
                },
                update = { view -> if (view.player !== player) view.player = player },
                onRelease = { view ->
                    onViewReleased(view)
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        val error = if (preparing) null else playback?.error
        when {
            error != null -> Text(
                "Не удалось воспроизвести запись\n$error\nНажмите ▶, чтобы повторить",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
            preparing || playback == null || playback.isBuffering -> CircularProgressIndicator(color = Color.White)
        }
        if (playback != null) {
            ArchivePlayerControls(
                playback = playback,
                isFullscreen = isFullscreen,
                onFullscreenChange = onFullscreenChange,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** Нижняя панель плеера: ▶/⏸, время, полоса перемотки (если длительность известна), звук, полный экран */
@Composable
private fun ArchivePlayerControls(
    playback: ArchivePlayback,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var position by remember(playback) { mutableStateOf(playback.positionMs()) }
    var duration by remember(playback) { mutableStateOf(playback.durationMs) }
    var playing by remember(playback) { mutableStateOf(playback.isPlaying) }
    // Пока полоса перетаскивается, показываем палец, а не позицию плеера; перемотка — когда палец отпущен
    var dragFraction by remember(playback) { mutableStateOf<Float?>(null) }
    fun refresh() {
        position = playback.positionMs()
        duration = playback.durationMs
        playing = playback.isPlaying
    }
    LaunchedEffect(playback) {
        while (true) {
            refresh()
            delay(250)
        }
    }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp)
    ) {
        IconButton(
            onClick = {
                if (playing) playback.pause() else playback.play()
                refresh()
            }
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Пауза" else "Воспроизвести",
                tint = Color.White
            )
        }
        if (duration > 0) {
            Text(formatClock((fraction * duration).toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { playback.seekTo((it * duration).toLong()) }
                    dragFraction = null
                    refresh()
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(formatClock(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        IconButton(onClick = { ArchiveAudio.toggle(context) }) {
            Icon(
                painterResource(if (ArchiveAudio.isMuted) R.drawable.ic_archive_volume_off else R.drawable.ic_archive_volume_up),
                contentDescription = if (ArchiveAudio.isMuted) "Включить звук" else "Выключить звук",
                tint = Color.White
            )
        }
        IconButton(onClick = { onFullscreenChange(!isFullscreen) }) {
            Icon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "Выйти из полного экрана" else "На весь экран",
                tint = Color.White
            )
        }
    }
}

/** Видео на весь экран: плавно появляется, закрывается кнопкой в панели плеера или системной кнопкой «назад» */
@Composable
internal fun ArchiveFullscreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(250)) }
        Surface(
            color = Color.Black,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = appear.value
                    alpha = p
                    scaleX = 0.7f + 0.3f * p
                    scaleY = 0.7f + 0.3f * p
                }
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Заголовок, дни, служебные строки

/** Заголовок окна: название, переключатель список/сетка, настройки SD (если есть у камеры) и «закрыть» */
@Composable
internal fun ArchiveHeader(
    camera: IpCamera,
    gridMode: Boolean,
    onGridModeChange: (Boolean) -> Unit,
    onSettings: (() -> Unit)?,
    onClose: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 8.dp)
    ) {
        Text(
            text = "Архив: ${camera.name}",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onGridModeChange(!gridMode) }) {
            if (gridMode) {
                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Показать списком")
            } else {
                Icon(Icons.Default.GridView, contentDescription = "Показать сеткой")
            }
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки SD")
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Закрыть")
        }
    }
}

/**
 * Настройки SD-карты: вкладка SD сама применяет изменения к камере, сохранять саму камеру не нужно.
 * После форматирования списки дней и записей окна архива устарели, поэтому окно архива закрывается и откроется заново.
 */
@Composable
internal fun ArchiveSdSettings(camera: IpCamera, onClose: () -> Unit, onCloseArchive: () -> Unit) {
    var formatted by remember { mutableStateOf(false) }
    val close = {
        onClose()
        if (formatted) onCloseArchive()
    }
    CameraEditDialog(
        cameraToEdit = camera,
        onDismiss = close,
        onSave = { close() },
        openSdTab = true,
        onSdFormatted = {
            formatted = true
            com.example.denmediaipcams.data.ArchiveListCache.clearCamera(camera.id)
        }
    )
}

/** Стрелки к соседним дням и выбранная дата (нажатие открывает календарь) */
@Composable
internal fun ArchiveDayBar(
    day: LocalDate?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onPickDate: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий день")
        }
        TextButton(onClick = onPickDate, enabled = day != null) {
            Text(day?.format(ARCHIVE_DAY_FORMAT) ?: "—")
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий день")
        }
    }
}

@Composable
internal fun ArchiveLoadingRow(text: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        if (text != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ArchiveMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/** Дни, в которые есть записи, можно выбрать в календаре; остальные недоступны */
internal fun selectableDays(days: Set<LocalDate>): SelectableDates = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.toLocalDateUtc() in days
}

/** Календарь выбора дня. [onMonthShown] сообщает, какой месяц сейчас открыт (Dahua подгружает его дни вне очереди) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveDatePicker(
    selected: LocalDate?,
    yearRange: IntRange,
    selectableDates: SelectableDates,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onMonthShown: suspend (YearMonth) -> Unit = {}
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected?.toUtcMillis(),
        yearRange = yearRange,
        selectableDates = selectableDates
    )
    LaunchedEffect(pickerState.displayedMonthMillis) {
        onMonthShown(YearMonth.from(pickerState.displayedMonthMillis.toLocalDateUtc()))
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onPick(it.toLocalDateUtc()) }
                    onDismiss()
                }
            ) { Text("Выбрать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    ) {
        DatePicker(state = pickerState)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Список записей

/** Скачивание записи: кнопка, а пока файл скачивается — ход процесса ([progress] null — ход неизвестен) */
internal class ArchiveDownloadAction(
    val active: Boolean,
    val progress: Float?,
    val enabled: Boolean,
    val label: String,
    val onClick: () -> Unit
)

/** Действия над записью; у камеры, которая чего-то не умеет, соответствующего поля нет и кнопка не показывается */
internal class ArchiveRecordActions(
    val download: ArchiveDownloadAction? = null,
    val onDelete: (() -> Unit)? = null,
    val deleteEnabled: Boolean = true
)

/** Список записей дня (от новых к старым) строками или сеткой превью */
@Composable
internal fun <T> ArchiveRecordingList(
    items: List<T>,
    gridMode: Boolean,
    row: @Composable (T) -> Unit,
    cell: @Composable (T) -> Unit
) {
    if (gridMode) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(LocalArchiveListHeight.current)
        ) {
            gridItems(items) { cell(it) }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(LocalArchiveListHeight.current)) {
            items(items) { row(it) }
        }
    }
}

/**
 * Превью записи: из кэша сразу, с камеры — только когда [buildEnabled] (пока идёт просмотр, камере лишние сеансы
 * не нужны; недостающие превью достраиваются после его окончания). Строится, лишь когда строка появилась на экране.
 */
@Composable
internal fun rememberArchiveThumbnail(
    key: Any,
    buildEnabled: Boolean,
    cached: suspend () -> Bitmap?,
    load: suspend () -> Bitmap?
): Bitmap? {
    var thumbnail by remember(key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key, buildEnabled) {
        if (thumbnail != null) return@LaunchedEffect
        thumbnail = cached()
        if (thumbnail == null && buildEnabled) thumbnail = load()
    }
    return thumbnail
}

/** Кнопки записи: в обычной строке рядом, в компактной — друг под другом */
@Composable
private fun RowActions(actions: ArchiveRecordActions, compact: Boolean) {
    val buttonSize = if (compact) Modifier.size(36.dp) else Modifier
    val content: @Composable () -> Unit = {
        actions.download?.let { download ->
            if (download.active) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (compact) 36.dp else 48.dp)) {
                    val progress = download.progress
                    if (progress != null) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            } else {
                IconButton(onClick = download.onClick, modifier = buttonSize, enabled = download.enabled) {
                    Icon(Icons.Default.Download, contentDescription = download.label)
                }
            }
        }
        actions.onDelete?.let { onDelete ->
            IconButton(onClick = onDelete, modifier = buttonSize, enabled = actions.deleteEnabled) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить запись", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (compact) Column { content() } else Row(verticalAlignment = Alignment.CenterVertically) { content() }
}

/** Запись в списке: превью, время начала и конца, подробности, длительность и действия */
@Composable
internal fun ArchiveRecordingRow(
    start: LocalDateTime,
    end: LocalDateTime,
    durationMs: Long,
    details: String,
    thumbnail: Bitmap?,
    isSelected: Boolean,
    onClick: () -> Unit,
    actions: ArchiveRecordActions? = null
) {
    val duration = formatArchiveDuration(durationMs)
    // Узкий список (смартфон по горизонтали: видео слева, список справа) — компактная строка
    BoxWithConstraints {
        val compact = maxWidth < 380.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = if (compact) 64.dp else 88.dp, height = if (compact) 48.dp else 66.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.6f))
                }
            }
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (compact) "${start.format(ARCHIVE_TIME_FORMAT)}–${end.format(ARCHIVE_TIME_FORMAT)}"
                    else "${start.format(ARCHIVE_TIME_FORMAT)} – ${end.format(ARCHIVE_TIME_FORMAT)}",
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                // В компактной строке длительность стоит под временем, а не отдельной колонкой
                val detailsLine = if (compact) listOf(duration, details).filter { it.isNotEmpty() }.joinToString(" · ") else details
                if (detailsLine.isNotEmpty()) {
                    Text(
                        detailsLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!compact) {
                Text(duration, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actions != null) RowActions(actions, compact)
        }
    }
}

/** Запись в сетке: превью с временем начала, действия поверх */
@Composable
internal fun ArchiveRecordingCell(
    start: LocalDateTime,
    thumbnail: Bitmap?,
    isSelected: Boolean,
    onClick: () -> Unit,
    actions: ArchiveRecordActions? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(MaterialTheme.shapes.small)
            .background(Color.Black)
            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(bitmap = thumbnail.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.6f))
        }
        Text(
            start.format(ARCHIVE_TIME_FORMAT),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        if (actions != null) {
            Row(modifier = Modifier.align(Alignment.TopEnd)) {
                actions.download?.let { download ->
                    if (download.active) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                            val progress = download.progress
                            if (progress != null) {
                                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = Color.White)
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = Color.White)
                            }
                        }
                    } else {
                        IconButton(onClick = download.onClick, enabled = download.enabled, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Download, contentDescription = download.label, tint = Color.White)
                        }
                    }
                }
                actions.onDelete?.let { onDelete ->
                    IconButton(onClick = onDelete, enabled = actions.deleteEnabled, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить запись", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
