package com.xz3t.equX.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xz3t.equX.ui.theme.AppUiTheme

/**
 * Тактильный круглый бегунок слайдера с удобным размером для касания.
 */
@Composable
fun EquXSliderThumb(
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val thumbColor = if (enabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(thumbColor)
    )
}

/**
 * Четкий, удобный слайдер с толщиной дорожки 6dp для точной регулировки в авто и на смартфоне.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquXSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            EquXSliderThumb(enabled = enabled)
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                enabled = enabled,
                modifier = Modifier.height(6.dp),
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null,
                colors = SliderDefaults.colors(
                    activeTrackColor = if (enabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledActiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                )
            )
        }
    )
}
