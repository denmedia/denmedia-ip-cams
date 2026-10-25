package com.example.denmediaipcams.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Настоящие SPS/PPS потоков камер, у которых параметры в SDP не совпадают с потоком
 * (например, SDP описывает 1280×720 Baseline, а в кадрах идёт 1920×1080 Main).
 * Запоминаются из потока (см. [SdpFixingSocketFactory]) по адресу потока и подставляются в SDP
 * при следующем подключении, потому что декодер настраивается по SDP до прихода первого кадра.
 */
object RtspParamSets {

    private const val PREFS_NAME = "rtsp_param_sets"

    private val cache = ConcurrentHashMap<String, Pair<ByteArray, ByteArray>>()
    @Volatile private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = stored
        stored.all.forEach { (key, value) ->
            val parts = (value as? String)?.split(",") ?: return@forEach
            if (parts.size != 2) return@forEach
            runCatching {
                cache[key] = Base64.decode(parts[0], Base64.NO_WRAP) to Base64.decode(parts[1], Base64.NO_WRAP)
            }
        }
    }

    fun get(streamUri: String): Pair<ByteArray, ByteArray>? = cache[streamUri]

    /** Запоминает SPS/PPS потока; true — значения новые или изменились */
    fun learn(streamUri: String, sps: ByteArray, pps: ByteArray): Boolean {
        val known = cache[streamUri]
        if (known != null && known.first.contentEquals(sps) && known.second.contentEquals(pps)) return false
        cache[streamUri] = sps to pps
        prefs?.edit()?.putString(
            streamUri,
            Base64.encodeToString(sps, Base64.NO_WRAP) + "," + Base64.encodeToString(pps, Base64.NO_WRAP)
        )?.apply()
        return true
    }
}
