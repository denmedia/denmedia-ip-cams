package com.example.denmediaipcams.network

import android.util.Xml
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Сбор полной информации о камере по ONVIF: устройство, сеть, медиа-профили, PTZ, Imaging,
 * события, DeviceIO, запись. Блокирующий — выполнять на Dispatchers.IO.
 * Каждый раздел независим: если камера не поддерживает вызов, показывается «недоступно».
 */
internal object OnvifInspector {

    private const val NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl"
    private const val NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl"
    private const val NS_PTZ = "http://www.onvif.org/ver20/ptz/wsdl"
    private const val NS_IMAGING = "http://www.onvif.org/ver20/imaging/wsdl"
    private const val NS_EVENTS = "http://www.onvif.org/ver10/events/wsdl"
    private const val NS_DEVICEIO = "http://www.onvif.org/ver10/deviceIO/wsdl"
    private const val NS_RECORDING = "http://www.onvif.org/ver10/recording/wsdl"
    private const val NS_SEARCH = "http://www.onvif.org/ver10/search/wsdl"

    private class Session(val host: String, val port: Int, val user: String, val pass: String, var offsetMs: Long) {
        fun call(url: String, ns: String, operation: String, inner: String = ""): String? =
            OnvifClient.request(url, "<$operation xmlns=\"$ns\">$inner</$operation>", user, pass, offsetMs)
    }

