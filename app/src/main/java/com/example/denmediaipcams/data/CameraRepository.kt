package com.example.denmediaipcams.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.UUID

class CameraRepository(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("denmedia_ip_cams_prefs", Context.MODE_PRIVATE)

    private val _cameras = MutableStateFlow<List<IpCamera>>(loadCamerasFromPrefs())
    val cameras: StateFlow<List<IpCamera>> = _cameras.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String?>(loadSelectedCameraIdFromPrefs())
    val selectedCameraId: StateFlow<String?> = _selectedCameraId.asStateFlow()

    fun selectCamera(id: String) {
        _selectedCameraId.value = id
        prefs?.edit()?.putString(KEY_SELECTED_CAM_ID, id)?.apply()
    }

    fun addCamera(camera: IpCamera): IpCamera {
        val newCamera = if (camera.id.isBlank()) {
            camera.copy(id = UUID.randomUUID().toString())
        } else camera

        val newList = _cameras.value + newCamera
        _cameras.value = newList
        if (_selectedCameraId.value == null) {
            selectCamera(newCamera.id)
        }
        saveCamerasToPrefs(newList)
        return newCamera
    }

    fun updateCamera(updatedCamera: IpCamera) {
        val newList = _cameras.value.map {
            if (it.id == updatedCamera.id) updatedCamera else it
        }
        _cameras.value = newList
        saveCamerasToPrefs(newList)
    }

    fun moveCamera(from: Int, to: Int) {
        val current = _cameras.value
        if (from !in current.indices || to !in current.indices || from == to) return
        val newList = current.toMutableList().apply { add(to, removeAt(from)) }
        _cameras.value = newList
        saveCamerasToPrefs(newList)
    }

    fun deleteCamera(id: String) {
        val newList = _cameras.value.filter { it.id != id }
        _cameras.value = newList
        if (_selectedCameraId.value == id) {
            val nextId = newList.firstOrNull()?.id
            _selectedCameraId.value = nextId
            prefs?.edit()?.putString(KEY_SELECTED_CAM_ID, nextId)?.apply()
        }
        saveCamerasToPrefs(newList)
    }

    private fun loadSelectedCameraIdFromPrefs(): String? {
        val savedId = prefs?.getString(KEY_SELECTED_CAM_ID, null)
        return savedId ?: _cameras.value.firstOrNull()?.id
    }

    private fun loadCamerasFromPrefs(): List<IpCamera> {
        // Демо-камеры — только при самом первом запуске, когда список камер ещё ни разу не сохранялся.
        // Пустой сохранённый список означает, что пользователь удалил все камеры, — демо не возвращаем
        if (prefs == null || !prefs.contains(KEY_CAMERAS_JSON)) {
            val defaults = initialCameras()
            saveCamerasToPrefs(defaults)
            return defaults
        }
        val jsonStr = prefs.getString(KEY_CAMERAS_JSON, null)
        if (jsonStr.isNullOrBlank()) return emptyList()

        return try {
            val jsonArray = JSONArray(jsonStr)
            // Камеры, сохранённые до появления опции autoSyncTime, время сами не меняют: включить можно в настройках
            (0 until jsonArray.length()).map { CameraJson.fromJson(jsonArray.getJSONObject(it), defaultAutoSyncTime = false) }
        } catch (e: Exception) {
            // Повреждённые данные не подменяем демо-камерами
            Log.e(TAG, "Failed to parse saved cameras", e)
            emptyList()
        }
    }

    private fun saveCamerasToPrefs(cameras: List<IpCamera>) {
        if (prefs == null) return
        try {
            val jsonArray = JSONArray()
            cameras.forEach { jsonArray.put(CameraJson.toJson(it)) }
            prefs.edit().putString(KEY_CAMERAS_JSON, jsonArray.toString()).apply()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "CameraRepository"
        private const val KEY_CAMERAS_JSON = "cameras_json"
        private const val KEY_SELECTED_CAM_ID = "selected_camera_id"

        fun initialCameras(): List<IpCamera> {
            return emptyList()
        }
    }
}
