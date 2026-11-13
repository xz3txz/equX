package com.xz3t.equX.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.xz3t.equX.data.AudioDeviceScanner
import com.xz3t.equX.data.DebugFlags
import com.xz3t.equX.data.EffectiveSpeedFilter
import com.xz3t.equX.data.SpeedSource
import com.xz3t.equX.data.SpeedSourceInfo
import com.xz3t.equX.data.SpeedSourceStatus
import com.xz3t.equX.data.resolveSpeedSource
import com.xz3t.equX.data.EquXLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import com.xz3t.equX.data.CarConfig
import kotlinx.coroutines.CoroutineScope

/**
 * Скорость автомобиля для GALA и скоростного EQ (вынесено из EqualizerService).
 *
 * Источники: vendor-служба ECARX `car_signal`, AOSP VHAL (CarPropertyManager + фолбэк `cmd car_service`), GPS.
 * На ГУ все автомобильные источники опрашиваются параллельно (индикатор и «макс.» в селекторе), в
 * [EffectiveSpeedFilter] идут только сэмплы активного источника ([resolveSpeedSource]). Имитация
 * скорости (отладка) перекрывает реальные источники. Эффективная скорость уходит в [Host.onEffectiveSpeed].
 * Car API и ECARX — только через рефлексию (compile-зависимостей нет).
 */
class VehicleSpeedMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** Что монитору нужно от службы. */
    interface Host {
        /** Последний применённый CarConfig (в т.ч. несохранённый черновик из UI). */
        fun carConfig(): CarConfig
        /** Эффективная скорость изменилась → скоростной EQ движка и GALA. */
        fun onEffectiveSpeed(kmh: Float)
        /** Тик цикла монитора (раз в секунду): служба запускает пробу методов громкости при включённом Car. */
        fun onMonitorTick()
        /** Устройство определено как ГУ при старте монитора. */
        fun onAutomotiveDetected()
        fun isPlaybackLikelyActive(): Boolean
        /** Тип `location` у foreground-службы — без него GPS в фоне не работает. */
        fun setForegroundLocationType(enabled: Boolean)
        /** Выполнить на главном потоке (колбэки в UI пишут в состояние Compose). */
        fun onMain(block: () -> Unit)
    }

    private companion object {
        const val TAG_SPEED = EqualizerService.TAG_SPEED

        // Источник считается «живым», если последний сэмпл не старше этого.
        const val SPEED_SOURCE_FRESH_MS = 5_000L

        // GPS на телефоне выключается, если музыка не играет дольше этого.
        const val GPS_IDLE_STOP_MS = 60_000L
    }

    // Интеграция с Android Automotive (GALA)
    private var carApiInstance: Any? = null
    private var carPropertyManager: Any? = null
    private var speedMonitorJob: Job? = null
    private var speedCallbackProxy: Any? = null
    private var speedPropertyId: Int = 0x11600207 // PERF_VEHICLE_SPEED
    private var getFloatPropertyMethod: Method? = null
    private var getCarPropertyValueMethod: Method? = null
    private var carConnected = false
    private var lastCarConnectAttemptAt = 0L
    private var speedReadErrorLogged = false
    private var speedReadSkipLogged = false
    private var monitorLoopTick = 0
    private var lastShellReadAt = 0L
    private var shellReadErrorLogged = false

    // Vendor-служба ECARX — CAN-скорость с приборной панели (не GPS).
    @Volatile
    private var ecarxServiceBound = false
    @Volatile
    private var ecarxPropertyManager: Any? = null
    @Volatile
    private var ecarxGetPropertyMethod: Method? = null
    @Volatile
    private var ecarxGetValueMethod: Method? = null
    @Volatile
    private var ecarxResolvedSpeedPropId = -1
    @Volatile
    private var ecarxResolvedSpeedIsMps = false
    private var ecarxLastConnectAttemptAt = 0L
    private var ecarxProbeLogged = false
    // Источники скорости (по приоритету):
    //  - 0x2160728C VehSpdLgtA — сырая продольная скорость, м/с (float).
    //    Основной источник на ECARX.
    //  - 0x21401B54 CLUSTER_FIXDISPLAY_VEHSPDLGTA — на части ГУ всегда 0.
    //  - 0x11600207 PERF_VEHICLE_SPEED — AOSP, м/с (на части ГУ не обновляется).
    //  - адапт-api кандидаты (0x322 / 0x100100 / 0x101A00) — фолбэк.
    private val ecarxSpeedCandidateIds = intArrayOf(0x2160728C, 0x21401B54, 0x11600207, 0x322, 0x100100, 0x101A00)
    // VehSpdLgtQf — качество сигнала скорости (2/3 = валидно, 0/1 = невалидно).
    private val ecarxSpeedQualityPropId = 0x2140728F
    private var lastOnChangeLogAt = 0L

    // Сырая скорость (из любого источника) → эффективная (EMA + мёртвая зона + удержание при потере сигнала).
    // В движок и GALA идёт только эффективная; UI показывает сырую. Доступ синхронизирован по самому фильтру:
    // сэмплы приходят из цикла монитора (IO), колбэка VHAL (binder) и имитации скорости.
    private val speedFilter = EffectiveSpeedFilter()
    private var lastEffectiveSpeedKmh: Float? = null

    // Источник скорости — глобальная настройка (DataStore), не в пресете. Ставит служба из speedSourceFlow.
    @Volatile
    var selectedSpeedSource = SpeedSource.AUTO

    // Источник, чьи сэмплы сейчас идут в фильтр (null — имитация скорости или ещё не выбран).
    @Volatile
    private var activeSpeedSource: SpeedSource? = null

    // Последний сэмпл, время и максимум по каждому источнику — для индикатора и «макс.» в селекторе.
    // Все источники на ГУ опрашиваются параллельно, независимо от выбранного.
    private val sourceStatsLock = Any()
    private val sourceLastAt = java.util.EnumMap<SpeedSource, Long>(SpeedSource::class.java)
    private val sourceMaxKmh = java.util.EnumMap<SpeedSource, Float>(SpeedSource::class.java)

    /** Состояние источников для UI (селектор на вкладке Car). */
    @Volatile
    var speedSourceInfos: Map<SpeedSource, SpeedSourceInfo> = emptyMap()
        private set
    var onSpeedSourcesChanged: (() -> Unit)? = null

    // GPS (LocationManager, без Play Services — на ГУ их нет).
    private var gpsListener: LocationListener? = null
    @Volatile
    private var gpsRunning = false
    private var lastPlaybackActiveAt = 0L
    private var lastShellStatsReadAt = 0L
    private val ecarxServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            ecarxServiceBound = true
            if (service == null) return
            try {
                val iecarxClass = Class.forName("ecarx.car.IECarXCar")
                val iecarxStub = Class.forName("ecarx.car.IECarXCar\$Stub")
                val asInterface = iecarxStub.getMethod("asInterface", IBinder::class.java)
                val iecarx = asInterface.invoke(null, service)
                val getCarService = iecarxClass.getMethod("getCarService", String::class.java)
                val signalBinder = getCarService.invoke(iecarx, "car_signal") as? IBinder
                EquXLog.i(TAG_SPEED, "ECARX: car_signal binder=$signalBinder")
                if (signalBinder == null) return

                val propClass = Class.forName("ecarx.car.hardware.property.IECarXCarProperty")
                val propStub = Class.forName("ecarx.car.hardware.property.IECarXCarProperty\$Stub")
                val propAsInterface = propStub.getMethod("asInterface", IBinder::class.java)
                val propManager = propAsInterface.invoke(null, signalBinder)
                ecarxGetPropertyMethod = propClass.getMethod(
                    "getProperty",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                val valueClass = Class.forName("ecarx.car.hardware.ECarXCarPropertyValue")
                ecarxGetValueMethod = valueClass.getMethod("getValue")
                ecarxPropertyManager = propManager

                if (!ecarxProbeLogged) {
                    ecarxProbeLogged = true
                    probeEcarxProperties(propManager, propClass, valueClass)
                }
            } catch (e: Exception) {
                Log.e(TAG_SPEED, "ECARX: подключение не удалось (${e.javaClass.simpleName}): ${e.message}", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ecarxServiceBound = false
            ecarxPropertyManager = null
            ecarxGetPropertyMethod = null
            ecarxGetValueMethod = null
        }
    }

    // Реальная скорость из Car API (CAR_SPEED). null = данные ещё не приходили
    // (например, права не выданы). Используется ТОЛЬКО для отображения в UI.
    @Volatile
    var lastRealSpeedKmh: Float? = null
        private set

    // Максимальная скорость за сессию службы — для отображения в UI.
    @Volatile
    var lastSpeedMaxKmh: Float? = null
        private set

    var onRealSpeedChanged: ((Float?) -> Unit)? = null

    /**
     * Устанавливает «имитированную» скорость и применяет её к GALA.
     *
     * В отличие от прямого `audioEngine.setVehicleSpeed()`, этот путь также запускает
     * системную громкость через выбранный метод, чтобы «Имитация скорости» вела себя
     * ровно так же, как реальная скорость из CAN/VHAL.
     */
    fun setMockVehicleSpeed(speedKmh: Float) {
        DebugFlags.mockSpeedValue = speedKmh
        // Имитация идёт через тот же фильтр, что и реальная скорость, — на стоянке так можно
        // проверить сглаживание и мёртвую зону. Тяжёлую системную громкость (рефлексия) — в фоне,
        // чтобы не блокировать UI-поток при драге слайдера.
        scope.launch(Dispatchers.IO) {
            feedSpeedSample(speedKmh)
        }
    }

    private fun hasCarPermissions(): Boolean {
        val carSpeed = ContextCompat.checkSelfPermission(context, "android.car.permission.CAR_SPEED")
        val displayUnits = ContextCompat.checkSelfPermission(context, "android.car.permission.READ_CAR_DISPLAY_UNITS")
        val carSpeedGranted = carSpeed == PackageManager.PERMISSION_GRANTED
        val displayUnitsGranted = displayUnits == PackageManager.PERMISSION_GRANTED
        EquXLog.i(TAG_SPEED, "hasCarPermissions: CAR_SPEED=$carSpeed(${if (carSpeedGranted) "GRANTED" else "DENIED"}), READ_CAR_DISPLAY_UNITS=$displayUnits(${if (displayUnitsGranted) "GRANTED" else "DENIED"})")
        return carSpeedGranted && displayUnitsGranted
    }

    /** Запуск цикла монитора (из onCreate службы). */
    fun start() {
        speedMonitorJob?.cancel()

        val automotive = isAutomotiveDevice()
        val perms = hasCarPermissions()
        EquXLog.i(TAG_SPEED, "initSpeedMonitor: automotive=$automotive, hasCarPermissions=$perms")
        if (automotive) host.onAutomotiveDetected()

        if (!automotive) {
            EquXLog.i(TAG_SPEED, "initSpeedMonitor: устройство не Automotive — монитор скорости отключён")
        }

        speedMonitorJob = scope.launch(Dispatchers.IO) {
            EquXLog.i(TAG_SPEED, "monitor loop: старт (mockSpeed=${DebugFlags.mockSpeedEnabled})")
            while (isActive) {
                host.onMonitorTick()

                updateGpsState(shouldRunGps(automotive))

                // Телефон без GPS и без имитации: ECARX/VHAL на нём нет, опрашивать нечего —
                // редкая проверка условий вместо цикла раз в секунду.
                if (!automotive && !DebugFlags.mockSpeedEnabled && !gpsRunning) {
                    activeSpeedSource = null
                    publishSpeedSourceInfos(automotive)
                    delay(5000)
                    continue
                }

                // Имитация перекрывает реальные источники: их сэмплы в фильтр не идут.
                activeSpeedSource = if (DebugFlags.mockSpeedEnabled) null else currentResolvedSpeedSource()

                if (automotive) {
                    // Опрашиваем все автомобильные источники (не только выбранный) — для «макс.» и
                    // индикатора в селекторе. В фильтр идут только сэмплы активного источника.
                    ensureEcarxConnected()
                    tryReadEcarxSpeed()
                    // Car API подключаем с ретраями: car_service на момент старта может быть ещё не
                    // готов (особенно после force-stop).
                    ensureCarConnected()
                    readCurrentSpeed()
                    // Фолбэк VHAL через `cmd car_service get-property-value` — если Java-путь не отдаёт
                    // данные. Процесс дорогой: часто — только когда VHAL активен, иначе раз в 10 с для статистики.
                    if (!isSourceFresh(SpeedSource.VHAL)) {
                        val now = System.currentTimeMillis()
                        if (activeSpeedSource == SpeedSource.VHAL || now - lastShellStatsReadAt >= 10_000L) {
                            lastShellStatsReadAt = now
                            readSpeedViaShellAndApply()
                        }
                    }
                }

                if (DebugFlags.mockSpeedEnabled) {
                    feedSpeedSample(DebugFlags.mockSpeedValue)
                }

                // Пропадание сигнала: удержание последней скорости, затем плавное снижение.
                tickSpeedFilter()
                publishSpeedSourceInfos(automotive)

                monitorLoopTick++
                if (monitorLoopTick % 30 == 0) {
                    EquXLog.i(TAG_SPEED, "monitor loop: жив (tick=$monitorLoopTick, lastSpeed=$lastRealSpeedKmh, source=$activeSpeedSource)")
                }
                delay(1000)
            }
        }
    }

    /** Активный источник по выбору пользователя и тому, какие источники уже отдавали данные. */
    private fun currentResolvedSpeedSource(): SpeedSource {
        val delivered = synchronized(sourceStatsLock) { sourceLastAt.keys.toSet() }
        return resolveSpeedSource(
            selected = selectedSpeedSource,
            ecarxDelivered = SpeedSource.ECARX in delivered,
            vhalDelivered = SpeedSource.VHAL in delivered,
        )
    }

    private fun isSourceFresh(source: SpeedSource): Boolean {
        val at = synchronized(sourceStatsLock) { sourceLastAt[source] } ?: return false
        return System.currentTimeMillis() - at <= SPEED_SOURCE_FRESH_MS
    }

    /**
     * Сэмпл скорости от конкретного источника: статистика источника всегда, а в фильтр
     * (движок/GALA/UI) — только если это активный источник.
     */
    private fun recordSourceSample(source: SpeedSource, kmh: Float) {
        synchronized(sourceStatsLock) {
            sourceLastAt[source] = System.currentTimeMillis()
            sourceMaxKmh[source] = maxOf(sourceMaxKmh[source] ?: kmh, kmh)
        }
        if (activeSpeedSource == source) applySpeedKmh(kmh)
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun gpsProviderStatus(): SpeedSourceStatus? {
        if (!hasFineLocationPermission()) return SpeedSourceStatus.UNAVAILABLE
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return SpeedSourceStatus.UNAVAILABLE
        return try {
            if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) SpeedSourceStatus.UNAVAILABLE
            else if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) SpeedSourceStatus.UNAVAILABLE
            else null
        } catch (e: Exception) {
            SpeedSourceStatus.UNAVAILABLE
        }
    }

    /**
     * Нужен ли сейчас GPS. Батарея телефона: на ГУ — всегда, когда GPS выбран (питание от машины);
     * на телефоне — в диагностике (открыта вкладка Car и включён Car) или в работе (в активном
     * пресете Car и GALA/скоростной EQ включены и играет музыка; пауза > 1 мин — выключаем).
     */
    private fun shouldRunGps(automotive: Boolean): Boolean {
        if (DebugFlags.mockSpeedEnabled) return false
        if (currentResolvedSpeedSource() != SpeedSource.GPS) return false
        if (gpsProviderStatus() != null) return false
        if (automotive) return true
        val car = host.carConfig()
        if (!car.isEnabled) return false
        if (EqualizerService.carScreenVisible) return true
        if (!car.volumeEnabled && !car.eqEnabled) return false
        val now = System.currentTimeMillis()
        if (host.isPlaybackLikelyActive()) lastPlaybackActiveAt = now
        return now - lastPlaybackActiveAt <= GPS_IDLE_STOP_MS
    }

    private fun updateGpsState(needed: Boolean) {
        if (needed == gpsRunning) return
        gpsRunning = needed
        scope.launch(Dispatchers.Main) {
            if (needed) startGps() else stopGps()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsListener != null) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        host.setForegroundLocationType(true)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasSpeed()) recordSourceSample(SpeedSource.GPS, location.speed * 3.6f)
            }
            // Явные реализации: на Android 10 у интерфейса ещё нет методов по умолчанию.
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            gpsListener = listener
            EquXLog.i(TAG_SPEED, "GPS: старт")
        } catch (e: Exception) {
            EquXLog.w(TAG_SPEED, "GPS: не удалось запустить: ${e.message}")
            gpsRunning = false
            host.setForegroundLocationType(false)
        }
    }

    private fun stopGps() {
        val listener = gpsListener ?: return
        gpsListener = null
        try {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
        } catch (_: Exception) {
        }
        host.setForegroundLocationType(false)
        EquXLog.i(TAG_SPEED, "GPS: стоп")
    }

    /** Пересчитывает состояние источников и уведомляет UI только при изменении. */
    private fun publishSpeedSourceInfos(automotive: Boolean) {
        fun vehicleStatus(source: SpeedSource) = when {
            !automotive -> SpeedSourceStatus.UNAVAILABLE
            isSourceFresh(source) -> SpeedSourceStatus.ACTIVE
            else -> SpeedSourceStatus.UNKNOWN
        }
        val gpsStatus = gpsProviderStatus()
            ?: if (gpsRunning && isSourceFresh(SpeedSource.GPS)) SpeedSourceStatus.ACTIVE else SpeedSourceStatus.UNKNOWN
        val max = synchronized(sourceStatsLock) { java.util.EnumMap(sourceMaxKmh) }
        val infos = mapOf(
            SpeedSource.ECARX to SpeedSourceInfo(vehicleStatus(SpeedSource.ECARX), max[SpeedSource.ECARX]),
            SpeedSource.VHAL to SpeedSourceInfo(vehicleStatus(SpeedSource.VHAL), max[SpeedSource.VHAL]),
            SpeedSource.GPS to SpeedSourceInfo(gpsStatus, max[SpeedSource.GPS]),
        )
        if (infos != speedSourceInfos) {
            speedSourceInfos = infos
            host.onMain { onSpeedSourcesChanged?.invoke() }
        }
    }

    private fun isAutomotiveDevice(): Boolean {
        val result = AudioDeviceScanner.isAutomotive(context)
        EquXLog.i(
            TAG_SPEED,
            "isAutomotiveDevice=$result (FEATURE_AUTOMOTIVE=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)})"
        )
        return result
    }

    private fun ensureCarConnected() {
        val ready = carConnected && carPropertyManager != null && getFloatPropertyMethod != null
        if (ready) return
        val now = System.currentTimeMillis()
        if (now - lastCarConnectAttemptAt < 5000) return
        lastCarConnectAttemptAt = now
        EquXLog.i(TAG_SPEED, "ensureCarConnected: повторная попытка подключения к Car API")
        tryConnectToCarApi()
    }

    private fun tryConnectToCarApi() {
        try {
            // Отключаем предыдущий экземпляр Car от неудачной попытки,
            // чтобы повторный createCar не плодил подключения.
            carApiInstance?.let { old ->
                runCatching { old.javaClass.getMethod("disconnect").invoke(old) }
            }
            carApiInstance = null
            carPropertyManager = null
            getFloatPropertyMethod = null
            getCarPropertyValueMethod = null
            speedCallbackProxy = null
            carConnected = false

            EquXLog.i(TAG_SPEED, "tryConnectToCarApi: загружаю android.car.Car...")
            val carClass = Class.forName("android.car.Car")
            logPublicMethods("android.car.Car", carClass)
            val createCarMethod = carClass.getMethod("createCar", Context::class.java)
            carApiInstance = createCarMethod.invoke(null, context)
            EquXLog.i(TAG_SPEED, "tryConnectToCarApi: Car.createCar() -> $carApiInstance")
            if (carApiInstance == null) {
                Log.e(TAG_SPEED, "tryConnectToCarApi: Car.createCar() вернул null")
                return
            }

            val isConnected = runCatching {
                carClass.getMethod("isConnected").invoke(carApiInstance) as? Boolean
            }.getOrNull() ?: false
            carConnected = isConnected
            EquXLog.i(TAG_SPEED, "tryConnectToCarApi: isConnected=$isConnected")

            val getCarManagerMethod = carClass.getMethod("getCarManager", String::class.java)
            carPropertyManager = getCarManagerMethod.invoke(carApiInstance, "property")
            EquXLog.i(TAG_SPEED, "tryConnectToCarApi: CarPropertyManager -> $carPropertyManager")
            if (carPropertyManager == null) {
                Log.e(TAG_SPEED, "tryConnectToCarApi: getCarManager(\"property\") вернул null")
                return
            }

            val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val carPropertyValueClass = Class.forName("android.car.hardware.CarPropertyValue")
            getCarPropertyValueMethod = carPropertyValueClass.getMethod("getValue")
            logPublicMethods("CarPropertyManager", carPropertyManager?.javaClass ?: Any::class.java)

            // PERF_VEHICLE_SPEED — глобальное float-свойство (areaId=0).
            // getFloatProperty(int propertyId, int areaId) есть везде,
            // а getProperty(int) на части сборок отсутствует (NoSuchMethodException).
            getFloatPropertyMethod = carPropertyManager?.javaClass?.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            EquXLog.i(TAG_SPEED, "tryConnectToCarApi: getFloatProperty метод = ${getFloatPropertyMethod != null}")

            speedCallbackProxy = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(callbackClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === (args?.firstOrNull())
                    "toString" -> "equX_CarSpeedCallback"
                    "onChangeEvent" -> {
                        // AOSP PERF_VEHICLE_SPEED на части ГУ всегда 0.0. В фильтр он попадает, только
                        // если VHAL — активный источник (recordSourceSample), поэтому ECARX не затирается нулём.
                        if (args != null && args.isNotEmpty()) {
                            try {
                                val propertyValue = args[0]
                                val value = getCarPropertyValueMethod?.invoke(propertyValue)
                                val mps = extractFirstFloat(value)
                                // PERF_VEHICLE_SPEED шлёт события ~10 Гц; логируем не чаще раза в секунду.
                                val now = System.currentTimeMillis()
                                if (now - lastOnChangeLogAt >= 1000) {
                                    lastOnChangeLogAt = now
                                    EquXLog.i(TAG_SPEED, "onChangeEvent value=$value -> m/s=$mps")
                                }
                                if (mps != null) {
                                    recordSourceSample(SpeedSource.VHAL, mps * 3.6f)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG_SPEED, "Ошибка обработки onChangeEvent: ${e.message}", e)
                            }
                        }
                        null
                    }
                    else -> null
                }
            }

            val registerMethod = carPropertyManager?.javaClass?.getMethod(
                "registerCallback",
                callbackClass,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            EquXLog.i(TAG_SPEED, "tryConnectToCarApi: registerCallback метод = ${registerMethod != null}")
            registerMethod?.invoke(carPropertyManager, speedCallbackProxy, speedPropertyId, 10.0f)
            EquXLog.i(TAG_SPEED, "tryConnectToCarApi: Car API подключен, callback зарегистрирован (propertyId=$speedPropertyId)")

            // Первичное чтение: у PERF_VEHICLE_SPEED обычно changeMode=ON_CHANGE,
            // поэтому при стоянке callback не придёт и UI покажет «—» вместо «0 км/ч».
            readCurrentSpeed()
        } catch (e: Exception) {
            // На смартфоне android.car.Car отсутствует — это ожидаемо, не спамим stacktrace.
            if (e is ClassNotFoundException) {
                EquXLog.i(TAG_SPEED, "tryConnectToCarApi: android.car.Car отсутствует — не Automotive")
            } else {
                Log.e(TAG_SPEED, "tryConnectToCarApi: не удалось подключить Car API (${e.javaClass.simpleName}): ${e.message}", e)
            }
            carApiInstance = null
            carPropertyManager = null
            getFloatPropertyMethod = null
            getCarPropertyValueMethod = null
            speedCallbackProxy = null
            carConnected = false
        }
    }

    /**
     * CarPropertyValue.getValue() возвращает Object: для одного значения это может быть
     * Float/Double, а для массива — Float[]/float[]/Int[] и т.д. Достаём первое число.
     */
    private fun extractFirstFloat(value: Any?): Float? {
        return when (value) {
            is Float -> value
            is Double -> value.toFloat()
            is Int -> value.toFloat()
            is FloatArray -> value.firstOrNull()
            is IntArray -> value.firstOrNull()?.toFloat()
            is Array<*> -> (value.firstOrNull() as? Number)?.toFloat()
            else -> null
        }
    }

    private fun readCurrentSpeed() {
        val getMethod = getFloatPropertyMethod
        val manager = carPropertyManager
        if (getMethod == null || manager == null) {
            if (!speedReadSkipLogged) {
                speedReadSkipLogged = true
                Log.w(TAG_SPEED, "readCurrentSpeed: пропуск — getFloatProperty=${getMethod != null}, manager=${manager != null}")
            }
            return
        }
        try {
            // getFloatProperty(propertyId, areaId). Для PERF_VEHICLE_SPEED areaId=0.
            val value = getMethod.invoke(manager, speedPropertyId, 0)
            val mps = extractFirstFloat(value)
            if (mps != null) {
                speedReadErrorLogged = false
                speedReadSkipLogged = false
                recordSourceSample(SpeedSource.VHAL, mps * 3.6f)
            } else if (!speedReadErrorLogged) {
                speedReadErrorLogged = true
                Log.w(TAG_SPEED, "readCurrentSpeed: не удалось извлечь число из value=${value?.javaClass?.simpleName}: $value")
            }
        } catch (e: Exception) {
            // Если car_service отвалился/переподключился — сбрасываем флаг, чтобы
            // ensureCarConnected на следующем тике пересоздал подключение.
            val msg = e.message ?: ""
            if (e.javaClass.simpleName.contains("NotConnected") || msg.contains("not connected", ignoreCase = true)) {
                carConnected = false
                lastCarConnectAttemptAt = 0
            }
            if (!speedReadErrorLogged) {
                speedReadErrorLogged = true
                Log.e(TAG_SPEED, "readCurrentSpeed: ошибка (${e.javaClass.simpleName}): ${e.message}", e)
            }
        }
    }

    /** Сырая скорость от реального источника: статистика и UI — по сырой, движок и GALA — через фильтр. */
    private fun applySpeedKmh(speedKmh: Float) {
        if (lastRealSpeedKmh != speedKmh) {
            lastRealSpeedKmh = speedKmh

            val prevMax = lastSpeedMaxKmh
            lastSpeedMaxKmh = if (prevMax == null) speedKmh else maxOf(prevMax, speedKmh)

            host.onMain { onRealSpeedChanged?.invoke(speedKmh) }
            // Раз в секунду в движении — только в отладочный лог (debug-сборка).
            EquXLog.i(TAG_SPEED, "Скорость: $speedKmh км/ч")
        }
        // Фильтру нужен каждый сэмпл (и повторяющийся): по ним он понимает, что сигнал жив.
        feedSpeedSample(speedKmh)
    }

    private fun feedSpeedSample(rawKmh: Float) = synchronized(speedFilter) {
        applyEffectiveSpeedLocked(speedFilter.onSample(rawKmh, System.currentTimeMillis()))
    }

    private fun tickSpeedFilter() = synchronized(speedFilter) {
        applyEffectiveSpeedLocked(speedFilter.tick(System.currentTimeMillis()))
    }

    /**
     * Эффективная скорость → скоростной EQ движка и GALA (только при реальном изменении).
     * Вызывается под блокировкой speedFilter, чтобы значения из разных потоков не применялись
     * в обратном порядке. Порядок блокировок speedFilter → galaVolumeLock, обратного нет.
     */
    private fun applyEffectiveSpeedLocked(effectiveKmh: Float) {
        val prev = lastEffectiveSpeedKmh
        if (prev != null && kotlin.math.abs(prev - effectiveKmh) < 0.01f) return
        lastEffectiveSpeedKmh = effectiveKmh
        host.onEffectiveSpeed(effectiveKmh)
    }

    private fun logPublicMethods(tag: String, cls: Class<*>) {
        try {
            val desc = cls.methods
                .joinToString(", ") { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
            EquXLog.i(TAG_SPEED, "методы $tag: $desc")
        } catch (e: Exception) {
            Log.w(TAG_SPEED, "не удалось перечислить методы $tag: ${e.message}")
        }
    }

    /**
     * Читает PERF_VEHICLE_SPEED через `cmd car_service get-property-value 0x11600207`.
     * Этот путь работает, даже когда Java-рефлексия по
     * android.car.Car падает с NoSuchMethod (OEM-сборка с нестандартным API).
     */
    private fun readSpeedViaShellAndApply() {
        val now = System.currentTimeMillis()
        if (now - lastShellReadAt < 2000) return
        lastShellReadAt = now
        val mps = readSpeedViaShell() ?: return
        shellReadErrorLogged = false
        recordSourceSample(SpeedSource.VHAL, mps * 3.6f)
    }

    private fun readSpeedViaShell(): Float? {
        return try {
            val process = ProcessBuilder("cmd", "car_service", "get-property-value", "0x11600207")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            parseSpeedFromCarServiceOutput(output)
        } catch (e: Exception) {
            if (!shellReadErrorLogged) {
                shellReadErrorLogged = true
                Log.e(TAG_SPEED, "readSpeedViaShell: ошибка (${e.javaClass.simpleName}): ${e.message}")
            }
            null
        }
    }

    private fun parseSpeedFromCarServiceOutput(output: String): Float? {
        // Формат: ...floatValues: [0.0],int32Values:...
        val marker = "floatValues:"
        val markerIdx = output.indexOf(marker)
        if (markerIdx < 0) {
            if (!shellReadErrorLogged) {
                shellReadErrorLogged = true
                Log.w(TAG_SPEED, "parseSpeedFromCarServiceOutput: не найден floatValues в выводе cmd")
            }
            return null
        }
        val after = output.substring(markerIdx + marker.length)
        val open = after.indexOf('[')
        val close = after.indexOf(']')
        if (open < 0 || close < 0 || close <= open) {
            if (!shellReadErrorLogged) {
                shellReadErrorLogged = true
                Log.w(TAG_SPEED, "parseSpeedFromCarServiceOutput: не удалось найти скобки floatValues")
            }
            return null
        }
        val inside = after.substring(open + 1, close).trim()
        val first = inside.split(',', ' ').firstOrNull { it.isNotBlank() }
        return first?.toFloatOrNull()
    }

    private fun ensureEcarxConnected() {
        if (ecarxServiceBound) return
        val now = System.currentTimeMillis()
        if (now - ecarxLastConnectAttemptAt < 5000) return
        ecarxLastConnectAttemptAt = now
        try {
            val intent = Intent("ecarx.car.IECarXCar")
                .setClassName("com.ecarx.car", "com.ecarx.car.ECarXCarService")
            val ok = context.bindService(intent, ecarxServiceConnection, Context.BIND_AUTO_CREATE)
            EquXLog.i(TAG_SPEED, "ECARX: bindService=$ok")
        } catch (e: Exception) {
            Log.w(TAG_SPEED, "ECARX: bindService ошибка: ${e.message}")
        }
    }

    private fun probeEcarxProperties(propManager: Any?, propClass: Class<*>, valueClass: Class<*>) {
        if (propManager == null) return
        try {
            val getPropertyId = valueClass.getMethod("getPropertyId")
            val list = propClass.getMethod("getPropertyList").invoke(propManager) as? List<*>
            val ids = list?.mapNotNull { v ->
                runCatching { getPropertyId.invoke(v) as? Int }.getOrNull()
            }
            EquXLog.i(TAG_SPEED, "ECARX: getPropertyList=${ids?.joinToString { "0x%x".format(it) }}")
        } catch (e: Exception) {
            Log.w(TAG_SPEED, "ECARX: getPropertyList не удался: ${e.message}")
        }

        for (id in ecarxSpeedCandidateIds) {
            try {
                val value = ecarxGetPropertyMethod?.invoke(propManager, id, 0)
                val raw = ecarxGetValueMethod?.invoke(value)
                EquXLog.i(TAG_SPEED, "ECARX: probe id=0x%x -> $raw".format(id))
                val f = extractFirstFloat(raw)
                // Раньше ждали f > 0, из-за чего на стоянке (0.0) источник не фиксировался.
                // Теперь достаточно, что свойство реально прочиталось как float (в т.ч. 0.0),
                // а не вернуло null/exception.
                if (f != null && ecarxResolvedSpeedPropId <= 0) {
                    ecarxResolvedSpeedPropId = id
                    // м/с: VehSpdLgtA (0x2160728C) и PERF_VEHICLE_SPEED (0x11600207).
                    // км/ч: CLUSTER_FIXDISPLAY_VEHSPDLGTA (0x21401B54) и адапт-api сенсоры.
                    ecarxResolvedSpeedIsMps = (id == 0x11600207 || id == 0x2160728C)
                    EquXLog.i(TAG_SPEED, "ECARX: источник скорости id=0x%x (mps=${ecarxResolvedSpeedIsMps})".format(id))
                }
            } catch (e: Exception) {
                Log.w(TAG_SPEED, "ECARX: probe id=0x%x ошибка: ${e.message}".format(id))
            }
        }

        try {
            val value = ecarxGetPropertyMethod?.invoke(propManager, ecarxSpeedQualityPropId, 0)
            val raw = ecarxGetValueMethod?.invoke(value)
            EquXLog.i(TAG_SPEED, "ECARX: probe quality id=0x%x -> $raw".format(ecarxSpeedQualityPropId))
        } catch (e: Exception) {
            Log.w(TAG_SPEED, "ECARX: probe quality id=0x%x ошибка: ${e.message}".format(ecarxSpeedQualityPropId))
        }
    }

    private fun tryReadEcarxSpeed(): Boolean {
        val manager = ecarxPropertyManager ?: return false
        val getMethod = ecarxGetPropertyMethod ?: return false
        val getValue = ecarxGetValueMethod ?: return false
        val id = ecarxResolvedSpeedPropId
        if (id <= 0) return false
        return try {
            val q = readEcarxInt(manager, getMethod, getValue, ecarxSpeedQualityPropId)
            if (q != null && q < 2) {
                EquXLog.i(TAG_SPEED, "ECARX: скорость невалидна (quality=$q) — пропуск")
                false
            } else {
                val value = getMethod.invoke(manager, id, 0) ?: return false
                val raw = getValue.invoke(value)
                val f = extractFirstFloat(raw) ?: return false
                val kmh = if (ecarxResolvedSpeedIsMps) f * 3.6f else f
                recordSourceSample(SpeedSource.ECARX, kmh)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun readEcarxInt(manager: Any, getMethod: Method, getValue: Method, id: Int): Int? {
        return try {
            val value = getMethod.invoke(manager, id, 0) ?: return null
            val raw = getValue.invoke(value)
            when (raw) {
                is Int -> raw
                is Float -> raw.toInt()
                is Double -> raw.toInt()
                is Number -> raw.toInt()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Остановка: цикл, GPS, колбэк VHAL, привязка ECARX (из onDestroy службы). */
    fun stop() {
        speedMonitorJob?.cancel()
        stopGps()
        try {
            if (carPropertyManager != null && speedCallbackProxy != null) {
                val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
                val unregisterMethod = carPropertyManager?.javaClass?.getMethod(
                    "unregisterCallback",
                    callbackClass,
                    Int::class.javaPrimitiveType
                )
                unregisterMethod?.invoke(carPropertyManager, speedCallbackProxy, speedPropertyId)
            }
        } catch (e: Exception) {
            // Освобождение при остановке: Car API мог уже отключиться — ошибка не важна.
        }
        try {
            if (ecarxServiceBound) context.unbindService(ecarxServiceConnection)
        } catch (e: Exception) {
            // Освобождение при остановке: служба ECARX могла уже отвязаться.
        }
        ecarxServiceBound = false
        ecarxPropertyManager = null
        ecarxGetPropertyMethod = null
        ecarxGetValueMethod = null
        carApiInstance = null
        carPropertyManager = null
        speedCallbackProxy = null
        getFloatPropertyMethod = null
        getCarPropertyValueMethod = null
    }
}
