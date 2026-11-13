package com.xz3t.equX.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.xz3t.equX.R

class AudioDeviceScanner(private val context: Context) {

    companion object {
        // AudioDeviceInfo.TYPE_BLE_HEADSET появился только в API 33, а minSdk у приложения 29.
        // Поэтому используем именованную константу вместо магического числа 26.
        private const val TYPE_BLE_HEADSET = 26

        @Volatile
        private var cachedIsAutomotive: Boolean? = null

        /**
         * Единственная проверка «работаем на автомобильном ГУ» для всего приложения
         * (служба, сканер, вкладка Car, источники звука, диалог автозапуска).
         *
         * На части ГУ системный флаг FEATURE_AUTOMOTIVE может быть
         * не выставлен, но клиентская библиотека Car API (класс android.car.Car) есть —
         * через неё по рефлексии работают скорость и громкость. Результат не меняется
         * за время жизни процесса, поэтому кэшируется.
         */
        fun isAutomotive(context: Context): Boolean {
            cachedIsAutomotive?.let { return it }
            val result = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
                try {
                    Class.forName("android.car.Car")
                    true
                } catch (_: Throwable) {
                    false
                }
            cachedIsAutomotive = result
            return result
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun scanAvailableOutputDevices(): List<AudioDeviceBinding> {
        val btList = mutableListOf<AudioDeviceBinding>()
        val systemList = mutableListOf<AudioDeviceBinding>()

        // На автомобильной магнитоле (ГУ) Bluetooth-роль инвертирована: сопряжённые
        // телефоны являются ИСТОЧНИКАМИ звука (A2DP source), а не устройствами вывода.
        // Показывать их в списке «Устройства вывода» бессмысленно — выбор не будет работать.
        // Поэтому на ГУ Bluetooth-устройства не добавляем вообще, реальный вывод — это
        // встроенный усилитель/динамики, AUX или USB DAC.
        if (!isAutomotive(context)) {
            try {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val bluetoothAdapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

                if (bluetoothAdapter != null) {
                    val bondedDevices = try { bluetoothAdapter.bondedDevices } catch (e: Exception) { null }
                    if (!bondedDevices.isNullOrEmpty()) {
                        for (device in bondedDevices) {
                            val name = try { device.name ?: context.getString(R.string.device_bluetooth) } catch (e: Exception) { context.getString(R.string.device_bluetooth) }
                            val address = device.address ?: ""
                            val validId = if (address.isNotBlank() && address != "00:00:00:00:00:00") address else name
                            btList.add(AudioDeviceBinding(validId, name, "BLUETOOTH"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("equX_DeviceScanner", "Ошибка сканирования BluetoothAdapter: ${e.message}")
            }
        }

        systemList.add(AudioDeviceBinding("TYPE_AUX", context.getString(R.string.device_aux), "AUX"))
        systemList.add(AudioDeviceBinding("TYPE_USB_DAC", context.getString(R.string.device_usb_dac), "USB_DAC"))
        systemList.add(AudioDeviceBinding("TYPE_SPEAKER", context.getString(R.string.built_in_speaker), "SPEAKER"))

        val fullList = mutableListOf<AudioDeviceBinding>()
        fullList.addAll(btList.distinctBy { it.id })
        fullList.addAll(systemList.distinctBy { it.id })

        return fullList.distinctBy { it.id }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentActiveOutputDevice(): AudioDeviceBinding {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                
                // 1. Сначала отфильтруем все Bluetooth-устройства
                val btDevices = devices.filter {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == TYPE_BLE_HEADSET
                }

                if (btDevices.isNotEmpty()) {
                    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val bluetoothAdapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
                    val bondedDevices = try { bluetoothAdapter?.bondedDevices } catch (e: Exception) { null }

                    // Шаг А: Прямой опрос A2DP профиля (самый точный системный метод приоритета №1)
                    try {
                        val connectedA2dp = bluetoothManager?.getConnectedDevices(BluetoothProfile.A2DP)
                        val activeBt = connectedA2dp?.firstOrNull()
                        if (activeBt != null) {
                            val name = try { activeBt.name ?: "" } catch (e: Exception) { "" }
                            val addr = activeBt.address ?: ""
                            if (name.isNotBlank() && !isHostDeviceName(name)) {
                                val validId = if (addr.isNotBlank() && addr != "00:00:00:00:00:00") addr else name
                                return AudioDeviceBinding(validId, name, "BLUETOOTH")
                            }
                        }
                    } catch (e: Exception) {
                        EquXLog.w("equX_DeviceScanner", "A2DP: ошибка опроса подключённых устройств: ${e.message}")
                    }

                    // Шаг Б: Ищем устройство с НАСТОЯЩИМ MAC-адресом от AudioManager
                    for (device in btDevices) {
                        val address = device.address ?: ""
                        if (address.isNotBlank() && address != "00:00:00:00:00:00") {
                            // Ищем этот MAC в списке сопряженных, чтобы достать красивое имя
                            val matchedBt = bondedDevices?.firstOrNull { it.address.equals(address, ignoreCase = true) }
                            if (matchedBt != null) {
                                val name = try { matchedBt.name ?: "" } catch (e: Exception) { "" }
                                if (name.isNotBlank()) return AudioDeviceBinding(address, name, "BLUETOOTH")
                            }

                            // Если в сопряженных нет, берем имя от AudioManager
                            val rawName = device.productName?.toString()?.trim() ?: ""
                            if (rawName.isNotBlank() && !isHostDeviceName(rawName)) {
                                return AudioDeviceBinding(address, rawName, "BLUETOOTH")
                            }
                        }
                    }

                    // Шаг В: Если нормального MAC нет, ищем по совпадению имени
                    for (device in btDevices) {
                        val rawName = device.productName?.toString()?.trim() ?: ""
                        if (rawName.isNotBlank() && !isHostDeviceName(rawName)) {
                            // Пробуем найти MAC по совпадению имени в сопряженных
                            val matchedBt = bondedDevices?.firstOrNull { 
                                val bName = try { it.name ?: "" } catch (e: Exception) { "" }
                                bName.equals(rawName, ignoreCase = true) 
                            }
                            val finalAddress = matchedBt?.address ?: rawName
                            return AudioDeviceBinding(finalAddress, rawName, "BLUETOOTH")
                        }
                    }

                    // Шаг Г: Если вообще ничего не сработало — выводим "Bluetooth Unknown"
                    return AudioDeviceBinding("BT_UNKNOWN", "Bluetooth Unknown", "BLUETOOTH")
                }

                // 2. Ищем USB DAC или проводные наушники (если нет Bluetooth)
                for (device in devices) {
                    when (device.type) {
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                            val name = if (!device.productName.isNullOrBlank()) device.productName.toString() else context.getString(R.string.device_usb_dac_short)
                            return AudioDeviceBinding("TYPE_USB_DAC", name, "USB_DAC")
                        }
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_LINE_ANALOG,
                        AudioDeviceInfo.TYPE_AUX_LINE -> {
                            return AudioDeviceBinding("TYPE_AUX", context.getString(R.string.device_aux), "AUX")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("equX_DeviceScanner", "Ошибка определения активного устройства вывода: ${e.message}", e)
        }

        return AudioDeviceBinding("TYPE_SPEAKER", context.getString(R.string.built_in_speaker), "SPEAKER")
    }

    /**
     * Сканирует источники звука (актуально только на автомобильном ГУ).
     *
     * На ГУ сопряжённые телефоны — это A2DP-источники, а не устройства вывода.
     * На обычном смартфоне источников нет, возвращаем пустой список.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun scanAvailableSources(): List<AudioDeviceBinding> {
        if (!isAutomotive(context)) return emptyList()

        val sources = mutableListOf<AudioDeviceBinding>()
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            val bondedDevices = try { bluetoothAdapter?.bondedDevices } catch (e: Exception) { null }

            if (!bondedDevices.isNullOrEmpty()) {
                for (device in bondedDevices) {
                    val name = try {
                        device.name ?: context.getString(R.string.device_bluetooth)
                    } catch (e: Exception) {
                        context.getString(R.string.device_bluetooth)
                    }
                    val address = device.address ?: ""
                    val validId = if (address.isNotBlank() && address != "00:00:00:00:00:00") address else name
                    sources.add(AudioDeviceBinding(validId, name, "SOURCE"))
                }
            }
        } catch (e: Exception) {
            Log.e("equX_DeviceScanner", "Ошибка сканирования источников: ${e.message}")
        }

        return sources.distinctBy { it.id }
    }

    /**
     * Возвращает активный источник звука (A2DP-телефон) на автомобильном ГУ,
     * либо null на обычном смартфоне или когда источник не подключён.
     */
    @SuppressLint("MissingPermission")
    fun getCurrentActiveSource(): AudioDeviceBinding? {
        if (!isAutomotive(context)) return null
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val activeBt = bluetoothManager?.getConnectedDevices(BluetoothProfile.A2DP)?.firstOrNull()
            if (activeBt == null) {
                null
            } else {
                val name = try { activeBt.name ?: context.getString(R.string.device_bluetooth) } catch (e: Exception) { context.getString(R.string.device_bluetooth) }
                val address = activeBt.address ?: ""
                val validId = if (address.isNotBlank() && address != "00:00:00:00:00:00") address else name
                AudioDeviceBinding(validId, name, "SOURCE")
            }
        } catch (e: Exception) {
            Log.e("equX_DeviceScanner", "Ошибка определения активного источника: ${e.message}")
            null
        }
    }

    /**
     * Проверяет, является ли переданное имя системным именем самого смартфона
     */
    private fun isHostDeviceName(name: String): Boolean {
        if (name.isBlank()) return true
        val hostModel = Build.MODEL ?: ""
        val hostProduct = Build.PRODUCT ?: ""
        
        return (hostModel.isNotBlank() && name.equals(hostModel, ignoreCase = true)) ||
               (hostProduct.isNotBlank() && name.equals(hostProduct, ignoreCase = true)) ||
               name.equals("Android", ignoreCase = true)
    }
}
