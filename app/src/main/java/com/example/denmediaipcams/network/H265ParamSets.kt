package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** VPS, SPS и PPS потока H.265 (NAL-единицы с двухбайтным заголовком, без стартовых кодов) */
class H265Sets(val vps: ByteArray, val sps: ByteArray, val pps: ByteArray) {
    /** Строка a=fmtp для SDP */
    fun fmtp(payloadType: String): String {
        val encoder = java.util.Base64.getEncoder()
        return "a=fmtp:$payloadType sprop-vps=${encoder.encodeToString(vps)};" +
            "sprop-sps=${encoder.encodeToString(sps)};sprop-pps=${encoder.encodeToString(pps)}"
    }
}

/**
 * Параметры кодека H.265 для потоков, у которых SDP их не содержит. Так отвечает, например, Dahua на
 * воспроизведение архива (запись хранится в H.265): VPS/SPS/PPS камера шлёт только внутри потока,
 * а Media3 без них в SDP не открывает видео («missing attribute fmtp» / «missing sprop-vps»).
 *
 * [prepare] коротко подключается к потоку, читает первые RTP-пакеты, достаёт параметры и запоминает их
 * по адресу потока; [SdpFixingSocketFactory] подставляет их в SDP при настоящем подключении плеера.
 */
object H265ParamSets {

    private const val TAG = "H265ParamSets"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 3_000
    private const val COLLECT_TIMEOUT_MS = 6_000L

    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34
    private const val NAL_AGGREGATION = 48

    private val cache = ConcurrentHashMap<String, H265Sets>()

    /** Параметры потока по его адресу без логина и пароля (rtsp://host:port/path?query) */
    fun get(streamUri: String): H265Sets? = cache[streamUri]

    /**
     * Узнаёт параметры H.265 потока камеры. Если поток не H.265 или SDP уже содержит параметры —
     * ничего не делает. true — параметры известны (найдены сейчас или раньше).
     */
    suspend fun prepare(camera: IpCamera): Boolean = withContext(Dispatchers.IO) {
        val uri = camera.rtspUrlWithoutCredentials
        if (cache.containsKey(uri)) return@withContext true
        try {
            val sets = fetch(camera, uri) ?: return@withContext false
            cache[uri] = sets
            true
        } catch (e: IOException) {
            Log.w(TAG, "Could not read H.265 parameter sets from $uri: ${e.message}")
            false
        }
    }

    private class Response(val code: Int, val headers: Map<String, String>, val body: String)

    /** Минимальный RTSP-клиент: одно соединение, запросы с Digest-авторизацией, затем чтение RTP по TCP */
    private class Session(private val socket: Socket, private val user: String, private val pass: String) {
        val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private var cseq = 0
        private var challenge: String? = null
        var sessionId: String? = null

