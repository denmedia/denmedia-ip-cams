package com.example.denmediaipcams.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.example.denmediaipcams.ui.components.LazyColumnWithBar
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.denmediaipcams.R
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.ui.components.ThumbnailImage
import com.example.denmediaipcams.ui.theme.DenMediaIPCamsTheme
import kotlinx.coroutines.launch

/**
 * Настройка виджета «Камера: трансляция»: выбор камеры при добавлении виджета на домашний экран.
 * Без выбора система удаляет виджет (результат по умолчанию — RESULT_CANCELED).
 */
class LiveWidgetConfigActivity : ComponentActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    @androidx.compose.material3.ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val cameras = WidgetRenderer.loadCameras(this)

        setContent {
            DenMediaIPCamsTheme {
                CameraPickerScreen(cameras = cameras, onPick = ::onCameraPicked)
            }
        }
    }

    private fun onCameraPicked(camera: IpCamera) {
        WidgetPrefs.setLiveCameraId(this, widgetId, camera.id)
        lifecycleScope.launch {
            val (frame, at) = WidgetRenderer.cachedFrame(this@LiveWidgetConfigActivity, camera.id)
            WidgetRenderer.renderLive(this@LiveWidgetConfigActivity, widgetId, camera, frame, at, isLive = false)
            setResult(RESULT_OK, android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun CameraPickerScreen(cameras: List<IpCamera>, onPick: (IpCamera) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_config_title)) },
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_name),
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        if (cameras.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Сначала добавьте камеру в приложении", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumnWithBar(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
        ) {
            items(cameras, key = { it.id }) { camera ->
                CameraPickerRow(camera = camera, onClick = { onPick(camera) })
            }
        }
    }
}

@Composable
private fun CameraPickerRow(camera: IpCamera, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            ThumbnailImage(
                key = com.example.denmediaipcams.player.ThumbnailStore.cameraKey(camera.id),
                placeholderIcon = Icons.Default.Videocam,
                modifier = Modifier
                    .size(width = 84.dp, height = 52.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(camera.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(camera.ipAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
