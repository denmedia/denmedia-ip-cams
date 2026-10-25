package com.example.denmediaipcams

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.denmediaipcams.data.SavedRecording
import com.example.denmediaipcams.data.SnapshotSaver

/** Уведомление о скачанной записи архива: по нажатию файл открывается в плеере по умолчанию */
object DownloadNotifier {

    private const val CHANNEL_ID = "downloads"

    fun notifyDownloaded(context: Context, saved: SavedRecording) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Скачанные записи", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(saved.uri, saved.mimeType.takeIf { it.startsWith("video/") } ?: "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(
            context,
            saved.uri.hashCode(),
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Запись скачана")
            .setContentText("${saved.name} · Загрузки/${SnapshotSaver.FOLDER_NAME}")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(saved.uri.hashCode(), notification)
    }
}
