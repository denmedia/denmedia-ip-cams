package com.example.denmediaipcams.ui.components

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.denmediaipcams.data.ArchiveListCache
import com.example.denmediaipcams.network.ArchiveException
import com.example.denmediaipcams.network.DahuaArchiveSession
import com.example.denmediaipcams.network.StorageVolume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth

/**
 * Дни, в которые на накопителе камеры есть записи. Камера отвечает на один запрос около 0,1 с и не терпит
 * параллельных, а на карте могут лежать месяцы ежедневных записей, поэтому индекс строится по уровням:
 * 1) самый свежий день — сразу, окно открывается на нём;
 * 2) месяцы с записями (годы получаются из них) — один запрос на месяц;
 * 3) дни месяца — сначала в фоне от новых месяцев к старым, а месяц, который открыл пользователь
 *    в календаре, загружается вне очереди ([loadMonth]).
 *
 * Построенный индекс сохраняется в [ArchiveListCache] на час: при следующем открытии окна он показывается сразу,
 * а [keepFresh] раз в минуту доискивает новые дни одним запросом (см. [refreshNewest]).
 */
@Stable
class ArchiveIndex(private val session: DahuaArchiveSession, private val cameraId: String? = null) {

    /** Снимок индекса для кэша */
    private class Snapshot(
        val months: List<YearMonth>,
        val daysByMonth: Map<YearMonth, List<LocalDate>>,
        val completeMonths: List<YearMonth>,
        val storage: List<StorageVolume>
    )

    enum class Status { Scanning, Ready, Unavailable }

    var status by mutableStateOf(Status.Scanning)
        private set

    /** Причина, если архив недоступен */
    var failure by mutableStateOf<ArchiveException?>(null)
        private set

    /** Накопители камеры (путь, размер, занятое место) */
    var storage by mutableStateOf<List<StorageVolume>>(emptyList())
        private set

    /** Месяцы с записями, от новых к старым; пополняется по ходу поиска */
    val months = mutableStateListOf<YearMonth>()

    /** Поиск месяцев закончен */
    var monthsDone by mutableStateOf(false)
        private set

    // Известные дни месяца: у месяца, которого нет в completeMonths, список может быть неполным
    private val daysByMonth = mutableStateMapOf<YearMonth, List<LocalDate>>()
    private val completeMonths = mutableStateListOf<YearMonth>()
    private val inFlight = mutableMapOf<YearMonth, CompletableDeferred<Unit>>()

    // Индекс восстановлен из кэша: поиск с нуля не нужен
    private var restored = false

    init {
        val snapshot = cameraId?.let { ArchiveListCache.get<Snapshot>(cacheKey(it)) }
        if (snapshot != null && snapshot.months.isNotEmpty()) {
            months.addAll(snapshot.months)
            daysByMonth.putAll(snapshot.daysByMonth)
            completeMonths.addAll(snapshot.completeMonths)
            storage = snapshot.storage
            monthsDone = true
            status = Status.Ready
            restored = true
        }
    }

    private fun save() {
        val id = cameraId ?: return
        if (status != Status.Ready) return
        ArchiveListCache.put(cacheKey(id), Snapshot(months.toList(), daysByMonth.toMap(), completeMonths.toList(), storage))
    }

    /** Камера отдаёт архив через JSON-RPC: календарь ограничен днями с записями. Иначе — обычный выбор любого дня */
    val rpcAvailable: Boolean
        get() = !(status == Status.Unavailable && failure?.kind == ArchiveException.Kind.UNSUPPORTED)

    /** Ещё идёт поиск месяцев или дней */
    val isLoading: Boolean
        get() = status == Status.Scanning || !monthsDone || months.any { it !in completeMonths }

    fun hasRecordings(date: LocalDate): Boolean = daysByMonth[YearMonth.from(date)]?.contains(date) == true

    fun hasYear(year: Int): Boolean = months.any { it.year == year }

    /** Все известные сейчас дни с записями, от новых к старым */
    fun knownDays(): List<LocalDate> = daysByMonth.values.flatten().sortedDescending()

    /**
     * Строит индекс; onNewest получает самый свежий день, как только он найден.
     * Возвращается, когда загружены дни всех месяцев (или поиск остановила ошибка).
     */
    suspend fun run(onNewest: (LocalDate) -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        if (restored) {
            // Индекс из кэша уже показан: сообщаем свежий день, добираем новые дни и недогруженные месяцы
            months.firstOrNull()?.let { daysByMonth[it]?.maxOrNull() }?.let(onNewest)
            refreshNewest()
            for (month in months.toList()) loadMonth(month)
            save()
            Log.i(TAG, "restored from cache: ${knownDays().size} days, refreshed in ${SystemClock.elapsedRealtime() - startedAt} ms")
            return
        }
        try {
            storage = session.volumes()
            val today = LocalDate.now()
            val lower = today.minusDays(LOOKBACK_DAYS.toLong()).atStartOfDay()
            // Часы телефона и камеры могут расходиться: верхнюю границу берём с запасом в сутки
            var upper = today.plusDays(1).atTime(23, 59, 59)
            for (step in 0 until MAX_MONTHS) {
                val newest = session.newestDay(lower, upper) ?: break
                val month = YearMonth.from(newest)
                months.add(month)
                // Самый свежий день месяца уже известен — календарь показывает его, пока дни месяца не загрузились
                daysByMonth[month] = listOf(newest)
                if (step == 0) {
                    status = Status.Ready
                    Log.i(TAG, "newest day $newest found in ${SystemClock.elapsedRealtime() - startedAt} ms")
                    onNewest(newest)
                }
                upper = month.atDay(1).atStartOfDay().minusSeconds(1)
            }
            monthsDone = true
            status = Status.Ready
            Log.i(TAG, "months found: ${months.size} in ${SystemClock.elapsedRealtime() - startedAt} ms")
            for (month in months.toList()) loadMonth(month)
            Log.i(TAG, "all days loaded: ${knownDays().size} days in ${SystemClock.elapsedRealtime() - startedAt} ms")
            save()
        } catch (e: ArchiveException) {
            failure = e
            status = Status.Unavailable
            monthsDone = true
        }
    }

