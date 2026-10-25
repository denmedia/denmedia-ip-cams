package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.IpCamera

/**
 * Разбор и сборка XML записи камер Hikvision по ISAPI (проверено на DS-I114W, прошивка V5.4.81). Работает с текстом,
 * без android-классов, поэтому проверяется юнит-тестами. Что установлено на живой камере:
 * - `/ISAPI/ContentMgmt/record/tracks/101`: `TrackSchedule/ScheduleBlock` — интервалы `ScheduleAction` (по 8 на день;
 *   неиспользуемые камера при чтении не возвращает). Интервал: `DayOfWeek` и `TimeOfDay` начала и конца (конец суток —
 *   «24:00:00»), `ActionRecordingMode`: CMR — постоянно, MOTION — по движению (остальные режимы приложение не
 *   знает и не трогает). Пустое расписание — камера ничего не пишет. `LoopEnable` — перезапись старых записей,
 *   `CustomExtension/PreRecordTimeSeconds` — предзапись.
 * - `/ISAPI/System/Video/inputs/channels/1/motionDetection`: `enabled`, `sensitivityLevel` (0..100, шаг 20), сетка зон 22×18.
 * - `/ISAPI/Event/triggers/VMD-1`: чтобы движение вызывало запись, в `EventTriggerNotificationList` должно быть
 *   уведомление `record` (камера принимает его вместе с `videoInputID`; при чтении `id` не возвращает).
 */
internal object HikvisionRecordingCodec {

    const val SLOTS_PER_DAY = 8
    const val SENSITIVITY_MAX = 5
    const val PRE_RECORD_MAX_SEC = 30
    private const val SENSITIVITY_STEP = 20
    private const val MODE_ALWAYS = "CMR"
    private const val MODE_MOTION = "MOTION"

    /** Дни недели в порядке модели приложения: 0 — понедельник */
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private val ACTION = Regex("""<ScheduleAction>(.*?)</ScheduleAction>""", RegexOption.DOT_MATCHES_ALL)
    private val START = Regex("""<ScheduleActionStartTime>(.*?)</ScheduleActionStartTime>""", RegexOption.DOT_MATCHES_ALL)
    private val END = Regex("""<ScheduleActionEndTime>(.*?)</ScheduleActionEndTime>""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_TAIL = Regex("""(<ScheduleBlockType>[^<]*</ScheduleBlockType>).*?(</ScheduleBlock>)""", RegexOption.DOT_MATCHES_ALL)
    private val NOTIFICATION = Regex("""<EventTriggerNotification>(.*?)</EventTriggerNotification>""", RegexOption.DOT_MATCHES_ALL)

    fun tag(xml: String, name: String): String? =
        Regex("""<$name(?:\s[^>]*)?>\s*([^<]*?)\s*</$name>""").find(xml)?.groupValues?.get(1)

    /** XML с заменённым первым значением тега [name]; null — такого тега нет */
    fun withTag(xml: String, name: String, value: String): String? {
        val match = Regex("""(<$name(?:\s[^>]*)?>)\s*[^<]*?\s*(</$name>)""").find(xml) ?: return null
        return xml.replaceRange(match.range, match.groupValues[1] + value + match.groupValues[2])
    }

    /** «HH:MM:SS» → минуты от начала суток (секунды отбрасываются); null — не время */
    private fun minutes(time: String?): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?$""").matchEntire(time.orEmpty()) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun clock(minute: Int): String = "%02d:%02d:00".format(minute / 60, minute % 60)

