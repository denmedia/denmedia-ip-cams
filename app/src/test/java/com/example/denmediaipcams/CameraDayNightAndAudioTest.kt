package com.example.denmediaipcams

import com.example.denmediaipcams.model.DayNightMode
import com.example.denmediaipcams.network.DahuaStreamAudio
import com.example.denmediaipcams.network.PtzController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Режим день/ночь Hikvision и звук в потоке Dahua по настоящим форматам ответов */
class CameraDayNightAndAudioTest {

    private val controller = PtzController()

    // Ответ DS-I114W (V5.4.81) на GET /ISAPI/Image/channels/1/ircutFilter
    private val irCut = """<?xml version="1.0" encoding="UTF-8"?>
<IrcutFilter version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<IrcutFilterType>auto</IrcutFilterType>
<nightToDayFilterLevel>4</nightToDayFilterLevel>
<nightToDayFilterTime>5</nightToDayFilterTime>
</IrcutFilter>"""

    @Test
    fun readsHikvisionIrCutModes() {
        assertEquals(DayNightMode.AUTO, controller.parseHikvisionDayNight(irCut))
        assertEquals(DayNightMode.DAY, controller.parseHikvisionDayNight(irCut.replace(">auto<", ">day<")))
        assertEquals(DayNightMode.NIGHT, controller.parseHikvisionDayNight(irCut.replace(">auto<", ">night<")))
    }

    @Test
    fun readsOlderHikvisionFormat() {
        assertEquals(DayNightMode.NIGHT, controller.parseHikvisionDayNight("<IrCutFilter><mode>night</mode></IrCutFilter>"))
        assertNull(controller.parseHikvisionDayNight("<ResponseStatus><statusString>Invalid Operation</statusString></ResponseStatus>"))
    }

    @Test
    fun changesOnlyTheModeValue() {
        val night = controller.withHikvisionDayNight(irCut, DayNightMode.NIGHT)!!
        assertEquals(DayNightMode.NIGHT, controller.parseHikvisionDayNight(night))
        assertEquals(irCut.replace(">auto<", ">night<"), night)
        // Прочие поля (уровень и время перехода) остаются как были
        assertEquals(DayNightMode.AUTO, controller.parseHikvisionDayNight(controller.withHikvisionDayNight(night, DayNightMode.AUTO)!!))
        assertEquals("<IrCutFilter><mode>day</mode></IrCutFilter>", controller.withHikvisionDayNight("<IrCutFilter><mode>auto</mode></IrCutFilter>", DayNightMode.DAY))
        assertNull(controller.withHikvisionDayNight("<IrcutFilter></IrcutFilter>", DayNightMode.DAY))
    }

    @Test
    fun dahuaAudioKeyFollowsStream() {
        assertEquals("Encode[0].MainFormat[0].AudioEnable", DahuaStreamAudio.audioKey("/cam/realmonitor?channel=1&subtype=0"))
        assertEquals("Encode[0].ExtraFormat[0].AudioEnable", DahuaStreamAudio.audioKey("/cam/realmonitor?channel=1&subtype=1"))
        assertEquals("Encode[0].MainFormat[0].AudioEnable", DahuaStreamAudio.audioKey("/live"))
    }

    @Test
    fun readsDahuaAudioFlag() {
        val body = "table.Encode[0].MainFormat[0].AudioEnable=false\r\ntable.Encode[0].MainFormat[0].Video.BitRate=2048\r\n" +
            "table.Encode[0].ExtraFormat[0].AudioEnable=true\r\n"
        assertEquals(false, DahuaStreamAudio.readFlag(body, "Encode[0].MainFormat[0].AudioEnable"))
        assertEquals(true, DahuaStreamAudio.readFlag(body, "Encode[0].ExtraFormat[0].AudioEnable"))
        assertNull(DahuaStreamAudio.readFlag(body, "Encode[0].ExtraFormat[1].AudioEnable"))
    }
}
