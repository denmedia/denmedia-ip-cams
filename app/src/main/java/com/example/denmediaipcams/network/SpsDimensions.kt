package com.example.denmediaipcams.network

/**
 * Размер картинки из SPS (без стартового кода): настоящий размер кадра, как его видит декодер, с учётом
 * обрезки (cropping). Нужен там, где поток не сообщает размер надёжно: в заголовке ключевого кадра DVRIP
 * ширина и высота хранятся в одном байте (в единицах по 8 пикселей), поэтому 2560 пикселей приходят как 512.
 */
internal object SpsDimensions {

    /** Размер в пикселях; null — SPS не удалось разобрать (тогда остаётся то, что сообщил заголовок) */
    data class Size(val width: Int, val height: Int)

    /** [nal] — NAL-блок SPS целиком, с заголовком NAL, но без стартового кода 00 00 00 01 */
    fun parse(nal: ByteArray, h265: Boolean): Size? = try {
        if (h265) parseH265(nal) else parseH264(nal)
    } catch (e: IndexOutOfBoundsException) {
        null
    }?.takeIf { it.width in 16..16384 && it.height in 16..16384 }

    private fun parseH265(nal: ByteArray): Size? {
        if (nal.size < 3) return null
        val r = BitReader(unescape(nal, headerSize = 2))
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.skip(1) // temporal_id_nesting
        // profile_tier_level: общий блок профиля (88 бит) и уровень (8 бит)
        r.skip(96)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.bits(1) == 1
            levelPresent[i] = r.bits(1) == 1
        }
        if (maxSubLayersMinus1 > 0) r.skip(2 * (8 - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1) // separate_colour_plane_flag
        val width = r.ue()
        val height = r.ue()
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (r.bits(1) == 1) {
            cropLeft = r.ue()
            cropRight = r.ue()
            cropTop = r.ue()
            cropBottom = r.ue()
        }
        val unitX = if (chroma == 1 || chroma == 2) 2 else 1
        val unitY = if (chroma == 1) 2 else 1
        return Size(width - unitX * (cropLeft + cropRight), height - unitY * (cropTop + cropBottom))
    }

    private fun parseH264(nal: ByteArray): Size? {
        if (nal.size < 5) return null
        val r = BitReader(unescape(nal, headerSize = 1))
        val profile = r.bits(8)
        r.skip(16) // constraint flags и level_idc
        r.ue() // seq_parameter_set_id
        var chroma = 1
        if (profile in HIGH_PROFILES) {
            chroma = r.ue()
            if (chroma == 3) r.skip(1)
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (r.bits(1) == 1) {
                for (i in 0 until if (chroma != 3) 8 else 12) {
                    if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1) == 1
        if (!frameMbsOnly) r.skip(1) // mb_adaptive_frame_field_flag
        r.skip(1) // direct_8x8_inference_flag
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (r.bits(1) == 1) {
            cropLeft = r.ue()
            cropRight = r.ue()
            cropTop = r.ue()
            cropBottom = r.ue()
        }
        val frameFactor = if (frameMbsOnly) 1 else 2
        val unitX = if (chroma == 1 || chroma == 2) 2 else 1
        val unitY = (if (chroma == 1) 2 else 1) * frameFactor
        return Size(
            widthMbs * 16 - unitX * (cropLeft + cropRight),
            heightMapUnits * frameFactor * 16 - unitY * (cropTop + cropBottom)
        )
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) {
                next = (last + r.se() + 256) % 256
            }
            if (next != 0) last = next
        }
    }

    /** Убирает байты предотвращения эмуляции стартового кода (00 00 03 → 00 00) и заголовок NAL */
    private fun unescape(nal: ByteArray, headerSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (i in headerSize until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = data[pos shr 3].toInt() and 0xFF
                value = (value shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return value
        }

        fun skip(count: Int) {
            pos += count
            if ((pos shr 3) > data.size) throw IndexOutOfBoundsException()
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros++
                if (zeros > 31) throw IndexOutOfBoundsException()
            }
            return if (zeros == 0) 0 else ((1 shl zeros) - 1 + bits(zeros))
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
