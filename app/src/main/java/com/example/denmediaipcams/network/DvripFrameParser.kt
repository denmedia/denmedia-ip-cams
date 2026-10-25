package com.example.denmediaipcams.network

import java.io.ByteArrayOutputStream

/** Декодированный кадр из «сырого» потока плейбэка DVRIP/XMEye (см. [DvripFrameParser]) */
sealed class DvripMediaFrame {
    /** [codec] — "h264", "h265" или "unknown"; [width]/[height] — последние известные (из ключевого кадра) */
    data class Video(
        val codec: String,
        val width: Int,
        val height: Int,
        val keyFrame: Boolean,
        val ptsMs: Long,
        val data: ByteArray
    ) : DvripMediaFrame()

    /** G.711 A-law, сырые отсчёты (как в HxvtMp4Converter — декодируются через [com.example.denmediaipcams.player.G711]) */
    data class Audio(val ptsMs: Long, val data: ByteArray) : DvripMediaFrame()
}

/** Камера прислала кадр в формате, которого эта реализация не знает — поток дальше разобрать нельзя */
class DvripStreamException(message: String) : Exception(message)

/**
 * Разбирает «сырой» поток плейбэка DVRIP/XMEye на кадры видео и звука. Формат (см. описание протокола
 * в DvripClient): каждый логический кадр начинается с 4-байтного big-endian тега, за которым идёт
 * заголовок фиксированного для тега размера, а затем — ровно столько байт данных, сколько указано
 * в этом заголовке.
 *
 * Поток приходит кусками произвольного размера (границы сетевых пакетов камеры никак не связаны
 * с границами кадров), поэтому и тег, и заголовок, и сами данные кадра могут быть разбиты между
 * несколькими вызовами [feed] — состояние разбора (сколько байт ещё нужно дособрать) хранится между вызовами.
 *
 * У видео нет своего времени показа (в отличие от HXVT с SD-карты Hipcam): время синтезируется по
 * порядку кадров и номинальному fps из заголовка ключевого кадра; у звука — отдельные «часы»
 * (G.711 8 кГц моно, 1 байт — 1 отсчёт).
 */