    /** Загружает все дни месяца (повторные и одновременные вызовы ждут одну загрузку) */
    suspend fun loadMonth(month: YearMonth) {
        if (month in completeMonths) return
        inFlight[month]?.let {
            it.await()
            return
        }
        val done = CompletableDeferred<Unit>()
        inFlight[month] = done
        try {
            val lower = month.atDay(1).atStartOfDay()
            var upper = month.atEndOfMonth().atTime(23, 59, 59)
            val found = mutableListOf<LocalDate>()
            while (true) {
                val day = session.newestDay(lower, upper) ?: break
                found += day
                daysByMonth[month] = found.toList()
                upper = day.atStartOfDay().minusSeconds(1)
            }
            completeMonths.add(month)
            save()
        } catch (_: ArchiveException) {
            // Месяц остаётся неполным: известные дни доступны, остальные подгрузятся при следующем обращении
        } finally {
            inFlight.remove(month)
            done.complete(Unit)
        }
    }

    /**
     * Раз в минуту проверяет, не появились ли новые дни с записями, пока открыто окно. Работает после [run]
     * и заканчивается вместе с корутиной окна.
     */
    suspend fun keepFresh() {
        if (status != Status.Ready) return
        while (true) {
            delay(ArchiveListCache.REFRESH_INTERVAL_MS)
            refreshNewest()
            save()
        }
    }

    /**
     * Добирает дни новее самого свежего известного: поиск идёт от границы «начало известного дня», поэтому обычно
     * это один запрос, который снова находит уже известный день. Старые дни не перепроверяются.
     */
    private suspend fun refreshNewest() {
        try {
            // Карта была пуста: опираться не на что, индекс не трогаем
            val known = months.firstOrNull()?.let { daysByMonth[it]?.maxOrNull() } ?: return
            val today = LocalDate.now()
            val lower = known.atStartOfDay()
            // Часы телефона и камеры могут расходиться: верхнюю границу берём с запасом в сутки
            var upper = today.plusDays(1).atTime(23, 59, 59)
            val newer = mutableListOf<LocalDate>()
            var reachedKnown = false
            for (step in 0 until MAX_NEW_DAYS) {
                val day = session.newestDay(lower, upper)
                if (day == null || day == known) {
                    reachedKnown = true
                    break
                }
                newer += day
                upper = day.atStartOfDay().minusSeconds(1)
            }
            // От старых новых дней к свежим: новый месяц встаёт в начало списка месяцев
            for (day in newer.asReversed()) {
                val month = YearMonth.from(day)
                if (month in months) {
                    daysByMonth[month] = (daysByMonth[month].orEmpty() + day).distinct().sortedDescending()
                } else {
                    months.add(0, month)
                    daysByMonth[month] = listOf(day)
                    // Все дни нового месяца найдены этим же поиском (если он дошёл до известного дня)
                    if (reachedKnown) completeMonths.add(month)
                }
            }
            if (newer.isNotEmpty()) Log.i(TAG, "new days found: $newer")
        } catch (_: ArchiveException) {
            // Камера не ответила: попробуем через минуту, показанный индекс остаётся
        }
    }

    /** Есть ли что-то раньше этого дня (точно, если месяц загружен; иначе — «возможно») */
    fun hasPrevious(from: LocalDate): Boolean {
        val month = YearMonth.from(from)
        return months.any { it < month } || month !in completeMonths || daysByMonth[month]?.any { it < from } == true
    }

    fun hasNext(from: LocalDate): Boolean {
        val month = YearMonth.from(from)
        return months.any { it > month } || month !in completeMonths || daysByMonth[month]?.any { it > from } == true
    }

    /** Ближайший более ранний день с записями; null — раньше записей нет */
    suspend fun previousDay(from: LocalDate): LocalDate? {
        val month = YearMonth.from(from)
        loadMonth(month)
        daysByMonth[month]?.filter { it < from }?.maxOrNull()?.let { return it }
        for (older in months.filter { it < month }.sortedDescending()) {
            loadMonth(older)
            daysByMonth[older]?.maxOrNull()?.let { return it }
        }
        return null
    }

    /** Ближайший более поздний день с записями; null — позже записей нет */
    suspend fun nextDay(from: LocalDate): LocalDate? {
        val month = YearMonth.from(from)
        loadMonth(month)
        daysByMonth[month]?.filter { it > from }?.minOrNull()?.let { return it }
        for (newer in months.filter { it > month }.sorted()) {
            loadMonth(newer)
            daysByMonth[newer]?.minOrNull()?.let { return it }
        }
        return null
    }

    private companion object {
        const val TAG = "ArchiveIndex"

        fun cacheKey(cameraId: String) = ArchiveListCache.daysKey("dahua", cameraId)

        // Не больше стольких новых дней за одну проверку (карта могла пролежать без обновления долго)
        const val MAX_NEW_DAYS = 62

        // Как далеко в прошлое искать: камера отвечает тем дольше, чем длиннее пустой промежуток до ближайшей
        // записи (9000 пустых суток — около 3,5 с), а записи старше нескольких лет на карте не бывает
        const val LOOKBACK_DAYS = 365 * 3
        const val MAX_MONTHS = 40
    }
}
