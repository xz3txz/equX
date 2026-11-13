package com.xz3t.equX.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.xz3t.equX.data.LogFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xz3t.equX.R
import com.xz3t.equX.data.APP_SCALE_MAX
import com.xz3t.equX.data.APP_SCALE_MIN
import com.xz3t.equX.data.AppLanguage
import com.xz3t.equX.data.DEFAULT_SMOOTH_TRANSITION_MS
import com.xz3t.equX.data.coerceAppScale
import com.xz3t.equX.data.computeRecommendedAppScale
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.ThemeMode
import com.xz3t.equX.ui.components.EquXCheckbox
import com.xz3t.equX.ui.components.EquXSlider
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.drawColumnScrollbar
import com.xz3t.equX.ui.theme.AccentColorOption
import com.xz3t.equX.ui.theme.AccentColorOptions
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Диалоги настроек (иконка в шапке): плавный переход, масштаб, тема, язык, цвет, диагностика.
// Вынесены из MainActivity.kt; общий заголовок со стрелкой «Назад» — DialogTitleWithBack.

@Composable
internal fun TransitionSettingsDialog(
    dataManager: PresetDataManager,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val enabled by dataManager.smoothTransitionEnabledFlow.collectAsState(initial = true)
    val durationMs by dataManager.smoothTransitionDurationMsFlow.collectAsState(initial = DEFAULT_SMOOTH_TRANSITION_MS)

    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithBack(stringResource(R.string.transition_dialog_title), onBack) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.transition_enable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    EquXCheckbox(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch { dataManager.setSmoothTransitionEnabled(checked) }
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.transition_duration_label, durationMs / 1000f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                EquXSlider(
                    value = durationMs / 1000f,
                    onValueChange = { value ->
                        val ms = (value * 1000).roundToInt().coerceIn(100, 5000)
                        coroutineScope.launch { dataManager.setSmoothTransitionDurationMs(ms) }
                    },
                    valueRange = 0.1f..5f,
                    enabled = enabled,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
internal fun ScaleDialog(
    dataManager: PresetDataManager,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedScale by dataManager.appScaleFlow.collectAsState(initial = 1.0f)

    // Локальное значение слайдера: обновляем мгновенно без записи в DataStore.
    // DataStore пишем только по onValueChangeFinished — иначе каждое движение
    // пересоздаёт Density и мерцает весь экран.
    // remember(savedScale) пересоздаёт draft при реальном изменении сохранённого
    // масштаба, чтобы при повторном открытии диалога не показывался устаревший 100%.
    var draftScale by remember(savedScale) { mutableFloatStateOf(savedScale) }

    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithBack(stringResource(R.string.scale_dialog_title), onBack) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.scale_value_label, draftScale * 100f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                EquXSlider(
                    value = draftScale,
                    onValueChange = { value ->
                        draftScale = coerceAppScale(value)
                    },
                    onValueChangeFinished = {
                        coroutineScope.launch { dataManager.setAppScale(draftScale) }
                    },
                    valueRange = APP_SCALE_MIN..APP_SCALE_MAX,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = {
                val auto = computeRecommendedAppScale(context.resources.displayMetrics)
                draftScale = auto
                coroutineScope.launch { dataManager.setAppScale(auto) }
            }) {
                Text(stringResource(R.string.scale_auto))
            }
        },
    )
}

@Composable
internal fun SettingsDialog(
    onDismiss: () -> Unit,
    onTransition: () -> Unit,
    onTheme: () -> Unit,
    onColors: () -> Unit,
    onLanguage: () -> Unit,
    onScale: () -> Unit,
    onReport: () -> Unit,
) {
    val settingsScrollState = rememberScrollState()
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(
                modifier = Modifier
                    .drawColumnScrollbar(settingsScrollState)
                    .padding(start = 12.dp)
                    .heightIn(max = 400.dp)
                    .verticalScroll(settingsScrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SettingsRow(title = stringResource(R.string.settings_transition), subtitle = stringResource(R.string.settings_transition_subtitle), onClick = onTransition)
                SettingsRow(title = stringResource(R.string.settings_theme), subtitle = stringResource(R.string.settings_theme_subtitle), onClick = onTheme)
                SettingsRow(title = stringResource(R.string.settings_color), subtitle = stringResource(R.string.settings_color_subtitle), onClick = onColors)
                SettingsRow(title = stringResource(R.string.settings_language), subtitle = stringResource(R.string.settings_language_subtitle), onClick = onLanguage)
                SettingsRow(title = stringResource(R.string.settings_scale), subtitle = stringResource(R.string.settings_scale_subtitle), onClick = onScale)
                SettingsRow(title = stringResource(R.string.settings_report), subtitle = stringResource(R.string.settings_report_subtitle), onClick = onReport)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        }
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(">", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DialogTitleWithBack(
    title: String,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun ThemeModeDialog(
    currentMode: ThemeMode,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelect: (ThemeMode) -> Unit,
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithBack(stringResource(R.string.theme_title), onBack) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    ThemeMode.NIGHT to stringResource(R.string.theme_night),
                    ThemeMode.DAY to stringResource(R.string.theme_day),
                    ThemeMode.AUTO to stringResource(R.string.theme_auto),
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(mode) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        RadioButton(selected = mode == currentMode, onClick = { onSelect(mode) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        }
    )
}

@Composable
internal fun LanguageDialog(
    currentLanguage: AppLanguage,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelect: (AppLanguage) -> Unit,
) {
    val languageScrollState = rememberScrollState()
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithBack(stringResource(R.string.language_title), onBack) },
        text = {
            Column(
                modifier = Modifier
                    .drawColumnScrollbar(languageScrollState)
                    .padding(start = 12.dp)
                    .heightIn(max = 400.dp)
                    .verticalScroll(languageScrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppLanguage.values().forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(language) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (language == AppLanguage.SYSTEM) stringResource(R.string.theme_auto) else language.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        RadioButton(selected = language == currentLanguage, onClick = { onSelect(language) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        }
    )
}

@Composable
internal fun AccentColorDialog(
    selectedArgb: Int,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithBack(stringResource(R.string.color_title), onBack) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.color_description),
                    style = MaterialTheme.typography.bodyMedium
                )

                AccentColorOptions.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { option ->
                            AccentSwatch(
                                option = option,
                                selected = option.argb == selectedArgb,
                                onClick = { onSelect(option.argb) }
                            )
                        }
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.size(56.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        }
    )
}

@Composable
private fun AccentSwatch(
    option: AccentColorOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(option.argb))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.3f),
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(option.nameRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * «Отчёт об ошибке»: выгрузка журнала (заголовок + журнал приложения + logcat) в Download
 * или через «Поделиться». Журнал пишется всегда; после выгрузки он очищается.
 */
@Composable
internal fun ReportDialog(
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Отчёт (logcat + zip) собирается в фоне: на главном потоке это ANR на медленном ГУ.
    var reportInProgress by remember { mutableStateOf(false) }
    fun runReport(block: suspend () -> Unit) {
        if (reportInProgress) return
        reportInProgress = true
        coroutineScope.launch {
            try {
                block()
            } finally {
                reportInProgress = false
            }
        }
    }

    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleWithBack(stringResource(R.string.diagnostics_title), onBack) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.diagnostics_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        runReport {
                            val name = withContext(Dispatchers.IO) { LogFileWriter.exportToDownload() }
                            Toast.makeText(
                                context,
                                if (name != null) R.string.diagnostics_saved else R.string.diagnostics_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !reportInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.diagnostics_save_download))
                }
                Button(
                    onClick = {
                        runReport {
                            val file = withContext(Dispatchers.IO) { LogFileWriter.buildShareZip() }
                            if (file == null) {
                                Toast.makeText(context, R.string.diagnostics_failed, Toast.LENGTH_SHORT).show()
                                return@runReport
                            }
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            } catch (e: Exception) {
                                Toast.makeText(context, R.string.diagnostics_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !reportInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.diagnostics_share))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

/**
 * Скрытое меню отладки (три быстрых нажатия на карточку аудиоустройства).
 * Флаги не сохраняются: после перезапуска приложения всё выключено.
 */
@Composable
internal fun DebugDialog(
    carModeForced: Boolean,
    onCarModeForcedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckboxRow(
                    title = stringResource(R.string.debug_force_car),
                    subtitle = null,
                    checked = carModeForced,
                    onCheckedChange = onCarModeForcedChange
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun CheckboxRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        EquXCheckbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}