    /**
     * Недельное расписание из ответа камеры; null — формат не тот: незнакомый режим записи, интервал через
     * полночь или время не разобралось (такое расписание приложение не меняет, чтобы ничего не потерять).
     */
    fun parseSchedule(trackXml: String): WeekSchedule? {
        val days = MutableList(DAYS.size) { mutableListOf<RecordSlot>() }
        for (action in ACTION.findAll(trackXml)) {
            val body = action.groupValues[1]
            val start = START.find(body)?.groupValues?.get(1) ?: return null
            val end = END.find(body)?.groupValues?.get(1) ?: return null
            val startDay = DAYS.indexOf(tag(start, "DayOfWeek"))
            if (startDay < 0 || tag(end, "DayOfWeek") != DAYS[startDay]) return null
            val from = minutes(tag(start, "TimeOfDay")) ?: return null
            val to = minutes(tag(end, "TimeOfDay")) ?: return null
            if (to <= from) continue // пустой интервал: камера отдаёт такие как 00:00–00:00
            val kind = when (tag(body, "ActionRecordingMode")) {
                MODE_ALWAYS -> RecordKind.ALWAYS
                MODE_MOTION -> RecordKind.MOTION
                else -> return null
            }
            days[startDay] += RecordSlot(from, to.coerceAtMost(WeekSchedule.MINUTES_PER_DAY), kind)
        }
        return WeekSchedule(days.map { day -> day.sortedWith(compareBy({ it.startMinute }, { it.endMinute })) })
    }

    /** Интервалы расписания в виде XML ScheduleAction (по порядку дней; номера слотов — с 1 в каждом дне) */
    fun scheduleActions(schedule: WeekSchedule): String = buildString {
        for ((index, slots) in schedule.days.withIndex()) {
            for ((slot, interval) in slots.sortedWith(compareBy({ it.startMinute }, { it.endMinute })).withIndex()) {
                val mode = if (interval.kind == RecordKind.MOTION) MODE_MOTION else MODE_ALWAYS
                append("<ScheduleAction><id>${slot + 1}</id>")
                append("<ScheduleActionStartTime><DayOfWeek>${DAYS[index]}</DayOfWeek><TimeOfDay>${clock(interval.startMinute)}</TimeOfDay></ScheduleActionStartTime>")
                append("<ScheduleActionEndTime><DayOfWeek>${DAYS[index]}</DayOfWeek><TimeOfDay>${clock(interval.endMinute)}</TimeOfDay></ScheduleActionEndTime>")
                append("<ScheduleDSTEnable>false</ScheduleDSTEnable><Description>nothing</Description>")
                append("<Actions><Record>true</Record><ActionRecordingMode>$mode</ActionRecordingMode></Actions></ScheduleAction>")
            }
        }
    }

    /** Ответ камеры о дорожке с заменённым расписанием; null — в ответе нет блока расписания */
    fun withSchedule(trackXml: String, schedule: WeekSchedule): String? {
        val match = BLOCK_TAIL.find(trackXml) ?: return null
        return trackXml.replaceRange(match.range, match.groupValues[1] + scheduleActions(schedule) + match.groupValues[2])
    }

    /** Чувствительность камеры (0..100, шаг 20) в шкалу приложения 1..5 и обратно */
    fun sensitivityOf(level: Int): Int = ((level + SENSITIVITY_STEP / 2) / SENSITIVITY_STEP).coerceIn(1, SENSITIVITY_MAX)

    fun levelOf(sensitivity: Int): Int = sensitivity.coerceIn(1, SENSITIVITY_MAX) * SENSITIVITY_STEP

    /** Есть ли среди уведомлений события запись видео */
    fun linksToRecording(triggerXml: String): Boolean =
        NOTIFICATION.findAll(triggerXml).any { tag(it.groupValues[1], "notificationMethod") == "record" }

