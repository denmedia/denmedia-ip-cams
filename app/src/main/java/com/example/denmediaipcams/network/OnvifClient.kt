package com.example.denmediaipcams.network

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Минимальный ONVIF-клиент (SOAP 1.2 + WS-Security UsernameToken, при необходимости HTTP Digest).
 * Блокирующие вызовы — выполнять на Dispatchers.IO.
 */
internal object OnvifClient {

    private const val TAG = "OnvifClient"
    private const val TIMEOUT_MS = 3000

    /** Адреса сервисов камеры. Хост и порт всегда берутся из настроек — XAddr может содержать внутренний IP. */
    data class Endpoints(
        val mediaUrl: String,
        val ptzUrl: String?,
        val imagingUrl: String?,
        val clockOffsetMs: Long
    )

    data class Profile(
        val token: String,
        val videoSourceToken: String?,
        val hasPtz: Boolean,
        val name: String? = null,
        /** Разрешение видеокодера профиля (0 — неизвестно) */
        val width: Int = 0,
        val height: Int = 0
    )

    fun discover(host: String, port: Int, user: String, pass: String): Endpoints? {
        val deviceUrl = "http://$host:$port/onvif/device_service"

        // Время камеры нужно для WS-Security: при расхождении часов камера отклоняет запросы
        val clockOffsetMs = request(deviceUrl, GET_SYSTEM_DATE_AND_TIME, "", "", 0)
            ?.let { parseCameraUtcTime(it) }
            ?.let { it - System.currentTimeMillis() }
            ?: 0L

        val capabilities = request(deviceUrl, GET_CAPABILITIES, user, pass, clockOffsetMs) ?: return null

        fun serviceUrl(service: String): String? =
            Regex("""<(?:\w+:)?$service>\s*<(?:\w+:)?XAddr>([^<]+)<""").find(capabilities)?.groupValues?.get(1)
                ?.let { "http://$host:$port" + (android.net.Uri.parse(it.trim()).encodedPath ?: "/") }

        return Endpoints(
            mediaUrl = serviceUrl("Media") ?: "http://$host:$port/onvif/media_service",
            ptzUrl = serviceUrl("PTZ"),
            imagingUrl = serviceUrl("Imaging"),
            clockOffsetMs = clockOffsetMs
        )
    }

