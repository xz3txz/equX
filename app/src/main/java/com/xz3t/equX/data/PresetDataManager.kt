package com.xz3t.equX.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "equx_settings")

/** Акцентный цвет по умолчанию — фирменный синий equX. */
val DEFAULT_ACCENT_ARGB: Int = 0xFF3498DB.toInt()

/** Ключ выбранного языка интерфейса. Доступен top-level для чтения в attachBaseContext. */
val APP_LANGUAGE_PREFERENCES_KEY = stringPreferencesKey("app_language")

/** Внутреннее (каноническое) имя встроенного пресета по умолчанию. */
const val DEFAULT_PRESET_NAME = "По умолчанию"

/** Длительность плавного перехода между пресетами по умолчанию (3 секунды). */
const val DEFAULT_SMOOTH_TRANSITION_MS = 3_000

/** Режим оформления: ночная тема, дневная тема или следование за системой. */
enum class ThemeMode { NIGHT, DAY, AUTO }

enum class BindingConflictType {
    NONE,
    GLOBAL_DEVICE_DUPLICATE,            // Правило 2: Другой пресет уже является глобальным для этого устройства (0 приложений)
    CANNOT_BE_GLOBAL_HAS_APPS,          // Правило 1: Устройство уже привязано в другом пресете с приложениями -> нельзя делать текущий пресет глобальным
    CANNOT_BIND_DEVICE_OTHER_IS_GLOBAL, // Правило 1: Другой пресет уже сделан глобальным для этого устройства
    APP_DEVICE_PAIR_DUPLICATE           // Обычное дублирование связки Приложение + Устройство
}

data class ConflictResult(
    val type: BindingConflictType,
    val conflictingPreset: PresetModel,
    val conflictingDevice: AudioDeviceBinding? = null,
    val conflictingApp: AppBinding? = null
)

/**
 * Единое на процесс состояние пресетов (сохранённый пресет, черновик, пользовательские пресеты,
 * настройки). Служба и UI работают с ОДНИМ экземпляром — [getInstance]: черновик из UI сразу виден
 * службе (пауза автопереключения, ручной режим, чистка привязок), без синхронизации через DataStore.
 */
class PresetDataManager private constructor(private val context: Context) {

    private val gson = Gson()
    private val currentPresetKey = stringPreferencesKey("current_active_preset_json")
    private val userPresetsKey = stringPreferencesKey("saved_user_presets_json")
    private val visualizerActiveKey = booleanPreferencesKey("is_visualizer_active_state")
    private val isAutoSwitchKey = booleanPreferencesKey("is_auto_switch_state")
    private val lastActivePackageKey = stringPreferencesKey("last_active_package_name_key")
    private val isEngineEnabledKey = booleanPreferencesKey("is_engine_enabled_state")
    private val accentColorKey = intPreferencesKey("accent_color_argb")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val appLanguageKey = APP_LANGUAGE_PREFERENCES_KEY
    private val autostartPromptShownKey = booleanPreferencesKey("autostart_prompt_shown")
    private val serviceRunningKey = booleanPreferencesKey("service_running")
    private val unexpectedServiceDeathKey = booleanPreferencesKey("unexpected_service_death")
    private val lastUnexpectedDeathPromptAtKey = longPreferencesKey("last_unexpected_death_prompt_at")
    private val smoothTransitionEnabledKey = booleanPreferencesKey("smooth_transition_enabled")
    private val smoothTransitionDurationMsKey = intPreferencesKey("smooth_transition_duration_ms")
    private val appScaleKey = floatPreferencesKey("app_scale")
    private val speedSourceKey = stringPreferencesKey("speed_source")
    private val volumeMethodKey = stringPreferencesKey("volume_method")

    // Глобальный переключатель эквалайзера On/Off
    var isEngineEnabled: Boolean by mutableStateOf(true)
        private set

    // Режим работы: true = Автопереключение, false = Ручной выбор (по умолчанию)
    var isAutoSwitchEnabled: Boolean by mutableStateOf(false)
        private set

    // Сохраненный пакет последнего игравшего плеера
    var lastActivePackageName: String? by mutableStateOf(null)
        private set

    // Выбранный пользователем акцентный цвет темы (ARGB Int).
    // Используется как в Compose-теме, так и для перекраски иконок из кода.
    var accentColor: Int by mutableStateOf(DEFAULT_ACCENT_ARGB)
        private set

    // Режим оформления: ночь / день / авто.
    var themeMode: ThemeMode by mutableStateOf(ThemeMode.NIGHT)
        private set

    // Выбранный язык интерфейса.
    var appLanguage: AppLanguage by mutableStateOf(AppLanguage.SYSTEM)
        private set

    val defaultPresetsMap: Map<String, PresetModel> = initDefaultPresets()

