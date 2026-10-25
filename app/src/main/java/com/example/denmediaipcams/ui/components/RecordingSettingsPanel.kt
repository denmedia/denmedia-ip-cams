@file:OptIn(ExperimentalLayoutApi::class)

package com.example.denmediaipcams.ui.components

import android.app.TimePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraRecording
import com.example.denmediaipcams.network.MotionSettings
import com.example.denmediaipcams.network.MotionZones
import com.example.denmediaipcams.network.RecordKind
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordSlot
import com.example.denmediaipcams.network.RecordingSettings
import com.example.denmediaipcams.network.RecordingWriteResult
import com.example.denmediaipcams.network.WeekSchedule
import com.example.denmediaipcams.network.normalized
import kotlin.math.roundToInt

private val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Состояние настроек записи камеры. Живёт в диалоге камеры, а не в панели: правки не пропадают при смене вкладки,
 * а кнопка «Сохранить» диалога записывает их в камеру ([write]).
 */
class RecordingSettingsState {
    var loading by mutableStateOf(true)
        internal set
    var saving by mutableStateOf(false)
        private set
    var loaded by mutableStateOf<RecordingSettings?>(null)
        internal set
    var draft by mutableStateOf<RecordingSettings?>(null)
    var message by mutableStateOf<String?>(null)
        internal set
    var messageIsError by mutableStateOf(false)
        internal set
    var reload by mutableIntStateOf(0)

    /** Подключение, с которым настройки уже прочитаны: при возврате на вкладку заново не читаем и правки не теряем */
    internal var loadedFor: List<Any?>? = null

    /** Что не так с расписанием (null — всё в порядке) */
    val problem: String?
        get() = draft?.let { current ->
            current.schedule?.takeIf { current.mode == RecordMode.CUSTOM }
                ?.let { scheduleProblem(it, current.scheduleSlotsPerDay) }
        }

    val changed: Boolean
        get() = draft?.let { !CameraRecording.matches(loaded, it.normalized()) } == true

    /** Есть ли что записывать в камеру: правки внесены, расписание в порядке */
    val needsWrite: Boolean
        get() = changed && problem == null

    /** Записывает правки в камеру; true — камера приняла и подтвердила их (или записывать было нечего) */
    suspend fun write(camera: IpCamera): Boolean {
        val current = draft?.takeIf { needsWrite } ?: return true
        saving = true
        message = null
        try {
            return when (val result = CameraRecording.write(camera, current)) {
                is RecordingWriteResult.Saved -> {
                    loaded = result.settings
                    draft = result.settings
                    messageIsError = false
                    true
                }
                is RecordingWriteResult.Failed -> {
                    result.current?.let {
                        loaded = it
                        draft = it
                    }
                    messageIsError = true
                    message = result.message
                    false
                }
            }
        } finally {
            saving = false
        }
    }
}

/**
 * Запись на SD-карту камеры, расписание, детекция движения и людей — настройки самой камеры (Dahua, Hipcam,
 * XMEye/DVRIP). Читаются из камеры при открытии, а в неё записываются кнопкой «Сохранить» диалога камеры
 * ([RecordingSettingsState.write]): в приложении они не хранятся.
 */
