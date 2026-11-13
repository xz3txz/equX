package com.xz3t.equX

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xz3t.equX.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-тесты для экрана пресетов.
 */
@RunWith(AndroidJUnit4::class)
class PresetScreenSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun createPresetDialogAppears() {
        dismissFirstRunDialogsIfAny(composeRule)

        composeRule.onNodeWithText("Presets").performClick()

        // Открытие диалога создания
        composeRule.onNodeWithText("Create").performClick()
        composeRule.onNodeWithText("New profile").assertIsDisplayed()

        // Закрытие
        composeRule.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun exportAndImportButtonsPresent() {
        dismissFirstRunDialogsIfAny(composeRule)

        composeRule.onNodeWithText("Presets").performClick()

        // Проверяем наличие кнопок экспорта/импорта на экране
        composeRule.onNodeWithText("Export").assertIsDisplayed()
        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }
}
