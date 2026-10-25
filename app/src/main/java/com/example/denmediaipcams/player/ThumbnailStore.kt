package com.example.denmediaipcams.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Превью камер и сохранённых положений (JPEG во внутреннем хранилище приложения).
 * versions меняется при каждой записи — по нему экраны понимают, что картинку пора перечитать.
 */
object ThumbnailStore {

    private const val TAG = "ThumbnailStore"
    private const val DIR_NAME = "thumbnails"
    private const val JPEG_QUALITY = 85

    /** Ширина превью: достаточно для карточек списка и полосы пресетов */
    const val THUMBNAIL_WIDTH = 480

    private val _versions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val versions: StateFlow<Map<String, Long>> = _versions.asStateFlow()

    fun cameraKey(cameraId: String) = "camera_$cameraId"
    fun presetKey(cameraId: String, presetId: Int) = "preset_${cameraId}_$presetId"

    private fun file(context: Context, key: String): File {
        val safeKey = key.replace(Regex("""[^A-Za-z0-9_\-]"""), "_")
        return File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, "$safeKey.jpg")
    }

    fun exists(context: Context, key: String): Boolean = file(context, key).exists()

    suspend fun save(context: Context, key: String, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            // Пишем во временный файл и переименовываем — превью не бывает «наполовину записанным»
            val target = file(context, key)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            _versions.update { it + (key to System.currentTimeMillis()) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save thumbnail $key: ${e.message}")
            false
        }
    }

    suspend fun load(context: Context, key: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(context, key)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    fun delete(context: Context, key: String) {
        file(context, key).delete()
        _versions.update { it + (key to System.currentTimeMillis()) }
    }

    /** Удаляет превью камеры и всех её положений */
    fun deleteCamera(context: Context, cameraId: String) {
        val prefixes = listOf(cameraKey(cameraId), "preset_${cameraId}_")
            .map { it.replace(Regex("""[^A-Za-z0-9_\-]"""), "_") }
        File(context.filesDir, DIR_NAME).listFiles()?.forEach { f ->
            if (prefixes.any { f.name.startsWith(it) }) f.delete()
        }
        _versions.update { versions -> versions.filterKeys { key -> prefixes.none { key.startsWith(it) } } }
    }
}