@Composable
fun RecordingSettingsPanel(camera: IpCamera, state: RecordingSettingsState) {
    val loading = state.loading
    val saving = state.saving
    val draft = state.draft
    val message = state.message
    val messageIsError = state.messageIsError
    val reload = state.reload
    val context = LocalContext.current
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var backdropLoading by remember { mutableStateOf(false) }
    var backdropReload by remember { mutableIntStateOf(0) }

    // Заново читаем камеру только при смене её подключения или по кнопке обновления
    val connection = listOf(
        camera.ipAddress, camera.effectiveHttpPort, camera.effectiveHipcamPort, camera.effectiveDvripPort,
        camera.username, camera.password, camera.dahuaApi, camera.sdHttpArchive, camera.hikvisionArchive, camera.dvripArchive, reload
    )
    LaunchedEffect(connection) {
        if (state.loadedFor == connection) return@LaunchedEffect
        state.loading = true
        state.message = null
        val settings = CameraRecording.read(camera)
        state.loaded = settings
        state.draft = settings
        if (settings == null) {
            state.messageIsError = true
            state.message = "Камера не отвечает или не поддерживает управление записью."
        }
        state.loadedFor = connection
        state.loading = false
    }

    // Кадр камеры под сеткой зон: сначала последнее превью (оно сразу под рукой), затем свежий кадр.
    // Загружается, только когда у камеры есть выбор зон, и после чтения настроек (камеру не опрашиваем параллельно)
    val hasZones = draft?.motion?.zones != null
    LaunchedEffect(hasZones, backdropReload) {
        if (!hasZones) return@LaunchedEffect
        backdropLoading = true
        if (backdrop == null) ZoneBackdrop.cached(context, camera)?.let { backdrop = it.asImageBitmap() }
        ZoneBackdrop.fresh(context, camera)?.let { backdrop = it.asImageBitmap() }
        backdropLoading = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Запись и детекция движения",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (loading || saving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TooltipIconButton(tooltip = "Прочитать настройки из камеры", onClick = { state.reload++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Прочитать настройки из камеры")
                }
            }
        }
        Text(
            "Настройки хранятся в самой камере и записываются в неё кнопкой «Сохранить».",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (camera.dvripArchive && !camera.hasDahuaApi && !camera.sdHttpArchive && !camera.hikvisionArchive) {
            Text(
                "⚠ Настройки передаются по протоколу XMEye/DVRIP: он слабо защищён, а у некоторых прошивок возможны зависания от сетевых запросов.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }

        val current = draft
        if (current != null) {
            Spacer(modifier = Modifier.height(12.dp))
            RecordingForm(
                current,
                backdrop = backdrop,
                backdropLoading = backdropLoading,
                onRefreshBackdrop = { backdropReload++ },
                onChange = { state.draft = it }
            )

            state.problem?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.changed) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(enabled = !saving, onClick = { state.draft = state.loaded }) { Text("Отменить изменения записи") }
            }
        }
    }
}