    fun inspect(host: String, port: Int, user: String, pass: String): String {
        val deviceUrl = "http://$host:$port/onvif/device_service"
        val s = Session(host, port, user, pass, 0L)
        val out = StringBuilder()

        // Время камеры: нужно и для отчёта, и для WS-Security
        val timeXml = OnvifClient.request(deviceUrl, "<GetSystemDateAndTime xmlns=\"$NS_DEVICE\"/>", "", "", 0)
        val cameraUtcMs = timeXml?.let { parseUtc(it) }
        s.offsetMs = cameraUtcMs?.let { it - System.currentTimeMillis() } ?: 0L

        val info = s.call(deviceUrl, NS_DEVICE, "GetDeviceInformation")
        val servicesXml = s.call(deviceUrl, NS_DEVICE, "GetServices", "<IncludeCapability>false</IncludeCapability>")
        val capsXml = if (servicesXml == null) s.call(deviceUrl, NS_DEVICE, "GetCapabilities", "<Category>All</Category>") else null
        if (info == null && servicesXml == null && capsXml == null) {
            return "Камера не ответила по ONVIF ($host:$port).\n\n" +
                "Проверьте ONVIF-порт, логин и пароль, а также что ONVIF включён в настройках камеры."
        }

        val services = parseServices(s, servicesXml, capsXml)

        // ---------- Устройство ----------
        section(out, "Устройство (Device Management)")
        if (info != null) {
            line(out, "Производитель", first(info, "Manufacturer"))
            line(out, "Модель", first(info, "Model"))
            line(out, "Прошивка", first(info, "FirmwareVersion"))
            line(out, "Серийный номер", first(info, "SerialNumber"))
            line(out, "Hardware ID", first(info, "HardwareId"))
        } else out.appendLine("GetDeviceInformation: недоступно")

        s.call(deviceUrl, NS_DEVICE, "GetScopes")?.let { scopes ->
            val items = texts(scopes, "ScopeItem").map { it.trim() }
            val prefix = "onvif://www.onvif.org/"
            fun scope(kind: String) = items.filter { it.startsWith("$prefix$kind/") }.map { it.removePrefix("$prefix$kind/") }
            line(out, "Имя (scope)", scope("name").joinToString())
            line(out, "Тип устройства", scope("type").joinToString())
            line(out, "Оборудование", scope("hardware").joinToString())
            line(out, "Расположение", scope("location").joinToString())
            line(out, "Профили ONVIF", scope("Profile").joinToString { "Profile $it" })
        }

        out.appendLine()
        if (timeXml != null) {
            val tz = first(timeXml, "TZ")
            line(out, "Время камеры (UTC)", cameraUtcMs?.let { LocalDateTime.ofEpochSecond(it / 1000, 0, ZoneOffset.UTC).toString().replace('T', ' ') })
            line(out, "Часовой пояс", tz)
            line(out, "Режим времени", first(timeXml, "DateTimeType"))
            line(out, "Летнее время", first(timeXml, "DaylightSavings"))
            val offsetSec = s.offsetMs / 1000
            if (kotlin.math.abs(offsetSec) > 5) {
                out.appendLine("⚠ Часы камеры расходятся с телефоном на $offsetSec с — возможны отказы авторизации")
            }
        }
        s.call(deviceUrl, NS_DEVICE, "GetNTP")?.let { line(out, "NTP", (texts(it, "IPv4Address") + texts(it, "DNSname")).joinToString().ifBlank { first(it, "FromDHCP")?.let { v -> "FromDHCP=$v" } }) }

        // ---------- Сеть ----------
        section(out, "Сеть")
        s.call(deviceUrl, NS_DEVICE, "GetHostname")?.let { line(out, "Hostname", first(it, "Name")) }
        s.call(deviceUrl, NS_DEVICE, "GetNetworkInterfaces")?.let { xml ->
            blocks(xml, "NetworkInterfaces").forEach { b ->
                line(out, "Интерфейс", first(b, "Name") ?: attr(b, "NetworkInterfaces", "token"))
                line(out, "  MAC", first(b, "HwAddress"))
                line(out, "  IPv4", texts(b, "Address").firstOrNull())
                line(out, "  DHCP", first(b, "DHCP"))
            }
        }
        s.call(deviceUrl, NS_DEVICE, "GetDNS")?.let { line(out, "DNS", texts(it, "IPv4Address").joinToString()) }
        s.call(deviceUrl, NS_DEVICE, "GetUsers")?.let { xml ->
            val users = blocks(xml, "User").map { "${first(it, "Username")} (${first(it, "UserLevel")})" }
            line(out, "Пользователи", users.joinToString())
        }

        // ---------- Сервисы ----------
        section(out, "Поддерживаемые сервисы")
        if (services.isEmpty()) out.appendLine("недоступно") else services.forEach { (name, url) -> line(out, name, url) }

        // ---------- Media ----------
        val mediaUrl = services["Media"] ?: "http://$host:$port/onvif/media_service"
        section(out, "Media: профили и потоки")
        val profilesXml = s.call(mediaUrl, NS_MEDIA, "GetProfiles")
        val endpoints = OnvifClient.Endpoints(mediaUrl, services["PTZ"], services["Imaging"], s.offsetMs)
        var ptzToken: String? = null
        var sourceToken: String? = null
        if (profilesXml == null) {
            out.appendLine("GetProfiles: недоступно")
        } else {
            val profileRegex = Regex("""<(?:\w+:)?Profiles\b[^>]*\btoken="([^"]+)"[^>]*>(.*?)</(?:\w+:)?Profiles>""", RegexOption.DOT_MATCHES_ALL)
            profileRegex.findAll(profilesXml).forEach { m ->
                val token = m.groupValues[1]
                val c = m.groupValues[2]
                val video = blocks(c, "VideoEncoderConfiguration").firstOrNull().orEmpty()
                val audio = blocks(c, "AudioEncoderConfiguration").firstOrNull()
                val hasPtz = Regex("""<(?:\w+:)?PTZConfiguration\b""").containsMatchIn(c)
                if (hasPtz && ptzToken == null) ptzToken = token
                if (sourceToken == null) sourceToken = first(c, "SourceToken")

                out.appendLine()
                out.appendLine("Профиль «${first(c, "Name") ?: token}» (token=$token)")
                if (video.isNotEmpty()) {
                    line(out, "  Видео", listOfNotNull(
                        first(video, "Encoding"),
                        first(video, "Width")?.let { w -> "${w}x${first(video, "Height")}" },
                        first(video, "FrameRateLimit")?.let { "$it fps" },
                        first(video, "BitrateLimit")?.let { "$it kbps" },
                        first(video, "GovLength")?.let { "GOP $it" },
                        first(video, "Profile")?.let { "profile $it" }
                    ).joinToString(", "))
                }
                if (audio != null) {
                    line(out, "  Аудио", listOfNotNull(
                        first(audio, "Encoding"),
                        first(audio, "Bitrate")?.let { "$it kbps" },
                        first(audio, "SampleRate")?.let { "$it kHz" }
                    ).joinToString(", "))
                } else line(out, "  Аудио", "нет")
                line(out, "  PTZ", if (hasPtz) "да" else "нет")
                line(out, "  Метаданные", if (Regex("""<(?:\w+:)?MetadataConfiguration\b""").containsMatchIn(c)) "да" else "нет")
                line(out, "  Аналитика", if (Regex("""<(?:\w+:)?VideoAnalyticsConfiguration\b""").containsMatchIn(c)) "да" else "нет")
                line(out, "  RTSP", OnvifClient.getStreamUri(endpoints, token, user, pass))
                line(out, "  Снимок (JPEG)", OnvifClient.getSnapshotUri(endpoints, token, user, pass))
            }
        }
        out.appendLine()
        s.call(mediaUrl, NS_MEDIA, "GetVideoSources")?.let { xml ->
            line(out, "Видеовходов", blocks(xml, "VideoSources").size.toString())
        }
        s.call(mediaUrl, NS_MEDIA, "GetAudioSources")?.let { xml ->
            line(out, "Аудиовходов", blocks(xml, "AudioSources").size.toString())
        }
        sourceToken?.let { token ->
            s.call(mediaUrl, NS_MEDIA, "GetVideoEncoderConfigurationOptions")?.let { xml ->
                val codecs = listOf("JPEG", "MPEG4", "H264", "H265").filter { Regex("""<(?:\w+:)?$it\b""").containsMatchIn(xml) }
                line(out, "Доступные кодеки", codecs.joinToString())
            }
        }

        // ---------- PTZ ----------
        section(out, "PTZ")
        val ptzUrl = services["PTZ"]
        if (ptzUrl == null) {
            out.appendLine("сервис не поддерживается")
        } else {
            s.call(ptzUrl, NS_PTZ, "GetNodes")?.let { xml ->
                line(out, "Максимум пресетов", first(xml, "MaximumNumberOfPresets"))
                line(out, "Home-позиция", first(xml, "HomeSupported"))
            }
            val token = ptzToken
            if (token == null) {
                out.appendLine("PTZ-конфигурация в профилях не найдена")
            } else {
                val t = OnvifClient.xmlEscape(token)
                s.call(ptzUrl, NS_PTZ, "GetStatus", "<ProfileToken>$t</ProfileToken>")?.let { xml ->
                    val pt = Regex("""<(?:\w+:)?PanTilt\b[^>]*\bx="([^"]+)"[^>]*\by="([^"]+)"""").find(xml)
                    val zoom = Regex("""<(?:\w+:)?Zoom\b[^>]*\bx="([^"]+)"""").find(xml)
                    line(out, "Положение pan/tilt", pt?.let { "${it.groupValues[1]} / ${it.groupValues[2]}" })
                    line(out, "Положение zoom", zoom?.groupValues?.get(1))
                    line(out, "Состояние движения", Regex("""<(?:\w+:)?MoveStatus>.*?<(?:\w+:)?PanTilt>([^<]+)<""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1))
                }
                s.call(ptzUrl, NS_PTZ, "GetPresets", "<ProfileToken>$t</ProfileToken>")?.let { xml ->
                    val presets = blocks(xml, "Preset").map { b -> first(b, "Name") ?: "?" }
                    line(out, "Пресеты", "${presets.size}" + if (presets.isNotEmpty()) ": " + presets.joinToString() else "")
                }
            }
        }

        // ---------- Imaging ----------
        section(out, "Imaging (изображение)")
        val imagingUrl = services["Imaging"]
        val src = sourceToken
        if (imagingUrl == null || src == null) {
            out.appendLine("сервис не поддерживается")
        } else {
            s.call(imagingUrl, NS_IMAGING, "GetImagingSettings", "<VideoSourceToken>${OnvifClient.xmlEscape(src)}</VideoSourceToken>")?.let { xml ->
                line(out, "Яркость", first(xml, "Brightness"))
                line(out, "Контраст", first(xml, "Contrast"))
                line(out, "Насыщенность", first(xml, "ColorSaturation"))
                line(out, "Резкость", first(xml, "Sharpness"))
                line(out, "IR-cut (день/ночь)", first(xml, "IrCutFilter"))
                line(out, "BLC", blocks(xml, "BacklightCompensation").firstOrNull()?.let { first(it, "Mode") })
                line(out, "WDR", blocks(xml, "WideDynamicRange").firstOrNull()?.let { first(it, "Mode") })
                line(out, "Экспозиция", blocks(xml, "Exposure").firstOrNull()?.let { first(it, "Mode") })
                line(out, "Баланс белого", blocks(xml, "WhiteBalance").firstOrNull()?.let { first(it, "Mode") })
                line(out, "Фокус", blocks(xml, "Focus").firstOrNull()?.let { first(it, "AutoFocusMode") })
            } ?: out.appendLine("GetImagingSettings: недоступно")
        }

        // ---------- Events ----------
        section(out, "События (Events)")
        val eventsUrl = services["Events"]
        if (eventsUrl == null) {
            out.appendLine("сервис не поддерживается")
        } else {
            s.call(eventsUrl, NS_EVENTS, "GetEventProperties")?.let { xml ->
                line(out, "Pull-подписка", first(xml, "WSPullingPointSupport") ?: first(xml, "WSPullPointSupport"))
                val topics = parseTopics(xml)
                if (topics.isEmpty()) out.appendLine("Список событий пуст") else {
                    out.appendLine("События:")
                    topics.forEach { out.appendLine("  • $it") }
                }
            } ?: out.appendLine("GetEventProperties: недоступно")
        }

        // ---------- Analytics ----------
        section(out, "Аналитика (Analytics / Rule Engine)")
        out.appendLine(if (services["Analytics"] != null) "сервис доступен: ${services["Analytics"]}" else "сервис не поддерживается")

        // ---------- DeviceIO ----------
        section(out, "DeviceIO (входы/выходы)")
        s.call(deviceUrl, NS_DEVICE, "GetRelayOutputs")?.let { xml ->
            val relays = blocks(xml, "RelayOutputs").map { b ->
                (attr(b, "RelayOutputs", "token") ?: "?") + (first(b, "Mode")?.let { " ($it)" } ?: "")
            }
            line(out, "Реле", if (relays.isEmpty()) "нет" else relays.joinToString())
        } ?: out.appendLine("Реле: недоступно")
        services["DeviceIO"]?.let { url ->
            s.call(url, NS_DEVICEIO, "GetDigitalInputs")?.let { xml ->
                line(out, "Цифровые входы", blocks(xml, "DigitalInputs").size.toString())
            }
        }

        // ---------- Recording / Search / Replay ----------
        section(out, "Запись (Recording / Search / Replay)")
        val recordingUrl = services["Recording"]
        val searchUrl = services["Search"]
        line(out, "Recording", if (recordingUrl != null) "да" else "нет")
        line(out, "Search", if (searchUrl != null) "да" else "нет")
        line(out, "Replay", if (services["Replay"] != null) "да" else "нет")
        recordingUrl?.let { url ->
            s.call(url, NS_RECORDING, "GetRecordings")?.let { xml ->
                line(out, "Записей", texts(xml, "RecordingToken").distinct().size.toString())
            }
        }
        searchUrl?.let { url ->
            s.call(url, NS_SEARCH, "GetRecordingSummary")?.let { xml ->
                line(out, "Архив с", first(xml, "DataFrom"))
                line(out, "Архив по", first(xml, "DataUntil"))
                line(out, "Количество записей", first(xml, "NumberRecordings"))
            }
        }

        // ---------- Discovery ----------
        section(out, "Discovery")
        out.appendLine("WS-Discovery: UDP multicast 239.255.255.250:3702 — камера отвечает на Probe своими scopes (см. «Устройство»).")

        return out.toString().trimEnd()
    }

    // ---------------- Разбор ----------------

    /** Имя сервиса → адрес (хост и порт подставляются из настроек: XAddr может содержать внутренний IP) */
    private fun parseServices(s: Session, servicesXml: String?, capsXml: String?): Map<String, String> {
        fun rewrite(xaddr: String) = "http://${s.host}:${s.port}" + (android.net.Uri.parse(xaddr.trim()).encodedPath ?: "/")
        val result = linkedMapOf<String, String>()
        if (servicesXml != null) {
            val byNamespace = mapOf(
                NS_MEDIA to "Media", "http://www.onvif.org/ver20/media/wsdl" to "Media2", NS_PTZ to "PTZ",
                NS_IMAGING to "Imaging", NS_EVENTS to "Events", "http://www.onvif.org/ver20/analytics/wsdl" to "Analytics",
                NS_DEVICEIO to "DeviceIO", NS_RECORDING to "Recording", NS_SEARCH to "Search",
                "http://www.onvif.org/ver10/replay/wsdl" to "Replay", NS_DEVICE to "Device"
            )
            blocks(servicesXml, "Service").forEach { b ->
                val ns = first(b, "Namespace")?.trim()
                val xaddr = first(b, "XAddr") ?: return@forEach
                val name = byNamespace[ns] ?: ns?.substringAfter("onvif.org/")?.substringBefore("/wsdl") ?: return@forEach
                result.putIfAbsent(name, rewrite(xaddr))
            }
        } else if (capsXml != null) {
            listOf("Device", "Media", "PTZ", "Imaging", "Events", "Analytics", "DeviceIO", "Recording", "Search", "Replay").forEach { name ->
                Regex("""<(?:\w+:)?$name>\s*<(?:\w+:)?XAddr>([^<]+)<""").find(capsXml)?.groupValues?.get(1)
                    ?.let { result[name] = rewrite(it) }
            }
        }
        return result
    }

    /** Топики GetEventProperties: элементы с атрибутом wstop:topic="true", путь — цепочка вложенных элементов */
    private fun parseTopics(xml: String): List<String> {
        val topics = mutableListOf<String>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            val stack = mutableListOf<String>()
            var inTopicSet = false
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        val name = parser.name.substringAfter(':')
                        if (name == "TopicSet") {
                            inTopicSet = true
                        } else if (inTopicSet) {
                            stack += name
                            val isTopic = (0 until parser.attributeCount).any {
                                parser.getAttributeName(it).substringAfter(':') == "topic" && parser.getAttributeValue(it) == "true"
                            }
                            if (isTopic) topics += stack.joinToString("/")
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        val name = parser.name.substringAfter(':')
                        if (name == "TopicSet") inTopicSet = false else if (inTopicSet && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return topics
    }

    private fun parseUtc(xml: String): Long? {
        val block = blocks(xml, "UTCDateTime").firstOrNull() ?: return null
        fun v(tag: String) = first(block, tag)?.toIntOrNull()
        return try {
            LocalDateTime.of(v("Year") ?: return null, v("Month") ?: return null, v("Day") ?: return null,
                v("Hour") ?: return null, v("Minute") ?: return null, v("Second") ?: return null)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- Вывод и regex-помощники ----------------

    private fun section(out: StringBuilder, title: String) {
        if (out.isNotEmpty()) out.appendLine()
        out.appendLine("━━ $title ━━")
    }

    private fun line(out: StringBuilder, label: String, value: String?) {
        if (!value.isNullOrBlank()) out.appendLine("$label: ${value.trim()}")
    }

    private fun first(xml: String, tag: String): String? =
        Regex("""<(?:\w+:)?$tag(?:\s[^>]*)?>([^<]*)</""").find(xml)?.groupValues?.get(1)?.trim()
            ?.replace("&amp;", "&")?.ifBlank { null }

    private fun texts(xml: String, tag: String): List<String> =
        Regex("""<(?:\w+:)?$tag(?:\s[^>]*)?>([^<]*)</""").findAll(xml).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    private fun blocks(xml: String, tag: String): List<String> =
        Regex("""<(?:\w+:)?$tag\b[^>]*?(?<!/)>(.*?)</(?:\w+:)?$tag>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }.toList()

    /** Атрибут первого вхождения тега (блок из [blocks] включает открывающий тег с атрибутами) */
    private fun attr(xml: String, tag: String, name: String): String? =
        Regex("""<(?:\w+:)?$tag\b[^>]*\b$name="([^"]+)"""").find(xml)?.groupValues?.get(1)
}
