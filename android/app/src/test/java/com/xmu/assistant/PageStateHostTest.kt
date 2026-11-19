package com.xmu.assistant

import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageStateHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `page input survives navigation and recreation but never crosses accounts`() {
        var page by mutableStateOf(Page.COURSEWARE)
        var owner by mutableStateOf("student-a")
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            XmuMobileTheme {
                PageStateHost(page, owner) {
                    var query by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(query, { query = it }, modifier = Modifier.testTag("query"))
                }
            }
        }
        composeRule.onNodeWithTag("query").performTextInput("高等数学")
        composeRule.runOnIdle { page = Page.SCHEDULE }
        composeRule.onNodeWithTag("query").assertTextEquals("")
        composeRule.runOnIdle { page = Page.COURSEWARE }
        composeRule.onNodeWithTag("query").assertTextEquals("高等数学")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("query").assertTextEquals("高等数学")
        composeRule.runOnIdle { owner = "student-b" }
        composeRule.onNodeWithTag("query").assertTextEquals("")
    }

    @Test fun `back targets preserve navigation hierarchy`() {
        assertEquals(Page.MORE, parentPage(Page.COURSEWARE))
        assertEquals(Page.MORE, parentPage(Page.SETTINGS))
        assertEquals(Page.HOME, parentPage(Page.MORE))
        assertEquals(Page.HOME, parentPage(Page.SCHEDULE))
    }
}
