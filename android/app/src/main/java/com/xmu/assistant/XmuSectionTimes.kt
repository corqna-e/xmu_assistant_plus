package com.xmu.assistant

/**
 * 厦门大学节次作息时间。
 *
 * 官方作息时间表（厦大教务处《作息时间》，各学院公布的《厦门大学作息时间表》一致）：
 * - 上午：第1节 8:00-8:45、第2节 8:55-9:40、第3节 10:10-10:55、第4节 11:05-11:50；
 * - 下午：第5节 14:30-15:15、第6节 15:25-16:10、第7节 16:40-17:25、第8节 17:35-18:20；
 * - 晚上：第9节 19:10-19:55、第10节 20:05-20:50、第11节 21:00-21:45。
 *
 * 之前 用的默认作息（08:00-08:50 等）是估的，与官方表不符，本文件更正。
 */

/**
 * 官方作息时间表：节次 → 一日内的分钟窗口（起, 止）。
 *
 * 只构造一次：调课/搬课每次重算课程时间都要查表（见 scheduleSectionStartTime），
 * 每次调用新建 Map 纯属浪费。
 */
private val OFFICIAL_SECTION_WINDOWS: Map<Int, IntRange> = mapOf(
    1 to (480..525),     // 08:00-08:45
    2 to (535..580),     // 08:55-09:40
    3 to (610..655),     // 10:10-10:55
    4 to (665..710),     // 11:05-11:50
    5 to (870..915),     // 14:30-15:15
    6 to (925..970),     // 15:25-16:10
    7 to (1000..1045),   // 16:40-17:25
    8 to (1055..1100),   // 17:35-18:20
    9 to (1150..1195),   // 19:10-19:55
    10 to (1205..1250),  // 20:05-20:50
    11 to (1260..1305),  // 21:00-21:45
)

/** 官方作息时间表（只读，调用方要改请自行 toMutableMap）。 */
internal fun officialSectionWindows(): Map<Int, IntRange> = OFFICIAL_SECTION_WINDOWS

/** 官方表与实测值允许的最大偏差（分钟）：超过则认为是特殊排课，不采信实测值。 */
private const val SECTION_TIME_TOLERANCE_MINUTES = 60

/**
 * 实际使用的节次时间窗：官方作息时间表打底，用本学期课表里的「单节课程」校正。
 *
 * 为什么不用「按课程时长均分」：一门 1-2 节的课 08:00-09:40 均分成两节会得到
 * 08:00-08:50 / 08:50-09:40，与真实的 08:00-08:45 / 08:55-09:40 都不符。
 * 单节课程（startSection == endSection）的时间就是该节课的真实起止，可直接校正。
 *
 * 校正时取该节次出现次数最多的那组时间（少数特殊排课不会带偏整节），
 * 且与官方表相差超过 1 小时的实测值不采信。
 *
 * @return 完整 1-11 节的时间窗。
 */
internal fun resolveSectionWindows(entries: List<XmuScheduleEntry>): Map<Int, IntRange> {
    val windows = officialSectionWindows().toMutableMap()
    // 节次 → 各组实测时间的出现次数
    val counters = mutableMapOf<Int, MutableMap<Pair<Int, Int>, Int>>()
    entries.forEach { entry ->
        val section = entry.startSection
        if (section != entry.endSection || section !in 1..11) return@forEach
        val start = entry.startTime / 100 * 60 + entry.startTime % 100
        val end = entry.endTime / 100 * 60 + entry.endTime % 100
        if (end <= start) return@forEach
        val bucket = counters.getOrPut(section) { mutableMapOf() }
        bucket[start to end] = (bucket[start to end] ?: 0) + 1
    }
    counters.forEach { (section, bucket) ->
        val best = bucket.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, Int>> { it.value }.thenBy { it.key.first })
            .first()
            .key
        val official = windows[section] ?: return@forEach
        // 与官方表相差过大 → 视为特殊排课，保留官方时间
        if (kotlin.math.abs(best.first - official.first) > SECTION_TIME_TOLERANCE_MINUTES) return@forEach
        if (kotlin.math.abs(best.second - official.last) > SECTION_TIME_TOLERANCE_MINUTES) return@forEach
        windows[section] = best.first..best.second
    }
    return windows
}
