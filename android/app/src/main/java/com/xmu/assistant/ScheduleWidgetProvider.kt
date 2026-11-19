package com.xmu.assistant

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews

/** Widget renders only the public course summary; each size gets a bounded set of rows. */
class ScheduleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateWidgets(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        updateWidgets(context, manager, intArrayOf(id))
    }

    // 「今日课程」组件不再有「点击查看明细」——课程行、明日课程行与空白处都不挂
    // 任何点击意图，因此本组件不再需要处理广播（原来只用于展开 / 收起详情覆盖层，
    // 以及详情卡里的「打开课表」中转）。onReceive 一并删除，组件内不存在任何响应手势的区域。
    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateWidgets(context, manager, ids)
        }

        private fun updateWidgets(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val today = java.time.LocalDate.now()
            val snapshot = ScheduleWidgetData.load(context, today)
            // 今日课程结束后要改显示明日课程，这里把明天的摘要一并取出。
            val tomorrow = ScheduleWidgetData.loadDay(context, today.plusDays(1).toEpochDay())
            // 总开关已删除，桌面小卡片恒可用（历史镜像里残留的 false 不再生效）。
            val enabled = true
            // Load the summary once, then reuse RemoteViews for widgets with the same dimensions.
            val viewsBySize = mutableMapOf<Pair<Int, Int>, RemoteViews>()
            // 不再有组件内详情覆盖层，渲染时不传 detail。
            fun render(width: Int, height: Int): RemoteViews = viewsBySize.getOrPut(width to height) {
                runCatching {
                    buildScheduleWidgetViews(
                        context, snapshot, enabled, height,
                        widthDp = width, tomorrow = tomorrow,
                    )
                }
                    .getOrElse {
                        buildScheduleWidgetViews(
                            context, null, enabled, height,
                            widthDp = width,
                        )
                    }
            }
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160).coerceAtLeast(1)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(1)
                @Suppress("DEPRECATION")
                val sizes = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) else null
                val validSizes = sizes.orEmpty().filter { it.width > 0 && it.height > 0 }.distinct().take(8)
                val views = if (Build.VERSION.SDK_INT >= 31 && validSizes.isNotEmpty()) {
                    RemoteViews(validSizes.associateWith { render(it.width.toInt().coerceAtLeast(1), it.height.toInt().coerceAtLeast(1)) })
                } else {
                    val portraitHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height).coerceAtLeast(height)
                    val landscapeWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width).coerceAtLeast(width)
                    RemoteViews(render(landscapeWidth, height), render(width, portraitHeight))
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
