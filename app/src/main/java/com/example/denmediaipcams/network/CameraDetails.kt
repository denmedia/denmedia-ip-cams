package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Сведения об устройстве из ONVIF (GetDeviceInformation и GetNetworkInterfaces); недоступное поле — null */
data class OnvifDevice(
    val manufacturer: String?,
    val model: String?,
    val firmware: String?,
    val serial: String?,
    val mac: String?,
    val ipv4: String?,
    val dhcp: Boolean?
)

/** Поток камеры по данным ONVIF-профиля: что камера заявляет о кодеке, разрешении и скорости потока */
data class OnvifStreamInfo(
    val token: String,
    val name: String?,
    val codec: String?,
    val width: Int,
    val height: Int,
    val fps: Int?,
    val bitrateKbps: Int?,
    val gop: Int?,
    /** Путь и параметры RTSP-адреса профиля (rtsp://host:port<путь>) */
    val rtspPath: String? = null
)

data class OnvifDetails(val device: OnvifDevice, val streams: List<OnvifStreamInfo>)

/** Настройка изображения камеры (ONVIF Imaging) с допустимым диапазоном */
data class ImagingParam(val key: String, val label: String, val value: Float, val min: Float, val max: Float)

/**
 * Дополнительные сведения и настройки камеры сверх тех, что нужны для просмотра: данные устройства и профилей
 * потока по ONVIF, настройки детекции движения (Hipcam, Dahua), яркость/контраст/насыщенность/резкость.
 * Все запросы — по требованию пользователя (окно информации, панель изображения); блокирующие вызовы идут на IO.
 */
object CameraDetails {

    private const val NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl"
    private const val NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl"
    private const val NS_IMAGING = "http://www.onvif.org/ver20/imaging/wsdl"
    private const val NS_SCHEMA = "http://www.onvif.org/ver10/schema"

    /** Сведения об устройстве почти не меняются — повторно камеру не опрашиваем, пока открыта панель информации */
    private const val CACHE_TTL_MS = 10 * 60_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, OnvifDetails>>()

    /** Настройки изображения, которые умеем читать и менять, в порядке показа */
    private val IMAGING_KEYS = listOf(
        "Brightness" to "Яркость",
        "Contrast" to "Контраст",
        "ColorSaturation" to "Насыщенность",
        "Sharpness" to "Резкость"
    )

    private fun cacheKey(camera: IpCamera) =
        "${camera.ipAddress}:${camera.effectiveOnvifPort}:${camera.username}:${camera.password.hashCode()}"

    // ---------------- Устройство и потоки (ONVIF) ----------------