    /**
     * Триггер события с добавленным уведомлением «записывать видео»; уже имеющиеся уведомления сохраняются (камера при
     * чтении не возвращает их `id`, а при записи требует — он берётся из способа уведомления). null — формат не тот.
     */
    fun withRecordingLink(triggerXml: String): String? {
        val list = Regex("""<EventTriggerNotificationList>(.*?)</EventTriggerNotificationList>""", RegexOption.DOT_MATCHES_ALL).find(triggerXml)
            ?: return null
        val kept = mutableListOf<String>()
        for (match in NOTIFICATION.findAll(list.groupValues[1])) {
            val body = match.groupValues[1]
            val method = tag(body, "notificationMethod") ?: return null
            val recurrence = tag(body, "notificationRecurrence")?.let { "<notificationRecurrence>$it</notificationRecurrence>" }.orEmpty()
            val input = tag(body, "videoInputID")?.let { "<videoInputID>$it</videoInputID>" }.orEmpty()
            kept += "<EventTriggerNotification><id>${tag(body, "id") ?: method}</id><notificationMethod>$method</notificationMethod>$input$recurrence</EventTriggerNotification>"
        }
        val record = "<EventTriggerNotification><id>record-1</id><notificationMethod>record</notificationMethod>" +
            "<notificationRecurrence>beginning</notificationRecurrence><videoInputID>1</videoInputID></EventTriggerNotification>"
        return triggerXml.replaceRange(
            list.range,
            "<EventTriggerNotificationList>${(kept + record).joinToString("")}</EventTriggerNotificationList>"
        )
    }
}

/**
 * Запись на SD-карту камеры Hikvision: режим и недельное расписание (дорожка 101), предзапись, перезапись старых
 * записей, детекция движения. Каждый XML читается и пишется целиком, как его вернула камера, меняются только нужные
 * поля. Зоны детекции не меняются (порядок ячеек сетки не проверен движением в кадре); детекции людей у камеры нет.
 */
internal class HikvisionRecordingDriver(private val camera: IpCamera) : RecordingDriver {

    private fun url(path: String) = "http://${camera.ipAddress}:${camera.effectiveHttpPort}$path"

    private fun get(path: String): String? {
        val response = CameraHttp.request(url(path), user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS)
        if (!response.isSuccess) Log.w(TAG, "${camera.name}: GET $path → ${response.code}")
        return response.body.takeIf { response.isSuccess }
    }

    private fun put(path: String, xml: String): Boolean {
        val response = CameraHttp.request(
            url(path), method = "PUT", body = xml, contentType = "application/xml",
            user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS
        )
        val accepted = response.isSuccess && response.body.contains("<statusString>OK</statusString>")
        if (!accepted) Log.w(TAG, "${camera.name}: PUT $path → ${response.code} ${response.body.take(160)}")
        return accepted
    }

