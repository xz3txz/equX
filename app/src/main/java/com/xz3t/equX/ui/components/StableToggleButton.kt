package com.xz3t.equX.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xz3t.equX.R

/**
 * Вычисляет ширину, достаточную для отображения самого длинного из двух текстов.
 * Используется, чтобы кнопка «Настроить»/«Свернуть» не меняла размер и не двигала соседние иконки.
 * Ширина берётся из реального текста, а не из «магического» числа в dp.
 */
@Composable
fun rememberStableToggleWidth(
    collapsedText: String,
    expandedText: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val maxWidthPx = remember(collapsedText, expandedText, style) {
        maxOf(
            textMeasurer.measure(text = collapsedText, style = style).size.width,
            textMeasurer.measure(text = expandedText, style = style).size.width,
        )
    }
    return with(density) { maxWidthPx.toDp() }
}

/**
 * Кнопка-тумблер «Настроить ▼» / «Свернуть ▲» со стабильной шириной.
 */
@Composable
fun StableToggleTextButton(
    isExpanded: Boolean,
    collapsedText: String? = null,
    expandedText: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val collapsed = collapsedText ?: stringResource(R.string.toggle_collapse)
    val expanded = expandedText ?: stringResource(R.string.toggle_expand)
    val width = rememberStableToggleWidth(collapsed, expanded, style)

    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = modifier,
    ) {
        Text(
            text = if (isExpanded) expanded else collapsed,
            style = style,
            textAlign = TextAlign.Center,
            color = color,
            modifier = Modifier.width(width),
        )
    }
}

/**
 * Невидимая заглушка той же ширины, что и [StableToggleTextButton].
 * Используется для выравнивания строк, где кнопка отсутствует (например, пресет «По умолчанию»).
 */
@Composable
fun StableTogglePlaceholder(
    collapsedText: String? = null,
    expandedText: String? = null,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    horizontalPadding: Dp = 8.dp,
    minHeight: Dp = 36.dp,
) {
    val collapsed = collapsedText ?: stringResource(R.string.toggle_collapse)
    val expanded = expandedText ?: stringResource(R.string.toggle_expand)
    val textWidth = rememberStableToggleWidth(collapsed, expanded, style)

    Box(
        modifier = Modifier
            .width(textWidth + horizontalPadding * 2)
            .defaultMinSize(minHeight = minHeight),
    )
}
