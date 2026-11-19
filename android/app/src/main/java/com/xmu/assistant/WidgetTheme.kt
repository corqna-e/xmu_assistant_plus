package com.xmu.assistant

import android.content.Context
import android.content.res.Configuration

/**
 *桌面小组件的主题解析 —— 与 App 内「外观」设置保持一致，而不是只看系统。
 *
 * RemoteViews 由桌面（SystemUI）进程 inflate：App 的 Compose 主题管不到它，
 * `values-night` 也只认**系统** uiMode。用户在 App 里强制浅色/深色时，
 * 原来那套资源限定符就会和 App 反着来。因此这里把 App 主题解析成一组固定颜色，
 * 构建 RemoteViews 时逐个控件显式写进去（颜色资源放在 values/，不带 night 限定符）。
 *
 * 加密设置在 Direct Boot 等尚未解锁的场景可能读不到，统一降级为「跟随系统」，
 * 绝不因为取主题失败让桌面上出现空卡片。
 */
internal fun widgetDark(context: Context): Boolean {
    val mode = runCatching { AssistantSettings(context).themeMode }
        .getOrDefault(THEME_MODE_SYSTEM)
    return when (mode) {
        THEME_MODE_LIGHT -> false
        THEME_MODE_DARK -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
}

/** 一张卡片需要的全部主题资源：颜色是 ARGB，资源项是 drawable id。 */
internal data class WidgetPalette(
    val accent: Int,
    val primary: Int,
    val secondary: Int,
    val tomorrow: Int,
    val stroke: Int,
    val onChip: Int,
    val cardBackground: Int,
    val todayChip: Int,
    val blockFills: IntArray,
    /**组件内课程详情覆盖层里那张卡片的底（圆角实色，与桌面卡片区分开）。 */
    val detailCard: Int,
)

internal fun widgetPalette(context: Context, dark: Boolean = widgetDark(context)): WidgetPalette =
    if (dark) {
        WidgetPalette(
            accent = context.getColor(R.color.widget_dark_accent),
            primary = context.getColor(R.color.widget_dark_primary),
            secondary = context.getColor(R.color.widget_dark_secondary),
            tomorrow = context.getColor(R.color.widget_dark_tomorrow),
            stroke = context.getColor(R.color.widget_dark_stroke),
            onChip = context.getColor(R.color.widget_dark_on_chip),
            cardBackground = R.drawable.schedule_widget_bg_dark,
            todayChip = R.drawable.week_today_chip_dark,
            blockFills = intArrayOf(
                R.drawable.week_block_fill_dark_0, R.drawable.week_block_fill_dark_1,
                R.drawable.week_block_fill_dark_2, R.drawable.week_block_fill_dark_3,
                R.drawable.week_block_fill_dark_4, R.drawable.week_block_fill_dark_5,
            ),
            detailCard = R.drawable.widget_detail_card_bg_dark,
        )
    } else {
        WidgetPalette(
            accent = context.getColor(R.color.widget_light_accent),
            primary = context.getColor(R.color.widget_light_primary),
            secondary = context.getColor(R.color.widget_light_secondary),
            tomorrow = context.getColor(R.color.widget_light_tomorrow),
            stroke = context.getColor(R.color.widget_light_stroke),
            onChip = context.getColor(R.color.widget_light_on_chip),
            cardBackground = R.drawable.schedule_widget_bg_light,
            todayChip = R.drawable.week_today_chip_light,
            blockFills = intArrayOf(
                R.drawable.week_block_fill_0, R.drawable.week_block_fill_1,
                R.drawable.week_block_fill_2, R.drawable.week_block_fill_3,
                R.drawable.week_block_fill_4, R.drawable.week_block_fill_5,
            ),
            detailCard = R.drawable.widget_detail_card_bg,
        )
    }
