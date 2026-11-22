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

/**
 * 上一次真正画进桌面的深浅色。
 *
 * 组件颜色是逐控件写死的，系统切深色时桌面只会重新 inflate 布局、不会重跑我们这段构建，
 * 所以必须在 App 进程里自己察觉并重投。记下「已渲染的深浅色」用来判重：
 * 字体大小、语言、旋转同样会发 CONFIGURATION_CHANGED，颜色没变就不该白重画一遍。
 * 与组件快照共用同一个偏好文件，方便随组件数据一起管理。
 */
internal object WidgetThemeState {
    private const val PREFS = "schedule_week_widget"
    private const val KEY_DARK = "rendered_dark"

    /** null 表示本机还没渲染过（首次安装 / 清过数据）。 */
    fun renderedDark(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DARK)) return null
        return prefs.getBoolean(KEY_DARK, false)
    }

    fun setRenderedDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DARK, dark).apply()
    }
}

/**
 * 按当前主题重画两个桌面组件（只重绘，不重算数据）。
 *
 * 深浅色变化时用它可以立刻换肤：组件快照里的课程数据一个字节都不用动。
 */
internal fun redrawScheduleWidgetsForTheme(context: Context) {
    val appContext = context.applicationContext
    WidgetThemeState.setRenderedDark(appContext, widgetDark(appContext))
    ScheduleWidgetProvider.refreshAll(appContext)
    ScheduleWeekWidgetProvider.refreshAll(appContext)
}

/** 深浅色实际变了才重画；同色（字体大小 / 语言 / 旋转）直接返回。 */
internal fun refreshWidgetsIfThemeChanged(context: Context) {
    val appContext = context.applicationContext
    val dark = widgetDark(appContext)
    if (WidgetThemeState.renderedDark(appContext) == dark) return
    redrawScheduleWidgetsForTheme(appContext)
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
    /**考试块底色与色条：考试不按课名散列配色，用固定的暗红，与 App 内日程的考试块同一套。 */
    val examBlockFill: Int,
    val examBlockBar: Int,
    /**考试文字与竖条的颜色（暗红），与 App 内考试块一致。 */
    val examInk: Int,
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
            examBlockFill = R.drawable.week_block_fill_exam_dark,
            examBlockBar = R.drawable.week_block_bar_exam_dark,
            examInk = context.getColor(R.color.widget_dark_exam_ink),
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
            examBlockFill = R.drawable.week_block_fill_exam,
            examBlockBar = R.drawable.week_block_bar_exam,
            examInk = context.getColor(R.color.widget_light_exam_ink),
        )
    }