    fun getProfiles(endpoints: Endpoints, user: String, pass: String): List<Profile> {
        val response = request(endpoints.mediaUrl, GET_PROFILES, user, pass, endpoints.clockOffsetMs) ?: return emptyList()
        return Regex(
            """<(?:\w+:)?Profiles\b[^>]*\btoken="([^"]+)"[^>]*>(.*?)</(?:\w+:)?Profiles>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(response).map { match ->
            val content = match.groupValues[2]
            // Разрешение берём из VideoEncoderConfiguration (первый блок Resolution в профиле)
            val encoder = Regex(
                """<(?:\w+:)?VideoEncoderConfiguration\b.*?</(?:\w+:)?VideoEncoderConfiguration>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(content)?.value.orEmpty()
            fun intTag(tag: String) = Regex("""<(?:\w+:)?$tag>(\d+)<""").find(encoder)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Profile(
                token = match.groupValues[1],
                videoSourceToken = Regex("""<(?:\w+:)?SourceToken>([^<]+)<""").find(content)?.groupValues?.get(1)?.trim(),
                hasPtz = Regex("""<(?:\w+:)?PTZConfiguration\b""").containsMatchIn(content),
                // Имя профиля — первый <Name> внутри профиля (до вложенных конфигураций)
                name = Regex("""^\s*<(?:\w+:)?Name>([^<]*)<""").find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null },
                width = intTag("Width"),
                height = intTag("Height")
            )
        }.toList()
    }

    fun getStreamUri(endpoints: Endpoints, profileToken: String, user: String, pass: String): String? {
        val body = """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl">""" +
            "<StreamSetup>" +
            """<Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream>""" +
            """<Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport>""" +
            "</StreamSetup>" +
            "<ProfileToken>${xmlEscape(profileToken)}</ProfileToken>" +
            "</GetStreamUri>"
        val response = request(endpoints.mediaUrl, body, user, pass, endpoints.clockOffsetMs) ?: return null
        return Regex("""<(?:\w+:)?Uri>([^<]+)<""").find(response)?.groupValues?.get(1)
            ?.trim()?.replace("&amp;", "&")
    }

    /** Адрес JPEG-снимка профиля (GetSnapshotUri) */
    fun getSnapshotUri(endpoints: Endpoints, profileToken: String, user: String, pass: String): String? {
        val body = """<GetSnapshotUri xmlns="http://www.onvif.org/ver10/media/wsdl">""" +
            "<ProfileToken>${xmlEscape(profileToken)}</ProfileToken></GetSnapshotUri>"
        val response = request(endpoints.mediaUrl, body, user, pass, endpoints.clockOffsetMs) ?: return null
        return Regex("""<(?:\w+:)?Uri>([^<]+)<""").find(response)?.groupValues?.get(1)
            ?.trim()?.replace("&amp;", "&")
    }

    /**
     * Отправляет SOAP-запрос. Возвращает тело ответа или null при ошибке/SOAP Fault.
     */
    fun request(url: String, body: String, user: String, pass: String, clockOffsetMs: Long): String? {
        val header = if (user.isNotBlank()) wsSecurityHeader(user, pass, clockOffsetMs) else ""
        val envelope = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">""" +
            "<s:Header>$header</s:Header><s:Body>$body</s:Body></s:Envelope>"
        return try {
            val response = CameraHttp.request(
                url = url,
                method = "POST",
                body = envelope,
                contentType = "application/soap+xml; charset=utf-8",
                user = user,
                pass = pass,
                timeoutMs = TIMEOUT_MS
            )
            if (response.isSuccess) {
                response.body
            } else {
                // SOAP Fault объясняет причину отказа (неверный токен, нет прав и т.п.)
                val operation = Regex("""^<(\w+)""").find(body)?.groupValues?.get(1) ?: "?"
                val fault = Regex("""<(?:\w+:)?Text[^>]*>([^<]+)<""").find(response.body)?.groupValues?.get(1)
                    ?: Regex("""<(?:\w+:)?Subcode>.*?<(?:\w+:)?Value>([^<]+)<""", RegexOption.DOT_MATCHES_ALL)
                        .find(response.body)?.groupValues?.get(1)
                    ?: response.body.take(300)
                Log.w(TAG, "ONVIF $operation -> HTTP ${response.code}: $fault")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ONVIF request to $url failed: ${e.message}")
            null
        }
    }

    fun xmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * WS-Security UsernameToken: PasswordDigest = Base64(SHA1(nonce + created + password)).
     */
    private fun wsSecurityHeader(user: String, pass: String, clockOffsetMs: Long): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = DateTimeFormatter.ISO_INSTANT.format(
            Instant.ofEpochMilli(System.currentTimeMillis() + clockOffsetMs).truncatedTo(ChronoUnit.SECONDS)
        )
        val digest = MessageDigest.getInstance("SHA-1").run {
            update(nonce)
            update(created.toByteArray(Charsets.UTF_8))
            update(pass.toByteArray(Charsets.UTF_8))
            digest()
        }
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">""" +
            "<UsernameToken><Username>${xmlEscape(user)}</Username>" +
            """<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">""" +
            "${Base64.encodeToString(digest, Base64.NO_WRAP)}</Password>" +
            """<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-soap-message-security-1.0#Base64Binary">""" +
            "${Base64.encodeToString(nonce, Base64.NO_WRAP)}</Nonce>" +
            """<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>""" +
            "</UsernameToken></Security>"
    }

    /** Ответ GetSystemDateAndTime: UTC (мс) и часовой пояс как его хранит камера (например, «MSK-3»), если она его отдаёт */
    data class CameraTime(val utcMs: Long, val tzRaw: String?)

    /**
     * Время камеры в UTC (мс). Обычно GetSystemDateAndTime отдаётся без авторизации, но часть камер требует
     * логин и пароль — тогда повторяем с ними. null — камера не ответила по ONVIF.
     */
    fun getCameraUtcMs(deviceUrl: String, user: String, pass: String): Long? =
        getCameraTime(deviceUrl, user, pass)?.utcMs

    /**
     * То же, что [getCameraUtcMs], но вместе с текущим часовым поясом камеры — проверить, что отправленный
     * командой [setSystemDateAndTime] пояс действительно применился (часть камер отвечает «ОК», но не применяет
     * его или теряет его сама по себе — см. [CameraTimeSync]).
     */
    fun getCameraTime(deviceUrl: String, user: String, pass: String): CameraTime? {
        val response = request(deviceUrl, GET_SYSTEM_DATE_AND_TIME, "", "", 0)
            ?: if (user.isNotBlank()) request(deviceUrl, GET_SYSTEM_DATE_AND_TIME, user, pass, 0) else null
        val body = response ?: return null
        val utcMs = parseCameraUtcTime(body) ?: return null
        return CameraTime(utcMs, parseCameraTz(body))
    }

    /**
     * Ставит камере ручное время ([utc]) и, если задан [posixTz], часовой пояс.
     * [clockOffsetMs] — на сколько часы камеры опережают часы планшета (нужно для WS-Security).
     * true — камера приняла команду.
     */
    fun setSystemDateAndTime(
        deviceUrl: String, user: String, pass: String, clockOffsetMs: Long,
        utc: Instant, posixTz: String?, daylightSavings: Boolean
    ): Boolean {
        val time = utc.atZone(ZoneOffset.UTC)
        val timeZone = if (posixTz != null) {
            """<TimeZone><TZ xmlns="http://www.onvif.org/ver10/schema">${xmlEscape(posixTz)}</TZ></TimeZone>"""
        } else ""
        val body = """<SetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl">""" +
            "<DateTimeType>Manual</DateTimeType>" +
            "<DaylightSavings>$daylightSavings</DaylightSavings>" +
            timeZone +
            "<UTCDateTime>" +
            """<Time xmlns="http://www.onvif.org/ver10/schema">""" +
            "<Hour>${time.hour}</Hour><Minute>${time.minute}</Minute><Second>${time.second}</Second></Time>" +
            """<Date xmlns="http://www.onvif.org/ver10/schema">""" +
            "<Year>${time.year}</Year><Month>${time.monthValue}</Month><Day>${time.dayOfMonth}</Day></Date>" +
            "</UTCDateTime></SetSystemDateAndTime>"
        return request(deviceUrl, body, user, pass, clockOffsetMs) != null
    }

    private fun parseCameraTz(response: String): String? =
        Regex("""<(?:\w+:)?TZ>([^<]+)<""").find(response)?.groupValues?.get(1)?.trim()

    private fun parseCameraUtcTime(response: String): Long? {
        val utcBlock = Regex("""UTCDateTime>(.*?)</(?:\w+:)?UTCDateTime""", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.get(1) ?: return null
        fun value(tag: String) = Regex("""<(?:\w+:)?$tag>(\d+)<""").find(utcBlock)?.groupValues?.get(1)?.toIntOrNull()
        return try {
            LocalDateTime.of(
                value("Year") ?: return null,
                value("Month") ?: return null,
                value("Day") ?: return null,
                value("Hour") ?: return null,
                value("Minute") ?: return null,
                value("Second") ?: return null
            ).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private const val GET_SYSTEM_DATE_AND_TIME =
        """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>"""

    private const val GET_CAPABILITIES =
        """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>All</Category></GetCapabilities>"""

    private const val GET_PROFILES =
        """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>"""
}
