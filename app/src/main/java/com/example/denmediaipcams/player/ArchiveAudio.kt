package com.example.denmediaipcams.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Звук плеера архива: один выключатель на все камеры, запоминается между запусками */
object ArchiveAudio {

    private const val PREFS = "denmedia_player_prefs"
    private const val KEY_MUTED = "archive_muted"

    var isMuted by mutableStateOf(false)
        private set

    private var loaded = false

    /** Читает сохранённое значение при первом обращении */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        isMuted = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, false)
    }

    fun toggle(context: Context) {
        load(context)
        isMuted = !isMuted
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MUTED, isMuted).apply()
    }

    val volume: Float get() = if (isMuted) 0f else 1f
}
