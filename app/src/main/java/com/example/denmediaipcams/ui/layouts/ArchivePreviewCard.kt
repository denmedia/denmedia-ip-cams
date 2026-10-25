package com.example.denmediaipcams.ui.layouts

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.example.denmediaipcams.ui.components.rememberIsNetworkMetered
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.data.ArchiveListCache
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.ArchiveException
import com.example.denmediaipcams.network.ArchiveResult
import com.example.denmediaipcams.network.DahuaApi
import com.example.denmediaipcams.network.DahuaArchiveSession
import com.example.denmediaipcams.network.DahuaRecording
import com.example.denmediaipcams.network.DvripArchive
import com.example.denmediaipcams.network.DvripRecording
import com.example.denmediaipcams.network.HikvisionArchive
import com.example.denmediaipcams.network.HikvisionRecording
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.HipcamRecording
import com.example.denmediaipcams.player.DahuaThumbnails
import com.example.denmediaipcams.player.DvripThumbnails
import com.example.denmediaipcams.player.HikvisionThumbnails
import com.example.denmediaipcams.player.HipcamThumbnails
import com.example.denmediaipcams.ui.components.LocalArchiveHost
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val PREVIEW_COUNT = 10
private const val DAHUA_LOOKBACK_DAYS = 365L
private const val MAX_DAYS_SCANNED = 6

private val PREVIEW_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** Запись для превью: у камер Dahua, Hikvision, с каталогом /sd/ и XMEye/DVRIP записи описываются по-разному */
private data class PreviewItem(
    val start: LocalDateTime,
    val dahua: DahuaRecording? = null,
    val hipcam: HipcamRecording? = null,
    val dvrip: DvripRecording? = null,
    val hikvision: HikvisionRecording? = null
)

private sealed class PreviewState {
    object Loading : PreviewState()
    data class Loaded(val items: List<PreviewItem>) : PreviewState()
    data class Message(val text: String) : PreviewState()
}

/**
 * Архив есть у камер с Dahua API, с каталогом записей /sd/ по HTTP и с включённым архивом XMEye/DVRIP.
 * Это же условие, что и у кнопки «Архив записей» в меню плеера (см. hasArchive в PlayerActions) и у ArchiveHost.
 */
internal fun hasVideoArchive(camera: IpCamera?): Boolean =
    camera != null && (camera.hasDahuaApi || camera.sdHttpArchive || camera.hikvisionArchive || camera.dvripArchive)

/** Последние записи на карте камеры: [PREVIEW_COUNT] новейших, от новых к старым */
private suspend fun loadLatest(camera: IpCamera): PreviewState {
    return try {
        val limit = camera.archiveBlockCount.coerceIn(1, PREVIEW_COUNT)
        val items = when {
            camera.hasDahuaApi -> latestDahua(camera, limit)
            camera.hikvisionArchive -> HikvisionArchive.latest(camera, limit).map { PreviewItem(it.start, hikvision = it) }
            // Порядок как в ArchiveHost: превью показывает то, что откроет кнопка «Все файлы»
            camera.dvripArchive -> DvripArchive.latest(camera, limit).map { PreviewItem(it.start, dvrip = it) }
            else -> latestHipcam(camera, limit)
        }
        if (items.isEmpty()) PreviewState.Message("Записей нет") else PreviewState.Loaded(items)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArchiveException) {
        PreviewState.Message(e.message ?: "Архив недоступен")
    } catch (e: Exception) {
        PreviewState.Message("Архив недоступен")
    }
}

private suspend fun latestDahua(camera: IpCamera, limit: Int): List<PreviewItem> {
    val found = mutableListOf<DahuaRecording>()
    val session = DahuaArchiveSession(camera)
    try {
        val today = LocalDate.now()
        val lower = today.minusDays(DAHUA_LOOKBACK_DAYS).atStartOfDay()
        // Часы телефона и камеры могут расходиться: верхнюю границу берём с запасом в сутки
        var upper = today.plusDays(1).atTime(23, 59, 59)
        for (step in 0 until MAX_DAYS_SCANNED) {
            val newest = session.newestDay(lower, upper) ?: break
            found += session.recordings(newest)
            if (found.size >= limit) break
            upper = newest.atStartOfDay().minusSeconds(1)
        }
    } catch (e: ArchiveException) {
        // Старые прошивки без JSON-RPC: список записей только по CGI, день за днём
        if (found.isEmpty()) {
            val today = LocalDate.now()
            for (back in 0 until MAX_DAYS_SCANNED) {
                val result = DahuaApi.findRecordings(camera, today.minusDays(back.toLong()))
                if (result is ArchiveResult.Found) found += result.recordings
                if (found.size >= limit) break
            }
        }
        if (found.isEmpty()) throw e
    } finally {
        session.close()
    }
    return found.sortedByDescending { it.start }.take(limit).map { PreviewItem(it.start, it, null) }
}

