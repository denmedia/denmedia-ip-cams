package com.example.denmediaipcams.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.media.MediaFormat
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripClient
import com.example.denmediaipcams.network.DvripFileEntry
import com.example.denmediaipcams.network.DvripMediaFrame
import com.example.denmediaipcams.network.DvripRecording
import com.example.denmediaipcams.network.HikvisionRecording
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.HipcamRecording
import com.example.denmediaipcams.widget.RtspFrameSource
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Превью записей архива: в памяти (пока список прокручивается) и на диске в кэше приложения —
 * система может очистить его сама, а потеря превью не страшна, они строятся заново.
 */
internal object ArchiveThumbnailCache {

    private const val DIR_NAME = "archive_thumbnails"
    private const val JPEG_QUALITY = 80
    private const val MAX_FILES = 1500
    private const val TRIM_EVERY = 50

    /** Превью живёт сутки, потом строится заново (возраст файла на диске — по времени его записи) */
    private const val TTL_MS = 24L * 60 * 60 * 1000

    private class Entry(val bitmap: Bitmap, val savedAt: Long)

    private val memory = LruCache<String, Entry>(60)
    private var writes = 0

    private fun file(context: Context, key: String): File {
        val safeKey = key.replace(Regex("""[^A-Za-z0-9_\-]"""), "_")
        return File(File(context.cacheDir, DIR_NAME).apply { mkdirs() }, "$safeKey.jpg")
    }

    private fun isExpired(savedAt: Long) = System.currentTimeMillis() - savedAt >= TTL_MS

    suspend fun get(context: Context, key: String): Bitmap? {
        memory.get(key)?.let { entry ->
            if (!isExpired(entry.savedAt)) return entry.bitmap
            memory.remove(key)
        }
        return withContext(Dispatchers.IO) {
            val f = file(context, key)
            val savedAt = f.lastModified()
            when {
                !f.exists() -> null
                isExpired(savedAt) -> {
                    f.delete()
                    null
                }
                else -> BitmapFactory.decodeFile(f.absolutePath)?.also { memory.put(key, Entry(it, savedAt)) }
            }
        }
    }

    suspend fun put(context: Context, key: String, bitmap: Bitmap) {
        memory.put(key, Entry(bitmap, System.currentTimeMillis()))
        withContext(Dispatchers.IO) {
            runCatching {
                file(context, key).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
            // Кэш не растёт бесконечно: записи на карте перезаписываются по кругу, а их превью остаются
            if (++writes % TRIM_EVERY == 0) trim(context)
        }
    }

    private fun trim(context: Context) {
        val all = File(context.cacheDir, DIR_NAME).listFiles() ?: return
        // Сначала уходят просроченные превью, и только если файлов всё ещё слишком много — самые старые
        val (expired, files) = all.partition { isExpired(it.lastModified()) }
        expired.forEach { it.delete() }
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }
}

/**
 * Записи, превью которых не построилось (камера занята просмотром, сеть). Они не запоминаются навсегда: чёрная плитка
 * до перезапуска приложения хуже, чем повторная попытка через [RETRY_AFTER_MS] — и не дёргает камеру при каждой прокрутке.
 */
internal class ThumbnailFailures {
    private val failedAt = Collections.synchronizedMap(HashMap<String, Long>())

    fun recently(key: String): Boolean =
        failedAt[key]?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true

    fun mark(key: String) {
        failedAt[key] = System.currentTimeMillis()
    }

    fun clear(key: String) {
        failedAt.remove(key)
    }

    private companion object {
        const val RETRY_AFTER_MS = 60_000L
    }
}

/**
 * Превью записей Hipcam: первый ключевой кадр файла. Он лежит в самом начале файла, поэтому хватает
 * первого мегабайта (запросом Range), а не всего файла в 60–80 МБ.
 */
object HipcamThumbnails {

    private const val THUMBNAIL_WIDTH = 320
    private const val FIRST_READ_BYTES = 1 shl 20
    private const val SECOND_READ_BYTES = 4 shl 20

