package com.example.denmediaipcams.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.HipcamRecording
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** G.711 A-law в 16-битный PCM (таблица из рекомендации ITU-T G.711) */
internal object G711 {
    fun alawToPcm(alaw: Int): Short {
        val value = alaw xor 0x55
        var t = (value and 0x0F) shl 4
        val segment = (value and 0x70) shr 4
        when (segment) {
            0 -> t += 8
            1 -> t += 0x108
            else -> {
                t += 0x108
                t = t shl (segment - 1)
            }
        }
        return (if (value and 0x80 != 0) t else -t).toShort()
    }

    /** Данные кадра звука A-law в PCM little-endian, по 2 байта на отсчёт */
    fun alawToPcmBytes(data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val sample = alawToPcm(data[offset + i].toInt() and 0xFF).toInt()
            out[i * 2] = sample.toByte()
            out[i * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }
}

/**
 * Скачивает запись Hipcam и собирает из неё обычный MP4, который открывают галерея и любые плееры:
 * видео H.265 кладётся в MP4 как есть (без перекодирования), звук A-law перекодируется в AAC.
 * Формат исходного файла описан в [HxvtExtractor].
 *
 * Читает файл потоком и сразу пишет MP4, поэтому исходный файл целиком на планшете не хранится;
 * нужен только временный MP4 (MediaMuxer пишет в файл), он удаляется по окончании.
 */
object HxvtMp4Converter {

    private const val FILE_HEADER_SIZE = 16
    private const val FRAME_HEADER_SIZE = 16
    private const val MAX_FRAME_SIZE = 8 * 1024 * 1024
    private const val AUDIO_HEADER_SIZE = 4
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34

    /**
     * @param workDir папка для временного файла
     * @param onProgress доля готовности от 0 до 1
     * @return размер записанного MP4 в байтах
     */
    suspend fun convert(
        camera: IpCamera,
        recording: HipcamRecording,
        workDir: File,
        out: OutputStream,
        onProgress: (Float) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("hipcam_", ".mp4", workDir)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(HipcamArchive.fileUrl(camera, recording)).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            HipcamArchive.authorization(camera)?.let { connection.setRequestProperty("Authorization", it) }
            if (connection.responseCode !in 200..299) throw IOException("Камера не отдала файл (код ${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: recording.sizeBytes
            connection.inputStream.use { stream ->
                writeMp4(BufferedInputStream(stream, 256 * 1024), temp, total, onProgress)
            }
            temp.inputStream().use { it.copyTo(out) }
            onProgress(1f)
            temp.length()
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }

    private class Reader(private val input: InputStream, private val onBytes: (Long) -> Unit) {
        private var consumed = 0L

        /** false — файл закончился ровно на границе записи; EOFException — оборван посреди записи */
        fun readFully(target: ByteArray, length: Int, allowEnd: Boolean = false): Boolean {
            var filled = 0
            while (filled < length) {
                val read = input.read(target, filled, length - filled)
                if (read < 0) {
                    if (allowEnd && filled == 0) return false
                    throw EOFException()
                }
                filled += read
            }
            consumed += length
            onBytes(consumed)
            return true
        }
    }

    private suspend fun writeMp4(input: InputStream, target: File, totalBytes: Long, onProgress: (Float) -> Unit) {
        val reader = Reader(input) { if (totalBytes > 0) onProgress((it.toFloat() / totalBytes).coerceIn(0f, 0.99f)) }
        val fileHeader = ByteArray(FILE_HEADER_SIZE)
        reader.readFully(fileHeader, FILE_HEADER_SIZE)
        if (String(fileHeader, 0, 4, Charsets.ISO_8859_1) != "HXVT") throw IOException("Это не запись камеры Hipcam")
        val width = le32(fileHeader, 4)
        val height = le32(fileHeader, 8)

        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var videoTrack = -1
        var audioTrack = -1
        var encoder: G711AacEncoder? = null
        try {
            var vps: ByteArray? = null
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val pending = ByteArrayOutputStream()
            var pendingTimeMs = -1L
            var pendingKey = false
            var baseTimeMs = -1L
            var wroteVideo = false

            fun flushVideo() {
                if (!started || pending.size() == 0) {
                    pending.reset()
                    return
                }
                val bytes = pending.toByteArray()
                val info = MediaCodec.BufferInfo()
                info.set(0, bytes.size, (pendingTimeMs - baseTimeMs) * 1000, if (pendingKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(videoTrack, ByteBuffer.wrap(bytes), info)
                wroteVideo = true
                pending.reset()
            }

            fun startMuxer() {
                val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
                videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(vps!! + sps!! + pps!!))
                videoTrack = muxer.addTrack(videoFormat)
                // Без аудиокодера (нет на устройстве) запись сохраняется без звука
                encoder = runCatching {
                    G711AacEncoder { buffer, info -> if (started) muxer.writeSampleData(audioTrack, buffer, info) }
                }.getOrNull()
                if (encoder != null) audioTrack = muxer.addTrack(G711AacEncoder.trackFormat())
                muxer.start()
                started = true
            }

            val header = ByteArray(FRAME_HEADER_SIZE)
            var payload = ByteArray(512 * 1024)
            var frames = 0
            try {
                while (reader.readFully(header, FRAME_HEADER_SIZE, allowEnd = true)) {
                    val tag = String(header, 0, 4, Charsets.ISO_8859_1)
                    val length = le32(header, 4)
                    // В конце законченного файла лежит индекс HXFI: кадры закончились
                    if (tag == "HXFI") break
                    if ((tag != "HXVF" && tag != "HXAF") || length < 0 || length > MAX_FRAME_SIZE) {
                        throw IOException("Файл записи повреждён")
                    }
                    val timeMs = le32(header, 8).toLong() and 0xFFFFFFFFL
                    if (payload.size < length) payload = ByteArray(length)
                    reader.readFully(payload, length)
                    if (baseTimeMs < 0) baseTimeMs = timeMs
                    if (++frames % 64 == 0) coroutineContext.ensureActive()

                    if (tag == "HXVF") {
                        if (length >= 6 && payload[0].toInt() == 0 && payload[1].toInt() == 0 && payload[2].toInt() == 0 && payload[3].toInt() == 1) {
                            when ((payload[4].toInt() shr 1) and 0x3F) {
                                NAL_VPS -> vps = payload.copyOf(length)
                                NAL_SPS -> sps = payload.copyOf(length)
                                NAL_PPS -> pps = payload.copyOf(length)
                            }
                        }
                        // Записи одного кадра идут подряд с одним временем; новое время — начало следующего кадра
                        if (pending.size() > 0 && timeMs != pendingTimeMs) flushVideo()
                        if (!started && vps != null && sps != null && pps != null) startMuxer()
                        pending.write(payload, 0, length)
                        pendingTimeMs = timeMs
                        pendingKey = le32(header, 12) == 1
                    } else if (started && length > AUDIO_HEADER_SIZE) {
                        encoder?.feed(G711.alawToPcmBytes(payload, AUDIO_HEADER_SIZE, length - AUDIO_HEADER_SIZE), (timeMs - baseTimeMs) * 1000)
                    }
                }
            } catch (e: EOFException) {
                // Запись, которую камера пишет прямо сейчас, обрывается на половине кадра: сохраняем всё, что есть
            }
            flushVideo()
            encoder?.finish()
            if (!wroteVideo) throw IOException("В записи нет видео")
        } finally {
            encoder?.release()
            if (started) {
                runCatching { muxer.stop() }
            }
            muxer.release()
        }
    }

    private fun le32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
