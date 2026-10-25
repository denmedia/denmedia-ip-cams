package com.example.denmediaipcams.player

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraTalkback
import com.example.denmediaipcams.network.G711
import com.example.denmediaipcams.network.G711Law
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Разговор с камерой: микрофон планшета → G.711 (8 кГц, моно) → динамик камеры, пока корутина не отменена.
 * Нужно разрешение RECORD_AUDIO (проверяет вызывающий).
 */
internal object TalkbackRecorder {

    private const val CODEC_RATE = 8_000
    private const val FRAME_MS = 40

    /** Микрофон, который удалось открыть, и во сколько раз его частота выше 8 кГц */
    private class Microphone(val record: AudioRecord, val rate: Int) {
        val decimation: Int get() = rate / CODEC_RATE
    }

    /**
     * Открывает канал разговора и передаёт звук, пока не будет отмена или ошибка. [onTalking] вызывается, когда канал
     * открыт и микрофон пишет. Ошибки — [IOException] с текстом для пользователя.
     */
    suspend fun run(camera: IpCamera, onTalking: () -> Unit) = withContext(Dispatchers.IO) {
        val channel = CameraTalkback.open(camera)
        try {
            val mic = openMicrophone()
            try {
                mic.record.startRecording()
                if (mic.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Микрофон занят другим приложением")
                onTalking()
                stream(mic, channel.law) { data, length -> channel.write(data, length) }
            } finally {
                mic.record.release()
            }
        } finally {
            channel.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): Microphone {
        for (rate in intArrayOf(CODEC_RATE, 2 * CODEC_RATE)) {
            val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) continue
            // Источник «голосовая связь» включает подавление эха и шума, где оно есть
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes, rate * 2 / 5)
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) return Microphone(record, rate)
            record.release()
        }
        throw IOException("Не удалось открыть микрофон")
    }

    private suspend fun stream(mic: Microphone, law: G711Law, send: (ByteArray, Int) -> Unit) {
        val decimation = mic.decimation
        val pcm = ShortArray(mic.rate * FRAME_MS / 1000)
        val encoded = ByteArray(pcm.size / decimation)
        val context = currentCoroutineContext()
        while (true) {
            context.ensureActive()
            var read = 0
            while (read < pcm.size) {
                val n = mic.record.read(pcm, read, pcm.size - read)
                if (n < 0) throw IOException("Ошибка чтения с микрофона ($n)")
                read += n
            }
            encode(pcm, decimation, law, encoded)
            send(encoded, encoded.size)
        }
    }

    /** Понижает частоту в [decimation] раз усреднением и кодирует в G.711 */
    internal fun encode(pcm: ShortArray, decimation: Int, law: G711Law, out: ByteArray) {
        for (i in out.indices) {
            var sum = 0
            for (k in 0 until decimation) sum += pcm[i * decimation + k]
            out[i] = G711.encode(law, sum / decimation)
        }
    }
}
