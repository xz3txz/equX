package com.xz3t.equX.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xz3t.equX.R
import com.xz3t.equX.audio.LoudnessStatus
import com.xz3t.equX.audio.SpatialWidthStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.xz3t.equX.data.LoudnessConfig
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.SpatialFxConfig
import com.xz3t.equX.ui.components.EquXCheckbox
import com.xz3t.equX.ui.components.EquXMainCard
import com.xz3t.equX.ui.components.EquXSlider
import com.xz3t.equX.ui.components.EquXSubCard
import com.xz3t.equX.ui.components.EquXSubSlider
import com.xz3t.equX.ui.components.StableToggleTextButton
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.drawColumnScrollbar
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.responsiveLabelBoxWidth
import com.xz3t.equX.ui.theme.responsiveValueTextWidth
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpatialFxScreen(
    dataManager: PresetDataManager,
    onInteraction: () -> Boolean = { true },
    onFxEnabledChanged: (Boolean) -> Unit = {},
    // enabled, ширина %, верха %
    onSpatialChanged: (Boolean, Int, Int) -> Unit = { _, _, _ -> },
    onLoudnessChanged: (LoudnessConfig) -> Unit = {},
    loudnessStatus: StateFlow<LoudnessStatus>? = null,
    // Работает ли «Ширина» (Virtualizer) — точка-индикатор на карточке «Пространство».
    spatialWidthStatus: StateFlow<SpatialWidthStatus>? = null,
    modifier: Modifier = Modifier
) {
    val currentPreset = dataManager.liveDraftPreset
    val originalPreset = dataManager.originalPreset

    // --- Общий мастер-тумблер FX ---
    var isFxEnabled by remember(currentPreset.presetName, currentPreset.fxEnabled) {
        mutableStateOf(currentPreset.fxEnabled)
    }

    var showInfoDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawColumnScrollbar(scrollState)
            .padding(horizontal = AppUiTheme.cardOuterPaddingHorizontal, vertical = AppUiTheme.cardOuterPaddingVertical)
            .verticalScroll(scrollState)
    ) {
        // --- ОБЩИЙ ТУМБЛЕР FX ---
        EquXMainCard(
            modifier = Modifier.padding(bottom = AppUiTheme.cardSpacing),
            isActive = true
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = isFxEnabled,
                    onCheckedChange = { checked ->
                        if (onInteraction()) {
                            isFxEnabled = checked
                            dataManager.updateLiveDraft(
                                dataManager.liveDraftPreset.copy(rawFxEnabled = checked)
                            )
                            onFxEnabledChanged(checked)
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.fx_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(if (isFxEnabled) R.string.enabled else R.string.disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFxEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.error
                    )
                }
                IconButton(
                    onClick = { showInfoDialog = true },
                    modifier = Modifier.size(AppUiTheme.iconButtonSize)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.fx_info_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- ПРОСТРАНСТВО ---
        SpaceCard(
            dataManager = dataManager,
            isFxEnabled = isFxEnabled,
            onInteraction = onInteraction,
            onSpatialChanged = onSpatialChanged,
            widthStatus = spatialWidthStatus,
        )

        // --- ТОНКОМПЕНСАЦИЯ ---
        LoudnessCard(
            dataManager = dataManager,
            isFxEnabled = isFxEnabled,
            onInteraction = onInteraction,
            onLoudnessChanged = onLoudnessChanged,
            loudnessStatus = loudnessStatus,
        )

    }

    if (showInfoDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.fx_title)) },
            text = { Text(stringResource(R.string.fx_info_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.got_it))
                }
            }
        )
    }
}

/**
 * «Пространство»: ширина (системный Virtualizer) и подъём верхов (PreEQ 1–16 кГц) — раздельно.
 * Оформление как у раскрывающихся карточек вкладки Car.
 */
