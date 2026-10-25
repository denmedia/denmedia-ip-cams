package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * LazyColumn с полосой прокрутки, в котором элементы переставляются перетаскиванием:
 * удерживайте элемент и тяните вверх или вниз. [itemContent] получает модификатор,
 * который нужно применить к корню элемента.
 */
@Composable
fun <T> ReorderableLazyColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    itemContent: @Composable LazyItemScope.(item: T, itemModifier: Modifier) -> Unit
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentItems by rememberUpdatedState(items)
    val currentKey by rememberUpdatedState(key)
    val currentOnMove by rememberUpdatedState(onMove)

    LazyColumn(
        state = listState,
        verticalArrangement = verticalArrangement,
        modifier = modifier.lazyScrollbar(listState)
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { _, item ->
            val itemKey = key(item)
            val isDragging = draggingKey == itemKey
            val itemModifier = (if (isDragging) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = dragOffset }
                    .shadow(8.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            } else {
                Modifier.animateItem()
            }).pointerInput(itemKey) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        draggingKey = itemKey
                        dragOffset = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val dragged = visible.firstOrNull { it.key == itemKey } ?: return@detectDragGesturesAfterLongPress
                        val center = dragged.offset + dragged.size / 2f + dragOffset
                        val target = visible.firstOrNull {
                            it.key != itemKey && center >= it.offset && center <= it.offset + it.size
                        } ?: return@detectDragGesturesAfterLongPress
                        val list = currentItems
                        val from = list.indexOfFirst { currentKey(it) == itemKey }
                        val to = list.indexOfFirst { currentKey(it) == target.key }
                        if (from >= 0 && to >= 0 && from != to) {
                            currentOnMove(from, to)
                            // Элемент встанет на место цели: смещение пересчитываем, чтобы он остался под пальцем
                            dragOffset += dragged.offset - target.offset
                        }
                    },
                    onDragEnd = { draggingKey = null; dragOffset = 0f },
                    onDragCancel = { draggingKey = null; dragOffset = 0f }
                )
            }
            itemContent(item, itemModifier)
        }
    }
}
