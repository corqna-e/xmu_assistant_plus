package com.xmu.assistant

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 会话清理与登录态镜像的行为测试。
 *
 * 取代原 AssistantSettingsSourceContractTest —— 那个版本靠 substring 切片断言源码文本，
 * 重命名一个局部变量就红，却抓不到真实回归。这里直接构造 AssistantSettings 跑真逻辑。
 *
 * 守住的三条语义：
 * 1. 登出只清会话缓存，绝不删学号/密码/自动登录策略；
 * 2. 登出把 Widget worker 的登录镜像置 false（否则后台 worker 会以残留凭据幽灵登录）；
 * 3. 登录镜像只写明文镜像，不再双写加密 prefs（两次 apply 之间有进程被杀的分叉窗口）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantSettingsSessionTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun settings(name: String): Pair<AssistantSettings, android.content.SharedPreferences> {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return AssistantSettings.forTest(context, prefs) to prefs
    }

    @Test
    fun `clearSession removes session cache but preserves credentials and policy`() {
        val (settings, prefs) = settings("session-clear")
        settings.username = "synthetic-user"
        settings.password = "synthetic-password"
        settings.cookieHeader = "session=abc"
        settings.scoreCookieHeader = "academic=def"
        settings.monitorDesired = true
        settings.recordMonitorFailure("boom")

        settings.clearSession()

        assertNull("session cookie must be dropped", prefs.getString("cookie_header", null))
        assertNull("score cookie must be dropped", prefs.getString("score_cookie_header", null))
        assertFalse("monitor_desired must be dropped", prefs.contains("monitor_desired"))
        assertFalse("monitor error must be dropped", prefs.contains("monitor_last_error"))
        assertEquals("username must survive logout", "synthetic-user", settings.username)
        assertEquals("password must survive logout", "synthetic-password", settings.password)
    }

    @Test
    fun `clearSession drops the widget worker logged-in mirror`() {
        val (settings, _) = settings("session-mirror-logout")
        settings.markLoginSucceeded()
        assertEquals(true, AssistantSettings.readWidgetLoggedInMirror(context))

        settings.clearSession()

        assertEquals(
            "logged-out session must block the widget worker from using残留 credentials",
            false,
            AssistantSettings.readWidgetLoggedInMirror(context),
        )
    }

    @Test
    fun `markLoginSucceeded enables auto login and writes the mirror only in plain prefs`() {
        val (settings, prefs) = settings("session-mirror-login")
        settings.autoLoginPolicy = AutoLoginPolicy.USER_LOGGED_OUT

        settings.markLoginSucceeded()

        assertEquals(AutoLoginPolicy.ENABLED, settings.autoLoginPolicy)
        assertEquals(true, AssistantSettings.readWidgetLoggedInMirror(context))
        assertFalse(
            "v112: the mirror must not be duplicated into the encrypted prefs",
            prefs.contains("widget_worker_logged_in"),
        )
    }

    @Test
    fun `explicit logout records the policy without touching credentials`() {
        val (settings, prefs) = settings("session-policy")
        settings.username = "synthetic-user"
        settings.password = "synthetic-password"

        settings.markUserLoggedOut()

        assertEquals(AutoLoginPolicy.USER_LOGGED_OUT, settings.autoLoginPolicy)
        assertTrue("logout must not remove the username", prefs.contains("username"))
        assertTrue("logout must not remove the password", prefs.contains("password"))
    }
}
