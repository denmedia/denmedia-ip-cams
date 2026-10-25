package com.example.denmediaipcams

import com.example.denmediaipcams.model.CameraOrientation
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.VideoRotation
import com.example.denmediaipcams.network.CameraRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRotationTest {

    // Фрагмент настоящего ответа Dahua configManager getConfig VideoInOptions (в том числе строки профилей)
    private val dahuaBody = """table.VideoInOptions[0].DayNightColor=1
table.VideoInOptions[0].Mirror=false
table.VideoInOptions[0].Flip=true
table.VideoInOptions[0].Rotate90=0
table.VideoInOptions[0].NightOptions.Mirror=true
table.VideoInOptions[0].NightOptions.Flip=false
table.VideoInOptions[0].NormalOptions.Mirror=true
table.VideoInOptions[0].NormalOptions.Flip=false
"""

    @Test
    fun readsDahuaChannelFieldsNotProfileOnes() {
        assertEquals(CameraOrientation(flip = true, mirror = false, rotate90 = 0), CameraRotation.parseDahua(dahuaBody))
    }

    @Test
    fun readsDahuaVideoImageControl() {
        val body = "table.VideoImageControl[0].Mirror=true\ntable.VideoImageControl[0].Flip=false\ntable.VideoImageControl[0].Freeze=false\ntable.VideoImageControl[0].Rotate90=0\n"
        assertEquals(CameraOrientation(flip = false, mirror = true), CameraRotation.parseDahua(body, "VideoImageControl"))
        // Ответ другой таблицы по этому имени не читается
        assertNull(CameraRotation.parseDahua(body, "VideoInOptions"))
    }

    @Test
    fun dahuaWithoutFlipFieldIsUnsupported() {
        assertNull(CameraRotation.parseDahua("table.VideoInOptions[0].DayNightColor=1\n"))
        assertNull(CameraRotation.parseDahua(""))
    }

    @Test
    fun readsDahuaQuarterTurn() {
        val body = "table.VideoInOptions[0].Mirror=false\ntable.VideoInOptions[0].Flip=false\ntable.VideoInOptions[0].Rotate90=2\n"
        assertEquals(CameraOrientation(false, false, 2), CameraRotation.parseDahua(body))
    }

    @Test
    fun readsHipcamImageAttributes() {
        val body = "var brightness=\"50\";\nvar flip=\"on\";\nvar mirror=\"off\";\nvar gc=\"90\";\n"
        assertEquals(CameraOrientation(flip = true, mirror = false), CameraRotation.parseHipcam(body))
    }

    @Test
    fun hipcamWithoutMirrorIsUnsupported() {
        assertNull(CameraRotation.parseHipcam("var flip=\"on\";\n"))
    }

    @Test
    fun halfTurnTogglesBothAxes() {
        val base = CameraOrientation(flip = true, mirror = false)
        assertEquals(CameraOrientation(flip = false, mirror = true), base.turned(VideoRotation.UPSIDE_DOWN))
        assertEquals(base, base.turned(VideoRotation.UPSIDE_DOWN).turned(VideoRotation.UPSIDE_DOWN))
        assertEquals(base, base.turned(VideoRotation.NONE))
    }

    @Test
    fun quarterTurnsSetDahuaRotate90() {
        val base = CameraOrientation(flip = false, mirror = false)
        assertEquals(1, base.turned(VideoRotation.RIGHT_90).rotate90)
        assertEquals(2, base.turned(VideoRotation.LEFT_90).rotate90)
    }

    @Test
    fun orientationSurvivesSaving() {
        val orientation = CameraOrientation(flip = true, mirror = false, rotate90 = 2)
        assertEquals(orientation, CameraOrientation.decode(orientation.encode()))
        assertNull(CameraOrientation.decode(null))
        assertNull(CameraOrientation.decode("garbage"))
    }

    @Test
    fun ptzFollowsVideoOrientation() {
        val camera = IpCamera(id = "1", name = "c", ipAddress = "192.168.1.2")
        // Без поворота команда не меняется
        assertEquals(1f to 0f, camera.cameraMove(1f, 0f))
        // Вверх-ногами: вправо на экране — влево для камеры, вверх — вниз
        val half = camera.copy(videoRotation = VideoRotation.UPSIDE_DOWN)
        assertEquals(-1f to -0.5f, half.cameraMove(1f, 0.5f))
        // 90° по часовой: вправо на экране — вверх у камеры, вверх на экране — влево у камеры
        val right = camera.copy(videoRotation = VideoRotation.RIGHT_90)
        assertEquals(0f to 1f, right.cameraMove(1f, 0f))
        assertEquals(-1f to 0f, right.cameraMove(0f, 1f))
        // 90° против часовой — наоборот
        val left = camera.copy(videoRotation = VideoRotation.LEFT_90)
        assertEquals(0f to -1f, left.cameraMove(1f, 0f))
        assertEquals(1f to 0f, left.cameraMove(0f, 1f))
    }

    @Test
    fun ptzMirrorFlipsCameraAxesAfterRotation() {
        val camera = IpCamera(id = "1", name = "c", ipAddress = "192.168.1.2", ptzInvertPan = true)
        assertEquals(-1f to 0.5f, camera.cameraMove(1f, 0.5f))
        assertEquals(-1f to -0.5f, camera.copy(ptzInvertTilt = true).cameraMove(1f, 0.5f))
        // Зеркало — ось камеры, поэтому при повороте на 90° экранное «вправо» (= наклон камеры) инвертируется по наклону
        val sideways = camera.copy(videoRotation = VideoRotation.RIGHT_90, ptzInvertPan = false, ptzInvertTilt = true)
        assertEquals(0f to -1f, sideways.cameraMove(1f, 0f))
    }

    @Test
    fun playerRotatesOnlyWhatCameraDidNot() {
        val camera = IpCamera(id = "1", name = "c", ipAddress = "192.168.1.2")
        // Камера ничего не делает — весь поворот программный
        assertEquals(180, camera.copy(videoRotation = VideoRotation.UPSIDE_DOWN).softwareRotationDegrees)
        assertEquals(270, camera.copy(videoRotation = VideoRotation.LEFT_90).softwareRotationDegrees)
        // Камера уже перевернула — приложение не поворачивает
        val flipped = camera.copy(videoRotation = VideoRotation.UPSIDE_DOWN, hardwareRotation = VideoRotation.UPSIDE_DOWN)
        assertEquals(0, flipped.softwareRotationDegrees)
        // Поворот сняли, а камера ещё не вернула — приложение компенсирует
        assertEquals(180, flipped.copy(videoRotation = VideoRotation.NONE).softwareRotationDegrees)
        // Камера повернула на 90° сама — остатка нет
        val quarter = camera.copy(videoRotation = VideoRotation.RIGHT_90, hardwareRotation = VideoRotation.RIGHT_90)
        assertEquals(0, quarter.softwareRotationDegrees)
    }
}
