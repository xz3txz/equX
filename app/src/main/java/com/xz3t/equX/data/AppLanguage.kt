package com.xz3t.equX.data

import java.util.Locale

/**
 * Язык интерфейса приложения.
 *
 * SYSTEM — следовать за языком системы; если язык системы не поддерживается,
 * Android сам выбирает ближайший доступный (по нашей конфигурации — английский).
 */
enum class AppLanguage(val tag: String?, val displayName: String) {
    SYSTEM(null, "System"),
    RUSSIAN("ru", "Русский"),
    ENGLISH("en", "English"),
    DUTCH("nl", "Nederlands"),
    GERMAN("de", "Deutsch"),
    CHINESE("zh", "中文"),
    JAPANESE("ja", "日本語");

    val locale: Locale?
        get() = tag?.let { Locale.forLanguageTag(it) }

    companion object {
        fun fromTag(tag: String?): AppLanguage = values().firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