private suspend fun latestHipcam(camera: IpCamera, limit: Int): List<PreviewItem> {
    val found = mutableListOf<HipcamRecording>()
    for (day in HipcamArchive.listDays(camera).sortedDescending().take(MAX_DAYS_SCANNED)) {
        found += HipcamArchive.listRecordings(camera, day)
        if (found.size >= limit) break
    }
    return found.sortedByDescending { it.start }.take(limit).map { PreviewItem(it.start, null, it) }
}

/**
 * Блок «Архив видео»: последние записи с карты камеры с превью и кнопка «Все файлы», открывающая окно архива.
 * Показывается только для камер, у которых архив доступен.
 */
@Composable
internal fun ArchivePreviewCard(camera: IpCamera?, modifier: Modifier = Modifier) {
    val metered by rememberIsNetworkMetered()
    if (camera == null || !hasVideoArchive(camera) || !camera.archiveBlockEnabled) return
    // На лимитной сети блок не выводится и записи не запрашиваются (не тратим трафик)
    if (camera.archiveBlockHideOnMetered && metered) return
    val host = LocalArchiveHost.current
    val context = LocalContext.current
    // «Все файлы» открывает архив как обычно, плитка — сразу свою запись и её день
    val openArchive = { start: LocalDateTime? -> host?.open(camera.id, start) ?: Unit }

    // Список перечитывается при смене камеры и после закрытия окна архива (записей могло стать больше)
    val archiveOpen = host?.cameraId != null
    var state by remember(camera.id) { mutableStateOf<PreviewState>(PreviewState.Loading) }
    val thumbnails = remember(camera.id) { mutableStateMapOf<Int, Bitmap>() }
    LaunchedEffect(camera.id, camera.archiveBlockCount, archiveOpen) {
        if (archiveOpen) return@LaunchedEffect
        // Записи из кэша (до часа) показываются сразу, вместе с уже готовыми превью; камера опрашивается следом
        val cacheKey = "preview|${camera.id}|${camera.archiveBlockCount}"
        if (state !is PreviewState.Loaded) {
            ArchiveListCache.get<List<PreviewItem>>(cacheKey)?.let { cachedItems ->
                state = PreviewState.Loaded(cachedItems)
                cachedItems.forEachIndexed { index, item ->
                    val bitmap = item.dahua?.let { DahuaThumbnails.cached(context, camera, it) }
                        ?: item.hipcam?.let { HipcamThumbnails.cached(context, camera, it) }
                        ?: item.dvrip?.let { DvripThumbnails.cached(context, camera, it) }
                        ?: item.hikvision?.let { HikvisionThumbnails.cached(context, camera, it) }
                    if (bitmap != null) thumbnails[index] = bitmap
                }
            }
        }
        val loaded = loadLatest(camera)
        if (loaded is PreviewState.Loaded) ArchiveListCache.put(cacheKey, loaded.items)
        // Ошибку не выводим поверх уже показанного списка
        if (loaded is PreviewState.Message && state is PreviewState.Loaded) return@LaunchedEffect
        if ((state as? PreviewState.Loaded)?.items != (loaded as? PreviewState.Loaded)?.items) thumbnails.clear()
        if (state != loaded) state = loaded
        // Превью строятся по одному: камера не терпит параллельных запросов
        if (loaded is PreviewState.Loaded) {
            loaded.items.forEachIndexed { index, item ->
                if (index in thumbnails) return@forEachIndexed
                val bitmap = item.dahua?.let { DahuaThumbnails.cached(context, camera, it) ?: DahuaThumbnails.load(context, camera, it) }
                    ?: item.hipcam?.let { HipcamThumbnails.cached(context, camera, it) ?: HipcamThumbnails.load(context, camera, it) }
                    ?: item.dvrip?.let { DvripThumbnails.cached(context, camera, it) ?: DvripThumbnails.load(context, camera, it) }
                    ?: item.hikvision?.let { HikvisionThumbnails.cached(context, camera, it) ?: HikvisionThumbnails.load(context, camera, it) }
                if (bitmap != null) thumbnails[index] = bitmap
            }
        }
    }

    PanelCard(title = "Архив видео", modifier = modifier, actionLabel = "Все файлы", onAction = { openArchive(null) }) {
        when (val current = state) {
            PreviewState.Loading -> Text(
                "Загрузка…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is PreviewState.Message -> Text(
                current.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is PreviewState.Loaded -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(current.items) { index, item ->
                    PreviewTile(item = item, thumbnail = thumbnails[index], onClick = { openArchive(item.start) })
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(item: PreviewItem, thumbnail: Bitmap?, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(104.dp)
            .aspectRatio(4f / 3f)
            .clip(MaterialTheme.shapes.small)
            .background(Color.Black)
            .clickable(onClick = onClick)
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
        Text(
            item.start.format(PREVIEW_TIME_FORMAT),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
