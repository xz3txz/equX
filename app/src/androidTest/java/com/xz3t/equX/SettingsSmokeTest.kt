package com.xz3t.equX

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xz3t.equX.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-тесты для диалогов глубоких настроек.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun themeDialogAppears() {
        dismissFirstRunDialogsIfAny(composeRule)

        // Открываем главные настройки
        composeRule.onNodeWithContentDescription("Settings").performClick()

        // Открываем диалог темы
        composeRule.onNodeWithText("Theme").performClick()
        composeRule.onNodeWithText("System").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()

        // Закрываем
        composeRule.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun languageDialogAppears() {
        dismissFirstRunDialogsIfAny(composeRule)

        // Открываем главные настройки
        composeRule.onNodeWithContentDescription("Settings").performClick()

        // Открываем диалог языка
        composeRule.onNodeWithText("Language").performClick()
        composeRule.onNodeWithText("English").assertIsDisplayed()

        // Закрываем
        composeRule.onNodeWithText("Cancel").performClick()
    }
}
