package com.example.denmediaipcams

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Сохраняет текст необработанного исключения в файл (в том числе из потока ExoPlayer),
 * чтобы при следующем запуске показать причину вылета вместо тихого закрытия приложения.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText("Поток: ${thread.name}\n${Log.getStackTraceString(error).take(6000)}")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось сохранить отчёт о вылете: ${e.message}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Текст последнего вылета; после чтения файл удаляется */
    fun takeLastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.readText().also { file.delete() }
        } catch (_: Exception) {
            null
        }
    }
}
