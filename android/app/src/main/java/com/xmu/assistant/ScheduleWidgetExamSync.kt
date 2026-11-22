package com.xmu.assistant

import android.content.Context
import java.time.LocalDate

/**
 * 把考试（教务抓取 + 自定义）并入桌面小组件的日程快照。
 *
 * 组件数据是「课表投影」的结果，投影只认课表条目，考试因此一直进不了卡片；
 * App 内周课表/日程早已用 [mergeExamsIntoWeek] 并入考试，桌面却只有课程。
 * 这里在渲染入口对快照补一次考试：快照本身保持纯课程，考试按「当天日期所在周」换算，
 * 与 App 内同一套节次、时间、教室换算。只按日期取当天，不做跨日兜底。
 */
internal fun ScheduleWidgetSnapshot.withExams(
    exams: List<XmuExam>,
    customExams: List<CustomExam>,
): ScheduleWidgetSnapshot {
    if (exams.isEmpty() && customExams.isEmpty()) return this
    if (savedEpochDay <= 0L) return this
    val date = LocalDate.ofEpochDay(savedEpochDay)
    val weekday = xmuWeekdayFrom(date)
    val weekStart = date.minusDays(date.dayOfWeek.value - 1L)
    // 节次时间窗传空课表：组件侧拿不到本学期课程明细，退回厦大官方作息表。
    // 自定义考试若点选了节次则完全不看时间窗；教务考试按官方表换算与 App 内相差在容差内。
    val groups = mergeExamsIntoWeek(weekStart, emptyList(), exams, customExams)
        .filter { it.weekday == weekday }
    if (groups.isEmpty()) return this
    val examCourses = groups.map { group ->
        ScheduleWidgetCourse(
            courseName = group.courseName,
            startTime = group.startTime,
            endTime = group.endTime,
            startSection = group.startSection,
            endSection = group.endSection,
            location = scheduleLocationSummary(group),
            weekday = weekday,
            week = week,
            weeks = group.weeks,
            isExam = true,
        )
    }
    return copy(
        courses = (courses + examCourses)
            .sortedWith(compareBy({ it.startTime }, { it.startSection })),
    )
}

/** 组件渲染入口用：读本机缓存的两类考试；读盘失败按「没有考试」处理，不影响原有卡片。 */
internal fun loadExamsForWidget(context: Context): Pair<List<XmuExam>, List<CustomExam>> {
    val exams = runCatching { ExamCache.loadAllExams(context) }.getOrDefault(emptyList())
    val customExams = runCatching { CustomExamStore.load(context) }.getOrDefault(emptyList())
    return exams to customExams
}

/**
 * 把考试并入「周课表」组件的一周快照。
 *
 * 快照按天存课程列表，考试按 [ScheduleWeekWidgetSnapshot.mondayEpochDay] 那一周换算后
 * 追加到对应那一天，与 App 内周课表同一套节次/时间/教室换算。
 * 只补渲染用的一份副本，落盘快照保持纯课程。
 */
internal fun ScheduleWeekWidgetSnapshot.withExams(
    exams: List<XmuExam>,
    customExams: List<CustomExam>,
): ScheduleWeekWidgetSnapshot {
    if (exams.isEmpty() && customExams.isEmpty()) return this
    val anchor = runCatching { LocalDate.ofEpochDay(mondayEpochDay) }.getOrNull() ?: return this
    val weekStart = anchor.minusDays(anchor.dayOfWeek.value - 1L)
    val groups = mergeExamsIntoWeek(weekStart, emptyList(), exams, customExams)
    if (groups.isEmpty()) return this
    val mergedDays = days.map { it.toMutableList() }
    groups.forEach { group ->
        val index = group.weekday - 1
        if (index !in mergedDays.indices) return@forEach
        mergedDays[index] += ScheduleWidgetCourse(
            courseName = group.courseName,
            startTime = group.startTime,
            endTime = group.endTime,
            startSection = group.startSection,
            endSection = group.endSection,
            location = scheduleLocationSummary(group),
            weekday = group.weekday,
            week = week,
            weeks = group.weeks,
            isExam = true,
        )
    }
    return copy(days = mergedDays.map { it.toList() })
}
