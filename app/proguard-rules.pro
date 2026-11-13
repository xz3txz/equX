# equX — правила R8 для release.

# --- Gson-модели пресетов ---
# Имена полей = ключи JSON в DataStore, DE-кэше и файлах экспорта. Переименование или удаление
# «неиспользуемых» полей (audioSessionId, crossfeed* и т.п.) ломает чтение старых пресетов и формат файлов.
# Конструкторы оставляем: Gson создаёт объекты через no-arg конструктор или Unsafe.
-keep class com.xz3t.equX.data.PresetModel { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.AudioDeviceBinding { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.AppBinding { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.EqualizerConfig { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.CompressorConfig { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.BandDynamicParams { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.SpatialFxConfig { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.CarConfig { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.LimiterConfig { <fields>; <init>(...); }
-keep class com.xz3t.equX.data.SpeedEqBand { <fields>; <init>(...); }

# TypeToken<Map<String, PresetModel>>: нужна generic-сигнатура анонимного подкласса.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# --- Enum'ы, сохраняемые в DataStore по имени (name / valueOf / entries) ---
-keepclassmembers enum com.xz3t.equX.data.** { <fields>; public static **[] values(); public static ** valueOf(java.lang.String); }

# Car API / ECARX вызываются рефлексией по системным классам — в APK их нет.
# Исключение — HuCustomSettingCallback: наследник класса прошивки (в APK только compile-only stub
# из :carstubs). R8 не видит суперкласс, поэтому имена переопределённых методов надо сохранить.
-dontwarn android.car.media.CarAudioManager$CarCustomSettingCallback
-keep class com.xz3t.equX.service.HuCustomSettingCallback { *; }
# Номера строк в стектрейсах.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
