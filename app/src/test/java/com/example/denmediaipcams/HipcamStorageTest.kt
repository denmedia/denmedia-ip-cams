package com.example.denmediaipcams

import com.example.denmediaipcams.network.ArchiveException
import com.example.denmediaipcams.network.HipcamStorage
import com.example.denmediaipcams.player.G711
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HipcamStorageTest {

    // Фрагмент настоящего ответа param.cgi?cmd=getserverinfo
    private fun serverInfo(status: String) = """var model="C6F0SoZ3N0PcL2";
var name="IPCAM";
var upnpstatus="off";
var sdshow="0";
var sdstatus="$status";
var sdfreespace="3084512";
var sdtotalspace="125012096";
"""

    @Test
    fun readsCardState() {
        val info = HipcamStorage.parseServerInfo(serverInfo("Ready"))
        val volume = info.volumes.single()
        assertEquals("Success", volume.state)
        assertEquals("ReadWrite", volume.type)
        assertEquals(125012096L * 1024, volume.totalBytes)
        assertEquals((125012096L - 3084512L) * 1024, volume.usedBytes)
    }

    @Test
    fun readonlyCardIsHealthyButNotWritable() {
        val volume = HipcamStorage.parseServerInfo(serverInfo("Readonly")).volumes.single()
        assertEquals("Success", volume.state)
        assertEquals("ReadOnly", volume.type)
    }

    @Test
    fun missingCardGivesNoVolumes() {
        assertTrue(HipcamStorage.parseServerInfo(serverInfo("out")).volumes.isEmpty())
    }

    @Test
    fun unknownStatusIsShownAsReported() {
        assertEquals("Unformatted", HipcamStorage.parseServerInfo(serverInfo("Unformatted")).volumes.single().state)
    }

    @Test
    fun answerWithoutCardStatusIsUnsupported() {
        try {
            HipcamStorage.parseServerInfo("var model=\"X\";")
            fail("ожидалась ошибка")
        } catch (e: ArchiveException) {
            assertEquals(ArchiveException.Kind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun alawDecodesToPcm() {
        // Самые тихие значения и крайнее значение из таблицы G.711
        assertEquals(8, G711.alawToPcm(0xD5).toInt())
        assertEquals(-8, G711.alawToPcm(0x55).toInt())
        assertEquals(848, G711.alawToPcm(0xFF).toInt())
        val bytes = G711.alawToPcmBytes(byteArrayOf(0xD5.toByte(), 0x55), 0, 2)
        assertEquals(listOf<Byte>(8, 0, -8, -1), bytes.toList())
    }
}
