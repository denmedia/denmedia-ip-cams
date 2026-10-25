package com.example.denmediaipcams.widget

import android.content.Context
import androidx.core.content.edit

/**
 * Настройки виджетов: какая камера в каком виджете трансляции,
 * последние известные статусы камер (виджеты не видят состояние приложения).
 */
internal object WidgetPrefs {

    private const val PREFS = "denmedia_widgets"
    private const val KEY_LIVE_CAMERA = "live_camera_"
    private const val KEY_ONLINE = "online_"
    private const val KEY_LIVE_FRAME_AT = "live_frame_at_"
    private const val KEY_LIVE_ERROR = "live_error_"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun liveCameraId(context: Context, widgetId: Int): String? =
        prefs(context).getString(KEY_LIVE_CAMERA + widgetId, null)

    fun setLiveCameraId(context: Context, widgetId: Int, cameraId: String) =
        prefs(context).edit { putString(KEY_LIVE_CAMERA + widgetId, cameraId) }

    fun removeWidget(context: Context, widgetId: Int) =
        prefs(context).edit { remove(KEY_LIVE_CAMERA + widgetId) }

    /** null — статус ещё не проверялся */
    fun isOnline(context: Context, cameraId: String): Boolean? {
        val p = prefs(context)
        return if (p.contains(KEY_ONLINE + cameraId)) p.getBoolean(KEY_ONLINE + cameraId, false) else null
    }

    /** @return true, если хоть один статус изменился */
    fun saveOnline(context: Context, statuses: Map<String, Boolean>): Boolean {
        val p = prefs(context)
        val changed = statuses.any { (id, online) -> !p.contains(KEY_ONLINE + id) || p.getBoolean(KEY_ONLINE + id, false) != online }
        if (changed) p.edit { statuses.forEach { (id, online) -> putBoolean(KEY_ONLINE + id, online) } }
        return changed
    }

    /** Время последнего кадра в виджете трансляции (для подписи «обновлено в ...») */
    fun liveFrameAt(context: Context, cameraId: String): Long =
        prefs(context).getLong(KEY_LIVE_FRAME_AT + cameraId, 0L)

    fun setLiveFrameAt(context: Context, cameraId: String, time: Long) =
        prefs(context).edit { putLong(KEY_LIVE_FRAME_AT + cameraId, time) }

    /** Текст ошибки подключения в виджете (null — ошибки нет) */
    fun liveError(context: Context, cameraId: String): String? =
        prefs(context).getString(KEY_LIVE_ERROR + cameraId, null)

    fun setLiveError(context: Context, cameraId: String, error: String?) =
        prefs(context).edit {
            if (error != null) putString(KEY_LIVE_ERROR + cameraId, error)
            else remove(KEY_LIVE_ERROR + cameraId)
        }
}
