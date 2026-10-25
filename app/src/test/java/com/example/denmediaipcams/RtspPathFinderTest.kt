package com.example.denmediaipcams

import com.example.denmediaipcams.network.RtspPathFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspPathFinderTest {

    @Test
    fun extractsLeakedCredentialsFromRealExample() {
        val uri = "rtsp://192.168.1.202:554/user=admin_password=ExamplePw1_channel=0_stream=0&onvif=0.sdp?real_stream"
        val leaked = RtspPathFinder.extractLeakedCredentials(uri)
        assertEquals("admin" to "ExamplePw1", leaked)
    }

    @Test
    fun extractsLeakedCredentialsWithEmptyPassword() {
        val uri = "rtsp://192.168.1.202:554/user=admin_password=_channel=0_stream=0&onvif=0.sdp?real_stream"
        val leaked = RtspPathFinder.extractLeakedCredentials(uri)
        assertEquals("admin" to "", leaked)
    }

    @Test
    fun returnsNullForNormalUriWithoutLeakedCredentials() {
        val uri = "rtsp://192.168.1.202:554/cam/realmonitor?channel=1&subtype=0"
        assertNull(RtspPathFinder.extractLeakedCredentials(uri))
    }

    @Test
    fun returnsNullForPlainChannelPathAndDoesNotThrow() {
        val uri = "rtsp://192.168.1.202:554/Streaming/Channels/101"
        assertNull(RtspPathFinder.extractLeakedCredentials(uri))
    }
}
