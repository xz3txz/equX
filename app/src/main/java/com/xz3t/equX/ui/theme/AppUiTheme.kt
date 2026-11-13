package com.xz3t.equX.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Единая система дизайна UI/UX для equX (Premium Automotive & Dark System)
 */
object AppUiTheme {
    // Внешние отступы и зазоры между картами
    val cardOuterPaddingHorizontal: Dp = 12.dp
    val cardOuterPaddingVertical: Dp = 8.dp
    val cardSpacing: Dp = 8.dp

    // Внутренние отступы внутри карт
    val cardInnerPaddingHorizontal: Dp = 12.dp
    val cardInnerPaddingVertical: Dp = 10.dp

    val presetCardHeight: Dp = 56.dp
    val presetCardPaddingHorizontal: Dp = 14.dp

    val labelBoxWidth: Dp = 110.dp
    val valueTextWidth: Dp = 60.dp
    val iconButtonSize: Dp = 36.dp
    val spacerGap: Dp = 6.dp

    // Скругления карточек и элементов
    val mainCardCornerRadius: Dp = 16.dp
    val subCardCornerRadius: Dp = 12.dp

    // Плоская графика без размытых теней
    val cardElevation: Dp = 0.dp

    // Цвета берутся из MaterialTheme.colorScheme, чтобы поддержать ночную/дневную тему.
    val appBackgroundColor: @Composable () -> Color = { MaterialTheme.colorScheme.background }

    // Цвет шапки и зоны статус-бара
    val topHeaderBackgroundColor: @Composable () -> Color = { MaterialTheme.colorScheme.surface }

    // ПЛОТНАЯ 3-УРОВНЕВАЯ ИЕРАРХИЯ ЦВЕТОВ КАРТОЧЕК:
    // 1. Выключенная главная карточка
    val inactiveHeaderColor: @Composable () -> Color = { MaterialTheme.colorScheme.surfaceContainerLowest }

    // 2. Включенная главная карточка
    val activeHeaderColor: @Composable () -> Color = { MaterialTheme.colorScheme.surfaceContainerLow }

    // 3. Внутренние под-карточки в меню "Настроить"
    val activeSubCardColor: @Composable () -> Color = { MaterialTheme.colorScheme.surfaceContainer }
    val inactiveSubCardColor: @Composable () -> Color = { MaterialTheme.colorScheme.surfaceContainerLowest }

    // Основной акцентный цвет (iOS / Automotive Electric Blue)
    val accentColor: @Composable () -> Color = { MaterialTheme.colorScheme.primary }
}

/**
 * Адаптивная ширина метки поля-слайдера.
 *
 * Расчёт пропорционален [LocalConfiguration.screenWidthDp] (ширина в dp уже учитывает
 * и разрешение, и плотность экрана). На части ГУ densityDpi занижен (160),
 * поэтому экран виден Android'ом как ~1440dp, и метка получается широкой.
 *
 * Формула: ширина * 0.18, с ограничением [min, max].
 *  - телефон (~360dp) → 110dp (нижняя граница);
 *  - ГУ (~1440dp)      → ~259dp → ограничено 260dp.
 *
 * Слайдер при этом занимает оставшееся место, поэтому на широких экранах он короче,
 * а длинным подписям хватает места без переносов и обрезки.
 */
@Composable
fun responsiveLabelBoxWidth(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val computed = (widthDp * 0.18f).roundToInt().dp
    return computed.coerceIn(AppUiTheme.labelBoxWidth, 260.dp)
}

/**
 * Адаптивная ширина числового значения у поля-слайдера.
 *
 * Формула: ширина * 0.075, с ограничением [min, max].
 */
@Composable
fun responsiveValueTextWidth(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val computed = (widthDp * 0.075f).roundToInt().dp
    return computed.coerceIn(AppUiTheme.valueTextWidth, 110.dp)
}

/**
 * Адаптивная ширина значения для поля «Статистика скорости».
 *
 * Строка «Текущая: X  Макс: Y» длиннее обычных значений, поэтому ей нужна
 * чуть более широкая правая колонка, чем обычному числу.
 */
@Composable
fun responsiveStatsValueWidth(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val computed = (widthDp * 0.15f).roundToInt().dp
    return computed.coerceIn(120.dp, 220.dp)
}
