package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripClient
import com.example.denmediaipcams.network.parseDvripStorageInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сведения о карте XMEye/DVRIP. org.json есть только на устройстве, поэтому проверка здесь, а не в юнит-тестах.
 * Живая часть запускается с аргументами -e dvripHost … -e dvripUser … -e dvripPass … и только читает.
 */
@RunWith(AndroidJUnit4::class)
class DvripStorageInfoTest {

    // Настоящий ответ XM530V200: один диск, раздел записей (тип 0) и раздел снимков (тип 4)
    private val oneCardTwoPartitions = """[{"PartNumber":2,"Partition":[
        {"DirverType":0,"IsCurrent":true,"LogicSerialNo":0,"RemainSpace":"0x00000000","Status":0,"TotalSpace":"0x00000575"},
        {"DirverType":4,"IsCurrent":true,"LogicSerialNo":65536,"RemainSpace":"0x0000018a","Status":0,"TotalSpace":"0x000001d1"}
    ],"PlysicalNo":1}]"""

    @Test
    fun partitionsOfOneCardAreOneVolume() {
        val info = parseDvripStorageInfo(JSONArray(oneCardTwoPartitions))
        val volume = info.volumes.single()
        assertEquals((0x575L + 0x1d1L) * 1024 * 1024, volume.totalBytes)
        assertEquals((0x575L + (0x1d1L - 0x18aL)) * 1024 * 1024, volume.usedBytes)
        // Форматирование нацелено на раздел записей
        assertEquals("1:0", volume.device)
        assertEquals("Success", volume.state)
        assertEquals("ReadWrite", volume.type)
    }

    @Test
    fun recordPartitionIsFoundEvenWhenItIsNotFirst() {
        val json = """[{"PlysicalNo":0,"Partition":[
            {"DirverType":4,"RemainSpace":"0x00000010","Status":0,"TotalSpace":"0x00000020"},
            {"DirverType":0,"RemainSpace":"0x00000100","Status":0,"TotalSpace":"0x00000200"}]}]"""
        assertEquals("0:1", parseDvripStorageInfo(JSONArray(json)).volumes.single().device)
    }

    @Test
    fun separateDisksStaySeparate() {
        val json = """[{"PlysicalNo":0,"Partition":[{"DirverType":0,"RemainSpace":"0x1","Status":0,"TotalSpace":"0x10"}]},
            {"PlysicalNo":1,"Partition":[{"DirverType":0,"RemainSpace":"0x2","Status":0,"TotalSpace":"0x20"}]}]"""
        assertEquals(listOf("Диск 0", "Диск 1"), parseDvripStorageInfo(JSONArray(json)).volumes.map { it.path })
    }

    @Test
    fun brokenPartitionStatusIsReported() {
        val json = """[{"PlysicalNo":0,"Partition":[{"DirverType":0,"RemainSpace":"0x0","Status":3,"TotalSpace":"0x10"}]}]"""
        val volume = parseDvripStorageInfo(JSONArray(json)).volumes.single()
        assertEquals("Status3", volume.state)
        assertNull(volume.type)
    }

    @Test
    fun diskWithoutPartitionsHasNoSpace() {
        val volume = parseDvripStorageInfo(JSONArray("""[{"PlysicalNo":0,"Partition":[]}]""")).volumes.single()
        assertEquals("NoPartition", volume.state)
    }

    @Test
    fun emptyCameraAnswerHasNoVolumes() {
        assertEquals(0, parseDvripStorageInfo(JSONArray("[]")).volumes.size)
    }

    @Test
    fun liveCameraShowsOneCard() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("dvripHost")
        assumeTrue("нет аргументов камеры", host != null)
        val client = DvripClient(
            IpCamera(
                id = "device-test", name = "test", ipAddress = host!!,
                username = args.getString("dvripUser").orEmpty(), password = args.getString("dvripPass").orEmpty(),
                dvripArchive = true
            )
        )
        try {
            client.login()
            val volumes = client.storageInfo().volumes
            println("volumes: $volumes")
            assertEquals(1, volumes.size)
        } finally {
            client.close()
        }
    }
}
