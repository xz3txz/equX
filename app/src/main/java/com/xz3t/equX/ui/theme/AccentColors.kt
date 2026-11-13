package com.xz3t.equX.ui.theme

import androidx.annotation.StringRes
import com.xz3t.equX.R

/**
 * Акцентный цвет темы.
 */
data class AccentColorOption(
    @StringRes val nameRes: Int,
    val argb: Int,
)

/**
 * Палитра акцентных цветов.
 *
 * Подобрана по правилам UI/UX: каждый оттенок достаточно насыщенный и
 * контрастный, чтобы читаться и на тёмной, и на светлой теме.
 */
val AccentColorOptions: List<AccentColorOption> = listOf(
    AccentColorOption(R.string.accent_blue, 0xFF3498DB.toInt()),
    AccentColorOption(R.string.accent_pink, 0xFFDB4A78.toInt()),
    AccentColorOption(R.string.accent_orange, 0xFFE07B1A.toInt()),
    AccentColorOption(R.string.accent_emerald, 0xFF17A673.toInt()),
    AccentColorOption(R.string.accent_violet, 0xFF7C5CE0.toInt()),
    AccentColorOption(R.string.accent_teal, 0xFF0EA5B5.toInt()),
)
