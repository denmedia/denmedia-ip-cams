package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.KnownPort
import com.example.denmediaipcams.model.PortKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Порты, подобранные для камеры: null — подходящего порта не найдено */
data class PortSelection(val rtsp: Int?, val http: Int?, val onvif: Int?, val dahua: Int?, val dvrip: Int?)

/**
 * Определение открытых портов камеры и протокола на каждом из них.
 * Порт считается RTSP / HTTP / ONVIF только если камера ответила на соответствующий запрос,
 * а не потому, что у порта «стандартный» номер.
 */
object PortDetector {

    /** Порты, которые обычно используют IP-камеры; остальные проверяет полное сканирование */
    val CANDIDATE_PORTS = listOf(
        80, 81, 82, 88, 443, 554, 555, 888, 2020, 5000, 5544, 6036, 7080, 8000, 8001, 8080, 8081, 8088,
        8090, 8443, 8554, 8555, 8888, 8899, 8999, 9000, 10080, 10554, 34567, 37777, 37778
    )

    /** Порядок предпочтения ONVIF-портов: если ONVIF отвечает на нескольких, берётся первый из списка */
    private val ONVIF_PREFERRED_PORTS = listOf(80, 8080, 8899, 2020, 5000, 8000, 888, 8090, 7080, 8999, 9000, 10080, 8081, 8888)

    /** Закрытые проприетарные порты Dahua: протокол не определяем, чтобы не слать в них чужие запросы */
    private val DAHUA_SDK_PORTS = setOf(37777, 37778)

    /**
     * Закрытый протокол XMEye / Sofia (DVRIP): произвольные байты в этот порт (протокольные пробы с
     * чужим форматом команд) вешают или перезагружают некоторые прошивки — поэтому порт помечается по
     * номеру, без попытки действительно поговорить с ним (как и DAHUA_SDK_PORTS ниже). Настоящая проверка —
     * попытка входа с паролем камеры — делается отдельно, только когда он уже известен (см. IpCamera.dvripArchive,
     * DvripArchive.isSupported, MainViewModel.observeDvripArchiveDetection), а не этим пассивным автосканом.
     */
    private val XMEYE_SDK_PORTS = setOf(34567)

    /** Одновременных проверок портов одной камеры: слабые камеры не выдерживают всплеска соединений */
    private const val MAX_PARALLEL_PORTS = 3

    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 1500
    private const val MAX_RESPONSE_BYTES = 8 * 1024

    /** Проверяет порты кандидатов (и extraPorts) и возвращает открытые, по возрастанию номера */
    suspend fun detect(
        host: String,
        extraPorts: Collection<Int> = emptyList(),
        onProgress: (String) -> Unit = {}
    ): List<KnownPort> = classifyAll(host, CANDIDATE_PORTS + extraPorts, onProgress)

    /** Определяет протокол на каждом из заданных портов; закрытые отбрасываются */
    suspend fun classifyAll(
        host: String,
        candidates: Collection<Int>,
        onProgress: (String) -> Unit = {}
    ): List<KnownPort> = coroutineScope {
        val cleanHost = cleanHost(host)
        if (cleanHost.isBlank()) return@coroutineScope emptyList()
        val ports = candidates.filter { it in 1..65535 }.distinct()
        val checked = AtomicInteger(0)
        val semaphore = Semaphore(MAX_PARALLEL_PORTS)
        ports.map { port ->
            async(Dispatchers.IO) {
                val result = semaphore.withPermit { classifyBlocking(cleanHost, port) }
                onProgress("Проверка портов: ${checked.incrementAndGet()}/${ports.size}")
                result
            }
        }.awaitAll().filterNotNull().sortedBy { it.port }
    }

    /**
     * ONVIF на порту, который не отвечает без авторизации: часть камер (HiSilicon) сразу закрывает соединение даже на
     * GetSystemDateAndTime, поэтому проверка без логина считает такой порт неопознанным. С логином и паролем на каждом
     * неопознанном порту делается одна попытка ONVIF; без пароля не проверяется — слать пустые данные бессмысленно.
     * Возвращает первый порт, где камера ответила как ONVIF, или null.
     */
    suspend fun findAuthenticatedOnvifPort(host: String, candidates: Collection<Int>, user: String, pass: String): Int? =
        withContext(Dispatchers.IO) {
            if (user.isBlank() && pass.isBlank()) return@withContext null
            val cleanHost = cleanHost(host)
            if (cleanHost.isBlank()) return@withContext null
            candidates.filter { it in 1..65535 }.distinct().firstOrNull { port ->
                try {
                    OnvifClient.discover(cleanHost, port, user, pass) != null
                } catch (_: Exception) {
                    false
                }
            }
        }

    /** Один порт: null — закрыт, иначе список подтверждённых протоколов (может быть пустым) */
    suspend fun classify(host: String, port: Int): KnownPort? = withContext(Dispatchers.IO) {
        classifyBlocking(cleanHost(host), port)
    }

    /** Проверка порта в поле настроек: открыт ли он и отвечает ли нужным протоколом */
    suspend fun verify(host: String, port: Int, expected: PortKind): PortPingStatus {
        val open = NetworkTester.pingPort(host, port)
        if (open is PortPingStatus.Error) return open
        val known = classify(host, port)
        return when {
            known == null -> PortPingStatus.Error("Порт $port закрылся во время проверки")
            expected in known.kinds -> PortPingStatus.Success
            known.kinds.isEmpty() -> PortPingStatus.Error(
                "Порт $port открыт, но не ответил как ${expected.label}. Возможно, это другой сервис или нужна другая настройка камеры."
            )
            else -> PortPingStatus.Error(
                "Порт $port открыт, но отвечает как ${known.kinds.joinToString(", ") { it.label }}, а не ${expected.label}."
            )
        }
    }

