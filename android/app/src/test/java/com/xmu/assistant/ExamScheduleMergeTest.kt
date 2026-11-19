package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * v52：考试并入课表的换算逻辑测试。
 *
 * 纯 JVM 计算（不依赖 Android），覆盖：
 * - 时间段解析的合法/非法输入；
 * - 节次时间窗的推导（跨节课程按节数均分）；
 * - 考试时间 → 节次的换算；
 * - 整周合并：日期落在周内才出现，星期与节次正确。
 */
class ExamScheduleMergeTest {

    private fun entry(
        weekday: Int,
        startSection: Int,
        endSection: Int,
        startTime: Int,
        endTime: Int,
        name: String = "课程",
    ) = XmuScheduleEntry(
        weekday = weekday,
        startSection = startSection,
        endSection = endSection,
        startTime = startTime,
        endTime = endTime,
        courseName = name,
        room = "A101",
        teacher = "张老师",
        weeks = "1-8周",
        termCode = "2025-2026-2",
    )

    @Test
    fun parsesTimeRange() {
        assertEquals(480 to 600, examTimeRangeMinutes("08:00-10:00"))
        assertEquals(540 to 570, examTimeRangeMinutes(" 09:00 - 09:30 "))
        assertNull(examTimeRangeMinutes("08:00"))
        assertNull(examTimeRangeMinutes("25:00-26:00"))
        // 结束早于开始视为非法（跨午夜不参与课表换算）
        assertNull(examTimeRangeMinutes("10:00-08:00"))
    }

    @Test
    fun derivesSectionWindows() {
        val windows = sectionTimeWindows(listOf(entry(1, 1, 2, 800, 940)))
        // 1-2 节共 100 分钟，均分后每节 50 分钟
        assertEquals(480, windows.getValue(1).first)
        assertEquals(530, windows.getValue(1).last)
        assertEquals(530, windows.getValue(2).first)
        assertEquals(580, windows.getValue(2).last)
    }

    @Test
    fun mapsExamTimeToSections() {
        val windows = sectionTimeWindows(
            listOf(
                entry(1, 1, 2, 800, 940),
                entry(1, 3, 4, 1000, 1140),
            ),
        )
        // 08:00-10:00 与 1-2 节重叠，不含 10:00 才开始的 3-4 节
        assertEquals(1..2, sectionsForExam(480, 600, windows))
        // 14:00-16:00 落在 5-6 节（若课表给出 5-6 节 14:00-15:40）
        val afternoon = sectionTimeWindows(listOf(entry(1, 5, 6, 1400, 1540)))
        assertEquals(5..6, sectionsForExam(840, 960, afternoon))
    }

    @Test
    fun mergesOnlyExamsInsideTheWeek() {
        val weekStart = LocalDate.of(2026, 6, 8) // 周一
        val entries = listOf(
            entry(1, 1, 2, 800, 940),
            entry(3, 1, 2, 800, 940),
        )
        val exams = listOf(
            XmuExam(
                id = "1",
                courseName = "解剖学",
                date = "2026-06-08",
                timeRange = "08:00-10:00",
                room = "A401",
                mode = "线下",
                examName = "期末考试",
            ),
            XmuExam(
                id = "2",
                courseName = "生理学",
                date = "2026-06-20", // 不在本周
                timeRange = "08:00-10:00",
                room = "B101",
                mode = "线下",
                examName = "期末考试",
            ),
        )
        val custom = listOf(
            CustomExam(
                id = "c1",
                courseName = "旁听考试",
                date = "2026-06-10",
                timeRange = "14:00-16:00",
                room = "C101",
            ),
        )
        val merged = mergeExamsIntoWeek(weekStart, entries, exams, custom)
        assertEquals(2, merged.size)

        val anatomy = merged.first { it.courseName == "解剖学" }
        assertEquals(1, anatomy.weekday) // 周一
        assertEquals(1, anatomy.startSection)
        assertEquals(2, anatomy.endSection)
        assertEquals(800, anatomy.startTime)
        assertEquals(1000, anatomy.endTime)
        assertEquals(listOf("A401"), anatomy.rooms)
        assertTrue(anatomy.isExam)
        assertEquals("考试", anatomy.weeks)

        val customGroup = merged.first { it.courseName == "旁听考试" }
        assertEquals(3, customGroup.weekday) // 周三
        assertEquals("考试（自定义）", customGroup.weeks)
    }

