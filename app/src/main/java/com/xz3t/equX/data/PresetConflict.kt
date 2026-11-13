package com.xz3t.equX.data

/**
 * Чистые функции проверки конфликтов привязок (Правила 1 и 2).
 * Вынесены из PresetDataManager для покрытия unit-тестами.
 */

/**
 * Проверяет конфликт при попытке привязать [app]/[device] к [target].
 *
 * Правила:
 * - GLOBAL_DEVICE_DUPLICATE — оба пресета глобальные для одного устройства.
 * - CANNOT_BE_GLOBAL_HAS_APPS — текущий пытается стать глобальным, но у другого
 *   пресета уже есть приложения под это устройство.
 * - CANNOT_BIND_DEVICE_OTHER_IS_GLOBAL — другой пресет уже глобальный для устройства.
 * - APP_DEVICE_PAIR_DUPLICATE — дублируется конкретная связка приложение + устройство.
 */
fun checkBindingConflict(
    target: PresetModel,
    otherPresets: List<PresetModel>,
    app: AppBinding?,
    device: AudioDeviceBinding?,
): ConflictResult? {
    val testDevices = if (device != null && target.boundDevices.none { it.id == device.id }) {
        target.boundDevices + device
    } else {
        target.boundDevices
    }
    val testApps = if (app != null && target.boundApps.none { it.packageName == app.packageName }) {
        target.boundApps + app
    } else {
        target.boundApps
    }

    for (other in otherPresets) {
        val sharedDevices = testDevices.filter { td -> other.boundDevices.any { it.id == td.id } }
        if (sharedDevices.isEmpty()) continue

        val sharedApps = testApps.filter { ta -> other.boundApps.any { it.packageName == ta.packageName } }

        when {
            testApps.isEmpty() && other.boundApps.isEmpty() ->
                return ConflictResult(BindingConflictType.GLOBAL_DEVICE_DUPLICATE, other, sharedDevices.first(), null)

            testApps.isEmpty() && other.boundApps.isNotEmpty() ->
                return ConflictResult(BindingConflictType.CANNOT_BE_GLOBAL_HAS_APPS, other, sharedDevices.first(), null)

            testApps.isNotEmpty() && other.boundApps.isEmpty() ->
                return ConflictResult(BindingConflictType.CANNOT_BIND_DEVICE_OTHER_IS_GLOBAL, other, sharedDevices.first(), null)

            testApps.isNotEmpty() && other.boundApps.isNotEmpty() && sharedApps.isNotEmpty() ->
                return ConflictResult(BindingConflictType.APP_DEVICE_PAIR_DUPLICATE, other, sharedDevices.first(), sharedApps.first())
        }
    }
    return null
}

/**
 * Защита от удаления последнего приложения, если пресет при этом пытается стать
 * глобальным, а у устройства уже есть другие привязанные пресеты.
 */
fun checkAppRemovalConflict(
    target: PresetModel,
    otherPresets: List<PresetModel>,
    appToRemove: AppBinding,
): ConflictResult? {
    val isLastApp = target.boundApps.size == 1 &&
        target.boundApps[0].packageName == appToRemove.packageName
    if (!isLastApp) return null

    for (device in target.boundDevices) {
        val conflict = otherPresets.firstOrNull { it.boundDevices.any { d -> d.id == device.id } }
        if (conflict != null) {
            val type = if (conflict.boundApps.isEmpty()) {
                BindingConflictType.GLOBAL_DEVICE_DUPLICATE
            } else {
                BindingConflictType.CANNOT_BE_GLOBAL_HAS_APPS
            }
            return ConflictResult(type, conflict, device, appToRemove)
        }
    }
    return null
}
