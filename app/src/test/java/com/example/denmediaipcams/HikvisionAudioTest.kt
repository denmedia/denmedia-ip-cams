package com.example.denmediaipcams

import com.example.denmediaipcams.network.HikvisionAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Настройки звука в потоке камеры Hikvision: ответ DS-I114W на /ISAPI/Streaming/channels/101 */
class HikvisionAudioTest {

    private fun channel(audio: String) = """<?xml version="1.0" encoding="UTF-8"?>
<StreamingChannel version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<id>101</id>
<channelName>Camera 01</channelName>
<enabled>true</enabled>
<Video>
<enabled>true</enabled>
<videoCodecType>H.264</videoCodecType>
</Video>
$audio
</StreamingChannel>"""

    private val audioOff = """<Audio>
<enabled>false</enabled>
<audioInputChannelID>1</audioInputChannelID>
<audioCompressionType>G.711ulaw</audioCompressionType>
</Audio>"""

    @Test
    fun streamChannelFromPath() {
        assertEquals("101", HikvisionAudio.streamChannel("/Streaming/Channels/101"))
        assertEquals("102", HikvisionAudio.streamChannel("Streaming/channels/102"))
        assertEquals("101", HikvisionAudio.streamChannel("/ISAPI/Streaming/channels/101/picture"))
        assertEquals("101", HikvisionAudio.streamChannel("/h264/ch1/main/av_stream"))
        assertEquals("102", HikvisionAudio.streamChannel("/h264/ch1/sub/av_stream"))
        assertEquals("202", HikvisionAudio.streamChannel("/Streaming/Channels/202"))
        assertNull(HikvisionAudio.streamChannel("/live/ch0"))
    }

    @Test
    fun readsAudioState() {
        assertEquals(false, HikvisionAudio.audioEnabled(channel(audioOff)))
        assertEquals(true, HikvisionAudio.audioEnabled(channel(audioOff.replace("false", "true"))))
        assertNull(HikvisionAudio.audioEnabled(channel("")))
    }

    @Test
    fun enablesOnlyAudioBlock() {
        val changed = HikvisionAudio.withAudioEnabled(channel(audioOff))!!
        assertEquals(true, HikvisionAudio.audioEnabled(changed))
        // Видео и сам поток остаются включёнными, остальное в ответе не тронуто
        assertTrue(changed.contains("<Video>\n<enabled>true</enabled>"))
        assertTrue(changed.contains("<audioCompressionType>G.711ulaw</audioCompressionType>"))
        assertEquals(channel(audioOff).length - 1, changed.length)
    }

    @Test
    fun streamWithoutAudioBlockIsNotChanged() {
        assertNull(HikvisionAudio.withAudioEnabled(channel("")))
        assertFalse(HikvisionAudio.withAudioEnabled(channel(audioOff.replace("false", "true")))!!.contains("false</enabled>\n<audioInputChannelID"))
    }
}
