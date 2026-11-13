package com.xz3t.equX.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.xz3t.equX.R
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.responsiveLabelBoxWidth
import com.xz3t.equX.ui.theme.responsiveValueTextWidth

/**
 * Параметр-ползунок в под-карточке раскрывающейся карточки (Car, тонкомпенсация FX):
 * подпись, ползунок, значение (подсвечено, если отличается от сохранённого) и сброс.
 */
@Composable
fun EquXSubSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    originalValue: Float,
    onReset: () -> Unit,
) {
    val isModified = enabled && kotlin.math.abs(value - originalValue) > 0.001f

    EquXSubCard(isActive = enabled) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(responsiveLabelBoxWidth()),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
        EquXSlider(
            value = value,
            enabled = enabled,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(responsiveValueTextWidth()),
            textAlign = TextAlign.End,
            color = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
        )
        IconButton(
            enabled = isModified,
            onClick = onReset,
            modifier = Modifier.size(AppUiTheme.iconButtonSize)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.reset_desc),
                tint = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}