        private fun readLine(): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.write(b)
            }
        }

        private fun readResponse(): Response {
            val status = readLine() ?: throw IOException("Камера закрыла соединение без ответа")
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Некорректный ответ RTSP: $status")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(":").trim().lowercase()] = line.substringAfter(":").trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return Response(code, headers, String(body, 0, read, Charsets.ISO_8859_1))
        }

        /** Запрос; при 401 повторяется с авторизацией по вызову Digest/Basic */
        fun request(method: String, uri: String, vararg extra: String): Response {
            fun send(auth: String?) {
                val text = buildString {
                    append("$method $uri RTSP/1.0\r\nCSeq: ${++cseq}\r\nUser-Agent: DenMediaIPCams\r\n")
                    sessionId?.let { append("Session: $it\r\n") }
                    extra.forEach { append("$it\r\n") }
                    auth?.let { append("Authorization: $it\r\n") }
                    append("\r\n")
                }
                output.write(text.toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }

            val known = challenge
            send(known?.let { NetworkTester.buildAuthHeader(it, user, pass, method, uri) })
            val response = readResponse()
            if (response.code != 401 || (user.isBlank() && pass.isBlank())) return response
            val newChallenge = response.headers["www-authenticate"] ?: return response
            challenge = newChallenge
            send(NetworkTester.buildAuthHeader(newChallenge, user, pass, method, uri))
            return readResponse()
        }

        fun readFully(length: Int): ByteArray {
            val data = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(data, read, length - read)
                if (n < 0) throw IOException("Поток оборвался")
                read += n
            }
            return data
        }
    }

    private fun fetch(camera: IpCamera, uri: String): H265Sets? {
        val port = camera.effectiveRtspPort
        Socket().use { socket ->
            socket.connect(InetSocketAddress(camera.ipAddress, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val session = Session(socket, camera.username, camera.password)

            val describe = session.request("DESCRIBE", uri, "Accept: application/sdp")
            if (describe.code != 200) throw IOException("DESCRIBE: RTSP ${describe.code}")

            // Нужна видеодорожка H.265 без параметров в SDP
            val sdpLines = describe.body.lines().map { it.trim() }
            val videoStart = sdpLines.indexOfFirst { it.startsWith("m=video") }
            if (videoStart < 0) return null
            val videoLines = sdpLines.drop(videoStart + 1).takeWhile { !it.startsWith("m=") }
            val payloadType = sdpLines[videoStart].split(" ").getOrNull(3) ?: return null
            val isH265 = videoLines.any { it.startsWith("a=rtpmap:$payloadType ", ignoreCase = true) && it.contains("H265/", ignoreCase = true) }
            val hasParams = videoLines.any { it.startsWith("a=fmtp:$payloadType ", ignoreCase = true) && it.contains("sprop-vps", ignoreCase = true) }
            if (!isH265 || hasParams) return null

            val control = videoLines.firstOrNull { it.startsWith("a=control:", ignoreCase = true) }
                ?.substringAfter(":")?.trim().orEmpty()
            val setupUri = when {
                control.isEmpty() || control == "*" -> uri
                "://" in control -> control
                else -> (describe.headers["content-base"] ?: uri).trimEnd('/') + "/" + control.trimStart('/')
            }

            val setup = session.request("SETUP", setupUri, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
            if (setup.code != 200) throw IOException("SETUP: RTSP ${setup.code}")
            session.sessionId = setup.headers["session"]?.substringBefore(";")?.trim()

            val play = session.request("PLAY", uri, "Range: npt=0.000-")
            if (play.code != 200) throw IOException("PLAY: RTSP ${play.code}")

            val found = collectParamSets(session)
            runCatching { session.request("TEARDOWN", uri) }
            if (found != null) Log.i(TAG, "H.265 parameter sets learned for $uri (vps=${found.vps.size}, sps=${found.sps.size}, pps=${found.pps.size} bytes)")
            return found
        }
    }

    /** Читает RTP по TCP (кадры вида $ канал длина), пока не найдены VPS, SPS и PPS */
    private fun collectParamSets(session: Session): H265Sets? {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val deadline = System.currentTimeMillis() + COLLECT_TIMEOUT_MS

        fun handleNal(data: ByteArray, from: Int, to: Int) {
            if (to - from < 3) return
            when ((data[from].toInt() shr 1) and 0x3F) {
                NAL_VPS -> vps = data.copyOfRange(from, to)
                NAL_SPS -> sps = data.copyOfRange(from, to)
                NAL_PPS -> pps = data.copyOfRange(from, to)
                NAL_AGGREGATION -> {
                    // Пакет агрегации: после двухбайтного заголовка идут [размер 2 байта][NAL]...
                    var i = from + 2
                    while (i + 2 <= to) {
                        val size = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                        i += 2
                        if (size <= 2 || i + size > to) break
                        when ((data[i].toInt() shr 1) and 0x3F) {
                            NAL_VPS -> vps = data.copyOfRange(i, i + size)
                            NAL_SPS -> sps = data.copyOfRange(i, i + size)
                            NAL_PPS -> pps = data.copyOfRange(i, i + size)
                        }
                        i += size
                    }
                }
            }
        }

        while (System.currentTimeMillis() < deadline && !(vps != null && sps != null && pps != null)) {
            val first = session.input.read()
            if (first < 0) break
            if (first != '$'.code) continue
            val channel = session.input.read()
            val length = (session.input.read() shl 8) or session.input.read()
            if (channel < 0 || length <= 0) break
            val packet = session.readFully(length)
            if (channel != 0 || packet.size < 14) continue
            // Заголовок RTP: 12 байт + CSRC (CC×4) + необязательное расширение
            var offset = 12 + (packet[0].toInt() and 0x0F) * 4
            if (packet[0].toInt() and 0x10 != 0) {
                if (packet.size < offset + 4) continue
                offset += 4 + (((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)) * 4
            }
            handleNal(packet, offset, packet.size)
        }

        val v = vps
        val s = sps
        val p = pps
        return if (v != null && s != null && p != null) H265Sets(v, s, p) else null
    }
}
