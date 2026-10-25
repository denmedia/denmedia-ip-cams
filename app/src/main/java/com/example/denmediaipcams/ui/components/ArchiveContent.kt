package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Смартфон в альбомной ориентации: окно архива делится на видео слева и список файлов справа */
@Composable
fun isPhoneLandscape(): Boolean =
    isPhoneScreen() && LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

/** Пустое место под видео записи: занимает столько же, сколько плеер, пока запись не выбрана */
@Composable
fun ArchivePlaceholder(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PlayCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Выберите запись для просмотра",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Содержимое окна архива: видео (или плейсхолдер) и список записей.
 * @param split видео слева, список справа (смартфон по горизонтали)
 * @param pinVideo видео закреплено сверху, список под ним прокручивается (смартфон по вертикали)
 * @param video получает размер 16:9 по ширине отведённого места
 */
@Composable
fun ArchiveContent(
    split: Boolean,
    pinVideo: Boolean,
    modifier: Modifier = Modifier,
    video: @Composable (Modifier) -> Unit,
    list: @Composable ColumnScope.() -> Unit
) {
    val videoModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    when {
        split -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp)
        ) {
            // Плеер занимает 60% ширины дисплея; слева отступ вдвое меньше обычного
            val playerWidth = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
            Box(modifier = Modifier.width(playerWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                video(videoModifier)
            }
            ScrollingList(Modifier.weight(1f).fillMaxHeight(), list)
        }
        pinVideo -> Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            video(videoModifier)
            Spacer(modifier = Modifier.height(8.dp))
            ScrollingList(Modifier.weight(1f).fillMaxWidth(), list)
        }
        else -> Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            video(videoModifier)
            Spacer(modifier = Modifier.height(8.dp))
            list()
        }
    }
}

/** Отступы окна архива на смартфоне: сверху и по бокам — вырез и системные панели, снизу — до края дисплея */
@Composable
fun Modifier.archivePhoneInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

/** Высота списка записей внутри прокручиваемой области: список файлов доходит до нижнего края окна */
val LocalArchiveListHeight = compositionLocalOf { 300.dp }

/** Прокручиваемая колонка с содержимым; списки внутри неё получают высоту, занимающую остаток экрана */
@Composable
private fun ScrollingList(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = modifier) {
        // Над списком остаются переключатель режимов и строка с датой
        val listHeight = (maxHeight - 120.dp).coerceAtLeast(200.dp)
        CompositionLocalProvider(LocalArchiveListHeight provides listHeight) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
        }
    }
}
