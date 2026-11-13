package com.xz3t.equX.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.unit.dp
import com.xz3t.equX.R
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.deriveLimiterParams
import com.xz3t.equX.data.limiterMatchesIntensity
import com.xz3t.equX.data.snapLimiterToSimple
import com.xz3t.equX.ui.components.EquXMainCard
import com.xz3t.equX.ui.components.EquXSlider
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.drawColumnScrollbar
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.responsiveLabelBoxWidth
import com.xz3t.equX.ui.theme.responsiveValueTextWidth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimiterScreen(
    modifier: Modifier = Modifier,
    dataManager: PresetDataManager,
    onInteraction: () -> Boolean = { true },
    onLimiterStatusChanged: (Boolean) -> Unit = {},
    onLimiterThresholdChanged: (Float) -> Unit = {},
    onLimiterPostGainChanged: (Float) -> Unit = {},
    onLimiterReleaseChanged: (Float) -> Unit = {},
    onLimiterAttackChanged: (Float) -> Unit = {},
    onLimiterRatioChanged: (Float) -> Unit = {},
    onLimiterIntensityChanged: (Int) -> Unit = {},
    onLimiterModeChanged: (Boolean) -> Unit = {}
) {
    val currentPreset = dataManager.liveDraftPreset
    val originalPreset = dataManager.originalPreset

    var isLimiterEnabled by remember(currentPreset.presetName, currentPreset.limiter.isEnabled) {
        mutableStateOf(currentPreset.limiter.isEnabled)
    }
    // Ключ по флагу из черновика: «Сбросить» в панели сохранения возвращает и режим.
    var isComplexMode by remember(currentPreset.presetName, currentPreset.limiterComplexMode) {
        mutableStateOf(currentPreset.limiterComplexMode)
    }

    var thresholdDb by remember(currentPreset.presetName, currentPreset.limiter.thresholdDb) {
        mutableStateOf(currentPreset.limiter.thresholdDb)
    }
    var postGainDb by remember(currentPreset.presetName, currentPreset.limiter.postGainDb) {
        mutableStateOf(currentPreset.limiter.postGainDb)
    }
    var releaseMs by remember(currentPreset.presetName, currentPreset.limiter.releaseMs) {
        mutableStateOf(currentPreset.limiter.releaseMs)
    }
    var attackMs by remember(currentPreset.presetName, currentPreset.limiter.attackMs) {
        mutableStateOf(currentPreset.limiter.attackMs)
    }
    var ratio by remember(currentPreset.presetName, currentPreset.limiter.ratio) {
        mutableStateOf(currentPreset.limiter.ratio)
    }
    var intensity by remember(currentPreset.presetName, currentPreset.limiter.simpleIntensityPercent) {
        mutableStateOf(currentPreset.limiter.simpleIntensityPercent)
    }

    // Complex→Simple при ручных Complex-значениях: предупреждаем, что они заменятся формулой.
    var showSimpleSwitchDialog by remember { mutableStateOf(false) }
    var showSimpleInfoDialog by remember { mutableStateOf(false) }

    val origThreshold = originalPreset.limiter.thresholdDb
    val isThreshModified = isLimiterEnabled && abs(thresholdDb - origThreshold) > 0.1f

    val origPostGain = originalPreset.limiter.postGainDb
    val isPostGainModified = isLimiterEnabled && abs(postGainDb - origPostGain) > 0.1f

    val origRelease = originalPreset.limiter.releaseMs
    val isReleaseModified = isLimiterEnabled && abs(releaseMs - origRelease) > 1.0f

    val origAttack = originalPreset.limiter.attackMs
    val isAttackModified = isLimiterEnabled && abs(attackMs - origAttack) > 0.1f

    val origRatio = originalPreset.limiter.ratio
    val isRatioModified = isLimiterEnabled && abs(ratio - origRatio) > 0.1f

    val origIntensity = originalPreset.limiter.simpleIntensityPercent
    val isIntensityModified = isLimiterEnabled && intensity != origIntensity

    // Применяет интенсивность: раскладывает в реальные параметры и обновляет модель + DSP.
    fun applyIntensity(value: Int) {
        val p = deriveLimiterParams(value)
        intensity = value
        thresholdDb = p.thresholdDb
        attackMs = p.attackMs
        ratio = p.ratio
        releaseMs = p.releaseMs

        val updated = dataManager.liveDraftPreset.copy(
            limiter = dataManager.liveDraftPreset.limiter.copy(
                simpleIntensityPercent = value,
                thresholdDb = p.thresholdDb,
                attackMs = p.attackMs,
                ratio = p.ratio,
                releaseMs = p.releaseMs
            )
        )
        dataManager.updateLiveDraft(updated)
        onLimiterIntensityChanged(value)
    }

    // В Simple Complex-поля всегда лежат на формуле (см. switchToSimple), поэтому
    // интенсивность меняется без предупреждений.
    fun requestIntensityChange(value: Int) {
        applyIntensity(value)
    }

    // Complex→Simple: поля приводятся к формуле Simple (интенсивность — по threshold,
    // если поля уже не на формуле сохранённой интенсивности).
    fun switchToSimple() {
        val snapped = snapLimiterToSimple(dataManager.liveDraftPreset.limiter)
        intensity = snapped.simpleIntensityPercent
        thresholdDb = snapped.thresholdDb
        attackMs = snapped.attackMs
        ratio = snapped.ratio
        releaseMs = snapped.releaseMs
        dataManager.updateLiveDraft(
            dataManager.liveDraftPreset.copy(limiterComplexMode = false, limiter = snapped)
        )
        isComplexMode = false
        onLimiterModeChanged(false)
    }

    // Обновляет параметры лимитера в черновике (движок обновляет вызывающий колбэк).
    fun updateLimiter(transform: (com.xz3t.equX.data.LimiterConfig) -> com.xz3t.equX.data.LimiterConfig) {
        dataManager.updateLiveDraft(
            dataManager.liveDraftPreset.copy(limiter = transform(dataManager.liveDraftPreset.limiter))
        )
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawColumnScrollbar(scrollState)
            .padding(horizontal = AppUiTheme.cardOuterPaddingHorizontal, vertical = AppUiTheme.cardOuterPaddingVertical)
            .verticalScroll(scrollState)
    ) {
        // --- ВЕРХНЯЯ ПАНЕЛЬ С ВЫКЛЮЧАТЕЛЕМ ЛИМИТЕРА И ТУМБЛЕРОМ РЕЖИМА ---
        EquXMainCard(
            modifier = Modifier.padding(bottom = AppUiTheme.cardSpacing),
            isActive = true
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Выключатель Лимитера
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isLimiterEnabled,
                        onCheckedChange = { checked ->
                            if (onInteraction()) {
                                isLimiterEnabled = checked
                                updateLimiter { it.copy(isEnabled = checked) }
                                onLimiterStatusChanged(checked)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.limiter_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(if (isLimiterEnabled) R.string.enabled else R.string.disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLimiterEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Переключатель Simple / Complex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isComplexMode) {
                        IconButton(
                            onClick = { showSimpleInfoDialog = true },
                            modifier = Modifier.size(AppUiTheme.iconButtonSize)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.limiter_simple_info_desc),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Text(
                        text = if (isComplexMode) "Complex" else "Simple",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Switch(
                        checked = isComplexMode,
                        onCheckedChange = { checked ->
                            if (onInteraction()) {
                                if (checked) {
                                    // Simple -> Complex: переносим значения из интенсивности (страховка, обычно совпадают).
                                    val p = deriveLimiterParams(intensity)
                                    thresholdDb = p.thresholdDb
                                    attackMs = p.attackMs
                                    ratio = p.ratio
                                    releaseMs = p.releaseMs

                                    dataManager.updateLiveDraft(
                                        dataManager.liveDraftPreset.copy(
                                            limiterComplexMode = true,
                                            limiter = dataManager.liveDraftPreset.limiter.copy(
                                                simpleIntensityPercent = intensity,
                                                thresholdDb = p.thresholdDb,
                                                attackMs = p.attackMs,
                                                ratio = p.ratio,
                                                releaseMs = p.releaseMs
                                            )
                                        )
                                    )
                                    // В Simple поля уже на формуле, поэтому перенос значений не меняет
                                    // и индикатор черновика не зажигает.
                                    isComplexMode = true
                                    // Применяем пресет ОДНИМ вызовом в фоне, а не 4 тяжёлыми DSP-вызовами подряд.
                                    onLimiterModeChanged(true)
                                } else if (limiterMatchesIntensity(dataManager.liveDraftPreset.limiter, intensity)) {
                                    // Complex не правили — значения уже на формуле.
                                    switchToSimple()
                                } else {
                                    showSimpleSwitchDialog = true
                                }
                            }
                        }
                    )
                }
            }
        }

        // Настройки доступны только если лимитер включен
        AnimatedVisibility(visible = isLimiterEnabled) {
            Column(
                verticalArrangement = Arrangement.spacedBy(AppUiTheme.cardSpacing),
                modifier = Modifier.animateContentSize()
            ) {
                // ==========================================
                // 🔊 OUT GAIN (всегда, первым)
                // ==========================================
                EquXMainCard(isActive = isLimiterEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Out Gain",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(responsiveLabelBoxWidth()),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                        EquXSlider(
                            value = postGainDb,
                            enabled = isLimiterEnabled,
                            onValueChange = { rawValue ->
                                if (onInteraction()) {
                                    val stepValue = (Math.round(rawValue * 5f) / 5f)
                                    postGainDb = stepValue

                                    updateLimiter { it.copy(postGainDb = stepValue) }
                                    onLimiterPostGainChanged(stepValue)
                                }
                            },
                            valueRange = -12f..6f,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                        Text(
                            text = String.format(Locale.US, "%.1f dB", postGainDb),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(responsiveValueTextWidth()),
                            textAlign = TextAlign.End,
                            color = if (isPostGainModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                        )

                        IconButton(
                            enabled = isPostGainModified,
                            onClick = {
                                if (onInteraction()) {
                                    postGainDb = origPostGain

                                    updateLimiter { it.copy(postGainDb = origPostGain) }
                                    onLimiterPostGainChanged(origPostGain)
                                }
                            },
                            modifier = Modifier.size(AppUiTheme.iconButtonSize)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reset_desc),
                                tint = if (isPostGainModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                // ==========================================
                // 🟢 SIMPLE: Интенсивность
                // ==========================================
                if (!isComplexMode) {
                    EquXMainCard(isActive = isLimiterEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.intensity),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            EquXSlider(
                                value = intensity.toFloat(),
                                enabled = isLimiterEnabled,
                                onValueChange = { value ->
                                    if (onInteraction()) {
                                        requestIntensityChange(value.roundToInt())
                                    }
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            Text(
                                text = "$intensity%",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveValueTextWidth()),
                                textAlign = TextAlign.End,
                                color = if (isIntensityModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                enabled = isIntensityModified,
                                onClick = {
                                    if (onInteraction()) {
                                        requestIntensityChange(origIntensity)
                                    }
                                },
                                modifier = Modifier.size(AppUiTheme.iconButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.reset_intensity_desc),
                                    tint = if (isIntensityModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // 🛠 COMPLEX: Threshold
                // ==========================================
                if (isComplexMode) {
                    EquXMainCard(isActive = isLimiterEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Threshold",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            EquXSlider(
                                value = thresholdDb,
                                enabled = isLimiterEnabled,
                                onValueChange = { rawValue ->
                                    if (onInteraction()) {
                                        val stepValue = (Math.round(rawValue * 5f) / 5f)
                                        thresholdDb = stepValue

                                        updateLimiter { it.copy(thresholdDb = stepValue) }
                                        onLimiterThresholdChanged(stepValue)
                                    }
                                },
                                valueRange = -20f..0f,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            Text(
                                text = String.format(Locale.US, "%.1f dB", thresholdDb),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveValueTextWidth()),
                                textAlign = TextAlign.End,
                                color = if (isThreshModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                enabled = isThreshModified,
                                onClick = {
                                    if (onInteraction()) {
                                        thresholdDb = origThreshold

                                        updateLimiter { it.copy(thresholdDb = origThreshold) }
                                        onLimiterThresholdChanged(origThreshold)
                                    }
                                },
                                modifier = Modifier.size(AppUiTheme.iconButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.reset_desc),
                                    tint = if (isThreshModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // 🛠 COMPLEX: Attack Time
                // ==========================================
                if (isComplexMode) {
                    EquXMainCard(isActive = isLimiterEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Attack",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            EquXSlider(
                                value = attackMs,
                                enabled = isLimiterEnabled,
                                onValueChange = { value ->
                                    if (onInteraction()) {
                                        attackMs = value

                                        updateLimiter { it.copy(attackMs = value) }
                                        onLimiterAttackChanged(value)
                                    }
                                },
                                valueRange = 0.1f..30f,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            Text(
                                text = String.format(Locale.US, "%.1f ms", attackMs),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveValueTextWidth()),
                                textAlign = TextAlign.End,
                                color = if (isAttackModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                enabled = isAttackModified,
                                onClick = {
                                    if (onInteraction()) {
                                        attackMs = origAttack

                                        updateLimiter { it.copy(attackMs = origAttack) }
                                        onLimiterAttackChanged(origAttack)
                                    }
                                },
                                modifier = Modifier.size(AppUiTheme.iconButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.reset_desc),
                                    tint = if (isAttackModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // 🛠 COMPLEX: Ratio
                // ==========================================
                AnimatedVisibility(visible = isComplexMode) {
                    EquXMainCard(isActive = isLimiterEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Ratio",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            EquXSlider(
                                value = ratio,
                                enabled = isLimiterEnabled,
                                onValueChange = { value ->
                                    if (onInteraction()) {
                                        ratio = value

                                        updateLimiter { it.copy(ratio = value) }
                                        onLimiterRatioChanged(value)
                                    }
                                },
                                valueRange = 2f..20f,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            Text(
                                text = String.format(Locale.US, "%.1f:1", ratio),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveValueTextWidth()),
                                textAlign = TextAlign.End,
                                color = if (isRatioModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                enabled = isRatioModified,
                                onClick = {
                                    if (onInteraction()) {
                                        ratio = origRatio

                                        updateLimiter { it.copy(ratio = origRatio) }
                                        onLimiterRatioChanged(origRatio)
                                    }
                                },
                                modifier = Modifier.size(AppUiTheme.iconButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.reset_desc),
                                    tint = if (isRatioModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // 🛠 COMPLEX: Release Time
                // ==========================================
                AnimatedVisibility(visible = isComplexMode) {
                    EquXMainCard(isActive = isLimiterEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Release",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            EquXSlider(
                                value = releaseMs,
                                enabled = isLimiterEnabled,
                                onValueChange = { value ->
                                    if (onInteraction()) {
                                        releaseMs = value

                                        updateLimiter { it.copy(releaseMs = value) }
                                        onLimiterReleaseChanged(value)
                                    }
                                },
                                valueRange = 10f..500f,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                            Text(
                                text = "${releaseMs.roundToInt()} ms",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(responsiveValueTextWidth()),
                                textAlign = TextAlign.End,
                                color = if (isReleaseModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                enabled = isReleaseModified,
                                onClick = {
                                    if (onInteraction()) {
                                        releaseMs = origRelease

                                        updateLimiter { it.copy(releaseMs = origRelease) }
                                        onLimiterReleaseChanged(origRelease)
                                    }
                                },
                                modifier = Modifier.size(AppUiTheme.iconButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.reset_desc),
                                    tint = if (isReleaseModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ПРЕДУПРЕЖДЕНИЕ: COMPLEX→SIMPLE ЗАМЕНИТ РУЧНЫЕ ЗНАЧЕНИЯ ---
    if (showSimpleSwitchDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showSimpleSwitchDialog = false },
            title = { Text(stringResource(R.string.overwrite_complex_title)) },
            text = { Text(stringResource(R.string.overwrite_complex_limiter_switch_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showSimpleSwitchDialog = false
                    switchToSimple()
                }) {
                    Text(stringResource(R.string.overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSimpleSwitchDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // --- СПРАВКА О SIMPLE-РЕЖИМЕ ЛИМИТЕРА ---
    if (showSimpleInfoDialog) {
        val infoScrollState = rememberScrollState()
        ScaledAlertDialog(
            onDismissRequest = { showSimpleInfoDialog = false },
            title = { Text(stringResource(R.string.limiter_simple_info_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .drawColumnScrollbar(infoScrollState)
                        .padding(start = 12.dp)
                        .heightIn(max = 400.dp)
                        .verticalScroll(infoScrollState)
                ) {
                    Text(
                        text = stringResource(R.string.limiter_info_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.limiter_info_values), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.parameter),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1.3f)
                        )
                        Text(
                            text = "0%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "100%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    LimiterInfoRow("Threshold", "-1 dB", "-20 dB")
                    LimiterInfoRow("Ratio", "2:1", "20:1")
                    LimiterInfoRow("Attack", "30 ms", "1 ms")
                    LimiterInfoRow("Release", "250 ms", "60 ms")

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.limiter_info_extra_text),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSimpleInfoDialog = false }) {
                    Text(stringResource(R.string.got_it))
                }
            }
        )
    }
}

@Composable
private fun LimiterInfoRow(param: String, low: String, high: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = param,
            style = MaterialTheme.typography.bodySmall,
            color = AppUiTheme.accentColor(),
            modifier = Modifier.weight(1.3f)
        )
        Text(
            text = low,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = high,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
    }
}
