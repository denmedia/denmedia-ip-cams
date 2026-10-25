package com.example.denmediaipcams.player

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/** Декодирует один ключевой кадр H.265 или H.264 (Annex-B) в уменьшенную картинку: для превью записей */
internal object HevcFrameDecoder {

    private const val TAG = "HevcFrameDecoder"
    private const val TIMEOUT_US = 2_000_000L
    private const val OUTPUT_POLL_US = 100_000L
    private const val DEADLINE_MS = 5_000L

    /**
     * null — кадр не удалось декодировать ни одним декодером (повреждённые данные, нет декодера H.265).
     * Сначала пробуются аппаратные декодеры, затем программные: у части устройств первый не справляется с большим кадром.
     */
    fun decode(
        width: Int,
        height: Int,
        parameterSets: ByteArray,
        frame: ByteArray,
        targetWidth: Int,
        mime: String = MediaFormat.MIMETYPE_VIDEO_HEVC
    ): Bitmap? {
        val decoders = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
                .sortedByDescending { it.isHardwareAccelerated }
                .map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
        for (name in decoders) {
            decodeWith(name, mime, width, height, parameterSets, frame, targetWidth)?.let { return it }
        }
        return null
    }

    private fun decodeWith(codecName: String, mime: String, width: Int, height: Int, parameterSets: ByteArray, frame: ByteArray, targetWidth: Int): Bitmap? {
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(parameterSets))
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, 0)
            codec.start()

            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) return null
            val input = codec.getInputBuffer(inputIndex) ?: return null
            if (frame.size > input.capacity()) return null
            input.clear()
            input.put(frame)
            codec.queueInputBuffer(inputIndex, 0, frame.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            // Конец потока: декодер отдаёт кадр сразу, не дожидаясь следующих (из-за переупорядочивания)
            val eosIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (eosIndex >= 0) codec.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            while (System.currentTimeMillis() < deadline) {
                val index = codec.dequeueOutputBuffer(info, OUTPUT_POLL_US)
                if (index < 0) continue
                try {
                    val image = if (info.size > 0) codec.getOutputImage(index) else null
                    if (image != null) return image.use { toBitmap(it, targetWidth) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Декодер $codecName не справился: ${e.message}")
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
        }
    }

    /** YUV 4:2:0 в RGB с прореживанием: берётся каждый step-й пиксель, этого хватает для превью */
    private fun toBitmap(image: Image, targetWidth: Int): Bitmap {
        val crop = image.cropRect
        val sourceWidth = crop.width()
        val sourceHeight = crop.height()
        val outWidth = minOf(targetWidth, sourceWidth)
        val outHeight = (sourceHeight.toLong() * outWidth / sourceWidth).toInt().coerceAtLeast(1)
        val planes = image.planes
        val y = planes[0].buffer
        val u = planes[1].buffer
        val v = planes[2].buffer
        val pixels = IntArray(outWidth * outHeight)
        for (row in 0 until outHeight) {
            val sy = crop.top + (row.toLong() * sourceHeight / outHeight).toInt()
            for (col in 0 until outWidth) {
                val sx = crop.left + (col.toLong() * sourceWidth / outWidth).toInt()
                val yy = y.get(sy * planes[0].rowStride + sx * planes[0].pixelStride).toInt() and 0xFF
                val uvRow = sy / 2
                val uvCol = sx / 2
                val uu = (u.get(uvRow * planes[1].rowStride + uvCol * planes[1].pixelStride).toInt() and 0xFF) - 128
                val vv = (v.get(uvRow * planes[2].rowStride + uvCol * planes[2].pixelStride).toInt() and 0xFF) - 128
                val luma = 1.164f * (yy - 16)
                val r = (luma + 1.596f * vv).toInt().coerceIn(0, 255)
                val g = (luma - 0.392f * uu - 0.813f * vv).toInt().coerceIn(0, 255)
                val b = (luma + 2.017f * uu).toInt().coerceIn(0, 255)
                pixels[row * outWidth + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }
}
