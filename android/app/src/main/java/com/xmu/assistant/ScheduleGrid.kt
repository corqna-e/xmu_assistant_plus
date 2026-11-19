package com.xmu.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

internal data class ScheduleGridAxes(val days: List<Int>, val sections: List<Int>)

internal fun scheduleGridAxes(): ScheduleGridAxes = ScheduleGridAxes((1..7).toList(), (1..11).toList())

internal data class ScheduleGridCluster(val courses: List<XmuScheduleGroup>) {
    val weekday get() = courses.first().weekday
    val startSection get() = courses.minOf { it.startSection }
    val endSection get() = courses.maxOf { it.endSection }
}

internal data class ScheduleGridPlacement(val course: XmuScheduleGroup, val lane: Int, val overlapping: Boolean)

internal fun scheduleGridPlacements(groups: List<XmuScheduleGroup>): List<ScheduleGridPlacement> =
    scheduleGridClusters(groups).flatMap { cluster ->
        val laneEnds = mutableListOf<Int>()
        cluster.courses.map { course ->
            val free = laneEnds.indexOfFirst { it < course.startSection }
            val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0 }
            laneEnds[lane] = course.endSection
            ScheduleGridPlacement(course, lane, cluster.courses.size > 1)
        }
    }

/** Keep overlapping courses reachable, including partial and chained overlaps. */
internal fun scheduleGridClusters(groups: List<XmuScheduleGroup>): List<ScheduleGridCluster> = buildList {
    groups.groupBy { it.weekday }.toSortedMap().forEach { (_, dayCourses) ->
        var current = mutableListOf<XmuScheduleGroup>()
        dayCourses.sortedWith(compareBy({ it.startSection }, { it.endSection }, { it.courseName })).forEach { course ->
            if (current.isNotEmpty() && course.startSection > current.maxOf { it.endSection }) {
                add(ScheduleGridCluster(current.toList()))
                current = mutableListOf()
            }
            current += course
        }
        if (current.isNotEmpty()) add(ScheduleGridCluster(current.toList()))
    }
}

/**
 *左侧节次列：节号在上，起止时间在下。
 * 时间取自厦大官方作息表（本学期有单节课程校正时用校正值）；课程格内不再重复显示时间。
 */
@Composable
private fun ScheduleGridSectionLabel(
    section: Int,
    window: IntRange?,
    textScale: Float,
) {
    val numStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = MaterialTheme.typography.labelMedium.fontSize * textScale,
        lineHeight = MaterialTheme.typography.labelMedium.fontSize * textScale * 1.05f,
    )
    val timeStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * textScale,
        lineHeight = MaterialTheme.typography.labelSmall.fontSize * textScale * 1.05f,
    )
    val timeColor = themePrimary().copy(alpha = 0.8f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PrivacyText("$section", style = numStyle, fontWeight = FontWeight.Bold, color = themePrimary(), maxLines = 1)
        PrivacyText(window?.let { formatMinuteOfDay(it.first) }.orEmpty(), style = timeStyle, color = timeColor, maxLines = 1)
        PrivacyText(window?.let { formatMinuteOfDay(it.last) }.orEmpty(), style = timeStyle, color = timeColor, maxLines = 1)
    }
}