    @Test
    fun toggleSectionIsOrderIndependentAndMultiSelect() {
        // v99：点哪个选哪个、可多选、再点取消，与先后无关，也不再有「起始/结束」。
        val empty = emptySet<Int>()
        assertEquals(setOf(5), toggleSection(empty, 5))
        // 先点 5 再点 3 → 两块都选中（旧逻辑点更小值会重置成 3）。
        assertEquals(setOf(3, 5), toggleSection(setOf(5), 3))
        // 再点 3 → 只取消 3，5 保留。
        assertEquals(setOf(5), toggleSection(setOf(3, 5), 3))
        // 越界点击不改变选择。
        assertEquals(setOf(5), toggleSection(setOf(5), 12))
        assertEquals(setOf(5), toggleSection(setOf(5), 0))
    }

    @Test
    fun sectionRunsSplitDiscontiguousSelection() {
        // 选 1、3、4、6 → 连续段 1 / 3-4 / 6，课表按这三块渲染。
        assertEquals(listOf(1..1, 3..4, 6..6), sectionRuns(setOf(1, 3, 4, 6)))
        assertEquals(listOf(2..5), sectionRuns(setOf(5, 2, 3, 4)))
        assertTrue(sectionRuns(emptySet()).isEmpty())
    }

    @Test
    fun sectionsTimeWindowUsesOfficialWindows() {
        val windows = officialSectionWindows()
        // 只选第 3 节 → 10:10-10:55（该块自己的时间）。
        assertEquals(610 to 655, sectionsTimeWindow(setOf(3), windows))
        // 选 1、3 → 最早起点(08:00) 到最晚终点(10:55)。
        assertEquals(480 to 655, sectionsTimeWindow(setOf(1, 3), windows))
        assertNull(sectionsTimeWindow(emptySet(), windows))
    }

    @Test
    fun formatsSectionsAscending() {
        assertEquals("1、3、5", formatSections(setOf(5, 1, 3)))
        assertEquals("", formatSections(emptySet()))
    }

    @Test
    fun customExamWithSplitSectionsProducesMultipleGridBlocks() {
        val weekStart = LocalDate.of(2026, 6, 8)
        val custom = listOf(
            CustomExam(
                id = "c1",
                courseName = "旁听考试",
                date = "2026-06-10",
                timeRange = "08:00-10:55",
                room = "C101",
                sections = setOf(1, 3),
            ),
        )
        val merged = mergeExamsIntoWeek(weekStart, emptyList(), emptyList(), custom)
        // 第 1、3 节各出一块，中间第 2 节不被占用。
        assertEquals(2, merged.size)
        val bySection = merged.associateBy { it.startSection }
        assertEquals(1, bySection[1]?.endSection)
        assertEquals(3, bySection[3]?.endSection)
        assertTrue(merged.all { it.weeks == "考试（自定义）" })
    }

    @Test
    fun returnsEmptyWithoutCalendarAnchor() {
        // 开学日未校准时无法把周次换算成日期，考试无法定位到具体某天
        assertTrue(
            mergeExamsIntoWeek(
                null,
                listOf(entry(1, 1, 2, 800, 940)),
                listOf(
                    XmuExam(
                        id = "1",
                        courseName = "解剖学",
                        date = "2026-06-08",
                        timeRange = "08:00-10:00",
                        room = "A401",
                        mode = "线下",
                        examName = "期末考试",
                    ),
                ),
                emptyList(),
            ).isEmpty(),
        )
    }
}