internal class DvripFrameParser(
    /**
     * Поток начинается не с границы кадра: камера при старте с середины записи отдаёт хвост файла с позиции,
     * пропорциональной времени (по байтам). Тогда разбор ждёт ближайшего ключевого кадра, всё до него отбрасывается
     */
    resync: Boolean = false,
    private val onFrame: (DvripMediaFrame) -> Unit
) {

    /** Удобная форма без ресинхронизации (разбор с начала файла) */
    constructor(onFrame: (DvripMediaFrame) -> Unit) : this(false, onFrame)

    private enum class Stage { TAG, SUBHEADER, PAYLOAD }

    private var stage = Stage.TAG
    private var need = TAG_SIZE
    private val collect = ByteArrayOutputStream()
    private val payload = ByteArrayOutputStream()

    private var currentTag = 0
    private var remaining = 0

    private var currentIsVideo = false
    private var currentIsAudio = false
    private var currentDiscard = false
    private var currentKey = false

    // «Липкие» параметры видео: меняются только заголовком ключевого кадра (0x1FC)
    private var currentCodec = "unknown"
    private var currentWidth = 0
    private var currentHeight = 0
    private var fps = DEFAULT_FPS

    /**
     * Камера добивает файл байтами 0xFF до целого числа килобайт: тег 0xFFFFFFFF — это не кадр, а конец
     * данных записи; всё, что идёт после него, пропускается
     */
    var reachedPadding = false
        private set

    private var syncing = resync
    private var syncTail = ByteArray(0)

    // Время видео копится в микросекундах: 1000/12 мс — не целое, и по миллисекундам набежало бы около секунды
    // расхождения со звуком на пятиминутной записи
    private var videoClockUs = 0L
    private var audioClockMs = 0L

    /** Разбирает очередной кусок сырого потока; может вызвать [onFrame] ноль, один или много раз подряд */
    fun feed(chunk: ByteArray) {
        if (syncing) {
            val joined = syncTail + chunk
            val found = findKeyFrameStart(joined)
            if (found < 0) {
                // Ключевой кадр мог начаться в конце этого куска — оставляем хвост на следующий
                syncTail = joined.copyOfRange(maxOf(0, joined.size - SYNC_KEEP), joined.size)
                return
            }
            syncing = false
            syncTail = ByteArray(0)
            feed(joined.copyOfRange(found, joined.size))
            return
        }
        var offset = 0
        while (offset < chunk.size && !reachedPadding) {
            if (stage == Stage.PAYLOAD) {
                val take = minOf(remaining, chunk.size - offset)
                if (!currentDiscard) payload.write(chunk, offset, take)
                remaining -= take
                offset += take
                if (remaining == 0) {
                    emitFrame()
                    stage = Stage.TAG
                    need = TAG_SIZE
                }
            } else {
                val take = minOf(need - collect.size(), chunk.size - offset)
                collect.write(chunk, offset, take)
                offset += take
                if (collect.size() == need) advanceStage()
            }
        }
    }

    /**
     * Начало ключевого кадра в [data]: тег 0x1FC, правдоподобный заголовок (fps, длина) и сразу за ним — стартовый код
     * NAL. Вместе это почти не встречается случайно в сжатых данных. -1 — не найден (или кадр ещё не весь в буфере)
     */
    private fun findKeyFrameStart(data: ByteArray): Int {
        var i = 0
        while (i + SYNC_KEEP <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1 && (data[i + 3].toInt() and 0xFF) == 0xFC) {
                val fpsValue = data[i + 5].toInt() and 0xFF
                val length = le32(data, i + 12)
                val startCode = data[i + 16].toInt() == 0 && data[i + 17].toInt() == 0 && data[i + 18].toInt() == 0 && data[i + 19].toInt() == 1
                if (fpsValue in 1..120 && length in 1..MAX_FRAME_PAYLOAD && startCode) return i
            }
            i++
        }
        return -1
    }

    private fun advanceStage() {
        val bytes = collect.toByteArray()
        collect.reset()
        when (stage) {
            Stage.TAG -> {
                val tag = be32(bytes)
                if (tag == TAG_PADDING) {
                    reachedPadding = true
                    return
                }
                currentTag = tag
                val size = subHeaderSize(tag)
                    ?: throw DvripStreamException("Камера прислала неизвестный формат потока (0x%08X)".format(tag))
                stage = Stage.SUBHEADER
                need = size
            }
            Stage.SUBHEADER -> {
                applySubHeader(currentTag, bytes)
                if (remaining < 0 || remaining > MAX_FRAME_PAYLOAD) {
                    throw DvripStreamException("Камера прислала повреждённый заголовок кадра (длина $remaining)")
                }
                if (remaining > 0) {
                    payload.reset()
                    stage = Stage.PAYLOAD
                } else {
                    emitFrame()
                    stage = Stage.TAG
                    need = TAG_SIZE
                }
            }
            Stage.PAYLOAD -> Unit
        }
    }

    private fun subHeaderSize(tag: Int): Int? = when (tag) {
        TAG_VIDEO_KEY, TAG_JPEG_HEADER -> 12
        TAG_VIDEO_DELTA -> 4
        TAG_AUDIO -> 4
        TAG_INFO -> 4
        else -> null
    }

    private fun applySubHeader(tag: Int, bytes: ByteArray) {
        when (tag) {
            TAG_VIDEO_KEY, TAG_JPEG_HEADER -> {
                val media = bytes[0].toInt() and 0xFF
                val fpsValue = bytes[1].toInt() and 0xFF
                val w8 = bytes[2].toInt() and 0xFF
                val h8 = bytes[3].toInt() and 0xFF
                val length = le32(bytes, 8)
                currentWidth = w8 * 8
                currentHeight = h8 * 8
                currentCodec = when (media) {
                    1 -> "mpeg4"
                    2 -> "h264"
                    // 0x13 — H.265 у камеры NVT (проверено по записи: в данных VPS/SPS/PPS H.265); другие коды
                    // опознаются по самим данным кадра, см. [refineFromKeyFrame]
                    3, MEDIA_H265_NVT -> "h265"
                    else -> "unknown"
                }
                fps = if (fpsValue in 1..120) fpsValue else DEFAULT_FPS
                currentIsVideo = true
                currentIsAudio = false
                currentDiscard = false
                currentKey = tag == TAG_VIDEO_KEY
                remaining = length
            }
            TAG_VIDEO_DELTA -> {
                currentIsVideo = true
                currentIsAudio = false
                currentDiscard = false
                currentKey = false
                remaining = le32(bytes, 0)
            }
            TAG_AUDIO -> {
                val media = bytes[0].toInt() and 0xFF
                currentIsVideo = false
                currentIsAudio = media == AUDIO_MEDIA_ALAW
                currentDiscard = !currentIsAudio
                remaining = le16(bytes, 2)
            }
            TAG_INFO -> {
                currentIsVideo = false
                currentIsAudio = false
                currentDiscard = true
                remaining = le16(bytes, 2)
            }
        }
    }

    private fun emitFrame() {
        if (currentDiscard) {
            payload.reset()
            return
        }
        val data = payload.toByteArray()
        payload.reset()
        when {
            currentIsVideo -> {
                if (currentKey) refineFromKeyFrame(data)
                val ptsMs = videoClockUs / 1000
                videoClockUs += 1_000_000L / fps
                onFrame(DvripMediaFrame.Video(currentCodec, currentWidth, currentHeight, currentKey, ptsMs, data))
            }
            currentIsAudio -> {
                val ptsMs = audioClockMs
                // 8 кГц, 1 байт на отсчёт G.711: длительность в мс = число байт / 8
                audioClockMs += (data.size / 8L).coerceAtLeast(1)
                onFrame(DvripMediaFrame.Audio(ptsMs, data))
            }
        }
    }

    /**
     * Заголовок ключевого кадра ненадёжен: кодек может прийти незнакомым кодом, а ширина и высота лежат в одном
     * байте (в единицах по 8 пикселей) и для кадра шире 2040 пикселей обрезаются (2560 приходит как 512).
     * Поэтому кодек (если он не опознан) определяется по первому NAL, а размер берётся из SPS в самом кадре.
     */
    private fun refineFromKeyFrame(data: ByteArray) {
        val nals = findNals(data)
        if (nals.isEmpty()) return
        if (currentCodec == "unknown") {
            val first = data[nals.first().first].toInt() and 0xFF
            val h265Type = (first shr 1) and 0x3F
            val h264Type = first and 0x1F
            currentCodec = when {
                first and 0x81 == 0 && h265Type in 32..34 -> "h265"
                first and 0x80 == 0 && h264Type in intArrayOf(5, 7, 8) -> "h264"
                else -> return
            }
        }
        val h265 = currentCodec == "h265"
        if (!h265 && currentCodec != "h264") return
        for ((start, end) in nals) {
            val first = data[start].toInt() and 0xFF
            val isSps = if (h265) ((first shr 1) and 0x3F) == 33 else (first and 0x1F) == 7
            if (!isSps) continue
            SpsDimensions.parse(data.copyOfRange(start, end), h265)?.let {
                currentWidth = it.width
                currentHeight = it.height
            }
            return
        }
    }

    /** NAL-блоки Annex-B в буфере: пары (первый байт NAL, конец), стартовые коды 00 00 01 и 00 00 00 01 */
    private fun findNals(data: ByteArray): List<Pair<Int, Int>> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { index, start ->
            var end = if (index + 1 < starts.size) starts[index + 1] - 3 else data.size
            while (end > start && data[end - 1].toInt() == 0) end-- // нуль перед 4-байтным стартовым кодом
            start to end
        }.filter { it.second > it.first }
    }

    private companion object {
        const val MEDIA_H265_NVT = 0x13
        const val TAG_SIZE = 4
        // тег (4) + заголовок ключевого кадра (12) + стартовый код (4) = 20 байт нужно, чтобы опознать кадр
        const val SYNC_KEEP = 20
        const val TAG_PADDING = -1 // 0xFFFFFFFF
        const val TAG_VIDEO_KEY = 0x1FC
        const val TAG_VIDEO_DELTA = 0x1FD
        const val TAG_AUDIO = 0x1FA
        const val TAG_INFO = 0x1F9
        // Заголовок JPEG-кадра (OPSNAP); в потоке плейбэка не ожидается, разбирается так же защитно, как 0x1FC
        const val TAG_JPEG_HEADER = 0x1FE
        const val AUDIO_MEDIA_ALAW = 0x0E
        const val DEFAULT_FPS = 15
        const val MAX_FRAME_PAYLOAD = 32 * 1024 * 1024

        fun be32(data: ByteArray): Int =
            ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)

        fun le32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

        fun le16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
