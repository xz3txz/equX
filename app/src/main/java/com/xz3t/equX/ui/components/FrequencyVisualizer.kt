package com.xz3t.equX.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Динамический спектроанализатор частот с адаптивной высотой под размер экрана.
 * Значения [fftData] нормализованы в диапазоне 0..1.
 */
@Composable
fun FrequencyVisualizer(
    fftData: State<FloatArray>,
    isVisualizerActive: Boolean,
    onToggleActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val magnitudes = fftData.value
    val maxBars = 16
    val barColor = if (isVisualizerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    var showOnBadge by remember { mutableStateOf(false) }

    LaunchedEffect(isVisualizerActive) {
        if (isVisualizerActive) {
            showOnBadge = true
            delay(1000) // Показываем бейдж "ON" ровно 1 секунду
            showOnBadge = false
        } else {
            showOnBadge = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 80.dp)
            .clickable { onToggleActive(!isVisualizerActive) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val slotWidth = canvasWidth / maxBars
            val gap = slotWidth * 0.26f
            val barWidth = slotWidth - gap
            val barCorner = (barWidth / 2f).coerceAtMost(2.dp.toPx())

            for (i in 0 until maxBars) {
                val magnitude = if (isVisualizerActive && i < magnitudes.size) magnitudes[i].coerceIn(0f, 1f) else 0f
                val barHeight = magnitude * canvasHeight
                val x = i * slotWidth + gap / 2f

                if (barHeight > 0.5f) {
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, canvasHeight - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barCorner, barCorner)
                    )
                }
            }
        }

        // Интерактивный оверлей-бейдж: "OFF" бессрочно при выключении, "ON" исчезает через 1 секунду
        AnimatedVisibility(
            visible = !isVisualizerActive || showOnBadge,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isVisualizerActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isVisualizerActive) "ON" else "OFF",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isVisualizerActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
