package com.xz3t.equX.data

/**
 * Чистые функции выбора пресета и фильтрации привязок.
 *
 * Вынесены из Android-зависимых классов, чтобы их можно было покрыть unit-тестами
 * и не сломать автопереключение при последующих рефакторингах.
 */

/**
 * Подбирает пресет по правилам Auto-Switch.
 *
 * Приоритет:
 * 1. Пресет, к которому привязаны и [activeDeviceId], и [pkg] (для не-GLOBAL пакета).
 * 2. Пресет, привязанный только к [activeDeviceId] (без приложений).
 * 3. Пресет с именем [defaultPresetName].
 *
 * Возвращает null, если не найден даже дефолтный пресет (вызывающая сторона создаёт фолбэк).
 */
fun selectPresetForAutoSwitch(
    pkg: String,
    activeDeviceId: String,
    allPresets: List<PresetModel>,
    defaultPresetName: String,
    activeSourceId: String? = null,
): PresetModel? {
    var target: PresetModel? = null

    // 1. Источник + приложение (только на ГУ, где активен A2DP-источник).
    if (!activeSourceId.isNullOrBlank() && pkg.isNotBlank() && pkg != "GLOBAL") {
        target = allPresets.firstOrNull { preset ->
            preset.boundSources.any { it.id == activeSourceId } &&
                preset.boundApps.any { it.packageName == pkg }
        }
    }

    // 2. Источник без приложений (пресет «по источнику»).
    if (target == null && !activeSourceId.isNullOrBlank()) {
        target = allPresets.firstOrNull { preset ->
            preset.boundSources.any { it.id == activeSourceId } &&
                preset.boundApps.isEmpty()
        }
    }

    // 3. Устройство вывода + приложение.
    if (target == null && pkg.isNotBlank() && pkg != "GLOBAL") {
        target = allPresets.firstOrNull { preset ->
            preset.boundDevices.any { it.id == activeDeviceId } &&
                preset.boundApps.any { it.packageName == pkg }
        }
    }

    // 4. Устройство вывода без приложений.
    if (target == null) {
        target = allPresets.firstOrNull { preset ->
            preset.boundDevices.any { it.id == activeDeviceId } &&
                preset.boundApps.isEmpty()
        }
    }

    // 5. Дефолтный пресет.
    if (target == null) {
        target = allPresets.firstOrNull { it.presetName == defaultPresetName }
    }

    return target
}

/**
 * Пресет без привязки к удалённому приложению [packageName] (тот же объект, если привязки не было).
 */
fun removeAppBinding(preset: PresetModel, packageName: String): PresetModel =
    if (preset.boundApps.none { it.packageName == packageName }) {
        preset
    } else {
        preset.copy(rawBoundApps = preset.boundApps.filter { it.packageName != packageName })
    }

/**
 * Пресет только с теми привязками устройств вывода и источников, которые прошли фильтры
 * (тот же объект, если ничего не удалено).
 */
fun retainDeviceBindings(
    preset: PresetModel,
    keepDevice: (AudioDeviceBinding) -> Boolean,
    keepSource: (AudioDeviceBinding) -> Boolean,
): PresetModel {
    val devices = preset.boundDevices.filter(keepDevice)
    val sources = preset.boundSources.filter(keepSource)
    if (devices.size == preset.boundDevices.size && sources.size == preset.boundSources.size) return preset
    return preset.copy(rawBoundDevices = devices, rawBoundSources = sources)
}
