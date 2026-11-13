package com.xz3t.equX.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xz3t.equX.R
import com.xz3t.equX.data.AppBinding
import com.xz3t.equX.data.AudioDeviceBinding
import com.xz3t.equX.data.AudioDeviceScanner
import com.xz3t.equX.data.BindingConflictType
import com.xz3t.equX.data.ConflictResult
import com.xz3t.equX.data.DEFAULT_PRESET_NAME
import com.xz3t.equX.data.MediaAppScanner
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.presetNameError
import com.xz3t.equX.data.sanitizePresetName
import com.xz3t.equX.data.suggestPresetName
import com.xz3t.equX.ui.components.EquXMainCard
import com.xz3t.equX.ui.components.EquXSubCard
import com.xz3t.equX.ui.components.FileManagerDialog
import com.xz3t.equX.ui.components.FileManagerMode
import com.xz3t.equX.ui.components.StableTogglePlaceholder
import com.xz3t.equX.ui.components.StableToggleTextButton
import com.xz3t.equX.ui.components.BindingOption
import com.xz3t.equX.ui.components.BindingSelectionDialog
import com.xz3t.equX.ui.components.PresetNameField
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.drawLazyScrollbar
import com.xz3t.equX.ui.theme.AppUiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetScreen(
    dataManager: PresetDataManager,
    onPresetActivated: (PresetModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentPreset = dataManager.liveDraftPreset
    val hasChanges = dataManager.hasUnsavedChanges()
    val isAutoSwitchEnabled = dataManager.isAutoSwitchEnabled

    var showNewPresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    var showRenameDialog by remember { mutableStateOf(false) }
    var presetToRename by remember { mutableStateOf<PresetModel?>(null) }
    var renamePresetNewName by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<PresetModel?>(null) }

    var expandedPresetName by remember { mutableStateOf<String?>(null) }

    // Состояния для диалоговых окон выбора устройств и приложений
    var showDevicesDialogForPreset by remember { mutableStateOf<PresetModel?>(null) }
    var showSourcesDialogForPreset by remember { mutableStateOf<PresetModel?>(null) }
    var showAppsDialogForPreset by remember { mutableStateOf<PresetModel?>(null) }

    // Состояния конфликтов привязкок
    var activeConflictResult by remember { mutableStateOf<Pair<PresetModel, ConflictResult>?>(null) }

    // Экспорт/импорт
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportPreset by remember { mutableStateOf<PresetModel?>(null) }
    var showImportNameDialog by remember { mutableStateOf(false) }
    var pendingImportPreset by remember { mutableStateOf<PresetModel?>(null) }
    var importNewName by remember { mutableStateOf("") }
    var showImportErrorDialog by remember { mutableStateOf(false) }
    // Имя файла, который не удалось записать при экспорте (null — диалога нет).
    var exportErrorFileName by remember { mutableStateOf<String?>(null) }
    var showAutoSwitchInfo by remember { mutableStateOf(false) }

    // Действие, отложенное до решения по черновику (Сбросить/Сохранить).
    var pendingDraftAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Выполняет действие сразу или, если есть несохранённый черновик, после диалога.
    fun runAfterDraftResolved(action: () -> Unit) {
        if (dataManager.hasUnsavedChanges()) pendingDraftAction = action else action()
    }

    // Привязки активного пресета меняют и черновик — сначала решаем его судьбу.
    fun openBindingsDialog(preset: PresetModel, open: () -> Unit) {
        if (preset.presetName == currentPreset.presetName) runAfterDraftResolved(open) else open()
    }

    val isAutomotive = remember { AudioDeviceScanner.isAutomotive(context.applicationContext) }

    // Занятые имена: все пресеты + перевод встроенного «По умолчанию» на текущем языке
    // (иначе можно создать второй «Default», неотличимый в списке).
    val defaultPresetDisplayName = stringResource(R.string.default_preset_name)
    fun takenNames(): List<String> = dataManager.presetNames() + defaultPresetDisplayName

    // Диалог импорта открыт из-за недопустимых символов (иначе — из-за занятого имени).
    var importNameInvalid by remember { mutableStateOf(false) }

    val availablePresets by remember {
        derivedStateOf { dataManager.getAllPresets() }
    }

    val deviceScanner = remember { AudioDeviceScanner(context.applicationContext) }
    val mediaAppScanner = remember { MediaAppScanner(context.applicationContext) }

    var availableDevices by remember { mutableStateOf(emptyList<AudioDeviceBinding>()) }
    var availableSources by remember { mutableStateOf(emptyList<AudioDeviceBinding>()) }
    var availableApps by remember { mutableStateOf(emptyList<AppBinding>()) }
    var appsScanVersion by remember { mutableStateOf(0) }

    // Динамическое сканирование устройств и источников (источники — только на ГУ) + чистка
    // привязок к отвязанным. Отвязку во время работы ловит служба (BOND_STATE_CHANGED).
    LaunchedEffect(expandedPresetName, showDevicesDialogForPreset, showSourcesDialogForPreset) {
        val devices = deviceScanner.scanAvailableOutputDevices()
        val sources = deviceScanner.scanAvailableSources()
        availableDevices = devices
        availableSources = sources
        dataManager.pruneMissingDeviceBindings(
            devices.map { it.id }.toSet(),
            sources.map { it.id }.toSet()
        )
    }

    // Динамическое сканирование приложений (перезапускается при установке/удалении пакетов).
    // Список нужен только для диалога выбора; привязки по нему НЕ чистятся — приложение,
    // найденное по активной MediaSession, после закрытия в списке отсутствует, хотя установлено.
    // Отвязку удалённых приложений делает служба по PACKAGE_REMOVED.
    LaunchedEffect(showAppsDialogForPreset, appsScanVersion) {
        availableApps = mediaAppScanner.scanInstalledMediaApps()
    }

    // Автоматический сброс кэша при установке/удалении/обновлении приложений
    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                MediaAppScanner.invalidateCache()
                appsScanVersion++
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val lazyListState = rememberLazyListState()

    // --- ВСТРОЕННЫЙ ФАЙЛОВЫЙ МЕНЕДЖЕР (для Android Automotive без системного DocumentsUI) ---
    var showFileManager by remember { mutableStateOf(false) }
    var fileManagerMode by remember { mutableStateOf(FileManagerMode.IMPORT) }
    var exportFileName by remember { mutableStateOf("") }
    var pendingStorageMode by remember { mutableStateOf<FileManagerMode?>(null) }

    fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            pendingStorageMode?.let { mode ->
                pendingStorageMode = null
                fileManagerMode = mode
                showFileManager = true
            }
        } else {
            pendingStorageMode = null
        }
    }

    fun requestStorageAccess(mode: FileManagerMode) {
        pendingStorageMode = mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (e: Exception) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    fun openFileManager(mode: FileManagerMode, fileName: String = "") {
        exportFileName = fileName
        if (hasStorageAccess()) {
            pendingStorageMode = null
            fileManagerMode = mode
            showFileManager = true
        } else {
            requestStorageAccess(mode)
        }
    }

    // После возврата из системных настроек «Все файлы» (Android 11+) продолжаем открытие ФМ.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pendingStorageMode?.let { mode ->
                    if (hasStorageAccess()) {
                        pendingStorageMode = null
                        fileManagerMode = mode
                        showFileManager = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(AppUiTheme.cardSpacing),
        modifier = modifier
            .fillMaxSize()
            .drawLazyScrollbar(lazyListState)
            .padding(horizontal = AppUiTheme.cardOuterPaddingHorizontal, vertical = AppUiTheme.cardOuterPaddingVertical)
    ) {
        // --- ТУМБЛЕР РЕЖИМА: АВТОПЕРЕКЛЮЧЕНИЕ / РУЧНОЙ ВЫБОР ---
        item(key = "auto_switch") {
        EquXMainCard(
            isActive = true
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Switch(
                        checked = isAutoSwitchEnabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                dataManager.setAutoSwitchEnabled(checked)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(if (isAutoSwitchEnabled) R.string.auto_switch else R.string.manual_mode),
                            style = MaterialTheme.typography.titleMedium,
                            color = AppUiTheme.accentColor()
                        )
                        Text(
                            text = stringResource(if (isAutoSwitchEnabled) R.string.auto_switch_subtitle else R.string.manual_mode_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { showAutoSwitchInfo = true },
                    modifier = Modifier.size(AppUiTheme.iconButtonSize)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.auto_switch_info_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        } // end item auto_switch

        // --- ПАНЕЛЬ БЕЗОПАСНОГО СОХРАНЕНИЯ ---
        if (hasChanges) {
        item(key = "save_panel") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppUiTheme.cardInnerPaddingHorizontal, vertical = AppUiTheme.cardInnerPaddingVertical).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.warning), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.preset_modified, presetDisplayName(currentPreset.presetName)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (isAutoSwitchEnabled) {
                                Text(
                                    text = stringResource(R.string.auto_switch_paused_draft),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            dataManager.discardChanges()
                            onPresetActivated(dataManager.liveDraftPreset)
                        }) {
                            Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = {
                            if (dataManager.defaultPresetsMap.containsKey(currentPreset.presetName)) {
                                showNewPresetDialog = true
                            } else {
                                coroutineScope.launch {
                                    dataManager.saveCurrentDraftAsActive()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Done, contentDescription = stringResource(R.string.save))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
        } // end item save_panel

        // --- ВЕРХНИЙ РЯД УПРАВЛЕНИЯ ФАЙЛАМИ JSON И ВЫБОРОМ ПРЕСЕТА ПО УМОЛЧАНИЮ ---
        item(key = "file_buttons") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = { showNewPresetDialog = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.create), style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = {
                    if (hasChanges) {
                        showExportDialog = true
                    } else {
                        pendingExportPreset = dataManager.originalPreset
                        openFileManager(FileManagerMode.EXPORT, "${dataManager.originalPreset.presetName}.json")
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.export), style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = { openFileManager(FileManagerMode.IMPORT) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.import_text), style = MaterialTheme.typography.labelSmall)
            }
        }
        } // end item file_buttons

        item(key = "choose_profile") {
        Text(
            text = stringResource(R.string.choose_profile),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        }

        // --- СПИСОК ВЫБОРА ПРОФИЛЕЙ ЗВУКА С ПОЛНОЙ АНИМАЦИЕЙ НАЖАТИЯ НА ВСЮ КАРТОЧКУ ---
        items(availablePresets, key = { it.presetName }) { preset ->
                val isActive = currentPreset.presetName == preset.presetName
                val isUserPreset = dataManager.userPresetsMap.containsKey(preset.presetName)
                val isExpanded = expandedPresetName == preset.presetName

                EquXMainCard(
                    isActive = isActive,
                    onClick = {
                        if (!isAutoSwitchEnabled && preset.presetName != currentPreset.presetName) {
                            runAfterDraftResolved {
                                coroutineScope.launch {
                                    dataManager.activatePreset(preset)
                                    onPresetActivated(preset)
                                }
                            }
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppUiTheme.presetCardHeight),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (preset.presetName == DEFAULT_PRESET_NAME) {
                            // «По умолчанию» не имеет привязок — центрируем надпись по
                            // вертикали, сохраняя высоту как у карточек с подписями.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = presetDisplayName(preset.presetName),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isActive) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = presetDisplayName(preset.presetName),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isActive) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )

                                // Компактные подписи количества привязок под названием пресета
                                // На ГУ привязки идут к источникам звука, а не к устройствам вывода.
                                val boundOutputs = if (isAutomotive) preset.boundSources else preset.boundDevices
                                if (boundOutputs.isNotEmpty() || preset.boundApps.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    if (boundOutputs.isNotEmpty()) {
                                        Text(
                                            text = stringResource(
                                                if (isAutomotive) R.string.sources_count else R.string.devices_count,
                                                boundOutputs.size
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isActive) AppUiTheme.accentColor().copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (preset.boundApps.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.apps_count, preset.boundApps.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isActive) AppUiTheme.accentColor().copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (isUserPreset) {
                            IconButton(
                                onClick = {
                                    presetToRename = preset
                                    renamePresetNewName = preset.presetName
                                    showRenameDialog = true
                                },
                                modifier = Modifier.size(AppUiTheme.iconButtonSize)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.rename_preset_desc),
                                    tint = AppUiTheme.accentColor()
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(
                                onClick = {
                                    presetToDelete = preset
                                    showDeleteDialog = true
                                },
                                modifier = Modifier.size(AppUiTheme.iconButtonSize)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete_preset_desc),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (preset.presetName != DEFAULT_PRESET_NAME) {
                            StableToggleTextButton(
                                isExpanded = isExpanded,
                                onClick = {
                                    expandedPresetName = if (isExpanded) null else preset.presetName
                                },
                                color = if (isActive) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            // Невидимая заглушка той же ширины, что и кнопка,
                            // чтобы иконки не «прыгали» при разной длине «Настроить»/«Свернуть».
                            StableTogglePlaceholder()
                        }
                    }

                    if (preset.presetName != DEFAULT_PRESET_NAME) {
                        // Раскрываемый чистый плоский блок настроек
                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // 1. Под-карточка "Устройства вывода"
                            EquXSubCard(
                                isActive = preset.boundDevices.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openBindingsDialog(preset) { showDevicesDialogForPreset = preset } }
                            ) {
                                Text(
                                    text = stringResource(R.string.output_devices),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    color = if (preset.boundDevices.isNotEmpty()) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = "${preset.boundDevices.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (preset.boundDevices.isNotEmpty()) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = { openBindingsDialog(preset) { showDevicesDialogForPreset = preset } },
                                    modifier = Modifier.size(AppUiTheme.iconButtonSize)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.select_output_devices_desc),
                                        tint = AppUiTheme.accentColor()
                                    )
                                }
                            }

                            // Под-карточка "Источники звука" (только на автомобильном ГУ).
                            if (isAutomotive) {
                                EquXSubCard(
                                    isActive = preset.boundSources.isNotEmpty(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openBindingsDialog(preset) { showSourcesDialogForPreset = preset } }
                                ) {
                                    Text(
                                        text = stringResource(R.string.sound_sources),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        color = if (preset.boundSources.isNotEmpty()) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                                    )

                                    Text(
                                        text = "${preset.boundSources.size}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (preset.boundSources.isNotEmpty()) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.width(4.dp))

                                    IconButton(
                                        onClick = { openBindingsDialog(preset) { showSourcesDialogForPreset = preset } },
                                        modifier = Modifier.size(AppUiTheme.iconButtonSize)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.select_sound_sources_desc),
                                            tint = AppUiTheme.accentColor()
                                        )
                                    }
                                }
                            }

                            // 2. Под-карточка "Приложения"
                            EquXSubCard(
                                isActive = preset.boundApps.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openBindingsDialog(preset) { showAppsDialogForPreset = preset } }
                            ) {
                                Text(
                                    text = stringResource(R.string.apps),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    color = if (preset.boundApps.isNotEmpty()) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = "${preset.boundApps.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (preset.boundApps.isNotEmpty()) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = { openBindingsDialog(preset) { showAppsDialogForPreset = preset } },
                                    modifier = Modifier.size(AppUiTheme.iconButtonSize)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.select_apps_desc),
                                        tint = AppUiTheme.accentColor()
                                    )
                                }
                            }
                        }
                    }
                    } // close if (preset.presetName != DEFAULT_PRESET_NAME)
                }
            }
        }

    // --- ДИАЛОГ ВЫБОРА УСТРОЙСТВ ВЫВОДА ---
    if (showDevicesDialogForPreset != null) {
        val preset = showDevicesDialogForPreset!!
        val currentBoundDevices = dataManager.getAllPresets().find { it.presetName == preset.presetName }?.boundDevices ?: preset.boundDevices
        BindingSelectionDialog(
            title = stringResource(R.string.output_devices_binding),
            options = availableDevices.map { device ->
                BindingOption(device.id, device.name, checked = currentBoundDevices.any { it.id == device.id })
            },
            onToggle = { option ->
                val device = availableDevices.first { it.id == option.key }
                // Конфликты проверяем только при добавлении привязки.
                val conflict = if (!option.checked) {
                    dataManager.checkBindingConflict(preset.presetName, app = null, device = device)
                } else {
                    null
                }
                if (conflict != null) {
                    activeConflictResult = preset to conflict
                } else {
                    coroutineScope.launch { dataManager.toggleDeviceBinding(preset.presetName, device) }
                }
            },
            onDismiss = { showDevicesDialogForPreset = null },
        )
    }

    // --- ДИАЛОГ ВЫБОРА ИСТОЧНИКОВ ЗВУКА (ТОЛЬКО НА ГУ) ---
    if (showSourcesDialogForPreset != null) {
        val preset = showSourcesDialogForPreset!!
        val currentBoundSources = dataManager.getAllPresets().find { it.presetName == preset.presetName }?.boundSources ?: preset.boundSources
        BindingSelectionDialog(
            title = stringResource(R.string.sound_sources_binding),
            options = availableSources.map { source ->
                BindingOption(source.id, source.name, checked = currentBoundSources.any { it.id == source.id })
            },
            emptyText = stringResource(R.string.no_sound_sources),
            onToggle = { option ->
                val source = availableSources.first { it.id == option.key }
                coroutineScope.launch { dataManager.toggleSourceBinding(preset.presetName, source) }
            },
            onDismiss = { showSourcesDialogForPreset = null },
        )
    }

    // --- ПРЕДУПРЕЖДЕНИЕ: ДЕЙСТВИЕ ПЕРЕЗАПИШЕТ ЧЕРНОВИК ---
    if (pendingDraftAction != null) {
        val action = pendingDraftAction!!
        ScaledAlertDialog(
            onDismissRequest = { pendingDraftAction = null },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = {
                Text(
                    text = stringResource(R.string.draft_pending_msg, presetDisplayName(currentPreset.presetName)),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingDraftAction = null
                    if (dataManager.defaultPresetsMap.containsKey(currentPreset.presetName)) {
                        // Встроенный пресет сохраняется только под новым именем (как в панели сохранения).
                        showNewPresetDialog = true
                    } else {
                        coroutineScope.launch {
                            dataManager.saveCurrentDraftAsActive()
                            action()
                        }
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingDraftAction = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = {
                        pendingDraftAction = null
                        dataManager.discardChanges()
                        onPresetActivated(dataManager.liveDraftPreset)
                        action()
                    }) {
                        Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    // --- СПРАВКА ОБ АВТОПЕРЕКЛЮЧЕНИИ ---
    if (showAutoSwitchInfo) {
        ScaledAlertDialog(
            onDismissRequest = { showAutoSwitchInfo = false },
            title = { Text(stringResource(R.string.auto_switch_info_title)) },
            text = {
                Text(
                    text = stringResource(R.string.auto_switch_info_text),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showAutoSwitchInfo = false }) {
                    Text(stringResource(R.string.got_it))
                }
            }
        )
    }

    // --- ДИАЛОГ ВЫБОРА ЧТО ЭКСПОРТИРОВАТЬ ПРИ НЕСОХРАНЁННЫХ ИЗМЕНЕНИЯХ ---
    if (showExportDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.export_unsaved_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    pendingExportPreset = dataManager.liveDraftPreset
                    openFileManager(FileManagerMode.EXPORT, "${dataManager.liveDraftPreset.presetName}.json")
                }) {
                    Text(stringResource(R.string.draft))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    pendingExportPreset = dataManager.originalPreset
                    openFileManager(FileManagerMode.EXPORT, "${dataManager.originalPreset.presetName}.json")
                }) {
                    Text(stringResource(R.string.saved))
                }
            }
        )
    }

    // --- ДИАЛОГ НОВОГО ИМЕНИ ПРИ КОНФЛИКТЕ ИМПОРТА ---
    if (showImportNameDialog && pendingImportPreset != null) {
        val importNameError = presetNameError(importNewName, takenNames())
        ScaledAlertDialog(
            onDismissRequest = {
                showImportNameDialog = false
                pendingImportPreset = null
            },
            title = {
                Text(stringResource(if (importNameInvalid) R.string.preset_name_invalid else R.string.preset_name_taken))
            },
            text = {
                Column {
                    Text(
                        text = stringResource(if (importNameInvalid) R.string.preset_name_invalid_msg else R.string.preset_name_taken_msg),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PresetNameField(
                        value = importNewName,
                        onValueChange = { importNewName = it },
                        error = importNameError
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = importNameError == null,
                    onClick = {
                        val preset = pendingImportPreset
                        if (preset != null) {
                            val renamed = preset.copy(presetName = importNewName.trim())
                            coroutineScope.launch {
                                val imported = dataManager.importPreset(renamed)
                                if (imported != null) {
                                    onPresetActivated(imported)
                                }
                            }
                        }
                        showImportNameDialog = false
                        pendingImportPreset = null
                    }
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportNameDialog = false
                    pendingImportPreset = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // --- ДИАЛОГ КОНФЛИКТОВ ПРИВЯЗКИ (Правило 1, Правило 2 и пары) ---
    if (activeConflictResult != null) {
        val (targetPreset, conflict) = activeConflictResult!!
        val oldPreset = conflict.conflictingPreset
        val device = conflict.conflictingDevice
        val app = conflict.conflictingApp

        val titleText: String
        val messageText: String
        val showRebindButton: Boolean

        when (conflict.type) {
            BindingConflictType.GLOBAL_DEVICE_DUPLICATE -> { // Правило 2
                titleText = stringResource(R.string.conflict_global_session_title)
                messageText = stringResource(R.string.conflict_global_session_msg, device?.name ?: "", oldPreset.presetName)
                showRebindButton = true
            }
            BindingConflictType.CANNOT_BE_GLOBAL_HAS_APPS -> { // Правило 1
                titleText = stringResource(R.string.conflict_cannot_global_title)
                messageText = stringResource(R.string.conflict_cannot_global_msg, device?.name ?: "", oldPreset.presetName)
                showRebindButton = false
            }
            BindingConflictType.CANNOT_BIND_DEVICE_OTHER_IS_GLOBAL -> { // Правило 1
                titleText = stringResource(R.string.conflict_global_exists_title)
                messageText = stringResource(R.string.conflict_global_exists_msg, device?.name ?: "", oldPreset.presetName)
                showRebindButton = true
            }
            BindingConflictType.APP_DEVICE_PAIR_DUPLICATE -> {
                titleText = stringResource(R.string.conflict_pair_title)
                val itemName = app?.appName ?: device?.name ?: ""
                messageText = stringResource(R.string.conflict_pair_msg, itemName, oldPreset.presetName)
                showRebindButton = true
            }
            else -> {
                titleText = ""
                messageText = ""
                showRebindButton = false
            }
        }

        if (titleText.isNotBlank()) {
            ScaledAlertDialog(
                onDismissRequest = { activeConflictResult = null },
                title = { Text(titleText) },
                text = { Text(text = messageText, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    if (showRebindButton) {
                        Button(onClick = {
                            coroutineScope.launch {
                                dataManager.resolveConflictAndBind(targetPreset.presetName, oldPreset.presetName, app, device)
                                activeConflictResult = null
                            }
                        }) { Text(stringResource(R.string.reassign)) }
                    } else {
                        Button(onClick = { activeConflictResult = null }) { Text(stringResource(R.string.ok)) }
                    }
                },
                dismissButton = {
                    if (showRebindButton) {
                        TextButton(onClick = { activeConflictResult = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            )
        }
    }

    // --- ДИАЛОГ ВЫБОРА МЕДИА-ПРИЛОЖЕНИЙ ---
    if (showAppsDialogForPreset != null) {
        val preset = showAppsDialogForPreset!!
        val currentBoundApps = dataManager.getAllPresets().find { it.presetName == preset.presetName }?.boundApps ?: preset.boundApps
        BindingSelectionDialog(
            title = stringResource(R.string.apps_binding),
            options = availableApps.map { app ->
                BindingOption(app.packageName, app.appName, app.packageName, currentBoundApps.any { it.packageName == app.packageName })
            },
            emptyText = stringResource(R.string.no_media_apps),
            onToggle = { option ->
                val app = availableApps.first { it.packageName == option.key }
                // Добавление — конфликты привязки; снятие — конфликт «пресет станет глобальным».
                val conflict = if (!option.checked) {
                    dataManager.checkBindingConflict(preset.presetName, app = app, device = null)
                } else {
                    dataManager.checkAppRemovalConflict(preset.presetName, app)
                }
                if (conflict != null) {
                    activeConflictResult = preset to conflict
                } else {
                    coroutineScope.launch { dataManager.toggleAppBinding(preset.presetName, app) }
                }
            },
            onDismiss = { showAppsDialogForPreset = null },
        )
    }

    // --- ДИАЛОГ СОЗДАНИЯ НОВОГО ПРЕСЕТА ---
    if (showNewPresetDialog) {
        val newNameError = presetNameError(newPresetName, takenNames())
        ScaledAlertDialog(
            onDismissRequest = { showNewPresetDialog = false },
            title = { Text(stringResource(R.string.new_profile)) },
            text = {
                PresetNameField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    error = newNameError,
                    label = { Text(stringResource(R.string.preset_name_label)) }
                )
            },
            confirmButton = {
                Button(
                    enabled = newNameError == null,
                    onClick = {
                        coroutineScope.launch {
                            if (dataManager.saveAsNewPreset(newPresetName)) {
                                showNewPresetDialog = false
                                newPresetName = ""
                                onPresetActivated(dataManager.liveDraftPreset)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewPresetDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // --- ДИАЛОГ ПЕРЕИМЕНОВАНИЯ ПРЕСЕТА ---
    if (showRenameDialog && presetToRename != null) {
        val renameError = presetNameError(renamePresetNewName, takenNames(), ownName = presetToRename?.presetName)
        ScaledAlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                presetToRename = null
            },
            title = { Text(stringResource(R.string.rename_profile)) },
            text = {
                PresetNameField(
                    value = renamePresetNewName,
                    onValueChange = { renamePresetNewName = it },
                    error = renameError,
                    label = { Text(stringResource(R.string.new_name)) }
                )
            },
            confirmButton = {
                Button(
                    enabled = renameError == null,
                    onClick = {
                        val oldName = presetToRename?.presetName
                        if (!oldName.isNullOrBlank()) {
                            coroutineScope.launch {
                                if (dataManager.renameUserPreset(oldName, renamePresetNewName)) {
                                    showRenameDialog = false
                                    presetToRename = null
                                }
                            }
                        }
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    presetToRename = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // --- ДИАЛОГ ПОДТВЕРЖДЕНИЯ УДАЛЕНИЯ ПРЕСЕТА ---
    if (showDeleteDialog && presetToDelete != null) {
        ScaledAlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                presetToDelete = null
            },
            title = { Text(stringResource(R.string.delete_profile)) },
            text = {
                Text(stringResource(R.string.delete_profile_msg, presetToDelete?.presetName ?: ""))
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val nameToDelete = presetToDelete?.presetName
                        if (!nameToDelete.isNullOrBlank()) {
                            coroutineScope.launch {
                                dataManager.deleteUserPreset(nameToDelete)
                                showDeleteDialog = false
                                presetToDelete = null
                                onPresetActivated(dataManager.liveDraftPreset)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    presetToDelete = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // --- ВСТРОЕННЫЙ ФАЙЛОВЫЙ МЕНЕДЖЕР ---
    if (showFileManager) {
        FileManagerDialog(
            mode = fileManagerMode,
            initialFileName = exportFileName,
            onDismiss = { showFileManager = false },
            onFilePicked = { source ->
                coroutineScope.launch {
                    val parsed = withContext(Dispatchers.IO) {
                        dataManager.parsePresetFromFile(source)
                    }
                    if (parsed != null) {
                        val fallback = context.getString(R.string.imported_preset)
                        val original = parsed.presetName.trim()
                        val suggested = suggestPresetName(original, takenNames(), fallback)
                        if (suggested != original) {
                            // Недопустимые символы и/или занятое имя: предлагаем исправленное.
                            importNameInvalid = original.isEmpty() || sanitizePresetName(original, fallback) != original
                            importNewName = suggested
                            pendingImportPreset = parsed
                            showImportNameDialog = true
                        } else {
                            val imported = dataManager.importPreset(parsed.copy(presetName = original))
                            if (imported != null) {
                                onPresetActivated(imported)
                            }
                        }
                    } else {
                        showImportErrorDialog = true
                    }
                    showFileManager = false
                }
            },
            onExportConfirmed = { target ->
                val preset = pendingExportPreset ?: dataManager.originalPreset
                coroutineScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        dataManager.exportPresetToExternalFile(target, preset)
                    }
                    pendingExportPreset = null
                    showFileManager = false
                    if (!ok) exportErrorFileName = target.name
                }
            }
        )
    }

    // --- ОШИБКА ЭКСПОРТА (нет доступа к папке, носитель только для чтения, нет места и т.п.) ---
    if (exportErrorFileName != null) {
        ScaledAlertDialog(
            onDismissRequest = { exportErrorFileName = null },
            title = { Text(stringResource(R.string.export_error_title)) },
            text = { Text(stringResource(R.string.export_error_message, exportErrorFileName ?: "")) },
            confirmButton = {
                TextButton(onClick = { exportErrorFileName = null }) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    if (showImportErrorDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showImportErrorDialog = false },
            title = { Text(stringResource(R.string.import_error_title)) },
            text = { Text(stringResource(R.string.import_error_message)) },
            confirmButton = {
                TextButton(onClick = { showImportErrorDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun presetDisplayName(presetName: String): String =
    if (presetName == DEFAULT_PRESET_NAME) stringResource(R.string.default_preset_name) else presetName
