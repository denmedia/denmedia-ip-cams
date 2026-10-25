package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule
import java.util.Locale
import java.util.TimeZone

/**
 * Установка времени и часового пояса камеры по часам планшета: ONVIF (SetSystemDateAndTime), а если камера
 * не отвечает по ONVIF, — HTTP API Dahua (только время). Блокирующие вызовы выполняются на Dispatchers.IO.
 */
object CameraTimeSync {

    private const val TAG = "CameraTimeSync"

    /** Допустимое расхождение часов камеры и планшета после установки, с */
    private const val TOLERANCE_SEC = 5L

    /** Допустимое расхождение эха часового пояса от запрошенного, с (минуты округления и т. п.) */
    private const val TIMEZONE_TOLERANCE_SEC = 60

    /** Сколько раз пробовать установить пояс, прежде чем сдаться на вариант «только время, без пояса» */
    private const val ZONE_ATTEMPTS = 2

    /** [message] — что показать пользователю (при успехе — что именно установлено) */
    data class Result(val ok: Boolean, val message: String)

    /**
     * Установка времени возможна для камер, у которых задан ONVIF-порт или есть HTTP API Dahua.
     * Примет ли камера команду, зависит от прав учётной записи — это видно только по результату [sync].
     */
    fun isAvailable(camera: IpCamera): Boolean =
        !camera.ipAddress.contains("://") && (camera.onvifPort > 0 || camera.hasDahuaApi)

    suspend fun sync(
        camera: IpCamera,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Result = withContext(Dispatchers.IO) {
        if (!isAvailable(camera)) {
            return@withContext Result(false, "Для этой камеры установка времени недоступна: нужен ONVIF-порт или HTTP API Dahua.")
        }
        var onvifFailure: String? = null
        if (camera.onvifPort > 0) {
            val onvif = syncOnvif(camera, now, zone)
            if (onvif.ok || !camera.hasDahuaApi) return@withContext onvif
            onvifFailure = onvif.message
        }
        val dahua = syncDahua(camera, now, zone)
        if (dahua.ok || onvifFailure == null) dahua else Result(false, "$onvifFailure\n${dahua.message}")
    }

    private fun syncOnvif(camera: IpCamera, now: Instant, zone: ZoneId): Result {
        val port = camera.onvifPort
        val deviceUrl = "http://${camera.ipAddress}:$port/onvif/device_service"
        val cameraUtcMs = OnvifClient.getCameraUtcMs(deviceUrl, camera.username, camera.password)
            ?: return Result(false, "Камера не ответила по ONVIF (порт $port). Проверьте порт, логин и пароль.")
        val offsetMs = cameraUtcMs - now.toEpochMilli()
        val daylight = zone.rules.isDaylightSavings(now)
        val intendedOffsetSec = zone.rules.getOffset(now).totalSeconds

        // Камера, не разобравшая строку часового пояса, отказывает во всей команде — тогда ставим одно время.
        // Отдельно от этого часть камер отвечает «ОК», но сам пояс не применяет или теряет его (разово —
        // проверено живьём на NVT): поэтому команду с
        // поясом повторяем и проверяем эхом GetSystemDateAndTime, прежде чем на неё полагаться
        val tz = PosixTimeZone.of(zone, now)
        var zoneAccepted = false
        var tzConfirmed = false
        var attempt = 0
        while (attempt < ZONE_ATTEMPTS && !tzConfirmed) {
            if (OnvifClient.setSystemDateAndTime(deviceUrl, camera.username, camera.password, offsetMs, now, tz, daylight)) {
                zoneAccepted = true
                val echoedOffset = OnvifClient.getCameraTime(deviceUrl, camera.username, camera.password)
                    ?.tzRaw?.let { PosixTimeZone.parseActiveOffsetSeconds(it, daylight) }
                tzConfirmed = echoedOffset != null &&
                    kotlin.math.abs(echoedOffset - intendedOffsetSec) <= TIMEZONE_TOLERANCE_SEC
            }
            attempt++
            if (!tzConfirmed && attempt < ZONE_ATTEMPTS) Thread.sleep(500)
        }

        val accepted: Boolean
        val withZone: Boolean
        if (tzConfirmed) {
            accepted = true
            withZone = true
        } else if (zoneAccepted) {
            // Камера приняла команду с поясом, но мы не смогли убедиться эхом, что он применился. Повторная
            // команда без пояса ничего не исправит (на этой прошивке зонная настройка ею не трогается)
            // — честнее сообщить как неудачу: MainViewModel сам повторит попытку позже (см. бэкофф
            // в autoSyncCameraTime), чем показать ложный успех при фактически неизвестном часовом поясе
            return Result(
                false,
                "Камера приняла команду с часовым поясом, но не подтвердила его эхом — повторим попытку позже."
            )
        } else {
            // Команда с поясом отклонена целиком (не разобрала формат и т. п.) — хотя бы время
            accepted = OnvifClient.setSystemDateAndTime(deviceUrl, camera.username, camera.password, offsetMs, now, null, daylight)
            withZone = false
        }
        if (!accepted) {
            return Result(
                false,
                "Камера не приняла команду установки времени. Нужна учётная запись администратора, " +
                    "либо камера не поддерживает смену времени по ONVIF."
            )
        }

        // Часть камер отвечает «ОК», но время не меняет (например, держит его по NTP)
        val after = OnvifClient.getCameraUtcMs(deviceUrl, camera.username, camera.password)
        if (after != null && kotlin.math.abs(after - System.currentTimeMillis()) > TOLERANCE_SEC * 1000) {
            Log.w(TAG, "${camera.name}: ONVIF accepted the time but the clock differs by ${(after - System.currentTimeMillis()) / 1000} s")
            return Result(false, "Камера приняла команду, но время не изменилось (возможно, оно задаётся по NTP на самой камере).")
        }
        return Result(
            true,
            if (withZone) "Время и часовой пояс установлены (${describe(now, zone)})."
            else "Время установлено (${describe(now, zone)}); часовой пояс камера не приняла."
        )
    }

    /** HTTP API Dahua умеет только локальное время камеры — ставим местное время планшета */
    private fun syncDahua(camera: IpCamera, now: Instant, zone: ZoneId): Result {
        val local = LocalDateTime.ofInstant(now, zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))
        val response = CameraHttp.request(
            "${DahuaApi.baseUrl(camera)}/cgi-bin/global.cgi?action=setCurrentTime&time=${local.replace(" ", "%20")}",
            user = camera.username,
            pass = camera.password,
            timeoutMs = 5_000
        )
        return when {
            response.isSuccess && response.body.trimStart().startsWith("OK") ->
                Result(true, "Время установлено через HTTP API Dahua (${describe(now, zone)}); часовой пояс не менялся.")
            response.code == 0 -> Result(false, "Камера не ответила по HTTP (порт ${camera.effectiveHttpPort}).")
            response.code == 401 -> Result(false, "Камера отклонила логин или пароль.")
            else -> Result(false, "Камера не приняла команду установки времени (HTTP ${response.code}).")
        }
    }

