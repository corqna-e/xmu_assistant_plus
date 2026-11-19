package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷设置磁贴（MonitorControlTileService）源码契约：
 * - manifest 必须以 BIND_QUICK_SETTINGS_TILE 权限 + QS_TILE action 声明（否则系统不绑定）；
 * - 启停必须与 App 内开关同链路（monitorDesired + RollcallMonitorService 启停），
 *   状态判定必须复用 shouldStartMonitorWorker 防口径分裂；
 * - App 内启动/暂停必须 requestResync 磁贴。
 */
class MonitorControlTileServiceSourceContractTest {

    private fun source(relativePath: String): String {
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)
        return requireNotNull(sourceFile) { "$relativePath was not found from ${File(".").absolutePath}" }
            .readText()
    }

    @Test
    fun `manifest declares the tile service with binding permission and action`() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue("tile service must be declared", "android:name=\".MonitorControlTileService\"" in manifest)
        assertTrue(
            "tile must require BIND_QUICK_SETTINGS_TILE",
            "android.permission.BIND_QUICK_SETTINGS_TILE" in manifest,
        )
        assertTrue("tile must filter QS_TILE action", "android.service.quicksettings.action.QS_TILE" in manifest)
    }
}
