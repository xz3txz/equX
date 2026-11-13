package com.xz3t.equX.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xz3t.equX.R
import com.xz3t.equX.ui.theme.AppUiTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopHeaderComponent(
    isEngineEnabled: Boolean,
    activeDeviceName: String,
    fftData: State<FloatArray>,
    isVisualizerActive: Boolean,
    modifier: Modifier = Modifier,
    hasUnsavedChanges: Boolean = false,
    onNavigateToPresets: () -> Unit = {},
    onVisualizerToggle: (Boolean) -> Unit = {},
    onEngineToggle: (Boolean) -> Unit = {},
    onOpenAccentSettings: () -> Unit = {},
    onDeviceTripleTap: () -> Unit = {},
) {
    // Три быстрых нажатия на карточку устройства — скрытое меню отладки.
    val tapTimestamps = remember { mutableStateListOf<Long>() }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = AppUiTheme.topHeaderBackgroundColor()
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AppUiTheme.cardElevation)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = AppUiTheme.cardInnerPaddingHorizontal,
                vertical = AppUiTheme.cardInnerPaddingVertical
            )
        ) {
            // Верхний ряд: Переключатель звука, индикатор несохраненных изменений и карточка устройства
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Глобальный переключатель Bypass (Вкл / Обход)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isEngineEnabled,
                        onCheckedChange = { onEngineToggle(it) }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isEngineEnabled) "On" else "Off",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isEngineEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onOpenAccentSettings,
                        modifier = Modifier.size(AppUiTheme.iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_content_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Индикатор несохраненных изменений
                    if (hasUnsavedChanges) {
                        Box(
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                                .clickable { onNavigateToPresets() }
                        )
                    }

                    // Информационная карточка подключенного аудиоустройства.
                    // Тройное нажатие (без визуального отклика) — скрытое меню отладки.
                    Card(
                        modifier = Modifier
                            .widthIn(min = 140.dp, max = 200.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(AppUiTheme.subCardCornerRadius))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                val now = System.currentTimeMillis()
                                tapTimestamps.removeAll { now - it > 600L }
                                tapTimestamps.add(now)
                                if (tapTimestamps.size >= 3) {
                                    tapTimestamps.clear()
                                    onDeviceTripleTap()
                                }
                            },
                        shape = RoundedCornerShape(AppUiTheme.subCardCornerRadius),
                        colors = CardDefaults.cardColors(
                            containerColor = AppUiTheme.activeHeaderColor()
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.device),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppUiTheme.accentColor()
                            )
                            Text(
                                text = activeDeviceName.ifBlank { stringResource(R.string.built_in_speaker) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Встроенный FFT-спектроанализатор частот
            FrequencyVisualizer(
                fftData = fftData,
                isVisualizerActive = isVisualizerActive,
                onToggleActive = onVisualizerToggle,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