@Composable
private fun SpaceCard(
    dataManager: PresetDataManager,
    isFxEnabled: Boolean,
    onInteraction: () -> Boolean,
    onSpatialChanged: (Boolean, Int, Int) -> Unit,
    widthStatus: StateFlow<SpatialWidthStatus>?,
) {
    val current = dataManager.liveDraftPreset.spatial3d
    val original = dataManager.originalPreset.spatial3d
    var config by remember(dataManager.liveDraftPreset.presetName, current) { mutableStateOf(current) }
    var expanded by remember { mutableStateOf(false) }
    val isActive = isFxEnabled && config.isEnabled
    // Галочка сравнивается всегда (иначе снятую галочку нельзя вернуть сбросом),
    // ползунки — только у включённого «Пространства», как в hasUnsavedChanges.
    val isModified = isFxEnabled && (
        config.isEnabled != original.isEnabled || (isActive && (
            config.strengthPercent != original.strengthPercent || config.airPercent != original.airPercent
        ))
    )

    fun update(newConfig: SpatialFxConfig) {
        if (!onInteraction()) return
        config = newConfig
        dataManager.updateLiveDraft(dataManager.liveDraftPreset.copy(spatial3d = newConfig))
        onSpatialChanged(newConfig.isEnabled, newConfig.strengthPercent, newConfig.airPercent)
    }

    EquXMainCard(
        modifier = Modifier.padding(bottom = AppUiTheme.cardSpacing),
        isActive = isActive
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Индикатор «Ширины», как у источников скорости и способов громкости на вкладке Car:
            // серый — не проверено (выключено или тишина), зелёный — работает, красный — система блокирует.
            if (isActive && config.strengthPercent > 0 && widthStatus != null) {
                val status by widthStatus.collectAsState()
                val description = stringResource(
                    when (status) {
                        SpatialWidthStatus.UNKNOWN -> R.string.spatial_width_status_unknown
                        SpatialWidthStatus.WORKS -> R.string.spatial_width_status_works
                        SpatialWidthStatus.BLOCKED -> R.string.spatial_width_status_blocked
                    }
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (status) {
                                SpatialWidthStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
                                SpatialWidthStatus.WORKS -> Color(0xFF2E7D32)
                                SpatialWidthStatus.BLOCKED -> MaterialTheme.colorScheme.error
                            }
                        )
                        .semantics { contentDescription = description }
                )
                Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
            }
            Text(
                text = stringResource(R.string.spatial3d_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EquXCheckbox(
                checked = config.isEnabled,
                enabled = isFxEnabled,
                onCheckedChange = { checked ->
                    // Включили с нулями — ставим 50/50, иначе галочка ничего не даст.
                    var next = config.copy(isEnabled = checked)
                    if (checked && next.strengthPercent == 0 && next.airPercent == 0) {
                        next = next.copy(strengthPercent = 50).withAir(50)
                    }
                    update(next)
                }
            )
            IconButton(
                enabled = isModified,
                onClick = { update(original) },
                modifier = Modifier.size(AppUiTheme.iconButtonSize)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.reset_desc),
                    tint = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            }
            StableToggleTextButton(
                isExpanded = expanded,
                onClick = { expanded = !expanded },
                color = if (isActive) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EquXSubSlider(
                    label = stringResource(R.string.spatial_width),
                    valueText = "${config.strengthPercent}%",
                    value = config.strengthPercent.toFloat(),
                    valueRange = 0f..100f,
                    enabled = isActive,
                    onValueChange = { update(config.copy(strengthPercent = it.roundToInt())) },
                    originalValue = original.strengthPercent.toFloat(),
                    onReset = { update(config.copy(strengthPercent = original.strengthPercent)) },
                )
                EquXSubSlider(
                    label = stringResource(R.string.spatial_air),
                    valueText = "${config.airPercent}%",
                    value = config.airPercent.toFloat(),
                    valueRange = 0f..100f,
                    enabled = isActive,
                    onValueChange = { update(config.withAir(it.roundToInt())) },
                    originalValue = original.airPercent.toFloat(),
                    onReset = { update(config.withAir(original.airPercent)) },
                )
            }
        }
    }
}

