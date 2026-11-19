package com.xmu.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ThemeUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `all four bottom tabs are visible and selectable on a narrow phone`() {
        composeRule.setContent {
            XmuMobileTheme(THEME_MODE_LIGHT) {
                var selected by remember { mutableStateOf(Page.HOME) }
                AppNavigationBar(selected, NotificationSettings(), 0, onSelected = { selected = it })
            }
        }
        // v17：成绩已从底栏移入「更多」，底栏只剩四键。
        listOf("首页", "课表", "签到", "更多").forEach { title ->
            composeRule.onNodeWithText(title).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithText(title).performClick().assertIsSelected()
        }
    }

    @Test fun `secondary pages highlight more in dark theme and expose hub on click`() {
        var destination: Page? = null
        composeRule.setContent {
            XmuMobileTheme(THEME_MODE_DARK) {
                AppNavigationBar(Page.COURSEWARE, NotificationSettings(), 3, onSelected = { destination = it })
            }
        }
        composeRule.onNodeWithText("更多").assertIsSelected().performClick()
        composeRule.runOnIdle { assertEquals(Page.MORE, destination) }
        composeRule.onNodeWithText("更多").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 个文件下载中"),
        )
        composeRule.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    @Test fun `all secondary destinations remain reachable including notification setup`() {
        var selected: Page? = null
        composeRule.setContent {
            XmuMobileTheme(THEME_MODE_LIGHT) {
                MorePage(NotificationSettings(pushPlusEnabled = true), 0) { selected = it }
            }
        }
        composeRule.onNodeWithText("有通知渠道待配置").assertIsDisplayed()
        // 排除两类：开发中入口不响应点击；课程坐标是「设置」下的三级界面，不在更多页展示。
        moreDestinations
            .filter { it.statusLabel.isEmpty() && it.page != Page.COURSE_LOCATION }
            .forEach {
            composeRule.onNodeWithText(it.label).performClick()
            composeRule.runOnIdle { assertEquals(it.page, selected) }
        }
    }
}
