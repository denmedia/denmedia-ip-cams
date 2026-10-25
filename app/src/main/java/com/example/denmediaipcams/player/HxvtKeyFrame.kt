package com.example.denmediaipcams.player

import java.io.ByteArrayOutputStream

/** Первый ключевой кадр записи Hipcam: всё, что нужно декодеру, чтобы получить одну картинку */
class HxvtKeyFrame(
    val width: Int,
    val height: Int,
    /** VPS, SPS и PPS подряд, со стартовыми кодами */
    val parameterSets: ByteArray,
    /** Весь кадр: параметры кодека, SEI и IDR, со стартовыми кодами */
    val data: ByteArray
) {
    companion object {
        private const val FILE_HEADER_SIZE = 16
        private const val FRAME_HEADER_SIZE = 16

        /**
         * Достаёт первый ключевой кадр из начала файла .265 (формат — в HxvtExtractor).
         * null — в переданных байтах кадр не нашёлся или оборван: нужно прочитать больше.
         */
        fun parse(file: ByteArray): HxvtKeyFrame? {
            if (file.size < FILE_HEADER_SIZE + FRAME_HEADER_SIZE || String(file, 0, 4, Charsets.ISO_8859_1) != "HXVT") return null
            val width = le32(file, 4)
            val height = le32(file, 8)
            val parameterSets = ByteArrayOutputStream()
            val frame = ByteArrayOutputStream()
            var started = false
            var pos = FILE_HEADER_SIZE
            while (pos + FRAME_HEADER_SIZE <= file.size) {
                val tag = String(file, pos, 4, Charsets.ISO_8859_1)
                val length = le32(file, pos + 4)
                if ((tag != "HXVF" && tag != "HXAF") || length < 0) return null
                val isKeyRecord = tag == "HXVF" && le32(file, pos + 12) == 1
                // Ключевой кадр — подряд идущие видеозаписи типа 1; следующая запись другого рода завершает его
                if (started && !isKeyRecord) break
                val start = pos + FRAME_HEADER_SIZE
                if (start + length > file.size) return null
                if (isKeyRecord) {
                    started = true
                    frame.write(file, start, length)
                    if (length >= 5 && isStartCode(file, start)) {
                        val nalType = (file[start + 4].toInt() shr 1) and 0x3F
                        if (nalType in 32..34) parameterSets.write(file, start, length)
                    }
                }
                pos = start + length
            }
            if (!started) return null
            // Кадр должен быть закончен записью другого рода (звуком или P-кадром): иначе IDR мог оборваться
            if (pos + FRAME_HEADER_SIZE > file.size) return null
            return HxvtKeyFrame(width, height, parameterSets.toByteArray(), frame.toByteArray())
        }

        private fun isStartCode(data: ByteArray, offset: Int) =
            data[offset].toInt() == 0 && data[offset + 1].toInt() == 0 && data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1

        private fun le32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
