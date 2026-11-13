package com.xz3t.equX.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.xz3t.equX.data.BandDynamicParams
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.compressorBandChanged
import com.xz3t.equX.data.deriveCompressorBandParams
import com.xz3t.equX.data.inferCompressorIntensity
import com.xz3t.equX.data.isCompressorBandManual
import com.xz3t.equX.data.snapCompressorBandToSimple
import com.xz3t.equX.ui.components.EquXCheckbox
import com.xz3t.equX.ui.components.EquXMainCard
import com.xz3t.equX.ui.components.EquXSlider
import com.xz3t.equX.ui.components.EquXSubCard
import com.xz3t.equX.ui.components.StableToggleTextButton
import com.xz3t.equX.ui.components.drawColumnScrollbar
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.drawLazyScrollbar
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.responsiveValueTextWidth
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressorScreen(
    modifier: Modifier = Modifier,
    dataManager: PresetDataManager,
    onInteraction: () -> Boolean = { true },
    onCompressorStatusChanged: (Boolean) -> Unit = {},
    onCompressorParamsChanged: (Int, BandDynamicParams) -> Unit = { _, _ -> }
) {
    val currentPreset = dataManager.liveDraftPreset
    val originalPreset = dataManager.originalPreset

    var isCompEnabled by remember(currentPreset.presetName, currentPreset.compressor.isEnabled) {
        mutableStateOf(currentPreset.compressor.isEnabled)
    }
    // Ключ по флагу из черновика: «Сбросить» в панели сохранения возвращает и режим.
    var isComplexMode by remember(currentPreset.presetName, currentPreset.compressorComplexMode) {
        mutableStateOf(currentPreset.compressorComplexMode)
    }

    // Complex→Simple при ручных значениях полос: предупреждаем, что они заменятся формулой.
    var showSimpleSwitchDialog by remember { mutableStateOf(false) }

    // Запасное предупреждение в Simple: полоса не на формуле (пресет из старой версии,
    // где Complex→Simple не выравнивал значения). Признак считается из данных полосы.
    var pendingOverwriteBand by remember { mutableStateOf(-1) }
    var pendingOverwriteValue by remember { mutableStateOf(0f) }
    var showSimpleInfoDialog by remember { mutableStateOf(false) }

    val bandsList = remember(currentPreset.presetName, currentPreset.compressor.bands) {
        mutableStateListOf(*currentPreset.compressor.bands.toTypedArray())
    }

    val defaultFrequencies = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    // Записывает полосу в локальный список экрана, в черновик и сразу в движок.
    fun commitBand(index: Int, band: BandDynamicParams) {
        if (index < bandsList.size) bandsList[index] = band
        val updatedBands = dataManager.liveDraftPreset.compressor.bands.toMutableList()
        if (index < updatedBands.size) updatedBands[index] = band
        dataManager.updateLiveDraft(
            dataManager.liveDraftPreset.copy(
                compressor = dataManager.liveDraftPreset.compressor.copy(bands = updatedBands)
            )
        )
        onCompressorParamsChanged(index, band)
    }
    val expandedBandIndex = remember { mutableStateOf(-1) }

    val lazyListState = rememberLazyListState()

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(AppUiTheme.cardSpacing),
        modifier = modifier
            .fillMaxSize()
            .drawLazyScrollbar(lazyListState)
            .padding(horizontal = AppUiTheme.cardOuterPaddingHorizontal, vertical = AppUiTheme.cardOuterPaddingVertical)
    ) {
        // --- ВЕРХНЯЯ ПАНЕЛЬ С ВЫКЛЮЧАТЕЛЕМ КОМПРЕССОРА И ТУМБЛЕРОМ РЕЖИМА ---
        item(key = "header_card") {
            EquXMainCard(
                isActive = true
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Выключатель Компрессора
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = isCompEnabled,
                            onCheckedChange = { checked ->
                                if (onInteraction()) {
                                    isCompEnabled = checked
                                    val updatedPreset = dataManager.liveDraftPreset.copy(
                                        compressor = dataManager.liveDraftPreset.compressor.copy(isEnabled = checked)
                                    )
                                    dataManager.updateLiveDraft(updatedPreset)
                                    onCompressorStatusChanged(checked)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.compressor_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(if (isCompEnabled) R.string.enabled else R.string.disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCompEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.error
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
                                    contentDescription = stringResource(R.string.compressor_simple_info_desc),
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
                                        // Simple→Complex: значения уже на формуле, ничего не переносим.
                                        isComplexMode = true
                                        dataManager.updateLiveDraft(dataManager.liveDraftPreset.copy(compressorComplexMode = true))
                                    } else if (dataManager.liveDraftPreset.compressor.bands.any(::isCompressorBandManual)) {
                                        showSimpleSwitchDialog = true
                                    } else {
                                        isComplexMode = false
                                        dataManager.updateLiveDraft(dataManager.liveDraftPreset.copy(compressorComplexMode = false))
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // --- ЛЕНИВЫЙ СПИСОК ПОЛОС КОМПРЕССОРА ---
        items(10, key = { it }) { i ->
            val band = bandsList.getOrNull(i) ?: BandDynamicParams(frequencyHz = defaultFrequencies[i])
            val origBand = originalPreset.compressor.bands.getOrNull(i) ?: BandDynamicParams(frequencyHz = defaultFrequencies[i])
            val freqLabel = if (band.frequencyHz >= 1000f) "${(band.frequencyHz / 1000f).toInt()}k Hz" else "${band.frequencyHz.toInt()} Hz"

            if (!isComplexMode) {
                // ==========================================
                // 🟢 РЕЖИМ SIMPLE (Использование EquXMainCard)
                // ==========================================
                val intensityPercent = inferCompressorIntensity(band.frequencyHz, band).toFloat()
                val origIntensity = inferCompressorIntensity(origBand.frequencyHz, origBand).toFloat()

                // Активная полоса показывает реальную интенсивность (в т.ч. 0%). 50% подставляется
                // только выключенной полосе с 0% — как стартовое значение при включении галочки.
                var currentIntensity by remember(currentPreset.presetName, band.frequencyHz) {
                    mutableStateOf(if (band.isPreEqEnabled || intensityPercent > 0f) intensityPercent else 50f)
                }

                // Применяет интенсивность к полосе (перезаписывает все Complex-параметры полосы).
                fun applyBandIntensity(value: Float) {
                    currentIntensity = value
                    val calculated = deriveCompressorBandParams(
                        frequencyHz = band.frequencyHz,
                        intensityPercent = kotlin.math.round(value).toInt(),
                        isPreEqEnabled = band.isPreEqEnabled
                    )

                    commitBand(i, calculated)
                }

                val isBandActive = isCompEnabled && band.isPreEqEnabled
                // Галочка сравнивается всегда (иначе снятую галочку нельзя вернуть сбросом),
                // интенсивность — только у активной полосы, как в hasUnsavedChanges.
                val isModified = isCompEnabled && (
                    band.isPreEqEnabled != origBand.isPreEqEnabled ||
                        (isBandActive && abs(currentIntensity - origIntensity) > 0.5f)
                )

                EquXMainCard(isActive = isBandActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.width(60.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = freqLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                        EquXSlider(
                            value = currentIntensity,
                            enabled = isBandActive,
                            onValueChange = { value ->
                                if (onInteraction()) {
                                    if (isCompressorBandManual(band)) {
                                        pendingOverwriteBand = i
                                        pendingOverwriteValue = value
                                    } else {
                                        applyBandIntensity(value)
                                    }
                                }
                            },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                        Text(
                            text = "${currentIntensity.toInt()}%",
                            color = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(42.dp)
                        )

                        EquXCheckbox(
                            checked = band.isPreEqEnabled,
                            enabled = isCompEnabled,
                            onCheckedChange = { checked ->
                                if (onInteraction()) {
                                    val activeIntensity = if (currentIntensity == 0f) 50f else currentIntensity
                                    if (currentIntensity == 0f) {
                                        currentIntensity = 50f
                                    }
                                    val updated = deriveCompressorBandParams(
                                        frequencyHz = band.frequencyHz,
                                        intensityPercent = kotlin.math.round(activeIntensity).toInt(),
                                        isPreEqEnabled = checked
                                    )

                                    commitBand(i, updated)
                                }
                            }
                        )

                        IconButton(
                            enabled = isModified,
                            onClick = {
                                if (onInteraction()) {
                                    // Как при инициализации: выключенная полоса с 0% показывает стартовые 50%.
                                    currentIntensity = if (origBand.isPreEqEnabled || origIntensity > 0f) origIntensity else 50f
                                    val restoredBand = origBand.copy()
                                    commitBand(i, restoredBand)
                                }
                            },
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

                // Предупреждение о перезаписи Complex-значений этой полосы
                if (pendingOverwriteBand == i) {
                    ScaledAlertDialog(
                        onDismissRequest = { pendingOverwriteBand = -1 },
                        title = { Text(stringResource(R.string.overwrite_complex_title)) },
                        text = { Text(stringResource(R.string.overwrite_complex_band_msg)) },
                        confirmButton = {
                            TextButton(onClick = {
                                applyBandIntensity(pendingOverwriteValue)
                                pendingOverwriteBand = -1
                            }) {
                                Text(stringResource(R.string.overwrite))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingOverwriteBand = -1 }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }
            } else {
                // ==========================================
                // 🛠 РЕЖИМ COMPLEX (Использование EquXMainCard + EquXSubCard)
                // ==========================================
                val isExpanded = expandedBandIndex.value == i
                val isBandActive = isCompEnabled && band.isPreEqEnabled
                val isModified = isCompEnabled && (
                    band.isPreEqEnabled != origBand.isPreEqEnabled ||
                        (isBandActive && compressorBandChanged(origBand, band))
                )

                EquXMainCard(isActive = isBandActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.width(60.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = freqLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        EquXCheckbox(
                            checked = band.isPreEqEnabled,
                            enabled = isCompEnabled,
                            onCheckedChange = { checked ->
                                if (onInteraction()) {
                                    val updated = if (checked && band.thresholdDb == 0.0f && band.ratio == 1.0f) {
                                        // Пустая полоса: стартуем с формулы Simple при 50%, чтобы значения
                                        // лежали на кривой и не считались ручными.
                                        deriveCompressorBandParams(
                                            frequencyHz = band.frequencyHz,
                                            intensityPercent = 50,
                                            isPreEqEnabled = true
                                        )
                                    } else {
                                        band.copy(isPreEqEnabled = checked)
                                    }

                                    commitBand(i, updated)
                                }
                            }
                        )

                        IconButton(
                            enabled = isModified,
                            onClick = {
                                if (onInteraction()) {
                                    val restoredBand = origBand.copy()
                                    commitBand(i, restoredBand)
                                }
                            },
                            modifier = Modifier.size(AppUiTheme.iconButtonSize)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reset_desc),
                                tint = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        }

                        StableToggleTextButton(
                            isExpanded = isExpanded,
                            onClick = { expandedBandIndex.value = if (isExpanded) -1 else i },
                            color = if (isBandActive) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val sliders = listOf(
                                Four(0f, 12f, band.preGainDb, origBand.preGainDb, "Pre Gain") { band.copy(preGainDb = it) },
                                Four(-60f, 0f, band.thresholdDb, origBand.thresholdDb, "Threshold") { band.copy(thresholdDb = it) },
                                Four(1f, 20f, band.ratio, origBand.ratio, "Ratio") { band.copy(ratio = it) },
                                Four(0f, 20f, band.kneeDb, origBand.kneeDb, "KneeWidth") { band.copy(kneeDb = it) },
                                Four(0.1f, 100f, band.attackMs, origBand.attackMs, "Attack Time") { band.copy(attackMs = it) },
                                Four(10f, 1000f, band.releaseMs, origBand.releaseMs, "Release Time") { band.copy(releaseMs = it) },
                                Four(-90f, -30f, band.noiseThresholdDb, origBand.noiseThresholdDb, "Noise Gate") { band.copy(noiseThresholdDb = it) },
                                Four(1f, 10f, band.expanderRatio, origBand.expanderRatio, "Expander") { band.copy(expanderRatio = it) },
                                Four(-12f, 12f, band.postGainDb, origBand.postGainDb, "Post Gain") { band.copy(postGainDb = it) }
                            )

                            sliders.forEach { item ->
                                EquXSubCard(isActive = isBandActive) {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(110.dp),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                                    EquXSlider(
                                        value = item.current,
                                        enabled = isBandActive,
                                        onValueChange = { valNew ->
                                            if (onInteraction()) {
                                                val updated = item.update(valNew)
                                                commitBand(i, updated)
                                            }
                                        },
                                        valueRange = item.min..item.max,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                                    Text(
                                        text = String.format(Locale.US, "%.1f", item.current),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(responsiveValueTextWidth()),
                                        textAlign = TextAlign.End,
                                        color = if (isBandActive && abs(item.current - item.original) > 0.1f) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ПРЕДУПРЕЖДЕНИЕ: COMPLEX→SIMPLE ЗАМЕНИТ РУЧНЫЕ ЗНАЧЕНИЯ ПОЛОС ---
    if (showSimpleSwitchDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showSimpleSwitchDialog = false },
            title = { Text(stringResource(R.string.overwrite_complex_title)) },
            text = { Text(stringResource(R.string.overwrite_complex_compressor_switch_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showSimpleSwitchDialog = false
                    // Приводим к формуле только ручные полосы, остальные и так на ней.
                    val bands = dataManager.liveDraftPreset.compressor.bands
                    val snapped = bands.map { if (isCompressorBandManual(it)) snapCompressorBandToSimple(it) else it }
                    dataManager.updateLiveDraft(
                        dataManager.liveDraftPreset.copy(
                            compressorComplexMode = false,
                            compressor = dataManager.liveDraftPreset.compressor.copy(bands = snapped)
                        )
                    )
                    isComplexMode = false
                    snapped.forEachIndexed { i, band ->
                        if (band != bands[i]) onCompressorParamsChanged(i, band)
                    }
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

    // --- СПРАВКА О SIMPLE-РЕЖИМЕ КОМПРЕССОРА ---
    if (showSimpleInfoDialog) {
        val infoScrollState = rememberScrollState()
        ScaledAlertDialog(
            onDismissRequest = { showSimpleInfoDialog = false },
            title = { Text(stringResource(R.string.compressor_simple_info_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .drawColumnScrollbar(infoScrollState)
                        .padding(start = 12.dp)
                        .heightIn(max = 400.dp)
                        .verticalScroll(infoScrollState)
                ) {
                    Text(
                        text = stringResource(R.string.compressor_info_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.compressor_info_band_ranges), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow("LOW", "31, 62, 125, 250 Hz")
                    InfoRow("MID", "500, 1000, 2000, 4000 Hz")
                    InfoRow("HIGH", "8000, 16000 Hz")

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.compressor_info_curves), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))

                    // Заголовки столбцов
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.parameter),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1.3f)
                        )
                        Text(
                            text = "LOW",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "MID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "HIGH",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    CurveRow("Threshold", "-18 dB", "-20 dB", "-22 dB")
                    CurveRow("Ratio", "4:1", "5:1", "5:1")
                    CurveRow("Attack", "30→10 ms", "15→5 ms", "5→1 ms")
                    CurveRow("Release", "200→120 ms", "150→90 ms", "100→60 ms")

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.compressor_info_character), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.compressor_info_character_text),
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppUiTheme.accentColor()
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CurveRow(param: String, low: String, mid: String, high: String) {
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
            text = mid,
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

private data class Four<T>(
    val min: Float,
    val max: Float,
    val current: Float,
    val original: Float,
    val label: String,
    val update: (Float) -> T
)
