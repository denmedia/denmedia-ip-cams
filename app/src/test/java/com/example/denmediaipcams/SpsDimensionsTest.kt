package com.example.denmediaipcams

import com.example.denmediaipcams.network.SpsDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpsDimensionsTest {

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** SPS H.265 из записи камеры NVT: ширина 2560 в заголовке кадра DVRIP приходит как 512 */
    @Test
    fun h265SpsFromNvtCameraGives2560x1440() {
        val sps = hex("4201010160000003000003000003000003003fa001402005a1fe72ee4c92e20100000303e800002ee008")
        assertEquals(SpsDimensions.Size(2560, 1440), SpsDimensions.parse(sps, h265 = true))
    }

    /** SPS H.264 Baseline 640x480 (типичный дополнительный поток камер) */
    @Test
    fun h264SpsGives640x480() {
        val sps = hex("6742c01e95a0280f68")
        assertEquals(SpsDimensions.Size(640, 480), SpsDimensions.parse(sps, h265 = false))
    }

    @Test
    fun brokenSpsGivesNull() {
        assertNull(SpsDimensions.parse(hex("4201"), h265 = true))
        assertNull(SpsDimensions.parse(hex("42010101"), h265 = true))
    }
}
