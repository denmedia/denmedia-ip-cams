package com.example.denmediaipcams.data

import com.example.denmediaipcams.model.CameraOrientation
import com.example.denmediaipcams.model.FocusAvailability
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.ClockPosition
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.KnownPort
import com.example.denmediaipcams.model.PortKind
import com.example.denmediaipcams.model.Preset
import com.example.denmediaipcams.model.StreamProfile
import com.example.denmediaipcams.model.VideoRotation
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Настройки камеры в JSON: один формат и для сохранённого списка камер (CameraRepository),
 * и для передачи камеры QR кодом (CameraShareCode).
 */
object CameraJson {

    fun toJson(cam: IpCamera): JSONObject = JSONObject().apply {
        put("id", cam.id)
        put("name", cam.name)
        put("ipAddress", cam.ipAddress)
        put("rtspPort", cam.rtspPort)
        put("httpPort", cam.httpPort)
        put("onvifPort", cam.onvifPort)
        put("dahuaPort", cam.dahuaPort)
        put("knownPorts", JSONArray().also { array ->
            cam.knownPorts.forEach { known ->
                array.put(JSONObject().apply {
                    put("port", known.port)
                    put("kinds", JSONArray(known.kinds.map { it.name }))
                })
            }
        })
        put("rtspPath", cam.rtspPath)
        put("username", cam.username)
        put("password", cam.password)
        put("protocol", cam.protocol.name)
        put("ptzSpeed", cam.ptzSpeed.toDouble())
        put("ptzRepeatMode", cam.ptzRepeatMode)
        put("ptzInvertPan", cam.ptzInvertPan)
        put("ptzInvertTilt", cam.ptzInvertTilt)
        put("focusAvailability", cam.focusAvailability.name)
        put("autoLowerQuality", cam.autoLowerQuality)
        put("snapshotThumbnail", cam.snapshotThumbnail)
        put("dahuaApi", cam.dahuaApi)
        put("sdHttpArchive", cam.sdHttpArchive)
        put("hikvisionArchive", cam.hikvisionArchive)
        put("cameraAudioWithApp", cam.cameraAudioWithApp)
        put("dvripArchive", cam.dvripArchive)
        put("dvripPort", cam.dvripPort)
        put("hipcamPort", cam.hipcamPort)
        put("motionAlerts", cam.motionAlerts)
        put("showDateTime", cam.showDateTime)
        put("clockPosition", cam.clockPosition.name)
        put("videoRotation", cam.videoRotation.name)
        put("hardwareRotation", cam.hardwareRotation.name)
        cam.rotationBase?.let { put("rotationBase", it.encode()) }
        put("archiveBlockEnabled", cam.archiveBlockEnabled)
        put("archiveBlockCount", cam.archiveBlockCount)
        put("archiveBlockHideOnMetered", cam.archiveBlockHideOnMetered)
        put("autoSyncTime", cam.autoSyncTime)
        cam.activeStreamProfileId?.let { put("activeStreamProfileId", it) }

        val streamProfilesArray = JSONArray()
        for (sp in cam.streamProfiles) {
            streamProfilesArray.put(JSONObject().apply {
                put("id", sp.id)
                put("name", sp.name)
                put("rtspPath", sp.rtspPath)
            })
        }
        put("streamProfiles", streamProfilesArray)

        val presetsArray = JSONArray()
        for (p in cam.presets) {
            presetsArray.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("token", p.token)
                put("saved", p.isSaved)
            })
        }
        put("presets", presetsArray)
    }

    /**
     * Камера из JSON; недостающие поля получают значения по умолчанию.
     * [defaultAutoSyncTime] — значение для записей без поля autoSyncTime (у камер, сохранённых до появления опции, — false)
     */
    fun fromJson(obj: JSONObject, defaultAutoSyncTime: Boolean = true): IpCamera {
        val presetsArray = obj.optJSONArray("presets")
        val presetsList = mutableListOf<Preset>()
        if (presetsArray != null) {
            for (j in 0 until presetsArray.length()) {
                val pObj = presetsArray.getJSONObject(j)
                presetsList.add(
                    Preset(
                        id = pObj.optInt("id", j + 1),
                        name = pObj.optString("name", "Пресет ${j + 1}"),
                        token = pObj.optString("token", (j + 1).toString()),
                        isSaved = pObj.optBoolean("saved", false)
                    )
                )
            }
        }

        val streamProfilesArray = obj.optJSONArray("streamProfiles")
        val streamProfilesList = mutableListOf<StreamProfile>()
        if (streamProfilesArray != null) {
            for (j in 0 until streamProfilesArray.length()) {
                val spObj = streamProfilesArray.getJSONObject(j)
                streamProfilesList.add(
                    StreamProfile(
                        id = spObj.optString("id", UUID.randomUUID().toString()),
                        name = spObj.optString("name", "Поток ${j + 1}"),
                        rtspPath = spObj.optString("rtspPath", "")
                    )
                )
            }
        }

        return IpCamera(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", "Камера IP"),
            ipAddress = obj.optString("ipAddress", "192.168.1.100"),
            rtspPort = obj.optInt("rtspPort", IpCamera.DEFAULT_RTSP_PORT),
            httpPort = obj.optInt("httpPort", IpCamera.DEFAULT_HTTP_PORT),
            onvifPort = obj.optInt("onvifPort", IpCamera.DEFAULT_ONVIF_PORT),
            dahuaPort = obj.optInt("dahuaPort", IpCamera.DEFAULT_DAHUA_PORT),
            knownPorts = parseKnownPorts(obj.optJSONArray("knownPorts")),
            rtspPath = obj.optString("rtspPath", IpCamera.DEFAULT_RTSP_PATH),
            username = obj.optString("username", ""),
            password = obj.optString("password", ""),
            protocol = enumOr(obj.optString("protocol"), CameraProtocol.ONVIF),
            ptzSpeed = obj.optDouble("ptzSpeed", 0.5).toFloat(),
            ptzRepeatMode = obj.optBoolean("ptzRepeatMode", false),
            ptzInvertPan = obj.optBoolean("ptzInvertPan", false),
            ptzInvertTilt = obj.optBoolean("ptzInvertTilt", false),
            focusAvailability = enumOr(obj.optString("focusAvailability"), FocusAvailability.AUTO),
            presets = if (presetsList.isNotEmpty()) presetsList else IpCamera.defaultPresets(),
            streamProfiles = streamProfilesList,
            activeStreamProfileId = if (obj.has("activeStreamProfileId")) obj.getString("activeStreamProfileId") else null,
            autoLowerQuality = obj.optBoolean("autoLowerQuality", true),
            snapshotThumbnail = obj.optBoolean("snapshotThumbnail", false),
            dahuaApi = obj.optBoolean("dahuaApi", false),
            sdHttpArchive = obj.optBoolean("sdHttpArchive", false),
            hikvisionArchive = obj.optBoolean("hikvisionArchive", false),
            cameraAudioWithApp = obj.optBoolean("cameraAudioWithApp", true),
            dvripArchive = obj.optBoolean("dvripArchive", false),
            dvripPort = obj.optInt("dvripPort", IpCamera.DEFAULT_DVRIP_PORT),
            hipcamPort = obj.optInt("hipcamPort", 0),
            motionAlerts = obj.optBoolean("motionAlerts", false),
            showDateTime = obj.optBoolean("showDateTime", true),
            clockPosition = enumOr(obj.optString("clockPosition"), ClockPosition.TOP_END),
            videoRotation = enumOr(obj.optString("videoRotation"), VideoRotation.NONE),
            hardwareRotation = enumOr(obj.optString("hardwareRotation"), VideoRotation.NONE),
            rotationBase = CameraOrientation.decode(obj.optString("rotationBase", "").ifEmpty { null }),
            archiveBlockEnabled = obj.optBoolean("archiveBlockEnabled", true),
            archiveBlockCount = obj.optInt("archiveBlockCount", IpCamera.MAX_ARCHIVE_BLOCK_COUNT)
                .coerceIn(1, IpCamera.MAX_ARCHIVE_BLOCK_COUNT),
            archiveBlockHideOnMetered = obj.optBoolean("archiveBlockHideOnMetered", false),
            autoSyncTime = obj.optBoolean("autoSyncTime", defaultAutoSyncTime)
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        runCatching { enumValueOf<T>(name.orEmpty()) }.getOrDefault(default)

    private fun parseKnownPorts(array: JSONArray?): List<KnownPort> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val port = item.optInt("port", 0)
            if (port !in 1..65535) return@mapNotNull null
            val kinds = item.optJSONArray("kinds")?.let { kindsArray ->
                (0 until kindsArray.length()).mapNotNull { i ->
                    runCatching { PortKind.valueOf(kindsArray.getString(i)) }.getOrNull()
                }.toSet()
            }.orEmpty()
            KnownPort(port, kinds)
        }
    }
}
