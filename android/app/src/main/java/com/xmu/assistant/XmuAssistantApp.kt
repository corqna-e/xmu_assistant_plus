package com.xmu.assistant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * 进程入口，目前只做一件事：**让桌面组件跟上系统深浅色**。
 *
 * 组件里的颜色是逐控件写进 RemoteViews 的（见 [widgetDark]），系统切深色时
 * 桌面只会重新 inflate 布局、不会重跑我们的构建代码，于是整张卡片要等到
 * 下一次联网同步或用户点翻周才换色 —— 这是「跟随系统颜色变换较慢」的根因。
 *
 * `CONFIGURATION_CHANGED` 自 Android 8 起不收清单静态注册（隐式广播限制），
 * 只能在进程里动态注册。因此进程活着时切深色是即时的；进程被系统回收后，
 * 下一次打开 App（[MainActivity] 启动时再补一次判断）或点组件上的刷新键即可更新。
 */
class XmuAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerConfigurationWatcher()
    }

    private fun registerConfigurationWatcher() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_CONFIGURATION_CHANGED) return
                // 只关心深浅色：字体大小、语言、旋转同样发这条广播，颜色没变就别重画。
                runCatching { refreshWidgetsIfThemeChanged(context) }
            }
        }
        runCatching {
            val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 系统广播不受 NOT_EXPORTED 限制，仍需显式声明，避免 API 33+ 抛异常。
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }
    }
}
