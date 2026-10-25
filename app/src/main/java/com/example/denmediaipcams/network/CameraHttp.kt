package com.example.denmediaipcams.network

import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP-запросы к камере с авторизацией Digest/Basic.
 * Сначала запрос уходит без авторизации; при ответе 401 повторяется с заголовком
 * по схеме из WWW-Authenticate (современные Hikvision/Dahua принимают только Digest).
 * Блокирующие вызовы — выполнять на Dispatchers.IO.
 */
internal object CameraHttp {

    class Response(
        val code: Int,
        val body: String,
        /** Сервер запросил авторизацию (первый ответ был 401) */
        val authChallenged: Boolean
    ) {
        val isSuccess: Boolean get() = code in 200..299
    }

    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        user: String = "",
        pass: String = "",
        timeoutMs: Int = 3000
    ): Response {
        android.util.Log.i("CamNet", "HTTP $method $url")
        try {
            val first = send(url, method, body, contentType, null, timeoutMs)
            if (first.code != 401 || user.isBlank() || first.challenge == null) {
                return Response(first.code, first.body, authChallenged = first.code == 401)
            }

            val requestUri = URL(url).file.ifEmpty { "/" }
            val authHeader = NetworkTester.buildAuthHeader(first.challenge, user, pass, method, requestUri)
            val second = send(url, method, body, contentType, authHeader, timeoutMs)
            return Response(second.code, second.body, authChallenged = true)
        } catch (e: java.io.IOException) {
            // Возвращаем ошибку как специальный код (0 = сетевая ошибка)
            return Response(0, e.message ?: "Network error", authChallenged = false)
        }
    }

    /** GET бинарных данных (например, JPEG-снимка); null — ошибка или не 2xx */
    fun requestBytes(url: String, user: String = "", pass: String = "", timeoutMs: Int = 3000): ByteArray? {
        return try {
            val first = send(url, "GET", null, null, null, timeoutMs)
            val result = if (first.code == 401 && user.isNotBlank() && first.challenge != null) {
                val requestUri = URL(url).file.ifEmpty { "/" }
                val authHeader = NetworkTester.buildAuthHeader(first.challenge, user, pass, "GET", requestUri)
                send(url, "GET", null, null, authHeader, timeoutMs)
            } else first
            if (result.code in 200..299) result.bytes else null
        } catch (e: java.io.IOException) {
            // При ошибке сети возвращаем null
            null
        }
    }

    private class RawResponse(val code: Int, val bytes: ByteArray, val challenge: String?) {
        val body: String get() = String(bytes, Charsets.UTF_8)
    }

    private fun send(
        url: String,
        method: String,
        body: String?,
        contentType: String?,
        authHeader: String?,
        timeoutMs: Int
    ): RawResponse {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            authHeader?.let { connection.setRequestProperty("Authorization", it) }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)

            // При нескольких схемах авторизации предпочитаем Digest
            val challenges = connection.headerFields.entries
                .filter { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                .flatMap { it.value }
            val challenge = challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
                ?: challenges.firstOrNull()

            return RawResponse(code, responseBody, challenge)
        } catch (e: java.net.ConnectException) {
            // Порт недоступен или камера не отвечает по HTTP
            throw java.io.IOException("HTTP порт недоступен: ${e.message}", e)
        } catch (e: java.net.SocketTimeoutException) {
            // Превышено время ожидания
            throw java.io.IOException("Превышено время ожидания HTTP: ${e.message}", e)
        } catch (e: java.net.UnknownHostException) {
            // Неизвестный хост
            throw java.io.IOException("Неизвестный хост: ${e.message}", e)
        } catch (e: Exception) {
            // Любая другая ошибка
            throw java.io.IOException("Ошибка HTTP запроса: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }
}
