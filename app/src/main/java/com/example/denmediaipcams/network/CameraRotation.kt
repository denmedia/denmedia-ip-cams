package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.CameraOrientation
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.VideoRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Что камера делает с картинкой после [CameraRotation.apply]: поворот, который выполняет она сама, и её исходное положение */
data class RotationOutcome(val hardwareRotation: VideoRotation, val base: CameraOrientation)

/**
 * Поворот картинки средствами самой камеры (её настройки переворота и отражения), а не программно в плеере:
 * так поворот попадает и в запись на SD-карте, и в снимки, и не тратит ресурсы планшета.
 * Поддерживается:
 * - Dahua (CGI configManager, VideoInOptions: Flip/Mirror, а при поддержке — Rotate90);
 * - Hipcam/HiSilicon (hi3510 param.cgi setimageattr: flip/mirror);
 * - XMEye/DVRIP (Camera.Param: PictureFlip/PictureMirror), только если для камеры включён этот протокол.
 * Переворот на 180° — это включение/выключение и вертикального, и горизонтального отражения; поворот на 90° есть
 * только у камер Dahua с Rotate90 (в других протоколах — нет, и 90° остаётся программным).
 *
 * Положение камеры считается относительно «базового» — того, что камера отдавала до первого поворота от приложения
 * (например, потолочная камера уже перевёрнута в своих настройках: «Без поворота» не должно её переворачивать назад).
 * Базовое положение запоминается в [IpCamera.rotationBase]; если оно разошлось с камерой (настройки поменяли в
 * веб-интерфейсе камеры), за базовое берётся то, что камера отдаёт сейчас.
 */
object CameraRotation {

    private const val TAG = "CameraRotation"
    private const val TIMEOUT_MS = 6_000
    private const val CONFIRM_ATTEMPTS = 3
    private const val CONFIRM_DELAY_MS = 1_000L

    /** Одно положение картинки камеры в конкретном протоколе */
    private abstract class Driver(val camera: IpCamera) {
        /** null — камера не отвечает или не умеет менять положение картинки этим способом */
        abstract suspend fun read(): CameraOrientation?

        /** Записывает положение [to]; [from] — то, что в камере сейчас (пишутся только отличающиеся поля) */
        abstract suspend fun write(from: CameraOrientation, to: CameraOrientation): Boolean

        open suspend fun supportsRotate90(): Boolean = false

        open fun close() {}
    }

    /** Есть ли у камеры способ управлять поворотом (без обращения к сети): повод попробовать [apply] */
    fun canTry(camera: IpCamera): Boolean = camera.username.isNotBlank() && driversFor(camera).isNotEmpty()

    private fun driversFor(camera: IpCamera): List<Driver> {
        if (camera.ipAddress.contains("://")) return emptyList()
        return listOfNotNull(
            DahuaDriver(camera).takeIf { camera.hasDahuaApi },
            HipcamDriver(camera).takeIf { camera.sdHttpArchive },
            DvripDriver(camera).takeIf { camera.dvripArchive }
        )
    }

    /**
     * Приводит камеру в соответствие с [IpCamera.videoRotation]: то, что она умеет, делает сама. Возвращает null, если
     * камера не ответила ни по одному из способов (ничего не менялось). Иначе — сколько поворота выполняет камера;
     * остаток поворачивает плеер ([IpCamera.softwareRotationDegrees]).
     */
    suspend fun apply(camera: IpCamera): RotationOutcome? = withContext(Dispatchers.IO) {
        val drivers = driversFor(camera)
        try {
            for (driver in drivers) {
                val now = driver.read() ?: continue
                val known = camera.rotationBase?.takeIf { now == it.turned(camera.hardwareRotation) }
                val base = known ?: now
                val hardware = if (known != null) camera.hardwareRotation else VideoRotation.NONE

                val desired = camera.videoRotation
                val canTurn = !desired.isQuarterTurn || (base.rotate90 == 0 && driver.supportsRotate90())
                val target = if (canTurn) desired else VideoRotation.NONE
                if (target == hardware) return@withContext RotationOutcome(hardware, base)

                val wanted = base.turned(target)
                if (driver.write(now, wanted) && confirmed(driver, wanted)) {
                    Log.i(TAG, "${camera.name}: поворот $target выполняет камера")
                    return@withContext RotationOutcome(target, base)
                }
                // Камера не приняла или приняла не то — возвращаем как было, чтобы плеер не повернул картинку дважды
                Log.w(TAG, "${camera.name}: камера не выполнила поворот $target")
                driver.write(wanted, now)
                return@withContext RotationOutcome(hardware, base)
            }
            null
        } finally {
            drivers.forEach { it.close() }
        }
    }

    /** Камера после записи отвечает не сразу (на время смены положения может молчать), поэтому проверка повторяется */
    private suspend fun confirmed(driver: Driver, wanted: CameraOrientation): Boolean {
        repeat(CONFIRM_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(CONFIRM_DELAY_MS)
            if (driver.read() == wanted) return true
        }
        return false
    }

    // ---------------- Dahua ----------------

