package com.example.denmediaipcams.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripClient
import com.example.denmediaipcams.network.DvripFileEntry
import com.example.denmediaipcams.network.DvripMediaFrame
import com.example.denmediaipcams.network.DvripRecording
import com.example.denmediaipcams.network.DvripStreamException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Скачивает запись камеры по протоколу XMEye/DVRIP и собирает из неё обычный MP4: видео H.264/H.265
 * кладётся как есть (без перекодирования), звук G.711 A-law перекодируется в AAC тем же кодером,
 * что и у записей Hipcam/.265 (см. [G711AacEncoder] — вынесен в общий файл, чтобы не дублировать код).
 *
 * У DVRIP, в отличие от каталога /sd/ у Hipcam, нет HTTP Range-запросов — прогрессивное воспроизведение
 * с камеры по кускам невозможно. Поэтому запись сначала целиком скачивается и собирается во временный
 * MP4 (см. [convert]), а воспроизводится уже как обычный локальный файл обычным ExoPlayer
 * (см. DvripArchiveDialog) — другая модель, чем у Hipcam, но для редкого просмотра архива с одной
 * SD-карты это не критично, а зато перемотка и буферизация работают как у любого локального видео.
 */
object DvripMp4Converter {

    private const val NAL_VPS = 32
    private const val NAL_SPS_H265 = 33
    private const val NAL_PPS_H265 = 34
    private const val NAL_SPS_H264 = 7
    private const val NAL_PPS_H264 = 8

    /**
     * @param workDir папка для временного файла
     * @param onProgress доля готовности от 0 до 1. Камера не сообщает общий размер записи заранее
     *   (нет Content-Length, как у HTTP), поэтому ход считается по доле покрытого времени записи
     *   (кадр.ptsMs / длительность записи), а не по числу скачанных байт
     * @return размер записанного MP4 в байтах
     */
    suspend fun convert(
        camera: IpCamera,
        recording: DvripRecording,
        workDir: File,
        out: OutputStream,
        onProgress: (Float) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("dvrip_", ".mp4", workDir)
        val client = DvripClient(camera)
        try {
            client.login()
            val file = DvripFileEntry(recording.start, recording.end, recording.channel, recording.fileName, recording.sizeBytes)
            val stream = client.openPlayback(file)
            try {
                writeMp4(stream, recording.durationMs.coerceAtLeast(1), temp, onProgress)
            } finally {
                // Сообщаем камере остановить скачивание, даже если конвертация прервалась ошибкой/отменой
                runCatching { stream.stop() }
            }
            temp.inputStream().use { it.copyTo(out) }
            onProgress(1f)
            temp.length()
        } finally {
            client.close()
            temp.delete()
        }
    }

    private suspend fun writeMp4(
        stream: DvripClient.DvripFrameStream,
        durationMs: Long,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var videoTrack = -1
        var audioTrack = -1
        var encoder: G711AacEncoder? = null
        try {
            var vps: ByteArray? = null
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            var mime: String? = null
            var width = 0
            var height = 0
            var wroteVideo = false
            var frames = 0

            fun parameterSetsReady(): Boolean = when (mime) {
                MediaFormat.MIMETYPE_VIDEO_HEVC -> vps != null && sps != null && pps != null
                MediaFormat.MIMETYPE_VIDEO_AVC -> sps != null && pps != null
                else -> false
            }

            fun startMuxer() {
                val videoFormat = MediaFormat.createVideoFormat(mime!!, width, height)
                if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(vps!! + sps!! + pps!!))
                } else {
                    videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
                    videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
                }
                videoTrack = muxer.addTrack(videoFormat)
                // Без аудиокодера (нет на устройстве) запись сохраняется без звука
                encoder = runCatching {
                    G711AacEncoder { buffer, info -> if (started) muxer.writeSampleData(audioTrack, buffer, info) }
                }.getOrNull()
                if (encoder != null) audioTrack = muxer.addTrack(G711AacEncoder.trackFormat())
                muxer.start()
                started = true
            }

            while (true) {
                val frame = try {
                    stream.nextFrame()
                } catch (e: DvripStreamException) {
                    // Неизвестный формат потока — разбор дальше невозможен; выше это превращается
                    // в понятное сообщение пользователю, а DownloadStop/закрытие сессии — в finally вызывающего кода
                    throw IOException(e.message, e)
                } ?: break
                if (++frames % 64 == 0) coroutineContext.ensureActive()

                when (frame) {
                    is DvripMediaFrame.Video -> {
                        if (frame.width > 0) width = frame.width
                        if (frame.height > 0) height = frame.height
                        mime = when (frame.codec) {
                            "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
                            "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
                            else -> mime
                        }
                        if (!started) {
                            captureParameterSets(frame.codec, frame.data) { vpsFound, spsFound, ppsFound ->
                                vpsFound?.let { vps = it }
                                spsFound?.let { sps = it }
                                ppsFound?.let { pps = it }
                            }
                            if (mime != null && width > 0 && height > 0 && parameterSetsReady()) startMuxer()
                        }
                        if (started) {
                            val info = MediaCodec.BufferInfo()
                            info.set(0, frame.data.size, frame.ptsMs * 1000, if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(videoTrack, ByteBuffer.wrap(frame.data), info)
                            wroteVideo = true
                            onProgress((frame.ptsMs.toFloat() / durationMs).coerceIn(0f, 0.99f))
                        }
                    }
                    is DvripMediaFrame.Audio -> {
                        if (started && encoder != null && frame.data.isNotEmpty()) {
                            encoder!!.feed(G711.alawToPcmBytes(frame.data, 0, frame.data.size), frame.ptsMs * 1000)
                        }
                    }
                }
            }
            encoder?.finish()
            if (!wroteVideo) throw IOException("В записи нет видео")
        } finally {
            encoder?.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /**
     * Ищет VPS/SPS/PPS внутри кадра (данные уже в Annex-B, может быть несколько NAL в одном буфере —
     * как у ключевого кадра HXVT, см. HxvtExtractor): сканирует стартовые коды 00 00 00 01 и
     * классифицирует каждый NAL по типу.
     */
    private inline fun captureParameterSets(
        codec: String,
        data: ByteArray,
        onFound: (vps: ByteArray?, sps: ByteArray?, pps: ByteArray?) -> Unit
    ) {
        if (codec != "h264" && codec != "h265") return
        var i = indexOfStartCode(data, 0)
        var foundAny = false
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        while (i >= 0 && i + 4 < data.size) {
            val next = indexOfStartCode(data, i + 4).let { if (it < 0) data.size else it }
            val nalType = if (codec == "h265") (data[i + 4].toInt() shr 1) and 0x3F else data[i + 4].toInt() and 0x1F
            when {
                codec == "h265" && nalType == NAL_VPS -> { vps = data.copyOfRange(i, next); foundAny = true }
                codec == "h265" && nalType == NAL_SPS_H265 -> { sps = data.copyOfRange(i, next); foundAny = true }
                codec == "h265" && nalType == NAL_PPS_H265 -> { pps = data.copyOfRange(i, next); foundAny = true }
                codec == "h264" && nalType == NAL_SPS_H264 -> { sps = data.copyOfRange(i, next); foundAny = true }
                codec == "h264" && nalType == NAL_PPS_H264 -> { pps = data.copyOfRange(i, next); foundAny = true }
            }
            i = if (next < data.size) next else -1
        }
        if (foundAny) onFound(vps, sps, pps)
    }

    private fun indexOfStartCode(data: ByteArray, from: Int): Int {
        var i = from
        val last = data.size - 4
        while (i <= last) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
            i++
        }
        return -1
    }
}
