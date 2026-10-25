package com.example.denmediaipcams

import com.example.denmediaipcams.network.CameraDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDetailsTest {

    @Test
    fun parsesDeviceInformationAndNetwork() {
        val info = """<tds:GetDeviceInformationResponse><tds:Manufacturer>IPCAM</tds:Manufacturer><tds:Model>C6F0SoZ3N0PcL2</tds:Model>""" +
            """<tds:FirmwareVersion>V19.1.65</tds:FirmwareVersion><tds:SerialNumber>001122334455</tds:SerialNumber></tds:GetDeviceInformationResponse>"""
        val network = """<tds:NetworkInterfaces token="eth0"><tt:Info><tt:HwAddress>00:11:22:33:44:55</tt:HwAddress></tt:Info>""" +
            """<tt:IPv4><tt:Config><tt:FromDHCP><tt:Address>192.168.1.204</tt:Address></tt:FromDHCP><tt:DHCP>true</tt:DHCP></tt:Config></tt:IPv4></tds:NetworkInterfaces>"""
        val device = CameraDetails.parseDevice(info, network)
        assertEquals("IPCAM", device.manufacturer)
        assertEquals("C6F0SoZ3N0PcL2", device.model)
        assertEquals("V19.1.65", device.firmware)
        assertEquals("001122334455", device.serial)
        assertEquals("00:11:22:33:44:55", device.mac)
        assertEquals("192.168.1.204", device.ipv4)
        assertEquals(true, device.dhcp)
    }

    @Test
    fun serialOfZerosMeansNoSerial() {
        val device = CameraDetails.parseDevice("<tds:SerialNumber>0000000</tds:SerialNumber><tds:Model>IPC</tds:Model>", "")
        assertNull(device.serial)
        assertNull(device.mac)
        assertEquals("IPC", device.model)
    }

    @Test
    fun parsesStreamProfiles() {
        val xml = """<trt:GetProfilesResponse><trt:Profiles token="IPCProfilesToken0"><tt:Name>MainStream</tt:Name>""" +
            """<tt:VideoEncoderConfiguration token="v0"><tt:Name>VideoEncoderConfig0</tt:Name><tt:Encoding>H264</tt:Encoding>""" +
            """<tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution><tt:Quality>10</tt:Quality>""" +
            """<tt:RateControl><tt:FrameRateLimit>30</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>1200</tt:BitrateLimit></tt:RateControl>""" +
            """<tt:H264><tt:GovLength>50</tt:GovLength></tt:H264></tt:VideoEncoderConfiguration></trt:Profiles>""" +
            """<trt:Profiles token="IPCProfilesToken1"><tt:Name>SubStream</tt:Name><tt:VideoEncoderConfiguration token="v1"><tt:Encoding>H264</tt:Encoding>""" +
            """<tt:Resolution><tt:Width>640</tt:Width><tt:Height>360</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles></trt:GetProfilesResponse>"""
        val streams = CameraDetails.parseStreams(xml)
        assertEquals(2, streams.size)
        assertEquals("IPCProfilesToken0", streams[0].token)
        assertEquals("MainStream", streams[0].name)
        assertEquals("H264", streams[0].codec)
        assertEquals(1920, streams[0].width)
        assertEquals(1080, streams[0].height)
        assertEquals(30, streams[0].fps)
        assertEquals(1200, streams[0].bitrateKbps)
        assertEquals(50, streams[0].gop)
        assertEquals(360, streams[1].height)
        assertNull(streams[1].fps)
    }

    @Test
    fun parsesPresetNamesAndSkipsStandardOnes() {
        val xml = """<tptz:GetPresetsResponse><tptz:Preset token="1"><tt:Name>Preset001</tt:Name></tptz:Preset>""" +
            """<tptz:Preset token="2"><tt:Name>Ворота</tt:Name></tptz:Preset><tptz:Preset token="3"><tt:Name>3</tt:Name></tptz:Preset>""" +
            """<tptz:Preset token="abc"><tt:Name>Двор</tt:Name></tptz:Preset></tptz:GetPresetsResponse>"""
        assertEquals(mapOf(2 to "Ворота"), CameraDetails.parsePresetNames(xml))
    }

    @Test
    fun parsesHipcamMotionZones() {
        val body = """var m1_enable="1"; var m1_x="0"; var m1_y="85"; var m1_w="2560"; var m1_h="1834"; var m1_sensitivity="50"; var m1_threshold="0";
            var m2_enable="0"; var m2_x="2240"; var m2_y="0"; var m2_w="320"; var m2_h="426"; var m2_sensitivity="40";"""
        val lines = CameraDetails.parseHipcamMotion(body)!!
        assertEquals("Детекция движения (Hipcam):", lines[0])
        assertEquals("  Зона 1: включена, чувствительность 50, область 2560×1834 (от 0, 85)", lines[1])
        assertEquals("  Зона 2: выключена, чувствительность 40", lines[2])
        assertNull(CameraDetails.parseHipcamMotion("Error 404"))
    }

    @Test
    fun parsesDahuaMotionCoverage() {
        val full = (0 until 18).joinToString("\n") { "table.MotionDetect[0].MotionDetectWindow[0].Region[$it]=4194303" }
        val half = (0 until 18).joinToString("\n") { "table.MotionDetect[0].MotionDetectWindow[1].Region[$it]=2047" }
        val body = "table.MotionDetect[0].Enable=true\ntable.MotionDetect[0].Level=3\n" +
            "table.MotionDetect[0].MotionDetectWindow[0].Name=Region1\n$full\n" +
            "table.MotionDetect[0].MotionDetectWindow[1].Name=Region2\n$half"
        val lines = CameraDetails.parseDahuaMotion(body)!!
        assertEquals("  Включена: да", lines[1])
        assertEquals("  Уровень чувствительности: 3 (1–6)", lines[2])
        assertEquals("  Region1: охват 100% кадра", lines[3])
        assertEquals("  Region2: охват 50% кадра", lines[4])
        assertNull(CameraDetails.parseDahuaMotion("Error"))
    }

    @Test
    fun parsesImagingSettingsWithRanges() {
        val settings = """<timg:GetImagingSettingsResponse><timg:ImagingSettings><tt:Brightness>60.000000</tt:Brightness>""" +
            """<tt:ColorSaturation>106</tt:ColorSaturation></timg:ImagingSettings></timg:GetImagingSettingsResponse>"""
        val options = """<timg:ImagingOptions><tt:Brightness><tt:Min>0</tt:Min><tt:Max>255</tt:Max></tt:Brightness></timg:ImagingOptions>"""
        val params = CameraDetails.parseImaging(settings, options)
        assertEquals(listOf("Brightness", "ColorSaturation"), params.map { it.key })
        assertEquals(60f, params[0].value, 0.001f)
        assertEquals(255f, params[0].max, 0.001f)
        // Диапазон не заявлен — по умолчанию 0…100
        assertEquals(100f, params[1].max, 0.001f)
        assertTrue(CameraDetails.parseImaging("", "").isEmpty())
    }

    @Test
    fun parsesHipcamImagingWithOnvifRanges() {
        val body = """var brightness="50"; var saturation="106"; var sharpness="65"; var contrast="40"; var hue="50"; var wdr="off";"""
        val onvif = CameraDetails.parseImaging(
            "<tt:Brightness>50</tt:Brightness>",
            "<tt:Brightness><tt:Min>0</tt:Min><tt:Max>255</tt:Max></tt:Brightness>"
        )
        val params = CameraDetails.parseHipcamImaging(body, onvif)!!
        assertEquals(listOf("Brightness", "Contrast", "ColorSaturation", "Sharpness"), params.map { it.key })
        assertEquals(255f, params[0].max, 0.001f)
        assertEquals(40f, params[1].value, 0.001f)
        // Резкость ONVIF не отдаёт — диапазон по умолчанию
        assertEquals(100f, params[3].max, 0.001f)
        assertNull(CameraDetails.parseHipcamImaging("Error 404", emptyList()))
    }

    @Test
    fun streamPathKeepsQuery() {
        assertEquals("/onvif1", CameraDetails.streamPath("rtsp://192.168.1.23:554/onvif1"))
        assertEquals(
            "/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif",
            CameraDetails.streamPath("rtsp://192.168.1.22:554/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif")
        )
    }
}
