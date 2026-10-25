package com.example.denmediaipcams

import com.example.denmediaipcams.network.PtzController
import com.example.denmediaipcams.network.PtzController.PtzCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Определение поворота, зума и фокуса по ответам камер (ONVIF GetNodes и GetMoveOptions, Hikvision capabilities) */
class PtzCapsTest {

    private val controller = PtzController()

    private fun onvifNodes(spaces: String) = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body>
<tptz:GetNodesResponse><tptz:PTZNode token="PTZNODETOKEN"><tt:Name>PTZNODE</tt:Name>
<tt:SupportedPTZSpaces>$spaces<tt:PanTiltSpeedSpace><tt:URI>x</tt:URI></tt:PanTiltSpeedSpace>
</tt:SupportedPTZSpaces><tt:MaximumNumberOfPresets>255</tt:MaximumNumberOfPresets></tptz:PTZNode>
</tptz:GetNodesResponse></s:Body></s:Envelope>"""

    @Test
    fun onvifPanTiltAndZoom() {
        val xml = onvifNodes(
            "<tt:ContinuousPanTiltVelocitySpace><tt:URI>a</tt:URI></tt:ContinuousPanTiltVelocitySpace>" +
                "<tt:ContinuousZoomVelocitySpace><tt:URI>b</tt:URI></tt:ContinuousZoomVelocitySpace>"
        )
        assertEquals(PtzCaps(panTilt = true, zoom = true), controller.parseOnvifPtzNodes(xml))
    }

    @Test
    fun onvifZoomOnly() {
        // Вариофокальная камера: узел PTZ есть, но пространства поворота нет (пространство скорости не в счёт)
        val xml = onvifNodes("<tt:AbsoluteZoomPositionSpace><tt:URI>b</tt:URI></tt:AbsoluteZoomPositionSpace>")
        assertEquals(PtzCaps(panTilt = false, zoom = true), controller.parseOnvifPtzNodes(xml))
    }

    @Test
    fun onvifUnknownWithoutSpaces() {
        assertEquals(PtzCaps(null, null), controller.parseOnvifPtzNodes("<s:Envelope><s:Body/></s:Envelope>"))
    }

    @Test
    fun hikvisionCapabilities() {
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<PTZChanelCap version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<AbsolutePanTiltPositionSpace><XRange><Min>0</Min><Max>3600</Max></XRange></AbsolutePanTiltPositionSpace>
<ContinuousPanTiltSpace><XRange><Min>-100</Min><Max>100</Max></XRange></ContinuousPanTiltSpace>
<ContinuousZoomSpace><XRange><Min>-100</Min><Max>100</Max></XRange></ContinuousZoomSpace>
</PTZChanelCap>"""
        assertEquals(PtzCaps(panTilt = true, zoom = true), controller.parseHikvisionPtzCaps(200, body))
        val panOnly = body.replace(Regex("<ContinuousZoomSpace>.*</ContinuousZoomSpace>"), "")
        assertEquals(PtzCaps(panTilt = true, zoom = false), controller.parseHikvisionPtzCaps(200, panOnly))
    }

    @Test
    fun hikvisionWithoutPtz() {
        val notSupport = """<ResponseStatus version="2.0"><statusCode>4</statusCode>
<statusString>Invalid Operation</statusString><subStatusCode>notSupport</subStatusCode></ResponseStatus>"""
        assertTrue(controller.parseHikvisionPtzCaps(403, notSupport)!!.none)
        assertTrue(controller.parseHikvisionPtzCaps(404, "")!!.none)
        assertTrue(controller.parseHikvisionPtzCaps(200, "<PTZChanelCap/>")!!.none)
    }

    @Test
    fun onvifFocusMoveOptions() {
        // IPCAM: моторизованный объектив, все три способа перемещения
        val ipcam = """<timg:GetMoveOptionsResponse><timg:MoveOptions><tt:Absolute><tt:Position><tt:Min>-1.000000</tt:Min>
<tt:Max>1.000000</tt:Max></tt:Position></tt:Absolute><tt:Relative><tt:Distance><tt:Min>-1.000000</tt:Min>
<tt:Max>1.000000</tt:Max></tt:Distance></tt:Relative><tt:Continuous><tt:Speed><tt:Min>-1.000000</tt:Min>
<tt:Max>1.000000</tt:Max></tt:Speed></tt:Continuous></timg:MoveOptions></timg:GetMoveOptionsResponse>"""
        assertTrue(controller.parseOnvifFocusMoveOptions(ipcam))
        // NVT: заявляет только непрерывное перемещение, а фокуса у неё нет
        val nvt = """<timg:GetMoveOptionsResponse><timg:MoveOptions><tt:Continuous><tt:Speed><tt:Min>-5.00</tt:Min>
<tt:Max>5.00</tt:Max></tt:Speed></tt:Continuous></timg:MoveOptions></timg:GetMoveOptionsResponse>"""
        assertFalse(controller.parseOnvifFocusMoveOptions(nvt))
        assertFalse(controller.parseOnvifFocusMoveOptions("<s:Body><timg:GetMoveOptionsResponse><timg:MoveOptions/></timg:GetMoveOptionsResponse></s:Body>"))
    }

    @Test
    fun hikvisionFocus() {
        val motorized = """<ImageChannel version="2.0"><id>1</id>
<FocusConfiguration><focusStyle opt="AUTO,MANUAL,SEMIAUTOMATIC">SEMIAUTOMATIC</focusStyle></FocusConfiguration>
<IrcutFilter><IrcutFilterType opt="auto,day,night">auto</IrcutFilterType></IrcutFilter></ImageChannel>"""
        assertEquals(true, controller.parseHikvisionFocusCaps(200, motorized))
        assertEquals(false, controller.parseHikvisionFocusCaps(200, "<ImageChannel><IrcutFilter/></ImageChannel>"))
        assertEquals(false, controller.parseHikvisionFocusCaps(404, ""))
        assertNull(controller.parseHikvisionFocusCaps(0, "Network error"))
    }

    @Test
    fun hikvisionUnknown() {
        assertNull(controller.parseHikvisionPtzCaps(0, "Network error"))
        assertNull(controller.parseHikvisionPtzCaps(401, ""))
        assertNull(controller.parseHikvisionPtzCaps(403, "<subStatusCode>lowPrivilege</subStatusCode>"))
    }
}
