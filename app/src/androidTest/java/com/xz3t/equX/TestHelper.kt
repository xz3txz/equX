package com.xz3t.equX

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick

/**
 * Закрывает типовые диалоги первого запуска (автозапуск, доступ к уведомлениям),
 * если они отображаются на экране. Не падает, если их нет.
 */
fun dismissFirstRunDialogsIfAny(composeRule: AndroidComposeTestRule<*, *>) {
    composeRule.waitForIdle()
    listOf("Later", "Got it", "Done", "OK", "Cancel").forEach { label ->
        val nodes = composeRule.onAllNodesWithText(label).fetchSemanticsNodes()
        if (nodes.isNotEmpty()) {
            composeRule.onAllNodesWithText(label)[0].performClick()
            composeRule.waitForIdle()
        }
    }
}
