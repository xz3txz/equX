package com.xz3t.equX.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xz3t.equX.R
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.formatFrequencyShort
import com.xz3t.equX.data.parseFrequencyInput
import com.xz3t.equX.ui.components.EquXMainCard
import com.xz3t.equX.ui.components.EquXSlider
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.drawLazyScrollbar
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.responsiveValueTextWidth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    modifier: Modifier = Modifier,
    dataManager: PresetDataManager,
    onInteraction: () -> Boolean = { true },
    onEqStatusChanged: (Boolean) -> Unit = {},
    onBandGainChanged: (Int, Float) -> Unit = { _, _ -> },
    onBandFrequencyChanged: (Int, Float) -> Unit = { _, _ -> }
) {
    val currentPreset = dataManager.liveDraftPreset
    val originalPreset = dataManager.originalPreset

    var isEqEnabled by remember(currentPreset.presetName, currentPreset.equalizer.isEnabled) {
        mutableStateOf(currentPreset.equalizer.isEnabled)
    }
    var isComplexMode by remember(currentPreset.presetName) { mutableStateOf(currentPreset.eqComplexMode) }

    val gains = remember(currentPreset.presetName, currentPreset.equalizer.gainsDb) {
        mutableStateListOf(*currentPreset.equalizer.gainsDb.toTypedArray())
    }
    val frequencies = remember(currentPreset.presetName, currentPreset.equalizer.customFrequenciesHz) {
        mutableStateListOf(*currentPreset.equalizer.customFrequenciesHz.toTypedArray())
    }

    val defaultFrequencies = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    var showFreqDialogIndex by remember { mutableIntStateOf(-1) }
    var freqInputText by remember { mutableStateOf("") }

    var showDbDialogIndex by remember { mutableIntStateOf(-1) }
    var dbInputText by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState()

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(AppUiTheme.cardSpacing),
        modifier = modifier
            .fillMaxSize()
            .drawLazyScrollbar(lazyListState)
            .padding(horizontal = AppUiTheme.cardOuterPaddingHorizontal, vertical = AppUiTheme.cardOuterPaddingVertical)
    ) {
        // --- ВЕРХНЯЯ ПАНЕЛЬ С ВЫКЛЮЧАТЕЛЕМ ЭКВАЛАЙЗЕРА И РЕЖИМОМ COMPLEX ---
        item(key = "header_card") {
            EquXMainCard(
                isActive = true
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Выключатель EQ
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = isEqEnabled,
                            onCheckedChange = { checked ->
                                if (onInteraction()) {
                                    isEqEnabled = checked
                                    val updatedPreset = dataManager.liveDraftPreset.copy(
                                        equalizer = dataManager.liveDraftPreset.equalizer.copy(isEnabled = checked)
                                    )
                                    dataManager.updateLiveDraft(updatedPreset)
                                    onEqStatusChanged(checked)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.equalizer_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(if (isEqEnabled) R.string.enabled else R.string.disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isEqEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Переключатель Simple / Complex временно скрыт из UI.
                    // Complex-режим EQ (кастомные частоты) не показываем пользователю.
                    // Код диалогов ввода частоты/dB оставлен ниже на будущее.
                    /*
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isComplexMode) "Complex" else "Simple",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Switch(
                            checked = isComplexMode,
                            onCheckedChange = { checked ->
                                if (onInteraction()) {
                                    isComplexMode = checked
                                    dataManager.updateLiveDraft(dataManager.liveDraftPreset.copy(eqComplexMode = checked))
                                }
                            }
                        )
                    }
                    */
                }
            }
        }

        // --- ЛЕНИВЫЙ СПИСОК ПОЛОС С КОМПАКТНЫМИ КАРТОЧКАМИ ---
        items(10, key = { it }) { i ->
            val currentGain = if (i < gains.size) gains[i] else 0f
            val origGain = if (i < originalPreset.equalizer.gainsDb.size) originalPreset.equalizer.gainsDb[i] else 0f
            val isModified = isEqEnabled && abs(currentGain - origGain) > 0.05f
            val currentFreq = if (i < frequencies.size) frequencies[i] else defaultFrequencies[i]

            EquXMainCard(isActive = isEqEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Окошко частоты Hz
                    Box(
                        modifier = Modifier.width(if (isComplexMode) 58.dp else 80.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (isComplexMode) {
                            OutlinedButton(
                                onClick = {
                                    if (onInteraction()) {
                                        showFreqDialogIndex = i
                                        freqInputText = formatFrequencyShort(currentFreq)
                                    }
                                },
                                enabled = isEqEnabled,
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text(
                                    text = formatFrequencyShort(currentFreq),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            val displayFreq = defaultFrequencies[i]
                            val freqText = if (displayFreq >= 1000f) "${(displayFreq / 1000f).roundToInt()}k" else "${displayFreq.roundToInt()}"
                            Text(
                                text = "$freqText Hz",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                    // 2. Слайдер усиления с новым регулятором EquXSlider
                    EquXSlider(
                        value = currentGain,
                        enabled = isEqEnabled,
                        onValueChange = { rawValue ->
                            if (onInteraction()) {
                                val dbValue = (Math.round(rawValue * 5f) / 5f)
                                if (i < gains.size) {
                                    gains[i] = dbValue
                                }

                                val updatedGains = dataManager.liveDraftPreset.equalizer.gainsDb.toMutableList()
                                if (i < updatedGains.size) {
                                    updatedGains[i] = dbValue
                                }
                                dataManager.updateLiveDraft(
                                    dataManager.liveDraftPreset.copy(
                                        equalizer = dataManager.liveDraftPreset.equalizer.copy(gainsDb = updatedGains)
                                    )
                                )
                                onBandGainChanged(i, dbValue)
                            }
                        },
                        valueRange = -12f..12f,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))

                    // 3. Окошко децибел dB
                    if (isComplexMode) {
                        OutlinedButton(
                            onClick = {
                                if (onInteraction()) {
                                    showDbDialogIndex = i
                                    dbInputText = String.format(Locale.US, "%.1f", currentGain)
                                }
                            },
                            enabled = isEqEnabled,
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            modifier = Modifier.width(58.dp).height(36.dp)
                        ) {
                            Text(
                                text = String.format(Locale.US, "%+.1f", currentGain),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                color = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Text(
                            text = String.format(locale = Locale.US, "%.1f dB", currentGain),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(responsiveValueTextWidth()),
                            textAlign = TextAlign.End,
                            color = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        enabled = isModified,
                        onClick = {
                            if (onInteraction()) {
                                if (i < gains.size) {
                                    gains[i] = origGain
                                }

                                val updatedGains = dataManager.liveDraftPreset.equalizer.gainsDb.toMutableList()
                                if (i < updatedGains.size) {
                                    updatedGains[i] = origGain
                                }
                                dataManager.updateLiveDraft(
                                    dataManager.liveDraftPreset.copy(
                                        equalizer = dataManager.liveDraftPreset.equalizer.copy(gainsDb = updatedGains)
                                    )
                                )
                                onBandGainChanged(i, origGain)
                            }
                        },
                        modifier = Modifier.size(AppUiTheme.iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.eq_band_reset_desc),
                            tint = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }

    // --- ДИАЛОГ РУЧНОГО ВВОДА ЧАСТОТЫ В ЕДИНОМ СТИЛЕ ---
    if (showFreqDialogIndex in 0..9) {
        val idx = showFreqDialogIndex
        val minFreq = if (idx > 0) frequencies[idx - 1] else 10f
        val maxFreq = if (idx < 9) frequencies[idx + 1] else 22000f

        val parsedFreq = parseFrequencyInput(freqInputText)
        val isValid = parsedFreq != null && parsedFreq > minFreq && parsedFreq < maxFreq

        ScaledAlertDialog(
            onDismissRequest = { showFreqDialogIndex = -1 },
            title = { Text(stringResource(R.string.eq_frequency_dialog_title, idx + 1)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.eq_frequency_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = freqInputText,
                        onValueChange = { freqInputText = it },
                        isError = !isValid && freqInputText.isNotBlank(),
                        label = { Text("Hz") },
                        singleLine = true,
                        supportingText = {
                            Text(
                                text = stringResource(R.string.eq_frequency_dialog_range, formatFrequencyShort(minFreq), formatFrequencyShort(maxFreq)),
                                color = if (!isValid && freqInputText.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = isValid,
                    onClick = {
                        if (isValid && parsedFreq != null) {
                            if (idx < frequencies.size) {
                                frequencies[idx] = parsedFreq
                            }

                            val updatedList = dataManager.liveDraftPreset.equalizer.customFrequenciesHz.toMutableList()
                            if (idx < updatedList.size) {
                                updatedList[idx] = parsedFreq
                            }
                            dataManager.updateLiveDraft(
                                dataManager.liveDraftPreset.copy(
                                    equalizer = dataManager.liveDraftPreset.equalizer.copy(customFrequenciesHz = updatedList)
                                )
                            )
                            onBandFrequencyChanged(idx, parsedFreq)
                            showFreqDialogIndex = -1
                        }
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showFreqDialogIndex = -1 }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // --- ДИАЛОГ РУЧНОГО ВВОДА УСИЛЕНИЯ ДЕЦИБЕЛ (dB) В ЕДИНОМ СТИЛЕ ---
    if (showDbDialogIndex in 0..9) {
        val idx = showDbDialogIndex
        val parsedDb = dbInputText.replace(',', '.').toFloatOrNull()
        val isValidDb = parsedDb != null && parsedDb in -12.0f..12.0f

        ScaledAlertDialog(
            onDismissRequest = { showDbDialogIndex = -1 },
            title = { Text(stringResource(R.string.eq_gain_dialog_title, idx + 1)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.eq_gain_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = dbInputText,
                        onValueChange = { dbInputText = it },
                        isError = !isValidDb && dbInputText.isNotBlank(),
                        label = { Text("dB") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text(
                                text = stringResource(R.string.eq_gain_dialog_range),
                                color = if (!isValidDb && dbInputText.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = isValidDb,
                    onClick = {
                        if (isValidDb && parsedDb != null) {
                            val dbValue = (Math.round(parsedDb.coerceIn(-12f, 12f) * 5f) / 5f)
                            if (idx < gains.size) {
                                gains[idx] = dbValue
                            }

                            val updatedGains = dataManager.liveDraftPreset.equalizer.gainsDb.toMutableList()
                            if (idx < updatedGains.size) {
                                updatedGains[idx] = dbValue
                            }
                            dataManager.updateLiveDraft(
                                dataManager.liveDraftPreset.copy(
                                    equalizer = dataManager.liveDraftPreset.equalizer.copy(gainsDb = updatedGains)
                                )
                            )
                            onBandGainChanged(idx, dbValue)
                            showDbDialogIndex = -1
                        }
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDbDialogIndex = -1 }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}


