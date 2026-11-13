package com.xz3t.equX.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xz3t.equX.R
import com.xz3t.equX.data.CarConfig
import com.xz3t.equX.data.DebugFlags
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.SPEED_EQ_FREQUENCIES_HZ
import com.xz3t.equX.data.SpeedEqBand
import com.xz3t.equX.data.SpeedSource
import com.xz3t.equX.data.SpeedSourceInfo
import com.xz3t.equX.data.SpeedSourceStatus
import com.xz3t.equX.data.VolumeMethod
import com.xz3t.equX.data.VolumeMethodStatus
import androidx.core.content.ContextCompat
import com.xz3t.equX.ui.components.EquXCheckbox
import com.xz3t.equX.ui.components.EquXMainCard
import com.xz3t.equX.ui.components.EquXSlider
import com.xz3t.equX.ui.components.EquXSubCard
import com.xz3t.equX.ui.components.EquXSubSlider
import com.xz3t.equX.ui.components.StableToggleTextButton
import com.xz3t.equX.ui.components.drawColumnScrollbar
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.drawLazyScrollbar
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.ProvideScaledDensity
import com.xz3t.equX.ui.theme.responsiveLabelBoxWidth
import com.xz3t.equX.ui.theme.responsiveStatsValueWidth
import com.xz3t.equX.ui.theme.responsiveValueTextWidth
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarScreen(
    modifier: Modifier = Modifier,
    dataManager: PresetDataManager,
    realSpeedKmh: Float? = null,
    speedMaxKmh: Float? = null,
    method2Status: VolumeMethodStatus = VolumeMethodStatus.UNTESTED,
    method3Status: VolumeMethodStatus = VolumeMethodStatus.UNTESTED,
    onCarChanged: (CarConfig) -> Unit = {},
    onMockSpeedChanged: (Float) -> Unit = {},
    onReProbeVolumeMethods: () -> Unit = {},
    // Уровень штатной регулировки громкости по скорости ГУ (ECARX); 0 — выкл, null — нет такой
    // или вкл/выкл не читается.
    checkHuSpeedVolume: () -> Int? = { null },
    speedSourceInfos: Map<SpeedSource, SpeedSourceInfo> = emptyMap(),
) {
    val car = dataManager.liveDraftPreset.car
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Источник скорости и метод громкости — глобальные настройки, не в пресете.
    val speedSource by dataManager.speedSourceFlow.collectAsState(initial = SpeedSource.AUTO)
    val volumeMethod by dataManager.volumeMethodFlow.collectAsState(initial = VolumeMethod.AUTO)
    var showGpsBackgroundDialog by remember { mutableStateOf(false) }
    var showHuSpeedVolumeDialog by remember { mutableStateOf(false) }

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Фоновая геолокация: без неё служба, запущенная по BOOT/ACC без открытия приложения,
    // GPS не получит (Android 11+). На Android 11+ запрос открывает системный экран разрешения.
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            showGpsBackgroundDialog = true
        }
    }

    fun selectSpeedSource(source: SpeedSource) {
        coroutineScope.launch { dataManager.setSpeedSource(source) }
        if (source != SpeedSource.GPS) return
        // Разрешения просим сразу при выборе GPS: точное, затем фоновое.
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            showGpsBackgroundDialog = true
        }
    }
    val originalCar = dataManager.originalPreset.car
    var mockSpeedEnabled by remember { mutableStateOf(DebugFlags.mockSpeedEnabled) }
    var mockSpeedValue by remember { mutableFloatStateOf(DebugFlags.mockSpeedValue) }

    var speedExpanded by remember { mutableStateOf(false) }
    var volumeExpanded by remember { mutableStateOf(false) }
    var eqExpanded by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    fun updateCar(transform: (CarConfig) -> CarConfig) {
        val updated = dataManager.liveDraftPreset.copy(
            rawCar = transform(dataManager.liveDraftPreset.car)
        )
        dataManager.updateLiveDraft(updated)
        onCarChanged(updated.car)
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(AppUiTheme.cardSpacing),
        modifier = modifier
            .fillMaxSize()
            .drawLazyScrollbar(lazyListState)
            .padding(horizontal = AppUiTheme.cardOuterPaddingHorizontal, vertical = AppUiTheme.cardOuterPaddingVertical)
    ) {
        // --- ВЕРХНЯЯ ПАНЕЛЬ С МАСТЕР-ВЫКЛЮЧАТЕЛЕМ CAR AUDIO ---
        item(key = "car_header") {
            EquXMainCard(isActive = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = car.isEnabled,
                        onCheckedChange = { checked ->
                            updateCar { it.copy(isEnabled = checked) }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.car_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(if (car.isEnabled) R.string.enabled else R.string.disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (car.isEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.size(AppUiTheme.iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.car_info_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // --- СКОРОСТЬ (общие пороги для громкости и скоростного EQ) ---
        item(key = "car_speed") {
            EquXMainCard(isActive = car.isEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.car_speed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        enabled = car.minSpeed != originalCar.minSpeed || car.maxSpeed != originalCar.maxSpeed,
                        onClick = {
                            updateCar {
                                it.copy(minSpeed = originalCar.minSpeed, maxSpeed = originalCar.maxSpeed)
                            }
                        },
                        modifier = Modifier.size(AppUiTheme.iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.reset_desc),
                            tint = if (car.minSpeed != originalCar.minSpeed || car.maxSpeed != originalCar.maxSpeed) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                    StableToggleTextButton(
                        isExpanded = speedExpanded,
                        onClick = { speedExpanded = !speedExpanded },
                        color = if (car.isEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                    )
                }

                AnimatedVisibility(visible = speedExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Источник скорости (глобальная настройка). В списке — индикатор и
                        // максимальная скорость каждого источника за поездку: мёртвый остаётся на 0.
                        EquXSubCard(isActive = car.isEnabled) {
                            Text(
                                text = stringResource(R.string.car_speed_source),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                            SpeedSourceSelector(
                                selected = speedSource,
                                infos = speedSourceInfos,
                                enabled = car.isEnabled,
                                modifier = Modifier.weight(1f),
                                onSelect = ::selectSpeedSource,
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.iconButtonSize))
                        }

                        // Статистика скорости: текущая и максимальная за сессию.
                        EquXSubCard(isActive = car.isEnabled) {
                            Text(
                                text = stringResource(R.string.car_speed_stats),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                            // Плейсхолдер под слайдер: выравнивает значение по той же
                            // вертикали, что и в остальных полях-слайдерах.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                            Text(
                                text = if (realSpeedKmh != null && speedMaxKmh != null) {
                                    stringResource(
                                        R.string.car_speed_stats_value,
                                        realSpeedKmh.roundToInt(),
                                        speedMaxKmh.roundToInt(),
                                    )
                                } else {
                                    stringResource(R.string.car_real_speed_unknown)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(responsiveStatsValueWidth()),
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.iconButtonSize))
                        }

                        // 2. Имитация скорости (отладочный параметр).
                        EquXSubCard(isActive = car.isEnabled) {
                            Text(
                                text = stringResource(R.string.car_mock_speed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                            EquXSlider(
                                value = mockSpeedValue,
                                enabled = car.isEnabled && mockSpeedEnabled,
                                onValueChange = { v ->
                                    mockSpeedValue = v
                                    DebugFlags.mockSpeedValue = v
                                    onMockSpeedChanged(v)
                                },
                                valueRange = 0f..200f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                            Text(
                                text = stringResource(R.string.car_mock_speed_value, mockSpeedValue.roundToInt()),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(responsiveValueTextWidth()),
                                maxLines = 1,
                                color = if (mockSpeedEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                            EquXCheckbox(
                                checked = mockSpeedEnabled,
                                enabled = car.isEnabled,
                                onCheckedChange = { checked ->
                                    mockSpeedEnabled = checked
                                    DebugFlags.mockSpeedEnabled = checked
                                    if (!checked) {
                                        onMockSpeedChanged(0f)
                                    } else {
                                        onMockSpeedChanged(mockSpeedValue)
                                    }
                                }
                            )
                        }

                        EquXSubSlider(
                            label = stringResource(R.string.gala_min_speed),
                            valueText = stringResource(R.string.gala_speed_value, car.minSpeed),
                            value = car.minSpeed.toFloat(),
                            valueRange = 10f..200f,
                            enabled = car.isEnabled,
                            onValueChange = { v ->
                                val value = v.roundToInt().coerceAtMost(car.maxSpeed - 10)
                                updateCar { it.copy(minSpeed = value) }
                            },
                            originalValue = originalCar.minSpeed.toFloat(),
                            onReset = {
                                updateCar { it.copy(minSpeed = originalCar.minSpeed) }
                            }
                        )
                        EquXSubSlider(
                            label = stringResource(R.string.gala_max_speed),
                            valueText = stringResource(R.string.gala_speed_value, car.maxSpeed),
                            value = car.maxSpeed.toFloat(),
                            valueRange = 20f..250f,
                            enabled = car.isEnabled,
                            onValueChange = { v ->
                                val value = v.roundToInt().coerceAtLeast(car.minSpeed + 10)
                                updateCar { it.copy(maxSpeed = value) }
                            },
                            originalValue = originalCar.maxSpeed.toFloat(),
                            onReset = {
                                updateCar { it.copy(maxSpeed = originalCar.maxSpeed) }
                            }
                        )
                    }
                }
            }
        }

        // --- ГРОМКОСТЬ (GALA) ---
        item(key = "car_volume") {
            EquXMainCard(isActive = car.isEnabled && car.volumeEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.car_volume),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EquXCheckbox(
                        checked = car.volumeEnabled,
                        enabled = car.isEnabled,
                        onCheckedChange = { checked ->
                            updateCar { it.copy(volumeEnabled = checked) }
                            // При каждом включении GALA: если в ГУ включена штатная регулировка
                            // громкости по скорости — предупреждаем о двойной прибавке.
                            if (checked) {
                                coroutineScope.launch {
                                    val level = withContext(Dispatchers.IO) { checkHuSpeedVolume() }
                                    if (level != null && level != 0) showHuSpeedVolumeDialog = true
                                }
                            }
                        }
                    )
                    val volumeDirty = car.volumeEnabled != originalCar.volumeEnabled ||
                        car.maxBoostSteps != originalCar.maxBoostSteps
                    IconButton(
                        enabled = volumeDirty,
                        onClick = {
                            updateCar {
                                it.copy(
                                    volumeEnabled = originalCar.volumeEnabled,
                                    maxBoostSteps = originalCar.maxBoostSteps,
                                )
                            }
                        },
                        modifier = Modifier.size(AppUiTheme.iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.reset_desc),
                            tint = if (volumeDirty) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                    StableToggleTextButton(
                        isExpanded = volumeExpanded,
                        onClick = { volumeExpanded = !volumeExpanded },
                        color = if (car.isEnabled && car.volumeEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                    )
                }

                AnimatedVisibility(visible = volumeExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EquXSubCard(isActive = car.isEnabled && car.volumeEnabled) {
                            Text(
                                text = stringResource(R.string.car_volume_method),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(responsiveLabelBoxWidth()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                            VolumeMethodSelector(
                                selected = volumeMethod,
                                method2Status = method2Status,
                                method3Status = method3Status,
                                enabled = car.isEnabled && car.volumeEnabled,
                                modifier = Modifier.weight(1f),
                                onSelect = { method -> coroutineScope.launch { dataManager.setVolumeMethod(method) } },
                                onOpenDropdown = onReProbeVolumeMethods,
                            )
                            Spacer(modifier = Modifier.width(AppUiTheme.iconButtonSize))
                        }
                        EquXSubSlider(
                            label = stringResource(R.string.gala_max_boost),
                            valueText = stringResource(R.string.gala_boost_value, car.maxBoostSteps),
                            value = car.maxBoostSteps.toFloat(),
                            valueRange = 0f..39f,
                            enabled = car.isEnabled && car.volumeEnabled,
                            onValueChange = { v ->
                                updateCar { it.copy(maxBoostSteps = v.roundToInt()) }
                            },
                            originalValue = originalCar.maxBoostSteps.toFloat(),
                            onReset = {
                                updateCar { it.copy(maxBoostSteps = originalCar.maxBoostSteps) }
                            }
                        )
                    }
                }
            }
        }

        // --- СКОРОСТНОЙ ЭКВАЛАЙЗЕР ---
        item(key = "car_eq") {
            EquXMainCard(isActive = car.isEnabled && car.eqEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.car_eq),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EquXCheckbox(
                        checked = car.eqEnabled,
                        enabled = car.isEnabled,
                        onCheckedChange = { checked ->
                            updateCar { it.copy(eqEnabled = checked) }
                        }
                    )
                    val eqDirty = car.eqEnabled != originalCar.eqEnabled ||
                        car.bands != originalCar.bands
                    IconButton(
                        enabled = eqDirty,
                        onClick = {
                            updateCar {
                                it.copy(eqEnabled = originalCar.eqEnabled, bands = originalCar.bands)
                            }
                        },
                        modifier = Modifier.size(AppUiTheme.iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.reset_desc),
                            tint = if (eqDirty) AppUiTheme.accentColor() else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                    StableToggleTextButton(
                        isExpanded = eqExpanded,
                        onClick = { eqExpanded = !eqExpanded },
                        color = if (car.isEnabled && car.eqEnabled) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                    )
                }

                AnimatedVisibility(visible = eqExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SPEED_EQ_FREQUENCIES_HZ.forEachIndexed { i, freq ->
                            val band = car.bands.getOrNull(i) ?: SpeedEqBand(frequencyHz = freq)
                            val origBand = originalCar.bands.getOrNull(i) ?: SpeedEqBand(frequencyHz = freq)
                            val active = car.isEnabled && car.eqEnabled && band.isEnabled
                            // Подсветка и сброс — от сохранённого пресета (как в остальных экранах и
                            // в hasUnsavedChanges): галочка и усиление, с тем же допуском 0.05 dB.
                            val isModified = car.isEnabled && car.eqEnabled && (
                                band.isEnabled != origBand.isEnabled ||
                                    kotlin.math.abs(band.gainDb - origBand.gainDb) > 0.05f
                            )
                            val freqLabel = if (freq >= 1000f) "${(freq / 1000f).toInt()}k Hz" else "${freq.toInt()} Hz"

                            EquXSubCard(isActive = active) {
                                Text(
                                    text = freqLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.width(60.dp)
                                )
                                Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                                EquXSlider(
                                    value = band.gainDb,
                                    enabled = active,
                                    onValueChange = { v ->
                                        val newBands = car.bands.toMutableList()
                                        // Шаг 0.2 dB, как в основном эквалайзере.
                                        newBands[i] = band.copy(gainDb = Math.round(v * 5f) / 5f)
                                        updateCar { it.copy(bands = newBands) }
                                    },
                                    valueRange = -12f..12f,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                                Text(
                                    text = stringResource(R.string.car_gain_value, band.gainDb),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(responsiveValueTextWidth()),
                                    color = if (isModified) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
                                EquXCheckbox(
                                    checked = band.isEnabled,
                                    enabled = car.isEnabled && car.eqEnabled,
                                    onCheckedChange = { checked ->
                                        val newBands = car.bands.toMutableList()
                                        newBands[i] = band.copy(isEnabled = checked)
                                        updateCar { it.copy(bands = newBands) }
                                    }
                                )
                                IconButton(
                                    enabled = isModified,
                                    onClick = {
                                        val newBands = car.bands.toMutableList()
                                        newBands[i] = origBand
                                        updateCar { it.copy(bands = newBands) }
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
                    }
                }
            }
        }
    }

    // --- ФОНОВАЯ ГЕОЛОКАЦИЯ ДЛЯ GPS ---
    if (showGpsBackgroundDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showGpsBackgroundDialog = false },
            title = { Text(stringResource(R.string.car_gps_background_title)) },
            text = { Text(stringResource(R.string.car_gps_background_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showGpsBackgroundDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showGpsBackgroundDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showHuSpeedVolumeDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showHuSpeedVolumeDialog = false },
            title = { Text(stringResource(R.string.car_hu_speed_volume_title)) },
            text = { Text(stringResource(R.string.car_hu_speed_volume_message)) },
            confirmButton = {
                TextButton(onClick = { showHuSpeedVolumeDialog = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    // --- СПРАВКА О CAR-АУДИО ---
    if (showInfoDialog) {
        val infoScrollState = rememberScrollState()
        ScaledAlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.car_info_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .drawColumnScrollbar(infoScrollState)
                        .padding(start = 12.dp)
                        .heightIn(max = 400.dp)
                        .verticalScroll(infoScrollState)
                ) {
                    Text(
                        text = stringResource(R.string.car_info_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.car_info_volume_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.car_info_volume_text),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.car_info_eq_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.car_info_eq_text),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.got_it))
                }
            }
        )
    }
}

/** Пункт выпадающего списка Car: подпись, цвет индикатора (null — без индикатора), доп. текст справа. */
private data class CarDropdownOption(
    val label: String,
    val indicator: Color?,
    val trailing: String? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarDropdownSelector(
    selectedLabel: String,
    selectedIndicator: Color?,
    enabled: Boolean,
    options: List<CarDropdownOption>,
    modifier: Modifier = Modifier,
    onOpenDropdown: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded) onOpenDropdown()
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .clip(RoundedCornerShape(AppUiTheme.subCardCornerRadius))
                .background(if (enabled) AppUiTheme.activeSubCardColor() else AppUiTheme.inactiveSubCardColor())
                .padding(
                    horizontal = AppUiTheme.cardInnerPaddingHorizontal,
                    vertical = AppUiTheme.cardInnerPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedIndicator != null) {
                StatusIndicator(selectedIndicator)
                Spacer(modifier = Modifier.width(AppUiTheme.spacerGap))
            }
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AppUiTheme.activeSubCardColor(),
            shape = RoundedCornerShape(AppUiTheme.subCardCornerRadius),
        ) {
            ProvideScaledDensity {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (option.indicator != null) {
                                    StatusIndicator(option.indicator)
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(option.label, modifier = Modifier.weight(1f, fill = false))
                                if (option.trailing != null) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = option.trailing,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        onClick = {
                            option.onClick()
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun volumeStatusColor(status: VolumeMethodStatus): Color = when (status) {
    VolumeMethodStatus.UNTESTED -> MaterialTheme.colorScheme.outline
    VolumeMethodStatus.WORKS -> Color(0xFF2E7D32)
    VolumeMethodStatus.FAILS -> MaterialTheme.colorScheme.error
}

@Composable
private fun speedStatusColor(status: SpeedSourceStatus): Color = when (status) {
    SpeedSourceStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
    SpeedSourceStatus.ACTIVE -> Color(0xFF2E7D32)
    SpeedSourceStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
}

@Composable
private fun VolumeMethodSelector(
    selected: VolumeMethod,
    method2Status: VolumeMethodStatus,
    method3Status: VolumeMethodStatus,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (VolumeMethod) -> Unit,
    onOpenDropdown: () -> Unit = {},
) {
    val labels = mapOf(
        VolumeMethod.AUTO to stringResource(R.string.car_volume_method_auto),
        VolumeMethod.METHOD_2 to stringResource(R.string.car_volume_method_2),
        VolumeMethod.METHOD_3 to stringResource(R.string.car_volume_method_3),
    )
    val indicators = mapOf(
        VolumeMethod.AUTO to null,
        VolumeMethod.METHOD_2 to volumeStatusColor(method2Status),
        VolumeMethod.METHOD_3 to volumeStatusColor(method3Status),
    )
    CarDropdownSelector(
        selectedLabel = labels.getValue(selected),
        selectedIndicator = indicators[selected],
        enabled = enabled,
        modifier = modifier,
        onOpenDropdown = onOpenDropdown,
        options = VolumeMethod.entries.map { method ->
            CarDropdownOption(labels.getValue(method), indicators[method]) { onSelect(method) }
        },
    )
}

@Composable
private fun SpeedSourceSelector(
    selected: SpeedSource,
    infos: Map<SpeedSource, SpeedSourceInfo>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (SpeedSource) -> Unit,
) {
    val labels = mapOf(
        SpeedSource.AUTO to stringResource(R.string.car_volume_method_auto),
        SpeedSource.ECARX to stringResource(R.string.car_speed_source_ecarx),
        SpeedSource.VHAL to stringResource(R.string.car_speed_source_vhal),
        SpeedSource.GPS to stringResource(R.string.car_speed_source_gps),
    )
    val indicators = SpeedSource.entries.associateWith { source ->
        infos[source]?.let { speedStatusColor(it.status) }
    }
    val maxTexts = SpeedSource.entries.associateWith { source ->
        infos[source]?.maxKmh?.let { stringResource(R.string.car_speed_source_max, it.roundToInt()) }
    }
    CarDropdownSelector(
        selectedLabel = labels.getValue(selected),
        selectedIndicator = indicators[selected],
        enabled = enabled,
        modifier = modifier,
        options = SpeedSource.entries.map { source ->
            CarDropdownOption(
                label = labels.getValue(source),
                indicator = indicators[source],
                trailing = if (source == SpeedSource.AUTO) null else maxTexts[source] ?: "—",
            ) { onSelect(source) }
        },
    )
}
