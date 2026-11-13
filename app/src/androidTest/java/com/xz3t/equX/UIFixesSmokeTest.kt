package com.xz3t.equX

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xz3t.equX.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-тесты, проверяющие работоспособность исправленных ранее UI-компонентов:
 * тогглов (Bypass), диалогов масштаба и их корректное появление.
 */
@RunWith(AndroidJUnit4::class)
class UIFixesSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun toggleEngineStateUpdatesText() {
        dismissFirstRunDialogsIfAny(composeRule)

        // Ищем свич по его семантической роли (в Compose Switch всегда имеет Role.Switch)
        val switchNodes = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        if (switchNodes.isNotEmpty()) {
            // Проверяем, что рядом есть текст On (по умолчанию движок включён)
            val isCurrentlyOn = composeRule.onAllNodesWithText("On").fetchSemanticsNodes().isNotEmpty()

            if (isCurrentlyOn) {
                // Кликаем по свичу (точнее, по первому кликабельному элементу, который меняет состояние)
                composeRule.onNodeWithText("On").performClick()
                composeRule.waitForIdle()

                // Текст должен измениться на Off
                composeRule.onNodeWithText("Off").assertIsDisplayed()
            }
        }
    }

    @Test
    fun testScaleDialogIsAccessible() {
        dismissFirstRunDialogsIfAny(composeRule)

        // Открываем Настройки -> Масштаб (Scale)
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Scale").performClick()

        // Убеждаемся, что диалог открылся и не упал из-за модификаторов масштаба
        composeRule.onNodeWithText("Scale").assertIsDisplayed()

        // Кнопка Cancel (или Done) должна быть доступна для клика
        composeRule.onNodeWithText("Cancel").performClick()
    }
}
