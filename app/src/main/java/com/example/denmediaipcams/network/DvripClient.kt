package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Один файл записи в ответе камеры на поиск (OPFileQuery) */
data class DvripFileEntry(
    val beginTime: LocalDateTime,
    val endTime: LocalDateTime,
    val channel: Int,
    val fileName: String,
    val sizeBytes: Long
)

/** Символы для sofia_hash: таблица из прошивки камер XMEye (не ASCII-таблица, порядок важен) */
private const val SOFIA_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

/**
 * Хэш пароля для входа DVRIP/XMEye: MD5 пароля, затем 8 символов — по одному на каждую пару байт
 * MD5-суммы (сумма пары по модулю 62 — индекс в [SOFIA_CHARS]). Это не криптографическая защита —
 * отсюда и предупреждение пользователю при включении архива по этому протоколу.
 */
internal fun sofiaHash(password: String): String {
    val md5 = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(8)
    for (i in 0 until 8) {
        val sum = (md5[2 * i].toInt() and 0xFF) + (md5[2 * i + 1].toInt() and 0xFF)
        sb.append(SOFIA_CHARS[sum % 62])
    }
    return sb.toString()
}

/** Коды ответа камеры (поле "Ret"), русский текст для пользователя; 100 и 515 — успех */
internal val DVRIP_CODES: Map<Int, String> = mapOf(
    100 to "OK",
    101 to "Неизвестная ошибка",
    102 to "Версия не поддерживается",
    103 to "Запрос запрещён",
    104 to "Пользователь уже вошёл",
    105 to "Пользователь не авторизован",
    106 to "Неверное имя пользователя или пароль",
    107 to "Недостаточно прав",
    108 to "Таймаут",
    109 to "Поиск не дал результатов",
    110 to "OK (есть ещё записи)",
    111 to "OK (есть ещё записи)",
    119 to "Файл не найден",
    203 to "Неверный пароль",
    515 to "OK"
)

/** Заголовок команды DVRIP/XMEye: 20 байт, все числа little-endian (см. описание протокола в коде DvripClient) */
internal data class DvripHeader(val sessionId: Int, val sequence: Int, val msgId: Int, val dataLength: Int) {

    fun pack(): ByteArray {
        val buf = ByteArray(HEADER_SIZE)
        buf[0] = 0xFF.toByte()
        buf[1] = 0x00
        // offsets 2-3 и 12-13 — зарезервированы, остаются нулями
        putLe32(buf, 4, sessionId)
        putLe32(buf, 8, sequence)
        putLe16(buf, 14, msgId)
        putLe32(buf, 16, dataLength)
        return buf
    }

    companion object {
        const val HEADER_SIZE = 20

        fun parse(buf: ByteArray): DvripHeader {
            require(buf.size >= HEADER_SIZE) { "Короткий заголовок DVRIP" }
            return DvripHeader(
                sessionId = le32(buf, 4),
                sequence = le32(buf, 8),
                msgId = le16(buf, 14),
                dataLength = le32(buf, 16)
            )
        }

        private fun le32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

        private fun le16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        private fun putLe32(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun putLe16(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }
}

/**
 * Низкоуровневый клиент проприетарного протокола XMEye/DVRIP (порт 34567, проверенная спецификация —
 * см. задачу/комментарии в DvripArchive). Один клиент — одно TCP-соединение и одна сессия камеры.
 *
 * Протокол двоичный: каждая команда — 20-байтный заголовок (с номером сообщения и длиной тела),
 * за которым следует JSON с 2-байтным «хвостом» (0x0A 0x00). Скачивание записи (OPPlayBack/DownloadStart)
 * и снимок (OPSNAP) переходят в «сырой» режим: после команды в том же соединении идёт поток кусков
 * «заголовок (нужна только длина) + столько-то байт», пока не придёт кусок нулевой длины.
 *
 * Протокол нигде не проверяет входные данные так тщательно, как современные API камер: отсюда и то,
 * что PortDetector обходит этот порт стороной при автосканировании (см. XMEYE_SDK_PORTS) — этот клиент
 * создаётся только по явному действию пользователя (включённый тумблер IpCamera.dvripArchive).
 *
 * Все операции ограничены таймаутами и не делают повторных попыток — решение о повторе остаётся
 * за вызывающим кодом.
 */
class DvripClient(private val camera: IpCamera) {

    private var socket: Socket? = null
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null

    private var sessionId: Int = 0
    private var sequence: Int = 0
    private var aliveIntervalSec: Int = DEFAULT_ALIVE_INTERVAL_SEC

    // Сериализует доступ к сокету: фоновый keepAlive и основной поток вызовов не должны писать/читать одновременно
    private val ioLock = Any()

    // Пока скачивается запись/снимок, keepAlive не шлётся: на одном TCP-соединении команда, вставленная
    // в середину «сырого» потока камеры, нарушила бы разбор границ кусков
    private val streamingActive = AtomicBoolean(false)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAliveJob: Job? = null

    /** Открывает TCP-соединение (без входа). login() делает это сам, если соединения ещё нет */
    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        if (socket != null) return@withContext
        try {
            openSocket()
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, "Камера не отвечает на порту ${camera.effectiveDvripPort}: ${e.message}")
        }
    }

