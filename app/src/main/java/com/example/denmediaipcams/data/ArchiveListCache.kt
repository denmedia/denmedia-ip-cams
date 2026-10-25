package com.example.denmediaipcams.data

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Списки файлов архива камер (дни с записями, записи дня) в памяти приложения. Окно архива показывает
 * сохранённый список сразу, пока камера отвечает на запрос, а затем в фоне обновляет его (см. [REFRESH_INTERVAL_MS]).
 * Запись старше [TTL_MS] считается устаревшей и не отдаётся: список тогда загружается как обычно.
 */
object ArchiveListCache {

    /** Сколько живёт сохранённый список */
    const val TTL_MS = 60L * 60 * 1000

    /** Как часто открытое окно архива перечитывает список с камеры */
    const val REFRESH_INTERVAL_MS = 60L * 1000

    private class Entry(val value: Any, val savedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Часы подменяются в тестах */
    internal var clock: () -> Long = System::currentTimeMillis

    fun daysKey(protocol: String, cameraId: String) = "$protocol|$cameraId|days"

    fun dayKey(protocol: String, cameraId: String, day: LocalDate) = "$protocol|$cameraId|day|$day"

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        val entry = entries[key] ?: return null
        if (clock() - entry.savedAt >= TTL_MS) {
            entries.remove(key, entry)
            return null
        }
        return entry.value as? T
    }

    fun put(key: String, value: Any) {
        entries[key] = Entry(value, clock())
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    /** Забывает все списки камеры (после форматирования карты или удаления записи они неверны) */
    fun clearCamera(cameraId: String) {
        entries.keys.removeAll { it.split('|').getOrNull(1) == cameraId }
    }
}