/** 以字为中心：缩放只决定字号，列宽/行高由当前字号下文字的实测尺寸决定，框去适应字。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleWeekGrid(
    groups: List<XmuScheduleGroup>,
    weekStart: LocalDate?,
    selectedIsCurrent: Boolean,
    todayWeekday: Int,
    nowValue: Int,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
    scale: Float = 1f,
    sectionWindows: Map<Int, IntRange> = officialSectionWindows(),
) {
    val axes = remember { scheduleGridAxes() }
    val validGroups = groups.filter { it.weekday in axes.days && it.startSection in axes.sections }
    val placements = remember(validGroups) { scheduleGridPlacements(validGroups) }
    val laneCounts = axes.days.associateWith { day -> (placements.filter { it.course.weekday == day }.maxOfOrNull { it.lane } ?: 0) + 1 }
    val headerStyle = MaterialTheme.typography.labelMedium
    // 宽度最低 75%：滑条低于 75% 时列宽/表头与节次号字号锁定在 75%，仅行高继续压缩。
    // 表头文字与节次号都跟随 widthScale（非原始 scale）：低于 75% 时字体不再缩小。
    val widthScale = maxOf(scale, 0.75f)
    val heightRatio = scale / widthScale
    @Composable
    fun CourseCard(placement: ScheduleGridPlacement) {
        val group = placement.course
            ScheduleGridCourseBlock(
                group = group,
                dimmed = selectedIsCurrent && group.weekday == todayWeekday && group.endTime < nowValue,
                inProgress = selectedIsCurrent && group.weekday == todayWeekday && nowValue in group.startTime..group.endTime,
                onClick = { onCourseSelected(group) },
                modifier = Modifier.padding(3.dp * widthScale),
                overlapping = placement.overlapping,
                textScale = widthScale,
            )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 可用宽度锁定为卡片内容宽度（屏宽减去卡片内边距），列宽据此反推，
        // 让表头与七列始终铺满屏幕；文字实测宽度只作为下限，防止放大时挤字。
        val availablePx = with(LocalDensity.current) { maxWidth.roundToPx() }
        SubcomposeLayout(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { constraints ->
            val scaledHeader = headerStyle.copy(fontSize = headerStyle.fontSize * widthScale)
            // 表头文字与节次号在缩放后字号下的自然宽度，仅作列宽下限。
            // 探针只用于测量宽度，包一层空语义：否则「周一 11/10」「11」会混进语义树，
            // TalkBack 和 UI 测试都会看到两份文本（onNodeWithText("11") 命中 2 个节点）。
            val dayProbe = subcompose("dayProbe") {
                Box(Modifier.clearAndSetSemantics {}) {
                    PrivacyText("周一\n11/10", style = scaledHeader, fontWeight = FontWeight.Bold, maxLines = 2)
                }
            }.first().measure(Constraints())
            val sectionProbe = subcompose("sectionProbe") {
                Box(Modifier.clearAndSetSemantics {}) {
                    ScheduleGridSectionLabel(11, sectionWindows[11], widthScale)
                }
            }.first().measure(Constraints())
            val pad = (8.dp * widthScale).roundToPx().coerceAtLeast(2)
            val minDayPx = (dayProbe.width + pad * 2).coerceAtLeast(6)
            // 节次列现在要放「节号 + 起止时间」，比原来宽；内边距单独取小一些。
            val sectionPad = (5.dp * widthScale).roundToPx().coerceAtLeast(2)
            val sectionPreferred = sectionProbe.width + sectionPad * 2
            val laneSum = laneCounts.values.sum()
            // 总宽锁定 = 可用宽度：先给七列（含重叠课拆出的车道）留出文字下限，
            // 节次列只吃剩余空间。否则时间进来后节次列变宽会把最后一列顶出卡片，
            // 日期被裁成「11/2」且整张表需要横向滚动。
            val sectionRoom = (availablePx - minDayPx * laneSum).coerceAtLeast(sectionProbe.width + 2)
            val sectionPx = minOf(sectionPreferred, sectionRoom).coerceAtLeast(6)
            // 列宽一律按「总宽 = 可用宽度」反算，不再给 dayPx 抬 minDayPx 下限。
            // 之前放大字号后下限把总宽顶出屏幕，导致整张表能横向拖动、周一列被裁。
            val dayPx = ((availablePx - sectionPx) / laneSum).coerceAtLeast(1)
            val width = sectionPx + dayPx * laneSum
            val dayDp = dayPx.toDp()
            val sectionDp = sectionPx.toDp()
        val measurables = subcompose("grid") {
            Row {
                ScheduleGridHeaderCell("节次", sectionDp, false, widthScale)
                axes.days.forEach { day ->
                    val date = weekStart?.plusDays(day - 1L)?.let { "${it.monthValue}/${it.dayOfMonth}" }.orEmpty()
                    ScheduleGridHeaderCell("周${xmuWeekdayShort(day)}\n$date", dayDp * laneCounts.getValue(day), selectedIsCurrent && day == todayWeekday, widthScale)
                }
            }
            axes.sections.forEach { section ->
                Row(Modifier.fillMaxWidth()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.width(sectionDp).fillMaxHeight(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            ScheduleGridSectionLabel(section, sectionWindows[section], widthScale)
                        }
                    }
                    axes.days.forEach { day ->
                        Surface(
                            color = if (selectedIsCurrent && day == todayWeekday) themeTodayColumn() else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(dayDp * laneCounts.getValue(day)).fillMaxHeight(),
                        ) { }
                    }
                }
            }
            placements.forEach { CourseCard(it) }
        }
        val header = measurables[0].measure(Constraints.fixedWidth(width))
        // Measure real wrapped text, including FlowRow, before fixing every card to its span.
        val spans = placements.map { (it.course.endSection.coerceAtMost(11) - it.course.startSection + 1).coerceAtLeast(1) }
        val measuredCards = subcompose("measure") {
            placements.forEach { placement ->
                Box(Modifier.clearAndSetSemantics {}) { CourseCard(placement) }
            }
        }.map { it.measure(Constraints.fixedWidth(dayPx)) }
        // 行高 = 实测高度 × 高度比（滑条低于 75% 时仅压高度；≥75% 时 ratio=1 即框适应字），
        // 下限同时要容得下左侧节次列的三行文字（节号 + 起止时间），
        // 否则低缩放下节次列文字会与相邻行重叠 —— 这是时间移到节次列后的缩放兼容关键。
        val minPeriod = maxOf((12.dp * scale).roundToPx().coerceAtLeast(1), sectionProbe.height + 2)
        val periodHeight = placements.indices.fold(minPeriod) { height, index ->
            maxOf(height, ((measuredCards[index].height * heightRatio).toInt() + spans[index] - 1) / spans[index])
        }
        val heights = IntArray(axes.sections.size) { periodHeight }
        val cards = placements.indices.map { index ->
            measurables[1 + axes.sections.size + index].measure(Constraints.fixed(dayPx, periodHeight * spans[index]))
        }
        val tops = IntArray(heights.size)
        var totalHeight = header.height
        heights.forEachIndexed { index, height -> tops[index] = totalHeight; totalHeight += height }
        val strips = axes.sections.indices.map { index ->
            measurables[1 + index].measure(Constraints.fixed(width, heights[index]))
        }
        layout(constraints.constrainWidth(width), constraints.constrainHeight(totalHeight)) {
            header.placeRelative(0, 0)
            strips.forEachIndexed { index, strip -> strip.placeRelative(0, tops[index]) }
            cards.forEachIndexed { index, card ->
                val placement = placements[index]
                val group = placement.course
                val precedingColumns = axes.days.takeWhile { it != group.weekday }.sumOf { laneCounts.getValue(it) }
                card.placeRelative(sectionPx + (precedingColumns + placement.lane) * dayPx, tops[axes.sections.indexOf(group.startSection)])
            }
        }
        }
    }
}
