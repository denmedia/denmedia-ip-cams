package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Очень тонкая полоса прокрутки: только показывает положение в списке, на касания не реагирует
private val ScrollbarThickness = 2.dp
private val ScrollbarMinThumb = 24.dp
private val ScrollbarEdgePadding = 1.dp

@Composable
private fun scrollbarColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

/** verticalScroll() с тонкой полосой прокрутки справа */
@Composable
fun Modifier.verticalScrollWithBar(): Modifier {
    val state = rememberScrollState()
    return this.scrollbar(state, vertical = true, color = scrollbarColor()).verticalScroll(state)
}

/** horizontalScroll() с тонкой полосой прокрутки снизу */
@Composable
fun Modifier.horizontalScrollWithBar(): Modifier {
    val state = rememberScrollState()
    return this.scrollbar(state, vertical = false, color = scrollbarColor()).horizontalScroll(state)
}

/** Тонкая полоса прокрутки для LazyColumn */
@Composable
fun Modifier.lazyScrollbar(state: LazyListState): Modifier {
    val color = scrollbarColor()
    return drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount == 0) return@drawWithContent

        // Размер элементов оцениваем по видимым: точного общего размера у ленивого списка нет
        val itemSize = visible.sumOf { it.size }.toFloat() / visible.size
        val total = itemSize * info.totalItemsCount
        val viewport = size.height
        if (total <= viewport) return@drawWithContent

        val offset = state.firstVisibleItemIndex * itemSize + state.firstVisibleItemScrollOffset
        drawThumb(
            vertical = true,
            viewport = viewport,
            content = total,
            offset = offset.coerceIn(0f, total - viewport),
            color = color
        )
    }
}

private fun Modifier.scrollbar(state: ScrollState, vertical: Boolean, color: Color): Modifier =
    drawWithContent {
        drawContent()
        // До первого измерения maxValue равен Int.MAX_VALUE; 0 — прокручивать нечего
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val viewport = if (vertical) size.height else size.width
        drawThumb(
            vertical = vertical,
            viewport = viewport,
            content = viewport + max,
            offset = state.value.toFloat(),
            color = color
        )
    }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(
    vertical: Boolean,
    viewport: Float,
    content: Float,
    offset: Float,
    color: Color
) {
    val thickness = ScrollbarThickness.toPx()
    val edge = ScrollbarEdgePadding.toPx()
    val thumb = (viewport * viewport / content).coerceIn(ScrollbarMinThumb.toPx().coerceAtMost(viewport), viewport)
    val maxOffset = (content - viewport).coerceAtLeast(1f)
    val position = (offset / maxOffset).coerceIn(0f, 1f) * (viewport - thumb)
    val radius = CornerRadius(thickness / 2)
    if (vertical) {
        drawRoundRect(color, Offset(size.width - thickness - edge, position), Size(thickness, thumb), radius)
    } else {
        drawRoundRect(color, Offset(position, size.height - thickness - edge), Size(thumb, thickness), radius)
    }
}

/** LazyColumn с тонкой полосой прокрутки справа */
@Composable
fun LazyColumnWithBar(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.foundation.lazy.LazyColumn(
        state = state,
        verticalArrangement = verticalArrangement,
        modifier = modifier.lazyScrollbar(state),
        content = content
    )
}
