package com.xz3t.equX.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.xz3t.equX.data.AudioDeviceScanner
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Открывает системный экран автозапуска / фоновой активности для конкретной прошивки.
 *
 * «Автозапуск» — вендорская надстройка поверх AOSP, у каждого производителя свой экран,
 * а компоненты меняются между версиями прошивок. Поэтому стратегия:
 *  1. Определяем вендора по Build.MANUFACTURER / Build.BRAND.
 *  2. Пробуем его специфичные интенты (сначала action с extras, затем ComponentName).
 *  3. Каждый кандидат проверяем через resolveActivity — если компонента нет, пропускаем.
 *  4. Фолбэк — ACTION_APPLICATION_DETAILS_SETTINGS (работает на любом Android 10+).
 */
object OemAutostart {

    /**
     * Нужно ли показывать пользователю просьбу включить «автозапуск».
     *
     * Автозапуск — это вендорская надстройка над AOSP, которая есть только на китайских
     * прошивках (vivo/iQOO, Xiaomi, OPPO/realme/OnePlus, Huawei/Honor, Meizu, Letv).
     * На стоковом Android и Android Automotive эта настройка не нужна — служба
     * перезапускается системой (START_STICKY / BOOT_COMPLETED).
     */
    fun needsAutostartPrompt(context: Context): Boolean {
        if (AudioDeviceScanner.isAutomotive(context)) {
            return false
        }
        return isVivoIqoo() || isXiaomi() || isHuaweiHonor() ||
            isOppoRealmeOnePlus() || isMeizu() || isLetv()
    }

    fun open(context: Context): Boolean {
        val appPkg = context.packageName

        when {
            isVivoIqoo() -> if (openVivoIqoo(context, appPkg)) return true
            isXiaomi() -> if (openXiaomi(context, appPkg)) return true
            isHuaweiHonor() -> if (openHuaweiHonor(context)) return true
            isOppoRealmeOnePlus() -> if (openOppoRealmeOnePlus(context)) return true
            isSamsung() -> if (openSamsung(context)) return true
            isMeizu() -> if (openMeizu(context)) return true
            isLetv() -> if (openLetv(context)) return true
        }

        return openAppDetails(context, appPkg)
    }

    // ------------------------------------------------------------------ //
    // Детект вендора
    // ------------------------------------------------------------------ //

    private val vendor: String
        get() = "${Build.MANUFACTURER.orEmpty()} ${Build.BRAND.orEmpty()}"

    private fun String.containsAny(vararg keys: String): Boolean =
        keys.any { contains(it, ignoreCase = true) }

    private fun isVivoIqoo() = vendor.containsAny("vivo", "iqoo")
    private fun isXiaomi() = vendor.containsAny("xiaomi", "redmi", "poco")
    private fun isHuaweiHonor() = vendor.containsAny("huawei", "honor")
    private fun isOppoRealmeOnePlus() = vendor.containsAny("oppo", "realme", "oneplus", "oplus")
    private fun isSamsung() = vendor.containsAny("samsung")
    private fun isMeizu() = vendor.containsAny("meizu")
    private fun isLetv() = vendor.containsAny("letv", "leeco")

    // ------------------------------------------------------------------ //
    // vivo / iQOO (OriginOS / Funtouch)
    // ------------------------------------------------------------------ //

    private fun openVivoIqoo(context: Context, appPkg: String): Boolean {
        // Экран «Все разрешения» приложения — содержит и «Автозапуск», и «Связанный запуск».
        // Критично передать extra "packagename" (нижний регистр): без него экран мигает и закрывается.
        val detail = Intent("permission.intent.action.softPermissionDetail").apply {
            setPackage("com.vivo.permissionmanager")
            putExtra("packagename", appPkg)
            putExtra("packageName", appPkg)
        }
        if (tryStart(context, detail)) return true

        // Экран «Автозапуск» (без «Связанного запуска») — запасной вариант.
        val bgStart = Intent("com.iqoo.secure.BGSTARTUPMANAGER").apply {
            setPackage("com.vivo.permissionmanager")
        }
        return tryStart(context, bgStart) || tryStart(
            context,
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        )
    }

    // ------------------------------------------------------------------ //
    // Xiaomi (MIUI / HyperOS)
    // ------------------------------------------------------------------ //

    private fun openXiaomi(context: Context, appPkg: String): Boolean {
        val action = Intent("miui.intent.action.OP_AUTO_START").apply {
            setPackage("com.miui.securitycenter")
            putExtra("extra_pkgname", appPkg)
            putExtra("extra_package_name", appPkg)
        }
        if (tryStart(context, action)) return true

        return tryStart(
            context,
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
    }

    // ------------------------------------------------------------------ //
    // Huawei / Honor (EMUI / HarmonyOS / MagicOS)
    // ------------------------------------------------------------------ //

    private fun openHuaweiHonor(context: Context): Boolean {
        val candidates = listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        return candidates.any { tryStart(context, it) }
    }

    // ------------------------------------------------------------------ //
    // Oppo / Realme / OnePlus (ColorOS / OxygenOS 12+)
    // ------------------------------------------------------------------ //

    private fun openOppoRealmeOnePlus(context: Context): Boolean {
        val candidates = listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            // Старые OxygenOS / HydrogenOS до перехода на ColorOS.
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        return candidates.any { tryStart(context, it) }
    }

    // ------------------------------------------------------------------ //
    // Samsung (One UI) — отдельного «автозапуска» нет
    // ------------------------------------------------------------------ //

    private fun openSamsung(context: Context): Boolean {
        // Для фоновой работы важны «Не спящие приложения» и «Батарея → Без ограничений».
        // Пробуем Device Care → Батарея; если компонента не найдётся — фолбэк на страницу приложения.
        val candidates = listOf(
            component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        return candidates.any { tryStart(context, it) }
    }

    // ------------------------------------------------------------------ //
    // Meizu / Letv (Flyme / LeEco)
    // ------------------------------------------------------------------ //

    private fun openMeizu(context: Context): Boolean =
        tryStart(context, component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))

    private fun openLetv(context: Context): Boolean =
        tryStart(context, component("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"))

    // ------------------------------------------------------------------ //
    // Общие утилиты
    // ------------------------------------------------------------------ //

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun openAppDetails(context: Context, appPkg: String): Boolean {
        return tryStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$appPkg"))
        )
    }
}
