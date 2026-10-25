package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.denmediaipcams.data.CameraShareCode
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.ClockPosition
import com.example.denmediaipcams.model.FocusAvailability
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.KnownPort
import com.example.denmediaipcams.model.PortKind
import com.example.denmediaipcams.model.Preset
import com.example.denmediaipcams.model.StreamProfile
import com.example.denmediaipcams.model.VideoRotation
import com.example.denmediaipcams.ui.components.QrCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Передача камеры QR кодом: все настройки доходят без изменений, кроме id */
@RunWith(AndroidJUnit4::class)
class CameraShareCodeTest {

    private val camera = IpCamera(
        id = "local-id",
        name = "Двор «Север» @ #1",
        ipAddress = "192.168.1.64",
        rtspPort = 554,
        httpPort = 80,
        onvifPort = 8000,
        dahuaPort = 0,
        knownPorts = listOf(KnownPort(554, setOf(PortKind.RTSP)), KnownPort(80, setOf(PortKind.HTTP, PortKind.ONVIF))),
        rtspPath = "/Streaming/Channels/101",
        username = "admin",
        password = "p@ss:word/#?%",
        protocol = CameraProtocol.HIKVISION,
        ptzInvertPan = true,
        focusAvailability = FocusAvailability.NO,
        presets = IpCamera.defaultPresets().mapIndexed { i, p -> if (i < 3) p.copy(name = "Ворота $i", isSaved = true) else p },
        streamProfiles = listOf(
            StreamProfile(name = "Основной", rtspPath = "/Streaming/Channels/101"),
            StreamProfile(name = "Дополнительный", rtspPath = "/Streaming/Channels/102")
        ),
        hikvisionArchive = true,
        clockPosition = ClockPosition.BOTTOM_START,
        videoRotation = VideoRotation.UPSIDE_DOWN,
        autoSyncTime = false
    ).let { it.copy(activeStreamProfileId = it.streamProfiles[1].id) }

    /** id потоков создаются заново при чтении: сравниваем выбранный поток по номеру, а не по id */
    private fun IpCamera?.normalized(): IpCamera? = this?.copy(
        streamProfiles = streamProfiles.map { it.copy(id = "") },
        activeStreamProfileId = streamProfiles.indexOfFirst { it.id == activeStreamProfileId }.toString()
    )

    private fun assertSame(expected: IpCamera, actual: IpCamera?) = assertEquals(expected.normalized(), actual.normalized())

    @Test
    fun roundTripKeepsAllSettings() {
        val code = CameraShareCode.encode(camera, includeCredentials = true)
        android.util.Log.i("CameraShareCodeTest", "Длина кода: ${code.length}")
        assertSame(camera.copy(id = ""), CameraShareCode.decode(code))
    }

    @Test
    fun withoutCredentials() {
        val decoded = CameraShareCode.decode(CameraShareCode.encode(camera, includeCredentials = false))
        assertSame(camera.copy(id = "", username = "", password = ""), decoded)
    }

    @Test
    fun defaultPresetsAreRestored() {
        val plain = IpCamera(id = "x", name = "Камера", ipAddress = "10.0.0.5")
        assertEquals(plain.copy(id = ""), CameraShareCode.decode(CameraShareCode.encode(plain, true)))
    }

    @Test
    fun qrImageDecodesBackToCamera() {
        val code = CameraShareCode.encode(camera, includeCredentials = true)
        val text = QrCodes.decode(QrCodes.encode(code, 800))
        assertEquals(code, text)
        assertSame(camera.copy(id = ""), CameraShareCode.decode(text!!))
    }

    /**
     * Необязательная проверка на снимке экрана с окном «Поделиться»: путь к PNG на устройстве передаётся
     * аргументом -e qrImage (например, /data/local/tmp/share.png), без него тест ничего не делает
     */
    @Test
    fun screenshotWithShareDialogDecodes() {
        val path = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("qrImage") ?: return
        val bitmap = android.graphics.BitmapFactory.decodeFile(path)
        val text = QrCodes.decode(bitmap)
        val decoded = text?.let { CameraShareCode.decode(it) }
        android.util.Log.i("CameraShareCodeTest", "Со снимка: ${decoded?.name} ${decoded?.ipAddress}, длина кода ${text?.length}")
        org.junit.Assert.assertNotNull(decoded)
    }

    @Test
    fun foreignCodesAreRejected() {
        assertNull(CameraShareCode.decode("https://example.com"))
        assertNull(CameraShareCode.decode("DMIPCAM1:not-base64!!"))
        assertNull(CameraShareCode.decode("DMIPCAM1:"))
    }
}
