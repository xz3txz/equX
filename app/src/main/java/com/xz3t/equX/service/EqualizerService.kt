package com.xz3t.equX.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xz3t.equX.R
import com.xz3t.equX.audio.AudioEngine
import com.xz3t.equX.data.AudioDeviceScanner
import com.xz3t.equX.data.CarConfig
import com.xz3t.equX.data.DEFAULT_PRESET_NAME
import com.xz3t.equX.data.SpeedSource
import com.xz3t.equX.data.SpeedSourceInfo
import com.xz3t.equX.data.EquXLog
import com.xz3t.equX.data.PresetDataManager
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.VolumeMethod
import com.xz3t.equX.data.VolumeMethodStatus
import com.xz3t.equX.data.selectPresetForAutoSwitch
import com.xz3t.equX.receiver.AudioSessionReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Method

class EqualizerService : Service() {

    companion object {
        const val TAG_SPEED = "equX_CarSpeed"

        // Группа media CarAudioManager и системный broadcast громкости (скрытый AudioManager API).
        private const val MEDIA_VOLUME_GROUP = 0
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        // Гистерезис ступеней GALA по скорости: шаг громкости меняется, только когда
        // скорость ушла за границу ступени дальше, чем на столько км/ч (убирает дребезг).
        private const val GALA_HYSTERESIS_KMH = 3f

        // Пока звук замьючен, проба методов громкости откладывается; повтор не чаще раза в 30 с.
        private const val VOLUME_PROBE_RETRY_MS = 30_000L

        // Состояние GALA на диске — чтобы после убийства процесса с поднятой громкостью
        // не снять «базу» с уже поднятого значения (буст копился бы от рестарта к рестарту).
        private const val GALA_STATE_PREFS = "gala_volume_state"

        // Как часто перечитывать штатную регулировку громкости по скорости ГУ (пишем в лог при смене).
        // Основной сигнал — колбэк ГУ; опрос — страховка, если подписка не оформилась.
        private const val HU_SPEED_VOLUME_CHECK_MS = 60_000L

        // Минимальный интервал между напоминаниями о включении автозапуска после
        // неожиданной смерти службы (5 минут). Защищает от спама диалогом.
        private const val DEATH_PROMPT_COOLDOWN_MS = 5 * 60 * 1000L

        // Сколько держим последний известный плеер после пропажи признака «реально играет».
        // Достаточно, чтобы пережить перемотку/seek (~600 мс), но не настолько, чтобы
        // «залипнуть» на Spotify при переходе в Instagram.
        private const val MEDIA_STICKY_GRACE_MS = 1_500L

        /**
         * Открыта ли вкладка Car (ставит MainActivity). На телефоне это режим диагностики GPS:
         * при включённом Car GPS работает независимо от музыки, чтобы проверить, что он отдаёт скорость.
         */
        @Volatile
        var carScreenVisible: Boolean = false
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var dataManager: PresetDataManager

    private var isProcessingEnabled = true
    @Volatile
    private var lastActivePackageName: String? = null

    // Когда в последний раз был достоверно определён играющий пакет. Нужен только чтобы
    // отличить короткую паузу при перемотке (пакет вернётся через ~600 мс) от реального
    // переключения на приложение без MediaSession (Instagram): в последнем случае
    // getActiveMediaPackage() вернёт null надолго, и мы сбрасываемся на дефолт.
    @Volatile
    private var lastConfirmedPlayingAt = 0L

    // Ключ последнего применённого состояния. Позволяет безопасно вызывать
    // applyPresetForCurrentState() по таймеру, не переприменяя DSP без изменений.
    @Volatile
    private var lastAppliedStateKey: String? = null

    // Единственный глобальный аудио-движок (session 0). На целевом устройстве (vivo)
    // AudioFlinger не поддерживает эффекты на конкретных сессиях (status=-38/ENOSYS),
    // поэтому мультидвижки откатывались на session 0 и создавали несколько глобальных
    // цепочек эффектов -> двойное наложение (x2). Один глобальный движок снимает проблему.
    private val audioEngine = AudioEngine()

    // Сериализация тяжёлого применения пресетов, чтобы не накладывались друг на друга из разных потоков.
    private val applyPresetMutex = Mutex()

    // Debounce реакции на частые события onPlaybackConfigChanged (скраббинг/перемотка).
    // Плеер при перемотке может много раз подряд пересоздавать AudioTrack; реагируем
    // один раз после затихания, чтобы не перегружать аудио-стек тяжёлым пересчётом профиля.
    private var playbackChangedJob: Job? = null
    private var visualizerCheckJob: Job? = null
    private var bindingsCleanupReceiver: BroadcastReceiver? = null
    private val playbackChangedDebounceMs = 600L

    // Пересоздание эффектов после смены устройства вывода (см. scheduleEffectsReattach).
    private val knownOutputDeviceIds = mutableSetOf<Int>()
    @Volatile
    private var effectsReattachPending = false
    private var effectsReattachReason = ""
    private var effectsReattachJob: Job? = null
    private var spatialWidthCheckJob: Job? = null
    private val outputSettleDelayMs = 1_500L

    // Прокси коллбэка для MainActivity, передающий данные спектра только от активного плеера
    var onFftDataUpdate: ((FloatArray) -> Unit)? = null

    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    // Громкость для тонкомпенсации: METHOD_3 — колбэк CarAudioManager, METHOD_2 — broadcast.
    @Volatile
    private var loudnessVolumeMethod = VolumeMethod.METHOD_3
    private var volumeChangedReceiver: BroadcastReceiver? = null

    // Скорость (ECARX / VHAL / GPS, фильтр, статистика источников) — отдельный класс.
    private val speedMonitor = VehicleSpeedMonitor(this, serviceScope, object : VehicleSpeedMonitor.Host {
        override fun carConfig() = audioEngine.carConfig
        override fun onEffectiveSpeed(kmh: Float) {
            audioEngine.setVehicleSpeed(kmh)
            applyGalaSystemVolume(kmh)
        }
        override fun onMonitorTick() {
            if (audioEngine.loudnessEnabled) ensureLoudnessVolumeWatch()
            if (audioEngine.carEnabled) {
                maybeRunVolumeMethodProbe()
                maybeCheckHuSpeedVolume()
            }
        }
        override fun onAutomotiveDetected() = maybeRunVolumeMethodProbe()
        override fun isPlaybackLikelyActive() = this@EqualizerService.isPlaybackLikelyActive()
        override fun setForegroundLocationType(enabled: Boolean) = this@EqualizerService.setForegroundLocationType(enabled)
        override fun onMain(block: () -> Unit) = this@EqualizerService.onMain(block)
    })

    // API скорости для UI — делегирует монитору.
    val lastRealSpeedKmh: Float? get() = speedMonitor.lastRealSpeedKmh
    val lastSpeedMaxKmh: Float? get() = speedMonitor.lastSpeedMaxKmh
    var onRealSpeedChanged: ((Float?) -> Unit)?
        get() = speedMonitor.onRealSpeedChanged
        set(value) { speedMonitor.onRealSpeedChanged = value }
    val speedSourceInfos: Map<SpeedSource, SpeedSourceInfo> get() = speedMonitor.speedSourceInfos
    var onSpeedSourcesChanged: (() -> Unit)?
        get() = speedMonitor.onSpeedSourcesChanged
        set(value) { speedMonitor.onSpeedSourcesChanged = value }

    /** Имитация скорости (отладка) — через тот же фильтр, что и реальная скорость. */
    fun setMockVehicleSpeed(speedKmh: Float) = speedMonitor.setMockVehicleSpeed(speedKmh)

    // Метод громкости GALA — глобальная настройка (DataStore), не в пресете.
    @Volatile
    private var selectedVolumeMethod = VolumeMethod.AUTO

    // Проверка методов регулировки громкости (Method 2 / Method 3 / Auto).
    @Volatile
    var volumeMethod2Status: VolumeMethodStatus = VolumeMethodStatus.UNTESTED
        private set
    @Volatile
    var volumeMethod3Status: VolumeMethodStatus = VolumeMethodStatus.UNTESTED
        private set
    var onVolumeMethodStatusChanged: (() -> Unit)? = null
    private var volumeMethodProbeRun = false
    private var volumeMethodProbeJob: Job? = null
    // Время (elapsedRealtime), раньше которого не повторяем отложенную из-за mute пробу.
    @Volatile
    private var volumeMethodProbeRetryAt = 0L
    // Проба на время сдвигает громкость на шаг — GALA в это время не должна принять
    // этот шаг за ручную регулировку. После пробы GALA пересчитывается в finally.
    @Volatile
    private var volumeMethodProbeRunning = false

    // Штатная регулировка громкости по скорости ГУ (ECARX PARAM_VOLUME_ADJUST_BY_SPEED):
    // последнее прочитанное значение (для лога при смене) и время следующей проверки.
    @Volatile
    private var lastHuSpeedVolume: CarVolumeController.HuSpeedVolume? = null
    @Volatile
    private var huSpeedVolumeCheckAt = 0L

    // Состояние GALA-громкости через системный volume-контроллер.
    // Когда выбран рабочий API-метод, громкость меняется реальным системным volume,
    // а не DSP-постгейном лимитера.
    private var galaVolumeMethod: VolumeMethod? = null
    // Сериализует применение GALA-громкости: его вызывают цикл скорости, имитация скорости,
    // смена Car-настроек и завершение пробы методов — с разных потоков.
    private val galaVolumeLock = Any()
    // Последняя скорость, по которой считалась GALA-громкость (для пересчёта при смене настроек).
    @Volatile
    private var lastGalaSpeedKmh = 0f

    private var galaNoMethodLogged = false
    private var galaMutedLogged = false
    // База — громкость пользователя без буста. «Виртуальная»: может быть < 1, если
    // пользователь убавил громкость ниже текущего буста (тогда target прижимается к 1).
    private var galaVolumeBase: Int? = null
    private var galaVolumeLastSet: Int? = null
    // Текущий буст в шагах (с гистерезисом) — на него сдвинута база в galaVolumeLastSet.
    private var galaVolumeBoost = 0
    private var galaVolumeMax: Int? = null
    private val galaStatePrefs by lazy { getSharedPreferences(GALA_STATE_PREFS, Context.MODE_PRIVATE) }

    /**
     * Возвращает единственный глобальный аудио-движок для MainActivity (крутилки и спектр).
     */
    fun getAudioEngine(): AudioEngine = audioEngine

    /**
     * Высчитывает правильный пресет по правилам Auto-Switch (Устройство + Приложение)
     */
    private fun calculatePresetFor(pkg: String): PresetModel {
        if (!::dataManager.isInitialized) return PresetModel(DEFAULT_PRESET_NAME)
        val scanner = AudioDeviceScanner(applicationContext)
        val activeDevice = scanner.getCurrentActiveOutputDevice()
        val activeSource = scanner.getCurrentActiveSource()
        val allPresets = dataManager.getAllPresets()

        return selectPresetForAutoSwitch(
            pkg = pkg,
            activeDeviceId = activeDevice.id,
            activeSourceId = activeSource?.id,
            allPresets = allPresets,
            defaultPresetName = DEFAULT_PRESET_NAME,
        ) ?: dataManager.createDefaultPreset(DEFAULT_PRESET_NAME)
    }

    /**
     * Применяет глобальный On/Off к единственному движку.
     */
    private fun applyEngineEnabled() {
        audioEngine.setEngineEnabled(isProcessingEnabled)
    }

    /**
     * Массовое применение правильных пресетов для всех активных закэшированных приложений (Например, при смене Bluetooth).
     * Тяжёлая DSP-работа выполняется в фоне и сериализуется, чтобы не блокировать UI-поток.
     */
    private fun applyPresetForCurrentState() {
        if (!::dataManager.isInitialized) return
        // Не применяем auto-switch, пока пресеты не загружены из DataStore.
        // Иначе calculatePresetFor() увидит пустой userPresetsMap и перезапишет
        // сохранённый пресет дефолтным — отсюда «разный звук» после перезагрузки.
        if (!dataManager.isDataLoaded) return
        // Пока сохранённый флаг «Авто/Ручной» не загружен, isAutoSwitchEnabled равен
        // дефолтному false. На этом окне авто-режим может перезатереть выбранный вручную
        // пресет дефолтным при перезапуске — пропускаем применение до разрешения флага.
        if (!dataManager.isAutoSwitchLoaded && dataManager.isAutoSwitchEnabled) return
        serviceScope.launch(Dispatchers.IO) {
            applyPresetMutex.withLock {
                applyPresetForCurrentStateInternal()
            }
        }
    }

    private suspend fun applyPresetForCurrentStateInternal() {
        // Определяем, кто реально играет, через MediaSessionManager. Этот источник работает
        // даже для приложений, которые не шлют ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
        // в отличие от сканера по UID, где hidden API заблокирован.
        val mediaPkg = MediaNotificationService.getActiveMediaPackage(this)
        val musicActive = audioManager?.isMusicActive() ?: true
        val now = System.currentTimeMillis()

        if (mediaPkg != null) {
            lastConfirmedPlayingAt = now
        }

        // «Липкий» пакет держим только на короткую паузу (перемотка/seek ~600 мс), пока
        // аудио реально идёт. Если реального медиа-плеера нет дольше GRACE_MS — значит
        // играет приложение без MediaSession (Instagram), и нужно упасть на GLOBAL/дефолт.
        val graceActive = now - lastConfirmedPlayingAt <= MEDIA_STICKY_GRACE_MS
        val effectivePkg = when {
            mediaPkg != null -> mediaPkg
            musicActive && graceActive -> lastActivePackageName?.takeIf { it.isNotBlank() && it != "GLOBAL" } ?: "GLOBAL"
            else -> "GLOBAL"
        }

        // Обновляем активный пакет только при реальной смене (защита от лишних рекурсий по flow).
        if (effectivePkg != lastActivePackageName) {
            lastActivePackageName = effectivePkg
            withContext(Dispatchers.Main) {
                dataManager.setLastActivePackageName(effectivePkg)
            }
        }

        // Один глобальный движок (session 0) обрабатывает весь звук. Пакет нужен только
        // для выбора пресета в авто-режиме.
        if (!dataManager.isAutoSwitchEnabled) {
            // Ручной режим: применяем мгновенно, чтобы пользователь слышал результат сразу.
            val preset = dataManager.liveDraftPreset
            val stateKey = "manual|${preset.presetName}"
            if (stateKey != lastAppliedStateKey) {
                lastAppliedStateKey = stateKey
                audioEngine.applyFullPreset(preset, smooth = false)
            }
        } else if (dataManager.hasUnsavedChanges()) {
            // Автопереключение приостановлено: есть несохранённый черновик (сохранить/сбросить).
            // PresetDataManager общий с UI, поэтому черновик виден здесь напрямую.
            // lastAppliedStateKey не трогаем — после сохранения/сброса цикл применит нужный пресет.
        } else {
            // Автопереключение: применяем плавно (настраиваемая длительность).
            val targetPreset = calculatePresetFor(effectivePkg)
            val stateKey = "auto|$effectivePkg|${targetPreset.presetName}"
            if (stateKey != lastAppliedStateKey) {
                lastAppliedStateKey = stateKey
                audioEngine.applyFullPreset(targetPreset, smooth = true)

                if (dataManager.liveDraftPreset.presetName != targetPreset.presetName) {
                    EquXLog.i("equX_Service", "Автоматическое переключение пресета -> Пресет [${targetPreset.presetName}]")
                    withContext(Dispatchers.Main) {
                        dataManager.activatePreset(targetPreset)
                    }
                }
            }
        }

        applyEngineEnabled()
    }

    private val NOTIFICATION_ID = 909
    private val CHANNEL_ID = "equx_audio_processing_channel_accent"
    private val binder = LocalBinder()

    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    /**
     * Колбэки в UI (они пишут в состояние Compose) — на главном потоке: источники скорости, проба
     * громкости и колбэк VHAL работают на IO/binder-потоках. Спектр (30 fps) сюда не идёт намеренно.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // startForeground должен вызываться один раз. Повторный вызов на каждое OPEN/CLOSE
    // заставляет систему пересоздавать уведомление -> иконка в статусбаре «прыгает».
    @Volatile
    private var foregroundStarted = false

    inner class LocalBinder : Binder() {
        fun getService(): EqualizerService = this@EqualizerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        EquXLog.i("equX_Service", "=== Создание фоновой службы EqualizerService ===")

        dataManager = PresetDataManager.getInstance(this)

        // Журнал в файл. Служба стартует и без MainActivity (START_STICKY / BootReceiver).
        com.xz3t.equX.data.LogFileWriter.init(applicationContext)

        // Контекст нужен движку для проверки RECORD_AUDIO перед созданием Visualizer.
        audioEngine.attachContext(applicationContext)

        // Связываем спектр движка с UI. Раньше это делалось в getOrCreateEngine();
        // при переходе на один глобальный движок связку потеряли — visualizer молчал.
        audioEngine.onFftDataUpdate = { magnitudes ->
            onFftDataUpdate?.invoke(magnitudes)
        }

        // Любое применение CarConfig (черновик из UI, ручной/авто-выбор пресета) сразу
        // пересчитывает системную GALA-громкость, не дожидаясь смены скорости.
        audioEngine.onCarConfigApplied = {
            serviceScope.launch(Dispatchers.IO) { applyGalaSystemVolume(lastGalaSpeedKmh) }
        }

        // Детект неожиданной смерти: если предыдущий экземпляр не завершился корректно
        // (OEM-киллер vivo/iQOO убил процесс без onDestroy), просим пользователя включить
        // автозапуск. С защитой от спама: напоминаем не чаще раза в DEATH_PROMPT_COOLDOWN_MS.
        serviceScope.launch {
            try {
                val wasRunning = dataManager.serviceRunningFlow.first()
                if (wasRunning) {
                    Log.w("equX_Service", "Обнаружена неожиданная смерть предыдущего экземпляра службы (OEM-киллер?)")
                    val now = System.currentTimeMillis()
                    val lastPrompt = dataManager.lastUnexpectedDeathPromptAtFlow.first()
                    if (now - lastPrompt >= DEATH_PROMPT_COOLDOWN_MS) {
                        dataManager.setUnexpectedServiceDeath(true)
                        dataManager.setLastUnexpectedDeathPromptAt(now)
                    }
                }
            } catch (e: Exception) {
                Log.w("equX_Service", "Не удалось проверить liveness: ${e.message}")
            }
            dataManager.setServiceRunning(true)
        }

        startForegroundServiceInternal()

        try {
            val componentName = ComponentName(this, AudioSessionReceiver::class.java)
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            EquXLog.i("equX_Service", "Компонент AudioSessionReceiver успешно активирован")
        } catch (e: Exception) {
            Log.e("equX_Service", "Ошибка активации AudioSessionReceiver: ${e.message}")
        }

        // Подписка на On/Off
        serviceScope.launch {
            dataManager.isEngineEnabledFlow.collect { isEnabled ->
                if (isProcessingEnabled != isEnabled) {
                    isProcessingEnabled = isEnabled
                    applyEngineEnabled()
                    updateNotification()
                }
            }
        }

        // Настройки плавного перехода пресетов.
        serviceScope.launch {
            dataManager.smoothTransitionEnabledFlow.collect { enabled ->
                audioEngine.smoothTransitionEnabled = enabled
            }
        }
        serviceScope.launch {
            dataManager.smoothTransitionDurationMsFlow.collect { durationMs ->
                audioEngine.smoothTransitionDurationMs = durationMs.toLong()
            }
        }

        // Car-настройки звука — в пресете (applyFullPreset -> applyCarConfig). Глобальны только
        // источник скорости и метод громкости: это свойства машины/устройства, а не звука.
        serviceScope.launch {
            dataManager.speedSourceFlow.collect { source ->
                speedMonitor.selectedSpeedSource = source
                EquXLog.i(TAG_SPEED, "Источник скорости: $source")
            }
        }
        serviceScope.launch {
            dataManager.volumeMethodFlow.collect { method ->
                if (selectedVolumeMethod != method) {
                    selectedVolumeMethod = method
                    launch(Dispatchers.IO) { applyGalaSystemVolume(lastGalaSpeedKmh) }
                }
            }
        }

        // Подписка на изменения ползунков в UI.
        // Инициализация движка и первое применение профиля происходят ТОЛЬКО после
        // первой эмиссии activePresetFlow — к этому моменту userPresetsMap уже заполнен
        // и dataManager.isDataLoaded == true. Иначе при старте/рестарте эффекты
        // не применяются (applyPresetForCurrentState() рано выходит по !isDataLoaded).
        serviceScope.launch {
            var firstLoad = true
            dataManager.activePresetFlow.collect {
                if (firstLoad) {
                    firstLoad = false

                    // Восстанавливаем состояние движка без блокировки главного потока.
                    isProcessingEnabled = dataManager.isEngineEnabledFlow.first()

                    val bootCachePreset = dataManager.loadBootCachePreset()
                    if (bootCachePreset != null) {
                        EquXLog.i("equX_Service", "Мгновенное применение DE-пресета [${bootCachePreset.presetName}]")
                        audioEngine.applyFullPreset(bootCachePreset, smooth = false)
                    }
                    audioEngine.initAudioEffects(0)
                    applyEngineEnabled()
                    applyPresetForCurrentState()
                }

                // НЕ применяем пресет к движку из этого flow. Раньше это дублировало
                // применение (activatePreset в авто-режиме → flow → повторный smooth=false)
                // и обрывало плавный переход. Ручные изменения пресета применяются
                // напрямую колбэками экранов / onPresetActivated.
            }
        }

        serviceScope.launch {
            dataManager.lastActivePackageNameFlow.collect { pkg ->
                if (!pkg.isNullOrBlank()) {
                    lastActivePackageName = pkg
                    applyPresetForCurrentState()
                }
            }
        }

        serviceScope.launch {
            dataManager.isAutoSwitchEnabledFlow.collect { _ ->
                applyPresetForCurrentState()
            }
        }

        registerAudioPlaybackCallbackInternal()
        registerAudioDeviceChangeListener()
        registerBindingsCleanupReceiver()

        speedMonitor.start()
        audioEngine.onLoudnessVolumeNeeded = {
            serviceScope.launch(Dispatchers.IO) {
                ensureLoudnessVolumeWatch()
                refreshLoudnessVolume()
            }
        }

        MediaNotificationService.onMediaSessionStateChanged = {
            applyPresetForCurrentState()
            scheduleVisualizerCheck()
        }

        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Страховка: периодически переоцениваем профиль по публичному MediaSessionManager
                // (события плеера доходят не на всех прошивках).
                applyPresetForCurrentState()

                // Сторож визуализатора: на глобальной сессии он может «тихо» отвалиться
                // от потока. Если музыка играет, а данные не приходят дольше таймаута —
                // пересоздаём, чтобы спектр не зависал без ручного выкл/вкл.
                val playing = isPlaybackLikelyActive()
                if (playing) {
                    audioEngine.restartVisualizerIfStale(8_000L)
                }
                audioEngine.checkSpatialWidthStatus(playing)

                delay(5000)
            }
        }
    }

    /**
     * Играет ли что-то. isMusicActive на части прошивок врёт, поэтому дополнительно
     * смотрим STATE_PLAYING у MediaSession.
     */
    private fun isPlaybackLikelyActive(): Boolean {
        if (audioManager?.isMusicActive() == true) return true
        return MediaNotificationService.getActiveMediaSessions(this).any { it.isPlaying }
    }

    /**
     * После нажатия Play проверяем, что спектр ожил. Если Visualizer был создан до старта
     * воспроизведения (открыли приложение в тишине), на session 0 он может не подцепиться
     * к новому потоку — тогда пересоздаём, не дожидаясь периодического сторожа.
     * Сам движок ничего не делает, если UI не виден или визуализатор выключен.
     */
    private fun scheduleVisualizerCheck() {
        visualizerCheckJob?.cancel()
        visualizerCheckJob = serviceScope.launch(Dispatchers.IO) {
            delay(2_500L)
            if (isPlaybackLikelyActive()) {
                audioEngine.restartVisualizerIfStale(2_000L)
            }
        }
    }

    private fun registerAudioDeviceChangeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val manager = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)
                audioManager = manager
                if (manager != null) {
                    knownOutputDeviceIds.clear()
                    manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { knownOutputDeviceIds.add(it.id) }
                    audioDeviceCallback = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                            applyPresetForCurrentState()
                            // Первый вызов после регистрации перечисляет уже известные устройства — не реагируем.
                            val newSinks = addedDevices.orEmpty().filter { it.isSink && knownOutputDeviceIds.add(it.id) }
                            if (newSinks.isNotEmpty()) {
                                scheduleEffectsReattach("подключено: ${newSinks.joinToString { "${it.type}/${it.productName}" }}")
                            }
                        }
                        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                            applyPresetForCurrentState()
                            val goneSinks = removedDevices.orEmpty().filter { it.isSink && knownOutputDeviceIds.remove(it.id) }
                            if (goneSinks.isNotEmpty()) {
                                scheduleEffectsReattach("отключено: ${goneSinks.joinToString { "${it.type}/${it.productName}" }}")
                            }
                        }
                    }
                    manager.registerAudioDeviceCallback(audioDeviceCallback!!, null)
                }
            } catch (e: Exception) {
                Log.w("equX_Service", "Не удалось зарегистрировать AudioDeviceCallback: ${e.message}")
            }
        }

        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(Intent.ACTION_HEADSET_PLUG)
            }
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        applyPresetForCurrentState()
                    }
                },
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w("equX_Service", "Не удалось зарегистрировать приёмник BT/HEADSET: ${e.message}")
        }
    }

    /**
     * Чистка привязок пресетов, пока служба жива (в т.ч. в фоне, без открытого UI):
     * - PACKAGE_REMOVED (не обновление) → убрать привязки к удалённому приложению;
     * - отвязка Bluetooth (BOND_NONE) → убрать привязки к устройству вывода / источнику.
     * PresetDataManager общий с UI: чистка трогает только привязки, несохранённые правки звука остаются.
     */
    private fun registerBindingsCleanupReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!::dataManager.isInitialized || !dataManager.isDataLoaded) return
                when (intent?.action) {
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                        val pkg = intent.data?.schemeSpecificPart ?: return
                        EquXLog.i("equX_Service", "Приложение удалено: $pkg — убираю его привязки")
                        serviceScope.launch { dataManager.removeAppBindings(pkg) }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        if (bond != BluetoothDevice.BOND_NONE) return
                        val scanner = AudioDeviceScanner(applicationContext)
                        val devices = scanner.scanAvailableOutputDevices().map { it.id }.toSet()
                        val sources = scanner.scanAvailableSources().map { it.id }.toSet()
                        serviceScope.launch { dataManager.pruneMissingDeviceBindings(devices, sources) }
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply { addDataScheme("package") },
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            bindingsCleanupReceiver = receiver
        } catch (e: Exception) {
            Log.w("equX_Service", "Не удалось зарегистрировать чистку привязок: ${e.message}")
        }
    }

    /**
     * Отложенно реагирует на изменение конфигурации воспроизведения.
     *
     * Отменяет предыдущий запланированный пересчёт и планирует новый. Это сглаживает
     * всплески событий при скраббинге/перемотке трека, когда AudioTrack пересоздаётся
     * много раз подряд. Полный пересчёт профиля и лёгкий rebind выполняются один раз
     * после того, как поток событий затихнет.
     */
    private fun schedulePlaybackStateReevaluation() {
        playbackChangedJob?.cancel()
        playbackChangedJob = serviceScope.launch {
            delay(playbackChangedDebounceMs)
            applyPresetForCurrentState()
            if (!reattachEffectsIfPending()) {
                audioEngine.rebindGlobalEffects()
                audioEngine.restartVisualizerIfActive()
            }
            scheduleSpatialWidthCheck()
        }
    }

    /**
     * Смена устройства вывода (BT, проводные, USB). Цепочка эффектов session 0 сама за звуком
     * переезжает не на всех прошивках (vivo: осталась на динамике, музыка ушла в A2DP без эффектов),
     * поэтому пересоздаём эффекты. Делаем это, когда маршрут устоялся и музыка играет: глобальный
     * эффект политика вешает на выход, где сейчас музыка. Если тихо — ждём старта воспроизведения.
     */
    private fun scheduleEffectsReattach(reason: String) {
        EquXLog.i("equX_Service", "Смена устройства вывода ($reason) — эффекты будут пересозданы")
        effectsReattachReason = reason
        effectsReattachPending = true
        effectsReattachJob?.cancel()
        effectsReattachJob = serviceScope.launch {
            delay(outputSettleDelayMs)
            reattachEffectsIfPending()
        }
    }

    /**
     * Быстрая проверка индикатора «Ширины» (после включения «Пространства» или старта музыки),
     * чтобы не ждать периодического цикла в 5 с. Ждём, пока бандл успеет перейти в режим.
     */
    fun scheduleSpatialWidthCheck() {
        spatialWidthCheckJob?.cancel()
        spatialWidthCheckJob = serviceScope.launch(Dispatchers.IO) {
            delay(3_000L)
            audioEngine.checkSpatialWidthStatus(isPlaybackLikelyActive())
        }
    }

    /** @return true, если эффекты пересозданы. */
    private suspend fun reattachEffectsIfPending(): Boolean {
        if (!effectsReattachPending) return false
        val playing = withContext(Dispatchers.IO) { isPlaybackLikelyActive() }
        if (!playing) return false
        effectsReattachPending = false
        withContext(Dispatchers.IO) {
            // Visualizer пересоздаётся внутри initAudioEffects.
            audioEngine.reattachEffects("смена устройства вывода (${effectsReattachReason})")
        }
        return true
    }

    private fun registerAudioPlaybackCallbackInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val manager = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)
                audioManager = manager
                if (manager != null) {
                    playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                            super.onPlaybackConfigChanged(configs)
                            if (!configs.isNullOrEmpty()) {
                                schedulePlaybackStateReevaluation()
                            }
                        }
                    }
                    manager.registerAudioPlaybackCallback(playbackCallback!!, null)
                }
            } catch (e: Exception) {
                Log.w("equX_Service", "Не удалось зарегистрировать AudioPlaybackCallback: ${e.message}")
            }
        }
    }

    /**
     * Основной тип foreground-службы для текущей версии Android (без location).
     * Android 14+ — specialUse. НЕ mediaProcessing: на Android 15 у него лимит 6 ч за сутки
     * (onTimeout → крэш), а эквалайзер работает постоянно.
     */
    private fun baseForegroundServiceType(): Int = when {
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    /**
     * Страховка Android 15+: система вызывает при исчерпании лимита типа с таймаутом. С specialUse
     * не должно происходить; если всё же пришло — останавливаемся сами, иначе система уронит процесс.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e("equX_Service", "onTimeout foreground-службы (type=$fgsType) — останавливаюсь")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Добавляет/убирает тип `location` у уже запущенной foreground-службы: без него GPS в фоне не работает.
     * Повторный startForeground с тем же уведомлением только меняет типы (иконка не пересоздаётся).
     */
    private fun setForegroundLocationType(enabled: Boolean) {
        if (!foregroundStarted || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val type = if (enabled) {
            baseForegroundServiceType() or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            baseForegroundServiceType()
        }
        try {
            startForeground(NOTIFICATION_ID, createNotification(), type)
        } catch (e: Exception) {
            EquXLog.w(TAG_SPEED, "GPS: не удалось сменить тип foreground-службы (location=$enabled): ${e.message}")
        }
    }

    private fun startForegroundServiceInternal() {
        if (foregroundStarted) return
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, baseForegroundServiceType())
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e("equX_Service", "Ошибка startForeground с типом: ${e.message}, пробуем fallback")
            try {
                // Если система отклонила специфичный тип (частая проблема на Automotive/OEM),
                // падаем на обычный вызов без типа.
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            } catch (fallbackEx: Exception) {
                Log.e("equX_Service", "Критическая ошибка startForeground fallback: ${fallbackEx.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceInternal()
        // AudioSessionReceiver запускает службу по OPEN/CLOSE аудиосессии плеера (в т.ч. если её убили).
        // Кто играет, определяет MediaSession — сразу пересчитываем пресет.
        when (intent?.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> applyPresetForCurrentState()
        }
        return START_STICKY
    }

    fun isAudioProcessingEnabled(): Boolean = isProcessingEnabled

    fun setAudioProcessingEnabled(enabled: Boolean) {
        if (this.isProcessingEnabled != enabled) {
            this.isProcessingEnabled = enabled
            applyEngineEnabled()
            updateNotification()
            EquXLog.i("equX_Service", "Глобальный статус звука изменен -> enabled=$enabled")
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val statusText = if (isProcessingEnabled) "On" else "Off"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification_equx)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            EquXLog.w("equX_Service", "Не удалось обновить уведомление: ${e.message}")
        }
    }

    /**
     * Проверяет методы регулировки громкости (Method 2 / Method 3).
     *
     * Автоматически запускается один раз за сессию (когда включён тоггл Car или когда
     * устройство определилось как Automotive). Пользователь может перезапустить проверку
     * вручную через [reProbeVolumeMethods] — например, открыв выпадающий список метода.
     */
    private fun maybeRunVolumeMethodProbe(force: Boolean = false) {
        if (!force && volumeMethodProbeRun) return
        if (!force && android.os.SystemClock.elapsedRealtime() < volumeMethodProbeRetryAt) return
        volumeMethodProbeRun = true
        volumeMethodProbeJob?.cancel()
        volumeMethodProbeJob = serviceScope.launch(Dispatchers.IO) {
            volumeMethodProbeRunning = true
            try {
                val result = CarVolumeMethodProbe.run(applicationContext)
                if (result.deferredByMute) {
                    // Звук замьючен — прежние статусы не трогаем, повторим позже из тика монитора.
                    volumeMethodProbeRetryAt = android.os.SystemClock.elapsedRealtime() + VOLUME_PROBE_RETRY_MS
                    volumeMethodProbeRun = false
                    return@launch
                }
                volumeMethod2Status = result.method2
                volumeMethod3Status = result.method3
                EquXLog.i(TAG_SPEED, "volume method probe result: method2=${result.method2}, method3=${result.method3}")
                EquXLog.i(TAG_SPEED, "volume method probe result: method2=${result.method2}, method3=${result.method3}")
                if (result.method3 == VolumeMethodStatus.WORKS) readHuSpeedVolumeLevel("проба громкости", alwaysLog = true)
            } catch (e: Exception) {
                EquXLog.w(TAG_SPEED, "volume method probe failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                volumeMethodProbeRunning = false
                onMain { onVolumeMethodStatusChanged?.invoke() }
                // Статус метода мог стать WORKS/FAILS — пересчитываем, кто рулит GALA.
                applyGalaSystemVolume(lastGalaSpeedKmh)
            }
        }
    }

    /**
     * Читает штатную регулировку громкости по скорости ГУ (ECARX) и пишет её в лог —
     * при [alwaysLog] всегда, иначе только при смене значения. Возвращает 0 — выключена,
     * 1..3 — уровень, null — параметра нет (не ECARX / нет CarAudioManager) или флаг
     * вкл/выкл не читается. Делает binder-вызов: не звать с главного потока.
     */
    fun readHuSpeedVolumeLevel(reason: String, alwaysLog: Boolean = false): Int? {
        // На устройствах без Car API (m3=FAILS) не дёргаем — иначе лог забьётся ошибками init.
        if (volumeMethod3Status == VolumeMethodStatus.FAILS) return null
        val state = CarVolumeController.getHuSpeedVolume(applicationContext) ?: return null
        val previous = lastHuSpeedVolume
        lastHuSpeedVolume = state
        // Как штатный getVehicleSpeedLevel(): при выключении сбрасывается только флаг enable,
        // value хранит последний уровень. Флаг не прочитался — уровень неизвестен (null).
        val level = state.level
        if (alwaysLog || previous != state) {
            val car = audioEngine.carConfig
            val galaOn = car.isEnabled && car.volumeEnabled
            val was = previous?.let { "enabled=${it.enabled} value=${it.value} menu=${it.menuSwitch}" } ?: "?"
            val message = "штатная регулировка громкости по скорости ГУ (PARAM_VOLUME_ADJUST_BY_SPEED): " +
                "enabled=${state.enabled} value=${state.value} menu=${state.menuSwitch} (было $was), " +
                "GALA=${if (galaOn) "вкл" else "выкл"} [$reason]"
            if (level != null && level != 0 && galaOn) {
                EquXLog.w(TAG_SPEED, "$message — двойная прибавка громкости с GALA")
            } else {
                EquXLog.i(TAG_SPEED, message)
            }
        }
        return level
    }

    /**
     * Периодическая (раз в [HU_SPEED_VOLUME_CHECK_MS]) проверка штатной регулировки
     * по скорости. Заодно (пере)оформляет подписку на колбэк ГУ после переподключения Car.
     */
    private fun maybeCheckHuSpeedVolume() {
        if (volumeMethod3Status != VolumeMethodStatus.WORKS) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < huSpeedVolumeCheckAt) return
        huSpeedVolumeCheckAt = now + HU_SPEED_VOLUME_CHECK_MS
        CarVolumeController.registerHuSettingCallback(applicationContext, ::onHuCustomSettingChanged)
        readHuSpeedVolumeLevel("периодическая проверка")
    }

    /**
     * Подписка на громкость для тонкомпенсации: на ГУ — колбэк CarAudioManager (переоформляется
     * из тика монитора после переподключения Car), без Car API — broadcast громкости STREAM_MUSIC.
     */
    private fun ensureLoudnessVolumeWatch() {
        if (CarVolumeController.registerVolumeCallback(applicationContext) { _, groupId ->
                if (groupId == MEDIA_VOLUME_GROUP || groupId < 0) {
                    serviceScope.launch(Dispatchers.IO) { refreshLoudnessVolume() }
                }
            }
        ) {
            loudnessVolumeMethod = VolumeMethod.METHOD_3
            return
        }
        if (volumeChangedReceiver != null) return
        loudnessVolumeMethod = VolumeMethod.METHOD_2
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                serviceScope.launch(Dispatchers.IO) { refreshLoudnessVolume() }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            volumeChangedReceiver = receiver
        }
    }

    /** Передаёт движку текущую громкость media. Binder-вызов: не звать с главного потока. */
    private fun refreshLoudnessVolume() {
        if (!audioEngine.loudnessEnabled) return
        val method = loudnessVolumeMethod
        val volume = CarVolumeController.getVolume(applicationContext, method) ?: return
        val max = CarVolumeController.getMaxVolume(applicationContext, method) ?: return
        audioEngine.setLoudnessVolume(volume, max)
    }

    /** Колбэк ГУ о смене custom setting'а (поток car_service): перечитываем громкость по скорости. */
    private fun onHuCustomSettingChanged(zoneId: Int, param: Int, event: String) {
        if (param == CarVolumeController.PARAM_VOLUME_ADJUST_BY_SPEED) {
            EquXLog.i(TAG_SPEED, "колбэк ГУ: громкость по скорости zone=$zoneId $event")
            serviceScope.launch(Dispatchers.IO) { readHuSpeedVolumeLevel("колбэк ГУ") }
        }
    }

    /** Перезапускает проверку методов громкости по запросу пользователя. */
    fun reProbeVolumeMethods() {
        maybeRunVolumeMethodProbe(force = true)
    }

    /**
     * Применяет GALA-громкость через выбранный системный volume-метод.
     *
     * Если метод выбран и протестирован как рабочий — реально двигает системную
     * громкость (базовая громкость + шаги по скорости). Если рабочего метода нет —
     * GALA не действует: DSP-фолбэка через постгейн лимитера нет намеренно
     * (на +3 дБ уже давал хруст).
     *
     * Ручная регулировка (руль, слайдер ГУ, mute) имеет приоритет:
     * - перед каждой записью читаем текущую громкость; если она не равна последней
     *   записанной нами — её сменил пользователь, и база пересчитывается от неё;
     * - при mute громкость не пишем вовсе: на части ГУ
     *   `CarAudioService.setGroupVolume(index != 0)` снимает mute с группы.
     */
    private fun applyGalaSystemVolume(speedKmh: Float): Unit = synchronized(galaVolumeLock) {
        lastGalaSpeedKmh = speedKmh
        if (volumeMethodProbeRunning) return
        // Берём настройки, реально применённые к движку (в т.ч. несохранённый черновик из UI),
        // а не сохранённый пресет: у службы свой экземпляр PresetDataManager без черновика.
        val car = audioEngine.carConfig
        if (!car.isEnabled || !car.volumeEnabled) {
            restoreGalaSystemVolume()
            return
        }

        val resolved = CarVolumeController.resolve(
            method = selectedVolumeMethod,
            method2Status = volumeMethod2Status,
            method3Status = volumeMethod3Status,
        )

        if (resolved == null) {
            // Пока проба не закончилась (статусы UNTESTED) — молча ждём её: при старте
            // GALA вызывается раньше пробы, и предупреждение было ложным.
            if (!isVolumeProbePending() && !galaNoMethodLogged) {
                galaNoMethodLogged = true
                EquXLog.w(TAG_SPEED, "GALA включена, но нет рабочего метода громкости (method=$selectedVolumeMethod, m2=$volumeMethod2Status, m3=$volumeMethod3Status) — громкость не меняется")
            }
            restoreGalaSystemVolume()
            return
        }
        galaNoMethodLogged = false

        // Метод сменился (или включились заново) — сначала возвращаем прежний метод к базе,
        // иначе новая база снимется с уже поднятой громкости и буст «накопится».
        if (galaVolumeMethod != null && galaVolumeMethod != resolved) {
            restoreGalaSystemVolume()
        }

        if (galaVolumeMethod == null) {
            // Включились заново — снимаем базу. Если прошлый процесс убили с поднятой
            // громкостью и она с тех пор не менялась, берём сохранённую базу, а не текущую.
            val current = CarVolumeController.getVolume(applicationContext, resolved) ?: return
            if (isGalaMuted(resolved, current)) return
            val max = CarVolumeController.getMaxVolume(applicationContext, resolved) ?: return
            val saved = loadGalaState()
            if (saved != null && saved.method == resolved && saved.lastSet == current) {
                galaVolumeBase = saved.base
                galaVolumeBoost = saved.boost
                EquXLog.i(TAG_SPEED, "GALA: база восстановлена после перезапуска: base=${saved.base} boost=${saved.boost} current=$current")
            } else {
                galaVolumeBase = current
                galaVolumeBoost = 0
            }
            galaVolumeMethod = resolved
            galaVolumeMax = max
            galaVolumeLastSet = current
        }

        val base = galaVolumeBase ?: return
        val max = galaVolumeMax ?: return
        val prevBoost = galaVolumeBoost
        val boost = galaBoostSteps(speedKmh, car)

        // Системную громкость читаем только перед реальной записью: ручная регулировка
        // учитывается как раз в этот момент, а лишние binder-вызовы раз в секунду не нужны
        // (OEM car-lib к тому же пишет Log.e на каждый getGroupVolume).
        if (galaVolumeLastSet == galaTarget(base, boost, max)) {
            if (boost != prevBoost) {
                galaVolumeBoost = boost
                saveGalaState()
            }
            return
        }

        val current = CarVolumeController.getVolume(applicationContext, resolved) ?: return
        if (isGalaMuted(resolved, current)) return

        var effectiveBase = base
        val lastSet = galaVolumeLastSet
        if (lastSet != null && current != lastSet) {
            // Громкость сменили не мы (руль, слайдер, mute/unmute) — это новая воля
            // пользователя: база = текущая громкость минус буст, действовавший на тот момент.
            effectiveBase = current - prevBoost
            EquXLog.i(TAG_SPEED, "GALA: громкость изменена пользователем $lastSet -> $current, база $base -> $effectiveBase (boost=$prevBoost)")
            galaVolumeBase = effectiveBase
            galaVolumeLastSet = current
        }

        galaVolumeBoost = boost
        val target = galaTarget(effectiveBase, boost, max)
        if (galaVolumeLastSet != target) {
            EquXLog.i(TAG_SPEED, "GALA system volume: method=$resolved base=$effectiveBase boost=$boost target=$target max=$max speed=$speedKmh")
            CarVolumeController.setVolume(applicationContext, resolved, target)
            galaVolumeLastSet = target
        }
        saveGalaState()
    }

    // Не ниже 1: на части ГУ setGroupVolume(0) включает mute.
    private fun galaTarget(base: Int, boost: Int, max: Int): Int =
        (base + boost).coerceIn(1, max.coerceAtLeast(1))

    /** Звук замьючен (или громкость 0) — GALA громкость не пишет. Логирует смену состояния. */
    private fun isGalaMuted(method: VolumeMethod, current: Int): Boolean {
        val muted = CarVolumeController.isMuted(applicationContext, method) == true || current <= 0
        if (muted && !galaMutedLogged) {
            galaMutedLogged = true
            EquXLog.i(TAG_SPEED, "GALA: звук замьючен — громкость не трогаем (current=$current)")
        } else if (!muted && galaMutedLogged) {
            galaMutedLogged = false
            EquXLog.i(TAG_SPEED, "GALA: mute снят (current=$current)")
        }
        return muted
    }

    /**
     * Ступень буста по скорости с гистерезисом [GALA_HYSTERESIS_KMH]: от текущей ступени
     * переходим на соседнюю, только когда скорость ушла за её границу с запасом.
     */
    private fun galaBoostSteps(speedKmh: Float, car: CarConfig): Int {
        val steps = car.maxBoostSteps
        val span = (car.maxSpeed - car.minSpeed).toFloat()
        if (steps <= 0 || span <= 0f) return 0
        val raw = ((speedKmh - car.minSpeed) / span).coerceIn(0f, 1f) * steps
        // Гистерезис в долях ступени; < 0.5, чтобы соседние пороги не перекрывались.
        val h = (GALA_HYSTERESIS_KMH * steps / span).coerceAtMost(0.45f)
        var boost = galaVolumeBoost.coerceIn(0, steps)
        while (boost < steps && raw >= boost + 0.5f + h) boost++
        while (boost > 0 && raw <= boost - 0.5f - h) boost--
        return boost
    }

    /** Проба методов ещё не дала ответа по выбранному методу. */
    private fun isVolumeProbePending(): Boolean = when (selectedVolumeMethod) {
        VolumeMethod.METHOD_2 -> volumeMethod2Status == VolumeMethodStatus.UNTESTED
        VolumeMethod.METHOD_3 -> volumeMethod3Status == VolumeMethodStatus.UNTESTED
        VolumeMethod.AUTO -> volumeMethod2Status == VolumeMethodStatus.UNTESTED ||
            volumeMethod3Status == VolumeMethodStatus.UNTESTED
    }

    /**
     * Снимает GALA-буст: возвращает системную громкость к базе.
     *
     * Если громкость после нашей записи сменил пользователь — снимаем только наш буст
     * с его значения. При mute ничего не пишем (запись сняла бы mute).
     */
    private fun restoreGalaSystemVolume(): Unit = synchronized(galaVolumeLock) {
        val method = galaVolumeMethod
        val base = galaVolumeBase
        val lastSet = galaVolumeLastSet
        val boost = galaVolumeBoost
        galaVolumeMethod = null
        galaVolumeBase = null
        galaVolumeLastSet = null
        galaVolumeBoost = 0
        galaVolumeMax = null
        galaMutedLogged = false
        if (method == null || base == null) return
        clearGalaState()
        if (boost == 0) return
        runCatching {
            val current = CarVolumeController.getVolume(applicationContext, method) ?: return@runCatching
            if (CarVolumeController.isMuted(applicationContext, method) == true || current <= 0) return@runCatching
            val restored = (if (current == lastSet) base else current - boost).coerceAtLeast(1)
            if (restored != current) {
                EquXLog.i(TAG_SPEED, "GALA: буст снят, громкость $current -> $restored")
                CarVolumeController.setVolume(applicationContext, method, restored)
            }
        }
    }

    private data class SavedGalaState(val method: VolumeMethod, val base: Int, val lastSet: Int, val boost: Int)

    private fun saveGalaState() {
        val method = galaVolumeMethod ?: return
        val base = galaVolumeBase ?: return
        val lastSet = galaVolumeLastSet ?: return
        galaStatePrefs.edit()
            .putString("method", method.name)
            .putInt("base", base)
            .putInt("lastSet", lastSet)
            .putInt("boost", galaVolumeBoost)
            .apply()
    }

    private fun loadGalaState(): SavedGalaState? {
        val prefs = galaStatePrefs
        val method = prefs.getString("method", null)
            ?.let { name -> VolumeMethod.values().firstOrNull { it.name == name } } ?: return null
        if (!prefs.contains("base") || !prefs.contains("lastSet")) return null
        return SavedGalaState(
            method = method,
            base = prefs.getInt("base", 0),
            lastSet = prefs.getInt("lastSet", 0),
            boost = prefs.getInt("boost", 0),
        )
    }

    private fun clearGalaState() {
        galaStatePrefs.edit().clear().apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        EquXLog.i("equX_Service", "Уничтожение службы, освобождение ресурсов")
        // Помечаем корректное завершение, чтобы отличить его от убийства OEM-киллером.
        try {
            if (::dataManager.isInitialized) {
                runBlocking { dataManager.setServiceRunning(false) }
            }
        } catch (e: Exception) {
            Log.w("equX_Service", "Не удалось пометить остановку службы: ${e.message}")
        }
        MediaNotificationService.onMediaSessionStateChanged = null
        bindingsCleanupReceiver?.let { runCatching { unregisterReceiver(it) } }
        bindingsCleanupReceiver = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
                val manager = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)
                manager?.unregisterAudioPlaybackCallback(playbackCallback!!)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                val manager = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)
                manager?.unregisterAudioDeviceCallback(audioDeviceCallback!!)
            }
        } catch (e: Exception) {
            // Освобождение при остановке службы — ошибка не важна.
        }
        
        audioEngine.onLoudnessVolumeNeeded = null
        volumeChangedReceiver?.let { runCatching { unregisterReceiver(it) } }
        volumeChangedReceiver = null
        restoreGalaSystemVolume()
        CarVolumeController.release()
        speedMonitor.stop()
        audioEngine.release()
        
        serviceJob.cancel()
    }
}