    override suspend fun read(): RecordingSettings? {
        val track = get(TRACK) ?: return null
        val schedule = HikvisionRecordingCodec.parseSchedule(track) ?: return null
        val codec = HikvisionRecordingCodec
        val motion = get(MOTION)?.let { motionOf(it) }
        val kinds = if (motion != null) setOf(RecordKind.ALWAYS, RecordKind.MOTION) else setOf(RecordKind.ALWAYS)
        val modes = if (motion != null) {
            setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.MOTION, RecordMode.CUSTOM)
        } else {
            setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.CUSTOM)
        }
        return RecordingSettings(
            mode = schedule.deriveMode(),
            supportedModes = modes,
            schedule = schedule,
            scheduleSlotsPerDay = codec.SLOTS_PER_DAY,
            scheduleKinds = kinds,
            preRecordSec = codec.tag(track, "PreRecordTimeSeconds")?.toIntOrNull(),
            preRecordMaxSec = codec.PRE_RECORD_MAX_SEC,
            overwrite = codec.tag(track, "LoopEnable")?.toBooleanStrictOrNull(),
            motion = motion,
            person = null
        )
    }

    private fun motionOf(xml: String): MotionSettings? {
        val codec = HikvisionRecordingCodec
        val enabled = codec.tag(xml, "enabled")?.toBooleanStrictOrNull() ?: return null
        val level = codec.tag(xml, "sensitivityLevel")?.toIntOrNull() ?: return null
        return MotionSettings(enabled = enabled, sensitivity = codec.sensitivityOf(level), sensitivityMax = codec.SENSITIVITY_MAX, zones = null)
    }

    override suspend fun write(from: RecordingSettings, to: RecordingSettings): Boolean {
        val codec = HikvisionRecordingCodec
        val wantsMotion = to.mode == RecordMode.MOTION ||
            (to.mode == RecordMode.CUSTOM && to.schedule?.days?.any { day -> day.any { it.kind == RecordKind.MOTION } } == true)
        if (wantsMotion && to.motion == null && from.motion == null) return false

        // Сначала детекция движения и связь с записью: режим «по движению» не должен остаться без работающей детекции
        if (!writeMotion(from, to, wantsMotion)) return false
        return writeTrack(from, to)
    }

    /** Режим, расписание, предзапись и перезапись (дорожка 101); ничего не пишет, если менять нечего */
    private fun writeTrack(from: RecordingSettings, to: RecordingSettings): Boolean {
        val codec = HikvisionRecordingCodec
        val schedule = if (to.mode == RecordMode.CUSTOM) to.schedule else WeekSchedule.forMode(to.mode)
        val scheduleChanged = schedule != null && (to.mode != from.mode || schedule != from.schedule)
        val preRecord = to.preRecordSec?.takeIf { it != from.preRecordSec }?.coerceIn(0, codec.PRE_RECORD_MAX_SEC)
        val overwrite = to.overwrite?.takeIf { it != from.overwrite }
        if (!scheduleChanged && preRecord == null && overwrite == null) return true

        var track = get(TRACK) ?: return false
        if (scheduleChanged && schedule != null) track = codec.withSchedule(track, schedule) ?: return false
        if (preRecord != null) track = codec.withTag(track, "PreRecordTimeSeconds", preRecord.toString()) ?: return false
        if (overwrite != null) track = codec.withTag(track, "LoopEnable", overwrite.toString()) ?: return false
        // Расписание дорожки должно быть включено, иначе записи не будет при любых интервалах
        if (to.mode != RecordMode.OFF) track = codec.withTag(track, "enableSchedule", "true") ?: track
        return put(TRACK, track)
    }

    /** Детекция движения: включение и чувствительность; для записи по движению — ещё и уведомление «записывать видео» */
    private fun writeMotion(from: RecordingSettings, to: RecordingSettings, wantsMotion: Boolean): Boolean {
        val codec = HikvisionRecordingCodec
        val wanted = to.motion
        if (wanted == null && !wantsMotion) return true

        var changed = false
        var config = get(MOTION) ?: return false
        val enable = wantsMotion || (wanted != null && wanted.enabled != from.motion?.enabled)
        if (enable) {
            val value = (wantsMotion || wanted?.enabled == true).toString()
            if (codec.tag(config, "enabled") != value) {
                config = codec.withTag(config, "enabled", value) ?: return false
                changed = true
            }
        }
        if (wanted != null && wanted.sensitivity != from.motion?.sensitivity) {
            config = codec.withTag(config, "sensitivityLevel", codec.levelOf(wanted.sensitivity).toString()) ?: return false
            changed = true
        }
        if (changed && !put(MOTION, config)) return false

        if (wantsMotion) {
            val trigger = get(MOTION_TRIGGER) ?: return false
            if (!codec.linksToRecording(trigger)) {
                val linked = codec.withRecordingLink(trigger) ?: return false
                if (!put(MOTION_TRIGGER, linked)) return false
            }
        }
        return true
    }

    override fun expectedAfterWrite(to: RecordingSettings): RecordingSettings {
        val codec = HikvisionRecordingCodec
        val wantsMotion = to.mode == RecordMode.MOTION ||
            (to.mode == RecordMode.CUSTOM && to.schedule?.days?.any { day -> day.any { it.kind == RecordKind.MOTION } } == true)
        val motion = to.motion?.let { m ->
            m.copy(enabled = m.enabled || wantsMotion, sensitivity = codec.sensitivityOf(codec.levelOf(m.sensitivity)))
        }
        return to.copy(motion = motion, preRecordSec = to.preRecordSec?.coerceIn(0, codec.PRE_RECORD_MAX_SEC))
    }

    private companion object {
        const val TAG = "HikvisionRecording"
        const val TIMEOUT_MS = 8_000
        const val TRACK = "/ISAPI/ContentMgmt/record/tracks/101"
        const val MOTION = "/ISAPI/System/Video/inputs/channels/1/motionDetection"
        const val MOTION_TRIGGER = "/ISAPI/Event/triggers/VMD-1"
    }
}
