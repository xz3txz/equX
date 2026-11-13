package com.xz3t.equX.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Индикатор прокрутки (Scrollbar) слева для LazyColumn
 */
fun Modifier.drawLazyScrollbar(
    state: LazyListState,
    color: Color = Color.White.copy(alpha = 0.45f)
): Modifier = this.drawWithContent {
    drawContent()
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo

    if (totalItems > 0 && visibleItems.isNotEmpty()) {
        val viewportHeight = size.height

        // Оценка средней высоты элемента по реально видимым элементам
        val avgItemHeight = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
        val totalContentHeight = avgItemHeight * totalItems

        if (totalContentHeight > viewportHeight) {
            val scrollbarHeight = (viewportHeight / totalContentHeight * viewportHeight)
                .coerceAtLeast(16.dp.toPx())
            val maxScrollbarOffset = viewportHeight - scrollbarHeight

            // Плавная позиция на основе пиксельного смещения, а не индекса элемента.
            // firstVisibleItemScrollOffset даёт внутриэлементный сдвиг в px — без резких скачков.
            val scrollOffsetPx = state.firstVisibleItemIndex * avgItemHeight + state.firstVisibleItemScrollOffset
            val maxContentScroll = (totalContentHeight - viewportHeight).coerceAtLeast(1f)
            val progress = (scrollOffsetPx / maxContentScroll).coerceIn(0f, 1f)
            val scrollbarOffsetY = progress * maxScrollbarOffset

            drawRoundRect(
                color = color,
                topLeft = Offset(2.dp.toPx(), scrollbarOffsetY),
                size = Size(3.dp.toPx(), scrollbarHeight),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

/**
 * Индикатор прокрутки (Scrollbar) слева для обычных Column с verticalScroll
 */
fun Modifier.drawColumnScrollbar(
    state: ScrollState,
    color: Color = Color.White.copy(alpha = 0.45f)
): Modifier = this.drawWithContent {
    drawContent()
    val maxScroll = state.maxValue
    if (maxScroll > 0) {
        val visibleHeight = size.height
        val totalContentHeight = visibleHeight + maxScroll
        val scrollbarHeight = ((visibleHeight / totalContentHeight) * visibleHeight).coerceAtLeast(16.dp.toPx())
        val scrollbarOffsetY = (state.value.toFloat() / maxScroll) * (visibleHeight - scrollbarHeight)

        drawRoundRect(
            color = color,
            topLeft = Offset(2.dp.toPx(), scrollbarOffsetY),
            size = Size(3.dp.toPx(), scrollbarHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}
