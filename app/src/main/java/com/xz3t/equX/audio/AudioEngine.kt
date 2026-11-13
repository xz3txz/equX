package com.xz3t.equX.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.xz3t.equX.data.EquXLog
import com.xz3t.equX.data.LoudnessConfig
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.SpeedEqBand
import com.xz3t.equX.data.effectiveLimiterParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class AudioEngine {

    companion object {
        /** Приоритет Virtualizer «Пространства» (как у DP). */
        private const val VIRTUALIZER_PRIORITY = 100
        /** Если управление у кого-то с приоритетом выше — пересоздаём с этим. */
        private const val VIRTUALIZER_PRIORITY_TAKEOVER = 1000
        /** Порядок фоллбэка режимов: BINAURAL (рабочий на ГУ), затем AUTO. TRANSAURAL бандл отвергает. */
        private val VIRTUALIZER_MODES = intArrayOf(
            Virtualizer.VIRTUALIZATION_MODE_BINAURAL,
            Virtualizer.VIRTUALIZATION_MODE_AUTO,
        )

        @Volatile
        private var globalVisualizerCaptureEnabled = false

        // Виден ли экран со спектром (Activity в RESUMED). Захват идёт только при видимом UI:
        // иначе фоновые события (пауза/плей, запись в DataStore) снова включали Visualizer.
        @Volatile
        private var visualizerUiVisible = false
    }

    // Все записи в DSP (DynamicsProcessing/Virtualizer) и общие массивы активных значений — под этой
    // блокировкой. Пишут разные потоки: плавный переход (Default), скорость (IO/binder), ползунки (Main),
    // служба (IO). Без неё операции «копия конфига → правка → set» перемешивались: тик скорости во время
    // драга мог записать старые гейны. Блокировка реентерабельная (вложенные вызовы безопасны).
    // Порядок блокировок: dspLock → visualizerLock, обратного нет.
    private val dspLock = Any()

    private inline fun <T> dsp(block: () -> T): T = synchronized(dspLock, block)

    // Поколение плавного перехода: шаг устаревшего перехода, дождавшийся блокировки после старта
    // нового, не должен записать свои значения поверх.
    private var rampGeneration = 0

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var virtualizer: Virtualizer? = null
    private var visualizer: Visualizer? = null

    // Контекст приложения нужен для проверки RECORD_AUDIO перед созданием Visualizer.
    private var appContext: Context? = null

    // Сериализует создание/настройку/включение Visualizer. Без этого при восстановлении
    // isVisualizerActive=true и параллельном initAudioEffects возникала гонка:
    // объект успевал включиться до setCaptureSize -> "setCaptureSize() called in wrong state: 2".
    private val visualizerLock = Any()

    var currentSessionId: Int = 0
        private set
    private var isEngineEnabled: Boolean = true

    var onFftDataUpdate: ((FloatArray) -> Unit)? = null

    // Сглаживание для реалистичного спектроанализатора
    private val smoothedMagnitudes = FloatArray(16)
    private val attack = 0.9f
    private val release = 0.7f

    // Частота дискретизации Visualizer. Нужна, чтобы корректно сопоставить
    // FFT-бин с частотой: freq = bin * sampleRate / captureSize.
    @Volatile
    private var visualizerSampleRate = 44100

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    // Ограничиваем частоту отправки спектра в UI до ~30 fps. Нативный захват может
    // идти с частотой до 20 кГц, но Compose-рекомпозиция на каждый кадр захвата —
    // избыточна и вызывает лишние рекомпозиции/GC (видно в Perfetto-трассе).
    private var lastFftPushMs = 0L

    // Сторож для визуализатора: время последнего пришедшего кадра захвата.
    // Нужно, чтобы на глобальной сессии (0) ловить «тихую смерть» Visualizer —
    // объект остаётся alive, но данные перестают приходить после смены маршрута.
    @Volatile
    private var lastVisualizerDataAt = 0L

    // Время последнего кадра с реальным сигналом (не тишина). На глобальной сессии
    // «отвалившийся» Visualizer часто продолжает слать нулевые кадры, поэтому одного
    // lastVisualizerDataAt недостаточно, чтобы заметить зависание.
    @Volatile
    private var lastVisualizerSignalAt = 0L

    // Когда Visualizer был создан в последний раз — точка отсчёта для сторожа.
    @Volatile
    private var visualizerCreatedAt = 0L

    val bandsFrequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val activeCustomFrequenciesHz = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val activeEqGainsDb = FloatArray(10) { 0f }
    private var isEqActiveInEngine = true

    // Текущее состояние компрессора/лимитера — нужно для плавной интерполяции параметров
    // при смене пресета (аналогично activeEqGainsDb).
    private var compressorEnabled = false
    private val activeCompressorBands = Array(10) { CompressorBandState() }
    private var limiterEnabled = false
    private var activeLimiter = LimiterState()
    private var currentFxEnabled = false
    private var currentSpatialEnabled = false
    private var current3dPercent = 0      // ширина (Virtualizer)
    private var currentAirPercent = 0     // подъём верхов (PreEQ)

    // Тонкомпенсация (ISO 226): подъём полос PreEQ по системной громкости + запас на входе DP.
    @Volatile
    private var loudness = LoudnessConfig()
    val loudnessEnabled: Boolean get() = loudness.isEnabled
    private var loudnessVolume: Int? = null
    private var loudnessVolumeMax: Int? = null
    private val activeLoudnessGainsDb = FloatArray(10)
    private val _loudnessStatus = MutableStateFlow(LoudnessStatus())
    val loudnessStatus: StateFlow<LoudnessStatus> = _loudnessStatus.asStateFlow()

    // Служба перечитывает громкость, когда тонкомпенсация включилась, а громкость ещё неизвестна.
    var onLoudnessVolumeNeeded: (() -> Unit)? = null

    var lastAppliedPreset: PresetModel? = null
        private set
    private var lastRebindTimestampMs = 0L

    private var lastWaveform: ByteArray? = null
    private var lastFft: ByteArray? = null

    // Плавное переключение пресета. Включается/настраивается в UI: галочка + длительность.
    private val rampScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eqRampJob: Job? = null
    private val rampStepMs = 16L

    @Volatile
    var smoothTransitionEnabled: Boolean = true

    @Volatile
    var smoothTransitionDurationMs: Long = 3_000L

    // --- Скоростная компенсация звука (Car) ---
    // Движок отвечает только за скоростной EQ. Громкость (GALA) — исключительно системной
    // громкостью в EqualizerService: подъём через постгейн лимитера давал хруст уже на +3 дБ.
    @Volatile
    var carEnabled: Boolean = false
    @Volatile
    var galaMinSpeed: Int = 40
    @Volatile
    var galaMaxSpeed: Int = 120
    @Volatile
    var carEqEnabled: Boolean = false
    @Volatile
    var carEqBands: List<SpeedEqBand> = emptyList()

    // Последний применённый к движку CarConfig — из пресета или из черновика UI.
    // Служба берёт отсюда настройки системной GALA-громкости, чтобы она совпадала
    // с тем, что реально играет, а не с сохранённым пресетом.
    @Volatile
    var carConfig: com.xz3t.equX.data.CarConfig = com.xz3t.equX.data.CarConfig()
        private set

    // Вызывается после каждого applyCarConfig (служба пересчитывает системную громкость).
    var onCarConfigApplied: ((com.xz3t.equX.data.CarConfig) -> Unit)? = null

    @Volatile
    private var currentVehicleSpeed: Float = 0f
    private val activeSpeedEqGainsDb = FloatArray(10)

    /**
     * Единственная точка входа для скорости. Приложение не различает реальную и
     * виртуальную скорость: и Car API, и отладочный слайдер просто задают «текущую».
     */
    fun setVehicleSpeed(speedKmh: Float) {
        dsp {
            if (this.currentVehicleSpeed == speedKmh) return
            this.currentVehicleSpeed = speedKmh
            updateCarEqGain()
        }
    }

    /**
     * Применяет настройки Car (GALA + скоростной EQ) из пресета к движку.
     */
    fun applyCarConfig(car: com.xz3t.equX.data.CarConfig) {
        dsp {
            carConfig = car
            carEnabled = car.isEnabled
            galaMinSpeed = car.minSpeed
            galaMaxSpeed = car.maxSpeed
            carEqEnabled = car.eqEnabled
            carEqBands = car.bands
            updateCarEqGain()
            onCarConfigApplied?.invoke(car)
        }
    }

    private fun eqActive(): Boolean = carEnabled && carEqEnabled

    private fun speedProgress(): Float {
        if (currentVehicleSpeed <= galaMinSpeed) return 0f
        if (galaMaxSpeed <= galaMinSpeed) return 0f
        return ((currentVehicleSpeed - galaMinSpeed) / (galaMaxSpeed - galaMinSpeed)).coerceIn(0f, 1f)
    }

    /** Скоростной эквалайзер: поднимает/опускает включённые частоты пропорционально скорости. */
    fun updateCarEqGain() {
        dsp {
            val progress = speedProgress()
            val target = FloatArray(10)
            if (eqActive()) {
                for (band in carEqBands) {
                    if (!band.isEnabled) continue
                    val idx = bandsFrequencies.indexOfFirst { kotlin.math.abs(it - band.frequencyHz) < 1f }
                    if (idx in 0..9) {
                        target[idx] += band.gainDb * progress
                    }
                }
            }
            if (!target.contentEquals(activeSpeedEqGainsDb)) {
                target.copyInto(activeSpeedEqGainsDb)
                applyEqToDsp(isEqActiveInEngine)
            }
        }
    }

    /** Числовые параметры одной полосы компрессора (для интерполяции при смене пресета). */
    private data class CompressorBandState(
        val isEnabled: Boolean = false,
        val thresholdDb: Float = 0f,
        val ratio: Float = 1f,
        val kneeDb: Float = 0f,
        val attackMs: Float = 15f,
        val releaseMs: Float = 80f,
        val noiseGateThresholdDb: Float = -90f,
        val expanderRatio: Float = 1f,
        val preGainDb: Float = 0f,
        val postGainDb: Float = 0f,
    )

    /** Числовые параметры лимитера (для интерполяции при смене пресета). */
    private data class LimiterState(
        val isEnabled: Boolean = false,
        val ratio: Float = 1f,
        val attackMs: Float = 1f,
        val thresholdDb: Float = 0f,
        val releaseMs: Float = 60f,
        val postGainDb: Float = 0f,
    )

    /**
     * Пересоздаёт эффекты на текущей сессии после смены устройства вывода.
     * На vivo (Android 16) после переподключения Bluetooth цепочка session 0 оставалась на
     * выходе динамика, а музыка шла в выход A2DP без эффектов. Новый глобальный эффект
     * политика вешает на текущий выход музыки, поэтому мягкое пересоздание переносит цепочку.
     */
    fun reattachEffects(reason: String) {
        EquXLog.i("equX_AudioEngine", "Пересоздание эффектов: $reason")
        initAudioEffects(currentSessionId, force = true)
    }

    @Suppress("DEPRECATION")
    fun initAudioEffects(sessionId: Int = 0, force: Boolean = false) {
        dsp {
            // Блокировка от пересоздания движка на той же сессии (Защита от хрустов при перемотке)
            if (!force && this.currentSessionId == sessionId && dynamicsProcessing != null) {
                EquXLog.i("equX_AudioEngine", "Эффекты уже активны для сессии $sessionId. Пропуск пересоздания (защита от хруста).")
                rebindGlobalEffects()
                return
            }

            EquXLog.i("equX_AudioEngine", "=== Плавная инициализация эффектов для сессии $sessionId (Priority 100) ===")

            // Мягкое пересоздание: сохраняем старые эффекты и освобождаем их только после
            // успешной привязки новых к новой сессии (убирает «дырку» release→create при перемотке).
            val oldDynamics = dynamicsProcessing
            val oldVirtualizer = virtualizer
            val oldVisualizer = visualizer

            // 1. 3D-виртуализатор «Пространства». Сила, режим и включение — в updateVirtualizer()
            // (после создания DP ниже), тем же порядком, что у рабочего тестового Virtualizer.
            // Пока старый Virtualizer не освобождён, у нового того же приоритета управления нет —
            // проверку управления (фоллбэк на повышенный приоритет) откладываем до release ниже.
            virtualizerControlCheck = false
            virtualizer = createVirtualizer(sessionId, VIRTUALIZER_PRIORITY)
            virtualizerEnabledAt = 0L // новый эффект — заново ждём перехода в режим для индикатора

            // 2. Инициализируем DynamicsProcessing с высшим приоритетом 100 (Захват мастер-микшера Audio Flinger)
            try {
                val builderConfig = DynamicsProcessing.Config.Builder(
                    0, 2,
                    true, 10, // PreEQ (Зарезервирован под 3D Spatial Audio)
                    true, 10, // MBC (Компрессор)
                    true, 10, // PostEQ (10-полосный Эквалайзер)
                    true      // Limiter (Лимитер)
                ).build()

                dynamicsProcessing = DynamicsProcessing(100, sessionId, builderConfig).apply {
                    for (channel in 0 until 2) {
                        // А. Выделенный Pre-EQ каскад (ТОЛЬКО для 3D Spatial Audio)
                        val preEq = getPreEqByChannelIndex(channel)
                        if (preEq != null) {
                            preEq.isEnabled = false
                            for (bandIndex in 0 until 10) {
                                val band = preEq.getBand(bandIndex)
                                if (band != null) {
                                    band.cutoffFrequency = bandsFrequencies[bandIndex]
                                    band.gain = 0.0f
                                    band.isEnabled = true
                                    preEq.setBand(bandIndex, band)
                                }
                            }
                            setPreEqByChannelIndex(channel, preEq)
                        }

                        // Б. Выделенный Post-EQ каскад (ТОЛЬКО для 10-полосного Эквалайзера)
                        val postEq = getPostEqByChannelIndex(channel)
                        if (postEq != null) {
                            postEq.isEnabled = true
                            for (bandIndex in 0 until 10) {
                                val band = postEq.getBand(bandIndex)
                                if (band != null) {
                                    band.cutoffFrequency = activeCustomFrequenciesHz[bandIndex]
                                    band.gain = activeEqGainsDb[bandIndex]
                                    band.isEnabled = true
                                    postEq.setBand(bandIndex, band)
                                }
                            }
                            setPostEqByChannelIndex(channel, postEq)
                        }

                        // В. Кроссовер-компрессор (MBC)
                        val mbc = getMbcByChannelIndex(channel)
                        if (mbc != null) {
                            mbc.isEnabled = true
                            for (bandIndex in 0 until 10) {
                                val mbcBand = mbc.getBand(bandIndex)
                                if (mbcBand != null) {
                                    mbcBand.cutoffFrequency = bandsFrequencies[bandIndex]
                                    mbcBand.isEnabled = true
                                    mbcBand.threshold = 0.0f
                                    mbcBand.ratio = 1.0f
                                    mbcBand.kneeWidth = 0.0f
                                    mbcBand.attackTime = 15f
                                    mbcBand.releaseTime = 80f
                                    mbcBand.noiseGateThreshold = -90f
                                    mbcBand.expanderRatio = 1.0f
                                    mbcBand.preGain = 0.0f
                                    mbcBand.postGain = 0.0f
                                    mbc.setBand(bandIndex, mbcBand)
                                }
                            }
                            setMbcByChannelIndex(channel, mbc)
                        }

                        // Г. Лимитер (по умолчанию 100% линейный bypass ratio = 1.0)
                        val limiter = getLimiterByChannelIndex(channel)
                        if (limiter != null) {
                            limiter.isEnabled = false
                            limiter.ratio = 1.0f
                            limiter.threshold = 0.0f
                            limiter.postGain = 0.0f
                            setLimiterByChannelIndex(channel, limiter)
                        }
                    }
                }

                // Предварительно накатываем сохраненный пресет НА КАСКАДЫ ДО ВКТЮЧЕНИЯ ДВИЖКА (исключает холодный скачок/хруст)
                lastAppliedPreset?.let { preset ->
                    applyFullPreset(preset, smooth = false)
                }

                dynamicsProcessing?.enabled = isEngineEnabled
                EquXLog.i("equX_AudioEngine", "[+] DynamicsProcessing создано и плавно запущенно (session=$sessionId, priority=100)")
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "[-] DynamicsProcessing недоступен для session=$sessionId: ${e.message}")
                if (sessionId != 0) {
                    Log.w("equX_AudioEngine", "Автоматический откат с сессии $sessionId на GLOBAL (0)")
                    oldDynamics?.release()
                    oldVirtualizer?.release()
                    oldVisualizer?.release()
                    virtualizer?.release()
                    dynamicsProcessing = null
                    virtualizer = null
                    visualizer = null
                    initAudioEffects(0)
                    return
                } else {
                    dynamicsProcessing = null
                }
            }

            // Устанавливаем новую сессию и сбрасываем поле Visualizer (старый освободим ниже).
            currentSessionId = sessionId
            visualizer = null

            // 3. Инициализируем Visualizer (По умолчанию выключен)
            try {
                initVisualizer()
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "[-] Visualizer недоступен для session=$sessionId: ${e.message}")
            }

            // Освобождаем старые эффекты после успешной привязки новых (мягкое пересоздание).
            oldDynamics?.release()
            oldVirtualizer?.release()
            oldVisualizer?.release()
            virtualizerControlCheck = true
            updateVirtualizer()

            EquXLog.d("equX_AudioEngine", "Инициализация аудиоэффектов equX завершена для сессии: $currentSessionId")
        }
    }

    /**
     * Лёгкое подтверждение активности C++ аудиоэффектов без разрыва буфера.
     *
     * НЕ выполняет тяжёлое [applyFullPreset]: во время перемотки/скраббинга плеер
     * многократно пересоздаёт AudioTrack, и если здесь каждый раз прогонять полную
     * конфигурацию DSP (10 полос PostEQ + 10 полос MBC + лимитер на 2 канала),
     * это перегружает аудио-стек и может подвешивать воспроизведение.
     *
     * Здесь только переподтверждаем enabled у уже созданных эффектов.
     */
    fun rebindGlobalEffects() {
        dsp {
            val now = System.currentTimeMillis()
            if (now - lastRebindTimestampMs < 800) {
                return
            }
            lastRebindTimestampMs = now

            if (!isEngineEnabled) return
            try {
                EquXLog.i("equX_AudioEngine", "Плавное подтверждение активности C++ аудиоэффектов без сброса PCM-буфера")
                dynamicsProcessing?.let { dp ->
                    if (!dp.enabled) {
                        dp.enabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка при плавном прихвате эффектов: ${e.message}")
            }
            // Раньше здесь Virtualizer включался по одной силе (без галочки «Пространства» и FX)
            // и без режима; теперь — общий путь с проверкой управления.
            updateVirtualizer()
        }
    }

    /**
     * Пакетное применение полного профиля пресета к аппаратному DSP движку.
     * EQ / компрессор / лимитер раскатываются плавно (ramp ~1 сек), чтобы смена
     * пресета не давала резкого скачка тембра и громкости.
     */
    fun applyFullPreset(preset: PresetModel, smooth: Boolean = true) {
        dsp {
            // Если уже идёт плавный переход к этому же пресету, а пришёл мгновенный дубль
            // (например, activePresetFlow после activatePreset) — НЕ прерываем ramp.
            // Сравниваем по имени, а не по equals: списки привязок могут отличаться и давать
            // ложное «не равно», из-за чего плавный переход обрывался на полпути.
            if (!smooth && eqRampJob?.isActive == true && lastAppliedPreset?.presetName == preset.presetName) {
                return
            }

            this.lastAppliedPreset = preset
            EquXLog.i("equX_AudioEngine", "Применение профиля [${preset.presetName}]: EQ=${preset.equalizer.isEnabled}, Comp=${preset.compressor.isEnabled}, 3D=${preset.spatial3d.isEnabled}, Limiter=${preset.limiter.isEnabled}, smooth=$smooth")

            // 1. Целевые значения EQ.
            val isEqEnabled = preset.equalizer.isEnabled

            val targetGains = FloatArray(10)
            for (i in 0 until 10) {
                activeCustomFrequenciesHz[i] = preset.equalizer.customFrequenciesHz.getOrElse(i) { bandsFrequencies[i] }
                targetGains[i] = preset.equalizer.gainsDb.getOrElse(i) { 0f }
            }

            // 2. Целевые значения компрессора.
            val isCompEnabled = preset.compressor.isEnabled
            val targetCompBands = Array(10) { i ->
                val band = preset.compressor.bands.getOrNull(i)
                if (band != null && isCompEnabled && band.isPreEqEnabled) {
                    CompressorBandState(
                        isEnabled = true,
                        thresholdDb = band.thresholdDb,
                        ratio = band.ratio,
                        kneeDb = band.kneeDb,
                        attackMs = band.attackMs,
                        releaseMs = band.releaseMs,
                        noiseGateThresholdDb = band.noiseThresholdDb,
                        expanderRatio = band.expanderRatio,
                        preGainDb = band.preGainDb,
                        postGainDb = band.postGainDb,
                    )
                } else {
                    CompressorBandState()
                }
            }

            // 3. Целевые значения лимитера.
            val isLimiterEnabled = preset.limiter.isEnabled
            val derived = effectiveLimiterParams(preset)
            val targetLimiter = if (isLimiterEnabled) {
                LimiterState(
                    isEnabled = true,
                    ratio = derived.ratio.coerceAtLeast(1.0f),
                    attackMs = derived.attackMs.coerceAtLeast(0.1f),
                    thresholdDb = derived.thresholdDb,
                    releaseMs = derived.releaseMs,
                    postGainDb = preset.limiter.postGainDb,
                )
            } else {
                LimiterState()
            }

            // 4. FX-модуль (пространственный эффект) тоже раскатываем плавно при авто-переходе —
            // именно он даёт самую заметную «смену характера» при переключении на дефолт.
            startPresetRamp(
                eqEnabled = isEqEnabled,
                targetEqGains = targetGains,
                compEnabled = isCompEnabled,
                targetCompBands = targetCompBands,
                limiterEnabled = isLimiterEnabled,
                targetLimiter = targetLimiter,
                fxEnabled = preset.fxEnabled,
                spatialEnabled = preset.spatial3d.isEnabled,
                targetSpatialStrength = preset.spatial3d.strengthPercent,
                targetSpatialAir = preset.spatial3d.airPercent,
                smooth = smooth,
            )

            // 5. Тонкомпенсация зависит от громкости, а не от перехода — применяем сразу.
            setLoudnessConfigLocked(preset.loudness)

            // 6. Car (GALA + скоростной эквалайзер) применяем сразу — это глобальная
            // скоростная компенсация, а не плавный переход между пресетами.
            applyCarConfig(preset.car)
        }
    }

    /**
     * Плавно раскатывает EQ, компрессор и лимитер от текущих значений к целевым.
     * Предыдущий незавершённый ramp отменяется, чтобы не накладывались два прохода.
     */
    private fun startPresetRamp(
        eqEnabled: Boolean,
        targetEqGains: FloatArray,
        compEnabled: Boolean,
        targetCompBands: Array<CompressorBandState>,
        limiterEnabled: Boolean,
        targetLimiter: LimiterState,
        fxEnabled: Boolean,
        spatialEnabled: Boolean,
        targetSpatialStrength: Int,
        targetSpatialAir: Int,
        smooth: Boolean,
    ) {
        eqRampJob?.cancel()
        val generation = ++rampGeneration
        val fromEq = activeEqGainsDb.copyOf()
        val fromCompBands = activeCompressorBands.copyOf()
        val fromLimiter = activeLimiter
        val fromFx = currentFxEnabled
        val fromSpatial = currentSpatialEnabled
        val fromSpatialStrength = current3dPercent
        val fromSpatialAir = currentAirPercent
        val fromEqEnabled = isEqActiveInEngine
        val fromCompEnabled = compressorEnabled
        val fromLimiterEnabled = this.limiterEnabled
        val targetStrength = if (spatialEnabled) targetSpatialStrength.coerceIn(0, 100) else 0
        val targetAir = if (spatialEnabled) targetSpatialAir.coerceIn(0, 100) else 0
        val startMs = System.currentTimeMillis()

        this.isEqActiveInEngine = eqEnabled
        this.compressorEnabled = compEnabled
        this.limiterEnabled = limiterEnabled

        // Ручные изменения (тогглы/ползунки) применяем мгновенно — пользователь хочет
        // слышать разницу сразу. Плавный переход только для авто-переключения.
        if (!smooth || !smoothTransitionEnabled) {
            targetEqGains.copyInto(activeEqGainsDb)
            for (i in 0 until 10) activeCompressorBands[i] = targetCompBands[i]
            activeLimiter = targetLimiter
            currentFxEnabled = fxEnabled
            currentSpatialEnabled = spatialEnabled
            current3dPercent = targetStrength
            currentAirPercent = targetAir
            applyEqToDsp(eqEnabled)
            applyCompressorToDsp(compEnabled)
            applyLimiterToDsp(limiterEnabled)
            updateVirtualizer()
            applyPreEq()
            return
        }

        val duration = smoothTransitionDurationMs.coerceAtLeast(1L)
        eqRampJob = rampScope.launch {
            while (isActive) {
                val done = dsp {
                    if (generation != rampGeneration) return@launch
                    val t = ((System.currentTimeMillis() - startMs).toFloat() / duration).coerceIn(0f, 1f)
                    for (i in 0 until 10) {
                        activeEqGainsDb[i] = lerp(fromEq[i], targetEqGains[i], t)
                        activeCompressorBands[i] = lerp(fromCompBands[i], targetCompBands[i], t)
                    }
                    activeLimiter = lerp(fromLimiter, targetLimiter, t)

                    // Пространственный эффект раскатываем по strength; флаги FX/3D держим
                    // включёнными до самого конца перехода, чтобы не было резкого среза.
                    current3dPercent = lerp(fromSpatialStrength.toFloat(), targetStrength.toFloat(), t).roundToInt()
                    currentAirPercent = lerp(fromSpatialAir.toFloat(), targetAir.toFloat(), t).roundToInt()
                    currentFxEnabled = if (t < 1f) (fromFx || fxEnabled) else fxEnabled
                    currentSpatialEnabled = if (t < 1f) (fromSpatial || spatialEnabled) else spatialEnabled

                    // Каскады держим включёнными, пока параметры не доехали до нейтральных —
                    // иначе отключение тоггла даст резкий скачок громкости.
                    applyEqToDsp(if (t < 1f) (fromEqEnabled || eqEnabled) else eqEnabled)
                    applyCompressorToDsp(if (t < 1f) (fromCompEnabled || compEnabled) else compEnabled)
                    applyLimiterToDsp(if (t < 1f) (fromLimiterEnabled || limiterEnabled) else limiterEnabled)
                    updateVirtualizer()
                    applyPreEq()
                    t >= 1f
                }

                if (done) break
                delay(rampStepMs)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun lerp(a: CompressorBandState, b: CompressorBandState, t: Float) = CompressorBandState(
        // Полосу держим включённой до конца перехода, если она была или будет активна —
        // иначе её отключение даёт резкий скачок басов/громкости, хотя параметры ещё едут.
        isEnabled = if (t < 1f) (a.isEnabled || b.isEnabled) else b.isEnabled,
        thresholdDb = lerp(a.thresholdDb, b.thresholdDb, t),
        ratio = lerp(a.ratio, b.ratio, t),
        kneeDb = lerp(a.kneeDb, b.kneeDb, t),
        attackMs = lerp(a.attackMs, b.attackMs, t),
        releaseMs = lerp(a.releaseMs, b.releaseMs, t),
        noiseGateThresholdDb = lerp(a.noiseGateThresholdDb, b.noiseGateThresholdDb, t),
        expanderRatio = lerp(a.expanderRatio, b.expanderRatio, t),
        preGainDb = lerp(a.preGainDb, b.preGainDb, t),
        postGainDb = lerp(a.postGainDb, b.postGainDb, t),
    )

    private fun lerp(a: LimiterState, b: LimiterState, t: Float) = LimiterState(
        isEnabled = b.isEnabled,
        ratio = lerp(a.ratio, b.ratio, t),
        attackMs = lerp(a.attackMs, b.attackMs, t),
        thresholdDb = lerp(a.thresholdDb, b.thresholdDb, t),
        releaseMs = lerp(a.releaseMs, b.releaseMs, t),
        postGainDb = lerp(a.postGainDb, b.postGainDb, t),
    )

    /**
     * Применяет текущие активные частоты/гейны EQ ко всем каналам DSP.
     */
    private fun applyEqToDsp(enabled: Boolean) {
        try {
            val dp = dynamicsProcessing ?: return
            for (channel in 0 until 2) {
                val postEq = dp.getPostEqByChannelIndex(channel) ?: continue
                postEq.isEnabled = enabled
                for (i in 0 until 10) {
                    val band = postEq.getBand(i) ?: continue
                    band.cutoffFrequency = activeCustomFrequenciesHz[i]
                    band.gain = activeEqGainsDb[i] + activeSpeedEqGainsDb[i]
                    band.isEnabled = true
                    postEq.setBand(i, band)
                }
                dp.setPostEqByChannelIndex(channel, postEq)
            }
            if (isEngineEnabled) {
                dp.enabled = true
            }
        } catch (e: Exception) {
            Log.e("equX_AudioEngine", "Ошибка применения Post-EQ ramp: ${e.message}")
        }
    }

    /**
     * Применяет текущее состояние компрессора ко всем каналам DSP.
     */
    private fun applyCompressorToDsp(enabled: Boolean) {
        try {
            val dp = dynamicsProcessing ?: return
            for (channel in 0 until 2) {
                val mbc = dp.getMbcByChannelIndex(channel) ?: continue
                mbc.isEnabled = enabled
                for (i in 0 until 10) {
                    val band = mbc.getBand(i) ?: continue
                    val s = activeCompressorBands[i]
                    band.isEnabled = enabled && s.isEnabled
                    band.threshold = s.thresholdDb
                    band.ratio = s.ratio
                    band.kneeWidth = s.kneeDb
                    band.attackTime = s.attackMs
                    band.releaseTime = s.releaseMs
                    band.noiseGateThreshold = s.noiseGateThresholdDb
                    band.expanderRatio = s.expanderRatio
                    band.preGain = s.preGainDb
                    band.postGain = s.postGainDb
                    mbc.setBand(i, band)
                }
                dp.setMbcByChannelIndex(channel, mbc)
            }
            if (isEngineEnabled) {
                dp.enabled = true
            }
        } catch (e: Exception) {
            Log.e("equX_AudioEngine", "Ошибка применения MBC ramp: ${e.message}")
        }
    }

    /**
     * Применяет текущее состояние лимитера ко всем каналам DSP.
     */
    private fun applyLimiterToDsp(enabled: Boolean) {
        try {
            val dp = dynamicsProcessing ?: return
            for (channel in 0 until 2) {
                val limiter = dp.getLimiterByChannelIndex(channel) ?: continue
                limiter.isEnabled = enabled
                limiter.ratio = if (enabled) activeLimiter.ratio else 1.0f
                limiter.attackTime = if (enabled) activeLimiter.attackMs else 1.0f
                limiter.threshold = if (enabled) activeLimiter.thresholdDb else 0.0f
                limiter.releaseTime = if (enabled) activeLimiter.releaseMs else 60.0f
                limiter.postGain = if (enabled) activeLimiter.postGainDb else 0.0f
                dp.setLimiterByChannelIndex(channel, limiter)
            }
            if (isEngineEnabled) {
                dp.enabled = true
            }
        } catch (e: Exception) {
            Log.e("equX_AudioEngine", "Ошибка применения Limiter ramp: ${e.message}")
        }
    }

    fun setEngineEnabled(enabled: Boolean) {
        dsp {
            // Идемпотентность: фоновый цикл вызывает updateEnginesBypassState() каждые 5 секунд.
            // Без этого guard мы каждый раз дёргаем нативные DynamicsProcessing/Virtualizer/Visualizer,
            // даже когда ничего не изменилось, — лишняя нагрузка на аудио-стек.
            if (this.isEngineEnabled == enabled) return

            this.isEngineEnabled = enabled
            EquXLog.i("equX_AudioEngine", "Глобальное состояние движка (Bypass): enabled=$enabled")
            try {
                dynamicsProcessing?.enabled = enabled
                updateVirtualizer()
                // Не затираем состояние захвата визуализатора: применяем его через единый источник правды.
                if (enabled && visualizer == null) {
                    initVisualizer()
                } else {
                    applyVisualizerState()
                }
                if (!enabled) {
                    resetVisualization()
                }
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка Bypass: ${e.message}")
            }
        }
    }

    private fun resetVisualization() {
        smoothedMagnitudes.fill(0f)
        onFftDataUpdate?.invoke(FloatArray(16))
    }

    fun setBandFrequencyAndGain(bandIndex: Int, frequencyHz: Float, gainDb: Float) {
        dsp {
            if (bandIndex in 0..9) {
                activeCustomFrequenciesHz[bandIndex] = frequencyHz
                activeEqGainsDb[bandIndex] = gainDb
            }
            // Горячий путь (каждый кадр перетаскивания) — только отладочное логирование (debug-сборка).
            EquXLog.d("equX_AudioEngine", "Изменение Post-EQ band $bandIndex -> freq=$frequencyHz Hz, gain=$gainDb dB")
            try {
                dynamicsProcessing?.let { dp ->
                    for (channel in 0 until 2) {
                        val postEq = dp.getPostEqByChannelIndex(channel)
                        if (postEq != null) {
                            val eqBand = postEq.getBand(bandIndex)
                            if (eqBand != null) {
                                eqBand.cutoffFrequency = frequencyHz
                                // Как в applyEqToDsp: EQ пресета + текущая прибавка скоростного EQ.
                                // Иначе ручной ползунок на ходу «съедал» скоростной EQ у этой полосы.
                                eqBand.gain = gainDb + (activeSpeedEqGainsDb.getOrNull(bandIndex) ?: 0f)
                                eqBand.isEnabled = true
                                postEq.setBand(bandIndex, eqBand)
                            }
                            dp.setPostEqByChannelIndex(channel, postEq)
                        }
                    }
                    if (isEngineEnabled) {
                        dp.enabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка Post-EQ: ${e.message}")
            }
        }
    }

    fun setComplexCompressor(
        bandIndex: Int,
        thresholdDb: Float,
        ratioValue: Float,
        attackMs: Float,
        releaseMs: Float,
        kneeDb: Float = 0f,
        noiseGateThresholdDb: Float = -90f,
        expanderRatio: Float = 1f,
        preGainDb: Float = 0f,
        postGainDb: Float = 0f,
        isEnabled: Boolean = true
    ) {
        dsp {
            EquXLog.d("equX_AudioEngine", "Изменение компрессора band $bandIndex -> thresh=$thresholdDb, ratio=$ratioValue, preGain=$preGainDb, postGain=$postGainDb, enabled=$isEnabled")
            try {
                dynamicsProcessing?.let { dp ->
                    for (channel in 0 until 2) {
                        val mbc = dp.getMbcByChannelIndex(channel)
                        if (mbc != null) {
                            mbc.isEnabled = isEngineEnabled
                            val mbcBand = mbc.getBand(bandIndex)
                            if (mbcBand != null) {
                                mbcBand.isEnabled = isEnabled
                                if (isEnabled) {
                                    mbcBand.threshold = thresholdDb
                                    mbcBand.ratio = ratioValue
                                    mbcBand.kneeWidth = kneeDb
                                    mbcBand.attackTime = attackMs
                                    mbcBand.releaseTime = releaseMs
                                    mbcBand.noiseGateThreshold = noiseGateThresholdDb
                                    mbcBand.expanderRatio = expanderRatio
                                    mbcBand.preGain = preGainDb
                                    mbcBand.postGain = postGainDb
                                } else {
                                    // 100% чистый линейный bypass полосы на C++ уровне
                                    mbcBand.threshold = 0.0f
                                    mbcBand.ratio = 1.0f
                                    mbcBand.kneeWidth = 0.0f
                                    mbcBand.preGain = 0.0f
                                    mbcBand.postGain = 0.0f
                                }
                                mbc.setBand(bandIndex, mbcBand)
                            }
                            dp.setMbcByChannelIndex(channel, mbc) // Гарантированная передача MBC каскада в C++ DSP!
                        }
                    }
                    if (isEngineEnabled) {
                        dp.enabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка компрессора: ${e.message}")
            }
        }
    }

    fun setLimiterParams(
        isEnabled: Boolean,
        thresholdDb: Float,
        releaseMs: Float,
        postGainDb: Float = 0.0f,
        attackMs: Float = 1.0f,
        ratio: Float = 10.0f,
    ) {
        dsp {
            EquXLog.d("equX_AudioEngine", "Изменение лимитера -> enabled=$isEnabled, thresh=$thresholdDb, rel=$releaseMs, postGain=$postGainDb, attack=$attackMs, ratio=$ratio")
            try {
                dynamicsProcessing?.let { dp ->
                    val limiter = DynamicsProcessing.Limiter(
                        true,
                        isEnabled,
                        1,
                        attackMs.coerceAtLeast(0.1f),
                        releaseMs,
                        if (isEnabled) ratio.coerceAtLeast(1.0f) else 1.0f, // 1:1 ratio при выключении
                        if (isEnabled) thresholdDb else 0.0f,
                        if (isEnabled) postGainDb else 0.0f
                    )
                    dp.setLimiterAllChannelsTo(limiter)
                    if (isEngineEnabled) {
                        dp.enabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка настройки лимитера: ${e.message}")
            }
        }
    }

    /**
     * Общий мастер-тумблер FX-модуля. Когда выключен — пространство и саб-бас не работают.
     */
    fun setFxEnabled(enabled: Boolean) {
        dsp {
            this.currentFxEnabled = enabled
            EquXLog.i("equX_AudioEngine", "FX master -> enabled=$enabled")
            updateVirtualizer()
            applyPreEq()
        }
    }

    /**
     * Включает/выключает пространственный эффект и задаёт его интенсивность.
     */
    fun setSpatial(enabled: Boolean, strengthPercent: Int, airPercent: Int) {
        dsp {
            this.currentSpatialEnabled = enabled
            this.current3dPercent = strengthPercent.coerceIn(0, 100)
            this.currentAirPercent = airPercent.coerceIn(0, 100)
            EquXLog.i("equX_AudioEngine", "Spatial -> enabled=$enabled, width=$current3dPercent%, air=$currentAirPercent%")
            updateVirtualizer()
            applyPreEq()
        }
    }

    /** Настройки тонкомпенсации из UI (черновик пресета). */
    fun setLoudness(config: LoudnessConfig) {
        dsp {
            setLoudnessConfigLocked(config)
            applyPreEq()
        }
    }

    /** Текущая системная громкость media (шаги) — от службы, по колбэку громкости. */
    fun setLoudnessVolume(volume: Int, max: Int) {
        dsp {
            if (loudnessVolume == volume && loudnessVolumeMax == max) return
            loudnessVolume = volume
            loudnessVolumeMax = max
            updateLoudnessGains()
            applyPreEq()
        }
    }

    private fun setLoudnessConfigLocked(config: LoudnessConfig) {
        if (loudness == config) return
        val wasEnabled = loudness.isEnabled
        loudness = config
        EquXLog.i("equX_AudioEngine", "Loudness -> enabled=${config.isEnabled}, strength=${config.strengthPercent}%, threshold=${config.thresholdPercent}%")
        updateLoudnessGains()
        // Пока тонкомпенсация выключена, громкость не отслеживается — при включении перечитываем.
        if (config.isEnabled && (!wasEnabled || loudnessVolume == null)) onLoudnessVolumeNeeded?.invoke()
    }

    private fun isLoudnessActive(): Boolean =
        isEngineEnabled && currentFxEnabled && loudness.isEnabled && loudness.strengthPercent > 0 && loudnessVolume != null

    private fun updateLoudnessGains() {
        val volume = loudnessVolume
        val max = loudnessVolumeMax
        if (!loudness.isEnabled || volume == null || max == null || max <= 0) {
            activeLoudnessGainsDb.fill(0f)
            _loudnessStatus.value = LoudnessStatus(volume = volume, maxVolume = max)
            return
        }
        val threshold = (max * loudness.thresholdPercent / 100f).roundToInt().coerceIn(1, max)
        val phon = LoudnessCurve.phonFor(volume, threshold, max)
        LoudnessCurve.gainsDb(phon, loudness.strengthPercent, bandsFrequencies, activeLoudnessGainsDb)
        val status = LoudnessStatus(
            volume = volume,
            maxVolume = max,
            thresholdStep = threshold,
            phon = phon,
            gainsDb = activeLoudnessGainsDb.toList(),
        )
        if (status != _loudnessStatus.value) {
            EquXLog.i("equX_AudioEngine", "Loudness: volume=$volume/$max threshold=$threshold phon=${"%.1f".format(phon)} gains=${activeLoudnessGainsDb.joinToString { "%.1f".format(it) }} headroom=${"%.1f".format(status.headroomDb)}")
        }
        _loudnessStatus.value = status
    }

    // Сессия, на которой создан текущий Virtualizer (для пересоздания с повышенным приоритетом).
    private var virtualizerSessionId = 0
    private var virtualizerControlCheck = true

    private fun createVirtualizer(sessionId: Int, priority: Int): Virtualizer? = try {
        Virtualizer(priority, sessionId).also {
            virtualizerSessionId = sessionId
            EquXLog.i("equX_AudioEngine", "[+] Virtualizer создан успешно (session=$sessionId, priority=$priority, control=${it.hasControl()})")
        }
    } catch (e: Exception) {
        EquXLog.e("equX_AudioEngine", "[-] Virtualizer недоступен для session=$sessionId (priority=$priority): ${e.message}")
        null
    }

    /**
     * Фоллбэк 1: управление эффектом у того, у кого выше приоритет. Если наш Virtualizer его
     * потерял (на той же сессии есть чужой/системный с приоритетом выше), команды молча не
     * доходят — пересоздаём с повышенным приоритетом (так работал тестовый Virtualizer: 101 > 100).
     */
    private fun ensureVirtualizerControl(): Virtualizer? {
        val v = virtualizer ?: return null
        if (!virtualizerControlCheck || runCatching { v.hasControl() }.getOrDefault(true)) return v
        EquXLog.w("equX_AudioEngine", "Virtualizer: нет управления на приоритете $VIRTUALIZER_PRIORITY — пересоздаём с $VIRTUALIZER_PRIORITY_TAKEOVER")
        val takeover = createVirtualizer(virtualizerSessionId, VIRTUALIZER_PRIORITY_TAKEOVER) ?: return v
        runCatching { v.release() }
        virtualizer = takeover
        return takeover
    }

    /**
     * Фоллбэк 2: режим. По логам ГУ (18.10): BINAURAL (2) принимается и слышен, AUTO (1)
     * принимается, TRANSAURAL (3) бандл NXP отвергает. Пробуем BINAURAL, затем AUTO.
     */
    private fun forceVirtualizerMode(v: Virtualizer): Int? {
        for (mode in VIRTUALIZER_MODES) {
            val accepted = try {
                v.forceVirtualizationMode(mode)
            } catch (e: Exception) {
                EquXLog.w("equX_AudioEngine", "Virtualizer: режим $mode — ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (accepted) return mode
        }
        return null
    }

    // Последнее записанное в лог состояние Virtualizer — чтобы не спамить на каждом тике.
    private var lastVirtualizerState: String? = null

    // Индикатор «Ширины». Момент включения нужен, чтобы не считать блокировкой то, что бандл
    // ещё не успел перейти в режим (на ГУ actual=0 сразу после включения, через долю секунды — 2).
    private val _spatialWidthStatus = MutableStateFlow(SpatialWidthStatus.UNKNOWN)
    val spatialWidthStatus: StateFlow<SpatialWidthStatus> = _spatialWidthStatus.asStateFlow()
    private var virtualizerEnabledAt = 0L
    private val virtualizerSettleMs = 2_500L

    /**
     * Проверка «Ширины» для индикатора. Вызывает служба периодически и при смене воспроизведения:
     * без звука бандл может не перейти в режим, поэтому вывод «заблокировано» — только когда играет.
     */
    fun checkSpatialWidthStatus(isPlaying: Boolean) {
        dsp {
            val is3dActive = isEngineEnabled && currentFxEnabled && currentSpatialEnabled && current3dPercent > 0
            val v = virtualizer
            val next = when {
                !is3dActive || virtualizerEnabledAt == 0L -> SpatialWidthStatus.UNKNOWN
                v == null -> SpatialWidthStatus.BLOCKED
                else -> {
                    val mode = runCatching { v.virtualizationMode }.getOrNull()
                    val settled = System.currentTimeMillis() - virtualizerEnabledAt >= virtualizerSettleMs
                    when {
                        mode != null && mode != Virtualizer.VIRTUALIZATION_MODE_OFF -> SpatialWidthStatus.WORKS
                        isPlaying && settled -> SpatialWidthStatus.BLOCKED
                        else -> _spatialWidthStatus.value // тихо или рано — оставляем как было
                    }
                }
            }
            if (next != _spatialWidthStatus.value) {
                EquXLog.i("equX_AudioEngine", "Ширина (Virtualizer): $next (playing=$isPlaying)")
                _spatialWidthStatus.value = next
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateVirtualizer() {
        val is3dActive = isEngineEnabled && currentFxEnabled && currentSpatialEnabled && current3dPercent > 0
        val systemStrength = (current3dPercent * 10).coerceIn(0, 1000)

        if (!is3dActive) {
            virtualizerEnabledAt = 0L
            _spatialWidthStatus.value = SpatialWidthStatus.UNKNOWN
        } else if (virtualizerEnabledAt == 0L) {
            virtualizerEnabledAt = System.currentTimeMillis()
        }

        try {
            val v = ensureVirtualizerControl() ?: return
            if (!is3dActive) {
                if (v.enabled) v.enabled = false
                logVirtualizerState("off")
                return
            }
            // Порядок как у рабочего тестового Virtualizer: сила → режим → включение.
            v.setStrength(systemStrength.toShort())
            val forcedMode = forceVirtualizerMode(v)
            if (!v.enabled) v.enabled = true
            // Фактический режим: OFF — бандл не виртуализирует (например, режим не принят).
            val actual = runCatching { v.virtualizationMode }.getOrNull()
            // Сила в ключ дедупликации не входит: при раскатке пресета она меняется каждый тик.
            logVirtualizerState(
                "on forced=${forcedMode ?: "none"} actual=$actual control=${v.hasControl()}",
                detail = "strength=${v.roundedStrength}",
            )
        } catch (e: Exception) {
            EquXLog.e("equX_AudioEngine", "Ошибка настройки Virtualizer: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun logVirtualizerState(state: String, detail: String = "") {
        if (state == lastVirtualizerState) return
        lastVirtualizerState = state
        EquXLog.i("equX_AudioEngine", "Virtualizer -> $state $detail".trimEnd())
    }

    /**
     * Применяет PreEQ-каскад: пространственный эффект (полосы 5..9: 1кГц..16кГц) + тонкомпенсация
     * (все полосы). Громкость системы применяется уже после эффектов, поэтому сигнал здесь в полной шкале:
     * подъём тонкомпенсации компенсируем запасом на входе DP, иначе бас клиппирует.
     */
    private fun applyPreEq() {
        try {
            dynamicsProcessing?.let { dp ->
                // Подъём верхов «Пространства» — свой ползунок, независимо от ширины (Virtualizer).
                val is3dActive = isEngineEnabled && currentFxEnabled && currentSpatialEnabled && currentAirPercent > 0
                val isLoudnessActive = isLoudnessActive()
                val headroomDb = if (isLoudnessActive) _loudnessStatus.value.headroomDb else 0f

                val factor = Math.pow((currentAirPercent / 100.0), 0.75).toFloat()
                val spatialAirGainDb = if (is3dActive) factor * 8.0f else 0f
                val spatialPresenceGainDb = if (is3dActive) factor * 5.0f else 0f
                val spatialVocalGainDb = if (is3dActive) factor * 3.0f else 0f

                for (channel in 0 until 2) {
                    val preEq = dp.getPreEqByChannelIndex(channel)
                    if (preEq != null) {
                        preEq.isEnabled = is3dActive || isLoudnessActive

                        for (bandIdx in 0 until 10) {
                            val band = preEq.getBand(bandIdx)
                            if (band != null) {
                                val spatialGain = when (bandIdx) {
                                    5 -> spatialVocalGainDb           // 1 кГц
                                    6, 7 -> spatialPresenceGainDb     // 2 кГц, 4 кГц
                                    8, 9 -> spatialAirGainDb          // 8 кГц, 16 кГц
                                    else -> 0.0f
                                }
                                band.gain = spatialGain + if (isLoudnessActive) activeLoudnessGainsDb[bandIdx] else 0f
                                preEq.setBand(bandIdx, band)
                            }
                        }
                        dp.setPreEqByChannelIndex(channel, preEq)
                    }
                }
                dp.setInputGainAllChannelsTo(-headroomDb)
                if (isEngineEnabled) {
                    dp.enabled = true
                }
            }
        } catch (e: Exception) {
            Log.e("equX_AudioEngine", "Ошибка применения PreEQ: ${e.message}")
        }
    }

    /**
     * Включение / Выключение захвата данных визуализации (Оптимизация батареи и CPU)
     */
    fun setVisualizerCaptureEnabled(enabled: Boolean) {
        synchronized(visualizerLock) {
            globalVisualizerCaptureEnabled = enabled
            try {
                if (shouldCaptureVisualizer() && visualizer == null) {
                    initVisualizer()
                } else {
                    applyVisualizerState()
                }
                if (!enabled) {
                    resetVisualization()
                }
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка управления захватом Visualizer: ${e.message}")
            }
        }
    }

    /**
     * Сообщает, виден ли UI со спектром. При false захват выключается, и никакие фоновые
     * события (смена playback config, сторож, запись настроек) его не включат.
     * При true Visualizer пересоздаётся (если пользователь его включил) — повторный
     * enabled на session 0 на части Automotive-прошивок не возобновляет захват.
     */
    fun setVisualizerUiVisible(visible: Boolean, context: Context? = null) {
        visualizerUiVisible = visible
        if (visible) {
            if (globalVisualizerCaptureEnabled) restartVisualizer(context) else applyVisualizerState()
        } else {
            applyVisualizerState()
            resetVisualization()
        }
    }

    private fun shouldCaptureVisualizer(): Boolean =
        isEngineEnabled && globalVisualizerCaptureEnabled && visualizerUiVisible

    fun restartVisualizer(context: Context? = null) {
        if (context != null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("equX_AudioEngine", "Пропуск Visualizer: нет разрешения RECORD_AUDIO")
            return
        }
        synchronized(visualizerLock) {
            try {
                visualizer?.release()
                visualizer = null
                initVisualizer()
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка перезапуска Visualizer: ${e.message}")
            }
        }
    }

    /**
     * Перезапускает Visualizer только если пользователь его включил.
     * Нужно при смене аудио-устройства/маршрута (BT connect/disconnect, громкая связь,
     * навигационные подсказки): на глобальной сессии (0) объект Visualizer может отвалиться
     * от пересозданного выходного потока и перестать слать данные.
     */
    fun restartVisualizerIfActive() {
        if (!shouldCaptureVisualizer()) return
        restartVisualizer()
    }

    /**
     * Пересоздаёт Visualizer, если он включён, но данные не приходят дольше [timeoutMs].
     *
     * Это страховка от «тихой смерти» глобального визуализатора: после смены
     * аудио-маршрута (Bluetooth reconnect, навигационная подсказка и т.п.) объект
     * остаётся alive, но колбэки больше не вызываются. Периодический вызов из службы
     * ловит такое зависание без ручного выкл/вкл.
     */
    fun restartVisualizerIfStale(timeoutMs: Long) {
        if (!shouldCaptureVisualizer()) return
        if (visualizer == null) {
            restartVisualizer()
            return
        }
        // Вызывается, только когда музыка играет. Считаем от последнего кадра с сигналом,
        // а не от любого кадра: зависший Visualizer на session 0 шлёт тишину.
        val last = maxOf(lastVisualizerSignalAt, visualizerCreatedAt)
        if (last == 0L) return
        if (System.currentTimeMillis() - last > timeoutMs) {
            EquXLog.i("equX_AudioEngine", "Visualizer завис (нет сигнала > ${timeoutMs}мс) — пересоздаю")
            restartVisualizer()
        }
    }

    private fun applyVisualizerState() {
        synchronized(visualizerLock) {
            try {
                visualizer?.enabled = shouldCaptureVisualizer()
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "Ошибка применения состояния Visualizer: ${e.message}")
            }
        }
    }

    private fun initVisualizer() {
        if (!isEngineEnabled) return
        // Без RECORD_AUDIO Visualizer на глобальной сессии падает с ERROR_NO_INIT (-3)
        // и спамит в logcat. На Automotive разрешение может прийти позже — не пытаемся
        // создавать его заранее.
        val ctx = appContext ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        synchronized(visualizerLock) {
            try {
                visualizer?.release()
                visualizer = null

                val v = Visualizer(currentSessionId)
                // Гарантируем состояние INITIALIZED перед настройкой, даже если эффект
                // на глобальной сессии был создан уже включённым.
                v.enabled = false

                val range = Visualizer.getCaptureSizeRange()
                val captureSize = if (range != null && range.size > 1) {
                    range[1].coerceAtMost(4096)
                } else {
                    256
                }
                v.captureSize = captureSize

                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        lastVisualizerDataAt = System.currentTimeMillis()
                        if (waveform == null || !isEngineEnabled) return
                        lastWaveform = waveform
                        processAudioData(waveform, lastFft)
                    }

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        lastVisualizerDataAt = System.currentTimeMillis()
                        if (fft == null || !isEngineEnabled) return
                        if (hasSignal(fft)) lastVisualizerSignalAt = lastVisualizerDataAt
                        // НЕ вызываем vis?.samplingRate: на state=0 он бросает
                        // IllegalStateException (getSamplingRate called in wrong state).
                        // Используем параметр callback с санитайзером (см. ниже).
                        visualizerSampleRate = sanitizeVisualizerSampleRate(samplingRate, visualizerSampleRate)
                        lastFft = fft
                        processAudioData(lastWaveform, fft)
                    }
                }, minOf(Visualizer.getMaxCaptureRate(), 12_000), false, true)

                v.enabled = shouldCaptureVisualizer()

                // Читаем реальную частоту дискретизации ПОСЛЕ включения (в состоянии
                // ENABLED getSamplingRate() корректен). Обёрнуто в runCatching, чтобы
                // на нестандартных прошивках не уронить инициализацию.
                runCatching {
                    visualizerSampleRate = sanitizeVisualizerSampleRate(v.samplingRate, visualizerSampleRate)
                }

                visualizer = v
                lastVisualizerDataAt = System.currentTimeMillis()
                visualizerCreatedAt = lastVisualizerDataAt

                EquXLog.i("equX_AudioEngine", "[+] Visualizer создан (session=$currentSessionId, capture=${globalVisualizerCaptureEnabled}, ui=$visualizerUiVisible)")
            } catch (e: Exception) {
                Log.e("equX_AudioEngine", "[-] Ошибка Visualizer API: ${e.message}")
            }
        }
    }

    // Есть ли в FFT-кадре что-то кроме тишины (DC-бин не учитываем).
    private fun hasSignal(fft: ByteArray): Boolean {
        for (i in 2 until fft.size) {
            val b = fft[i].toInt()
            if (b > 1 || b < -1) return true
        }
        return false
    }

    /**
     * Двухкаскадный процессор обработки визуализации: Native FFT + PCM Waveform Fallback.
     * На выходе — нормализованные (0..1) и сглаженные полосы.
     */
    private fun processAudioData(waveform: ByteArray?, fft: ByteArray?) {
        if (!isEngineEnabled) return

        try {
            val numBars = 16
            val raw = FloatArray(numBars)
            var hasValidFft = false
            var hasSignal = false

            // 1. Нативный FFT. Раскладка байтов (длина == captureSize):
            //    [0] = Rf0 (DC), [1] = Rf(Nyquist), далее пары (Rfk, Ifk) для k=1..
            //    Для k>=1: real = fft[2k], imag = fft[2k+1], magnitude = hypot().
            if (fft != null && fft.size >= 4) {
                val captureSize = fft.size
                val half = captureSize / 2
                val sampleRate = visualizerSampleRate.coerceAtLeast(8000)
                var fftSum = 0f

                val magnitudes = FloatArray(half)
                for (k in 1 until half) {
                    val idx = 2 * k
                    if (idx + 1 >= fft.size) break
                    val real = fft[idx].toFloat()
                    val imag = fft[idx + 1].toFloat()
                    val mag = Math.hypot(real.toDouble(), imag.toDouble()).toFloat()
                    magnitudes[k] = mag
                    fftSum += mag
                }

                if (fftSum > 0.5f) {
                    hasValidFft = true
                    hasSignal = true

                    // 16 лог-полос: 30 Гц … Nyquist (ограничен 16 кГц).
                    val nyquist = (sampleRate / 2f).coerceAtMost(16000f)
                    val minFreq = 30f
                    val logMin = Math.log10(maxOf(minFreq, 1f).toDouble())
                    val logMax = Math.log10(maxOf(nyquist, minFreq + 1f).toDouble())

                    for (bar in 0 until numBars) {
                        val f0 = Math.pow(10.0, logMin + (logMax - logMin) * bar / numBars)
                        val f1 = Math.pow(10.0, logMin + (logMax - logMin) * (bar + 1) / numBars)

                        val bin0 = Math.floor(f0 * captureSize / sampleRate).toInt().coerceIn(1, half - 1)
                        val bin1 = Math.ceil(f1 * captureSize / sampleRate).toInt().coerceIn(bin0 + 1, half)

                        var sum = 0f
                        var count = 0
                        for (bin in bin0 until bin1) {
                            if (bin < magnitudes.size) {
                                sum += magnitudes[bin]
                                count++
                            }
                        }

                        val avg = if (count > 0) sum / count else 0f
                        // dB-шкала от сырой магнитуды, прогрессивный буст верхов, делитель 512.
                        val db = if (avg > 0f) (10.0 * Math.log10(avg.toDouble())).toFloat() else 0f
                        val factor = if (bar < 4) 1.0f else (1.0f + 0.1f * bar)
                        raw[bar] = (db * factor * 15f / 512f).coerceIn(0f, 1f)
                    }
                }
            }

            // 2. Резерв: RMS из PCM, если FFT не пришёл.
            if (!hasValidFft && waveform != null && waveform.isNotEmpty()) {
                val size = waveform.size
                val chunkSize = (size / numBars).coerceAtLeast(1)

                for (i in 0 until numBars) {
                    val startIndex = i * chunkSize
                    val endIndex = (startIndex + chunkSize).coerceAtMost(size)
                    var sumSquare = 0f
                    var count = 0

                    for (j in startIndex until endIndex) {
                        val sample = (waveform[j].toInt() and 0xFF) - 128
                        sumSquare += (sample * sample).toFloat()
                        count++
                    }

                    val rms = if (count > 0) Math.sqrt((sumSquare / count).toDouble()).toFloat() else 0f
                    // Тот же принцип, что и для FFT: 10*log10 + прогрессивный буст верхов.
                    val db = if (rms > 0f) (10.0 * Math.log10(rms.toDouble())).toFloat() else 0f
                    val factor = if (i < 4) 1.0f else (1.0f + 0.1f * i)
                    raw[i] = (db * factor * 15f / 512f).coerceIn(0f, 1f)
                }

                hasSignal = raw.any { it > 0.01f }
            }

            // Сглаживание по частоте + атака/спад.
            applySmoothing(smoothSpatially(raw), hasSignal)
        } catch (e: Exception) {
            Log.e("equX_AudioEngine", "Ошибка в processAudioData: ${e.message}")
        }
    }

    /**
     * Усреднение каждой полосы с соседями (по частоте) для плавного «аналогового» рельефа.
     * Веса подобраны так, чтобы сглаживание было умеренным и не «съедало» быстрые верха.
     */
    private fun smoothSpatially(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            val left = if (i > 0) input[i - 1] else input[i]
            val right = if (i < input.size - 1) input[i + 1] else input[i]
            out[i] = left * 0.15f + input[i] * 0.7f + right * 0.15f
        }
        return out
    }

    /**
     * Атака/спад мгновенных полос.
     */
    private fun applySmoothing(raw: FloatArray, hasSignal: Boolean) {
        val numBars = raw.size

        if (!hasSignal) {
            for (i in 0 until numBars) {
                smoothedMagnitudes[i] *= release
            }
        } else {
            for (i in 0 until numBars) {
                val scaled = raw[i].coerceIn(0f, 1f)
                val coeff = if (scaled > smoothedMagnitudes[i]) attack else release
                smoothedMagnitudes[i] += (scaled - smoothedMagnitudes[i]) * coeff
            }
        }

        // Троттлим передачу в UI до ~30 fps, чтобы не дёргать Compose на каждый
        // нативный кадр захвата (иначе — избыточные рекомпозиции и GC).
        val now = System.currentTimeMillis()
        if (now - lastFftPushMs < 33L) return
        lastFftPushMs = now

        // Передаём КОПИЮ массива. Если отдать ту же ссылку, Compose mutableStateOf
        // увидит структурно равный объект (массивы сравниваются по ссылке) и не
        // запустит рекомпозицию — визуализатор «зависнет» после первого кадра.
        onFftDataUpdate?.invoke(smoothedMagnitudes.copyOf())
    }

    fun release() {
        dsp {
            eqRampJob?.cancel()
            rampScope.cancel()
            visualizer?.release()
            dynamicsProcessing?.release()
            virtualizer?.release()
            visualizer = null
            dynamicsProcessing = null
            virtualizer = null
        }
    }
}

/**
 * Работает ли «Ширина» (системный Virtualizer) — для индикатора на карточке «Пространство».
 * BLOCKED: эффект включён, музыка играет, а бандл так и не перешёл в режим виртуализации
 * (vivo, Android 16: AudioFlinger держит Virtualizer в Suspended, API это не сообщает).
 */
enum class SpatialWidthStatus { UNKNOWN, WORKS, BLOCKED }

/** Состояние тонкомпенсации для UI: громкость, порог, уровень (phon) и подъём полос PreEQ. */
data class LoudnessStatus(
    val volume: Int? = null,
    val maxVolume: Int? = null,
    val thresholdStep: Int? = null,
    val phon: Float? = null,
    val gainsDb: List<Float> = emptyList(),
) {
    /** Запас на входе DP = наибольший подъём полосы. */
    val headroomDb: Float get() = gainsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
}
