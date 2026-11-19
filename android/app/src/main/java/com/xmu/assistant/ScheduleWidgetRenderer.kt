package com.xmu.assistant

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import android.widget.FrameLayout
import java.time.LocalDate
import java.time.LocalTime

internal data class WidgetCoursePlan(val courses: List<ScheduleWidgetCourse>, val hiddenCount: Int)

internal fun widgetCoursePlan(courses: List<ScheduleWidgetCourse>, nowTime: Int, availableHeight: Int, rowHeight: (ScheduleWidgetCourse) -> Int): WidgetCoursePlan {
    val upcoming = courses.filter { it.endTime > nowTime }.sortedWith(compareBy({ it.startTime }, { it.startSection }))
    var usedHeight = 0
    val visible = upcoming.takeWhile { course ->
        usedHeight += rowHeight(course)
        usedHeight <= availableHeight
    }
    return WidgetCoursePlan(visible, upcoming.size - visible.size)
}

/** Pure rendering inputs make size, stale-day and long-location behavior testable without account data. */
internal fun buildScheduleWidgetViews(
    context: Context,
    snapshot: ScheduleWidgetSnapshot?,
    enabled: Boolean,
    heightDp: Int,
    today: LocalDate = LocalDate.now(),
    now: LocalTime = LocalTime.now(),
    widthDp: Int = 250,
    /**明日快照。今天的课全上完后用它做半透明的「明日课程」预告。 */
    tomorrow: ScheduleWidgetSnapshot? = null,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.schedule_widget)
    // 颜色按 App 主题显式写入，不再依赖 values-night（那只看系统 uiMode）。
    val palette = widgetPalette(context)
    views.setInt(R.id.widget_root, "setBackgroundResource", palette.cardBackground)
    views.setTextColor(R.id.widget_title, palette.accent)
    views.setTextColor(R.id.widget_week, palette.secondary)
    views.setTextColor(R.id.widget_empty, palette.secondary)
    views.setTextColor(R.id.widget_tomorrow_title, palette.secondary)
    views.setInt(R.id.widget_tomorrow_divider, "setBackgroundColor", palette.stroke)
    val compact = heightDp < 96
    val displayDensity = context.resources.displayMetrics.density
    views.setViewVisibility(R.id.widget_header, if (compact) View.GONE else View.VISIBLE)
    views.setViewPadding(R.id.widget_content, (12 * displayDensity).toInt(), ((if (compact) 4 else 12) * displayDensity).toInt(),
        (12 * displayDensity).toInt(), ((if (compact) 4 else 12) * displayDensity).toInt())
    // 「今日课程」组件不再提供「点击查看明细」——课程行、明日课程行、按钮区与
    // 卡片空白处都不挂任何点击意图，点击不产生任何动作。
    // 详情覆盖层只剩「周课表」组件在用，本布局里的 detail_overlay 已删除。
    views.setTextViewText(R.id.widget_title, "今日课程")
    views.setTextViewText(R.id.widget_week, "${today.monthValue}/${today.dayOfMonth} 周${xmuWeekdayShort(today.dayOfWeek.value)}")
    views.setViewVisibility(R.id.widget_tomorrow_box, View.GONE)
    views.removeAllViews(R.id.widget_courses)

    fun empty(text: String): RemoteViews {
        views.setViewVisibility(R.id.widget_courses, View.GONE)
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        views.setTextViewText(R.id.widget_empty, text)
        return views
    }
    if (!enabled) return empty("小卡片已关闭\n在「更多 → 通知与外观」中开启")
    if (snapshot == null) return empty("课表还未同步\n打开课表，刷新后即可查看")
    // Validate the date before the empty-course branch: yesterday's free day is not today's free day.
    if (snapshot.savedEpochDay != today.toEpochDay()) return empty("今日课表待更新\n点击打开课表刷新")
    if (snapshot.week in 1..30) views.setTextViewText(R.id.widget_week, "周${xmuWeekdayShort(today.dayOfWeek.value)} · 第${snapshot.week}周")

    val nowTime = now.hour * 100 + now.minute
    val todayCourses = snapshot.courses
    val allDone = todayCourses.isNotEmpty() && todayCourses.none { it.endTime > nowTime }

    // 底部固定显示明日课程预告，与今日课程用分隔线和次要色区分。
    // 不再把整张卡切成明日课程。
    // 明日课程改成「每节课一行」，节次放在课程名前面；行数按剩余空间决定。
    val next = tomorrow?.takeIf { it.courses.isNotEmpty() && it.savedEpochDay == today.plusDays(1).toEpochDay() }
    val tomorrowCourses = next?.courses?.sortedBy { it.startTime }.orEmpty()

    /**每行前缩进两个中文字符宽，末尾附完整地点；地点不做任何截断。
     *课名与地点之间拉开间距；文字颜色在布局里单独降低不透明度。 */
    fun tomorrowLine(course: ScheduleWidgetCourse, ellipsis: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.schedule_widget_tomorrow_line).apply {
            val location = course.location.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                .distinct().joinToString(" / ").ifBlank { "教室待确认" }
            // 课名与完整地点之间留出约两个中文字符宽的间距，避免挤在一起。
            val line = "\u3000\u3000${course.startSection}-${course.endSection}节 " +
                (if (course.isAdjusted) "[调]" else "") + course.courseName +
                "    $location" + (if (ellipsis) " …" else "")
            setTextViewText(R.id.tomorrow_line_text, line)
            setTextColor(R.id.tomorrow_line_text, palette.tomorrow)
        }

    /** 明日区显示策略：>0 显示前 [shown] 行（末行放不下用省略号）；
     *0 表示整块隐藏；-1 表示只显示标题（空间够分隔线+标题、但不够任何一行）。 */
    fun applyTomorrowLines(shown: Int) {
        if (tomorrowCourses.isEmpty() || shown == 0) {
            views.setViewVisibility(R.id.widget_tomorrow_box, View.GONE)
            return
        }
        views.setViewVisibility(R.id.widget_tomorrow_box, View.VISIBLE)
        views.removeAllViews(R.id.widget_tomorrow_lines)
        // -1：只保留分隔线与标题，一行都不塞，避免整块消失。
        if (shown < 0) return
        val count = shown.coerceAtMost(tomorrowCourses.size)
        repeat(count) { index ->
            views.addView(
                R.id.widget_tomorrow_lines,
                tomorrowLine(tomorrowCourses[index], ellipsis = index == count - 1 && count < tomorrowCourses.size),
            )
        }
    }

    if (todayCourses.isEmpty()) {
        // 今天没课：整块空间都归明日，最多列 6 行。
        applyTomorrowLines(tomorrowCourses.size.coerceAtMost(6))
        return empty("今天没有课\n留些时间，安排自己的计划")
    }
    // 今日课已全部结束：今日区给出提示，底部明日预告仍然保留。
    if (allDone) {
        applyTomorrowLines(tomorrowCourses.size.coerceAtMost(6))
        return empty("今日没有课程啦")
    }

    views.setViewVisibility(R.id.widget_courses, View.VISIBLE)
    views.setViewVisibility(R.id.widget_empty, View.GONE)
    val density = context.resources.displayMetrics.density
    val parent = FrameLayout(context)
    val wide = widthDp >= 340 && context.resources.configuration.fontScale <= 1.15f
    val template = views.apply(context, parent)
    template.measure(
        View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY),
    )
    val container = template.findViewById<View>(R.id.widget_courses)
    // widget_courses 改为 wrap_content 后不再自带可用高度，这里由内容区实测扣掉内边距与标题得出，
    // 等价于旧版本 weight=1 时分给课程区的高度。
    val contentView = template.findViewById<View>(R.id.widget_content) as android.widget.LinearLayout
    val headerView = template.findViewById<View>(R.id.widget_header)
    val headerH = if (headerView.visibility == View.GONE) 0 else headerView.measuredHeight
    val availContent = (contentView.measuredHeight - contentView.paddingTop - contentView.paddingBottom - headerH).coerceAtLeast(0)
    val rows = mutableMapOf<ScheduleWidgetCourse, RemoteViews>()
    val heights = mutableMapOf<ScheduleWidgetCourse, Int>()
    val upcoming = todayCourses.filter { it.endTime > nowTime }
    upcoming.forEach { course ->
        val row = RemoteViews(context.packageName, if (wide) R.layout.schedule_widget_row_wide else R.layout.schedule_widget_row)
        if (wide && heightDp < 96) {
            row.setViewPadding(R.id.widget_row, 0, (2 * density).toInt(), 0, (2 * density).toInt())
            row.setInt(R.id.widget_row, "setMinimumHeight", (26 * density).toInt())
        }
        row.setTextViewText(R.id.row_time, if (wide) "${formatXmuTime(course.startTime)}–${formatXmuTime(course.endTime)}" else formatXmuTime(course.startTime))
        if (!wide) row.setTextViewText(R.id.row_end_time, formatXmuTime(course.endTime))
        row.setTextViewText(R.id.row_course, if (course.isAdjusted) "[调]${course.courseName}" else course.courseName)
        val location = course.location.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }.distinct().joinToString(" / ")
            .ifBlank { "教室待确认" }
        row.setTextViewText(R.id.row_location, location)
        row.setTextColor(R.id.row_time, palette.accent)
        row.setTextColor(R.id.row_course, palette.primary)
        row.setTextColor(R.id.row_location, palette.secondary)
        if (!wide) row.setTextColor(R.id.row_end_time, palette.secondary)
        row.setContentDescription(R.id.row_course, "${if (course.isAdjusted) "[调]" else ""}${course.courseName}，${formatXmuTime(course.startTime)}至${formatXmuTime(course.endTime)}，$location")
        // 课程行不再挂点击（原来是弹组件内详情覆盖层），点课程没有任何动作。
        val view = row.apply(context, parent)
        view.measure(View.MeasureSpec.makeMeasureSpec(container.measuredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        rows[course] = row
        heights[course] = view.measuredHeight
    }
    // 明日课程跟在今日最后一门课后：今日课程优先占满，明日只用今日排完后的余量；
    // 余量放不下「固定开销 + 至少一行」时整块隐藏，不再挤占今日课程，也没有任何溢出提示。
    var lineH = 0
    var chromeH = 0
    if (tomorrowCourses.isNotEmpty()) {
        applyTomorrowLines(tomorrowCourses.size)
        val probe = views.apply(context, parent)
        probe.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        val boxView = probe.findViewById<View>(R.id.widget_tomorrow_box)
        val linesView = probe.findViewById<android.widget.LinearLayout>(R.id.widget_tomorrow_lines)
        val lineHeights = (0 until linesView.childCount).map { linesView.getChildAt(it).measuredHeight }
        lineH = lineHeights.maxOrNull() ?: 0
        // 明日区固定开销：分隔线 + 标题 + box 上外边距（去掉各行后的高度）。
        val boxMarginTop = (boxView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        chromeH = (boxView.measuredHeight - lineHeights.sum()).coerceAtLeast(0) + boxMarginTop
    }
    val plan = widgetCoursePlan(todayCourses, nowTime, availContent) { heights.getValue(it) }
    if (plan.courses.isEmpty()) {
        applyTomorrowLines(0)
        return empty("空间不足，拉高查看课程")
    }
    val remain = availContent - plan.courses.sumOf { heights.getValue(it) }
    // 能放几行放几行，不因无法全量显示而整块隐藏；一行都放不下时退回只显示标题。
    val shown = if (lineH > 0) ((remain - chromeH) / lineH).coerceAtMost(tomorrowCourses.size) else -1
    applyTomorrowLines(shown)
    plan.courses.forEach { views.addView(R.id.widget_courses, rows.getValue(it)) }
    return views
}
