package com.xmu.assistant

import java.time.LocalDate

/**
 * 考试 → 课表的换算。
 *
 * 考试与课程表的坐标不同：考试给的是「日期 + 时间段」（如 2026-06-14 08:00-10:00），
 * 课表给的是「星期 + 节次」。这里做一次换算，把落在同一周内的考试变成课表条目，
 * 让周课表与日程视图都能直接看到考试。
 *
 * 节次换算依据本学期真实课程的时间：一门课跨 n 节时按节数均分时长，
 * 由此得到「节次 → 时间窗口」对照表，再按时间重叠判定考试占用哪几节。
 * 判不出（时间与任何节次都不重叠）时取时间最接近的一节，至少让考试出现在表上。
 */

/** 解析时间段 "HH:mm-HH:mm" → 一日内的起止分钟；格式错误返回 null。 */
internal fun examTimeRangeMinutes(timeRange: String): Pair<Int, Int>? {
    val parts = timeRange.split("-").map { it.trim() }
    if (parts.size != 2) return null
    val start = parseMinuteOfDay(parts[0]) ?: return null
    val end = parseMinuteOfDay(parts[1]) ?: return null
    if (end <= start) return null
    return start to end
}

/** 解析 "HH:mm" → 一日内的分钟数；兼容 "HHmm" 与 "HH:mm:ss"。 */
internal fun parseMinuteOfDay(value: String): Int? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val parts = text.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** 一日内的分钟数 → 课表用的 HHMM 整数（如 8:30 → 830）。 */
internal fun minutesToHhmm(minutes: Int): Int {
    val clamped = minutes.coerceIn(0, 24 * 60 - 1)
    return (clamped / 60) * 100 + (clamped % 60)
}

/** 考试时间落在哪几节：与节次时间窗有重叠即占用；都不重叠时取时间最接近的一节。 */
internal fun sectionsForExam(startMinute: Int, endMinute: Int, windows: Map<Int, IntRange>): IntRange? {
    if (windows.isEmpty()) return null
    val hit = windows.filter { (_, window) -> window.first < endMinute && window.last > startMinute }
        .keys
        .sorted()
    if (hit.isNotEmpty()) return hit.first()..hit.last()
    val nearest = windows.minByOrNull { (_, window) ->
        minOf(kotlin.math.abs(window.first - startMinute), kotlin.math.abs(window.last - endMinute))
    }?.key
    return nearest?.let { it..it }
}

/** 参加换算的一条考试（抓取项与自定义项统一成同一形态）。 */
private data class MergeExam(
    val date: LocalDate,
    val courseName: String,
    val startMinute: Int,
    val endMinute: Int,
    val room: String,
    /** 用户手动添加（与教务抓取项区分）。 */
    val custom: Boolean = false,
    /**
     *用户点选的节次集合（1-11）。非空时考试按这些节落位，
     * 不再按时间重叠推断；显示的时间仍用真实时间（startMinute/endMinute）。
     */
    val sections: Set<Int> = emptySet(),
)

/**
 *把选中的离散节次切成若干连续段。例如 {1,3,4,6} → [1..1, 3..4, 6..6]，
 * 每段在课表上渲染成一块，从而「选中哪些块就只占哪些块」。
 */
internal fun sectionRuns(sections: Set<Int>): List<IntRange> {
    val sorted = sections.filter { it in 1..11 }.sorted()
    if (sorted.isEmpty()) return emptyList()
    val runs = mutableListOf<IntRange>()
    var start = sorted.first()
    var end = start
    for (section in sorted.drop(1)) {
        if (section == end + 1) {
            end = section
        } else {
            runs.add(start..end)
            start = section
            end = section
        }
    }
    runs.add(start..end)
    return runs
}

/**
 * 把落在 [weekStart] 那一周内的考试换算成课表条目。
 *
 * @param weekStart 该周周一；为空（开学日未校准）时无法定位日期，返回空列表。
 * @param entries 本学期全部课程，用于推导节次时间窗。
 */
internal fun mergeExamsIntoWeek(
    weekStart: LocalDate?,
    entries: List<XmuScheduleEntry>,
    exams: List<XmuExam>,
    customExams: List<CustomExam>,
): List<XmuScheduleGroup> {
    val start = weekStart ?: return emptyList()
    val weekDates = (0L..6L).map { start.plusDays(it) }
    // 节次时间窗改用「厦大官方作息时间表 + 本学期单节课程校正」，
    // 不再按课程时长均分（均分会把 08:00-09:40 的 1-2 节切成 08:00-08:50 / 08:50-09:40，与真实作息不符）。
    val windows = resolveSectionWindows(entries)

    val candidates = buildList {
        exams.forEach { exam ->
            val date = runCatching { LocalDate.parse(exam.date) }.getOrNull() ?: return@forEach
            val minutes = examTimeRangeMinutes(exam.timeRange) ?: return@forEach
            add(
                MergeExam(
                    date = date,
                    courseName = exam.courseName.ifBlank { "考试" },
                    startMinute = minutes.first,
                    endMinute = minutes.second,
                    room = exam.room,
                )
            )
        }
        customExams.forEach { exam ->
            val date = runCatching { LocalDate.parse(exam.date) }.getOrNull() ?: return@forEach
            val minutes = examTimeRangeMinutes(exam.timeRange)
            // 用户点选了节次 → 按所选节次落位；没选节次（或时间段非法）才回退到时间重叠推断。
            val explicitSections = exam.sections.filter { it in 1..11 }.toSet()
            if (minutes == null && explicitSections.isEmpty()) return@forEach
            add(
                MergeExam(
                    date = date,
                    courseName = exam.courseName.ifBlank { "考试" },
                    startMinute = minutes?.first ?: 0,
                    endMinute = minutes?.second ?: 0,
                    room = exam.room,
                    custom = true,
                    sections = explicitSections,
                )
            )
        }
    }.filter { it.date in weekDates }

    return candidates.flatMap { exam ->
        // 落位节次段：用户点选的节次按连续段拆成多块（选 1、3 节就在第 1、3 行各出一块）；
        // 没选节次则按时间重叠推断（都判不出时取最接近的一节）。
        val runs = when {
            exam.sections.isNotEmpty() -> sectionRuns(exam.sections)
            exam.startMinute > 0 -> sectionsForExam(exam.startMinute, exam.endMinute, windows)
                ?.let { listOf(it) }
                ?: emptyList()
            else -> emptyList()
        }
        runs.mapNotNull { sections ->
            // 显示时间用真实时间：用户填的起止时刻；没填（时间段非法）才退回该节次段的时间窗。
            val startMinute = exam.startMinute.takeIf { it > 0 }
                ?: windows[sections.first]?.first
                ?: return@mapNotNull null
            val endMinute = exam.endMinute.takeIf { it > 0 }
                ?: windows[sections.last]?.last
                ?: startMinute
            XmuScheduleGroup(
                weekday = exam.date.dayOfWeek.value,
                startSection = sections.first,
                endSection = sections.last,
                startTime = minutesToHhmm(startMinute),
                endTime = minutesToHhmm(endMinute),
                courseName = exam.courseName,
                rooms = listOf(exam.room).filter(String::isNotBlank),
                teachers = emptyList(),
                // 周次位在课表里用于显示「周次未标注」，考试没有周次概念，
                // 这里直接写「考试」，日程视图里就显示「考试」而不是误导性的周次文本。
                // 自定义项再缀「（自定义）」，与教务抓取的考试区分。
                weeks = if (exam.custom) "考试（自定义）" else "考试",
                isExam = true,
            )
        }
    }
}
