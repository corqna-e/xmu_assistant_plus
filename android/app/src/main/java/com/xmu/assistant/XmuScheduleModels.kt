package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject

data class XmuScheduleEntry(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: Int,
    val endTime: Int,
    val courseName: String,
    val room: String,
    val teacher: String,
    val weeks: String,
    val termCode: String,
)

data class XmuScheduleGroup(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: Int,
    val endTime: Int,
    val courseName: String,
    val rooms: List<String>,
    val teachers: List<String>,
    val weeks: String,
    val variants: List<XmuScheduleVariant> = emptyList(),
    /** 该条目是考试而不是课程（考试由 ExamScheduleMerge 并入课表）。 */
    val isExam: Boolean = false,
    /**该条目被本地「调课」改过（星期/节次来自本地调整），显示时加「[调]」前缀。 */
    val isAdjusted: Boolean = false,
)

data class XmuScheduleVariant(
    val room: String,
    val teacher: String,
    /** 该教学班对应的周次（如 "1-8周"），保留「周次→教室→教师」完整映射。 */
    val weeks: String = "",
)

fun List<XmuScheduleEntry>.groupForDisplay(): List<XmuScheduleGroup> =
    groupBy {
        listOf(
            it.weekday,
            it.startSection,
            it.endSection,
            it.startTime,
            it.endTime,
            it.courseName,
        )
    }
        .values
        .map { entries ->
            val first = entries.first()
            XmuScheduleGroup(
                weekday = first.weekday,
                startSection = first.startSection,
                endSection = first.endSection,
                startTime = first.startTime,
                endTime = first.endTime,
                courseName = first.courseName,
                rooms = entries.map { it.room }.filter(String::isNotBlank).distinct(),
                teachers = entries.map { it.teacher }.filter(String::isNotBlank).distinct(),
                // 同一课程同一时间可能有多条周次互补的记录（如
                // "1-2,7,9-12周" + "3-6,8,13-14周"），合并展示为一段周次文本。
                weeks = entries
                    .map { it.weeks }
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString("；"),
                variants = entries
                    .map { XmuScheduleVariant(room = it.room, teacher = it.teacher, weeks = it.weeks) }
                    .distinct(),
            )
        }
        .sortedWith(
            compareBy<XmuScheduleGroup> { it.weekday }
                .thenBy { it.startSection }
                .thenBy { it.courseName },
        )

internal fun parseXmuScheduleEntries(body: String, termCode: String): List<XmuScheduleEntry> {
    val root = xmuJsonRoot(body)
    if (!root.optBoolean("success", false)) {
        error(root.optRealString("msg").ifBlank { "课表接口返回失败" })
    }
    val rows = root.optJSONArray("pkjgList") ?: JSONArray()
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        XmuScheduleEntry(
            weekday = row.weekdayValue("XQ"),
            startSection = row.intValue("KSJCDM"),
            endSection = row.intValue("JSJCDM"),
            startTime = row.timeValue("KSSJ"),
            endTime = row.timeValue("JSSJ"),
            courseName = row.firstNonBlank("KCMC", "KCM", "courseName"),
            room = row.firstNonBlank("JASMC", "JAS", "room"),
            teacher = row.firstNonBlank("JSXM", "JS", "teacher"),
            weeks = row.firstNonBlank("ZCMC", "ZC", "SKZC", "weeks"),
            termCode = termCode,
        )
    }
}

private fun JSONObject.intValue(key: String): Int {
    val value = opt(key)
    return when (value) {
        is Number -> value.toInt()
        else -> value?.toString()?.trim()?.toIntOrNull() ?: 0
    }
}

private fun JSONObject.weekdayValue(key: String): Int {
    val raw = opt(key)?.toString()?.trim().orEmpty()
    raw.toIntOrNull()?.let { return it }
    return when {
        raw.contains("一") -> 1
        raw.contains("二") -> 2
        raw.contains("三") -> 3
        raw.contains("四") -> 4
        raw.contains("五") -> 5
        raw.contains("六") -> 6
        raw.contains("日") || raw.contains("天") -> 7
        else -> 0
    }
}