    private fun describe(now: Instant, zone: ZoneId): String {
        val local = LocalDateTime.ofInstant(now, zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT))
        return "$local, ${zone.id}"
    }
}

/**
 * Часовой пояс в формате POSIX TZ, который понимает ONVIF: «EET-2EEST,M3.5.0/3,M10.5.0/4».
 * Правила перехода на летнее время берутся из правил зоны Java; если их не удаётся выразить в этом формате,
 * возвращается поясное время без перехода.
 */
internal object PosixTimeZone {

    /**
     * Обратное к [of]: смещение (восток положительный), которое означает строка [tz], — чтобы проверить,
     * что камера в эхе GetSystemDateAndTime вернула именно тот пояс, что мы ей послали (другое имя пояса
     * при том же числе — не страшно, многие камеры подставляют своё имя из таблицы; другое число — страшно).
     * [daylight] выбирает летнее смещение, если в строке есть и оно, и обычное (само летнее смещение в
     * строке может быть без явного числа — тогда по правилам POSIX оно на час восточнее обычного, см. [of]).
     * Null — строку не разобрать.
     */
    fun parseActiveOffsetSeconds(tz: String, daylight: Boolean): Int? {
        val spec = tz.substringBefore(',')
        var i = 0
        fun readName(): String {
            val start = i
            while (i < spec.length && spec[i].isLetter()) i++
            return spec.substring(start, i)
        }
        // null — после имени нет числа вовсе (не отсутствие строки, а именно «смещение не указано»)
        fun readOffset(): Int? {
            val start = i
            if (i < spec.length && (spec[i] == '+' || spec[i] == '-')) i++
            val digitsStart = i
            while (i < spec.length && spec[i].isDigit()) i++
            if (i == digitsStart) {
                i = start
                return null
            }
            val hours = spec.substring(start, i).toInt()
            var minutes = 0
            if (i < spec.length && spec[i] == ':') {
                i++
                val minutesStart = i
                while (i < spec.length && spec[i].isDigit()) i++
                minutes = spec.substring(minutesStart, i).toIntOrNull() ?: 0
            }
            val posixSeconds = hours * 3600 + (if (hours < 0) -minutes else minutes) * 60
            // POSIX: местное время = UTC − смещение, поэтому обычное (восточное положительное) — это минус его
            return -posixSeconds
        }

        if (readName().isEmpty()) return null
        val standard = readOffset() ?: return null
        if (!daylight) return standard
        if (readName().isEmpty()) return standard // летней части нет вовсе — переход не предусмотрен
        return readOffset() ?: (standard + 3600)
    }

