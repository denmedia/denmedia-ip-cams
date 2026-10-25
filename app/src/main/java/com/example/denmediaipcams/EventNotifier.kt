package com.example.denmediaipcams

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.example.denmediaipcams.model.IpCamera

/** Системные уведомления о событиях камер (движение и др.) */
object EventNotifier {

    private const val CHANNEL_ID = "camera_events"
    // Камера шлёт события пачками: повторное уведомление того же типа не чаще, чем раз в полминуты
    private const val THROTTLE_MS = 30_000L

    private val lastShownAt = mutableMapOf<String, Long>()

    fun notify(context: Context, camera: IpCamera, title: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val key = "${camera.id}|$title"
        val now = SystemClock.elapsedRealtime()
        synchronized(lastShownAt) {
            val last = lastShownAt[key]
            if (last != null && now - last < THROTTLE_MS) return
            lastShownAt[key] = now
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "События камер", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Движение, пересечение линии и другие события камер Dahua"
            }
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_camera)
            .setContentTitle(camera.name)
            .setContentText(title)
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(camera.id.hashCode(), notification)
    }
}
