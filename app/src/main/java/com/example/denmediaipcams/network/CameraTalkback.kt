package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Вариант кодека G.711, которым камера принимает звук: 8 кГц, 1 байт на отсчёт */
enum class G711Law { MU_LAW, A_LAW }

/** Кодек G.711: 16-битный PCM → 8-битный отсчёт */
object G711 {
    private const val MU_BIAS = 0x84
    private const val MU_CLIP = 32635
    private val A_SEGMENT_END = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)

    fun encode(law: G711Law, sample: Int): Byte = if (law == G711Law.MU_LAW) muLaw(sample) else aLaw(sample)

    fun muLaw(sample: Int): Byte {
        var value = sample
        val sign = if (value < 0) { value = -value; 0x80 } else 0
        if (value > MU_CLIP) value = MU_CLIP
        value += MU_BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && value and mask == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (value shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun aLaw(sample: Int): Byte {
        var value = sample shr 3
        val mask: Int
        if (value >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            value = -value - 1
        }
        val segment = A_SEGMENT_END.indexOfFirst { value <= it }
        if (segment < 0) return (0x7F xor mask).toByte()
        var encoded = segment shl 4
        encoded = encoded or if (segment < 2) (value shr 1) and 0x0F else (value shr segment) and 0x0F
        return (encoded xor mask).toByte()
    }
}

/**
 * Камера приняла запрос, но закрыла соединение, не ответив: эта модель не умеет принимать звук таким способом
 * (так ведёт себя, например, Dahua SD-H2C). Повторять бесполезно — приложение прячет кнопку разговора для камеры.
 */
class TalkbackRefusedException(message: String) : IOException(message)

/** Открытый канал разговора с камерой: в него пишется звук G.711 (8 кГц, моно) в реальном времени */
interface TalkbackChannel : Closeable {
    val law: G711Law

    /** Передаёт [length] байт звука камере; бросает [IOException], если камера закрыла соединение */
    fun write(data: ByteArray, length: Int)
}

/**
 * Обратная связь: звук с микрофона планшета на динамик камеры. Стандартного способа нет (камеры не объявляют
 * ONVIF/RTSP backchannel), поэтому у каждого производителя свой: Hikvision — ISAPI TwoWayAudio ([HikvisionTalkback]).
 * Остальные камеры приложение не поддерживает. Dahua (`audio.cgi?action=postAudio`, [DahuaTalkback]) выключен: Dahua SD-H2C
 * принимает поток по HTTP, но звук не воспроизводит ни в одном из вариантов (проверено на слух 23.10.2026), поэтому кнопка
 * разговора для Dahua только вводила бы в заблуждение. Включить — добавить `|| camera.hasDahuaApi` в [isSupported] и ветку в [open].
 */
object CameraTalkback {

    fun isSupported(camera: IpCamera): Boolean =
        !camera.ipAddress.contains("://") && camera.username.isNotBlank() && camera.hikvisionArchive

    /** Открывает канал разговора; блокирующий вызов (Dispatchers.IO). При отказе камеры бросает [IOException] с понятным текстом */
    fun open(camera: IpCamera): TalkbackChannel =
        if (isSupported(camera)) HikvisionTalkback.open(camera) else throw IOException("Эта камера не поддерживает разговор")
}

/**
 * Hikvision: `PUT /ISAPI/System/TwoWayAudio/channels/{id}/open` выдаёт sessionId, звук идёт потоком в теле
 * `PUT …/audioData?sessionId=…`, `PUT …/close` завершает сеанс. Формат звука задан в настройках канала камеры
 * (`audioCompressionType`), поддерживаются G.711 u-law и a-law.
 */
internal object HikvisionTalkback {

    private const val TIMEOUT_MS = 6_000
    private const val BASE = "/ISAPI/System/TwoWayAudio/channels"

    internal fun parseLaw(compression: String?): G711Law? = when (compression?.trim()?.lowercase()) {
        "g.711ulaw" -> G711Law.MU_LAW
        "g.711alaw" -> G711Law.A_LAW
        else -> null
    }

    internal fun parseStatus(xml: String): String? =
        Regex("""<statusString>([^<]*)</statusString>""").find(xml)?.groupValues?.get(1)?.trim()

    fun open(camera: IpCamera): TalkbackChannel {
        val http = "http://${camera.ipAddress}:${camera.effectiveHttpPort}"
        fun request(method: String, path: String, body: String? = null) =
            CameraHttp.request(http + path, method, body, user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS)

        val info = request("GET", BASE)
        if (info.code == 0) throw IOException("Камера не отвечает: ${info.body}")
        if (!info.isSuccess) throw IOException("Камера не поддерживает разговор (HTTP ${info.code})")
        val id = Regex("""<id>(\d+)</id>""").find(info.body)?.groupValues?.get(1) ?: "1"
        val compression = Regex("""<audioCompressionType[^>]*>([^<]*)<""").find(info.body)?.groupValues?.get(1)
        val codec = parseLaw(compression)
            ?: throw IOException("Формат звука камеры (${compression ?: "неизвестен"}) не поддерживается: выберите G.711 в настройках камеры")

        val channelPath = "$BASE/$id"
        val opened = request("PUT", "$channelPath/open", "")
        val session = Regex("""<sessionId>([^<]+)</sessionId>""").find(opened.body)?.groupValues?.get(1)
        if (!opened.isSuccess || session == null) {
            val reason = parseStatus(opened.body) ?: "HTTP ${opened.code}"
            throw IOException("Камера не открыла канал разговора: $reason")
        }

        val stream = try {
            StreamingHttp.open(camera, "PUT", "$channelPath/audioData?sessionId=$session", "application/octet-stream")
        } catch (e: IOException) {
            request("PUT", "$channelPath/close", "")
            throw e
        }
        return object : TalkbackChannel {
            override val law = codec
            override fun write(data: ByteArray, length: Int) = stream.write(data, length)
            override fun close() {
                stream.close()
                // Сеанс закрывается всегда: незакрытый занимает единственный канал камеры
                request("PUT", "$channelPath/close", "")
            }
        }
    }
}

/**
 * Dahua: `POST /cgi-bin/audio.cgi?action=postAudio&httptype=…&channel=1`, тело — непрерывный поток G.711A. Камера отвечает
 * только по завершении потока. Бывает два вида тела: `singlepart` — сырой поток (`Content-Type: Audio/G.711A`, длина в
 * заголовке условная) и `multipart` — `multipart/x-mixed-replace`, чанками, где каждый кусок звука — отдельная часть
 * с `Content-Type: Audio/G.711A`. Часть моделей (например, Dahua SD-H2C) молча закрывает соединение на `singlepart`,
 * но принимает `multipart`, поэтому пробуются оба.
 */
internal object DahuaTalkback {

    private const val PATH = "/cgi-bin/audio.cgi?action=postAudio&httptype="
    private const val BOUNDARY = "audio-boundary"

    /** Есть ли у камеры выход звука: `devAudioOutput.cgi?action=getCollect` → `result=N` (число выходов); null — камера не ответила */
    internal fun parseOutputs(body: String): Int? =
        Regex("""(?m)^\s*result=(\d+)\s*$""").find(body)?.groupValues?.get(1)?.toIntOrNull()

    fun open(camera: IpCamera): TalkbackChannel {
        val outputs = CameraHttp.request(
            "http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/devAudioOutput.cgi?action=getCollect",
            user = camera.username, pass = camera.password, timeoutMs = 4_000
        )
        if (outputs.code == 0) throw IOException("Камера не отвечает: ${outputs.body}")
        if (outputs.isSuccess && parseOutputs(outputs.body) == 0) throw IOException("У камеры нет динамика")

        val stream = try {
            StreamingHttp.open(camera, "POST", PATH + "singlepart&channel=1", "Audio/G.711A")
        } catch (_: TalkbackRefusedException) {
            StreamingHttp.open(
                camera, "POST", PATH + "multipart&channel=1", "multipart/x-mixed-replace; boundary=$BOUNDARY",
                StreamingHttp.Framing.MULTIPART_CHUNKED, partType = "Audio/G.711A", boundary = BOUNDARY
            )
        }
        return object : TalkbackChannel {
            override val law = G711Law.A_LAW
            override fun write(data: ByteArray, length: Int) = stream.write(data, length)
            override fun close() = stream.close()
        }
    }
}

/**
 * Запрос с бесконечным телом: звук пишется в сокет, пока идёт разговор. HttpURLConnection для этого не годится
 * (Digest нельзя повторить после 401, когда тело уже отправляется), поэтому свежий nonce берётся отдельным запросом
 * без тела, а потоковый запрос сразу уходит с нужным заголовком Authorization.
 */
internal class StreamingHttp private constructor(
    private val socket: Socket,
    private val framing: Framing,
    private val partType: String,
    private val boundary: String
) : Closeable {

    /** Как звук укладывается в тело запроса */
    enum class Framing {
        /** Байты звука идут в тело как есть */
        RAW,

        /** Тело — чанки (Transfer-Encoding: chunked), в каждом одна часть multipart со своим Content-Type и Content-Length */
        MULTIPART_CHUNKED
    }

    private val out = socket.getOutputStream()

    fun write(data: ByteArray, length: Int) {
        try {
            when (framing) {
                Framing.RAW -> out.write(data, 0, length)
                Framing.MULTIPART_CHUNKED -> {
                    val part = "\r\n--$boundary\r\nContent-Type: $partType\r\nContent-Length: $length\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                    val chunk = java.io.ByteArrayOutputStream(part.size + length + 16)
                    chunk.write("${(part.size + length).toString(16)}\r\n".toByteArray(Charsets.ISO_8859_1))
                    chunk.write(part)
                    chunk.write(data, 0, length)
                    chunk.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.write(chunk.toByteArray())
                }
            }
            out.flush()
        } catch (e: IOException) {
            throw IOException("Камера закрыла канал разговора: ${e.message}", e)
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) { }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val EARLY_REPLY_WAIT_MS = 400
        private const val UNBOUNDED_LENGTH = 9_999_999

        fun open(
            camera: IpCamera, method: String, path: String, contentType: String,
            framing: Framing = Framing.RAW, partType: String = contentType, boundary: String = ""
        ): StreamingHttp {
            val host = camera.ipAddress
            val port = camera.effectiveHttpPort
            android.util.Log.i("CamNet", "HTTP stream $method http://$host:$port${path.substringBefore('?')}")
            val challenge = fetchChallenge(host, port, method, path)
            val auth = challenge?.let { NetworkTester.buildAuthHeader(it, camera.username, camera.password, method, path) }

            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                val head = buildString {
                    append("$method $path HTTP/1.1\r\nHost: $host:$port\r\n")
                    if (auth != null) append("Authorization: $auth\r\n")
                    append("Content-Type: $contentType\r\n")
                    append(if (framing == Framing.RAW) "Content-Length: $UNBOUNDED_LENGTH\r\n" else "Transfer-Encoding: chunked\r\n")
                    append("Connection: keep-alive\r\nCache-Control: no-cache\r\n\r\n")
                }
                socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
                checkEarlyReply(socket)
            } catch (e: IOException) {
                try { socket.close() } catch (_: IOException) { }
                throw if (e is TalkbackRefusedException || e.message?.startsWith("Камера") == true) e
                else IOException("Не удалось открыть канал разговора: ${e.message}", e)
            }
            return StreamingHttp(socket, framing, partType, boundary)
        }

        /** Challenge WWW-Authenticate от камеры (Digest предпочтительнее); null — авторизация не нужна */
        private fun fetchChallenge(host: String, port: Int, method: String, path: String): String? {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                s.getOutputStream().write(
                    "$method $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                val head = readHead(s.getInputStream())
                if (!head.startsWith("HTTP/1.1 401") && !head.startsWith("HTTP/1.0 401")) return null
                val challenges = head.lines().filter { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
                    .map { it.substringAfter(':').trim() }
                return challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) } ?: challenges.firstOrNull()
            }
        }

        /** Ответ камеры сразу после заголовков: 2xx и тишина — норма; 4xx/5xx или закрытое соединение — отказ */
        private fun checkEarlyReply(socket: Socket) {
            socket.soTimeout = EARLY_REPLY_WAIT_MS
            try {
                val head = readHead(socket.getInputStream())
                val code = Regex("""^HTTP/\d\.\d (\d{3})""").find(head)?.groupValues?.get(1)?.toIntOrNull()
                if (code == null) throw TalkbackRefusedException("Камера закрыла соединение, не приняв звук: эта модель не поддерживает разговор по такому протоколу")
                when {
                    code == 401 || code == 403 -> throw IOException("Камера отклонила логин и пароль или у пользователя нет прав на разговор (HTTP $code)")
                    code !in 200..299 -> throw IOException("Камера отказала в разговоре (HTTP $code)")
                }
            } catch (_: SocketTimeoutException) {
                // Камера ждёт звук и ничего не отвечает — так и должно быть
            } catch (e: java.net.SocketException) {
                // Соединение сброшено, не дождавшись звука: то же молчаливое закрытие, только жёсткое
                throw TalkbackRefusedException("Камера оборвала соединение, не приняв звук: эта модель не поддерживает разговор по такому протоколу")
            } finally {
                socket.soTimeout = 0
            }
        }

        private fun readHead(input: InputStream): String {
            val buffer = StringBuilder()
            while (buffer.length < MAX_HEAD && !buffer.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                buffer.append(b.toChar())
            }
            return buffer.toString()
        }

        private const val MAX_HEAD = 8_192
    }
}