    companion object {
        @Volatile
        private var instance: PresetDataManager? = null

        fun getInstance(context: Context): PresetDataManager =
            instance ?: synchronized(this) {
                instance ?: PresetDataManager(context.applicationContext).also { instance = it }
            }
    }

    var originalPreset: PresetModel by mutableStateOf(createDefaultPreset(DEFAULT_PRESET_NAME))
        private set

    var liveDraftPreset: PresetModel by mutableStateOf(originalPreset)
        private set

    var userPresetsMap: Map<String, PresetModel> by mutableStateOf(emptyMap())
        private set

    // true после первой успешной загрузки пресетов из DataStore.
    // Используется EqualizerService, чтобы не применять auto-switch на пустых данных при старте/перезагрузке.
    @Volatile
    var isDataLoaded: Boolean = false
        private set

    // true после того, как из DataStore прочитан флаг «Авто/Ручной». До этого момента
    // isAutoSwitchEnabled держит дефолтный false, и авто-режим на старте не применяется.
    @Volatile
    var isAutoSwitchLoaded: Boolean = false
        private set

    private fun initDefaultPresets(): Map<String, PresetModel> {
        val map = LinkedHashMap<String, PresetModel>()
        map[DEFAULT_PRESET_NAME] = PresetModel(
            presetName = DEFAULT_PRESET_NAME,
            equalizer = EqualizerConfig(isEnabled = false),
            compressor = CompressorConfig(isEnabled = false),
            spatial3d = SpatialFxConfig(isEnabled = false),
            limiter = LimiterConfig(isEnabled = false)
        ).let(::normalizeSimpleLimiter)
        return map
    }

