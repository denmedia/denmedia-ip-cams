package com.example.denmediaipcams

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

/**
 * Разовое предупреждение о риске протокола XMEye/DVRIP (порт 34567): уведомление и баннер в окне архива
 * показываются по одному разу за всё время работы приложения (а не для каждой камеры или при каждом открытии
 * архива). Уведомление — когда архив по этому протоколу впервые успешно открылся (значит, пользователь им реально
 * пользуется, а не просто включил галочку). Модель — как у [DownloadNotifier], отдельный канал и
 * отдельный файл настроек (аналог dahuaProbePrefs в MainViewModel, но для «показано/не показано»).
 */
object SecurityNoticeNotifier {

    private const val CHANNEL_ID = "security_notices"
    private const val PREFS_NAME = "denmedia_dvrip_notice"
    private const val KEY_NOTIFIED = "notified"
    private const val KEY_BANNER = "banner_shown"
    private const val NOTIFICATION_ID = 0x44565250 // «DVRP»

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Версии до этой запоминали уведомление отдельно для каждой камеры (ключ — id камеры): значит, оно уже было */
    private fun SharedPreferences.alreadyNotified(): Boolean =
        getBoolean(KEY_NOTIFIED, false) || all.keys.any { it != KEY_NOTIFIED && it != KEY_BANNER }

    /** Нужно ли показать баннер в окне архива: только пока он ни разу не показывался */
    fun takeBannerTurn(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_BANNER, false)) return false
        prefs.edit().putBoolean(KEY_BANNER, true).apply()
        return true
    }

    fun noticeFirstSuccess(context: Context) {
        val prefs = prefs(context)
        if (prefs.alreadyNotified()) return
        prefs.edit().putBoolean(KEY_NOTIFIED, true).apply()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Небезопасные протоколы камер", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Небезопасный протокол камеры")
            .setContentText("Архив по XMEye/DVRIP (порт 34567): пароль передаётся слабо хэшированным, возможны зависания камеры.")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Для одной из камер включён архив по протоколу XMEye/DVRIP (порт 34567). Это устаревший и " +
                        "небезопасный протокол: пароль передаётся в слабо хэшированном виде, известны уязвимости, " +
                        "а некорректные сетевые запросы могут подвесить или перезагрузить некоторые прошивки камер. " +
                        "Функция используется только по вашему явному выбору и на ваш риск."
                )
            )
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