private fun JSONObject.timeValue(key: String): Int {
    val raw = opt(key)?.toString()?.trim().orEmpty()
    if (raw.contains(':')) {
        val parts = raw.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        if (hour != null && minute != null) return hour * 100 + minute
    }
    return raw.toIntOrNull() ?: 0
}

private fun JSONObject.firstNonBlank(vararg keys: String): String =
    keys.asSequence()
        .map { optRealString(it).trim() }
        .firstOrNull(String::isNotBlank)
        .orEmpty()

internal fun parseXmuTermCodes(body: String): List<String> {
    val root = xmuJsonRoot(body)
    val rows = root.optJSONObject("datas")
        ?.optJSONObject("kfdxnxqcx")
        ?.optJSONArray("rows")
        ?: JSONArray()
    return (0 until rows.length()).mapNotNull { index ->
        rows.optJSONObject(index)?.optRealString("XNXQDM")?.takeIf(String::isNotBlank)
    }
}

/** 带响应源可能前置 BOM（XmuScoreAutoQueryClient 先例实证）：解析前统一剥离。 */
internal fun xmuJsonRoot(body: String): JSONObject = JSONObject(body.removePrefix("\uFEFF"))

fun formatXmuTime(value: Int): String {
    // 畸形时间（如 870 表示 8:70、负数）按 ICS 导出侧 formatXmuTimeOrNull 同款
    // 校验拒绝，返回空串而非展示 "08:70"
    val hour = value / 100
    val minute = value % 100
    if (hour !in 0..23 || minute !in 0..59) return ""
    return "%02d:%02d".format(hour, minute)
}

/**
 * 自动监控时段自查：当前时间是否在今天任一课程的 [开始-30 分钟, 结束+30 分钟] 窗口内。
 *
 *修正：
 * - 旧实现把「今天没有课」当作放行（返回 true），于是周末、没课的日子全天都在轮询；
 * - 判定用的课表数据取自 `scheduleCacheJson`（旧版加密 prefs），而课表现在存在文件缓存里，
 *那个字段恒为空 → 直接短路放行，开关等于没有。
 * 现在：只有"完全没有课表数据"才放行（否则监控会彻底停摆），其余严格按当天课程判断；
 * 当天没课一律待机。周次不参与判定：假期多开的窗只会多待机检查，不会漏检。
 */
internal fun autoMonitorWindowActive(
    snapshot: XmuScheduleSnapshot,
    weekday: Int,
    nowMinuteOfDay: Int,
): Boolean {
    if (snapshot.entries.isEmpty()) return true
    val today = snapshot.entries.filter { it.weekday == weekday }
    // 今天没课：这一整天都不需要检查签到。
    if (today.isEmpty()) return false
    return today.any { entry ->
        val start = xmuMinuteOfDay(entry.startTime)
        val end = xmuMinuteOfDay(entry.endTime)
        // 时间字段异常时无法判断，宁可多查不漏查。
        if (start == null || end == null || end < start) return@any true
        nowMinuteOfDay in (start - 30)..(end + 30)
    }
}

/** 兼容旧调用/测试的 JSON 入口：解析失败或空串视为「无课表数据」，放行。 */
internal fun autoMonitorWindowActive(scheduleJson: String, weekday: Int, nowMinuteOfDay: Int): Boolean {
    if (scheduleJson.isBlank()) return true
    val snapshot = runCatching { xmuScheduleSnapshotFromJson(scheduleJson) }.getOrNull()
        ?: return true
    return autoMonitorWindowActive(snapshot, weekday, nowMinuteOfDay)
}

private fun xmuMinuteOfDay(hhmm: Int): Int? {
    val hour = hhmm / 100
    val minute = hhmm % 100
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
