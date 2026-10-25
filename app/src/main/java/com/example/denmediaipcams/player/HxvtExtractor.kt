package com.example.denmediaipcams.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * Файлы .265 с SD-карты камер Hipcam (HiSilicon, имя вида P261010_020112_021118.265).
 *
 * Устройство файла (все числа little-endian):
 *  - заголовок 16 байт: «HXVT», ширина, высота, 0;
 *  - далее кадры: «HXVF» (видео) или «HXAF» (звук), длина данных, время в мс, тип, сами данные.
 *    Тип видеокадра: 1 — ключевой, 2 — обычный; у звука 0.
 *  - видео — H.265 в формате Annex-B; у ключевого кадра VPS, SPS, PPS и SEI идут отдельными записями с тем же временем;
 *  - звук — 4 байта служебного заголовка и 160 байт G.711 A-law (20 мс, 8 кГц, моно).
 *
 * У законченного файла в конце лежит запись «HXFI» (всегда 200000 байт данных; вместо времени и типа в её заголовке
 * длительность файла в мс и служебное число), а в данных пары «смещение записи, время в мс» для VPS, SPS, PPS, SEI и IDR
 * каждого ключевого кадра (ключевой кадр раз в 4 секунды).
 * По ней перемотка точная до ключевого кадра. Файл, который камера пишет сейчас, индекса ещё не имеет —
 * тогда позиция в файле берётся пропорционально времени, а воспроизведение начинается с ближайшего ключевого кадра дальше.
 */
@OptIn(UnstableApi::class)
class HxvtExtractor(private val durationUs: Long) : Extractor {

    private enum class Stage { START, INDEX, MEDIA }

    private var stage = Stage.START

    // Индекс из конца файла: время начала и смещение каждого ключевого кадра (null — индекса нет)
    private var indexTimesUs: LongArray? = null
    private var indexOffsets: LongArray? = null
    private var indexDurationUs = C.TIME_UNSET

    private lateinit var output: ExtractorOutput
    private var video: TrackOutput? = null
    private var audio: TrackOutput? = null

    private val header = ByteArray(FRAME_HEADER_SIZE)
    private var payload = ByteArray(INITIAL_PAYLOAD_SIZE)

    // Параметры кодека, из которых собирается формат видео (VPS, SPS, PPS вместе со стартовыми кодами)
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var fileWidth = 0
    private var fileHeight = 0

    // Видеокадр, который ещё собирается из нескольких записей с одним временем
    private var pending = ByteArray(INITIAL_PAYLOAD_SIZE)
    private var pendingSize = 0
    private var pendingTimeMs = -1L
    private var pendingKey = false

    private var baseTimeMs = -1L
    private var needResync = true

    override fun sniff(input: ExtractorInput): Boolean {
        val tag = ByteArray(4)
        input.peekFully(tag, 0, 4)
        return String(tag, Charsets.ISO_8859_1) == FILE_TAG
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingSize = 0
        pendingTimeMs = -1
        needResync = true
    }