/**
 * Тонкомпенсация по кривым равной громкости: сила (доля от кривой ISO 226) и порог громкости,
 * выше которого компенсации нет. Оформлена как раскрывающиеся карточки вкладки Car:
 * заголовок с галочкой, сбросом и «развернуть», параметры — под-карточками.
 */
@Composable
private fun LoudnessCard(
    dataManager: PresetDataManager,
    isFxEnabled: Boolean,
    onInteraction: () -> Boolean,
    onLoudnessChanged: (LoudnessConfig) -> Unit,
    loudnessStatus: StateFlow<LoudnessStatus>?,
) {
    val current = dataManager.liveDraftPreset.loudness
    val original = dataManager.originalPreset.loudness
    var config by remember(dataManager.liveDraftPreset.presetName, current) { mutableStateOf(current) }
    var expanded by remember { mutableStateOf(false) }
    val isActive = isFxEnabled && config.isEnabled
    // Как у «Пространства»: галочка сравнивается всегда, ползунки — только у включённой.
    val isModified = isFxEnabled && (
        config.isEnabled != original.isEnabled || (isActive && config != original)
    )

    fun update(newConfig: LoudnessConfig) {
        if (!onInteraction()) return
        config = newConfig
        dataManager.updateLiveDraft(dataManager.liveDraftPreset.copy(rawLoudness = newConfig))
        onLoudnessChanged(newConfig)
    }

    EquXMainCard(
        modifier = Modifier.padding(bottom = AppUiTheme.cardSpacing),
        isActive = isActive
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.loudness_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EquXCheckbox(
                checked = config.isEnabled,
                enabled = isFxEnabled,
                onCheckedChange = { checked ->
                    val strength = if (checked && config.strengthPercent == 0) 50 else config.strengthPercent
                    update(config.copy(isEnabled = checked, strengthPercent = strength))
                }
            )
            IconButton(
                enabled = isModified,
                onClick = { update(original) },
                modifier = Modifier.size(AppUiTheme.iconButtonSize)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.reset_desc),
                    tint = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            }
            StableToggleTextButton(
                isExpanded = expanded,
                onClick = { expanded = !expanded },
                color = if (isActive) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EquXSubSlider(
                    label = stringResource(R.string.loudness_strength),
                    valueText = "${config.strengthPercent}%",
                    value = config.strengthPercent.toFloat(),
                    valueRange = 0f..100f,
                    enabled = isActive,
                    onValueChange = { update(config.copy(strengthPercent = it.roundToInt())) },
                    originalValue = original.strengthPercent.toFloat(),
                    onReset = { update(config.copy(strengthPercent = original.strengthPercent)) },
                )
                EquXSubSlider(
                    label = stringResource(R.string.loudness_threshold),
                    valueText = "${config.thresholdPercent}%",
                    value = config.thresholdPercent.toFloat(),
                    valueRange = 10f..100f,
                    enabled = isActive,
                    onValueChange = { update(config.copy(thresholdPercent = it.roundToInt())) },
                    originalValue = original.thresholdPercent.toFloat(),
                    onReset = { update(config.copy(thresholdPercent = original.thresholdPercent)) },
                )
                if (isActive) {
                    val status = loudnessStatus?.collectAsState()?.value
                    val volume = status?.volume
                    val max = status?.maxVolume
                    val gains = status?.gainsDb.orEmpty()
                    EquXSubCard(isActive = true) {
                        // Две строки: громкость/порог и что сейчас добавлено — раньше всё шло одной
                        // строкой и переносилось где придётся.
                        val lines = if (status == null || volume == null || max == null || gains.size < 10) {
                            listOf(stringResource(R.string.loudness_status_unknown))
                        } else {
                            listOf(
                                stringResource(R.string.loudness_status_volume, volume, max, status.thresholdStep ?: 0),
                                stringResource(R.string.loudness_status_gains, gains[1], gains[9], -status.headroomDb),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            lines.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
