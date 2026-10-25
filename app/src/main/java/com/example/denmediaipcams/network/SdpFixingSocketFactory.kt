package com.example.denmediaipcams.network

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.net.Socket
import javax.net.SocketFactory

/**
 * Фабрика сокетов для RtspMediaSource, исправляющая SDP «на лету».
 *
 * Многие недорогие камеры (HiSilicon / XMEye и аналоги) передают в a=fmtp параметры
 * sprop-vps / sprop-sps / sprop-pps (и sprop-parameter-sets для H.264) вместе со стартовым
 * кодом 00 00 00 01, что противоречит RFC 7798 / RFC 6184. ExoPlayer добавляет свой стартовый
 * код, неверно разбирает SPS и падает с ArrayIndexOutOfBoundsException в фоновом потоке
 * (что приводит к закрытию всего приложения). Здесь лишние стартовые коды удаляются,
 * а Content-Length ответа пересчитывается.
 */
class SdpFixingSocketFactory(private val onParamSetsLearned: (() -> Unit)? = null) : SocketFactory() {

    override fun createSocket(): Socket = SdpFixingSocket(onParamSetsLearned)

    override fun createSocket(host: String, port: Int): Socket =
        SdpFixingSocket(onParamSetsLearned).apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        SdpFixingSocket(onParamSetsLearned).apply {
            bind(java.net.InetSocketAddress(localHost, localPort))
            connect(java.net.InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        SdpFixingSocket(onParamSetsLearned).apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        SdpFixingSocket(onParamSetsLearned).apply {
            bind(java.net.InetSocketAddress(localAddress, localPort))
            connect(java.net.InetSocketAddress(address, port))
        }
}

private class SdpFixingSocket(onParamSetsLearned: (() -> Unit)?) : Socket() {
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
        Log.i("CamNet", "EXO connect $endpoint")
        super.connect(endpoint, timeout)
    }

    private var wrappedInput: InputStream? = null
    private var wrappedOutput: OutputStream? = null

    // Номера CSeq отправленных запросов, на которые ещё нет ответа
    private val trace = RtspTrace().also { it.onParamSetsLearned = onParamSetsLearned }

    @Synchronized
    override fun getInputStream(): InputStream =
        wrappedInput ?: SdpFixingInputStream(super.getInputStream(), trace).also { wrappedInput = it }

    @Synchronized
    override fun getOutputStream(): OutputStream =
        wrappedOutput ?: CSeqTrackingOutputStream(super.getOutputStream(), trace).also { wrappedOutput = it }
}

/** Что клиент уже отправил камере: нужно, чтобы исправлять её нестандартные ответы */
private class RtspTrace {
    /** Номера CSeq отправленных запросов, на которые ещё нет ответа */
    val pendingCSeqs = ConcurrentLinkedQueue<Int>()
    val describeUri = AtomicReference<String?>()
    /** Адреса дорожек, для которых отправлен SETUP */
    val setupUris = CopyOnWriteArrayList<String>()
    /** Первый interleaved-канал (RTP) каждой дорожки по адресу из SETUP */
    val setupChannels = ConcurrentHashMap<String, Int>()
    /** CSeq запросов PLAY, на которые ещё нет ответа */
    val playCSeqs: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** Вызывается, когда из потока получены настоящие SPS/PPS, отличающиеся от запомненных */
    @Volatile var onParamSetsLearned: (() -> Unit)? = null
}

/**
 * Запоминает CSeq, DESCRIBE, SETUP и PLAY исходящих RTSP-запросов. Запрос PLAY записи Hikvision исправляет:
 * камера берёт начало записи из `starttime` адреса только при `Range: clock=…`, а Media3 шлёт `Range: npt=0.000-`,
 * и тогда камера всегда играет с начала самой старой записи на карте.
 */
private class CSeqTrackingOutputStream(
    private val target: OutputStream,
    private val trace: RtspTrace
) : OutputStream() {
    override fun write(b: Int) = target.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0 && b[off] != '$'.code.toByte()) {
            val head = String(b, off, minOf(len, 1024), Charsets.ISO_8859_1)
            DESCRIBE_REGEX.find(head)?.groupValues?.get(1)?.let { trace.describeUri.set(it) }
            SETUP_REGEX.find(head)?.groupValues?.get(1)?.let { uri ->
                trace.setupUris.add(uri)
                INTERLEAVED_REGEX.find(head)?.groupValues?.get(1)?.toIntOrNull()?.let { trace.setupChannels[uri] = it }
            }
            val cseq = CSEQ_REGEX.find(head)?.groupValues?.get(1)?.toIntOrNull()
            if (cseq != null) {
                trace.pendingCSeqs.add(cseq)
                if (head.startsWith("PLAY ")) {
                    trace.playCSeqs.add(cseq)
                    withClockRange(String(b, off, len, Charsets.ISO_8859_1))?.let { fixed ->
                        val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                        target.write(bytes, 0, bytes.size)
                        return
                    }
                }
            }
        }
        target.write(b, off, len)
    }

    override fun flush() = target.flush()
    override fun close() = target.close()

    private companion object {
        val CSEQ_REGEX = Regex("""(?im)^CSeq:\s*(\d+)""")
        val DESCRIBE_REGEX = Regex("""^DESCRIBE\s+(\S+)""")
        val SETUP_REGEX = Regex("""^SETUP\s+(\S+)""")
        val INTERLEAVED_REGEX = Regex("""interleaved=(\d+)""")
        val PLAY_URI_REGEX = Regex("""^PLAY\s+(\S+)""")
        val START_TIME_REGEX = Regex("""[?&]starttime=(\d{8}T\d{6}Z)""")
        val END_TIME_REGEX = Regex("""[?&]endtime=(\d{8}T\d{6}Z)""")
        val RANGE_NPT_REGEX = Regex("""(?im)^Range:\s*npt=[^\r\n]*""")

        /** PLAY записи Hikvision с `Range: clock=<начало>-<конец>` из адреса; null — запрос менять не нужно */
        fun withClockRange(message: String): String? {
            val uri = PLAY_URI_REGEX.find(message)?.groupValues?.get(1) ?: return null
            val start = START_TIME_REGEX.find(uri)?.groupValues?.get(1) ?: return null
            val end = END_TIME_REGEX.find(uri)?.groupValues?.get(1) ?: return null
            if (!RANGE_NPT_REGEX.containsMatchIn(message)) return null
            return RANGE_NPT_REGEX.replace(message, "Range: clock=$start-$end")
        }
    }
}

