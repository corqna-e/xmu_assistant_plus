package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ScheduleCourseOverride(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val room: String,
    val teacher: String,
    val weeks: String,
    /**
     * 生效周次：null 表示该课程整学期所有周次（详情页「修改安排」与旧数据的语义），
     *具体数字表示只调整这一周的那一次课（「调课」按周生效）。
     */
    val scopeWeek: Int? = null,
    /**
     *该周次这次课被用户删除（例如某周停课）。
     * 只对 [scopeWeek] 这一周生效，其它周次仍按教务安排显示；还原时按锚点整条清掉。
     */
    val deleted: Boolean = false,
    /**
     *这条调整是否真的把这门课挪了时段（只有「调课」弹窗写入 true）。
     *
     * 详情页「修改安排」只改教室 / 教师 / 周次，写 false —— 课表里不加「[调]」前缀。
     * `null` 表示旧数据没有这个字段：按锚点几何判断（星期 / 起止节次相对原始安排有没有变），
     * 这样升级前用「调课」挪过时段的课程仍保留标记，只改过教室的课程则不再误标。
     */
    val moved: Boolean? = null,
)

/**
 * Local corrections to a course arrangement, kept separate from the official timetable cache.
 *
 *同一门课可以同时存在「整学期」与「某一周」两类调整，按周解析时具体周优先。
 */