    /**
     * Переворот и отражение Dahua лежат в конфиге VideoImageControl; у старых прошивок — в VideoInOptions.
     * Основным берётся VideoImageControl: на проверенной DH-SD-H2C-0400B он возвращает ровно то, что записали,
     * а VideoInOptions при Flip=false+Mirror=true отвечает Flip=true, из-за чего запись казалась неудавшейся.
     */
    private class DahuaDriver(camera: IpCamera) : Driver(camera) {

        private var table: String? = null

        private fun get(path: String) = CameraHttp.request(
            "${DahuaApi.baseUrl(camera)}/cgi-bin/$path", user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS
        )

        private fun readTable(name: String): CameraOrientation? =
            get("configManager.cgi?action=getConfig&name=$name").takeIf { it.isSuccess }?.let { parseDahua(it.body, name) }

        override suspend fun read(): CameraOrientation? {
            table?.let { return readTable(it) }
            for (name in DAHUA_TABLES) {
                val orientation = readTable(name) ?: continue
                table = name
                return orientation
            }
            return null
        }

        override suspend fun write(from: CameraOrientation, to: CameraOrientation): Boolean {
            val name = table ?: return false
            val fields = buildList {
                if (from.flip != to.flip) add("Flip=${to.flip}")
                if (from.mirror != to.mirror) add("Mirror=${to.mirror}")
                if (from.rotate90 != to.rotate90) add("Rotate90=${to.rotate90}")
            }
            if (fields.isEmpty()) return true
            // Скобки индекса канала — в виде %5B0%5D: так их принимают и камеры, и клиенты, не разбирающие [] в адресе
            val query = fields.joinToString("&") { "$name%5B0%5D.$it" }
            val response = get("configManager.cgi?action=setConfig&$query")
            return response.isSuccess && response.body.trim().startsWith("OK", ignoreCase = true)
        }

        override suspend fun supportsRotate90(): Boolean {
            val response = get("devVideoInput.cgi?action=getCaps&channel=1")
            return response.isSuccess && Regex("""caps\.Rotate90\s*=\s*true""", RegexOption.IGNORE_CASE).containsMatchIn(response.body)
        }
    }

    private val DAHUA_TABLES = listOf("VideoImageControl", "VideoInOptions")

    /**
     * Разбор ответа configManager getConfig для таблицы [table] (VideoImageControl или VideoInOptions). Берутся
     * только поля самого канала («table.VideoInOptions[0].Flip»), а не профилей NormalOptions/NightOptions:
     * запись в канал меняет и их.
     */
    internal fun parseDahua(body: String, table: String = "VideoInOptions"): CameraOrientation? {
        fun field(name: String) =
            Regex("""^table\.$table\[0]\.$name=(\S+)\s*$""", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)
        val flip = field("Flip")?.toBooleanStrictOrNull() ?: return null
        val mirror = field("Mirror")?.toBooleanStrictOrNull() ?: return null
        return CameraOrientation(flip, mirror, field("Rotate90")?.toIntOrNull() ?: 0)
    }

    // ---------------- Hipcam ----------------

    private class HipcamDriver(camera: IpCamera) : Driver(camera) {

        private fun get(query: String) = CameraHttp.request(
            "http://${camera.ipAddress}:${camera.effectiveHipcamPort}/cgi-bin/hi3510/param.cgi?$query",
            user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS
        )

        override suspend fun read(): CameraOrientation? =
            get("cmd=getimageattr").takeIf { it.isSuccess }?.let { parseHipcam(it.body) }

        override suspend fun write(from: CameraOrientation, to: CameraOrientation): Boolean {
            fun onOff(value: Boolean) = if (value) "on" else "off"
            val response = get("cmd=setimageattr&-flip=${onOff(to.flip)}&-mirror=${onOff(to.mirror)}")
            return response.isSuccess && response.body.contains("Succeed", ignoreCase = true)
        }
    }

    /** Разбор ответа getimageattr: строки вида var flip="on"; */
    internal fun parseHipcam(body: String): CameraOrientation? {
        fun field(name: String) = Regex("""var\s+$name\s*=\s*"(on|off)"""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.equals("on", ignoreCase = true)
        return CameraOrientation(flip = field("flip") ?: return null, mirror = field("mirror") ?: return null)
    }

    // ---------------- XMEye / DVRIP ----------------

    private class DvripDriver(camera: IpCamera) : Driver(camera) {

        private val client = DvripClient(camera)
        private var loggedIn = false

        // Вход один на всё применение поворота; любая ошибка связи или входа — «камера не умеет/не отвечает»
        private suspend fun <T> session(block: suspend () -> T): T? = try {
            if (!loggedIn) {
                client.login()
                loggedIn = true
            }
            block()
        } catch (e: ArchiveException) {
            Log.w(TAG, "${camera.name}: DVRIP: ${e.message}")
            null
        }

        override suspend fun read(): CameraOrientation? =
            session { client.pictureOrientation() }?.let { (flip, mirror) -> CameraOrientation(flip, mirror) }

        override suspend fun write(from: CameraOrientation, to: CameraOrientation): Boolean =
            session { client.setPictureOrientation(to.flip, to.mirror) } == true

        override fun close() = client.close()
    }
}
