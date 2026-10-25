package com.example.denmediaipcams.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Кодер AAC для звука записей со звуком G.711 (8 кГц, моно): G.711 A-law сначала декодируется в PCM
 * объектом [G711], затем этот кодер сжимает PCM в AAC-LC; готовые кадры отдаёт в [sink]. Общий для всех
 * конвертеров записей камер в MP4 (Hipcam/.265 и DVRIP) — вынесен из HxvtMp4Converter, чтобы не
 * дублировать код кодирования звука.
 */
internal class G711AacEncoder(private val sink: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun feed(pcm: ByteArray, ptsUs: Long) {
        var attempts = 0
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: throw IOException("Кодер звука не отдал буфер")
                buffer.clear()
                buffer.put(pcm)
                codec.queueInputBuffer(index, 0, pcm.size, ptsUs, 0)
                break
            }
            // Нет свободного входа: забираем готовое
            drain()
            if (++attempts > 500) throw IOException("Кодер звука не отвечает")
        }
        drain()
    }

    fun finish() {
        var attempts = 0
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain()
            if (++attempts > 500) return
        }
        var polls = 0
        while (polls++ < 500) {
            if (drain(timeoutUs = 10_000)) return
        }
    }

    /** true — кодер выдал признак конца потока */
    private fun drain(timeoutUs: Long = 0): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            if (index < 0) return false
            val buffer = codec.getOutputBuffer(index)
            // Служебные данные кодера (CODEC_CONFIG) уже заданы в формате дорожки
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                sink(buffer, info)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        const val SAMPLE_RATE = 8000
        private const val BIT_RATE = 32_000

        /** Формат дорожки: AudioSpecificConfig для AAC-LC, 8000 Гц, моно — 0x15 0x88 */
        fun trackFormat(): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x15, 0x88.toByte())))
            }
    }
}