    // Камера и аппаратный декодер не любят параллельной работы: превью строятся строго по очереди
    private val queue = Mutex()

    private fun key(camera: IpCamera, recording: HipcamRecording) = "hipcam_${camera.id}_${recording.path}"

    /** Превью из кэша, без обращения к камере */
    suspend fun cached(context: Context, camera: IpCamera, recording: HipcamRecording): Bitmap? =
        ArchiveThumbnailCache.get(context.applicationContext, key(camera, recording))

    /** Превью записи: из кэша или с камеры; null — построить не удалось */
    suspend fun load(context: Context, camera: IpCamera, recording: HipcamRecording): Bitmap? {
        val appContext = context.applicationContext
        val key = key(camera, recording)
        ArchiveThumbnailCache.get(appContext, key)?.let { return it }
        return queue.withLock {
            // Пока превью ждало очереди, его мог построить другой запрос
            ArchiveThumbnailCache.get(appContext, key)?.let { return@withLock it }
            val bitmap = withContext(Dispatchers.Default) { build(camera, recording) } ?: return@withLock null
            ArchiveThumbnailCache.put(appContext, key, bitmap)
            bitmap
        }
    }

    private suspend fun build(camera: IpCamera, recording: HipcamRecording): Bitmap? {
        var data = HipcamArchive.readStart(camera, recording, FIRST_READ_BYTES) ?: return null
        var keyFrame = HxvtKeyFrame.parse(data)
        // Ключевой кадр ночью бывает крупнее мегабайта: один раз читаем больше
        if (keyFrame == null && data.size >= FIRST_READ_BYTES) {
            data = HipcamArchive.readStart(camera, recording, SECOND_READ_BYTES) ?: return null
            keyFrame = HxvtKeyFrame.parse(data)
        }
        keyFrame ?: return null
        return HevcFrameDecoder.decode(keyFrame.width, keyFrame.height, keyFrame.parameterSets, keyFrame.data, THUMBNAIL_WIDTH)
    }
}

/**
 * Превью записей Hikvision: первый кадр RTSP-воспроизведения записи (отдельного запроса «картинка записи» у камеры нет).
 * Каждое превью — короткое подключение; строятся строго по очереди, камера не терпит параллельных сеансов.
 */
object HikvisionThumbnails {

    private const val THUMBNAIL_WIDTH = 320

    private val queue = Mutex()
    private val failures = ThumbnailFailures()

    private fun key(camera: IpCamera, recording: HikvisionRecording) = "hikvision_${camera.id}_${recording.startUtc}"

    /** Превью из кэша, без обращения к камере */
    suspend fun cached(context: Context, camera: IpCamera, recording: HikvisionRecording): Bitmap? =
        ArchiveThumbnailCache.get(context.applicationContext, key(camera, recording))

    /** Превью записи: из кэша или с камеры; null — построить не удалось */
    suspend fun load(context: Context, camera: IpCamera, recording: HikvisionRecording): Bitmap? {
        val appContext = context.applicationContext
        val key = key(camera, recording)
        ArchiveThumbnailCache.get(appContext, key)?.let { return it }
        if (failures.recently(key)) return null
        return queue.withLock {
            ArchiveThumbnailCache.get(appContext, key)?.let { return@withLock it }
            if (failures.recently(key)) return@withLock null
            val bitmap = grab(appContext, camera, recording)
            if (bitmap == null) {
                failures.mark(key)
                return@withLock null
            }
            failures.clear(key)
            ArchiveThumbnailCache.put(appContext, key, bitmap)
            bitmap
        }
    }