@Composable
private fun RecordingForm(
    settings: RecordingSettings,
    backdrop: ImageBitmap?,
    backdropLoading: Boolean,
    onRefreshBackdrop: () -> Unit,
    onChange: (RecordingSettings) -> Unit
) {
    // Запись
    Text("Запись на SD-карту", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        RecordMode.entries.filter { it in settings.supportedModes }.forEach { mode ->
            FilterChip(
                selected = settings.mode == mode,
                onClick = {
                    onChange(
                        when {
                            mode == settings.mode -> settings
                            // К расписанию переходим от текущего режима: его можно править дальше
                            mode == RecordMode.CUSTOM -> settings.copy(
                                mode = mode,
                                schedule = settings.schedule?.takeIf { settings.mode == RecordMode.CUSTOM }
                                    ?: WeekSchedule.forMode(settings.mode.takeIf { it != RecordMode.CUSTOM } ?: RecordMode.OFF)
                            )
                            // Запись по движению без детекции не работает — включаем её вместе с режимом
                            mode == RecordMode.MOTION && settings.motion != null ->
                                settings.copy(mode = mode, motion = settings.motion.copy(enabled = true))
                            else -> settings.copy(mode = mode)
                        }
                    )
                },
                label = { Text(mode.title) }
            )
        }
    }
    val needsMotion = settings.mode == RecordMode.MOTION ||
        (settings.mode == RecordMode.CUSTOM && settings.schedule?.days?.any { day -> day.any { it.kind == RecordKind.MOTION } } == true)
    if (needsMotion && settings.motion?.enabled == false) {
        Text(
            "Запись по движению требует детекции движения: при сохранении камера её включит.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (settings.mode == RecordMode.CUSTOM && settings.schedule != null) {
        Spacer(modifier = Modifier.height(8.dp))
        ScheduleEditor(
            schedule = settings.schedule,
            maxSlots = settings.scheduleSlotsPerDay,
            kinds = settings.scheduleKinds,
            onChange = { onChange(settings.copy(schedule = it)) }
        )
    }

    settings.preRecordSec?.let { seconds ->
        if (settings.preRecordMaxSec > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Предзапись: ${if (seconds == 0) "выкл." else "$seconds с"}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = seconds.toFloat(),
                onValueChange = { onChange(settings.copy(preRecordSec = it.roundToInt())) },
                valueRange = 0f..settings.preRecordMaxSec.toFloat(),
                steps = (settings.preRecordMaxSec - 1).coerceAtLeast(0)
            )
        }
    }
    settings.overwrite?.let { overwrite ->
        SwitchRow(
            title = "Перезаписывать старые записи",
            subtitle = "Когда карта заполнена. Если выключено, запись останавливается.",
            checked = overwrite,
            onCheckedChange = { onChange(settings.copy(overwrite = it)) }
        )
    }

    // Детекция движения
    settings.motion?.let { motion ->
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        SwitchRow(
            title = "Детекция движения",
            subtitle = null,
            checked = motion.enabled,
            onCheckedChange = { onChange(settings.copy(motion = motion.copy(enabled = it))) }
        )
        if (motion.enabled) {
            Text(
                "Чувствительность: ${motion.sensitivity} из ${motion.sensitivityMax}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = motion.sensitivity.toFloat(),
                onValueChange = { onChange(settings.copy(motion = motion.copy(sensitivity = it.roundToInt()))) },
                valueRange = 1f..motion.sensitivityMax.toFloat(),
                steps = if (motion.sensitivityMax <= 10) motion.sensitivityMax - 2 else 0
            )
            motion.zones?.let { zones ->
                Text("Зоны движения: коснитесь ячейки; чтобы закрасить или стереть несколько, задержите палец и ведите", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                MotionZonesEditor(
                    zones,
                    backdrop = backdrop,
                    backdropLoading = backdropLoading,
                    onRefreshBackdrop = onRefreshBackdrop,
                    onChange = { onChange(settings.copy(motion = motion.copy(zones = it))) }
                )
            }
        }
    }
    settings.person?.let { person ->
        SwitchRow(
            title = "Детекция людей",
            subtitle = "Камера отмечает события «человек в кадре».",
            checked = person,
            onCheckedChange = { onChange(settings.copy(person = it)) }
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------- Расписание ----------------

@Composable
private fun ScheduleEditor(schedule: WeekSchedule, maxSlots: Int, kinds: Set<RecordKind>, onChange: (WeekSchedule) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        schedule.days.forEachIndexed { dayIndex, slots ->
            fun setDay(newSlots: List<RecordSlot>) =
                onChange(WeekSchedule(schedule.days.mapIndexed { i, old -> if (i == dayIndex) newSlots else old }))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(DAY_NAMES[dayIndex], style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp))
                    if (slots.isEmpty()) {
                        Text("не записывать", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TooltipIconButton(
                        tooltip = "Скопировать на все дни",
                        onClick = { onChange(WeekSchedule(List(7) { slots })) }
                    ) { Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать на все дни") }
                    TooltipIconButton(
                        tooltip = "Добавить интервал",
                        enabled = slots.size < maxSlots,
                        onClick = {
                            val start = slots.maxOfOrNull { it.endMinute }?.takeIf { it < WeekSchedule.MINUTES_PER_DAY } ?: 0
                            setDay(slots + RecordSlot(start, WeekSchedule.MINUTES_PER_DAY, kinds.firstOrNull() ?: RecordKind.ALWAYS))
                        }
                    ) { Icon(Icons.Default.Add, contentDescription = "Добавить интервал") }
                }
                slots.forEachIndexed { slotIndex, slot ->
                    fun setSlot(new: RecordSlot) = setDay(slots.mapIndexed { i, old -> if (i == slotIndex) new else old })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            pickTime(context, slot.startMinute, isEnd = false) { setSlot(slot.copy(startMinute = it)) }
                        }) { Text(formatMinutes(slot.startMinute)) }
                        Text("–")
                        OutlinedButton(onClick = {
                            pickTime(context, slot.endMinute, isEnd = true) { setSlot(slot.copy(endMinute = it)) }
                        }) { Text(formatMinutes(slot.endMinute)) }
                        if (kinds.size > 1) {
                            FilterChip(
                                selected = true,
                                onClick = {
                                    val order = kinds.toList()
                                    setSlot(slot.copy(kind = order[(order.indexOf(slot.kind) + 1) % order.size]))
                                },
                                label = { Text(slot.kind.title) }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TooltipIconButton(tooltip = "Удалить интервал", onClick = { setDay(slots.filterIndexed { i, _ -> i != slotIndex }) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить интервал", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** Выбор времени; конец интервала 00:00 означает конец суток (24:00) */
private fun pickTime(context: android.content.Context, initialMinutes: Int, isEnd: Boolean, onPicked: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute ->
            val minutes = hour * 60 + minute
            onPicked(if (isEnd && minutes == 0) WeekSchedule.MINUTES_PER_DAY else minutes)
        },
        (initialMinutes / 60) % 24, initialMinutes % 60, true
    ).show()
}

/** Что не так с расписанием (null — всё в порядке): порядок времени, пересечения, число интервалов */
internal fun scheduleProblem(schedule: WeekSchedule, maxSlots: Int): String? {
    schedule.days.forEachIndexed { index, slots ->
        val day = DAY_NAMES[index]
        if (slots.size > maxSlots) return "$day: камера принимает не больше $maxSlots интервалов в сутки."
        if (slots.any { it.startMinute >= it.endMinute }) return "$day: начало интервала должно быть раньше его конца."
        val sorted = slots.sortedBy { it.startMinute }
        for (i in 1 until sorted.size) {
            if (sorted[i].startMinute < sorted[i - 1].endMinute) return "$day: интервалы пересекаются."
        }
    }
    return null
}

// ---------------- Зоны движения ----------------

/**
 * Сетка зон: касание переключает ячейку, а после долгого нажатия ячейки закрашиваются или стираются проведением
 * (обычное проведение прокручивает окно). Под сеткой — кадр камеры [backdrop]: выбранные ячейки видны как есть,
 * остальные затемнены, так что видно, какую часть картинки выбрал пользователь.
 */
@Composable
private fun MotionZonesEditor(
    zones: MotionZones,
    backdrop: ImageBitmap?,
    backdropLoading: Boolean,
    onRefreshBackdrop: () -> Unit,
    onChange: (MotionZones) -> Unit
) {
    val currentZones by rememberUpdatedState(zones)
    val currentOnChange by rememberUpdatedState(onChange)
    val hasBackdrop = backdrop != null
    val selectedColor = MaterialTheme.colorScheme.primary.copy(alpha = if (hasBackdrop) 0.18f else 0.5f)
    val unselectedColor = Color.Black.copy(alpha = 0.6f)
    val grid = (if (hasBackdrop) Color.White else MaterialTheme.colorScheme.outline).copy(alpha = 0.35f)

    fun cellAt(position: Offset, size: IntSize): Int? {
        if (size.width == 0 || size.height == 0) return null
        val column = (position.x / size.width * currentZones.columns).toInt()
        val row = (position.y / size.height * currentZones.rows).toInt()
        if (column !in 0 until currentZones.columns || row !in 0 until currentZones.rows) return null
        return row * currentZones.columns + column
    }

    fun paint(cell: Int, value: Boolean) {
        val cells = currentZones.cells
        if ((cell in cells) == value) return
        currentOnChange(currentZones.copy(cells = if (value) cells + cell else cells - cell))
    }

    // Что делает текущее проведение: закрашивает или стирает — решает первая затронутая ячейка
    var paintValue by remember { mutableStateOf(true) }
    // Сетка растягивается на весь кадр, поэтому соотношение сторон берём у кадра (у камер оно бывает 4:3 и 16:9)
    val aspect = backdrop?.let { it.width.toFloat() / it.height }?.coerceIn(1f, 2.4f) ?: (16f / 9f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
    ) {
        backdrop?.let {
            Image(
                bitmap = it,
                contentDescription = "Кадр камеры",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        cellAt(position, size)?.let { paint(it, it !in currentZones.cells) }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            cellAt(position, size)?.let {
                                paintValue = it !in currentZones.cells
                                paint(it, paintValue)
                            }
                        },
                        onDrag = { change, _ -> cellAt(change.position, size)?.let { paint(it, paintValue) } }
                    )
                }
        ) {
            val cellWidth = size.width / zones.columns
            val cellHeight = size.height / zones.rows
            // Без кадра затемнять нечего: закрашиваются только выбранные ячейки; с кадром — наоборот, затемняются невыбранные
            for (cell in 0 until zones.columns * zones.rows) {
                val selected = cell in zones.cells
                if (!hasBackdrop && !selected) continue
                drawRect(
                    color = if (selected) selectedColor else unselectedColor,
                    topLeft = Offset((cell % zones.columns) * cellWidth, (cell / zones.columns) * cellHeight),
                    size = Size(cellWidth, cellHeight)
                )
            }
            for (column in 1 until zones.columns) {
                drawLine(grid, Offset(column * cellWidth, 0f), Offset(column * cellWidth, size.height), strokeWidth = 1f)
            }
            for (row in 1 until zones.rows) {
                drawLine(grid, Offset(0f, row * cellHeight), Offset(size.width, row * cellHeight), strokeWidth = 1f)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = {
            onChange(zones.copy(cells = (0 until zones.columns * zones.rows).toSet()))
        }) { Text("Весь кадр") }
        OutlinedButton(onClick = { onChange(zones.copy(cells = emptySet())) }) { Text("Очистить") }
        OutlinedButton(onClick = onRefreshBackdrop, enabled = !backdropLoading) {
            if (backdropLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("Обновить кадр")
        }
    }
    if (backdrop == null && !backdropLoading) {
        Text(
            "Кадр с камеры получить не удалось: зоны выбираются вслепую.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
