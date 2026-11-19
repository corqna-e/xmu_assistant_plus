package com.xmu.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机/应用更新后恢复监控：仅在用户此前主动开启过监控（monitorDesired）且
 * 登录态仍在时重启前台服务。任何异常（系统限制启动前台服务等）一律静默降级，
 * 等用户打开 App 后由既有恢复流程接管，绝不崩溃。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // 装新包后系统不保证会给两个 AppWidgetProvider 补发 APPWIDGET_UPDATE，
        // 桌面可能一直挂着旧版本的 RemoteViews（表现为代码改了、桌面上却没变）。
        // 这里主动重建一次两张卡片，更新后立刻用上新布局。
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { ScheduleWidgetProvider.refreshAll(context) }
            runCatching { ScheduleWeekWidgetProvider.refreshAll(context) }
        }
        runCatching {
            val settings = AssistantSettings(context)
            if (settings.monitorDesired && settings.cookieHeader.isNotBlank()) {
                context.startForegroundService(Intent(context, RollcallMonitorService::class.java))
                Log.i("BootReceiver", "已按既有开关恢复签到监控")
            }
        }.onFailure {
            Log.w("BootReceiver", "开机恢复监控未执行（降级放行）：${it.message}")
        }
    }
}