    private suspend fun grab(context: Context, camera: IpCamera, recording: HikvisionRecording): Bitmap? {
        // Запись — тот же RTSP-порт, но другой путь; потоки живой трансляции в нём не участвуют
        val playbackCamera = camera.copy(rtspPath = recording.playbackPath, streamProfiles = emptyList(), activeStreamProfileId = null)
        return RtspFrameSource(context, playbackCamera).use { it.awaitFrame(THUMBNAIL_WIDTH) }
    }
}

/**
 * Превью записей камер XMEye/DVRIP: первый ключевой кадр записи. Камера отдаёт запись с начала, а ключевой кадр
 * идёт самым первым (около 80 КБ для 2560x1440), поэтому читается только он, после чего скачивание останавливается.
 * Каждое превью — отдельный короткий сеанс с камерой: они строятся строго по очереди, камера не терпит параллельных
 * запросов. Если построить не удалось, повторять в этом запуске приложения не пытаемся (иначе каждое появление
 * строки в списке снова дёргало бы камеру).
 */
object DvripThumbnails {

    private const val THUMBNAIL_WIDTH = 320
    private const val BUILD_TIMEOUT_MS = 20_000L

    private val queue = Mutex()
    private val failures = ThumbnailFailures()

    private fun key(camera: IpCamera, recording: DvripRecording) = "dvrip_${camera.id}_${recording.fileName}"

    /** Превью из кэша, без обращения к камере */
    suspend fun cached(context: Context, camera: IpCamera, recording: DvripRecording): Bitmap? =
        ArchiveThumbnailCache.get(context.applicationContext, key(camera, recording))

    /** Превью записи: из кэша или с камеры; null — построить не удалось */
    suspend fun load(context: Context, camera: IpCamera, recording: DvripRecording): Bitmap? {
        val appContext = context.applicationContext
        val key = key(camera, recording)
        ArchiveThumbnailCache.get(appContext, key)?.let { return it }
        if (failures.recently(key)) return null
        return queue.withLock {
            ArchiveThumbnailCache.get(appContext, key)?.let { return@withLock it }
            if (failures.recently(key)) return@withLock null
            val bitmap = withContext(Dispatchers.Default) { build(camera, recording) }
            if (bitmap == null) {
                failures.mark(key)
                return@withLock null
            }
            failures.clear(key)
            ArchiveThumbnailCache.put(appContext, key, bitmap)
            bitmap
        }
    }

    private suspend fun build(camera: IpCamera, recording: DvripRecording): Bitmap? {
        val key = withTimeoutOrNull(BUILD_TIMEOUT_MS) { firstKeyFrame(camera, recording) } ?: return null
        val sets = parameterSets(key.data, key.codec == "h265")
        if (sets.isEmpty()) return null
        val mime = if (key.codec == "h265") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        return HevcFrameDecoder.decode(key.width, key.height, sets, key.data, THUMBNAIL_WIDTH, mime)
    }

    private suspend fun firstKeyFrame(camera: IpCamera, recording: DvripRecording): DvripMediaFrame.Video? {
        val client = DvripClient(camera)
        try {
            client.login()
            val file = DvripFileEntry(recording.start, recording.end, recording.channel, recording.fileName, recording.sizeBytes)
            val stream = client.openPlayback(file)
            try {
                // Ключевой кадр идёт в самом начале записи; если за первые кадры его нет, превью не построить
                repeat(MAX_FRAMES_TO_SCAN) {
                    val frame = stream.nextFrame() ?: return null
                    if (frame is DvripMediaFrame.Video && frame.keyFrame && (frame.codec == "h265" || frame.codec == "h264")) return frame
                }
                return null
            } finally {
                withContext(NonCancellable) { runCatching { stream.stop() } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            client.close()
        }
    }

    /** VPS/SPS/PPS (H.265) или SPS/PPS (H.264) из ключевого кадра: для настройки декодера */
    private fun parameterSets(data: ByteArray, h265: Boolean): ByteArray {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 4 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                starts += i
                i += 4
            } else {
                i++
            }
        }
        val out = java.io.ByteArrayOutputStream()
        starts.forEachIndexed { index, start ->
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            val header = data[start + 4].toInt() and 0xFF
            val wanted = if (h265) ((header shr 1) and 0x3F) in 32..34 else (header and 0x1F) in 7..8
            if (wanted) out.write(data, start, end - start)
        }
        return out.toByteArray()
    }

    private const val MAX_FRAMES_TO_SCAN = 8
}
