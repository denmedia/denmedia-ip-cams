package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.player.ThumbnailStore

/** Превью из ThumbnailStore; перечитывается автоматически, когда превью обновляется */
@Composable
fun rememberThumbnail(key: String): ImageBitmap? {
    val context = LocalContext.current
    val versions by ThumbnailStore.versions.collectAsState()
    val version = versions[key]
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key, version) {
        value = ThumbnailStore.load(context, key)?.asImageBitmap()
    }
    return bitmap
}

/**
 * Картинка-превью; если превью ещё нет — тёмная заглушка с иконкой.
 */
@Composable
fun ThumbnailImage(
    key: String,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.Videocam,
    /** Идёт съёмка этого превью: картинка полупрозрачная и размытая, поверх — индикатор загрузки */
    loading: Boolean = false
) {
    val bitmap = rememberThumbnail(key)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (loading) Modifier.alpha(0.5f).blur(6.dp) else Modifier)
            )
        } else {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}
