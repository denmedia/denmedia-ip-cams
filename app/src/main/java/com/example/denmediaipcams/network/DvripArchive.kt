package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Запись на SD-карте камеры по протоколу XMEye/DVRIP (порт 34567, см. [DvripClient]) */
data class DvripRecording(
    val day: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** Имя файла на камере (FileName из OPFileQuery) — передаётся обратно в OPPlayBack как есть */
    val fileName: String,
    val channel: Int,
    val sizeBytes: Long
) {
    val durationMs: Long get() = Duration.between(start, end).toMillis()
}

/**
 * Архив записей камер по проприетарному протоколу XMEye/DVRIP (порт 34567). Включается только явным
 * согласием пользователя (IpCamera.dvripArchive, диалог настройки камеры) — протокол слабо защищён
 * (см. [sofiaHash]), а некорректные запросы к некоторым прошивкам вызывали зависания/перезагрузки,
 * поэтому, в отличие от Dahua/Hipcam, автоматического определения для этого способа архива нет
 * (см. комментарий у PortDetector.XMEYE_SDK_PORTS).
 */
object DvripArchive {

    // Камера не умеет отдавать список дней — только поиск файлов по интервалу времени. Поэтому дни
    // собираются перебором окон в прошлое и сверкой BeginTime найденных записей; окно короче 60 дней
    // целиком, чтобы один запрос не держал сессию открытой слишком долго
    private const val LOOKBACK_DAYS = 60L
    private const val DAY_WINDOW = 10L
    private const val MAX_DAY_RECORDINGS = 2_000
    private const val LATEST_LOOKBACK_DAYS = 14

    /** Попытка входа подтверждает, что камера отвечает на этом протоколе и принимает логин/пароль */
    suspend fun isSupported(camera: IpCamera): Boolean? = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext false
        val client = DvripClient(camera)
        try {
            client.login()
            true
        } catch (e: ArchiveException) {
            if (e.kind == ArchiveException.Kind.NETWORK) null else false
        } finally {
            client.close()
        }
    }

    /** Дни, за которые на карте есть записи, по возрастанию (см. комментарий в начале объекта) */
    suspend fun listDays(camera: IpCamera): List<LocalDate> = withContext(Dispatchers.IO) {
        val client = DvripClient(camera)
        try {
            client.login()
            val days = sortedSetOf<LocalDate>()
            var windowEnd = LocalDate.now().plusDays(1).atStartOfDay()
            var remainingDays = LOOKBACK_DAYS
            while (remainingDays > 0) {
                val span = minOf(DAY_WINDOW, remainingDays)
                val windowStart = windowEnd.minusDays(span)
                client.listFiles(channel = 0, begin = windowStart, end = windowEnd)
                    .forEach { days += it.beginTime.toLocalDate() }
                windowEnd = windowStart
                remainingDays -= span
            }
            days.toList()
        } finally {
            client.close()
        }
    }

    /**
     * Новейшие записи на карте (до [limit]), от новых к старым, для блока «Архив видео» на главном экране.
     * Идём назад по суткам одной сессией: окно в сутки не даёт обрезать новейшие записи лимитом
     * [MAX_DAY_RECORDINGS], а дни без записей просто пропускаются (не дальше [LATEST_LOOKBACK_DAYS]).
     */
    suspend fun latest(camera: IpCamera, limit: Int): List<DvripRecording> = withContext(Dispatchers.IO) {
        val client = DvripClient(camera)
        try {
            client.login()
            val found = mutableListOf<DvripRecording>()
            // Часы телефона и камеры могут расходиться: верхнюю границу берём с запасом в сутки
            var dayEnd = LocalDate.now().plusDays(2).atStartOfDay()
            for (step in 0 until LATEST_LOOKBACK_DAYS) {
                val dayStart = dayEnd.minusDays(1)
                client.listFiles(channel = 0, begin = dayStart, end = dayEnd).forEach {
                    found += DvripRecording(it.beginTime.toLocalDate(), it.beginTime, it.endTime, it.fileName, it.channel, it.sizeBytes)
                }
                if (found.size >= limit) break
                dayEnd = dayStart
            }
            found.sortedByDescending { it.start }.take(limit)
        } finally {
            client.close()
        }
    }

    /** Записи дня по возрастанию времени начала */
    suspend fun listRecordings(camera: IpCamera, day: LocalDate): List<DvripRecording> = withContext(Dispatchers.IO) {
        val client = DvripClient(camera)
        try {
            client.login()
            client.listFiles(channel = 0, begin = day.atStartOfDay(), end = day.plusDays(1).atStartOfDay())
                .take(MAX_DAY_RECORDINGS)
                .map { DvripRecording(day, it.beginTime, it.endTime, it.fileName, it.channel, it.sizeBytes) }
                .sortedBy { it.start }
        } finally {
            client.close()
        }
    }
}
