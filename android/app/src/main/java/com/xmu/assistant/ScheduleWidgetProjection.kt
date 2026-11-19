package com.xmu.assistant

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Precompute two weeks of public summaries; the widget can roll over without credentials or network. */
internal fun projectScheduleWidgetDays(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int = 0,
    today: LocalDate = LocalDate.now(),
): List<ScheduleWidgetSnapshot> {
    val totalWeeks = calendar?.totalWeeks ?: 19
    val index = indexXmuScheduleByWeek(entries)
    val currentMonday = today.minusDays(today.dayOfWeek.value - 1L)
    return (0L until 14L).map { offset ->
        val date = today.plusDays(offset)
        val week = if (manualWeek in 1..totalWeeks) {
            val monday = date.minusDays(date.dayOfWeek.value - 1L)
            (manualWeek + ChronoUnit.WEEKS.between(currentMonday, monday).toInt()).takeIf { it in 1..totalWeeks }
        } else xmuAcademicWeekFor(calendar, date).week
        val weekday = xmuWeekdayFrom(date)
        val courses = (index[week] ?: emptyList()).filter { it.weekday == weekday }.groupForDisplay().map { group ->
            ScheduleWidgetCourse(group.courseName, group.startTime, group.endTime,
                group.startSection, group.endSection, scheduleLocationSummary(group), group.isAdjusted,
                weekday = weekday, week = week ?: 0, weeks = group.weeks,
                teachers = group.teachers.joinToString("、"))
        }
        ScheduleWidgetSnapshot(weekday, week ?: 0, termCode, courses, date.toEpochDay())
    }
}

/**
 *把整周课表投影成「周课表」小组件的快照。
 *
 * 逻辑与 App 内周课表保持一致：
 * - 当前周次优先用手动校准周（manualWeek），否则按学期日历推算；
 *- 同一天同一时段的多条记录用 groupForDisplay 合并（同课程、同时间才合并）；
 *- 教室文本走 scheduleLocationSummary，与日程 / 明日课程 / 详情页同源。
 * 七列固定为周一到周日，无课的日子给空列表，让渲染端按列宽均分。
 */
internal fun projectScheduleWeekWidget(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int = 0,
    today: LocalDate = LocalDate.now(),
): ScheduleWeekWidgetSnapshot {
    val totalWeeks = calendar?.totalWeeks ?: 19
    val currentWeek = if (manualWeek in 1..totalWeeks) manualWeek else xmuAcademicWeekFor(calendar, today).week
    return projectWeekAt(entries, termCode, calendar, manualWeek, totalWeeks, currentWeek ?: 0, today)
}

/**把整学期每一周都投影成快照，供桌面周课表左右翻周。 */
internal fun projectScheduleWeekWidgetBundle(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int = 0,
    today: LocalDate = LocalDate.now(),
): List<ScheduleWeekWidgetSnapshot> {
    val totalWeeks = calendar?.totalWeeks ?: 19
    return (1..totalWeeks).map { week ->
        projectWeekAt(entries, termCode, calendar, manualWeek, totalWeeks, week, today)
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
): ScheduleWeekWidgetSnapshot {
    val index = indexXmuScheduleByWeek(entries)
    // 快照的 monday 用「学期第一周周一 + (week-1) 周」，跨周判断据此进行。
    val firstMonday = calendar?.startDate?.minusDays(calendar.startDate.dayOfWeek.value - 1L)
        ?: today.minusDays(today.dayOfWeek.value - 1L).minusWeeks((if (manualWeek in 1..totalWeeks) manualWeek else (xmuAcademicWeekFor(calendar, today).week ?: 1)) - 1L)
    val monday = firstMonday.plusWeeks((week - 1).toLong())
    val days = (1..7).map { weekday ->
        (index[week] ?: emptyList())
            .filter { it.weekday == weekday }
            .groupForDisplay()
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