    /** Лучший порт для каждого назначения среди проверенных; при нескольких — самый «стандартный» */
    fun select(ports: List<KnownPort>): PortSelection {
        fun pick(kind: PortKind, preferred: List<Int>): Int? {
            val candidates = ports.filter { kind in it.kinds }.map { it.port }
            return preferred.firstOrNull { it in candidates } ?: candidates.minOrNull()
        }
        return PortSelection(
            rtsp = pick(PortKind.RTSP, listOf(554, 8554, 555, 10554)),
            http = pick(PortKind.HTTP, listOf(80, 8080, 8000, 81, 88, 8888)),
            onvif = pick(PortKind.ONVIF, ONVIF_PREFERRED_PORTS),
            dahua = pick(PortKind.DAHUA_SDK, listOf(37777, 37778)),
            dvrip = pick(PortKind.DVRIP, listOf(34567))
        )
    }

    private fun cleanHost(host: String): String = host.removePrefix("rtsp://")
        .removePrefix("http://")
        .removePrefix("https://")
        .substringAfter("@")
        .substringBefore("/")
        .substringBefore(":")
        .trim()

    private fun classifyBlocking(host: String, port: Int): KnownPort? {
        if (!isOpen(host, port)) return null
        if (port in DAHUA_SDK_PORTS) return KnownPort(port, setOf(PortKind.DAHUA_SDK))
        if (port in XMEYE_SDK_PORTS) return KnownPort(port, setOf(PortKind.DVRIP))

        val kinds = linkedSetOf<PortKind>()

        // RTSP-сервер отвечает на OPTIONS строкой RTSP/1.0; HTTP-сервер на тот же запрос отвечает HTTP-ошибкой
        val rtspReply = exchange(host, port, "OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: DenMediaIPCams\r\n\r\n")
        when {
            rtspReply.startsWith("RTSP/") -> kinds += PortKind.RTSP
            rtspReply.startsWith("HTTP/") -> kinds += PortKind.HTTP
            isTls(rtspReply) -> kinds += PortKind.HTTPS
        }

        // Ответ RTSP не исключает HTTP на том же порту (Dahua отдаёт и RTSP, и веб/ONVIF на порту 80),
        // а часть веб-серверов молчит на непонятный запрос — поэтому HTTP проверяем всегда обычным запросом
        if (PortKind.HTTP !in kinds && PortKind.HTTPS !in kinds) {
            val httpReply = exchange(host, port, "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
            when {
                httpReply.startsWith("HTTP/") -> kinds += PortKind.HTTP
                isTls(httpReply) -> kinds += PortKind.HTTPS
            }
        }

        if (PortKind.HTTP in kinds && answersOnvif(host, port)) kinds += PortKind.ONVIF
        return KnownPort(port, kinds)
    }

    /**
     * ONVIF: сервис устройства отвечает SOAP-конвертом на GetSystemDateAndTime (авторизация не нужна).
     * Если камера требует вход (401) даже для него, ONVIF засчитывается, только когда
     * несуществующий путь на этом же порту отвечает иначе — то есть /onvif/device_service существует.
     */
    private fun answersOnvif(host: String, port: Int): Boolean {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body>""" +
            """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/></s:Body></s:Envelope>"""
        val request = "POST /onvif/device_service HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Content-Type: application/soap+xml; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n$body"
        val reply = exchange(host, port, request, untilEof = true)
        if (reply.contains("Envelope", ignoreCase = true) &&
            (reply.contains("onvif", ignoreCase = true) || reply.contains("soap", ignoreCase = true))
        ) return true

        if (statusCode(reply) == 401) {
            val bogus = exchange(host, port, "GET /__den_probe__ HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
            return statusCode(bogus).let { it != null && it != 401 }
        }
        return false
    }

    private fun statusCode(reply: String): Int? =
        if (reply.startsWith("HTTP/")) reply.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() else null

    /** TLS-ответ на открытый текст: alert (0x15) или handshake (0x16) с версией 3.x */
    private fun isTls(reply: String): Boolean =
        reply.length >= 2 && (reply[0] == '\u0015' || reply[0] == '\u0016') && reply[1] == '\u0003'

    private fun isOpen(host: String, port: Int): Boolean = try {
        android.util.Log.i("CamNet", "PORT connect $host:$port")
        Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Отправляет запрос и читает ответ: до конца заголовков (untilEof = false) или до закрытия
     * соединения. Пустая строка — порт не ответил или оборвал соединение.
     */
    private fun exchange(host: String, port: Int, request: String, untilEof: Boolean = false): String = try {
        android.util.Log.i("CamNet", "PORT send $host:$port ${request.lineSequence().first()}")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val input = socket.getInputStream()
            val buffer = ByteArray(2048)
            val received = StringBuilder()
            try {
                while (received.length < MAX_RESPONSE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    received.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                    if (!untilEof && received.contains("\r\n\r\n")) break
                    // Не HTTP/RTSP и без перевода строки — дальше ждать нечего
                    if (!untilEof && received.length >= 16 && !received.contains("\r\n")) break
                }
            } catch (_: SocketTimeoutException) {
                // отвечало ли что-то до таймаута — решает полученная часть
            }
            received.toString()
        }
    } catch (_: Exception) {
        ""
    }
}