    /** null — камера не отвечает по ONVIF или адрес камеры задан ссылкой */
    suspend fun onvifDetails(camera: IpCamera, forceRefresh: Boolean = false): OnvifDetails? = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext null
        val key = cacheKey(camera)
        if (!forceRefresh) {
            cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_TTL_MS }?.let { return@withContext it.second }
        }
        try {
            val endpoints = OnvifClient.discover(camera.ipAddress, camera.effectiveOnvifPort, camera.username, camera.password)
                ?: return@withContext null
            val deviceUrl = "http://${camera.ipAddress}:${camera.effectiveOnvifPort}/onvif/device_service"
            fun call(url: String, body: String) =
                OnvifClient.request(url, body, camera.username, camera.password, endpoints.clockOffsetMs)

            val info = call(deviceUrl, """<GetDeviceInformation xmlns="$NS_DEVICE"/>""")
            val network = call(deviceUrl, """<GetNetworkInterfaces xmlns="$NS_DEVICE"/>""")
            val profiles = call(endpoints.mediaUrl, """<GetProfiles xmlns="$NS_MEDIA"/>""").orEmpty()
            val streams = parseStreams(profiles).map { stream ->
                stream.copy(
                    rtspPath = OnvifClient.getStreamUri(endpoints, stream.token, camera.username, camera.password)?.let(::streamPath)
                )
            }
            OnvifDetails(parseDevice(info.orEmpty(), network.orEmpty()), streams).also {
                cache[key] = System.currentTimeMillis() to it
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseDevice(deviceInformation: String, networkInterfaces: String): OnvifDevice {
        fun tag(xml: String, name: String) =
            Regex("""<(?:\w+:)?$name>([^<]*)<""").find(xml)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        return OnvifDevice(
            manufacturer = tag(deviceInformation, "Manufacturer"),
            model = tag(deviceInformation, "Model"),
            firmware = tag(deviceInformation, "FirmwareVersion"),
            // Часть камер отдаёт серийный номер из одних нулей — это «нет номера»
            serial = tag(deviceInformation, "SerialNumber")?.takeIf { s -> s.any { it != '0' } },
            mac = tag(networkInterfaces, "HwAddress")?.uppercase()?.replace('-', ':'),
            ipv4 = Regex("""<(?:\w+:)?Address>(\d{1,3}(?:\.\d{1,3}){3})<""").find(networkInterfaces)?.groupValues?.get(1),
            dhcp = tag(networkInterfaces, "DHCP")?.toBooleanStrictOrNull()
        )
    }

    internal fun parseStreams(profilesXml: String): List<OnvifStreamInfo> = Regex(
        """<(?:\w+:)?Profiles\b[^>]*\btoken="([^"]+)"[^>]*>(.*?)</(?:\w+:)?Profiles>""",
        RegexOption.DOT_MATCHES_ALL
    ).findAll(profilesXml).mapNotNull { match ->
        val encoder = Regex(
            """<(?:\w+:)?VideoEncoderConfiguration\b.*?</(?:\w+:)?VideoEncoderConfiguration>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(match.groupValues[2])?.value ?: return@mapNotNull null
        fun intTag(name: String) = Regex("""<(?:\w+:)?$name>(\d+)<""").find(encoder)?.groupValues?.get(1)?.toIntOrNull()
        OnvifStreamInfo(
            token = match.groupValues[1],
            name = Regex("""^\s*<(?:\w+:)?Name>([^<]*)<""").find(match.groupValues[2])?.groupValues?.get(1)?.trim()?.ifBlank { null },
            codec = Regex("""<(?:\w+:)?Encoding>([^<]+)<""").find(encoder)?.groupValues?.get(1)?.trim(),
            width = intTag("Width") ?: 0,
            height = intTag("Height") ?: 0,
            fps = intTag("FrameRateLimit"),
            bitrateKbps = intTag("BitrateLimit"),
            gop = intTag("GovLength")
        )
    }.toList()

    /** Путь и параметры RTSP-адреса без хоста и порта: так хранится путь потока в настройках камеры */
    internal fun streamPath(uri: String): String =
        uri.replaceFirst(Regex("""^\w+://[^/]*"""), "").ifEmpty { "/" }

    // ---------------- Пресеты ----------------

    /** Название положения → ячейка: по числовому токену (так сохраняют камеры, для которых ячейка = номер) */
    internal fun parsePresetNames(response: String): Map<Int, String> = Regex(
        """<(?:\w+:)?Preset\b[^>]*\btoken="([^"]+)"[^>]*>(.*?)</(?:\w+:)?Preset>""",
        RegexOption.DOT_MATCHES_ALL
    ).findAll(response).mapNotNull { match ->
        val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val name = Regex("""<(?:\w+:)?Name>([^<]*)<""").find(match.groupValues[2])?.groupValues?.get(1)?.trim()
        if (name.isNullOrBlank() || Regex("""^Preset\s*\d+$""", RegexOption.IGNORE_CASE).matches(name) || name == id.toString()) null
        else id to name
    }.toMap()

    // ---------------- Детекция движения (Hipcam, Dahua) ----------------

    /**
     * Строки отчёта о настройках детекции движения; null — камера не отдаёт их ни Dahua-, ни Hipcam-интерфейсом.
     * Только чтение: ничего на камере не меняется.
     */
    suspend fun motionReport(camera: IpCamera): List<String>? = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext null
        val base = "http://${camera.ipAddress}:${camera.effectiveHttpPort}"
        try {
            if (camera.hasDahuaApi) {
                val response = CameraHttp.request(
                    "$base/cgi-bin/configManager.cgi?action=getConfig&name=MotionDetect",
                    user = camera.username, pass = camera.password
                )
                if (response.isSuccess) parseDahuaMotion(response.body)?.let { return@withContext it }
            } else {
                val response = CameraHttp.request(
                    "$base/cgi-bin/hi3510/getmdattr.cgi",
                    user = camera.username, pass = camera.password
                )
                if (response.isSuccess) parseHipcamMotion(response.body)?.let { return@withContext it }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Hipcam: четыре зоны var mN_enable / mN_x / mN_y / mN_w / mN_h / mN_sensitivity */
    internal fun parseHipcamMotion(body: String): List<String>? {
        val values = Regex("""var\s+m(\d)_(\w+)\s*=\s*"([^"]*)"""").findAll(body)
            .groupBy({ it.groupValues[1].toInt() }, { it.groupValues[2] to it.groupValues[3] })
            .mapValues { it.value.toMap() }
        if (values.isEmpty()) return null
        val lines = mutableListOf("Детекция движения (Hipcam):")
        values.toSortedMap().forEach { (zone, v) ->
            val enabled = v["enable"] == "1"
            lines += buildString {
                append("  Зона $zone: ${if (enabled) "включена" else "выключена"}")
                v["sensitivity"]?.let { append(", чувствительность $it") }
                if (enabled && v["w"] != null && v["h"] != null) append(", область ${v["w"]}×${v["h"]} (от ${v["x"]}, ${v["y"]})")
            }
        }
        return lines
    }

    /** Dahua: MotionDetect[0].Enable / Level и битовые маски окон Region[]; охват — доля включённых ячеек сетки */
    internal fun parseDahuaMotion(body: String): List<String>? {
        val enable = Regex("""MotionDetect\[0]\.Enable=(\w+)""").find(body)?.groupValues?.get(1) ?: return null
        val lines = mutableListOf("Детекция движения (Dahua):")
        lines += "  Включена: ${if (enable.equals("true", ignoreCase = true)) "да" else "нет"}"
        Regex("""MotionDetect\[0]\.Level=(\d+)""").find(body)?.let { lines += "  Уровень чувствительности: ${it.groupValues[1]} (1–6)" }
        val windows = Regex("""MotionDetect\[0]\.MotionDetectWindow\[(\d+)]\.(\w+)(?:\[(\d+)])?=(.*)""").findAll(body)
            .groupBy { it.groupValues[1].toInt() }
        windows.toSortedMap().forEach { (index, entries) ->
            val name = entries.firstOrNull { it.groupValues[2] == "Name" }?.groupValues?.get(4)?.trim() ?: "Окно ${index + 1}"
            val rows = entries.filter { it.groupValues[2] == "Region" }.mapNotNull { it.groupValues[4].trim().toLongOrNull() }
            if (rows.isNotEmpty()) {
                val covered = rows.sumOf { java.lang.Long.bitCount(it) }
                // В строке сетки 22 ячейки
                lines += "  $name: охват ${covered * 100 / (rows.size * 22)}% кадра"
            } else {
                lines += "  $name"
            }
        }
        return lines
    }

    // ---------------- Изображение (ONVIF Imaging) ----------------

    private class ImagingTarget(val url: String, val sourceToken: String, val clockOffsetMs: Long)

    private fun resolveImagingTarget(camera: IpCamera): ImagingTarget? {
        val endpoints = OnvifClient.discover(camera.ipAddress, camera.effectiveOnvifPort, camera.username, camera.password)
            ?: return null
        val imagingUrl = endpoints.imagingUrl ?: return null
        val token = OnvifClient.getProfiles(endpoints, camera.username, camera.password).firstNotNullOfOrNull { it.videoSourceToken }
            ?: OnvifClient.request(
                endpoints.mediaUrl, """<GetVideoSources xmlns="$NS_MEDIA"/>""",
                camera.username, camera.password, endpoints.clockOffsetMs
            )?.let { Regex("""<(?:\w+:)?VideoSources\b[^>]*\btoken="([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: return null
        return ImagingTarget(imagingUrl, token, endpoints.clockOffsetMs)
    }

    /**
     * Текущие настройки изображения; пустой список — камера не отдаёт их; null — недоступно ни по ONVIF Imaging,
     * ни родным интерфейсом Hipcam. Если камера Hipcam принимает изменения только своим интерфейсом (по ONVIF —
     * только чтение), значения берутся оттуда, а запомненный способ используется и при записи.
     */
    suspend fun imagingParams(camera: IpCamera): List<ImagingParam>? = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext null
        val onvif = try {
            onvifImagingParams(camera)
        } catch (_: Exception) {
            null
        }
        if (camera.hasDahuaApi) return@withContext onvif
        val hipcam = try {
            hipcamImagingParams(camera, onvif.orEmpty())
        } catch (_: Exception) {
            null
        }
        if (hipcam != null) hipcamImagingCameras += cacheKey(camera)
        hipcam ?: onvif
    }

    private fun onvifImagingParams(camera: IpCamera): List<ImagingParam>? {
        val target = resolveImagingTarget(camera) ?: return null
        fun call(operation: String) = OnvifClient.request(
            target.url,
            """<$operation xmlns="$NS_IMAGING"><VideoSourceToken>${OnvifClient.xmlEscape(target.sourceToken)}</VideoSourceToken></$operation>""",
            camera.username, camera.password, target.clockOffsetMs
        )
        val settings = call("GetImagingSettings") ?: return null
        return parseImaging(settings, call("GetOptions").orEmpty())
    }

    private fun hipcamImagingParams(camera: IpCamera, onvif: List<ImagingParam>): List<ImagingParam>? {
        val response = CameraHttp.request(
            "http://${camera.ipAddress}:${camera.effectiveHipcamPort}/cgi-bin/hi3510/getimageattr.cgi",
            user = camera.username, pass = camera.password
        )
        return if (response.isSuccess) parseHipcamImaging(response.body, onvif) else null
    }

    /** Hipcam: var brightness / contrast / saturation / sharpness; диапазон — из ONVIF, если камера его заявила */
    internal fun parseHipcamImaging(body: String, onvif: List<ImagingParam>): List<ImagingParam>? {
        val values = Regex("""var\s+(\w+)\s*=\s*"([^"]*)"""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        val params = IMAGING_KEYS.mapNotNull { (key, label) ->
            val value = values[HIPCAM_IMAGING_NAMES.getValue(key)]?.toFloatOrNull() ?: return@mapNotNull null
            val known = onvif.firstOrNull { it.key == key }
            ImagingParam(key, label, value, known?.min ?: 0f, known?.max ?: 100f)
        }
        return params.ifEmpty { null }
    }

    private val HIPCAM_IMAGING_NAMES = mapOf(
        "Brightness" to "brightness",
        "Contrast" to "contrast",
        "ColorSaturation" to "saturation",
        "Sharpness" to "sharpness"
    )

    /** Камеры, у которых изображение меняется родным интерфейсом Hipcam, а не по ONVIF */
    private val hipcamImagingCameras: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal fun parseImaging(settings: String, options: String): List<ImagingParam> = IMAGING_KEYS.mapNotNull { (key, label) ->
        val value = Regex("""<(?:\w+:)?$key>\s*([-\d.]+)\s*<""").find(settings)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return@mapNotNull null
        val range = Regex("""<(?:\w+:)?$key>\s*<(?:\w+:)?Min>\s*([-\d.]+)\s*</(?:\w+:)?Min>\s*<(?:\w+:)?Max>\s*([-\d.]+)\s*<""")
            .find(options)
        val min = range?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val max = range?.groupValues?.get(2)?.toFloatOrNull() ?: 100f
        ImagingParam(key, label, value, min, if (max > min) max else min + 100f)
    }

    /** Меняет одну настройку изображения; true — камера приняла значение */
    suspend fun setImagingParam(camera: IpCamera, key: String, value: Float): Boolean = withContext(Dispatchers.IO) {
        if (IMAGING_KEYS.none { it.first == key }) return@withContext false
        try {
            if (cacheKey(camera) in hipcamImagingCameras) return@withContext setHipcamImaging(camera, key, value)
            val target = resolveImagingTarget(camera)
            val accepted = target != null && OnvifClient.request(
                target.url,
                """<SetImagingSettings xmlns="$NS_IMAGING"><VideoSourceToken>${OnvifClient.xmlEscape(target.sourceToken)}</VideoSourceToken>""" +
                    """<ImagingSettings xmlns="$NS_SCHEMA"><$key>${String.format(Locale.US, "%.1f", value)}</$key></ImagingSettings>""" +
                    "<ForcePersistence>true</ForcePersistence></SetImagingSettings>",
                camera.username, camera.password, target.clockOffsetMs
            ) != null
            accepted || (!camera.hasDahuaApi && setHipcamImaging(camera, key, value))
        } catch (_: Exception) {
            false
        }
    }

    private fun setHipcamImaging(camera: IpCamera, key: String, value: Float): Boolean {
        val name = HIPCAM_IMAGING_NAMES[key] ?: return false
        val response = CameraHttp.request(
            "http://${camera.ipAddress}:${camera.effectiveHipcamPort}/cgi-bin/hi3510/setimageattr.cgi?-$name=${value.roundToInt()}",
            user = camera.username, pass = camera.password
        )
        return response.isSuccess && response.body.contains("ok", ignoreCase = true)
    }
}