    val isAutoSwitchEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        val mode = preferences[isAutoSwitchKey] ?: false
        isAutoSwitchEnabled = mode
        isAutoSwitchLoaded = true
        mode
    }

    val isEngineEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        val enabled = preferences[isEngineEnabledKey] ?: true
        isEngineEnabled = enabled
        enabled
    }

    suspend fun setEngineEnabled(enabled: Boolean) {
        isEngineEnabled = enabled
        context.dataStore.edit { preferences ->
            preferences[isEngineEnabledKey] = enabled
        }
    }

    suspend fun setAutoSwitchEnabled(enabled: Boolean) {
        isAutoSwitchEnabled = enabled
        context.dataStore.edit { preferences ->
            preferences[isAutoSwitchKey] = enabled
        }
    }

    val autostartPromptShownFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[autostartPromptShownKey] ?: false
    }

    suspend fun setAutostartPromptShown(shown: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[autostartPromptShownKey] = shown
        }
    }

    val serviceRunningFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[serviceRunningKey] ?: false
    }

    suspend fun setServiceRunning(running: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[serviceRunningKey] = running
        }
    }

    val unexpectedServiceDeathFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[unexpectedServiceDeathKey] ?: false
    }

    suspend fun setUnexpectedServiceDeath(death: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[unexpectedServiceDeathKey] = death
        }
    }

    val lastUnexpectedDeathPromptAtFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[lastUnexpectedDeathPromptAtKey] ?: 0L
    }

    suspend fun setLastUnexpectedDeathPromptAt(timeMillis: Long) {
        context.dataStore.edit { preferences ->
            preferences[lastUnexpectedDeathPromptAtKey] = timeMillis
        }
    }

    val smoothTransitionEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[smoothTransitionEnabledKey] ?: true
    }

    suspend fun setSmoothTransitionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[smoothTransitionEnabledKey] = enabled
        }
    }

    val smoothTransitionDurationMsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[smoothTransitionDurationMsKey] ?: DEFAULT_SMOOTH_TRANSITION_MS
    }

    suspend fun setSmoothTransitionDurationMs(durationMs: Int) {
        context.dataStore.edit { preferences ->
            preferences[smoothTransitionDurationMsKey] = durationMs
        }
    }

    /** Источник скорости (глобально). Неизвестное/отсутствующее значение → AUTO. */
    val speedSourceFlow: Flow<SpeedSource> = context.dataStore.data.map { preferences ->
        preferences[speedSourceKey]?.let { name -> SpeedSource.entries.firstOrNull { it.name == name } }
            ?: SpeedSource.AUTO
    }

    suspend fun setSpeedSource(source: SpeedSource) {
        context.dataStore.edit { preferences -> preferences[speedSourceKey] = source.name }
    }

    /** Метод регулировки громкости GALA (глобально). Неизвестное/отсутствующее значение → AUTO. */
    val volumeMethodFlow: Flow<VolumeMethod> = context.dataStore.data.map { preferences ->
        preferences[volumeMethodKey]?.let { name -> VolumeMethod.entries.firstOrNull { it.name == name } }
            ?: VolumeMethod.AUTO
    }

    suspend fun setVolumeMethod(method: VolumeMethod) {
        context.dataStore.edit { preferences -> preferences[volumeMethodKey] = method.name }
    }

    val appScaleFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        coerceAppScale(preferences[appScaleKey] ?: APP_SCALE_DEFAULT)
    }

    suspend fun setAppScale(scale: Float) {
        context.dataStore.edit { preferences ->
            preferences[appScaleKey] = coerceAppScale(scale)
        }
    }

    /**
     * Одноразово подставляет рекомендованный масштаб при первом запуске,
     * если пользователь ещё ни разу не трогал настройку масштаба.
     *
     * Если ключ уже записан (пользователь менял масштаб или прошлый запуск уже
     * проставил дефолт) — ничего не делаем.
     */
    suspend fun ensureAutoAppScaleInitialized() {
        context.dataStore.edit { preferences ->
            if (preferences[appScaleKey] == null) {
                val metrics = context.resources.displayMetrics
                preferences[appScaleKey] = computeRecommendedAppScale(metrics)
            }
        }
    }

    val accentColorFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        val color = preferences[accentColorKey] ?: DEFAULT_ACCENT_ARGB
        accentColor = color
        color
    }

    suspend fun setAccentColor(argb: Int) {
        accentColor = argb
        context.dataStore.edit { preferences ->
            preferences[accentColorKey] = argb
        }
    }

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        val raw = preferences[themeModeKey] ?: ThemeMode.NIGHT.name
        val mode = runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.NIGHT)
        themeMode = mode
        mode
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
        context.dataStore.edit { preferences ->
            preferences[themeModeKey] = mode.name
        }
    }

    val appLanguageFlow: Flow<AppLanguage> = context.dataStore.data.map { preferences ->
        val tag = preferences[appLanguageKey]
        val language = AppLanguage.fromTag(tag)
        appLanguage = language
        language
    }

    suspend fun setAppLanguage(language: AppLanguage) {
        appLanguage = language
        context.dataStore.edit { preferences ->
            if (language.tag == null) {
                preferences.remove(appLanguageKey)
            } else {
                preferences[appLanguageKey] = language.tag
            }
        }
    }

    val lastActivePackageNameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        val pkg = preferences[lastActivePackageKey]
        lastActivePackageName = pkg
        pkg
    }

    suspend fun setLastActivePackageName(packageName: String) {
        if (packageName.isBlank()) return
        lastActivePackageName = packageName
        context.dataStore.edit { preferences ->
            preferences[lastActivePackageKey] = packageName
        }
    }

    /**
     * Проверка на конфликт с учетом Правила 1 и Правила 2:
     * - GLOBAL_DEVICE_DUPLICATE: Глобальная сессия уже существует в другом пресете
     * - CANNOT_BE_GLOBAL_HAS_APPS: Другой пресет уже использует это устройство с приложениями -> нельзя делать текущий пресет глобальным
     * - CANNOT_BIND_DEVICE_OTHER_IS_GLOBAL: Другой пресет сделан глобальным для этого устройства
     * - APP_DEVICE_PAIR_DUPLICATE: Дублирование конкретной связки Приложение + Устройство
     */
    fun checkBindingConflict(targetPresetName: String, app: AppBinding?, device: AudioDeviceBinding?): ConflictResult? {
        val target = getAllPresets().firstOrNull { it.presetName == targetPresetName } ?: return null
        val otherPresets = getAllPresets().filter { it.presetName != targetPresetName }
        return checkBindingConflict(target, otherPresets, app, device)
    }

    /**
     * Защита от удаления последнего приложения, если пресет при этом пытается стать глобальным,
     * а у устройства уже есть другие привязанные пресеты.
     */
    fun checkAppRemovalConflict(targetPresetName: String, appToRemove: AppBinding): ConflictResult? {
        val target = getAllPresets().firstOrNull { it.presetName == targetPresetName } ?: return null
        val otherPresets = getAllPresets().filter { it.presetName != targetPresetName }
        return checkAppRemovalConflict(target, otherPresets, appToRemove)
    }

    /**
     * Разрешение конфликта: переназначает элемент и чистит старый пресет
     */
    suspend fun resolveConflictAndBind(targetPresetName: String, oldPresetName: String, app: AppBinding?, device: AudioDeviceBinding?) {
        // Удаляем из старого
        val oldPreset = userPresetsMap[oldPresetName] ?: defaultPresetsMap[oldPresetName]
        if (oldPreset != null) {
            val newApps = if (app != null) oldPreset.boundApps.filter { it.packageName != app.packageName } else oldPreset.boundApps
            
            // Если мы забрали последнее приложение, то убираем и устройство (по ТЗ)
            val newDevices = if (newApps.isEmpty() && oldPreset.boundApps.isNotEmpty()) {
                emptyList()
            } else if (device != null) {
                oldPreset.boundDevices.filter { it.id != device.id }
            } else {
                oldPreset.boundDevices
            }
            
            val updatedOld = oldPreset.copy(rawBoundApps = newApps, rawBoundDevices = newDevices)
            val mutableUserMap = userPresetsMap.toMutableMap()
            if (userPresetsMap.containsKey(oldPresetName)) {
                mutableUserMap[oldPresetName] = updatedOld
                userPresetsMap = mutableUserMap
            }
        }

        // Добавляем в целевой
        val targetPreset = userPresetsMap[targetPresetName] ?: defaultPresetsMap[targetPresetName]
        if (targetPreset != null) {
            val newApps = if (app != null && !targetPreset.boundApps.any { it.packageName == app.packageName }) targetPreset.boundApps + app else targetPreset.boundApps
            val newDevices = if (device != null && !targetPreset.boundDevices.any { it.id == device.id }) targetPreset.boundDevices + device else targetPreset.boundDevices
            
            val updatedTarget = targetPreset.copy(rawBoundApps = newApps, rawBoundDevices = newDevices)
            val mutableUserMap = userPresetsMap.toMutableMap()
            if (userPresetsMap.containsKey(targetPresetName)) {
                mutableUserMap[targetPresetName] = updatedTarget
                userPresetsMap = mutableUserMap
            }
            
            if (liveDraftPreset.presetName == targetPresetName) {
                liveDraftPreset = updatedTarget
                originalPreset = updatedTarget
            }
        }

        context.dataStore.edit { preferences ->
            preferences[userPresetsKey] = gson.toJson(userPresetsMap)
        }
    }

    suspend fun toggleDeviceBinding(presetName: String, device: AudioDeviceBinding) {
        val target = userPresetsMap[presetName] ?: defaultPresetsMap[presetName] ?: return
        val currentBound = target.boundDevices.toMutableList()
        
        if (currentBound.any { it.id == device.id }) {
            currentBound.removeAll { it.id == device.id }
        } else {
            currentBound.add(device)
        }
        
        val updated = target.copy(rawBoundDevices = currentBound)
        saveUpdatedPreset(presetName, updated)
    }

    suspend fun toggleAppBinding(presetName: String, app: AppBinding) {
        val target = userPresetsMap[presetName] ?: defaultPresetsMap[presetName] ?: return
        val currentBound = target.boundApps.toMutableList()
        
        if (currentBound.any { it.packageName == app.packageName }) {
            currentBound.removeAll { it.packageName == app.packageName }
        } else {
            currentBound.add(app)
        }
        
        val updated = target.copy(rawBoundApps = currentBound)
        saveUpdatedPreset(presetName, updated)
    }

    suspend fun toggleSourceBinding(presetName: String, source: AudioDeviceBinding) {
        val target = userPresetsMap[presetName] ?: defaultPresetsMap[presetName] ?: return
        val currentBound = target.boundSources.toMutableList()
        
        if (currentBound.any { it.id == source.id }) {
            currentBound.removeAll { it.id == source.id }
        } else {
            currentBound.add(source)
        }
        
        val updated = target.copy(rawBoundSources = currentBound)
        saveUpdatedPreset(presetName, updated)
    }

    /**
     * Убирает привязки к удалённому приложению из всех пресетов.
     *
     * Вызывается только по событию PACKAGE_REMOVED (не обновление). Сверяться со списком
     * медиа-сканера нельзя: приложение, найденное лишь по активной MediaSession, после
     * закрытия в нём не появляется, а через PackageManager оно невидимо (package visibility),
     * хотя установлено — его привязка удалялась бы по ошибке.
     */
    suspend fun removeAppBindings(packageName: String) {
        pruneBindings { removeAppBinding(it, packageName) }
    }

    /**
     * Удаляет привязки устройств вывода, которых больше нет (Bluetooth отвязан),
     * и — на ГУ — источников (телефонов), которых нет среди сопряжённых.
     *
     * Bluetooth-привязки удаляются, только если список сопряжённых надёжен: Bluetooth включён
     * и (Android 12+) выдано BLUETOOTH_CONNECT. Иначе сканер не видит сопряжённые устройства
     * (после переустановки, при выключенном BT), и мы стёрли бы привязку наушников.
     * Пустой список источников тоже не считается надёжным (мог прийти до включения BT).
     */
    suspend fun pruneMissingDeviceBindings(availableDeviceIds: Set<String>, availableSourceIds: Set<String>) {
        if (availableDeviceIds.isEmpty()) return
        val canTrustBluetoothList = isBluetoothScanReliable()
        val canPruneSources = canTrustBluetoothList && availableSourceIds.isNotEmpty()

        pruneBindings { preset ->
            retainDeviceBindings(
                preset,
                keepDevice = { device ->
                    if (device.deviceType == "BLUETOOTH") {
                        !canTrustBluetoothList || device.id in availableDeviceIds
                    } else {
                        device.id in availableDeviceIds
                    }
                },
                keepSource = { source -> !canPruneSources || source.id in availableSourceIds }
            )
        }
    }

    /**
     * Применяет чистку привязок ко всем пресетам.
     *
     * Активный пресет чистится отдельно в сохранённой версии и в черновике: на диск пишется
     * очищенный СОХРАНЁННЫЙ пресет, а несохранённые правки звука в черновике остаются.
     * (Раньше на диск писался очищенный черновик — правки молча сохранялись.)
     */
    private suspend fun pruneBindings(transform: (PresetModel) -> PresetModel) {
        var mapChanged = false
        val updatedMap = userPresetsMap.mapValues { (_, preset) ->
            transform(preset).also { if (it !== preset) mapChanged = true }
        }
        val cleanedOriginal = transform(originalPreset)
        val cleanedDraft = transform(liveDraftPreset)
        val originalChanged = cleanedOriginal !== originalPreset
        if (!mapChanged && !originalChanged && cleanedDraft === liveDraftPreset) return

        liveDraftPreset = cleanedDraft
        if (mapChanged) userPresetsMap = updatedMap
        if (originalChanged) {
            originalPreset = cleanedOriginal
            saveBootCachePreset(cleanedOriginal)
        }
        val userPresetsJson = if (mapChanged) gson.toJson(updatedMap) else null
        val currentJson = if (originalChanged) gson.toJson(cleanedOriginal) else null
        // Помечаем JSON как уже применённые: иначе activePresetFlow увидит «новый» активный
        // пресет и перезапишет им черновик.
        if (userPresetsJson != null) lastUserPresetsJson = userPresetsJson
        if (currentJson != null) lastActivePresetJson = currentJson
        context.dataStore.edit { preferences ->
            if (userPresetsJson != null) preferences[userPresetsKey] = userPresetsJson
            if (currentJson != null) preferences[currentPresetKey] = currentJson
        }
    }

    /**
     * Можем ли доверять списку сопряжённых Bluetooth-устройств, полученному сканером.
     *
     * Возвращает false, если Bluetooth выключен или (на Android 12+) не выдано
     * BLUETOOTH_CONNECT — в этих случаях bondedDevices недоступен/пуст, и удалять
     * Bluetooth-привязки нельзя, иначе потеряем привязку наушников.
     */
    private fun isBluetoothScanReliable(): Boolean {
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
            val adapter = manager.adapter ?: return false
            if (!adapter.isEnabled) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun saveUpdatedPreset(presetName: String, updated: PresetModel) {
        if (userPresetsMap.containsKey(presetName)) {
            val map = userPresetsMap.toMutableMap()
            map[presetName] = updated
            userPresetsMap = map
            context.dataStore.edit { preferences ->
                preferences[userPresetsKey] = gson.toJson(userPresetsMap)
            }
        }
        if (liveDraftPreset.presetName == presetName) {
            // Привязки не входят в звук: несохранённые правки звука в черновике сохраняем,
            // меняем только привязки (иначе DSP играет черновик, а UI показывает сохранённое).
            val hadDraft = hasUnsavedChanges()
            originalPreset = updated
            liveDraftPreset = if (hadDraft) {
                liveDraftPreset.copy(
                    rawBoundDevices = updated.boundDevices,
                    rawBoundSources = updated.boundSources,
                    rawBoundApps = updated.boundApps
                )
            } else {
                updated
            }
        }
    }

    private fun saveBootCachePreset(preset: PresetModel) {
        try {
            val deContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val cacheFile = File(deContext.filesDir, "active_preset_boot_cache.json")
            val json = gson.toJson(preset)
            cacheFile.writeText(json)
            EquXLog.i("equX_DataManager", "Пресет [${preset.presetName}] закеширован в DE-хранилище")
        } catch (e: Exception) {
            Log.e("equX_DataManager", "Ошибка зашифровки пресета: ${e.message}")
        }
    }

    fun loadBootCachePreset(): PresetModel? {
        return try {
            val deContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val cacheFile = File(deContext.filesDir, "active_preset_boot_cache.json")
            if (cacheFile.exists()) {
                val json = cacheFile.readText()
                val rawPreset = gson.fromJson(json, PresetModel::class.java)
                if (rawPreset != null) {
                    sanitizePreset(rawPreset, DEFAULT_PRESET_NAME)
                } else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    val isVisualizerActiveFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[visualizerActiveKey] ?: false
    }

    suspend fun setVisualizerActiveState(active: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[visualizerActiveKey] = active
        }
    }

    fun createDefaultPreset(name: String): PresetModel {
        return defaultPresetsMap[name]?.copy() ?: PresetModel(
            presetName = name,
            equalizer = EqualizerConfig(isEnabled = false),
            compressor = CompressorConfig(isEnabled = false),
            spatial3d = SpatialFxConfig(isEnabled = false),
            limiter = LimiterConfig(isEnabled = false),
            rawCar = CarConfig()
        ).let(::normalizeSimpleLimiter)
    }

    fun getAllPresets(): List<PresetModel> {
        val result = mutableListOf<PresetModel>()
        
        // ВАЖНО: Если дефолтный пресет был изменен и сохранен в userPresetsMap, берем его оттуда, чтобы не терять привязки
        defaultPresetsMap.forEach { (name, defaultPreset) ->
            result.add(userPresetsMap[name] ?: defaultPreset)
        }
        
        userPresetsMap.values.forEach { userPreset ->
            if (!defaultPresetsMap.containsKey(userPreset.presetName)) {
                result.add(userPreset)
            }
        }
        return result
    }

    // Последние обработанные сырые JSON. DataStore эмитит при изменении ЛЮБОГО ключа
    // (визуализатор, тема, активный пакет из службы...), и без сравнения каждая такая запись
    // перезаписывала черновик сохранённым пресетом.
    private var lastUserPresetsJson: String? = null
    private var lastActivePresetJson: String? = null

    val activePresetFlow: Flow<PresetModel> = context.dataStore.data.map { preferences ->
        // Чтение JSON с диска в Dispatchers.IO выполняется автоматически внутри Flow из DataStore
        // Это больше не блокирует UI-поток при старте приложения

        val userPresetsJson = preferences[userPresetsKey]
        val userPresetsChanged = userPresetsJson != lastUserPresetsJson
        lastUserPresetsJson = userPresetsJson
        if (userPresetsChanged && !userPresetsJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<Map<String, PresetModel>>() {}.type
                val loadedMap: Map<String, PresetModel?>? = gson.fromJson(userPresetsJson, type)
                if (loadedMap != null) {
                    userPresetsMap = loadedMap
                        .filterValues { it != null }
                        .mapValues { (key, p) -> sanitizePreset(p!!, key) }
                }
            } catch (e: Exception) {
                Log.e("equX_DataManager", "Ошибка чтения пользовательских пресетов: ${e.message}", e)
            }
        }

        val json = preferences[currentPresetKey]
        if (isDataLoaded && json == lastActivePresetJson) {
            return@map originalPreset
        }
        lastActivePresetJson = json
        val loadedRaw = if (json != null) {
            try {
                gson.fromJson(json, PresetModel::class.java) ?: createDefaultPreset(DEFAULT_PRESET_NAME)
            } catch (e: Exception) {
                createDefaultPreset(DEFAULT_PRESET_NAME)
            }
        } else {
            createDefaultPreset(DEFAULT_PRESET_NAME)
        }

        val loaded = sanitizePreset(loadedRaw, DEFAULT_PRESET_NAME)

        originalPreset = loaded
        liveDraftPreset = loaded.copy(rawBoundDevices = loaded.boundDevices, rawBoundApps = loaded.boundApps)
        saveBootCachePreset(loaded)
        isDataLoaded = true
        loaded
    }

    fun updateLiveDraft(updatedPreset: PresetModel) {
        liveDraftPreset = updatedPreset
    }

    suspend fun activatePreset(preset: PresetModel) {
        withContext(Dispatchers.Main) {
            val safePreset = preset.copy(rawBoundDevices = preset.boundDevices, rawBoundApps = preset.boundApps)
            originalPreset = safePreset.copy()
            liveDraftPreset = safePreset.copy()
            saveBootCachePreset(originalPreset)
            val json = gson.toJson(originalPreset)
            context.dataStore.edit { preferences ->
                preferences[currentPresetKey] = json
            }
        }
    }

    fun hasUnsavedChanges(): Boolean = hasUnsavedChanges(originalPreset, liveDraftPreset)

    suspend fun saveCurrentDraftAsActive() {
        if (defaultPresetsMap.containsKey(liveDraftPreset.presetName)) {
            val safeDraft = liveDraftPreset.copy(rawBoundDevices = liveDraftPreset.boundDevices, rawBoundApps = liveDraftPreset.boundApps)
            originalPreset = safeDraft
            saveBootCachePreset(originalPreset)
            val json = gson.toJson(originalPreset)

            val updatedUserMap = userPresetsMap.toMutableMap()
            updatedUserMap[safeDraft.presetName] = safeDraft
            userPresetsMap = updatedUserMap

            val userPresetsJson = gson.toJson(userPresetsMap)

            context.dataStore.edit { preferences ->
                preferences[currentPresetKey] = json
                preferences[userPresetsKey] = userPresetsJson
            }
            return
        }

        originalPreset = liveDraftPreset.copy(rawBoundDevices = liveDraftPreset.boundDevices, rawBoundApps = liveDraftPreset.boundApps)
        saveBootCachePreset(originalPreset)
        val json = gson.toJson(originalPreset)

        val presetName = originalPreset.presetName
        val updatedUserMap = userPresetsMap.toMutableMap()
        updatedUserMap[presetName] = originalPreset
        userPresetsMap = updatedUserMap

        val userPresetsJson = gson.toJson(userPresetsMap)

        context.dataStore.edit { preferences ->
            preferences[currentPresetKey] = json
            preferences[userPresetsKey] = userPresetsJson
        }
    }

    fun discardChanges() {
        liveDraftPreset = originalPreset.copy(rawBoundDevices = originalPreset.boundDevices, rawBoundApps = originalPreset.boundApps)
    }

    /**
     * «Создать»: новый пресет — копия текущего звука (с несохранёнными правками), БЕЗ привязок
     * (иначе у двух пресетов одни и те же устройства/приложения и правила конфликтов нарушаются).
     * Возвращает false, если имя недопустимо или занято (UI проверяет это заранее).
     */
    suspend fun saveAsNewPreset(rawName: String): Boolean {
        val newName = rawName.trim()
        if (presetNameError(newName, presetNames()) != null) return false
        liveDraftPreset = liveDraftPreset.copy(
            presetName = newName,
            rawBoundDevices = emptyList(),
            rawBoundSources = emptyList(),
            rawBoundApps = emptyList()
        )
        originalPreset = liveDraftPreset.copy()
        saveBootCachePreset(originalPreset)
        val json = gson.toJson(originalPreset)

        val updatedUserMap = userPresetsMap.toMutableMap()
        updatedUserMap[newName] = originalPreset
        userPresetsMap = updatedUserMap

        val userPresetsJson = gson.toJson(userPresetsMap)

        context.dataStore.edit { preferences ->
            preferences[currentPresetKey] = json
            preferences[userPresetsKey] = userPresetsJson
        }
        return true
    }

    /**
     * Переименование пользовательского пресета. Своё имя не считается занятым (можно сменить
     * регистр). Несохранённые правки активного пресета сохраняются — меняется только имя.
     * Возвращает false, если имя недопустимо или занято.
     */
    suspend fun renameUserPreset(oldName: String, rawNewName: String): Boolean {
        val newName = rawNewName.trim()
        if (defaultPresetsMap.containsKey(oldName)) return false
        if (newName == oldName) return true
        if (presetNameError(newName, presetNames(), ownName = oldName) != null) return false
        val existingPreset = userPresetsMap[oldName] ?: return false

        val updatedUserMap = userPresetsMap.toMutableMap()
        updatedUserMap.remove(oldName)

        val renamedPreset = existingPreset.copy(presetName = newName, rawBoundDevices = existingPreset.boundDevices, rawBoundApps = existingPreset.boundApps)
        updatedUserMap[newName] = renamedPreset
        userPresetsMap = updatedUserMap

        val userPresetsJson = gson.toJson(userPresetsMap)

        if (liveDraftPreset.presetName == oldName) {
            val hadDraft = hasUnsavedChanges()
            originalPreset = renamedPreset.copy()
            liveDraftPreset = if (hadDraft) liveDraftPreset.copy(presetName = newName) else renamedPreset.copy()
            saveBootCachePreset(originalPreset)
            val activeJson = gson.toJson(originalPreset)
            // Помечаем как применённые, чтобы activePresetFlow не перезаписал черновик.
            lastActivePresetJson = activeJson
            lastUserPresetsJson = userPresetsJson
            context.dataStore.edit { preferences ->
                preferences[currentPresetKey] = activeJson
                preferences[userPresetsKey] = userPresetsJson
            }
        } else {
            context.dataStore.edit { preferences ->
                preferences[userPresetsKey] = userPresetsJson
            }
        }
        return true
    }

    suspend fun deleteUserPreset(presetName: String) {
        if (defaultPresetsMap.containsKey(presetName)) return
        val updatedUserMap = userPresetsMap.toMutableMap()
        updatedUserMap.remove(presetName)
        userPresetsMap = updatedUserMap
        val userPresetsJson = gson.toJson(userPresetsMap)
        context.dataStore.edit { preferences ->
            preferences[userPresetsKey] = userPresetsJson
        }

        if (liveDraftPreset.presetName == presetName) {
            val defaultPreset = createDefaultPreset(DEFAULT_PRESET_NAME)
            activatePreset(defaultPreset)
        }
    }

    /**
     * Проверяет, существует ли уже пресет с указанным именем (включая «По умолчанию»).
     */
    /** Имена всех пресетов (встроенные + пользовательские) для проверки уникальности. */
    fun presetNames(): List<String> = getAllPresets().map { it.presetName }

    /**
     * Экспортирует конкретный пресет в файл ТОЛЬКО с аудио-настройками.
     * Привязки устройств/приложений не экспортируются: на другом устройстве они неактуальны.
     * Принимает пресет явно, чтобы не зависеть от liveDraftPreset (черновика).
     */
    fun exportPresetToExternalFile(targetFile: File, preset: PresetModel): Boolean {
        return try {
            val portablePreset = preset.copy(
                rawApp = EQUX_PRESET_MARKER,
                rawBoundDevices = emptyList(),
                rawBoundSources = emptyList(),
                rawBoundApps = emptyList()
            )
            val jsonString = gson.toJson(portablePreset)
            targetFile.writeText(jsonString)
            true
        } catch (e: Exception) {
            Log.e("equX_DataManager", "Ошибка экспорта в ${targetFile.path}: ${e.message}", e)
            false
        }
    }

    /**
     * Читает пресет из файла БЕЗ сохранения в DataStore. Нужно для предварительной проверки
     * имени и показа диалога, прежде чем выполнить импорт.
     */
    // Валидация — sanitizePreset() (PresetSanitizer.kt): тот же код на всех входах (файл, DataStore, DE-кэш).
    fun parsePresetFromFile(sourceFile: File): PresetModel? {
        return try {
            val jsonString = sourceFile.readText()
            if (!isEquXPresetJson(jsonString)) return null
            val importedPreset = gson.fromJson(jsonString, PresetModel::class.java) ?: return null
            if (importedPreset.formatVersion > PRESET_FORMAT_VERSION) {
                Log.w("equX_DataManager", "Пресет формата v${importedPreset.formatVersion} новее поддерживаемого " +
                    "v$PRESET_FORMAT_VERSION: читаем известные поля")
            }
            // Игнорируем привязки из файла: на текущем устройстве они могут не существовать.
            sanitizePreset(importedPreset).copy(
                rawBoundDevices = emptyList(),
                rawBoundSources = emptyList(),
                rawBoundApps = emptyList()
            )
        } catch (e: Exception) {
            Log.e("equX_DataManager", "Ошибка чтения пресета из ${sourceFile.path}: ${e.message}", e)
            null
        }
    }

    /**
     * Проверяет, что JSON является валидным пресетом equX: корневой объект содержит
     * поле `app` со значением [EQUX_PRESET_MARKER].
     */
    private fun isEquXPresetJson(jsonString: String): Boolean {
        return try {
            val root = JsonParser.parseString(jsonString)?.asJsonObject ?: return false
            root.get("app")?.asString == EQUX_PRESET_MARKER &&
                !root.get("presetName")?.asString.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Импортирует уже проверенный пресет под указанным именем и делает его активным.
     * Имя должно быть допустимым и свободным (UI подбирает его через [suggestPresetName]);
     * иначе возвращается null и ничего не перезаписывается.
     */
    // TODO(обсудить): пресет с машины, импортированный на телефон, приносит включённый Car (GALA/скоростной EQ).
    //  Привязки при импорте обнуляются, но в ручном режиме такой пресет активен — на телефоне с источником GPS
    //  это включит GPS при воспроизведении. Варианты: выключать Car при импорте на не-ГУ, спрашивать, оставить.
    suspend fun importPreset(importedPreset: PresetModel): PresetModel? {
        return try {
            val presetName = importedPreset.presetName.trim()
            // Имя проверяет и при необходимости исправляет UI (suggestPresetName) ДО вызова.
            if (presetNameError(presetName, presetNames()) != null) return null
            // Привязки не импортируем: переносим только аудио-настройки.
            val validPreset = sanitizePreset(importedPreset.copy(
                presetName = presetName,
                rawBoundDevices = emptyList(),
                rawBoundSources = emptyList(),
                rawBoundApps = emptyList()
            ))

            originalPreset = validPreset.copy()
            liveDraftPreset = validPreset.copy()
            saveBootCachePreset(validPreset)

            val updatedUserMap = userPresetsMap.toMutableMap()
            updatedUserMap[presetName] = validPreset
            userPresetsMap = updatedUserMap

            val activeJson = gson.toJson(validPreset)
            val userPresetsJson = gson.toJson(userPresetsMap)

            context.dataStore.edit { preferences ->
                preferences[currentPresetKey] = activeJson
                preferences[userPresetsKey] = userPresetsJson
            }

            validPreset
        } catch (e: Exception) {
            Log.e("equX_DataManager", "Ошибка импорта пресета: ${e.message}", e)
            null
        }
    }
}
