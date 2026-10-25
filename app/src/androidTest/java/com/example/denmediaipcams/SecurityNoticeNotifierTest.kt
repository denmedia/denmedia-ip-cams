package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Предупреждение о протоколе DVRIP показывается один раз за всё время, а не для каждой камеры */
@RunWith(AndroidJUnit4::class)
class SecurityNoticeNotifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("denmedia_dvrip_notice", android.content.Context.MODE_PRIVATE)

    @Test
    fun bannerIsShownOnlyOnce() {
        prefs.edit().clear().commit()
        assertTrue(SecurityNoticeNotifier.takeBannerTurn(context))
        assertFalse(SecurityNoticeNotifier.takeBannerTurn(context))
        assertFalse(SecurityNoticeNotifier.takeBannerTurn(context))
    }

    @Test
    fun notificationIsRememberedOnceForAllCameras() {
        prefs.edit().clear().commit()
        SecurityNoticeNotifier.noticeFirstSuccess(context)
        assertTrue(prefs.getBoolean("notified", false))
        // Второй вызов (в том числе «для другой камеры») ничего не меняет и не падает
        SecurityNoticeNotifier.noticeFirstSuccess(context)
    }

    @Test
    fun legacyPerCameraRecordCountsAsAlreadyNotified() {
        // Прежняя версия записывала флаг по id камеры: после обновления уведомление повторяться не должно
        prefs.edit().clear().putBoolean("some-camera-id", true).commit()
        SecurityNoticeNotifier.noticeFirstSuccess(context)
        assertFalse(prefs.getBoolean("notified", false))
    }
}