    /**
     * Вход в камеру (sofia_hash пароля, см. [sofiaHash]). Бросает [ArchiveException] с видом AUTH —
     * неверный логин/пароль, NETWORK — камера не отвечает/оборвала соединение. Успешный вызов всегда
     * возвращает true (исключение — единственный способ сообщить о неудаче).
     */
    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (socket == null) openSocket()
            val body = JSONObject()
                .put("EncryptType", "MD5")
                .put("LoginType", "DVRIP-Web")
                .put("PassWord", sofiaHash(camera.password))
                .put("UserName", camera.username)
            val reply = synchronized(ioLock) { commandSync(MSG_LOGIN, body) }
            val code = reply.optInt("Ret", -1)
            if (code != 100 && code != 515) {
                throw ArchiveException(authKindFor(code), DVRIP_CODES[code] ?: "Камера отклонила вход (код $code)")
            }
            sessionId = parseSessionId(reply.optString("SessionID"))
            aliveIntervalSec = reply.optInt("AliveInterval", DEFAULT_ALIVE_INTERVAL_SEC)
                .takeIf { it > 0 } ?: DEFAULT_ALIVE_INTERVAL_SEC
            startKeepAlive()
            true
        } catch (e: ArchiveException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
    }

    /** Один внеплановый KeepAlive (сессия и так поддерживает себя сама после успешного login()) */
    suspend fun keepAlive(): Unit = withContext(Dispatchers.IO) {
        try {
            synchronized(ioLock) { commandSync(MSG_KEEPALIVE, keepAliveBody()) }
            Unit
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
    }

    /**
     * Текущее время камеры (OPTimeQuery, msgId 1452) — диагностика: если часы камеры сильно расходятся
     * с реальным временем, поиск записей по интервалу дат ищет не там. Null — камера не ответила
     * понятной датой.
     */
    suspend fun deviceTime(): LocalDateTime? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("Name", "OPTimeQuery").put("SessionID", sessionHex())
            val reply = synchronized(ioLock) { commandSync(MSG_TIME_QUERY, body) }
            val text = reply.optString("OPTimeQuery")
            runCatching { LocalDateTime.parse(text, TIME_FORMAT) }.getOrNull()
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
    }

    /**
     * Сведения о накопителях (StorageInfo, msgId 1020; только чтение). Объёмы камера отдаёт в мегабайтах
     * (hex-строкой). Раздел с нулевым объёмом — карта вставлена, но файловая система не распознана.
     * Пустой список — карты в камере нет.
     */
    suspend fun storageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val body = JSONObject().put("Name", "StorageInfo").put("SessionID", sessionHex())
        val reply = try {
            synchronized(ioLock) { commandSync(MSG_STORAGE_INFO, body) }
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
        val code = reply.optInt("Ret", -1)
        if (code != 100 && code != 515) {
            throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, DVRIP_CODES[code] ?: "Камера не отдаёт сведения о карте (код $code)")
        }
        parseDvripStorageInfo(reply.optJSONArray("StorageInfo") ?: JSONArray())
    }

    /**
     * Режим ночной подсветки камер с белыми светодиодами (Camera.WhiteLight, WorkMode: "Close" — только ИК,
     * "KeepOpen" — полноцветный ночью, "Intelligent"/"Auto" — решает камера). null — камера не отдаёт этот конфиг.
     * Проверено на XM530V200: DayNightColor и ИК-фильтр у такой камеры день/ночь не переключают, работает именно WorkMode.
     */
    suspend fun whiteLightMode(): String? =
        readConfig(WHITE_LIGHT)?.optString("WorkMode")?.takeIf { it.isNotEmpty() }

    suspend fun setWhiteLightMode(mode: String): Boolean = updateConfig(WHITE_LIGHT, "WorkMode", mode)

    /** Настройки ночной подсветки: режим белых LED, их яркость и режим ИК-фильтра (null — камера не отдала конфиг) */
    data class NightLight(val whiteMode: String?, val brightness: Int?, val irCutMode: Int?)

    suspend fun nightLight(): NightLight? {
        val white = readConfig(WHITE_LIGHT)
        val param = readConfig(CAMERA_PARAM)
        if (white == null && param == null) return null
        return NightLight(
            whiteMode = white?.optString("WorkMode")?.takeIf { it.isNotEmpty() },
            brightness = white?.takeIf { it.has("Brightness") }?.optInt("Brightness"),
            irCutMode = param?.takeIf { it.has("IRCUTMode") }?.optInt("IRCUTMode")
        )
    }

    suspend fun setWhiteLightBrightness(percent: Int): Boolean = updateConfig(WHITE_LIGHT, "Brightness", percent.coerceIn(0, 100))

    suspend fun setIrCutMode(mode: Int): Boolean = updateConfig(CAMERA_PARAM, "IRCUTMode", mode)

    /**
     * Положение картинки, которое камера выдаёт сама (Camera.Param: PictureFlip — отражение по вертикали,
     * PictureMirror — по горизонтали; оба сразу — переворот на 180°). Камера отдаёт значения шестнадцатеричной
     * строкой («0x00000001»). null — камера не отдала эти поля. Проверено на XM530V200: на картинку влияют обе.
     */
    suspend fun pictureOrientation(): Pair<Boolean, Boolean>? {
        val param = readConfig(CAMERA_PARAM) ?: return null
        val flip = param.opt("PictureFlip")?.let(::parseFlag) ?: return null
        val mirror = param.opt("PictureMirror")?.let(::parseFlag) ?: return null
        return flip to mirror
    }

    suspend fun setPictureOrientation(flip: Boolean, mirror: Boolean): Boolean =
        updateConfig(CAMERA_PARAM, mapOf("PictureFlip" to flagHex(flip), "PictureMirror" to flagHex(mirror)))

    private fun flagHex(value: Boolean) = if (value) "0x00000001" else "0x00000000"

    private fun parseFlag(raw: Any): Boolean? = when (raw) {
        is Number -> raw.toInt() != 0
        is Boolean -> raw
        is String -> runCatching { java.lang.Long.decode(raw) != 0L }.getOrNull()
        else -> null
    }

    /** Конфиг камеры (GetConfig, только чтение). null — камера не поддерживает такой конфиг */
    private suspend fun readConfig(name: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val reply = synchronized(ioLock) { commandSync(MSG_GET_CONFIG, configRequest(name)) }
            if (reply.optInt("Ret", -1) != 100) null else reply.optJSONObject(name)
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
    }

    /** Меняет одно поле конфига, остальные поля возвращаются камере как были. true — камера приняла запись */
    private suspend fun updateConfig(name: String, key: String, value: Any): Boolean =
        updateConfig(name, mapOf(key to value))

    /** То же для нескольких полей сразу: камера получает их одной записью */
    private suspend fun updateConfig(name: String, values: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        try {
            synchronized(ioLock) {
                val current = commandSync(MSG_GET_CONFIG, configRequest(name))
                if (current.optInt("Ret", -1) != 100) return@synchronized false
                val config = current.optJSONObject(name) ?: return@synchronized false
                values.forEach { (key, value) -> config.put(key, value) }
                val body = JSONObject().put("Name", name).put("SessionID", sessionHex()).put(name, config)
                commandSync(MSG_SET_CONFIG, body).optInt("Ret", -1) == 100
            }
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
    }

    /**
     * Конфиг камеры целиком, как она его отдала (GetConfig, только чтение): имя вида «Record.[0]» (с индексом канала —
     * тогда камера отвечает объектом, а не списком). null — камера не поддерживает такой конфиг.
     */
    suspend fun getConfig(name: String): JSONObject? = readConfig(name)

    /**
     * Записывает конфиг [config] целиком (SetConfig). Конфиг должен быть тем объектом, что вернул [getConfig], с
     * изменёнными нужными полями: камера принимает объект как есть, поэтому нельзя ни убирать поля, ни выдумывать
     * новые, ни менять тип значений (шестнадцатеричные строки остаются строками). true — камера приняла запись.
     */
    suspend fun setConfig(name: String, config: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            synchronized(ioLock) {
                val body = JSONObject().put("Name", name).put("SessionID", sessionHex()).put(name, config)
                val code = commandSync(MSG_SET_CONFIG, body).optInt("Ret", -1)
                code == 100 || code == 515
            }
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
    }

    private fun configRequest(name: String): JSONObject = JSONObject().put("Name", name).put("SessionID", sessionHex())

    /**
     * Форматирование карты (OPStorageManager/Clear, msgId 1450): стирает записи раздела. Камера может оборвать
     * соединение, пока работает, — тогда это ArchiveException вида NETWORK, состояние проверяет вызывающий код.
     * [device] — «диск:раздел» из [StorageVolume.device].
     */
    suspend fun formatStorage(device: String): Unit = withContext(Dispatchers.IO) {
        val part = device.substringAfter(':', "0").toIntOrNull() ?: 0
        val body = JSONObject()
            .put("Name", "OPStorageManager")
            .put("SessionID", sessionHex())
            .put("OPStorageManager", JSONObject().put("Action", "Clear").put("PartNo", part).put("SerialNo", 0).put("Type", "Data"))
        val reply = try {
            synchronized(ioLock) {
                socket?.soTimeout = FORMAT_READ_TIMEOUT_MS
                commandSync(MSG_STORAGE_MANAGER, body)
            }
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
        val code = reply.optInt("Ret", -1)
        if (code == 103 || code == 107) {
            throw ArchiveException(
                ArchiveException.Kind.UNSUPPORTED,
                "Камера не разрешает форматировать карту по сети (код $code). Обычно так бывает, когда карта " +
                    "не размечена или у пользователя нет прав администратора. Отформатируйте карту (FAT32) в веб-интерфейсе " +
                    "камеры, приложении XMEye или на компьютере."
            )
        }
        if (code != 100 && code != 515) {
            throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, DVRIP_CODES[code] ?: "Камера отклонила форматирование (код $code)")
        }
    }

    /**
     * Поиск файлов записей (OPFileQuery). Камера отдаёт не больше 64 записей за раз — при полной
     * странице запрос повторяется с BeginTime последней полученной записи (как в эталонной реализации
     * протокола), пока не придёт неполная страница; общее число результатов ограничено [MAX_SEARCH_RESULTS]
     * на случай зацикливания при испорченном ответе камеры.
     */
    suspend fun listFiles(
        channel: Int,
        begin: LocalDateTime,
        end: LocalDateTime,
        type: String = "h264"
    ): List<DvripFileEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DvripFileEntry>()
        var cursor = begin
        while (results.size < MAX_SEARCH_RESULTS) {
            val condition = JSONObject()
                .put("BeginTime", cursor.format(TIME_FORMAT))
                .put("EndTime", end.format(TIME_FORMAT))
                .put("Channel", channel)
                .put("DriverTypeMask", "0x0000FFFF")
                .put("Event", "*")
                .put("StreamType", "0x00000000")
                .put("Type", type)
            val body = JSONObject()
                .put("Name", "OPFileQuery")
                .put("SessionID", sessionHex())
                .put("OPFileQuery", condition)
            val reply = try {
                synchronized(ioLock) { commandSync(MSG_FILE_QUERY, body) }
            } catch (e: IOException) {
                throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
            }
            val code = reply.optInt("Ret", -1)
            // 109/119 — поиск завершён без (новых) результатов, это не ошибка
            if (code == 109 || code == 119) break
            if (code != 100 && code != 110 && code != 111) {
                throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, DVRIP_CODES[code] ?: "Камера отклонила поиск записей (код $code)")
            }
            val array = reply.optJSONArray("OPFileQuery") ?: JSONArray()
            val batch = (0 until array.length()).mapNotNull { parseFileEntry(array.optJSONObject(it)) }
            if (batch.isEmpty()) break
            results += batch
            if (batch.size < 64) break
            val nextCursor = batch.last().beginTime
            if (!nextCursor.isAfter(cursor)) break // защита от зацикливания, если камера повторяет те же записи
            cursor = nextCursor
        }
        results.take(MAX_SEARCH_RESULTS)
    }

    /**
     * Запрашивает у камеры скачивание файла записи (Claim, затем DownloadStart) и возвращает поток
     * декодированных кадров. Во время скачивания фоновый KeepAlive приостанавливается (см. [streamingActive]),
     * а таймаут чтения увеличивается — камера может делать паузы между кусками потока.
     */
    suspend fun openPlayback(file: DvripFileEntry, from: LocalDateTime = file.beginTime): DvripFrameStream = withContext(Dispatchers.IO) {
        val parameter = JSONObject()
            .put("PlayMode", "ByName")
            .put("FileName", file.fileName)
            .put("StreamType", 0)
            .put("Value", 0)
            .put("TransMode", "TCP")

        fun body(action: String) = JSONObject()
            .put("Name", "OPPlayBack")
            .put("SessionID", sessionHex())
            .put(
                "OPPlayBack",
                JSONObject()
                    .put("Action", action)
                    .put("Parameter", parameter)
                    // Камера начинает передачу не с начала файла, а с позиции, соответствующей этому времени
                    .put("StartTime", from.format(TIME_FORMAT))
                    .put("EndTime", file.endTime.format(TIME_FORMAT))
            )

        val claim = try {
            synchronized(ioLock) { commandSync(MSG_PLAYBACK_CLAIM, body("Claim")) }
        } catch (e: IOException) {
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
        val claimCode = claim.optInt("Ret", -1)
        if (claimCode != 100 && claimCode != 515) {
            throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, DVRIP_CODES[claimCode] ?: "Камера отклонила запрос записи (код $claimCode)")
        }

        streamingActive.set(true)
        try {
            synchronized(ioLock) {
                socket?.soTimeout = STREAM_READ_TIMEOUT_MS
                sendJsonSync(MSG_PLAYBACK, body("DownloadStart"))
            }
        } catch (e: IOException) {
            streamingActive.set(false)
            throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
        }
        // Размер хвоста при старте с середины неизвестен, конец определяют заполнитель 0xFF и тишина в потоке
        val seeking = from.isAfter(file.beginTime)
        DvripFrameStream(body("DownloadStop"), if (seeking) 0L else file.sizeBytes, resync = seeking)
    }

    /**
     * Снимок кадра (OPSNAP): запрос не ждёт обычного JSON-ответа — сразу после него камера присылает
     * один JPEG тем же «сырым» потоком кусков, что и скачивание записи. Best-effort: любая ошибка —
     * null, не исключение (это второстепенная функция, не стоит ронять из-за неё интерфейс).
     */
    suspend fun snapshot(channel: Int = 0): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("Name", "OPSNAP")
                .put("SessionID", sessionHex())
                .put("OPSNAP", JSONObject().put("Channel", channel))
            streamingActive.set(true)
            synchronized(ioLock) {
                socket?.soTimeout = STREAM_READ_TIMEOUT_MS
                sendJsonSync(MSG_SNAPSHOT, body)
            }
            val out = ByteArrayOutputStream()
            while (out.size() < MAX_SNAPSHOT_BYTES) {
                val chunk = synchronized(ioLock) { readStreamChunkSync() } ?: break
                out.write(chunk)
            }
            out.toByteArray().takeIf { it.size > 4 }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "snapshot (OPSNAP) не удался: ${e.message}")
            null
        } finally {
            streamingActive.set(false)
            runCatching { socket?.soTimeout = CONTROL_READ_TIMEOUT_MS }
        }
    }

    /**
     * Закрывает соединение. В проверенной спецификации протокола нет документированной команды выхода
     * (Logout) — поэтому она не отправляется (см. требование не слать неподтверждённые команды в этот
     * протокол); закрытие TCP-сокета даёт тот же эффект — камера сама закрывает сессию по таймауту.
     */
    fun close() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        ioScope.cancel()
        synchronized(ioLock) {
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
        }
    }

    /** Поток декодированных кадров записи, полученной через [openPlayback] */
    inner class DvripFrameStream internal constructor(private val stopBody: JSONObject, private val expectedBytes: Long, resync: Boolean = false) {
        private val pending = ArrayDeque<DvripMediaFrame>()
        private val parser = DvripFrameParser(resync) { pending.addLast(it) }
        private var finished = false
        private var bytesRead = 0L

        /** Следующий декодированный кадр; null — запись прочитана до конца (или оборвалась на камере) */
        suspend fun nextFrame(): DvripMediaFrame? = withContext(Dispatchers.IO) {
            while (pending.isEmpty() && !finished) {
                val chunk = try {
                    synchronized(ioLock) { readStreamChunkSync() }
                } catch (e: java.net.SocketTimeoutException) {
                    finishStreaming()
                    // Камера не присылает признака конца записи: если размер файла неизвестен, а данные уже
                    // пошли, тишина в потоке — это и есть конец; без данных — настоящая ошибка
                    if (expectedBytes > 0 || bytesRead == 0L) throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
                    finished = true
                    break
                } catch (e: IOException) {
                    finishStreaming()
                    throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
                }
                if (chunk == null) {
                    finished = true
                    finishStreaming()
                } else {
                    // DvripStreamException (неизвестный формат потока) сознательно не перехватывается здесь:
                    // это не сетевая ошибка, а повод остановить разбор — вызывающий код (DvripMp4Converter)
                    // превращает её в понятное сообщение и гарантированно останавливает скачивание
                    parser.feed(chunk)
                    bytesRead += chunk.size
                    // Конец записи: камера добила файл 0xFF-ми, либо прислала ровно столько, сколько весит файл
                    // (FileLength из поиска) — нулевого куска в конце она не шлёт, и без этого мы ждали бы таймаут
                    if (parser.reachedPadding || (expectedBytes > 0 && bytesRead >= expectedBytes)) {
                        finished = true
                        finishStreaming()
                    }
                }
            }
            pending.removeFirstOrNull()
        }

        /** Просит камеру остановить скачивание (fire-and-forget); сессию клиента после этого можно закрывать */
        suspend fun stop(): Unit = withContext(Dispatchers.IO) {
            finishStreaming()
            runCatching { synchronized(ioLock) { sendJsonSync(MSG_PLAYBACK, stopBody) } }
            Unit
        }

        private fun finishStreaming() {
            streamingActive.set(false)
            runCatching { socket?.soTimeout = CONTROL_READ_TIMEOUT_MS }
        }
    }

    // --- низкоуровневые блокирующие операции с сокетом (вызываются под ioLock, кроме openSocket при входе) ---

    private fun openSocket() {
        val s = Socket()
        s.connect(InetSocketAddress(camera.ipAddress, camera.effectiveDvripPort), CONNECT_TIMEOUT_MS)
        s.soTimeout = CONTROL_READ_TIMEOUT_MS
        socket = s
        input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        output = s.getOutputStream()
    }

    private fun commandSync(msgId: Int, body: JSONObject): JSONObject {
        sendJsonSync(msgId, body)
        val header = DvripHeader.parse(readFullySync(DvripHeader.HEADER_SIZE))
        val data = readFullySync(header.dataLength)
        val jsonLength = (header.dataLength - 2).coerceAtLeast(0).coerceAtMost(data.size)
        return JSONObject(String(data, 0, jsonLength, Charsets.UTF_8))
    }

    private fun sendJsonSync(msgId: Int, body: JSONObject) {
        val payload = body.toString().toByteArray(Charsets.UTF_8) + TAIL
        val header = DvripHeader(sessionId, sequence, msgId, payload.size).pack()
        sequence++
        val out = output ?: throw IOException("Нет соединения с камерой")
        out.write(header)
        out.write(payload)
        out.flush()
    }

    /** Один кусок «сырого» потока (скачивание записи/снимок): null — поток закончен (длина в заголовке 0) */
    private fun readStreamChunkSync(): ByteArray? {
        val header = DvripHeader.parse(readFullySync(DvripHeader.HEADER_SIZE))
        if (header.dataLength <= 0) return null
        return readFullySync(header.dataLength)
    }

    private fun readFullySync(length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val inp = input ?: throw IOException("Нет соединения с камерой")
        val buf = ByteArray(length)
        var filled = 0
        while (filled < length) {
            // input имеет soTimeout сокета — каждый read() ограничен по времени, не висит бесконечно
            val read = inp.read(buf, filled, length - filled)
            if (read < 0) throw IOException("Камера закрыла соединение")
            filled += read
        }
        return buf
    }

    private fun keepAliveBody(): JSONObject = JSONObject().put("Name", "KeepAlive").put("SessionID", sessionHex())

    private fun sessionHex(): String = "0x%08X".format(sessionId)

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        val intervalMs = aliveIntervalSec * 1000L
        keepAliveJob = ioScope.launch {
            while (true) {
                delay(intervalMs)
                if (streamingActive.get()) continue
                try {
                    synchronized(ioLock) { commandSync(MSG_KEEPALIVE, keepAliveBody()) }
                } catch (e: Exception) {
                    // Сессия, похоже, потеряна — дальнейшие попытки не имеют смысла, пока не будет нового login()
                    return@launch
                }
            }
        }
    }

    private fun parseFileEntry(obj: JSONObject?): DvripFileEntry? = parseDvripFileEntry(obj)

    private fun parseSessionId(hex: String): Int =
        runCatching { java.lang.Long.decode(hex.ifBlank { "0x0" }) }.getOrDefault(0L).toInt()

    private fun authKindFor(code: Int): ArchiveException.Kind =
        if (code == 105 || code == 106 || code == 203) ArchiveException.Kind.AUTH else ArchiveException.Kind.UNSUPPORTED

    private companion object {
        const val TAG = "DvripClient"
        const val CONNECT_TIMEOUT_MS = 4_000
        const val CONTROL_READ_TIMEOUT_MS = 8_000
        const val STREAM_READ_TIMEOUT_MS = 20_000
        const val DEFAULT_ALIVE_INTERVAL_SEC = 30
        const val MAX_SEARCH_RESULTS = 2_000
        const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024

        const val MSG_LOGIN = 1000
        const val MSG_KEEPALIVE = 1006
        const val MSG_TIME_QUERY = 1452
        const val FORMAT_READ_TIMEOUT_MS = 60_000
        const val MSG_STORAGE_INFO = 1020
        const val MSG_GET_CONFIG = 1042
        const val MSG_SET_CONFIG = 1040
        const val WHITE_LIGHT = "Camera.WhiteLight"
        const val CAMERA_PARAM = "Camera.Param.[0]"
        const val MSG_STORAGE_MANAGER = 1450
        const val MSG_FILE_QUERY = 1440
        const val MSG_PLAYBACK_CLAIM = 1424
        const val MSG_PLAYBACK = 1420
        const val MSG_SNAPSHOT = 1560

        val TAIL = byteArrayOf(0x0A, 0x00)
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

private val DVRIP_ENTRY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Размер файла из записи OPFileQuery. Камера отдаёт его в поле FileLength, в килобайтах, шестнадцатеричной
 * строкой («0x000067f4»); проверено на NVT (5-минутный файл — около 40 МБ). Поля FileSize
 * в ответе нет, поэтому оно — лишь запасной вариант (в байтах) для прошивок, которые отдают размер иначе.
 */
internal fun dvripFileSizeBytes(fileLength: Any?, fileSize: Any?): Long {
    fun number(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong()
        is String -> runCatching { java.lang.Long.decode(raw.trim()) }.getOrNull()
        else -> null
    }
    number(fileLength)?.let { return it.coerceAtLeast(0) * 1024 }
    return number(fileSize)?.coerceAtLeast(0) ?: 0L
}

/** Одна запись ответа OPFileQuery; null — без времени начала или имени файла запись бесполезна */
internal fun parseDvripFileEntry(obj: JSONObject?): DvripFileEntry? {
    obj ?: return null
    val begin = runCatching { LocalDateTime.parse(obj.optString("BeginTime"), DVRIP_ENTRY_TIME_FORMAT) }.getOrNull() ?: return null
    val end = runCatching { LocalDateTime.parse(obj.optString("EndTime"), DVRIP_ENTRY_TIME_FORMAT) }.getOrNull() ?: begin
    val fileName = obj.optString("FileName").takeIf { it.isNotEmpty() } ?: return null
    return DvripFileEntry(begin, end, obj.optInt("Channel", 0), fileName, dvripFileSizeBytes(obj.opt("FileLength"), obj.opt("FileSize")))
}

/**
 * Разбор ответа StorageInfo. Одна SD-карта камера описывает как диск (PlysicalNo) с несколькими разделами:
 * XM530V200 делит карту на раздел записей (DirverType 0) и раздел снимков (DirverType 4) — для пользователя
 * это одна карта, поэтому разделы диска сводятся в один том: объём и занятое место суммируются, а [StorageVolume.device]
 * указывает раздел записей (его очищает форматирование). Размеры камера отдаёт в мегабайтах, hex-строкой.
 */
internal fun parseDvripStorageInfo(disks: JSONArray): StorageInfo {
    fun megabytes(text: String): Long = runCatching { java.lang.Long.decode(text.ifBlank { "0" }) }.getOrDefault(0L)

    val volumes = mutableListOf<StorageVolume>()
    for (d in 0 until disks.length()) {
        val disk = disks.optJSONObject(d) ?: continue
        val diskNo = disk.optInt("PlysicalNo", d)
        val partitions = disk.optJSONArray("Partition") ?: JSONArray()
        if (partitions.length() == 0) {
            // Диск есть, разделов нет — карта не размечена
            volumes += StorageVolume(path = "Диск $diskNo", totalBytes = 0, usedBytes = null, device = "$diskNo:0", state = "NoPartition")
            continue
        }
        var totalMb = 0L
        var usedMb = 0L
        var badStatus = 0
        // Раздел записей — первый с типом 0 (чтение и запись); если такого нет, первый раздел с объёмом
        var recordIndex = -1
        var firstSized = -1
        for (p in 0 until partitions.length()) {
            val part = partitions.optJSONObject(p) ?: continue
            val partTotal = megabytes(part.optString("TotalSpace"))
            val partRemain = megabytes(part.optString("RemainSpace"))
            totalMb += partTotal
            usedMb += (partTotal - partRemain).coerceAtLeast(0)
            part.optInt("Status", 0).let { if (it != 0 && badStatus == 0) badStatus = it }
            if (partTotal > 0 && firstSized < 0) firstSized = p
            if (partTotal > 0 && recordIndex < 0 && part.optInt("DirverType", 0) == 0) recordIndex = p
        }
        val format = if (recordIndex >= 0) recordIndex else maxOf(firstSized, 0)
        volumes += StorageVolume(
            path = "Диск $diskNo",
            totalBytes = totalMb * 1024 * 1024,
            usedBytes = if (totalMb > 0) usedMb * 1024 * 1024 else null,
            device = "$diskNo:$format",
            state = when {
                badStatus != 0 -> "Status$badStatus"
                totalMb == 0L -> "NoPartition"
                else -> "Success"
            },
            type = if (totalMb > 0 && badStatus == 0) "ReadWrite" else null
        )
    }
    return StorageInfo(volumes, null)
}