internal class ScheduleOverrideStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 取该课程在 [week] 这一周的最终安排：具体周调整优先于整学期调整；
     * [week] 传 null 时只认整学期调整（调用方不掌握周次的场景）。
     */
    @Synchronized
    fun apply(termCode: String, group: XmuScheduleGroup, week: Int? = null): XmuScheduleGroup {
        // 标记删除的记录不参与改期，交给 isWeekCancelled 在列表层剔除。
        val matched = readAll().filter { it.matches(termCode, group, week) && !it.value.deleted }
        val override = matched.firstOrNull { it.scopeWeek != null }?.value
            ?: matched.firstOrNull()?.value
            ?: return group
        return applyScheduleOverride(group, override)
    }

    /** 该课程是否存在任意本地调整（含单周调整），用于详情页显示「已修改」。 */
    @Synchronized
    fun has(termCode: String, group: XmuScheduleGroup): Boolean =
        readAll().any { it.matchesAnchor(termCode, group) }

    @Synchronized
    fun save(termCode: String, original: XmuScheduleGroup, value: ScheduleCourseOverride) {
        require(value.weekday in 1..7) { "星期必须在 1—7 之间" }
        require(value.startSection in 1..11) { "开始节次必须在 1—11 之间" }
        require(value.endSection in value.startSection..11) { "结束节次不能早于开始节次" }
        require(value.scopeWeek == null || value.scopeWeek in 1..30) { "周次必须在 1—30 之间" }
        val all = readAll().toMutableList()
        // 只覆盖同一作用域的旧记录：整学期调整与单周调整互不抹掉。
        all.removeAll { it.matchesAnchor(termCode, original) && it.scopeWeek == value.scopeWeek }
        all += StoredScheduleOverride(
            termCode = termCode,
            courseName = original.courseName,
            originalWeekday = original.weekday,
            originalStartSection = original.startSection,
            originalEndSection = original.endSection,
            value = value.copy(
                room = value.room.trim(),
                teacher = value.teacher.trim(),
                weeks = value.weeks.trim(),
            ),
        )
        writeAll(all)
    }

    /** 还原为教务安排：删除该课程的全部本地调整（整学期与单周一并清掉）。 */
    @Synchronized
    fun remove(termCode: String, original: XmuScheduleGroup): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeAll { it.matchesAnchor(termCode, original) }
        if (removed) writeAll(all)
        return removed
    }

    /**
     *把这门课在 [week] 这一周的这次课标记为删除（例如某周停课），其它周次不受影响。
     * 走与调课同一套单周覆盖，因此「课程详情 → 还原」能一并清掉。
     */
    @Synchronized
    fun cancelWeek(termCode: String, original: XmuScheduleGroup, week: Int) {
        save(
            termCode = termCode,
            original = original,
            value = ScheduleCourseOverride(
                weekday = original.weekday,
                startSection = original.startSection,
                endSection = original.endSection,
                room = original.rooms.firstOrNull().orEmpty(),
                teacher = original.teachers.firstOrNull().orEmpty(),
                weeks = original.weeks,
                scopeWeek = week,
                deleted = true,
            ),
        )
    }

    /**该课程在 [week] 这一周是否已被标记删除。 */
    @Synchronized
    fun isWeekCancelled(termCode: String, group: XmuScheduleGroup, week: Int): Boolean =
        readAll().any { it.matches(termCode, group, week) && it.value.deleted }

    private fun readAll(): List<StoredScheduleOverride> {
        val raw = prefs.getString(KEY_OVERRIDES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val rows = JSONArray(raw)
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val value = row.optJSONObject("value") ?: continue
                    val weekday = value.optInt("weekday")
                    val startSection = value.optInt("startSection")
                    val endSection = value.optInt("endSection")
                    if (weekday !in 1..7 || startSection !in 1..11 || endSection !in startSection..11) continue
                    // 旧数据没有 scopeWeek 字段 → 0 → 视为整学期调整，语义与升级前一致。
                    val scopeWeek = value.optInt("scopeWeek", 0).takeIf { it in 1..30 }
                    add(
                        StoredScheduleOverride(
                            termCode = row.optString("termCode"),
                            courseName = row.optString("courseName"),
                            originalWeekday = row.optInt("originalWeekday"),
                            originalStartSection = row.optInt("originalStartSection"),
                            originalEndSection = row.optInt("originalEndSection"),
                            value = ScheduleCourseOverride(
                                weekday = weekday,
                                startSection = startSection,
                                endSection = endSection,
                                room = value.optString("room"),
                                teacher = value.optString("teacher"),
                                weeks = value.optString("weeks"),
                                scopeWeek = scopeWeek,
                                deleted = value.optBoolean("deleted", false),
                                // 旧数据无此字段 → null，交给 applyScheduleOverride 按锚点判断。
                                moved = if (value.has("moved")) value.optBoolean("moved") else null,
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(items: List<StoredScheduleOverride>) {
        val rows = JSONArray()
        items.forEach { item ->
            val value = JSONObject()
                .put("weekday", item.value.weekday)
                .put("startSection", item.value.startSection)
                .put("endSection", item.value.endSection)
                .put("room", item.value.room)
                .put("teacher", item.value.teacher)
                .put("weeks", item.value.weeks)
                .put("scopeWeek", item.value.scopeWeek ?: 0)
                .put("deleted", item.value.deleted)
            // moved 为 null 表示「旧数据，未记录来源」——不写这个字段，
            // 下次读回来仍是 null，继续按锚点几何判断，不会被一次无关写入抹掉。
            item.value.moved?.let { value.put("moved", it) }
            rows.put(
                JSONObject()
                    .put("termCode", item.termCode)
                    .put("courseName", item.courseName)
                    .put("originalWeekday", item.originalWeekday)
                    .put("originalStartSection", item.originalStartSection)
                    .put("originalEndSection", item.originalEndSection)
                    .put("value", value),
            )
        }
        prefs.edit().putString(KEY_OVERRIDES, rows.toString()).apply()
    }

    private data class StoredScheduleOverride(
        val termCode: String,
        val courseName: String,
        val originalWeekday: Int,
        val originalStartSection: Int,
        val originalEndSection: Int,
        val value: ScheduleCourseOverride,
    ) {
        val scopeWeek: Int? get() = value.scopeWeek

        /** 锚点匹配（不看周次）：课程 + 原始上课时段一致，即同一条「课程安排」。 */
        fun matchesAnchor(term: String, group: XmuScheduleGroup): Boolean =
            termCode == term &&
                normalizeCourseTitle(courseName) == normalizeCourseTitle(group.courseName) &&
                originalWeekday == group.weekday &&
                originalStartSection == group.startSection &&
                originalEndSection == group.endSection

        /** 锚点匹配 + 周次作用域命中：整学期调整对任意周生效，单周调整只对该周生效。 */
        fun matches(term: String, group: XmuScheduleGroup, week: Int?): Boolean =
            matchesAnchor(term, group) && (scopeWeek == null || scopeWeek == week)
    }

    private companion object {
        const val PREFS_NAME = "xmu_schedule_overrides"
        const val KEY_OVERRIDES = "course_arrangements_v1"
    }
}

/**
 *跨周搬课记录 —— 把某门课在 [fromWeek] 的那一次课整体搬到 [toWeek]。
 *
 * 与 [ScheduleCourseOverride] 分开存：那条只改「同一周内的星期与节次」，
 * 这条改的是「这一次课出现在哪一周」，因此显示层要两头处理：
 * 源周把这一次课剔除，目标周把这一次课补进来。
 */
internal data class ScheduleWeekMove(
    val fromWeek: Int,
    val toWeek: Int,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val room: String = "",
    val teacher: String = "",
    val weeks: String = "",
)

/**
 *「调课」弹窗里「调换周次 → 目标周次」的跨周搬课存储。
 *
 * 锚点与覆盖一致（课程名 + 原始星期 / 起止节次），按 [ScheduleWeekMove.fromWeek] 唯一，
 * 改同一周的去向就是覆盖旧记录。搬走的课在原周不再显示，搬到的那一周补一条「[调]」课。
 */
internal class ScheduleWeekMoveStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(termCode: String, original: XmuScheduleGroup, move: ScheduleWeekMove) {
        require(move.fromWeek in 1..30 && move.toWeek in 1..30) { "周次必须在 1—30 之间" }
        require(move.weekday in 1..7) { "星期必须在 1—7 之间" }
        require(move.startSection in 1..11) { "开始节次必须在 1—11 之间" }
        require(move.endSection in move.startSection..11) { "结束节次不能早于开始节次" }
        val all = readAll().toMutableList()
        all.removeAll { it.matchesAnchor(termCode, original) && it.move.fromWeek == move.fromWeek }
        all += StoredWeekMove(
            termCode = termCode,
            courseName = original.courseName,
            originalWeekday = original.weekday,
            originalStartSection = original.startSection,
            originalEndSection = original.endSection,
            move = move,
        )
        writeAll(all)
    }

    /** 该课程在 [week] 这一周是否已经被搬去别的周。 */
    @Synchronized
    fun isMovedOut(termCode: String, group: XmuScheduleGroup, week: Int): Boolean =
        readAll().any { it.matchesAnchor(termCode, group) && it.move.fromWeek == week }

    /** 该课程是否存在跨周搬课记录（详情页「已修改」与「还原」都要认它）。 */
    @Synchronized
    fun has(termCode: String, group: XmuScheduleGroup): Boolean =
        readAll().any { it.matchesAnchor(termCode, group) }

    @Synchronized
    fun remove(termCode: String, original: XmuScheduleGroup): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeAll { it.matchesAnchor(termCode, original) }
        if (removed) writeAll(all)
        return removed
    }

    /** 别周搬到 [week] 的那些课；返回（原始锚点组, 搬过来之后用于显示的组）。 */
    @Synchronized
    fun movedIn(termCode: String, week: Int): List<Pair<XmuScheduleGroup, XmuScheduleGroup>> =
        readAll()
            .filter { it.termCode == termCode && it.move.toWeek == week }
            .map { stored ->
                val base = XmuScheduleGroup(
                    weekday = stored.originalWeekday,
                    startSection = stored.originalStartSection,
                    endSection = stored.originalEndSection,
                    startTime = scheduleSectionStartTime(stored.originalStartSection, 800),
                    endTime = scheduleSectionEndTime(stored.originalEndSection, 845),
                    courseName = stored.courseName,
                    rooms = listOfNotNull(stored.move.room.takeIf(String::isNotBlank)),
                    teachers = listOfNotNull(stored.move.teacher.takeIf(String::isNotBlank)),
                    weeks = stored.move.weeks,
                )
                base to base.copy(
                    weekday = stored.move.weekday,
                    startSection = stored.move.startSection,
                    endSection = stored.move.endSection,
                    startTime = scheduleSectionStartTime(stored.move.startSection, base.startTime),
                    endTime = scheduleSectionEndTime(stored.move.endSection, base.endTime),
                    // 跨周搬过来的课一定算调过 —— 加「[调]」前缀。
                    isAdjusted = true,
                )
            }

    private fun readAll(): List<StoredWeekMove> {
        val raw = prefs.getString(KEY_MOVES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val rows = JSONArray(raw)
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val value = row.optJSONObject("move") ?: continue
                    val fromWeek = value.optInt("fromWeek")
                    val toWeek = value.optInt("toWeek")
                    val weekday = value.optInt("weekday")
                    val startSection = value.optInt("startSection")
                    val endSection = value.optInt("endSection")
                    if (fromWeek !in 1..30 || toWeek !in 1..30) continue
                    if (weekday !in 1..7 || startSection !in 1..11 || endSection !in startSection..11) continue
                    add(
                        StoredWeekMove(
                            termCode = row.optString("termCode"),
                            courseName = row.optString("courseName"),
                            originalWeekday = row.optInt("originalWeekday"),
                            originalStartSection = row.optInt("originalStartSection"),
                            originalEndSection = row.optInt("originalEndSection"),
                            move = ScheduleWeekMove(
                                fromWeek = fromWeek,
                                toWeek = toWeek,
                                weekday = weekday,
                                startSection = startSection,
                                endSection = endSection,
                                room = value.optString("room"),
                                teacher = value.optString("teacher"),
                                weeks = value.optString("weeks"),
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(items: List<StoredWeekMove>) {
        val rows = JSONArray()
        items.forEach { item ->
            val value = JSONObject()
                .put("fromWeek", item.move.fromWeek)
                .put("toWeek", item.move.toWeek)
                .put("weekday", item.move.weekday)
                .put("startSection", item.move.startSection)
                .put("endSection", item.move.endSection)
                .put("room", item.move.room)
                .put("teacher", item.move.teacher)
                .put("weeks", item.move.weeks)
            rows.put(
                JSONObject()
                    .put("termCode", item.termCode)
                    .put("courseName", item.courseName)
                    .put("originalWeekday", item.originalWeekday)
                    .put("originalStartSection", item.originalStartSection)
                    .put("originalEndSection", item.originalEndSection)
                    .put("move", value),
            )
        }
        prefs.edit().putString(KEY_MOVES, rows.toString()).apply()
    }

    private data class StoredWeekMove(
        val termCode: String,
        val courseName: String,
        val originalWeekday: Int,
        val originalStartSection: Int,
        val originalEndSection: Int,
        val move: ScheduleWeekMove,
    ) {
        fun matchesAnchor(term: String, group: XmuScheduleGroup): Boolean =
            termCode == term &&
                normalizeCourseTitle(courseName) == normalizeCourseTitle(group.courseName) &&
                originalWeekday == group.weekday &&
                originalStartSection == group.startSection &&
                originalEndSection == group.endSection
    }

    private companion object {
        const val PREFS_NAME = "xmu_schedule_week_moves"
        const val KEY_MOVES = "course_week_moves_v1"
    }
}

/**
 *某一周最终要显示的（原始组, 生效组）列表 —— 删除 / 搬家 / 改时段三层依次套。
 * 周课表、明日课程与调课弹窗的对调判断都走这里，避免各处逻辑分叉。
 */
internal fun resolveWeekPairs(
    rawGroups: List<XmuScheduleGroup>,
    termCode: String,
    week: Int,
    overrides: ScheduleOverrideStore,
    moves: ScheduleWeekMoveStore,
): List<Pair<XmuScheduleGroup, XmuScheduleGroup>> {
    val kept = rawGroups
        .filterNot { overrides.isWeekCancelled(termCode, it, week) }
        .filterNot { moves.isMovedOut(termCode, it, week) }
        .map { it to overrides.apply(termCode, it, week) }
    return kept + moves.movedIn(termCode, week)
}

internal fun applyScheduleOverride(group: XmuScheduleGroup, value: ScheduleCourseOverride): XmuScheduleGroup {
    val room = value.room.takeIf(String::isNotBlank)
    val teacher = value.teacher.takeIf(String::isNotBlank)
    return group.copy(
        weekday = value.weekday,
        startSection = value.startSection,
        endSection = value.endSection,
        startTime = scheduleSectionStartTime(value.startSection, group.startTime),
        endTime = scheduleSectionEndTime(value.endSection, group.endTime),
        rooms = listOfNotNull(room),
        teachers = listOfNotNull(teacher),
        weeks = value.weeks,
        variants = listOf(XmuScheduleVariant(room.orEmpty(), teacher.orEmpty(), value.weeks)),
        // 被本地调整过的课在课表里加「[调]」前缀，便于和教务原始安排区分。
        // 只有真的挪了时段的记录才加前缀 —— 「修改安排」改教室 / 教师 / 周次不加；
        // 旧数据没有 moved 字段时退回按锚点比较星期与起止节次。
        isAdjusted = (value.moved ?: (
            value.weekday != group.weekday ||
                value.startSection != group.startSection ||
                value.endSection != group.endSection
            )) && !value.deleted,
    )
}

internal fun scheduleSectionStartTime(section: Int, fallback: Int): Int = when (section) {
    1 -> 800
    2 -> 850
    3 -> 1010
    4 -> 1100
    5 -> 1430
    6 -> 1520
    7 -> 1630
    8 -> 1720
    9 -> 1900
    10 -> 1950
    11 -> 2040
    else -> fallback
}

internal fun scheduleSectionEndTime(section: Int, fallback: Int): Int = when (section) {
    1 -> 845
    2 -> 935
    3 -> 1055
    4 -> 1145
    5 -> 1515
    6 -> 1605
    7 -> 1715
    8 -> 1805
    9 -> 1945
    10 -> 2035
    11 -> 2125
    else -> fallback
}
