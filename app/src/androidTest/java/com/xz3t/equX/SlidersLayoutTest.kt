package com.xz3t.equX

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xz3t.equX.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlidersLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun slidersAreVisibleWithoutWrappingErrors() {
        dismissFirstRunDialogsIfAny(composeRule)

        // Идем на вкладку Compressor
        composeRule.onNodeWithText("Compressor").performClick()

        // Проверяем, что слайдеры отрендерились и их лейблы не разбиты (определяются как единый узел)
        composeRule.onNodeWithText("Threshold").assertIsDisplayed()
        composeRule.onNodeWithText("Ratio").assertIsDisplayed()

        // Идем на вкладку FX
        composeRule.onNodeWithText("FX").performClick()

        // Проверяем лейблы FX
        composeRule.onNodeWithText("Bass Boost").assertIsDisplayed()
        composeRule.onNodeWithText("Virtualizer").assertIsDisplayed()
    }
}
