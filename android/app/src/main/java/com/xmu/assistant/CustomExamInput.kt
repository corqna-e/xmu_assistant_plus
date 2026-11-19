package com.xmu.assistant

import java.time.LocalDate

/**
 *自定义考试录入的纯逻辑（年份推断、节次选择、时间格式）。
 *
 * 单独成文件：不依赖 Android/Compose，可用独立 Kotlin 编译器直接跑断言验证。
 *
 *节次的作息时间不再在本文件自带估算值（原 08:00-08:50 等与厦大官方表不符），
 * 改由 [officialSectionWindows]（厦大教务处公布的作息时间表）提供。
 */

/** 一日内的分钟数 → "HH:mm"。 */
internal fun formatMinuteOfDay(minute: Int): String {
    val clamped = minute.coerceIn(0, 24 * 60 - 1)
    return "%02d:%02d".format(clamped / 60, clamped % 60)
}

/**
 *节次选择改为「点哪个块就选哪个块、可多选」，不再有起始/结束、先点后点的顺序概念。
 *
 * 选中结果是一组节次编号（1-11，升序去重）。每个块独立：
 * - 点一个未选中的块 → 加入选中集合；
 * - 再点已选中的块 → 取消选中（可反复切换）；
 * - 空集合 = 未指定节次，并入课表时回退按时间重叠推断。
 */
internal fun toggleSection(selected: Set<Int>, tapped: Int): Set<Int> =
    if (tapped !in 1..11) selected
    else if (tapped in selected) selected - tapped
    else (selected + tapped).sorted().toSet()

/**
 * 由选中的节次集合推算「起止时间」：起点取所选块中最早的开始时刻，
 * 终点取所选块中最晚的结束时刻。即「选中什么块就什么块的时间」。
 *
 * 只依赖真实作息窗口 [windows]，不做任何按课程时长的均分；
 * 选中集合为空或所有块的窗口都缺失时返回 null（由调用方保留手填时间）。
 */
internal fun sectionsTimeWindow(selected: Set<Int>, windows: Map<Int, IntRange>): Pair<Int, Int>? {
    val valid = selected.filter { it in windows }
    if (valid.isEmpty()) return null
    val start = valid.minOf { windows.getValue(it).first }
    val end = valid.maxOf { windows.getValue(it).last }
    return start to end
}

/** 选中节次的展示文案：升序、顿号分隔，如「1、3、5」。 */
internal fun formatSections(selected: Set<Int>): String =
    selected.filter { it in 1..11 }.sorted().joinToString("、")

/**
 * 学期能唯一确定年份时按学期给年份：
 * - 第一学期（9 月起）：9-12 月算起始年，1-2 月算次年；
 * - 第二学期（2 月起）：2-7 月算次年。
 * 学期说不准的月份返回 null，交由 [inferExamYear] 的兜底规则处理。
 */
private fun termYearFor(month: Int, termCode: String): Int? {
    val match = Regex("""^(\d{4})-(\d{4})-(\d)$""").find(termCode) ?: return null
    val startYear = match.groupValues[1].toIntOrNull() ?: return null
    return when (match.groupValues[3]) {
        "1" -> when (month) {
            in 9..12 -> startYear
            in 1..2 -> startYear + 1
            else -> null
        }
        "2" -> when (month) {
            in 2..7 -> startYear + 1
            else -> null
        }
        else -> null
    }
}

/**
 *年份自动推断：
 * - 学期能定位时按学期给年份；
 * - 否则按「最近的将来」推断：今年该月日若已过去 45 天以上，则算到明年。
 */
internal fun inferExamYear(month: Int, day: Int, termCode: String, today: LocalDate): Int {
    termYearFor(month, termCode)?.let { return it }
    val sameYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
        ?: return today.year
    return if (sameYear.isBefore(today.minusDays(45))) today.year + 1 else today.year
}

/** 组装 yyyy-MM-dd。 */
internal fun formatExamDate(year: Int, month: Int, day: Int): String =
    "%04d-%02d-%02d".format(year, month, day)

/** 从 "yyyy-MM-dd" 取月份（取不到返回 null）。 */
internal fun parseExamMonth(date: String): Int? =
    date.split('-').getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 1..12 }

/** 从 "yyyy-MM-dd" 取日（取不到返回 null）。 */
internal fun parseExamDay(date: String): Int? =
    date.split('-').getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 1..31 }

/** 从 "yyyy-MM-dd" 取年份（取不到返回 null）。 */
internal fun parseExamYear(date: String): Int? =
    date.split('-').getOrNull(0)?.trim()?.toIntOrNull()
