package com.example.denmediaipcams.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Асинхронная работа в ресивере: система ждёт до ~10 с после goAsync() */
private fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

private fun onAnyWidgetRemoved(context: Context) {
    if (!WidgetRenderer.hasAnyWidget(context)) {
        WidgetRefreshWorker.cancelPeriodic(context)
        WidgetLiveService.stop(context)
    }
}

/**
 * Виджет «Камера: трансляция»: кадры выбранной камеры, обновляемые каждые 2 секунды
 * (кнопка ▶ или сворачивание приложения), нажатие открывает камеру на весь экран.
 */
class LiveCameraWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WidgetRefreshWorker.schedulePeriodic(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        launchAsync {
            WidgetRenderer.renderAllLiveFromCache(context)
        }
        WidgetRefreshWorker.refreshNow(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        launchAsync { WidgetRenderer.renderAllLiveFromCache(context) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.removeWidget(context, it) }
    }

    override fun onDisabled(context: Context) = onAnyWidgetRemoved(context)
}

/**
 * Виджет «Камеры: список»: превью, название и статус каждой камеры,
 * нажатие на камеру открывает её трансляцию на весь экран.
 */
class CameraListWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WidgetRefreshWorker.schedulePeriodic(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        launchAsync { WidgetRenderer.renderAllLists(context) }
        WidgetRefreshWorker.refreshNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            WidgetRefreshWorker.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) = onAnyWidgetRemoved(context)

    companion object {
        private const val ACTION_REFRESH = "com.example.denmediaipcams.widget.REFRESH_LIST"

        fun refreshPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, CameraListWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
