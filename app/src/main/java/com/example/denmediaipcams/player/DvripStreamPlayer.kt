package com.example.denmediaipcams.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.ArchiveException
import com.example.denmediaipcams.network.DvripClient
import com.example.denmediaipcams.network.DvripFileEntry
import com.example.denmediaipcams.network.DvripMediaFrame
import com.example.denmediaipcams.network.DvripRecording
import com.example.denmediaipcams.network.DvripStreamException
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Просмотр записи камеры XMEye/DVRIP «как стрим»: кадры приходят с камеры, упаковываются в MPEG-TS
 * ([MpegTsMuxer]) и по мере поступления идут в ExoPlayer — запись начинает играть почти сразу, без
 * скачивания целиком. Перемотка — по запросу к камере: она умеет начинать передачу с заданного времени
 * (OPPlayBack/DownloadStart со StartTime внутри файла), поэтому при перемотке поток просто перезапускается с нужной
 * секунды. Камера считает позицию по байтам файла, поэтому точность — порядка секунд (плюс до ближайшего ключевого кадра).
 *
 * Положение внутри записи — [positionMs]: смещение последнего запуска потока плюс позиция ExoPlayer.
 * Все публичные методы вызываются с главного потока.
 */
@OptIn(UnstableApi::class)
class DvripStreamPlayer(
    context: Context,
    private val camera: IpCamera,
    private val recording: DvripRecording,
    /** Сборщик ExoPlayer; подменяется в тестах (например, чтобы исключить негодный декодер эмулятора) */
    builder: ExoPlayer.Builder = ExoPlayer.Builder(context.applicationContext)
) {
    val exo: ExoPlayer = builder.build()

    /** Длительность записи, мс (по времени начала и конца из поиска файлов) */
    val durationMs: Long = recording.durationMs.coerceAtLeast(1)

    var isBuffering by mutableStateOf(true)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Запись доиграна до конца */
    var onEnded: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var feed: Feed? = null
    private var job: Job? = null
    private var baseMs = 0L
    private var generation = 0
    private var failedRestarts = 0
    private var released = false

    private val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)

    init {
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) failedRestarts = 0
                if (state == Player.STATE_ENDED) handleEnded()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(e: PlaybackException) {
                isBuffering = false
                error = e.localizedMessage ?: "Не удалось воспроизвести запись"
            }
        })
    }

    /** Текущее положение в записи, мс */
    fun positionMs(): Long = (baseMs + exo.currentPosition).coerceIn(0, durationMs)

    /** Запускает (или перезапускает) воспроизведение с позиции [positionMs] внутри записи */
    fun start(positionMs: Long) {
        if (released) return
        stopPipeline()
        error = null
        isBuffering = true
        val position = positionMs.coerceIn(0, durationMs)
        baseMs = position
        val newFeed = Feed()
        feed = newFeed
        val id = ++generation
        val factory = DataSource.Factory { FeedDataSource(newFeed) }
        exo.setMediaSource(
            ProgressiveMediaSource.Factory(factory, extractors).createMediaSource(MediaItem.fromUri(Uri.parse("dvrip://stream/$id")))
        )
        exo.prepare()
        exo.playWhenReady = true
        // Меньше секунды от начала — это просто начало записи; камере сдвиг нужен целыми секундами
        val from = if (position < 1_000) recording.start else recording.start.plusSeconds(position / 1_000)
        job = scope.launch { runPipeline(newFeed, from) }
    }

    fun seekTo(positionMs: Long) = start(positionMs)

    fun play() {
        error?.let { start(positionMs()); return }
        exo.play()
    }

    fun pause() = exo.pause()

    fun setVolume(volume: Float) {
        exo.volume = volume
    }

    fun release() {
        if (released) return
        released = true
        stopPipeline()
        scope.cancel()
        exo.release()
    }

    private fun stopPipeline() {
        feed?.close()
        job?.cancel()
        feed = null
        job = null
    }

    /**
     * ExoPlayer дошёл до конца потока: если запись кончилась сама — это конец ролика; если поток оборвался
     * (камера закрыла сессию, например, после долгой паузы) — продолжаем с того места, где остановились
     */
    private fun handleEnded() {
        // Запоздалое событие от потока, который уже заменён перемоткой: плеер к этому времени снова грузит новый
        if (exo.playbackState != Player.STATE_ENDED) return
        val current = feed
        if (current?.failure != null) {
            if (failedRestarts++ < MAX_RESTARTS) {
                Log.w(TAG, "поток оборвался (${current.failure}), продолжаю с ${positionMs()} мс")
                start(positionMs())
            } else {
                error = current.failure
            }
            return
        }
        onEnded?.invoke()
    }

    // --- конвейер: камера → кадры → TS → очередь для ExoPlayer ---

    private suspend fun runPipeline(feed: Feed, from: LocalDateTime) {
        val client = DvripClient(camera)
        var encoder: G711AacEncoder? = null
        var stream: DvripClient.DvripFrameStream? = null
        try {
            client.login()
            val file = DvripFileEntry(recording.start, recording.end, recording.channel, recording.fileName, recording.sizeBytes)
            stream = client.openPlayback(file, from)

            var muxer: MpegTsMuxer? = null
            // Пока не ясно, есть ли у камеры звук (от этого зависит таблица PMT), кадры копятся здесь
            val held = ArrayList<DvripMediaFrame>()
            var keySeen = false
            var audioSeen = false
            var videoAfterKey = 0
            var frames = 0

            fun handle(frame: DvripMediaFrame) {
                val m = muxer ?: return
                when (frame) {
                    is DvripMediaFrame.Video -> m.writeVideo(frame.ptsMs, frame.keyFrame, frame.data)
                    is DvripMediaFrame.Audio -> encoder?.feed(G711.alawToPcmBytes(frame.data, 0, frame.data.size), frame.ptsMs * 1000)
                }
            }

            while (true) {
                val frame = stream.nextFrame() ?: break
                if (++frames % 32 == 0) kotlin.coroutines.coroutineContext.ensureActive()
                if (feed.closed) return
                if (muxer != null) {
                    handle(frame)
                    continue
                }
                if (frame is DvripMediaFrame.Video) {
                    if (frame.keyFrame) keySeen = true else if (!keySeen) continue // кадры без опорного ключевого не показать
                    if (keySeen && !frame.keyFrame) videoAfterKey++
                } else if (!keySeen) {
                    continue
                } else {
                    audioSeen = true
                }
                held += frame
                if (keySeen && (audioSeen || videoAfterKey >= AUDIO_WAIT_FRAMES)) {
                    val codec = (held.first() as DvripMediaFrame.Video).codec
                    if (codec != "h264" && codec != "h265") throw IOException("Неподдерживаемый формат видео ($codec)")
                    val withAudio = audioSeen && runCatching {
                        G711AacEncoder { buffer, info ->
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            muxer?.writeAudio(info.presentationTimeUs / 1000, bytes)
                        }
                    }.onSuccess { encoder = it }.isSuccess
                    muxer = MpegTsMuxer(codec == "h265", withAudio) { feed.put(it) }
                    held.forEach(::handle)
                    held.clear()
                }
            }
            // Запись кончилась раньше, чем накопилось достаточно кадров: отдаём то, что есть, без звука
            if (muxer == null && keySeen) {
                val codec = (held.first() as DvripMediaFrame.Video).codec
                muxer = MpegTsMuxer(codec == "h265", false) { feed.put(it) }
                held.forEach(::handle)
            }
            if (muxer == null) throw IOException("В записи нет видео")
            encoder?.finish()
            feed.finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArchiveException) {
            feed.fail(e.message ?: "Камера не отвечает")
        } catch (e: DvripStreamException) {
            feed.fail(e.message ?: "Неизвестный формат потока")
        } catch (e: Exception) {
            Log.w(TAG, "конвейер просмотра остановлен: ${e.message}")
            feed.fail(e.message ?: "Не удалось воспроизвести запись")
        } finally {
            withContext(NonCancellable) {
                runCatching { stream?.stop() }
                client.close()
            }
            encoder?.release()
        }
    }

    /** Очередь готовых TS-данных между конвейером (пишет) и ExoPlayer (читает) */
    private class Feed {
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CHUNKS)

        @Volatile
        var closed = false
            private set

        /** Причина обрыва потока; null — запись закончилась сама */
        @Volatile
        var failure: String? = null
            private set

        /** Блокируется, пока ExoPlayer не заберёт данные: так камера не гонит поток быстрее, чем нужно плееру */
        fun put(chunk: ByteArray) {
            while (!closed) {
                if (queue.offer(chunk, 100, TimeUnit.MILLISECONDS)) return
            }
        }

        fun finish() = put(END)

        fun fail(message: String) {
            failure = message
            put(END)
        }

        fun close() {
            closed = true
            queue.clear()
        }

        /** Следующий кусок; [END] — данных больше не будет; null — истёк срок ожидания или читатель закрыт */
        fun take(): ByteArray? = if (closed) END else queue.poll(100, TimeUnit.MILLISECONDS)

        companion object {
            val END = ByteArray(0)
        }
    }

    /** Источник данных ExoPlayer поверх [Feed]: нет ни длины, ни перемотки — за это отвечает перезапуск потока */
    private class FeedDataSource(private val feed: Feed) : BaseDataSource(/* isNetwork = */ false) {
        private var current: ByteArray = Feed.END
        private var position = 0
        private var uri: Uri? = null
        private var ended = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            if (dataSpec.position != 0L) throw IOException("Перемотка внутри потока не поддерживается")
            uri = dataSpec.uri
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= current.size) {
                if (ended) return C.RESULT_END_OF_INPUT
                val next = feed.take() ?: continue
                if (next === Feed.END) {
                    ended = true
                    return C.RESULT_END_OF_INPUT
                }
                current = next
                position = 0
            }
            val count = minOf(length, current.size - position)
            System.arraycopy(current, position, buffer, offset, count)
            position += count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            if (uri != null) {
                uri = null
                transferEnded()
            }
        }
    }

    private companion object {
        const val TAG = "DvripStreamPlayer"
        const val QUEUE_CHUNKS = 96
        const val AUDIO_WAIT_FRAMES = 30
        const val MAX_RESTARTS = 3
    }
}
