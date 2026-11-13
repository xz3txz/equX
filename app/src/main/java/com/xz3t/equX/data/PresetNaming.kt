package com.xz3t.equX.data

/**
 * Символы, недопустимые в имени пресета. Имя пресета становится именем файла при экспорте,
 * а в общей памяти Android, на FAT/exFAT (флешки, SD) и в Windows эти символы запрещены.
 */
const val FORBIDDEN_PRESET_NAME_CHARS = "/\\:*?\"<>|"

enum class PresetNameError { EMPTY, FORBIDDEN_CHARS, TRAILING_DOT, TAKEN }

private val NUMBER_SUFFIX = Regex("^(.*)_(\\d+)$")

/**
 * Ошибка в имени или null, если имя допустимо. Пробелы по краям не считаются ошибкой
 * (имя обрезается при сохранении). Уникальность — без учёта регистра: на Android,
 * FAT/exFAT и Windows «Rock.json» и «rock.json» — один файл.
 *
 * @param takenNames имена существующих пресетов (включая встроенный и его перевод).
 * @param ownName текущее имя переименовываемого пресета — оно не считается занятым.
 */
fun presetNameError(
    raw: String,
    takenNames: Collection<String>,
    ownName: String? = null,
): PresetNameError? {
    val name = raw.trim()
    if (name.isEmpty()) return PresetNameError.EMPTY
    if (name.any { it in FORBIDDEN_PRESET_NAME_CHARS }) return PresetNameError.FORBIDDEN_CHARS
    if (name.endsWith('.')) return PresetNameError.TRAILING_DOT
    if (ownName != null && name.equals(ownName.trim(), ignoreCase = true)) return null
    if (takenNames.any { it.trim().equals(name, ignoreCase = true) }) return PresetNameError.TAKEN
    return null
}

/**
 * Допустимый вариант имени: запрещённые символы → «_», пробелы и точки в конце срезаются.
 * Пустой результат заменяется на [fallback].
 */
fun sanitizePresetName(raw: String, fallback: String): String {
    val replaced = raw.map { if (it in FORBIDDEN_PRESET_NAME_CHARS) '_' else it }.joinToString("")
    val clean = replaced.trim().trimEnd { it == '.' || it.isWhitespace() }
    return clean.ifEmpty { fallback }
}

/**
 * Свободное допустимое имя на основе [raw]: исправленное имя, если оно свободно, иначе с
 * числовым суффиксом. Уже имеющийся суффикс «_N» продолжается: «Rock_2» занято → «Rock_3».
 */
fun suggestPresetName(raw: String, takenNames: Collection<String>, fallback: String): String {
    val clean = sanitizePresetName(raw, fallback)
    if (presetNameError(clean, takenNames) == null) return clean

    val match = NUMBER_SUFFIX.matchEntire(clean)?.takeIf { it.groupValues[1].isNotBlank() }
    val base = match?.groupValues?.get(1) ?: clean
    var n = (match?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
    while (presetNameError("${base}_$n", takenNames) != null) n++
    return "${base}_$n"
}
