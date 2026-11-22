package com.xmu.assistant

import kotlin.math.abs

/**
 * 配色用的一个「课表格子」：某门课在某天占了哪几节。
 *
 * 只留配色需要的字段，让分配逻辑可以脱离 Android / Compose 单独验证。
 */
internal data class ScheduleColorCell(
    val courseName: String,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
)

/**
 * 课程配色分配：把「课名 → 色号」从纯散列改成按课表邻接着色。
 *
 * 纯课名散列只见课名、不见同屏还有哪些课，短中文课名的哈希低位又容易扎堆，
 * 于是相邻两格经常同色。这里把整学期课表当成一张图：同一天上下相接、同一天按节次
 * 排下来前后相邻、相邻两天时段重叠的两门课都算相邻；逐门课挑色时先避开已着色邻居
 * 用过的色号，再从剩下的里挑全局用得最少的（同分取色号小的）。
 *
 * 结果只取决于课表条目本身，与「当前看哪一周」无关，所以同一门课在周课表、
 * 日程、今日课程组件与周课表组件里永远同一个色号。代价是整学期的邻接比单周密，
 * 一门课的邻居把色板占满时才会退让 —— 那是重复不可避免的情况。
 */
internal fun allocateScheduleColors(
    cells: List<ScheduleColorCell>,
    paletteSize: Int,
): Map<String, Int> {
    if (paletteSize <= 0) return emptyMap()
    val uniqueCells = cells.filter { it.courseName.isNotBlank() }.distinct()
    if (uniqueCells.isEmpty()) return emptyMap()
    // 着色顺序：先上后下、先左后右，让相邻格子尽早拿到不同色。
    val ordered = uniqueCells.sortedWith(
        compareBy<ScheduleColorCell> { it.weekday }
            .thenBy { it.startSection }
            .thenBy { it.endSection }
            .thenBy { it.courseName },
    )
    // 同一天按节次排下来，前后紧挨的两门课也算相邻。
    // 只按「节次重叠 / 上下相接」判定会漏掉 3-4 节与 7-8 节这种中间空一段的情况：
    // 课表里它们在同一列、只隔一行空白，日程列表里更是上下紧挨的两张卡，同色就撞上了。
    val dayOrderAdjacent = LinkedHashSet<Pair<String, String>>()
    ordered.groupBy { it.weekday }.values.forEach { dayCells ->
        dayCells
            .sortedWith(compareBy({ it.startSection }, { it.endSection }, { it.courseName }))
            .zipWithNext()
            .forEach { (before, after) ->
                if (before.courseName != after.courseName) {
                    dayOrderAdjacent += coursePairKey(before.courseName, after.courseName)
                }
            }
    }
    val assigned = LinkedHashMap<String, Int>(ordered.size)
    val usage = IntArray(paletteSize)
    for (cell in ordered) {
        if (cell.courseName in assigned) continue
        val reserved = ordered.asSequence()
            .filter { it.courseName != cell.courseName && it.courseName in assigned }
            .filter {
                isAdjacentCell(cell, it) ||
                    coursePairKey(cell.courseName, it.courseName) in dayOrderAdjacent
            }
            .map { assigned.getValue(it.courseName) }
            .toSet()
        val candidates = (0 until paletteSize).filter { it !in reserved }
            .ifEmpty { (0 until paletteSize).toList() }
        var chosen = candidates.first()
        for (index in candidates) {
            if (usage[index] < usage[chosen]) chosen = index
        }
        assigned[cell.courseName] = chosen
        usage[chosen]++
    }
    return assigned
}

/** 课名对的无序键，用来把「这天这两门课挨着」记成一个可查的集合元素。 */
private fun coursePairKey(a: String, b: String): Pair<String, String> =
    if (a <= b) a to b else b to a

/** 同一天上下相接、或相邻两天时段重叠，两者在课表上就是并排/上下相邻的格子。 */
private fun isAdjacentCell(a: ScheduleColorCell, b: ScheduleColorCell): Boolean {
    val dayGap = abs(a.weekday - b.weekday)
    if (dayGap > 1) return false
    // 节次区间有交集（跨节次的长课与旁边的短课）。
    val overlap = a.startSection <= b.endSection && b.startSection <= a.endSection
    if (dayGap == 0) {
        return overlap || a.endSection + 1 == b.startSection || b.endSection + 1 == a.startSection
    }
    return overlap
}

/** 整学期课表 → 课名配色表。App 与桌面组件都从这里取同一份映射。 */
internal fun scheduleColorMapForEntries(
    entries: List<XmuScheduleEntry>,
    paletteSize: Int,
): Map<String, Int> = allocateScheduleColors(
    entries.map { ScheduleColorCell(it.courseName, it.weekday, it.startSection, it.endSection) },
    paletteSize,
)