/**
 * Пропускает RTSP-ответы через себя, пока не встретит первый ответ с SDP (DESCRIBE).
 * После этого переходит в режим прямой передачи — RTP-данные (interleaved по TCP)
 * идут без изменений и без дополнительной обработки.
 */
private class SdpFixingInputStream(
    source: InputStream,
    private val trace: RtspTrace
) : InputStream() {
    private val input = BufferedInputStream(source)
    private var pending = ByteArray(0)
    private var pendingPos = 0
    private var passthrough = false

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pendingPos < pending.size) {
            val n = minOf(len, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, b, off, n)
            pendingPos += n
            return n
        }
        if (passthrough) return input.read(b, off, len)

        pending = readNextMessage() ?: return -1
        pendingPos = 0
        return read(b, off, len)
    }

    override fun available(): Int =
        (pending.size - pendingPos) + if (passthrough) input.available() else 0

    override fun close() = input.close()

    private fun readNextMessage(): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        // Не текстовый RTSP-ответ (например, interleaved RTP '$') — дальше ничего не трогаем
        // Interleaved RTP/RTCP кадр: '$', канал, длина (2 байта), данные — передаём целиком
        if (first == '$'.code) {
            val frameHeader = ByteArray(3)
            if (!readFully(frameHeader)) return byteArrayOf(first.toByte())
            val length = ((frameHeader[1].toInt() and 0xFF) shl 8) or (frameHeader[2].toInt() and 0xFF)
            val payload = ByteArray(length)
            if (!readFully(payload)) return byteArrayOf(first.toByte()) + frameHeader
            val fixed = fixVideoPayload(payload)
            if (fixed.size != payload.size) {
                frameHeader[1] = (fixed.size shr 8).toByte()
                frameHeader[2] = fixed.size.toByte()
            }
            return byteArrayOf(first.toByte()) + frameHeader + fixed
        }
        if (first != 'R'.code) {
            Log.w(TAG, "Unexpected byte 0x${first.toString(16)} in RTSP stream, switching to passthrough")
            passthrough = true
            return byteArrayOf(first.toByte())
        }

        val header = ByteArrayOutputStream().apply { write(first) }
        var last4 = first
        while (true) {
            val c = input.read()
            if (c < 0) return header.toByteArray()
            header.write(c)
            last4 = (last4 shl 8) or c
            if (last4 == 0x0D0A0D0A) break
        }
        val headerText = header.toString(Charsets.ISO_8859_1.name())

        // Нормализация заголовков: гарантируем наличие CSeq (исправление для камер, которые опускают CSeq или пишут cseq в нижнем регистре)
        val baseHeader = if (!headerText.lines().any { it.startsWith("CSeq", ignoreCase = true) }) {
            headerText.replace("\r\n\r\n", "\r\nCSeq: ${trace.pendingCSeqs.poll() ?: 1}\r\n\r\n")
        } else {
            // headerText уже оканчивается пустой строкой (\r\n\r\n): отбрасываем хвостовые пустые элементы,
            // иначе лишние переводы строк ExoPlayer примет за новое сообщение с пустой стартовой строкой
            headerText.lines().dropLastWhile { it.isEmpty() }.joinToString("\r\n") { line ->
                if (line.startsWith("cseq:", ignoreCase = true)) {
                    line.substring(5).trim().toIntOrNull()?.let { trace.pendingCSeqs.remove(it) }
                    "CSeq:" + line.substring(5)
                } else {
                    line
                }
            } + "\r\n\r\n"
        }

        // Ответ SETUP с interleaved-каналами, но без «/TCP» в транспорте (Yoosee и аналоги): клиент иначе ждёт UDP
        val normalizedHeader = addMissingRtpInfo(baseHeader)
            .let { TRANSPORT_WITHOUT_TCP_REGEX.replace(it) { m -> "${m.groupValues[1]}/TCP${m.groupValues[2]}" } }

        val contentLength = CONTENT_LENGTH_REGEX.find(normalizedHeader)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val bodyText = String(body, 0, read, Charsets.ISO_8859_1)

        if (!bodyText.contains("m=")) {
            val (alignedHeader, lookahead) = if (normalizedHeader.contains("RTP-Info", ignoreCase = true)) {
                alignRtpInfoWithStream(normalizedHeader)
            } else {
                normalizedHeader to ByteArray(0)
            }
            return alignedHeader.toByteArray(Charsets.ISO_8859_1) + body.copyOf(read) + lookahead
        }

        // Ответ на DESCRIBE: исправляем SDP (остальные ответы идут выше без изменений тела)
        val fixedBody = fixSdp(bodyText, trace.describeUri.get())
        val fixedBytes = fixedBody.toByteArray(Charsets.ISO_8859_1)
        val fixedHeader = CONTENT_LENGTH_REGEX.replace(normalizedHeader) { "${it.groupValues[1]}${fixedBytes.size}" }
        if (fixedBody != bodyText) {
            Log.i(TAG, "SDP fixed: removed start codes from sprop parameter sets")
        }
        return fixedHeader.toByteArray(Charsets.ISO_8859_1) + fixedBytes
    }

    /**
     * Камеры вроде Yoosee отвечают на PLAY без RTP-Info, и Media3 падает с NullPointerException
     * при старте воспроизведения. Добавляем заголовок со всеми дорожками, для которых был SETUP.
     */
    private fun addMissingRtpInfo(header: String): String {
        val cseq = Regex("""(?im)^CSeq:\s*(\d+)""").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return header
        if (!trace.playCSeqs.remove(cseq)) return header
        if (!header.startsWith("RTSP/1.0 200")) return header
        // Часть камер пишет адрес дорожки без «//» (url=rtsp:192.168.1.201:554/...): у такого Uri нет пути, и Media3 падает
        if (header.contains("RTP-Info", ignoreCase = true)) {
            return RTP_INFO_BAD_URL_REGEX.replace(header) { "${it.groupValues[1]}rtsp://" }
        }
        val tracks = trace.setupUris.toList().distinct()
        if (tracks.isEmpty()) return header
        val rtpInfo = tracks.joinToString(",") { "url=$it;seq=0;rtptime=0" }
        return header.removeSuffix("\r\n\r\n") + "\r\nRTP-Info: $rtpInfo\r\n\r\n"
    }

    /**
     * Некоторые камеры в RTP-Info указывают seq/rtptime, не совпадающие с настоящими RTP-пакетами
     * (rtptime=8575110, а в потоке timestamp=385876012). Media3 берёт rtptime как начало отсчёта,
     * и кадры оказываются «на десятки минут в будущем» — видео не показывается. Читаем уже пришедшие
     * первые пакеты каждой дорожки и подставляем их seq/timestamp; прочитанные кадры отдаются дальше без изменений.
     * Возвращает исправленный заголовок и прочитанные кадры.
     */
    private fun alignRtpInfoWithStream(header: String): Pair<String, ByteArray> {
        val line = RTP_INFO_LINE_REGEX.find(header) ?: return header to ByteArray(0)
        val entries = line.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val channelOf = entries.map { entry ->
            RTP_INFO_URL_REGEX.find(entry)?.groupValues?.get(1)?.let { trace.setupChannels[it] }
        }
        val wanted = channelOf.filterNotNull().toSet()
        if (wanted.isEmpty()) return header to ByteArray(0)

        val seen = HashMap<Int, Pair<Int, Long>>()
        val lookahead = ByteArrayOutputStream()
        var frames = 0
        val deadline = System.currentTimeMillis() + RTP_LOOKAHEAD_WAIT_MS
        while (frames < 64 && seen.size < wanted.size) {
            if (input.available() < 4) {
                // Камера присылает первые RTP-пакеты чуть позже ответа PLAY
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(20)
                continue
            }
            input.mark(1)
            val first = input.read()
            if (first != '$'.code) {
                input.reset()
                break
            }
            val frameHeader = ByteArray(3)
            if (!readFully(frameHeader)) break
            val length = ((frameHeader[1].toInt() and 0xFF) shl 8) or (frameHeader[2].toInt() and 0xFF)
            val rawPayload = ByteArray(length)
            val complete = readFully(rawPayload)
            val payload = if (complete) fixVideoPayload(rawPayload) else rawPayload
            if (payload.size != rawPayload.size) {
                frameHeader[1] = (payload.size shr 8).toByte()
                frameHeader[2] = payload.size.toByte()
            }
            lookahead.write(first)
            lookahead.write(frameHeader)
            if (complete) lookahead.write(payload)
            frames++

            val channel = frameHeader[0].toInt() and 0xFF
            if (complete && channel in wanted && channel !in seen && payload.size >= 12) {
                val seq = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                val timestamp = ((payload[4].toLong() and 0xFF) shl 24) or ((payload[5].toLong() and 0xFF) shl 16) or
                    ((payload[6].toLong() and 0xFF) shl 8) or (payload[7].toLong() and 0xFF)
                seen[channel] = seq to timestamp
            }
        }
        if (seen.isEmpty()) return header to lookahead.toByteArray()

        // Дорожки, у которых пакет не пришёл, убираем: их значения заведомо неверны
        val fixedEntries = entries.indices.mapNotNull { i ->
            val channel = channelOf[i] ?: return@mapNotNull entries[i]
            val (seq, timestamp) = seen[channel] ?: return@mapNotNull null
            entries[i].replace(RTP_INFO_SEQ_REGEX, "seq=$seq").replace(RTP_INFO_TIME_REGEX, "rtptime=$timestamp")
        }
        Log.i(TAG, "RTP-Info aligned with stream: ${seen.entries.joinToString { "ch${it.key}: seq=${it.value.first} ts=${it.value.second}" }}")
        return header.replace(line.groupValues[1], fixedEntries.joinToString(",")) to lookahead.toByteArray()
    }

    /**
     * В склеенном первом фрагменте перед IDR лежат настоящие SPS и PPS камеры. Запоминаем их:
     * в SDP у таких камер параметры другие (иное разрешение и профиль), а декодер настраивается по SDP.
     * Если значения новые, просим переподключиться — SDP при этом будет уже исправлен.
     */
    private fun learnGluedParamSets(payload: ByteArray, idrNalIndex: Int) {
        val streamUri = trace.describeUri.get() ?: return
        // Границы стартовых кодов перед IDR: (начало кода, конец кода)
        val codes = ArrayList<Pair<Int, Int>>()
        var i = 14
        while (i + 2 < idrNalIndex) {
            if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 && payload[i + 2].toInt() == 1) {
                val begin = if (i > 14 && payload[i - 1].toInt() == 0) i - 1 else i
                codes += begin to i + 3
                i += 3
            } else {
                i++
            }
        }
        if (codes.isEmpty()) return

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (k in codes.indices) {
            val nal = if (k == 0) {
                // Заголовок первого NAL восстанавливается из FU-A: NRI индикатора + тип из заголовка фрагмента
                val header = ((payload[12].toInt() and 0xE0) or (payload[13].toInt() and 0x1F)).toByte()
                byteArrayOf(header) + payload.copyOfRange(14, codes[0].first)
            } else {
                payload.copyOfRange(codes[k - 1].second, codes[k].first)
            }
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        val foundSps = sps ?: return
        val foundPps = pps ?: return
        if (RtspParamSets.learn(streamUri, foundSps, foundPps)) {
            Log.i(TAG, "Real SPS/PPS learned from stream for $streamUri, reconnecting with fixed SDP")
            trace.onParamSetsLearned?.invoke()
        }
    }

    // Идёт ли сейчас пересобранный ключевой кадр (его продолжения помечены камерой типом 7/8)
    private var insideGluedKeyFrame = false

    /**
     * Некоторые камеры (Yoosee) отправляют ключевой кадр одним FU-A «типа 7 (SPS)», внутри которого
     * склеены `SPS 00 00 00 01 PPS 00 00 00 01 IDR…`. Media3 по такому типу не считает кадр ключевым
     * и картинка не появляется. Первый пакет пересобираем: убираем SPS/PPS (они нужны в SDP), оставляем
     * начало IDR как FU-A типа 5; продолжения помечаем типом 5. Правка прямо в RTP-пакете
     * (video, payload type 96, заголовок 12 байт). Возвращает пакет, возможно другой длины.
     */
    private fun fixVideoPayload(payload: ByteArray): ByteArray {
        if (payload.size < 14 || (payload[1].toInt() and 0x7F) != 96) return payload
        if ((payload[12].toInt() and 0x1F) != 28) return payload
        val fuHeader = payload[13].toInt() and 0xFF
        val nalType = fuHeader and 0x1F
        if (nalType != 7 && nalType != 8) return payload

        if (fuHeader and 0x80 == 0) {
            // Продолжение или конец фрагментированного кадра
            if (!insideGluedKeyFrame) return payload
            payload[13] = ((fuHeader and 0xE0) or 5).toByte()
            if (fuHeader and 0x40 != 0) insideGluedKeyFrame = false
            return payload
        }

        // Первый фрагмент: ищем последний стартовый код — за ним NAL, который реально фрагментируется
        var nalIndex = -1
        var i = 14
        while (i + 3 < payload.size) {
            if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 && payload[i + 2].toInt() == 1) {
                nalIndex = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (nalIndex < 0 || nalIndex >= payload.size) return payload
        val nalHeader = payload[nalIndex].toInt() and 0xFF
        val realType = nalHeader and 0x1F
        if (realType != 5 && realType != 1) return payload

        learnGluedParamSets(payload, nalIndex)

        val rebuilt = ByteArray(14 + payload.size - nalIndex - 1)
        System.arraycopy(payload, 0, rebuilt, 0, 12)
        rebuilt[12] = ((nalHeader and 0xE0) or 28).toByte()
        rebuilt[13] = (0x80 or realType).toByte()
        System.arraycopy(payload, nalIndex + 1, rebuilt, 14, payload.size - nalIndex - 1)
        insideGluedKeyFrame = fuHeader and 0x40 == 0
        Log.i(TAG, "Glued key frame rebuilt: ${payload.size} -> ${rebuilt.size} bytes (SPS/PPS removed, IDR type $realType)")
        return rebuilt
    }

    private fun readFully(buffer: ByteArray): Boolean {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    companion object {
        private const val TAG = "SdpFixingSocket"
        private const val RTP_LOOKAHEAD_WAIT_MS = 1500L
        private val RTP_INFO_LINE_REGEX = Regex("""(?im)^RTP-Info:\s*(.*?)\s*$""")
        private val RTP_INFO_URL_REGEX = Regex("""url=([^;,\s]+)""")
        private val RTP_INFO_SEQ_REGEX = Regex("""seq=\d+""")
        private val RTP_INFO_TIME_REGEX = Regex("""rtptime=\d+""")
        private val TRANSPORT_WITHOUT_TCP_REGEX = Regex("""(?im)^(Transport:\s*RTP/AVP)(;[^\r\n]*interleaved=)""")
        private val RTP_INFO_BAD_URL_REGEX = Regex("""(?i)(url=)rtsp:(?!//)""")
        private val CONTENT_LENGTH_REGEX = Regex("""(?im)^(content-length:\s*)(\d+)""")
        private val SPROP_REGEX = Regex("""(sprop-(?:vps|sps|pps|parameter-sets)=)([^;\s]+)""")

        /**
         * Исправляет SDP: убирает стартовые коды из sprop-параметров, а у адреса с параметрами
         * (rtsp://host/cam/realmonitor?channel=1&subtype=0) делает адрес трека абсолютным —
         * «base?query/track1», как ждут такие камеры; относительный путь Media3 они отвечают 400.
         */
        fun fixSdp(sdp: String, describeUri: String? = null): String =
            addMissingH265Fmtp(fixSdpLines(sdp, describeUri), describeUri?.let { H265ParamSets.get(it) })

        private fun fixSdpLines(sdp: String, describeUri: String?): String =
            sdp.lines().joinToString("\r\n") { line ->
                val trimmed = line.trim()
                if (describeUri != null && '?' in describeUri && trimmed.startsWith("a=control:", ignoreCase = true)) {
                    val control = trimmed.substring("a=control:".length).trim()
                    if (control.isNotEmpty() && control != "*" && "://" !in control) {
                        return@joinToString "a=control:${describeUri.trimEnd('/')}/${control.trimStart('/')}"
                    }
                }
                // Настоящие SPS/PPS, запомненные из потока (см. RtspParamSets): SDP у такой камеры описывает другой поток
                val learned = describeUri?.let { RtspParamSets.get(it) }
                if (learned != null && trimmed.startsWith("a=fmtp:", ignoreCase = true)) {
                    val (sps, pps) = learned
                    val payloadType = Regex("""(?i)^a=fmtp:(\d+)""").find(trimmed)?.groupValues?.get(1) ?: "96"
                    val profileLevel = "%02X%02X%02X".format(sps[1], sps[2], sps[3])
                    return@joinToString "a=fmtp:$payloadType packetization-mode=1;profile-level-id=$profileLevel;" +
                        "sprop-parameter-sets=${Base64.encodeToString(sps, Base64.NO_WRAP)},${Base64.encodeToString(pps, Base64.NO_WRAP)}"
                }
                if (!trimmed.startsWith("a=fmtp:", ignoreCase = true)) line
                else SPROP_REGEX.replace(line) { match ->
                    val fixedSets = match.groupValues[2].split(",").joinToString(",") { stripStartCode(it) }
                    match.groupValues[1] + fixedSets
                }
            }

        private fun stripStartCode(base64Value: String): String {
            val bytes = try {
                Base64.decode(base64Value, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return base64Value
            }
            val prefix = when {
                bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte() -> 4
                bytes.size > 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() -> 3
                else -> return base64Value
            }
            return Base64.encodeToString(bytes.copyOfRange(prefix, bytes.size), Base64.NO_WRAP)
        }
    }
}

/**
 * У H.265 без a=fmtp Media3 отказывается открывать поток («missing attribute fmtp»). Так отвечает,
 * например, Dahua на воспроизведение архива (запись хранится в H.265): VPS/SPS/PPS камера шлёт в самом потоке.
 * Добавляем нейтральный fmtp (sprop-max-don-diff=0 — значение по умолчанию по RFC 7798).
 */
internal fun addMissingH265Fmtp(sdp: String, sets: H265Sets? = null): String {
    val lines = sdp.lines()
    val h265Types = lines.mapNotNull { H265_RTPMAP_REGEX.find(it.trim())?.groupValues?.get(1) }
    if (h265Types.isEmpty()) return sdp
    val withFmtp = lines.mapNotNull { FMTP_PAYLOAD_REGEX.find(it.trim())?.groupValues?.get(1) }.toSet()
    val missing = h265Types.filter { it !in withFmtp }.toSet()
    if (missing.isEmpty()) return sdp
    return lines.joinToString("\r\n") { line ->
        val type = H265_RTPMAP_REGEX.find(line.trim())?.groupValues?.get(1)
        if (type != null && type in missing) "$line\r\n" + (sets?.fmtp(type) ?: "a=fmtp:$type sprop-max-don-diff=0") else line
    }
}

private val H265_RTPMAP_REGEX = Regex("""(?i)^a=rtpmap:(\d+)\s+H265/""")
private val FMTP_PAYLOAD_REGEX = Regex("""(?i)^a=fmtp:(\d+)""")
