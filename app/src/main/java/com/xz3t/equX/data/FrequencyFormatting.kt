package com.xz3t.equX.data

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Парсит пользовательский ввод частоты.
 * Поддерживает: "16000", "16k", "16к", "16000hz", "16000гц".
 */
fun parseFrequencyInput(input: String): Float? {
    val trimmed = input.trim().lowercase(Locale.US)
        .replace("hz", "")
        .replace("гц", "")
        .trim()

    if (trimmed.endsWith("k") || trimmed.endsWith("к")) {
        val numPart = trimmed.dropLast(1).trim()
        val floatVal = numPart.toFloatOrNull() ?: return null
        return floatVal * 1000f
    }

    return trimmed.toFloatOrNull()
}

/**
 * Форматирует частоту в короткое отображаемое значение: "31", "1k", "1.5k".
 */
fun formatFrequencyShort(freqHz: Float): String {
    return if (freqHz >= 1000f) {
        val kVal = freqHz / 1000f
        if (kVal % 1f == 0f) {
            "${kVal.toInt()}k"
        } else {
            String.format(Locale.US, "%.1fk", kVal)
        }
    } else {
        "${freqHz.roundToInt()}"
    }
}
