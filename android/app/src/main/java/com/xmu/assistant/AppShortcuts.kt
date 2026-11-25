package com.xmu.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * 长按桌面图标的「自动签到」动态快捷方式。
 *
 * 为什么不写进 res/xml/shortcuts.xml：这一条的文案要随开关在
 * 「开启自动签到 / 关闭自动签到」之间切换，静态 XML 只能写死一句。
 * 系统把动态快捷方式排在静态之前，因此长按菜单里它固定是第 1 项，
 * 后三条（下载课件 / 课表 / 签到情况）仍由静态 XML 提供。
 *
 * 开启判定与 App 内首页「自动签到」开关同一口径（数字或雷达任一开启即算开启），
 * 否则会出现「App 里显示已开、长按菜单写着开启」的分裂。
 */
internal object AppShortcuts {
    /** 快捷方式带过来的开关请求；MainScreen 消费后按当前状态取反。 */
    const val EXTRA_TOGGLE_AUTO = "toggle_auto"
    private const val TOGGLE_ID = "toggle_auto_rollcall"

    /** 重下发一次动态快捷方式（文案、顺序）。失败静默：桌面不支持时只是少这一项。 */
    fun update(context: Context) {
        val manager = runCatching { context.getSystemService(ShortcutManager::class.java) }
            .getOrNull() ?: return
        val settings = runCatching { AssistantSettings(context) }.getOrNull() ?: return
        val rollcall = settings.rollcall()
        val enabled = rollcall.autoAnswerNumber || rollcall.autoAnswerRadar
        val shortcut = ShortcutInfo.Builder(context, TOGGLE_ID)
            .setShortLabel(
                context.getString(
                    if (enabled) R.string.shortcut_auto_disable_short else R.string.shortcut_auto_enable_short,
                ),
            )
            .setLongLabel(
                context.getString(
                    if (enabled) R.string.shortcut_auto_disable_long else R.string.shortcut_auto_enable_long,
                ),
            )
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_auto))
            .setRank(0)
            .setIntent(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    putExtra(EXTRA_TOGGLE_AUTO, true)
                    // 落「首页」而不是沿用上次停留的页：这条快捷方式只是开关，
                    // 开关生效与提示都在首页卡片上，落在签到页会造成「点了却看不懂发生了什么」。
                    // 复用既有 open_page 深链（deepLinkSeq 消费）切页，无需另开路由。
                    putExtra("open_page", Page.HOME.id)
                    // MainActivity 是 singleTask：已有任务时复用，不叠第二个界面
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
            )
            .build()
        runCatching { manager.setDynamicShortcuts(listOf(shortcut)) }
    }
}