    fun of(zone: ZoneId, at: Instant): String {
        val rules = zone.rules
        val timeZone = TimeZone.getTimeZone(zone)
        val standard = rules.getStandardOffset(at).totalSeconds
        val transitions = rules.transitionRules
        val toSummer = transitions.firstOrNull { it.offsetAfter.totalSeconds > it.offsetBefore.totalSeconds }
        val toStandard = transitions.firstOrNull { it.offsetAfter.totalSeconds < it.offsetBefore.totalSeconds }
        val start = toSummer?.let { rule(it) }
        val end = toStandard?.let { rule(it) }

        if (toSummer == null || start == null || end == null) {
            return name(timeZone, false, "UTC") + offset(rules.getOffset(at).totalSeconds)
        }
        val summerShift = toSummer.offsetAfter.totalSeconds - standard
        return name(timeZone, false, "STD") + offset(standard) +
            name(timeZone, true, "DST") +
            // Без смещения летнего времени подразумевается +1 час
            (if (summerShift == 3600) "" else offset(standard + summerShift)) +
            ",$start,$end"
    }

    /** Имя из часовых поясов Java, если оно годится для POSIX (только буквы), иначе [fallback] */
    private fun name(timeZone: TimeZone, daylight: Boolean, fallback: String): String =
        timeZone.getDisplayName(daylight, TimeZone.SHORT, Locale.ENGLISH)
            .takeIf { it.matches(Regex("[A-Za-z]{3,6}")) } ?: fallback

    /** В POSIX смещение отсчитывается от местного времени к UTC: для UTC+2 пишется «-2» */
    private fun offset(totalSeconds: Int): String {
        val posix = -totalSeconds
        val abs = kotlin.math.abs(posix)
        val minutes = abs % 3600 / 60
        return (if (posix < 0) "-" else "") + abs / 3600 + (if (minutes > 0) ":%02d".format(minutes) else "")
    }

    /** «Mмесяц.неделя.день[/время]»; null — правило не выражается (переход в конкретную дату и т. п.) */
    private fun rule(rule: ZoneOffsetTransitionRule): String? {
        val dayOfWeek = rule.dayOfWeek ?: return null
        val indicator = rule.dayOfMonthIndicator
        val week = when {
            // «Последнее воскресенье» в базе зон записано либо как -1, либо как «воскресенье начиная с 25-го»
            indicator == -1 || indicator > 0 && indicator + 6 >= rule.month.maxLength() -> 5
            indicator > 0 && (indicator - 1) % 7 == 0 -> (indicator - 1) / 7 + 1
            else -> return null
        }
        // Время перехода задаётся по местным часам до перехода
        val definition = when (rule.timeDefinition) {
            ZoneOffsetTransitionRule.TimeDefinition.UTC -> rule.offsetBefore.totalSeconds
            ZoneOffsetTransitionRule.TimeDefinition.STANDARD -> rule.offsetBefore.totalSeconds - rule.standardOffset.totalSeconds
            ZoneOffsetTransitionRule.TimeDefinition.WALL -> 0
        }
        val seconds = rule.localTime.toSecondOfDay() + (if (rule.isMidnightEndOfDay) 86_400 else 0) + definition
        if (seconds < 0 || seconds >= 86_400) return null
        val minutes = seconds % 3600 / 60
        val time = when {
            seconds == 2 * 3600 -> "" // по умолчанию переход в 02:00
            minutes == 0 -> "/${seconds / 3600}"
            else -> "/${seconds / 3600}:%02d".format(minutes)
        }
        return "M${rule.month.value}.$week.${dayOfWeek.value % 7}$time"
    }
}
