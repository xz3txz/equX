package com.xz3t.equX

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xz3t.equX.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Базовые Compose UI smoke-тесты главного экрана.
 *
 * Это шаблон, от которого проще отталкиваться при написании UI-тестов.
 * Для «скриншотных» сценариев удобнее Journey-тесты (см. src/journeysTest/):
 * они пишутся на естественном языке и раннер сам снимает скриншоты на каждом шаге.
 *
 * ВАЖНО для запуска на эмуляторе:
 *  - MainActivity при старте запрашивает системные разрешения (RECORD_AUDIO и т.п.).
 *    Перед прогоном выдай их заранее:
 *      adb shell pm grant com.xz3t.equX android.permission.RECORD_AUDIO
 *      adb shell pm grant com.xz3t.equX android.permission.POST_NOTIFICATIONS
 *  - Первый запуск может показывать диалоги (автозапуск / доступ к уведомлениям),
 *    поэтому в начале тестов вызывается [dismissFirstRunDialogsIfAny].
 */
@RunWith(AndroidJUnit4::class)
class MainScreenSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainTabsAreVisible() {
        dismissFirstRunDialogsIfAny(composeRule)

        composeRule.onNodeWithText("Presets").assertIsDisplayed()
        composeRule.onNodeWithText("EQ").assertIsDisplayed()
        composeRule.onNodeWithText("Compressor").assertIsDisplayed()
        composeRule.onNodeWithText("FX").assertIsDisplayed()
        composeRule.onNodeWithText("Limiter").assertIsDisplayed()
    }

    @Test
    fun switchToEqualizerTab() {
        dismissFirstRunDialogsIfAny(composeRule)

        composeRule.onNodeWithText("EQ").performClick()
        // Внутри экрана EQ заголовок карточки — "Equalizer", а вкладка называется "EQ",
        // поэтому селектор по тексту однозначен.
        composeRule.onNodeWithText("Equalizer").assertIsDisplayed()
    }

    @Test
    fun openSettingsDialog() {
        dismissFirstRunDialogsIfAny(composeRule)

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Smooth preset transition").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Language").assertIsDisplayed()
        composeRule.onNodeWithText("Scale").assertIsDisplayed()
    }
}
