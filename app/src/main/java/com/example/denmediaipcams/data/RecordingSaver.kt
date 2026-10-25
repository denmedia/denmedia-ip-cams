package com.example.denmediaipcams.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** Сохранённая запись: имя файла (система могла его изменить), адрес в MediaStore и тип содержимого */
class SavedRecording(val name: String, val uri: Uri, val mimeType: String)

/**
 * Сохранение скачанной записи камеры в «Загрузки/DenIPCam» через MediaStore (разрешения не нужны).
 * Запись пишется как «ожидающая»: пока загрузка не закончилась, файл не виден в галерее и файловых менеджерах,
 * а при ошибке или отмене удаляется — недокачанных файлов не остаётся.
 */
object RecordingSaver {

    /**
     * @param write пишет данные в переданный поток; возвращает число записанных байт
     * @return сохранённый файл
     */
    suspend fun save(
        context: Context,
        fileName: String,
        mimeType: String = "application/octet-stream",
        write: suspend (OutputStream) -> Long
    ): SavedRecording =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${SnapshotSaver.FOLDER_NAME}")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Не удалось создать файл в «Загрузках»")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Не удалось открыть файл для записи")
                out.use { write(it) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                // Система могла переименовать файл при совпадении имён
                val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: fileName
                SavedRecording(name, uri, mimeType)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }
}
