package com.xmu.assistant

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 *「某一周该显示哪些课」的解析回调：输入教务原始分组，输出已套用本地调整后的分组。
 *
 * 本地调课（`ScheduleOverrideStore`）、跨周搬课（`ScheduleWeekMoveStore`）都只改本机数据，
 * 不经过教务接口，因此组件投影必须显式问一次调用方 —— 否则桌面上的课表永远是教务原始版本。
 * 传 null 表示不套用任何本地调整（纯投影，测试与旧调用点沿用）。
 */
internal typealias ScheduleWeekGroupResolver = (groups: List<XmuScheduleGroup>, week: Int) -> List<XmuScheduleGroup>

/** 按周缓存解析结果：整学期投影会对同一周反复取数据，避免重复读盘。 */
private class WeekResolverCache(private val resolve: ScheduleWeekGroupResolver) {
    private val resolved = HashMap<Int, List<XmuScheduleGroup>>()

    fun groupsOf(
        index: Map<Int, List<XmuScheduleEntry>>,
        week: Int,
    ): List<XmuScheduleGroup> = resolved.getOrPut(week) {
        // groupForDisplay() 先做同课同时段的合并，再交给调用方套本地调整 ——
        // 调课的锚点是「课程 + 原始时段」，合并后仍能对上。
        val raw = (index[week] ?: emptyList()).groupForDisplay()
        resolve(raw, week)
    }
}

/** Precompute two weeks of public summaries; the widget can roll over without credentials or network. */
internal fun projectScheduleWidgetDays(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int = 0,
    today: LocalDate = LocalDate.now(),
    resolveWeek: ScheduleWeekGroupResolver? = null,
): List<ScheduleWidgetSnapshot> {
    val totalWeeks = calendar?.totalWeeks ?: 19
    val index = indexXmuScheduleByWeek(entries)
    val cache = resolveWeek?.let { WeekResolverCache(it) }
    // 色号按整学期课表算一次，两个星期的快照共用同一份 —— 与 App 内课表同一张表。
    val colorMap = scheduleColorMapForEntries(entries, SCHEDULE_COLOR_PALETTE.size)
    val currentMonday = today.minusDays(today.dayOfWeek.value - 1L)
    return (0L until 14L).map { offset ->
        val date = today.plusDays(offset)
        val week = if (manualWeek in 1..totalWeeks) {
            val monday = date.minusDays(date.dayOfWeek.value - 1L)
            (manualWeek + ChronoUnit.WEEKS.between(currentMonday, monday).toInt()).takeIf { it in 1..totalWeeks }
        } else xmuAcademicWeekFor(calendar, date).week
        val weekday = xmuWeekdayFrom(date)
        val weekGroups = if (cache != null && week != null) cache.groupsOf(index, week)
        else (index[week] ?: emptyList()).filter { it.weekday == weekday }.groupForDisplay()
        val courses = weekGroups.filter { it.weekday == weekday }.map { group ->
            ScheduleWidgetCourse(group.courseName, group.startTime, group.endTime,
                group.startSection, group.endSection, scheduleLocationSummary(group), group.isAdjusted,
                weekday = weekday, week = week ?: 0, weeks = group.weeks,
                teachers = group.teachers.joinToString("、"),
                colorIndex = colorMap[group.courseName] ?: -1)
        }
        ScheduleWidgetSnapshot(weekday, week ?: 0, termCode, courses, date.toEpochDay())
    }
}

/**
 *把整周课表投影成「周课表」小组件的快照。
 *
 * 逻辑与 App 内周课表保持一致：
 * - 当前周次优先用手动校准周（manualWeek），否则按学期日历推算；
 * - 同一天同一时段的多条记录用 groupForDisplay() 合并（同课程、同时间才合并）；
 * - 教室文本走 scheduleLocationSummary()，与日程 / 明日课程 / 详情页同源。
 * 七列固定为周一到周日，无课的日子给空列表，让渲染端按列宽均分。
 */
internal fun projectScheduleWeekWidget(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int = 0,
    today: LocalDate = LocalDate.now(),
    resolveWeek: ScheduleWeekGroupResolver? = null,
): ScheduleWeekWidgetSnapshot {
    val totalWeeks = calendar?.totalWeeks ?: 19
    val currentWeek = if (manualWeek in 1..totalWeeks) manualWeek else xmuAcademicWeekFor(calendar, today).week
    val cache = resolveWeek?.let { WeekResolverCache(it) }
    val colorMap = scheduleColorMapForEntries(entries, SCHEDULE_COLOR_PALETTE.size)
    return projectWeekAt(entries, termCode, calendar, manualWeek, totalWeeks, currentWeek ?: 0, today, cache, colorMap)
}

/**把整学期每一周都投影成快照，供桌面周课表左右翻周。 */
internal fun projectScheduleWeekWidgetBundle(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int = 0,
    today: LocalDate = LocalDate.now(),
    resolveWeek: ScheduleWeekGroupResolver? = null,
): List<ScheduleWeekWidgetSnapshot> {
    val totalWeeks = calendar?.totalWeeks ?: 19
    // 整学期一次投影会对同一周反复取数据，解析结果按周缓存，避免重复读盘解析。
    val cache = resolveWeek?.let { WeekResolverCache(it) }
    // 色号表也只需算一次：十九周共用同一份，同课跨周不换色。
    val colorMap = scheduleColorMapForEntries(entries, SCHEDULE_COLOR_PALETTE.size)
    return (1..totalWeeks).map { week ->
        projectWeekAt(entries, termCode, calendar, manualWeek, totalWeeks, week, today, cache, colorMap)
    }
}

private fun projectWeekAt(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int,
    totalWeeks: Int,
    week: Int,
    today: LocalDate,
    cache: WeekResolverCache? = null,
    colorMap: Map<String, Int> = emptyMap(),
): ScheduleWeekWidgetSnapshot {
    val index = indexXmuScheduleByWeek(entries)
    // 快照的 monday 用「学期第一周周一 + (week-1) 周」，跨周判断据此进行。
    val firstMonday = calendar?.startDate?.minusDays(calendar.startDate.dayOfWeek.value - 1L)
        ?: today.minusDays(today.dayOfWeek.value - 1L).minusWeeks((if (manualWeek in 1..totalWeeks) manualWeek else (xmuAcademicWeekFor(calendar, today).week ?: 1)) - 1L)
    val monday = firstMonday.plusWeeks((week - 1).toLong())
    // 先按周解析本地调整（调课 / 搬课 / 单周删除），再按天切分 ——
    // 剔除与补入都是「周」级动作，先切天会把搬来的课漏掉、把搬走的课留下。
    val weekGroups = cache?.groupsOf(index, week)
        ?: (index[week] ?: emptyList()).groupForDisplay()
    val days = (1..7).map { weekday ->
        weekGroups
            .filter { it.weekday == weekday }
            .map { group ->
                ScheduleWidgetCourse(
                    courseName = group.courseName,
                    startTime = group.startTime,
                    endTime = group.endTime,
                    startSection = group.startSection,
                    endSection = group.endSection,
                    location = scheduleLocationSummary(group),
                    isAdjusted = group.isAdjusted,
                    weekday = weekday,
                    week = week,
                    weeks = group.weeks,
                    teachers = group.teachers.joinToString("、"),
                    colorIndex = colorMap[group.courseName] ?: -1,
                )
            }
    }
    return ScheduleWeekWidgetSnapshot(
        termCode = termCode,
        week = week,
        mondayEpochDay = monday.toEpochDay(),
        generatedEpochDay = today.toEpochDay(),
        days = days,
    )
}