    override fun release() = Unit

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        try {
            if (fileWidth == 0 && input.position == 0L) readFileHeader(input)
            // Индекс лежит в самом конце файла: сначала читаем его, затем возвращаемся к началу
            if (stage == Stage.START) {
                val length = input.length
                if (length > INDEX_TOTAL_SIZE + FILE_HEADER_SIZE) {
                    stage = Stage.INDEX
                    seekPosition.position = length - INDEX_TOTAL_SIZE
                    return Extractor.RESULT_SEEK
                }
                stage = Stage.MEDIA
            }
            if (stage == Stage.INDEX) {
                try {
                    readIndex(input)
                } catch (e: EOFException) {
                    // Индекс обрезан: перемотка останется по пропорции
                }
                stage = Stage.MEDIA
                needResync = true
                seekPosition.position = FILE_HEADER_SIZE.toLong()
                return Extractor.RESULT_SEEK
            }
            if (needResync) {
                if (!resyncToKeyFrame(input)) {
                    flushVideo()
                    return Extractor.RESULT_END_OF_INPUT
                }
                needResync = false
            }
            if (!input.readFully(header, 0, FRAME_HEADER_SIZE, true)) {
                flushVideo()
                return Extractor.RESULT_END_OF_INPUT
            }
            val tag = String(header, 0, 4, Charsets.ISO_8859_1)
            val length = le32(header, 4)
            // Индекс в конце файла — кадры закончились
            if (tag == INDEX_TAG) {
                flushVideo()
                return Extractor.RESULT_END_OF_INPUT
            }
            // Нарушенная разметка (повреждённый файл): ищем следующий ключевой кадр
            if ((tag != VIDEO_TAG && tag != AUDIO_TAG) || length < 0 || length > MAX_FRAME_SIZE) {
                needResync = true
                pendingSize = 0
                pendingTimeMs = -1
                return Extractor.RESULT_CONTINUE
            }
            val timeMs = le32(header, 8).toLong() and 0xFFFFFFFFL
            if (payload.size < length) payload = ByteArray(length)
            input.readFully(payload, 0, length)
            if (baseTimeMs < 0) baseTimeMs = timeMs
            if (tag == VIDEO_TAG) onVideoRecord(timeMs, le32(header, 12) == 1, length) else onAudioRecord(timeMs, length)
            return Extractor.RESULT_CONTINUE
        } catch (e: EOFException) {
            // Файл, который камера пишет прямо сейчас, обрывается на половине кадра
            flushVideo()
            return Extractor.RESULT_END_OF_INPUT
        }
    }

    /** Читает запись HXFI с текущей позиции; если её нет (файл ещё пишется) — остаётся перемотка по пропорции */
    private fun readIndex(input: ExtractorInput) {
        if (!input.readFully(header, 0, FRAME_HEADER_SIZE, true)) return
        val size = le32(header, 4)
        if (String(header, 0, 4, Charsets.ISO_8859_1) != INDEX_TAG || size < 16 || size > MAX_INDEX_SIZE) return
        // В заголовке записи вместо времени и типа — длительность файла в мс и служебное число; в данных сразу пары
        val durationMs = le32(header, 8).toLong() and 0xFFFFFFFFL
        val data = ByteArray(size)
        input.readFully(data, 0, size)
        val times = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        var lastTimeMs = -1L
        val fileLength = input.length
        var i = 0
        while (i + 8 <= size) {
            val offset = le32(data, i).toLong() and 0xFFFFFFFFL
            val timeMs = le32(data, i + 4).toLong() and 0xFFFFFFFFL
            i += 8
            // Нулевая пара — свободное место в конце резервной области индекса
            if (offset == 0L || (fileLength != C.LENGTH_UNSET.toLong() && offset >= fileLength) || timeMs < lastTimeMs) break
            // Пары идут по записям кадра; началом ключевого кадра считается первая из них
            if (timeMs != lastTimeMs) {
                times.add(timeMs * 1000)
                offsets.add(offset)
                lastTimeMs = timeMs
            }
        }
        if (times.isEmpty()) return
        indexTimesUs = times.toLongArray()
        indexOffsets = offsets.toLongArray()
        if (durationMs > 0) indexDurationUs = durationMs * 1000
    }

    private fun readFileHeader(input: ExtractorInput) {
        val fileHeader = ByteArray(FILE_HEADER_SIZE)
        input.peekFully(fileHeader, 0, FILE_HEADER_SIZE)
        fileWidth = le32(fileHeader, 4)
        fileHeight = le32(fileHeader, 8)
        input.resetPeekPosition()
    }

    private fun onVideoRecord(timeMs: Long, key: Boolean, length: Int) {
        captureParameterSet(length)
        // Записи одного кадра идут подряд с одним временем; новое время — начало следующего кадра
        if (pendingSize > 0 && timeMs != pendingTimeMs) flushVideo()
        // Пока нет VPS, SPS и PPS, формат видео неизвестен: записи копятся и войдут в первый кадр
        if (video == null) createTracks()
        if (pendingSize + length > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + length))
        System.arraycopy(payload, 0, pending, pendingSize, length)
        pendingSize += length
        pendingTimeMs = timeMs
        pendingKey = key
    }

    private fun onAudioRecord(timeMs: Long, length: Int) {
        val track = audio ?: return
        val size = length - AUDIO_HEADER_SIZE
        if (size <= 0) return
        track.sampleData(ParsableByteArray(payload.copyOfRange(AUDIO_HEADER_SIZE, length)), size)
        track.sampleMetadata((timeMs - baseTimeMs) * 1000, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
    }

    private fun captureParameterSet(length: Int) {
        if (length < 6 || be32(payload) != START_CODE) return
        val record = payload.copyOf(length)
        when ((payload[4].toInt() shr 1) and 0x3F) {
            NAL_VPS -> vps = record
            NAL_SPS -> sps = record
            NAL_PPS -> pps = record
        }
    }

    /** Дорожки создаются, когда известны параметры кодека: до этого формат видео описать нельзя */
    private fun createTracks(): Boolean {
        val parameterSets = listOfNotNull(vps, sps, pps)
        if (parameterSets.size < 3) return false
        val videoTrack = output.track(VIDEO_TRACK_ID, C.TRACK_TYPE_VIDEO)
        videoTrack.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(fileWidth)
                .setHeight(fileHeight)
                .setInitializationData(listOf(parameterSets.reduce { acc, bytes -> acc + bytes }))
                .build()
        )
        val audioTrack = output.track(AUDIO_TRACK_ID, C.TRACK_TYPE_AUDIO)
        audioTrack.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_ALAW)
                .setChannelCount(1)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .build()
        )
        video = videoTrack
        audio = audioTrack
        output.endTracks()
        val times = indexTimesUs
        val offsets = indexOffsets
        output.seekMap(
            if (times != null && offsets != null) {
                IndexedSeekMap(if (indexDurationUs != C.TIME_UNSET) indexDurationUs else durationUs, times, offsets)
            } else {
                HxvtSeekMap(durationUs, lastKnownLength)
            }
        )
        return true
    }

    private var lastKnownLength = C.LENGTH_UNSET.toLong()

    private fun flushVideo() {
        val track = video
        if (track == null) {
            // Формат так и не появился (в начале файла нет параметров кодека): кадр без него показать нельзя
            pendingSize = 0
            pendingTimeMs = -1
            return
        }
        if (pendingSize == 0) return
        track.sampleData(ParsableByteArray(pending.copyOf(pendingSize)), pendingSize)
        track.sampleMetadata(
            (pendingTimeMs - baseTimeMs) * 1000,
            if (pendingKey) C.BUFFER_FLAG_KEY_FRAME else 0,
            pendingSize,
            0,
            null
        )
        pendingSize = 0
        pendingTimeMs = -1
    }

    /**
     * Ставит чтение на начало ближайшего ключевого кадра: запись «HXVF» с типом 1, после которой идёт VPS.
     * false — до конца файла такого кадра нет.
     */
    private fun resyncToKeyFrame(input: ExtractorInput): Boolean {
        if (lastKnownLength == C.LENGTH_UNSET.toLong()) lastKnownLength = input.length
        val window = ByteArray(SCAN_WINDOW)
        while (true) {
            input.resetPeekPosition()
            var filled = 0
            while (filled < window.size) {
                val read = input.peek(window, filled, window.size - filled)
                if (read == C.RESULT_END_OF_INPUT) break
                filled += read
            }
            input.resetPeekPosition()
            if (filled < KEY_FRAME_PATTERN_SIZE) return false
            val index = findKeyFrame(window, filled)
            if (index >= 0) {
                input.skipFully(index)
                return true
            }
            // Образец мог оказаться на границе окна: следующее окно начинается с небольшим перекрытием
            input.skipFully(filled - KEY_FRAME_PATTERN_SIZE + 1)
        }
    }

    private fun findKeyFrame(data: ByteArray, size: Int): Int {
        var i = 0
        val last = size - KEY_FRAME_PATTERN_SIZE
        while (i <= last) {
            if (data[i] == 'H'.code.toByte() && data[i + 1] == 'X'.code.toByte() &&
                data[i + 2] == 'V'.code.toByte() && data[i + 3] == 'F'.code.toByte() &&
                le32(data, i + 12) == 1 &&
                be32(data, i + 16) == START_CODE &&
                ((data[i + 20].toInt() shr 1) and 0x3F) == NAL_VPS
            ) return i
            i++
        }
        return -1
    }

    /** Карта перемотки по индексу из конца файла: точно до ключевого кадра (они идут раз в несколько секунд) */
    private class IndexedSeekMap(
        private val durationUs: Long,
        private val timesUs: LongArray,
        private val offsets: LongArray
    ) : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = if (durationUs > 0) durationUs else C.TIME_UNSET

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            // Последний ключевой кадр не позже запрошенного времени и следующий за ним
            var index = timesUs.indexOfLast { it <= timeUs }
            if (index < 0) index = 0
            val first = SeekPoint(timesUs[index], offsets[index])
            if (timesUs[index] == timeUs || index == timesUs.lastIndex) return SeekMap.SeekPoints(first)
            return SeekMap.SeekPoints(first, SeekPoint(timesUs[index + 1], offsets[index + 1]))
        }
    }

    /** Карта перемотки по пропорции: байт ≈ доля времени от общей длины файла (качество кадров по файлу меняется мало) */
    private class HxvtSeekMap(private val durationUs: Long, private val lengthBytes: Long) : SeekMap {
        private val canSeek = durationUs > 0 && lengthBytes > FILE_HEADER_SIZE

        override fun isSeekable() = canSeek
        override fun getDurationUs() = if (durationUs > 0) durationUs else C.TIME_UNSET
        override fun isEstimated() = true

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!canSeek) return SeekMap.SeekPoints(SeekPoint.START)
            val time = timeUs.coerceIn(0, durationUs)
            val position = FILE_HEADER_SIZE + ((lengthBytes - FILE_HEADER_SIZE) * (time.toDouble() / durationUs)).toLong()
            return SeekMap.SeekPoints(SeekPoint(time, position))
        }
    }

    private companion object {
        const val FILE_TAG = "HXVT"
        const val VIDEO_TAG = "HXVF"
        const val AUDIO_TAG = "HXAF"
        const val INDEX_TAG = "HXFI"
        const val INDEX_DATA_SIZE = 200_000
        const val INDEX_TOTAL_SIZE = INDEX_DATA_SIZE + 16
        const val MAX_INDEX_SIZE = 1024 * 1024
        const val FILE_HEADER_SIZE = 16
        const val FRAME_HEADER_SIZE = 16
        const val AUDIO_HEADER_SIZE = 4
        const val AUDIO_SAMPLE_RATE = 8000
        const val VIDEO_TRACK_ID = 0
        const val AUDIO_TRACK_ID = 1
        const val INITIAL_PAYLOAD_SIZE = 512 * 1024
        const val MAX_FRAME_SIZE = 8 * 1024 * 1024
        const val SCAN_WINDOW = 64 * 1024

        // «HXVF», длина, время, тип (1), стартовый код 00 00 00 01 и заголовок NAL
        const val KEY_FRAME_PATTERN_SIZE = 22
        const val START_CODE = 1
        const val NAL_VPS = 32
        const val NAL_SPS = 33
        const val NAL_PPS = 34

        fun le32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

        fun be32(data: ByteArray, offset: Int = 0): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}

/** Фабрика для ProgressiveMediaSource: длительность берётся из имени файла записи */
@OptIn(UnstableApi::class)
class HxvtExtractorsFactory(private val durationMs: Long) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        arrayOf(HxvtExtractor(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET))
}
