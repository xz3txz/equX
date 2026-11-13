package com.xz3t.equX.ui

import android.Manifest
import android.app.LocaleManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xz3t.equX.R
import com.xz3t.equX.data.APP_LANGUAGE_PREFERENCES_KEY
import com.xz3t.equX.data.AppLanguage
import com.xz3t.equX.data.AudioDeviceScanner
import com.xz3t.equX.data.DebugFlags
import com.xz3t.equX.data.LogFileWriter
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.effectiveLimiterParams
import com.xz3t.equX.data.ThemeMode
import com.xz3t.equX.data.SpeedSource
import com.xz3t.equX.data.SpeedSourceInfo
import com.xz3t.equX.data.VolumeMethodStatus
import com.xz3t.equX.data.dataStore
import com.xz3t.equX.data.deriveLimiterParams
import com.xz3t.equX.receiver.BootReceiver
import com.xz3t.equX.service.EqualizerService
import com.xz3t.equX.ui.components.EquXCheckbox
import com.xz3t.equX.ui.components.ScaledAlertDialog
import com.xz3t.equX.ui.components.TopHeaderComponent
import com.xz3t.equX.ui.screens.CompressorScreen
import com.xz3t.equX.ui.screens.EqualizerScreen
import com.xz3t.equX.ui.screens.LimiterScreen
import com.xz3t.equX.ui.screens.PresetScreen
import com.xz3t.equX.ui.screens.SpatialFxScreen
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.LocalAppScale
import com.xz3t.equX.util.OemAutostart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import android.util.Log
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.wrapWithAppLanguage())
    }

    /**
     * Применяет выбранный язык на API < 33 через обёртку контекста.
     * На Android 13+ локаль уже применяется системой через LocaleManager.
     */
    private fun Context.wrapWithAppLanguage(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
        val tag = runCatching {
            runBlocking { dataStore.data.first()[APP_LANGUAGE_PREFERENCES_KEY] }
        }.getOrNull()
        val language = AppLanguage.fromTag(tag)
        val locale = language.locale ?: return this
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        return createConfigurationContext(config)
    }

    /** Применяет выбранный язык и перезапускает Activity для обновления интерфейса. */
    private fun applyAppLanguage(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = if (language.locale == null) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language.tag ?: "")
            }
        } else {
            recreate()
        }
    }

    private var equalizerService: EqualizerService? = null
    private var isBound = false
    private val fftDataState = mutableStateOf(FloatArray(16))
    private var serviceState = mutableStateOf<EqualizerService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? EqualizerService.LocalBinder
            val actualService = binder?.getService()
            equalizerService = actualService
            serviceState.value = actualService
            isBound = true

            actualService?.onFftDataUpdate = { magnitudes ->
                fftDataState.value = magnitudes
            }
            // Служба могла подключиться уже после ON_RESUME — сообщаем движку текущую видимость UI.
            actualService?.getAudioEngine()?.setVisualizerUiVisible(
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                this@MainActivity
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            equalizerService = null
            serviceState.value = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, EqualizerService::class.java)
        val dataManager = PresetDataManager.getInstance(this)

        // Журнал в файл — до старта службы.
        LogFileWriter.init(applicationContext)

        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e("equX_MainActivity", "Не удалось запустить службу: ${e.message}", e)
        }
        bindService(intent, connection, BIND_AUTO_CREATE)

        setContent {
            val accentArgb by dataManager.accentColorFlow.collectAsState(initial = dataManager.accentColor)
            val themeMode by dataManager.themeModeFlow.collectAsState(initial = dataManager.themeMode)
            val appScale by dataManager.appScaleFlow.collectAsState(initial = 1.0f)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.NIGHT -> true
                ThemeMode.DAY -> false
                ThemeMode.AUTO -> systemDark
            }
            val colorScheme = remember(accentArgb, darkTheme) {
                buildEquXColorScheme(Color(accentArgb), darkTheme)
            }

            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalAppScale provides appScale,
                LocalDensity provides Density(
                    density = baseDensity.density * appScale,
                    fontScale = baseDensity.fontScale,
                )
            ) {
            MaterialTheme(colorScheme = colorScheme) {
                @Suppress("DEPRECATION")
                val topHeaderColor = AppUiTheme.topHeaderBackgroundColor()
                val window = (LocalContext.current as? ComponentActivity)?.window
                SideEffect {
                    @Suppress("DEPRECATION")
                    window?.statusBarColor = topHeaderColor.toArgb()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppUiTheme.appBackgroundColor()
                ) {
                    MainScreenContent(
                        service = serviceState.value,
                        fftDataState = fftDataState,
                        dataManager = dataManager,
                        onLanguageApplied = ::applyAppLanguage
                    )
                }
            }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Черновик живёт в общем на процесс PresetDataManager: при пересоздании Activity (язык, поворот)
        // он сохраняется. При закрытии приложения отбрасываем его (движок не трогаем — звучит как звучал),
        // иначе автопереключение стояло бы на паузе, пока жив процесс.
        if (isFinishing) PresetDataManager.getInstance(this).discardChanges()
        if (isBound) {
            equalizerService?.onFftDataUpdate = null
            unbindService(connection)
            isBound = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    service: EqualizerService?,
    fftDataState: State<FloatArray>,
    dataManager: PresetDataManager,
    onLanguageApplied: (AppLanguage) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Вкладка «Car» видна на ГУ (та же проверка, что у службы: флаг или android.car.Car)
    // или при «Принудительно включить режим машины» в скрытом меню.
    val isAutomotive = remember { AudioDeviceScanner.isAutomotive(context.applicationContext) }
    var carModeForced by remember { mutableStateOf(DebugFlags.carModeForced) }
    val tabs = remember(isAutomotive, carModeForced) {
        buildList {
            add("Presets"); add("EQ"); add("Compressor"); add("FX"); add("Limiter")
            if (isAutomotive || carModeForced) add("Car")
        }
    }

    val deviceScanner = remember { AudioDeviceScanner(context.applicationContext) }

    var isEnabled by remember { mutableStateOf(true) }
    var activeDeviceName by remember { mutableStateOf("") }

    // Мгновенный реактивный индекс вкладки
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    // Выключили «режим машины», стоя на вкладке Car, — уходим на последнюю существующую.
    LaunchedEffect(tabs.size) {
        if (selectedTabIndex >= tabs.size) selectedTabIndex = tabs.size - 1
    }
    var totalDragX by remember { mutableFloatStateOf(0f) }

    // Состояние активности визуализатора (По умолчанию ВЫКЛЮЧЕН)
    var isVisualizerActive by remember { mutableStateOf(false) }

    // Реальная скорость из CAR_SPEED (null, если данные ещё не приходили).
    var realSpeedKmh by remember { mutableStateOf<Float?>(null) }

    // Максимальная наблюдаемая скорость (для карточки статистики).
    var speedMaxKmh by remember { mutableStateOf<Float?>(null) }

    // Статусы проверки методов регулировки громкости (Method 2 / Method 3).
    var volumeMethod2Status by remember { mutableStateOf(VolumeMethodStatus.UNTESTED) }
    var volumeMethod3Status by remember { mutableStateOf(VolumeMethodStatus.UNTESTED) }

    // Состояние источников скорости (индикатор и «макс.» в селекторе на вкладке Car).
    var speedSourceInfos by remember { mutableStateOf<Map<SpeedSource, SpeedSourceInfo>>(emptyMap()) }

    // Кэшированный быстрый расчет изменений
    val hasUnsavedChanges by remember(dataManager.liveDraftPreset, dataManager.originalPreset) {
        derivedStateOf { dataManager.hasUnsavedChanges() }
    }

    // Лаунчер запроса всех необходимых прав при старте
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        service?.getAudioEngine()?.restartVisualizer(context)
        activeDeviceName = deviceScanner.getCurrentActiveOutputDevice().name
    }

    // Последний запрошенный набор отсутствующих прав. Нужен, чтобы:
    //  - перезапросить права после их отзыва в системных настройках;
    //  - не спамить диалогом при каждом onResume и после «Отклонить».
    var lastRequestedMissing by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun missingPermissions(): Set<String> {
        val missing = mutableSetOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return missing
    }

    fun requestMissingPermissionsIfNeeded() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            lastRequestedMissing = emptySet()
            service?.getAudioEngine()?.restartVisualizer(context)
            service?.getAudioEngine()?.setVisualizerCaptureEnabled(isVisualizerActive)
            return
        }
        if (missing != lastRequestedMissing) {
            lastRequestedMissing = missing
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Загрузка пользовательских пресетов и активного профиля
    LaunchedEffect(dataManager) {
        dataManager.activePresetFlow.collect { _ ->
            activeDeviceName = deviceScanner.getCurrentActiveOutputDevice().name
        }
    }

    // Восстановление сохраненного статуса визуализатора
    LaunchedEffect(dataManager) {
        // distinctUntilChanged: DataStore эмитит при записи любого ключа (служба пишет активный пакет
        // на каждый плей/паузу) — без него захват заново включался в фоне.
        dataManager.isVisualizerActiveFlow.distinctUntilChanged().collect { savedActive ->
            isVisualizerActive = savedActive
            service?.getAudioEngine()?.setVisualizerCaptureEnabled(savedActive)
        }
    }

    // Синхронизация главного тумблера On/Off с реальным состоянием движка.
    // Раньше UI читал isAudioProcessingEnabled() один раз и мог показывать On,
    // хотя движок после асинхронной загрузки DataStore оставался Off.
    LaunchedEffect(dataManager) {
        dataManager.isEngineEnabledFlow.collect { engineOn ->
            isEnabled = engineOn
        }
    }

    // Подписка на выбранный язык, чтобы dataManager.appLanguage загрузился при старте.
    LaunchedEffect(dataManager) {
        dataManager.appLanguageFlow.collect { }
    }

    // Подписка на режим автопереключения, чтобы UI не показывал дефолтное значение
    // до того, как DataStore отдаст сохранённый флаг (иначе тумблер «слетает» после рестарта).
    LaunchedEffect(dataManager) {
        dataManager.isAutoSwitchEnabledFlow.collect { }
    }

    // Наблюдатель за жизненным циклом приложения
    val lifecycleOwner = LocalLifecycleOwner.current

    // Проверка прав NotificationListenerService
    var isListenerEnabled by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    var showAutostartDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentSettings by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTransitionDialog by remember { mutableStateOf(false) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    // Скрытое меню: три быстрых нажатия на карточку аудиоустройства.
    var showDebugDialog by remember { mutableStateOf(false) }

    // Одноразовый расчёт рекомендованного масштаба на первом запуске
    // (если пользователь ещё не трогал настройку масштаба).
    LaunchedEffect(dataManager) {
        dataManager.ensureAutoAppScaleInitialized()
    }

    // Первый запуск: сначала спрашиваем про автозапуск, затем (при необходимости) про доступ к уведомлениям.
    val needsAutostartPrompt = remember { OemAutostart.needsAutostartPrompt(context) }
    LaunchedEffect(dataManager, needsAutostartPrompt) {
        val autostartShown = dataManager.autostartPromptShownFlow.first()
        if (!autostartShown) {
            showAutostartDialog = true
        } else if (!isListenerEnabled) {
            showNotificationDialog = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                isListenerEnabled = enabled
                if (enabled) showNotificationDialog = false
                // Перепроверяем права: если пользователь отозвал их в настройках — перезапросим.
                requestMissingPermissionsIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Если служба была убита без onDestroy (OEM-киллер vivo/iQOO) — просим включить автозапуск.
    // Только на китайских OEM; на стоковом Android и Automotive это не нужно.
    LaunchedEffect(dataManager, needsAutostartPrompt) {
        dataManager.unexpectedServiceDeathFlow.collect { unexpected ->
            if (unexpected && needsAutostartPrompt) {
                showAutostartDialog = true
                coroutineScope.launch { dataManager.setUnexpectedServiceDeath(false) }
            }
        }
    }

    if (showAutostartDialog) {
        ScaledAlertDialog(
            onDismissRequest = {
                // Не помечаем «показан» при случайном закрытии — предложим снова при следующем запуске.
                showAutostartDialog = false
                if (!isListenerEnabled) showNotificationDialog = true
            },
            title = { Text(stringResource(R.string.autostart_title)) },
            text = { Text(stringResource(R.string.autostart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutostartDialog = false
                    coroutineScope.launch { dataManager.setAutostartPromptShown(true) }
                    openAutostartSettings(context)
                    if (!isListenerEnabled) showNotificationDialog = true
                }) {
                    Text(stringResource(R.string.autostart_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // «Позже» тоже не подавляем навсегда — напомним при следующем запуске.
                    showAutostartDialog = false
                    if (!isListenerEnabled) showNotificationDialog = true
                }) {
                    Text(stringResource(R.string.later))
                }
            }
        )
    }

    if (showNotificationDialog) {
        ScaledAlertDialog(
            onDismissRequest = { showNotificationDialog = false },
            title = { Text(stringResource(R.string.notification_access_title)) },
            text = { Text(stringResource(R.string.notification_access_message)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    } catch (e: Exception) {
                        // На части ГУ экрана доступа к уведомлениям нет.
                        Log.w("equX_MainActivity", "Нет экрана доступа к уведомлениям: ${e.message}")
                    }
                }) {
                    Text(stringResource(R.string.notification_access_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationDialog = false }) {
                    Text(stringResource(R.string.later))
                }
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onTransition = {
                showSettings = false
                showTransitionDialog = true
            },
            onTheme = {
                showSettings = false
                showThemeDialog = true
            },
            onColors = {
                showSettings = false
                showAccentSettings = true
            },
            onLanguage = {
                showSettings = false
                showLanguageDialog = true
            },
            onScale = {
                showSettings = false
                showScaleDialog = true
            },
            onReport = {
                showSettings = false
                showReportDialog = true
            },
        )
    }

    if (showReportDialog) {
        ReportDialog(
            onDismiss = { showReportDialog = false },
            onBack = {
                showReportDialog = false
                showSettings = true
            },
        )
    }

    if (showDebugDialog) {
        DebugDialog(
            carModeForced = carModeForced,
            onCarModeForcedChange = { checked ->
                carModeForced = checked
                DebugFlags.carModeForced = checked
            },
            onDismiss = { showDebugDialog = false },
        )
    }

    if (showTransitionDialog) {
        TransitionSettingsDialog(
            dataManager = dataManager,
            onDismiss = { showTransitionDialog = false },
            onBack = {
                showTransitionDialog = false
                showSettings = true
            },
        )
    }

    if (showScaleDialog) {
        ScaleDialog(
            dataManager = dataManager,
            onDismiss = { showScaleDialog = false },
            onBack = {
                showScaleDialog = false
                showSettings = true
            },
        )
    }

    if (showLanguageDialog) {
        LanguageDialog(
            currentLanguage = resolveCurrentAppLanguage(context, dataManager.appLanguage),
            onDismiss = { showLanguageDialog = false },
            onBack = {
                showLanguageDialog = false
                showSettings = true
            },
            onSelect = { language ->
                showLanguageDialog = false
                coroutineScope.launch {
                    dataManager.setAppLanguage(language)
                    onLanguageApplied(language)
                }
            }
        )
    }

    if (showThemeDialog) {
        ThemeModeDialog(
            currentMode = dataManager.themeMode,
            onDismiss = { showThemeDialog = false },
            onBack = {
                showThemeDialog = false
                showSettings = true
            },
            onSelect = { mode ->
                coroutineScope.launch { dataManager.setThemeMode(mode) }
                showThemeDialog = false
            }
        )
    }

    if (showAccentSettings) {
        AccentColorDialog(
            selectedArgb = dataManager.accentColor,
            onDismiss = { showAccentSettings = false },
            onBack = {
                showAccentSettings = false
                showSettings = true
            },
            onSelect = { argb ->
                coroutineScope.launch { dataManager.setAccentColor(argb) }
                showAccentSettings = false
            }
        )
    }

    // Подписка на реальную скорость из CAR_SPEED (не мокаем: null остаётся null).
    // Максимум считаем прямо здесь — так «Макс» точно накопится, независимо от
    // порядка подписки/восстановления в сервисе.
    DisposableEffect(service) {
        service?.onRealSpeedChanged = { speed ->
            realSpeedKmh = speed
            if (speed != null) {
                speedMaxKmh = maxOf(speedMaxKmh ?: speed, speed)
            }
        }
        realSpeedKmh = service?.lastRealSpeedKmh
        speedMaxKmh = service?.lastSpeedMaxKmh
        onDispose {
            service?.onRealSpeedChanged = null
        }
    }

    DisposableEffect(service) {
        service?.onSpeedSourcesChanged = { speedSourceInfos = service.speedSourceInfos }
        speedSourceInfos = service?.speedSourceInfos ?: emptyMap()
        onDispose { service?.onSpeedSourcesChanged = null }
    }

    // Вкладка Car открыта и экран на переднем плане — служба включает GPS в режиме диагностики
    // (на телефоне — при включённом Car, независимо от музыки).
    val carTabSelected = tabs.getOrNull(selectedTabIndex) == "Car"
    DisposableEffect(lifecycleOwner, carTabSelected) {
        fun update() {
            EqualizerService.carScreenVisible =
                carTabSelected && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycleOwner.lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            EqualizerService.carScreenVisible = false
        }
    }

    // Подписка на результаты проверки методов регулировки громкости (Method 2 / Method 3).
    DisposableEffect(service) {
        service?.onVolumeMethodStatusChanged = {
            volumeMethod2Status = service.volumeMethod2Status
            volumeMethod3Status = service.volumeMethod3Status
        }
        volumeMethod2Status = service?.volumeMethod2Status ?: VolumeMethodStatus.UNTESTED
        volumeMethod3Status = service?.volumeMethod3Status ?: VolumeMethodStatus.UNTESTED
        onDispose {
            service?.onVolumeMethodStatusChanged = null
        }
    }

    DisposableEffect(lifecycleOwner, service) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                // Мягко выключаем захват, но НЕ release объект. Флаг «UI не виден» в движке
                // не даёт фоновым событиям (пауза/плей, смена маршрута, сторож) включить его снова.
                service?.getAudioEngine()?.setVisualizerUiVisible(false)
            } else if (event == Lifecycle.Event.ON_RESUME) {
                activeDeviceName = deviceScanner.getCurrentActiveOutputDevice().name
                // Полный перезапуск (внутри setVisualizerUiVisible) вместо мягкого re-enable:
                // на session 0 повторное .enabled не всегда возобновляет захват на Automotive.
                service?.getAudioEngine()?.setVisualizerUiVisible(true, context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Наблюдатель за сменой аудиоустройств
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                activeDeviceName = deviceScanner.getCurrentActiveOutputDevice().name
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                activeDeviceName = deviceScanner.getCurrentActiveOutputDevice().name
            }
        }
        manager.registerAudioDeviceCallback(callback, null)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                activeDeviceName = deviceScanner.getCurrentActiveOutputDevice().name
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        onDispose {
            manager.unregisterAudioDeviceCallback(callback)
            context.unregisterReceiver(receiver)
        }
    }

    // Автоматическая проверка прав при запуске.
    // НЕ читаем isEnabled из isAudioProcessingEnabled(): сервис подгружает реальное
    // состояние из DataStore асинхронно и в этот момент может вернуть дефолтное true,
    // из-за чего мастер-тоггл показывал On при реально выключенном движке.
    // Источник правды для UI — dataManager.isEngineEnabledFlow (см. выше).
    LaunchedEffect(service) {
        service?.let {
            activeDeviceName = deviceScanner.getCurrentActiveOutputDevice().name
            requestMissingPermissionsIfNeeded()
        }
    }

    if (service == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AppUiTheme.accentColor())
        }
        return
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var headerVisible by remember { mutableStateOf(true) }

    // В landscape шапка схлопывается при свайпе вниз по контенту и остаётся скрытой.
    // Возвращается только свайпом вниз по строке вкладок (TabsBar).
    val collapseConnection = remember(isLandscape) {
        if (!isLandscape) null
        else object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0) headerVisible = false
                return Offset.Zero
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppUiTheme.appBackgroundColor())
            .then(if (collapseConnection != null) Modifier.nestedScroll(collapseConnection) else Modifier)
    ) {
        // Закрашивание области статус-бара
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(AppUiTheme.topHeaderBackgroundColor())
        )

        AnimatedVisibility(
            visible = headerVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TopHeaderComponent(
                isEngineEnabled = isEnabled,
                activeDeviceName = activeDeviceName,
                fftData = fftDataState,
                isVisualizerActive = isVisualizerActive,
                hasUnsavedChanges = hasUnsavedChanges,
                onNavigateToPresets = { selectedTabIndex = 0 },
                onVisualizerToggle = { active ->
                    isVisualizerActive = active
                    coroutineScope.launch {
                        dataManager.setVisualizerActiveState(active)
                    }
                    service.getAudioEngine()?.setVisualizerCaptureEnabled(active)
                },
                onOpenAccentSettings = { showSettings = true },
                onDeviceTripleTap = { showDebugDialog = true },
                onEngineToggle = { checked ->
                    isEnabled = checked
                    service.setAudioProcessingEnabled(checked)
                    coroutineScope.launch { dataManager.setEngineEnabled(checked) }

                    val bootComponent = ComponentName(context, BootReceiver::class.java)
                    val componentState = if (checked) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    context.packageManager.setComponentEnabledSetting(
                        bootComponent,
                        componentState,
                        PackageManager.DONT_KILL_APP
                    )
                },
            )
        }

        TabsBar(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it },
            onTabsTouched = { headerVisible = true },
            onTabsSwipeUp = { headerVisible = false },
            showHeaderHandle = isLandscape,
            headerVisible = headerVisible
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppUiTheme.appBackgroundColor())
                // Ключ tabs.size: иначе жест держит список вкладок с первой композиции
                // (после появления Car по отладочной галочке до неё нельзя было долистать).
                .pointerInput(tabs.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { totalDragX = 0f },
                        onDragEnd = {
                            if (totalDragX < -90f && selectedTabIndex < tabs.size - 1) {
                                selectedTabIndex++
                            } else if (totalDragX > 90f && selectedTabIndex > 0) {
                                selectedTabIndex--
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDragX += dragAmount
                        }
                    )
                }
        ) {
            AnimatedContent(
                targetState = selectedTabIndex,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut()
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = "TabContentTransition"
            ) { page ->
                val audioEngine = service.getAudioEngine()

                when (page) {
                    0 -> PresetScreen(
                        dataManager = dataManager,
                        onPresetActivated = { activatedPreset ->
                            audioEngine?.applyFullPreset(activatedPreset, smooth = false)
                        }
                    )

                    1 -> EqualizerScreen(
                        dataManager = dataManager,
                        onEqStatusChanged = {
                            audioEngine?.applyFullPreset(dataManager.liveDraftPreset, smooth = false)
                        },
                        onBandGainChanged = { index, gainDb ->
                            val freq = if (index < dataManager.liveDraftPreset.equalizer.customFrequenciesHz.size) dataManager.liveDraftPreset.equalizer.customFrequenciesHz[index] else audioEngine?.bandsFrequencies?.getOrNull(index) ?: 31f
                            audioEngine?.setBandFrequencyAndGain(index, freq, gainDb)
                        },
                        onBandFrequencyChanged = { index, freqHz ->
                            val gain = if (index < dataManager.liveDraftPreset.equalizer.gainsDb.size) dataManager.liveDraftPreset.equalizer.gainsDb[index] else 0f
                            audioEngine?.setBandFrequencyAndGain(index, freqHz, gain)
                        }
                    )

                    // Вкладка 3: Инженерный компрессор
                    2 -> CompressorScreen(
                        dataManager = dataManager,
                        onCompressorStatusChanged = {
                            audioEngine?.applyFullPreset(dataManager.liveDraftPreset, smooth = false)
                        },
                        onCompressorParamsChanged = { index, params ->
                            val masterCompEnabled = dataManager.liveDraftPreset.compressor.isEnabled
                            audioEngine?.setComplexCompressor(
                                bandIndex = index,
                                thresholdDb = params.thresholdDb,
                                ratioValue = params.ratio,
                                attackMs = params.attackMs,
                                releaseMs = params.releaseMs,
                                kneeDb = params.kneeDb,
                                noiseGateThresholdDb = params.noiseThresholdDb,
                                expanderRatio = params.expanderRatio,
                                preGainDb = params.preGainDb,
                                postGainDb = params.postGainDb,
                                isEnabled = masterCompEnabled && params.isPreEqEnabled
                            )
                        }
                    )

                    3 -> SpatialFxScreen(
                        dataManager = dataManager,
                        onFxEnabledChanged = { enabled ->
                            audioEngine?.setFxEnabled(enabled)
                        },
                        onSpatialChanged = { enabled, width, air ->
                            audioEngine?.setSpatial(enabled, width, air)
                            service.scheduleSpatialWidthCheck()
                        },
                        onLoudnessChanged = { config ->
                            audioEngine?.setLoudness(config)
                        },
                        loudnessStatus = audioEngine?.loudnessStatus,
                        spatialWidthStatus = audioEngine?.spatialWidthStatus,
                    )

                    // Вкладка 5: Финальный защитный жесткий лимитер
                    4 -> LimiterScreen(
                        dataManager = dataManager,
                        // Тумблер и Out Gain есть и в Simple: берём то, что реально звучит
                        // (в Simple — формулу интенсивности), а не Complex-поля черновика.
                        onLimiterStatusChanged = { enabled ->
                            val p = effectiveLimiterParams(dataManager.liveDraftPreset)
                            audioEngine?.setLimiterParams(
                                isEnabled = enabled,
                                thresholdDb = p.thresholdDb,
                                releaseMs = p.releaseMs,
                                postGainDb = dataManager.liveDraftPreset.limiter.postGainDb,
                                attackMs = p.attackMs,
                                ratio = p.ratio
                            )
                        },
                        onLimiterThresholdChanged = { threshold ->
                            audioEngine?.setLimiterParams(
                                isEnabled = dataManager.liveDraftPreset.limiter.isEnabled,
                                thresholdDb = threshold,
                                releaseMs = dataManager.liveDraftPreset.limiter.releaseMs,
                                postGainDb = dataManager.liveDraftPreset.limiter.postGainDb,
                                attackMs = dataManager.liveDraftPreset.limiter.attackMs,
                                ratio = dataManager.liveDraftPreset.limiter.ratio
                            )
                        },
                        onLimiterPostGainChanged = { postGain ->
                            val p = effectiveLimiterParams(dataManager.liveDraftPreset)
                            audioEngine?.setLimiterParams(
                                isEnabled = dataManager.liveDraftPreset.limiter.isEnabled,
                                thresholdDb = p.thresholdDb,
                                releaseMs = p.releaseMs,
                                postGainDb = postGain,
                                attackMs = p.attackMs,
                                ratio = p.ratio
                            )
                        },
                        onLimiterReleaseChanged = { release ->
                            audioEngine?.setLimiterParams(
                                isEnabled = dataManager.liveDraftPreset.limiter.isEnabled,
                                thresholdDb = dataManager.liveDraftPreset.limiter.thresholdDb,
                                releaseMs = release,
                                postGainDb = dataManager.liveDraftPreset.limiter.postGainDb,
                                attackMs = dataManager.liveDraftPreset.limiter.attackMs,
                                ratio = dataManager.liveDraftPreset.limiter.ratio
                            )
                        },
                        onLimiterAttackChanged = { attack ->
                            audioEngine?.setLimiterParams(
                                isEnabled = dataManager.liveDraftPreset.limiter.isEnabled,
                                thresholdDb = dataManager.liveDraftPreset.limiter.thresholdDb,
                                releaseMs = dataManager.liveDraftPreset.limiter.releaseMs,
                                postGainDb = dataManager.liveDraftPreset.limiter.postGainDb,
                                attackMs = attack,
                                ratio = dataManager.liveDraftPreset.limiter.ratio
                            )
                        },
                        onLimiterRatioChanged = { ratioValue ->
                            audioEngine?.setLimiterParams(
                                isEnabled = dataManager.liveDraftPreset.limiter.isEnabled,
                                thresholdDb = dataManager.liveDraftPreset.limiter.thresholdDb,
                                releaseMs = dataManager.liveDraftPreset.limiter.releaseMs,
                                postGainDb = dataManager.liveDraftPreset.limiter.postGainDb,
                                attackMs = dataManager.liveDraftPreset.limiter.attackMs,
                                ratio = ratioValue
                            )
                        },
                        onLimiterIntensityChanged = { intensity ->
                            val p = deriveLimiterParams(intensity)
                            audioEngine?.setLimiterParams(
                                isEnabled = dataManager.liveDraftPreset.limiter.isEnabled,
                                thresholdDb = p.thresholdDb,
                                releaseMs = p.releaseMs,
                                postGainDb = dataManager.liveDraftPreset.limiter.postGainDb,
                                attackMs = p.attackMs,
                                ratio = p.ratio
                            )
                        },
                        onLimiterModeChanged = { _ ->
                            coroutineScope.launch(Dispatchers.IO) {
                                audioEngine?.applyFullPreset(dataManager.liveDraftPreset, smooth = false)
                            }
                        }
                    )

                    // Вкладка 5: Car (скоростная компенсация громкости и эквалайзера)
                    5 -> com.xz3t.equX.ui.screens.CarScreen(
                        dataManager = dataManager,
                        realSpeedKmh = realSpeedKmh,
                        speedMaxKmh = speedMaxKmh,
                        method2Status = volumeMethod2Status,
                        method3Status = volumeMethod3Status,
                        onCarChanged = { car ->
                            audioEngine?.applyCarConfig(car)
                        },
                        onMockSpeedChanged = { speed ->
                            service.setMockVehicleSpeed(speed)
                        },
                        onReProbeVolumeMethods = {
                            service.reProbeVolumeMethods()
                        },
                        checkHuSpeedVolume = {
                            service.readHuSpeedVolumeLevel("включение GALA", alwaysLog = true)
                        },
                        speedSourceInfos = speedSourceInfos,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabsBar(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onTabsTouched: () -> Unit = {},
    onTabsSwipeUp: () -> Unit = {},
    showHeaderHandle: Boolean = false,
    headerVisible: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppUiTheme.topHeaderBackgroundColor())
            .zIndex(1f),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (showHeaderHandle) {
                        Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    if (dragAmount > 0f) {
                                        // Свайп вниз — раскрыть шапку.
                                        onTabsTouched()
                                        change.consume()
                                    } else if (dragAmount < 0f) {
                                        // Свайп вверх — скрыть шапку.
                                        onTabsSwipeUp()
                                        change.consume()
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTabIndex == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else AppUiTheme.activeHeaderColor()
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onTabSelected(index)
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        if (showHeaderHandle) {
            Icon(
                imageVector = if (headerVisible) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 10.dp)
                    .size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
    }
}

/**
 * Собирает цветовую схему equX с реактивным акцентным цветом и поддержкой ночной/дневной темы.
 * primaryContainer затемняется/осветляется от акцента, а контрастный onPrimary выбирается по яркости.
 */
private fun buildEquXColorScheme(accent: Color, darkTheme: Boolean): ColorScheme {
    val onPrimary = if (accent.luminance() > 0.5f) Color(0xFF1A1A1E) else Color.White
    return if (darkTheme) {
        val container = Color(
            red = accent.red * 0.25f,
            green = accent.green * 0.25f,
            blue = accent.blue * 0.25f,
            alpha = 1f
        )
        darkColorScheme(
            primary = accent,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = Color(0xFFE3E3E8),
            background = Color(0xFF0E0E11),
            onBackground = Color(0xFFE3E3E8),
            surface = Color(0xFF16161A),
            onSurface = Color(0xFFE3E3E8),
            surfaceVariant = Color(0xFF1E1E24),
            onSurfaceVariant = Color(0xFFA0A0A8),
            surfaceContainerLowest = Color(0xFF18181C),
            surfaceContainerLow = Color(0xFF222228),
            surfaceContainer = Color(0xFF2C2C34),
            surfaceContainerHigh = Color(0xFF33333C),
            surfaceContainerHighest = Color(0xFF3A3A44),
        )
    } else {
        val container = Color(
            red = accent.red * 0.3f + 0.7f,
            green = accent.green * 0.3f + 0.7f,
            blue = accent.blue * 0.3f + 0.7f,
            alpha = 1f
        )
        lightColorScheme(
            primary = accent,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = Color(0xFF111418),
            background = Color(0xFFE0E3E8),
            onBackground = Color(0xFF111418),
            surface = Color(0xFFE7E9ED),
            onSurface = Color(0xFF111418),
            surfaceVariant = Color(0xFFD3D7DD),
            onSurfaceVariant = Color(0xFF3A4048),
            surfaceContainerLowest = Color(0xFFFCFDFE),
            surfaceContainerLow = Color(0xFFF1F3F6),
            surfaceContainer = Color(0xFFE6E9ED),
            surfaceContainerHigh = Color(0xFFDADEE4),
            surfaceContainerHighest = Color(0xFFCED3DA),
        )
    }
}

/** Открывает системный экран автозапуска (или настройки приложения) в зависимости от прошивки. */
private fun openAutostartSettings(context: Context) {
    OemAutostart.open(context)
}

/** Возвращает фактическую текущую локаль приложения (LocaleManager на 13+, иначе DataStore). */
private fun resolveCurrentAppLanguage(context: Context, fallback: AppLanguage): AppLanguage {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val tags = localeManager.applicationLocales.toLanguageTags()
        return AppLanguage.fromTag(tags.takeIf { it.isNotBlank() })
    }
    return fallback
}
