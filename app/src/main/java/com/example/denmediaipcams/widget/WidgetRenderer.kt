package com.example.denmediaipcams.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.example.denmediaipcams.MainActivity
import com.example.denmediaipcams.R
import com.example.denmediaipcams.data.CameraRepository
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.player.ThumbnailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Отрисовка виджетов «Камера: трансляция» и «Камеры: список» */
internal object WidgetRenderer {

    /** Ширина кадра в виджете трансляции (+ RGB_565): весь виджет должен уложиться в ~1 МБ */
    const val LIVE_FRAME_WIDTH = 560
    private const val LIST_THUMB_WIDTH = 144

    private const val ONLINE_COLOR = 0xFF22C55E.toInt()
    private const val OFFLINE_COLOR = 0xFF8A97B1.toInt()

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun liveFrameKey(cameraId: String) = "widget_frame_$cameraId"

    fun liveWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, LiveCameraWidgetProvider::class.java))

    fun listWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, CameraListWidgetProvider::class.java))

    fun hasAnyWidget(context: Context) = liveWidgetIds(context).isNotEmpty() || listWidgetIds(context).isNotEmpty()

    fun loadCameras(context: Context): List<IpCamera> = CameraRepository(context.applicationContext).cameras.value

    /** Перерисовать все виджеты по сохранённым данным (из приложения: сменился список, превью, статусы) */
    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        if (!hasAnyWidget(appContext)) return
        updateScope.launch {
            renderAllLists(appContext)
            renderAllLiveFromCache(appContext)
        }
    }

    /** Открыть приложение с камерой на весь экран */
    fun openCameraPendingIntent(context: Context, cameraId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_FULLSCREEN, cameraId != null)
            cameraId?.let { putExtra(MainActivity.EXTRA_CAMERA_ID, it) }
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------------- Виджет трансляции ----------------

    fun renderLive(context: Context, widgetId: Int, camera: IpCamera?, frame: Bitmap?, frameAt: Long, isLive: Boolean) {
        val views = RemoteViews(context.packageName, R.layout.widget_live_camera)
        val errorMessage = camera?.let { WidgetPrefs.liveError(context, it.id) }

        // Камеру из виджета удалили (или пересоздали с новым id) — старый кадр показывать нельзя: он выглядит как «зависший»
        if (frame != null && camera != null) {
            views.setImageViewBitmap(R.id.widget_frame, toWidgetBitmap(frame, LIVE_FRAME_WIDTH))
            views.setViewVisibility(R.id.widget_placeholder, View.GONE)
        } else {
            views.setImageViewBitmap(R.id.widget_frame, null)
            views.setViewVisibility(R.id.widget_placeholder, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_placeholder,
                when {
                    camera == null -> "Камера удалена или изменена — удерживайте виджет и выберите камеру заново в его настройках"
                    errorMessage != null -> "⚠️ Ошибка подключения:\n$errorMessage"
                    else -> context.getString(R.string.widget_live_no_frame)
                }
            )
        }

        if (errorMessage != null && frame != null) {
            views.setTextViewText(R.id.widget_error_text, "⚠️ $errorMessage")
            views.setViewVisibility(R.id.widget_error_text, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_error_text, View.GONE)
        }

        val online = camera?.let { WidgetPrefs.isOnline(context, it.id) }
        val isError = errorMessage != null || online == false
        val statusDot = if (isError) "● " else if (online == true || isLive) "● " else ""
        views.setTextViewText(R.id.widget_camera_name, statusDot + (camera?.name ?: "—"))
        val textColor = when {
            errorMessage != null -> 0xFFEF4444.toInt()
            online == false && !isLive -> OFFLINE_COLOR
            else -> 0xFFFFFFFF.toInt()
        }
        views.setTextColor(R.id.widget_camera_name, textColor)
        views.setTextViewText(
            R.id.widget_frame_time,
            when {
                frameAt <= 0L -> ""
                isLive -> "LIVE " + timeFormat().format(Date(frameAt))
                else -> timeFormat().format(Date(frameAt))
            }
        )
        views.setViewVisibility(R.id.widget_frame_time, if (frameAt > 0L) View.VISIBLE else View.GONE)

        views.setImageViewResource(R.id.widget_toggle_live, if (isLive) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        if (camera != null) {
            views.setOnClickPendingIntent(R.id.widget_toggle_live, WidgetLiveService.togglePendingIntent(context, camera.id))
        }
        views.setOnClickPendingIntent(R.id.widget_root, openCameraPendingIntent(context, camera?.id, widgetId))

        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
    }

    /** Последний сохранённый кадр камеры (или её превью из приложения) */
    suspend fun cachedFrame(context: Context, cameraId: String): Pair<Bitmap?, Long> {
        val frame = ThumbnailStore.load(context, liveFrameKey(cameraId))
        if (frame != null) return frame to WidgetPrefs.liveFrameAt(context, cameraId)
        return ThumbnailStore.load(context, ThumbnailStore.cameraKey(cameraId)) to 0L
    }

    /** ▶/⏸ и подпись LIVE у каждого виджета зависят только от его собственной камеры */
    suspend fun renderAllLiveFromCache(context: Context) {
        val cameras = loadCameras(context).associateBy { it.id }
        val cache = mutableMapOf<String, Pair<Bitmap?, Long>>()
        liveWidgetIds(context).forEach { widgetId ->
            val cameraId = WidgetPrefs.liveCameraId(context, widgetId) ?: return@forEach
            val (frame, at) = cache.getOrPut(cameraId) { cachedFrame(context, cameraId) }
            renderLive(context, widgetId, cameras[cameraId], frame, at, WidgetLiveService.isLive(cameraId))
        }
    }

    // ---------------- Виджет-список ----------------

    suspend fun renderAllLists(context: Context) {
        val ids = listWidgetIds(context)
        if (ids.isEmpty()) return
        val cameras = loadCameras(context)

        val itemsBuilder = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            .setViewTypeCount(1)
        cameras.forEachIndexed { index, camera ->
            val item = RemoteViews(context.packageName, R.layout.widget_camera_list_item)
            item.setTextViewText(R.id.widget_item_name, camera.name)
            val online = WidgetPrefs.isOnline(context, camera.id)
            item.setTextViewText(
                R.id.widget_item_status,
                when (online) {
                    true -> "● Онлайн"
                    false -> "● Не в сети"
                    null -> "● Проверка..."
                }
            )
            item.setTextColor(R.id.widget_item_status, if (online == true) ONLINE_COLOR else OFFLINE_COLOR)
            val thumb = ThumbnailStore.load(context, ThumbnailStore.cameraKey(camera.id))
            if (thumb != null) {
                item.setImageViewBitmap(R.id.widget_item_thumbnail, toWidgetBitmap(thumb, LIST_THUMB_WIDTH))
            } else {
                item.setImageViewResource(R.id.widget_item_thumbnail, R.drawable.ic_widget_camera)
            }
            item.setOnClickFillInIntent(
                R.id.widget_item_root,
                Intent().putExtra(MainActivity.EXTRA_CAMERA_ID, camera.id).putExtra(MainActivity.EXTRA_FULLSCREEN, true)
            )
            itemsBuilder.addItem(index.toLong() + 1, item)
        }
        val items = itemsBuilder.build()

        val onlineCount = cameras.count { WidgetPrefs.isOnline(context, it.id) == true }
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_camera_list)
            views.setTextViewText(
                R.id.widget_summary,
                if (cameras.isEmpty()) "Нет камер" else "Камер: ${cameras.size} · онлайн: $onlineCount"
            )
            views.setRemoteAdapter(R.id.widget_list, items)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            // Шаблон нажатия на элемент списка; id камеры подставляет fill-in intent элемента
            val template = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            views.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(
                    context, 10_000 + widgetId, template,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, CameraListWidgetProvider.refreshPendingIntent(context))
            views.setOnClickPendingIntent(R.id.widget_header, openCameraPendingIntent(context, null, 20_000 + widgetId))
            manager.updateAppWidget(widgetId, views)
        }
    }

    /** Уменьшение и 16-битный цвет — укладываемся в лимит размера данных виджета */
    private fun toWidgetBitmap(source: Bitmap, maxWidth: Int): Bitmap {
        val scaled = if (source.width > maxWidth) {
            Bitmap.createScaledBitmap(source, maxWidth, (source.height.toLong() * maxWidth / source.width).toInt(), true)
        } else source
        return if (scaled.config == Bitmap.Config.RGB_565) scaled else scaled.copy(Bitmap.Config.RGB_565, false)
    }

    private fun timeFormat() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
}
