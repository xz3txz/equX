import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================
// Автоверсия: хранится в version.properties (в корне проекта).
// Если включена (equx.autoVersion=true в local.properties или
// -Pequx.autoVersion=true), при каждой упаковочной сборке
// (assemble*/bundle*) versionCode увеличивается на 1, а patch-часть
// versionName повышается (1.0.3 -> 1.0.4). По умолчанию выключена,
// чтобы у контрибьюторов не появлялись лишние диффы.
// Имя APK: equx_<versionName>-<buildType>.apk
// ============================================================
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val autoVersionEnabled =
    (providers.gradleProperty("equx.autoVersion").orNull ?: localProps.getProperty("equx.autoVersion"))
        ?.trim()?.toBoolean() == true

val versionFile = rootProject.file("version.properties")

val versionProps = Properties().apply {
    if (versionFile.exists()) {
        versionFile.inputStream().use { load(it) }
    }
}

val currentCode = (versionProps.getProperty("versionCode") ?: "1").toIntOrNull() ?: 1
val currentName = versionProps.getProperty("versionName") ?: "1.0.0"

fun bumpPatch(name: String): String {
    val parts = name.split(".")
    val last = parts.lastOrNull()?.toIntOrNull()
    return if (last != null) (parts.dropLast(1) + (last + 1)).joinToString(".") else name
}

val isPackagingBuild = autoVersionEnabled && gradle.startParameter.taskNames.any {
    it.contains("assemble", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
}

// Используем отличные от DSL имена, чтобы внутри android.defaultConfig
// скриптовые значения не затенялись одноимёнными свойствами AGP.
// (Из-за затенения versionName/versionCode не попадали в манифест.)
val autoVersionCode = if (isPackagingBuild) currentCode + 1 else currentCode
val autoVersionName = if (isPackagingBuild) bumpPatch(currentName) else currentName

if (isPackagingBuild) {
    versionProps.setProperty("versionCode", autoVersionCode.toString())
    versionProps.setProperty("versionName", autoVersionName)
    versionFile.parentFile?.mkdirs()
    versionFile.outputStream().use { versionProps.store(it, "auto-generated, do not edit manually") }
}

// Ключ подписи release — в keystore.properties (в корне, вне git):
//   storeFile=..., storePassword=..., keyAlias=..., keyPassword=...
// Без файла release подписывается debug-ключом (локальные сборки контрибьюторов).
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.xz3t.equX"
    compileSdk = 35
    // Стабильный стандарт компиляции

    defaultConfig {
        applicationId = "com.xz3t.equX"
        minSdk = 29
        targetSdk = 35
        versionCode = autoVersionCode
        versionName = autoVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // BuildConfig.DEBUG: подробные (info/debug) логи пишутся только в debug-сборке.
    buildFeatures {
        buildConfig = true
    }

    // ПРИНУДИТЕЛЬНАЯ ФИКСАЦИЯ ПУТИ К МАНИФЕСТУ
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // R8 и в debug: без сжатия APK ~30+ МБ. Правила общие с release.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            // R8: без него Compose неоптимизирован, APK ~30+ МБ (material-icons-extended).
            // Keep-правила для Gson-моделей и enum'ов DataStore — в proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Переименование APK, чтобы в имени файла всегда была актуальная версия
// (защита от путаницы «старый/новый APK» при переносе на магнитолу).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("equx_${autoVersionName}-${variant.name}.apk")
        }
    }
}

dependencies {
    // Базовый стек Jetpack Compose (Material 3) — берем версии напрямую из libs-каталога Студии
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.compose.material:material-icons-extended")

    // Низкоуровневые библиотеки ядра equX
    implementation("androidx.datastore:datastore-preferences:1.1.1") // Память ползунков магнитолы
    implementation("com.google.code.gson:gson:2.11.0")               // Парсер JSON пресетов
    compileOnly(project(":carstubs"))                                 // Stub CarCustomSettingCallback (класс берётся из прошивки)

    // Тестирование и отладка (Все дубликаты platform удалены)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
