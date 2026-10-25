package com.example.denmediaipcams.player

/**
 * Упаковщик в MPEG-TS для потокового просмотра записей, которые камера отдаёт кадр за кадром (DVRIP):
 * в отличие от MP4, TS не требует итогового индекса в конце файла, поэтому ExoPlayer начинает играть,
 * пока запись ещё приходит. Видео H.264/H.265 кладётся как есть (Annex-B), звук — AAC в обёртке ADTS.
 *
 * Каждый вызов write* упаковывает один кадр в PES и отдаёт [sink] готовые TS-пакеты по 188 байт
 * (целым числом пакетов). Перед каждым ключевым кадром повторяются таблицы PAT/PMT, на нём же ставится PCR.
 */
internal class MpegTsMuxer(
    private val h265: Boolean,
    private val hasAudio: Boolean,
    private val sink: (ByteArray) -> Unit
) {
    private val continuity = IntArray(8192)

    /** Видео и звук: время показа кадра в миллисекундах от начала потока */
    fun writeVideo(ptsMs: Long, keyFrame: Boolean, annexB: ByteArray) {
        if (keyFrame) writeTables()
        val pts = toPts(ptsMs)
        // Часы PCR идут чуть раньше времени показа, как требует стандарт (PCR <= DTS первого кадра)
        val pcr = if (keyFrame) (pts - PCR_LEAD).coerceAtLeast(0) else null
        writePes(VIDEO_PID, STREAM_ID_VIDEO, pts, annexB, pcr, randomAccess = keyFrame, unbounded = true)
    }

    fun writeAudio(ptsMs: Long, aacFrame: ByteArray) {
        if (!hasAudio) return
        writePes(AUDIO_PID, STREAM_ID_AUDIO, toPts(ptsMs), adts(aacFrame), pcr = null, randomAccess = false, unbounded = false)
    }

    private fun toPts(ms: Long): Long = BASE_PTS + ms * 90

    private fun writeTables() {
        sink(psiPacket(PAT_PID, pat()) + psiPacket(PMT_PID, pmt()))
    }

    private fun pat(): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01, // transport_stream_id
            0xC1.toByte(), 0x00, 0x00, // версия 0, действует, номер секции 0, последняя 0
            0x00, 0x01, // program_number 1
            (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte()
        )
        return section(0x00, body)
    }

    private fun pmt(): ByteArray {
        val streams = ArrayList<Byte>()
        fun stream(type: Int, pid: Int) {
            streams += type.toByte()
            streams += (0xE0 or (pid shr 8)).toByte()
            streams += (pid and 0xFF).toByte()
            streams += 0xF0.toByte() // ES_info_length = 0
            streams += 0x00
        }
        stream(if (h265) 0x24 else 0x1B, VIDEO_PID)
        if (hasAudio) stream(0x0F, AUDIO_PID)
        val body = byteArrayOf(
            0x00, 0x01, // program_number
            0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), // PCR_PID
            0xF0.toByte(), 0x00 // program_info_length = 0
        ) + streams.toByteArray()
        return section(0x02, body)
    }

    /** Секция PSI: заголовок с длиной, тело и CRC32 */
    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val length = body.size + 4 // тело + CRC
        val head = byteArrayOf(tableId.toByte(), (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte())
        val withoutCrc = head + body
        val crc = crc32Mpeg(withoutCrc)
        return withoutCrc + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    /** Один TS-пакет с секцией PSI (укладывается в 183 байта полезных данных) */
    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        require(section.size + 1 <= 184)
        val packet = ByteArray(PACKET) { 0xFF.toByte() }
        packet[0] = SYNC
        packet[1] = (0x40 or (pid shr 8)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or nextCounter(pid)).toByte()
        packet[4] = 0x00 // pointer_field
        section.copyInto(packet, 5)
        return packet
    }

    private fun writePes(pid: Int, streamId: Int, pts: Long, data: ByteArray, pcr: Long?, randomAccess: Boolean, unbounded: Boolean) {
        val pes = ByteArray(PES_HEADER + data.size)
        pes[0] = 0
        pes[1] = 0
        pes[2] = 1
        pes[3] = streamId.toByte()
        val length = if (unbounded) 0 else (3 + 5 + data.size).coerceAtMost(0xFFFF)
        pes[4] = (length shr 8).toByte()
        pes[5] = length.toByte()
        pes[6] = 0x84.toByte() // маркер, выравнивание по началу кадра
        pes[7] = 0x80.toByte() // есть только PTS
        pes[8] = 5 // длина поля PTS
        putTimestamp(pes, 9, 0x20, pts)
        data.copyInto(pes, PES_HEADER)

        val packets = ArrayList<ByteArray>()
        var offset = 0
        var first = true
        var total = 0
        while (offset < pes.size) {
            val remaining = pes.size - offset
            var adaptation = if (first && pcr != null) 8 else 0 // вместе с байтом длины: длина + флаги + PCR (6)
            val capacity = 184 - adaptation
            val take = minOf(remaining, capacity)
            if (take < capacity) adaptation = 184 - take // последний пакет добивается «набивкой» в поле адаптации
            val packet = ByteArray(PACKET) { 0xFF.toByte() }
            packet[0] = SYNC
            packet[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            packet[2] = (pid and 0xFF).toByte()
            packet[3] = ((if (adaptation > 0) 0x30 else 0x10) or nextCounter(pid)).toByte()
            if (adaptation > 0) {
                packet[4] = (adaptation - 1).toByte()
                if (adaptation > 1) {
                    var flags = 0
                    if (first && randomAccess) flags = flags or 0x40
                    if (first && pcr != null) flags = flags or 0x10
                    packet[5] = flags.toByte()
                    if (first && pcr != null) putPcr(packet, 6, pcr)
                }
            }
            pes.copyInto(packet, 4 + adaptation, offset, offset + take)
            offset += take
            first = false
            packets += packet
            total += PACKET
        }
        val out = ByteArray(total)
        packets.forEachIndexed { i, p -> p.copyInto(out, i * PACKET) }
        sink(out)
    }

    private fun nextCounter(pid: Int): Int {
        val value = continuity[pid]
        continuity[pid] = (value + 1) and 0x0F
        return value
    }

    /** 5 байт PTS: 4 бита префикса, затем 33 бита времени тремя группами с маркерными единицами */
    private fun putTimestamp(target: ByteArray, at: Int, prefix: Int, value: Long) {
        target[at] = (prefix or (((value shr 30) and 0x07).toInt() shl 1) or 1).toByte()
        target[at + 1] = ((value shr 22) and 0xFF).toByte()
        target[at + 2] = ((((value shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        target[at + 3] = ((value shr 7) and 0xFF).toByte()
        target[at + 4] = (((value and 0x7F).toInt() shl 1) or 1).toByte()
    }

    private fun putPcr(target: ByteArray, at: Int, base: Long) {
        target[at] = (base shr 25).toByte()
        target[at + 1] = (base shr 17).toByte()
        target[at + 2] = (base shr 9).toByte()
        target[at + 3] = (base shr 1).toByte()
        target[at + 4] = (((base and 1).toInt() shl 7) or 0x7E).toByte()
        target[at + 5] = 0
    }

    /** Кадр AAC-LC 8 кГц моно в обёртке ADTS (7 байт заголовка), как у G711AacEncoder */
    private fun adts(frame: ByteArray): ByteArray {
        val length = frame.size + 7
        val out = ByteArray(length)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, без CRC
        out[2] = ((1 shl 6) or (ADTS_FREQ_8000 shl 2)).toByte() // AAC-LC, 8000 Гц, канал: старший бит 0
        out[3] = ((1 shl 6) or ((length shr 11) and 0x03)).toByte() // моно
        out[4] = ((length shr 3) and 0xFF).toByte()
        out[5] = (((length and 0x07) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        frame.copyInto(out, 7)
        return out
    }

    internal companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
        const val PAT_PID = 0
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        private const val STREAM_ID_VIDEO = 0xE0
        private const val STREAM_ID_AUDIO = 0xC0
        private const val PES_HEADER = 14
        private const val ADTS_FREQ_8000 = 11

        // Начало шкалы 1 с, чтобы первое время показа и PCR перед ним не ушли в минус
        private const val BASE_PTS = 90_000L
        private const val PCR_LEAD = 4_500L

        /** CRC-32/MPEG-2: полином 0x04C11DB7, начальное значение 0xFFFFFFFF, без отражения и итогового XOR */
        fun crc32Mpeg(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}
