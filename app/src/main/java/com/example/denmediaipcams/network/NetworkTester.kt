package com.example.denmediaipcams.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

sealed class PortPingStatus {
    object Idle : PortPingStatus()
    object Loading : PortPingStatus()
    object Success : PortPingStatus()
    data class Error(val message: String) : PortPingStatus()
}

sealed class AuthCheckStatus {
    object Idle : AuthCheckStatus()
    object Loading : AuthCheckStatus()
    data class Success(val message: String) : AuthCheckStatus()
    data class Error(val message: String) : AuthCheckStatus()
}

sealed class RtspCheckStatus {
    object Idle : RtspCheckStatus()
    object Loading : RtspCheckStatus()
    data class Success(val message: String) : RtspCheckStatus()
    data class Error(val message: String) : RtspCheckStatus()
}

object NetworkTester {

    suspend fun pingPort(host: String, port: Int, timeoutMs: Int = 2000): PortPingStatus = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext PortPingStatus.Error("Не указан IP-адрес или Host")
        if (port !in 1..65535) return@withContext PortPingStatus.Error("Некорректный номер порта: $port")

        val cleanHost = host.removePrefix("rtsp://")
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
            }
            PortPingStatus.Success
        } catch (e: Exception) {
            val errorMsg = when {
                e is java.net.SocketTimeoutException -> "Превышено время ожидания (таймаут ${timeoutMs}мс)"
                e is java.net.ConnectException -> "Подключение отклонено сервером (Connection Refused)"
                e is java.net.UnknownHostException -> "Неизвестный хост: $cleanHost"
                else -> e.localizedMessage ?: "Ошибка подключения к порту $port"
            }
            PortPingStatus.Error(errorMsg)
        }
    }

    /**
     * «Пинг» по TCP: время установки соединения с портом камеры (ICMP приложениям недоступен).
     * null — порт не ответил за отведённое время.
     */
    suspend fun tcpPingMs(host: String, port: Int, timeoutMs: Int = 2000): Long? = withContext(Dispatchers.IO) {
        android.util.Log.i("CamNet", "TCP ping $host:$port")
        try {
            val start = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }

    suspend fun checkAuth(
        host: String,
        httpPort: Int,
        user: String,
        pass: String,
        timeoutMs: Int = 3000
    ): AuthCheckStatus = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext AuthCheckStatus.Error("Не указан IP-адрес хоста")

        val cleanHost = host.removePrefix("rtsp://")
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")

        val testUrlStr = "http://$cleanHost:$httpPort/"

        try {
            val response = CameraHttp.request(testUrlStr, user = user, pass = pass, timeoutMs = timeoutMs)
            val code = response.code

            // Многие камеры отдают страницу входа с кодом 200 без всякой авторизации —
            // в этом случае логин/пароль по HTTP фактически не проверены
            if (code in 200..299 && !response.authChallenged) {
                return@withContext AuthCheckStatus.Error(
                    "Веб-сервер камеры ответил HTTP $code без запроса авторизации — логин и пароль этим способом проверить нельзя. " +
                        "Используйте «Проверить RTSP поток»."
                )
            }

            when (code) {
                in 200..299 -> AuthCheckStatus.Success("Авторизация успешна! Сервер ответил HTTP $code")
                401 -> AuthCheckStatus.Error("Ошибка авторизации (HTTP 401 Unauthorized). Проверьте логин и пароль.")
                403 -> AuthCheckStatus.Error("Доступ запрещен (HTTP 403 Forbidden). Недостаточно прав.")
                else -> AuthCheckStatus.Success("Камера ответила кодом HTTP $code. Подключение установлено.")
            }
        } catch (e: Exception) {
            val errorMsg = when {
                e is java.net.SocketTimeoutException -> "Таймаут проверки авторизации на $cleanHost:$httpPort"
                e is java.net.ConnectException -> "Не удалось подключиться к веб-серверу $cleanHost:$httpPort"
                else -> e.localizedMessage ?: "Ошибка авторизации"
            }
            AuthCheckStatus.Error(errorMsg)
        }
    }

    /**
     * Реальная проверка RTSP: отправляет DESCRIBE (с Digest/Basic авторизацией)
     * и разбирает ответ. Позволяет отличить неверный путь (404), неверный пароль (401)
     * и показать кодеки потока из SDP.
     */
    suspend fun probeRtsp(
        rtspUrl: String,
        user: String,
        pass: String,
        timeoutMs: Int = 4000
    ): RtspCheckStatus = withContext(Dispatchers.IO) {
        val uri = android.net.Uri.parse(rtspUrl)
        val host = uri.host
        if (uri.scheme != "rtsp" || host.isNullOrBlank()) {
            return@withContext RtspCheckStatus.Error("Некорректный RTSP адрес: $rtspUrl")
        }
        val port = if (uri.port > 0) uri.port else 554

        // Логин/пароль могут быть указаны прямо в URL
        val urlUserInfo = uri.userInfo
        val login = if (urlUserInfo != null) urlUserInfo.substringBefore(":") else user
        val password = if (urlUserInfo != null) urlUserInfo.substringAfter(":", "") else pass

        val query = uri.encodedQuery?.let { "?$it" } ?: ""
        val requestUri = "rtsp://$host:$port${uri.encodedPath ?: "/"}$query"

        try {
            val response = describeWithAuth(host, port, requestUri, login, password, timeoutMs)

            when (response.code) {
                200 -> {
                    val setup = setupFirstTrack(host, port, requestUri, response.body, login, password, timeoutMs)
                    if (setup.code == 200) {
                        RtspCheckStatus.Success("RTSP поток доступен. ${describeSdp(response.body)}")
                    } else {
                        RtspCheckStatus.Error(
                            "Камера описывает поток (${describeSdp(response.body)}), но отклонила его запуск: " +
                                "SETUP → ${setup.code} ${setup.reason}. Воспроизведение невозможно. " +
                                "Часть камер отвечает на DESCRIBE по любому пути — проверьте путь потока, " +
                                "логин и пароль (в документации камеры или в VLC)."
                        )
                    }
                }
                401 -> if (login.isBlank() && password.isBlank()) {
                    RtspCheckStatus.Error("Камера требует авторизацию RTSP (401), но ни логин, ни пароль не указаны.")
                } else {
                    RtspCheckStatus.Error("RTSP: неверный логин или пароль (401 Unauthorized).")
                }
                400, 407 -> if (login.isBlank() && password.isBlank()) {
                    RtspCheckStatus.Error("RTSP: камера ответила ${response.code} ${response.reason} — вероятно, нужен пароль (логин можно оставить пустым).")
                } else {
                    RtspCheckStatus.Error("RTSP: камера ответила ${response.code} ${response.reason}. Проверьте путь потока и пароль.")
                }
                403 -> RtspCheckStatus.Error("RTSP: доступ запрещён (403). У пользователя нет прав на просмотр.")
                404 -> RtspCheckStatus.Error("RTSP: путь потока не найден (404). Проверьте поле «RTSP путь потока» — для этой камеры он другой.")
                else -> RtspCheckStatus.Error("RTSP: камера ответила ${response.code} ${response.reason}")
            }
        } catch (e: Exception) {
            val errorMsg = when (e) {
                is java.net.SocketTimeoutException -> "RTSP: камера не ответила на запрос за ${timeoutMs}мс ($host:$port)."
                is java.net.ConnectException -> "RTSP: подключение к $host:$port отклонено."
                is java.net.UnknownHostException -> "RTSP: неизвестный хост $host"
                else -> "RTSP: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
            RtspCheckStatus.Error(errorMsg)
        }
    }

    internal class RtspResponse(
        val code: Int,
        val reason: String,
        val headers: Map<String, String>,
        val body: String,
        /** Запрос повторялся с логином и паролем (после ответа 401) */
        val authRetried: Boolean = false,
        /** Заголовок WWW-Authenticate первого ответа камеры */
        val challenge: String? = null
    )

    /**
     * DESCRIBE с авторизацией: при ответе 401 повторяет запрос с Digest/Basic.
     * Блокирующий вызов — выполнять на Dispatchers.IO.
     */
    internal fun describeWithAuth(
        host: String,
        port: Int,
        requestUri: String,
        login: String,
        password: String,
        timeoutMs: Int
    ): RtspResponse {
        return sendRtspDescribe(host, port, requestUri, login, password, timeoutMs)
    }

    /**
     * Проверка SETUP первой дорожки: часть камер отвечает на DESCRIBE при любом пути,
     * а воспроизведение отклоняет ответом 400 на SETUP. Возвращает код ответа на SETUP.
     */
    internal fun setupFirstTrack(
        host: String,
        port: Int,
        requestUri: String,
        sdp: String,
        login: String,
        password: String,
        timeoutMs: Int
    ): RtspResponse {
        val mediaSection = sdp.substringAfter("\nm=", "")
        val control = Regex("""(?im)^a=control:\s*(\S+)""").find(mediaSection)?.groupValues?.get(1)
        val setupUri = when {
            control == null || control == "*" -> requestUri
            "://" in control -> control
            // Как у плееров: к адресу с параметрами дорожка добавляется в конец
            else -> requestUri.trimEnd('/') + "/" + control.trimStart('/')
        }
        val transport = "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
        return sendRtspDescribe(host, port, setupUri, login, password, timeoutMs, "SETUP", transport)
    }

    /**
     * Запрос RTSP; при ответе 401 повторяется с Digest/Basic по тому же соединению
     * (камеры вроде Yoosee привязывают nonce к соединению и на новом отвечают 400).
     */
    private fun sendRtspDescribe(
        host: String,
        port: Int,
        requestUri: String,
        login: String,
        password: String,
        timeoutMs: Int,
        method: String = "DESCRIBE",
        extraHeaders: String = ""
    ): RtspResponse {
        android.util.Log.i("CamNet", "RTSP $method $requestUri")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            fun send(cseq: Int, authHeader: String?) {
                val request = buildString {
                    append("$method $requestUri RTSP/1.0\r\n")
                    append("CSeq: $cseq\r\n")
                    if (method == "DESCRIBE") append("Accept: application/sdp\r\n")
                    append(extraHeaders)
                    append("User-Agent: DenMediaIPCams\r\n")
                    if (authHeader != null) append("Authorization: $authHeader\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }

            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)

            fun readResponse(): RtspResponse {
            val statusLine = reader.readLine() ?: throw java.io.IOException("Камера закрыла соединение без ответа")
            val parts = statusLine.split(" ", limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull()
                ?: throw java.io.IOException("Некорректный ответ RTSP: $statusLine")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(":").trim().lowercase()
                val value = line.substringAfter(":").trim()
                // При нескольких WWW-Authenticate предпочитаем Digest
                if (name == "www-authenticate" && headers[name]?.startsWith("Digest", ignoreCase = true) == true) continue
                headers[name] = value
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            return RtspResponse(code, parts.getOrElse(2) { "" }, headers, body)
            }

            send(1, null)
            val first = readResponse()
            // Некоторым камерам нужен только пароль — тогда логин пустой ("" : пароль)
            if (first.code != 401 || (login.isBlank() && password.isBlank())) return first
            val challenge = first.headers["www-authenticate"] ?: return first
            send(2, buildAuthHeader(challenge, login, password, method, requestUri))
            return readResponse().let { RtspResponse(it.code, it.reason, it.headers, it.body, authRetried = true, challenge = challenge) }
        }
    }

    internal fun buildAuthHeader(challenge: String, user: String, pass: String, method: String, uri: String): String {
        if (challenge.startsWith("Basic", ignoreCase = true)) {
            return "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        }

        val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""").findAll(challenge)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }

        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (qop != null) {
                val nc = "00000001"
                val cnonce = md5(System.nanoTime().toString()).take(16)
                append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\"")
                append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            } else {
                append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
            }
            params["opaque"]?.let { append(", opaque=\"$it\"") }
            params["algorithm"]?.let { append(", algorithm=$it") }
        }
    }

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Краткое описание дорожек из SDP: "Видео: H264, Аудио: PCMA".
     */
    internal fun describeSdp(sdp: String): String {
        val tracks = mutableListOf<String>()
        var currentMedia: String? = null
        for (line in sdp.lines()) {
            when {
                line.startsWith("m=") -> currentMedia = line.removePrefix("m=").substringBefore(" ")
                line.startsWith("a=rtpmap:") && currentMedia != null -> {
                    val codec = line.substringAfter(" ").substringBefore("/")
                    val title = when (currentMedia) {
                        "video" -> "Видео"
                        "audio" -> "Аудио"
                        else -> currentMedia
                    }
                    tracks += "$title: $codec"
                    currentMedia = null
                }
            }
        }
        return if (tracks.isEmpty()) "Дорожки в SDP не распознаны." else tracks.joinToString(", ")
    }

    /**
     * Быстрое сканирование всех портов (1..65535) с использованием параллельных корутин.
     */
    suspend fun scanAllPorts(
        host: String,
        onPortFound: suspend (Int) -> Unit = {},
        onProgress: (String) -> Unit
    ): List<Int> = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext emptyList()
        val cleanHost = host.removePrefix("rtsp://")
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")

        val openPorts = mutableListOf<Int>()
        val totalPorts = 65535
        val batchSize = 1000
        val timeoutMs = 250

        for (startPort in 1..totalPorts step batchSize) {
            val endPort = minOf(startPort + batchSize - 1, totalPorts)
            onProgress("Сканирование портов ($startPort-$endPort / 65535)...")

            val deferreds = (startPort..endPort).map { port ->
                async {
                    val open = try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (open) {
                        onPortFound(port)
                        port
                    } else {
                        null
                    }
                }
            }
            val results = deferreds.awaitAll().filterNotNull()
            openPorts.addAll(results)
        }
        openPorts.sorted()
    }

    /**
     * Пинг IP-адреса хоста (проверка доступности по стандартным портам камеры или ICMP).
     */
    suspend fun pingIp(host: String, timeoutMs: Int = 2000): PortPingStatus = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext PortPingStatus.Error("Не указан IP-адрес")
        val cleanHost = host.removePrefix("rtsp://")
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")

        val portsToTry = listOf(80, 554, 8080, 8000, 21)
        for (port in portsToTry) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                }
                return@withContext PortPingStatus.Success
            } catch (_: Exception) {}
        }
        try {
            val address = java.net.InetAddress.getByName(cleanHost)
            if (address.isReachable(timeoutMs)) {
                return@withContext PortPingStatus.Success
            }
        } catch (_: Exception) {}

        PortPingStatus.Error("Хост недоступен или не отвечает")
    }
}
