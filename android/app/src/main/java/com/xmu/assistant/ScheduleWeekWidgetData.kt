package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 *周课表桌面小组件的整周快照。
 *
 * 与「今日课程」小组件（ScheduleWidgetData，14 天滚动摘要）分开存：
 * 周课表要一次画满周一到周日，需要「本周 7 天」的横切数据，而不是逐日快照。
 * 同样只含课程名 / 时间 / 地点这类非敏感信息，明文存普通 SharedPreferences。
 */
internal data class ScheduleWeekWidgetSnapshot(
    val termCode: String,
    val week: Int,
    /** 本周周一的 epochDay，用于判断快照是否已过期（跨周）。 */
    val mondayEpochDay: Long,
    /** 生成快照时的本地日期，用于「今天」列的判定。 */
    val generatedEpochDay: Long,
    /** 周一到周日共 7 项，每项为当天已合并的课程；无课为空列表。 */
    val days: List<List<ScheduleWidgetCourse>>,
)

internal object ScheduleWeekWidgetData {
    private const val PREFS = "schedule_week_widget"
    private const val KEY_JSON = "week_summary"
    /**整学期各周快照（week -> 该周 7 天），供左右箭头翻周查阅。 */
    private const val KEY_BUNDLE = "week_bundle"
    /**用户正在查看的周次；0 = 跟随当前周。 */
    private const val KEY_VIEW_WEEK = "view_week"
    /**
     * 同步时算出的「当前教学周」。
     * 之前靠 mondayEpochDay 与今天所在周一比对反推当前周，学期日历有偏差时会整体匹配不上，
     * 兜底落到「最后一周」，桌面小卡片因此变空白。改为同步时直接落库。
     */
    private const val KEY_CURRENT_WEEK = "current_week"

    fun save(context: Context, snapshot: ScheduleWeekWidgetSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, encode(snapshot).toString())
            .apply()
    }

    /**
     * 一次写入整学期所有周；保留旧的 KEY_JSON 兼容老代码路径。
     *
     * [resetViewWeek]：联网同步（新课表）时归零，让桌面回到「跟随当前周」；
     * 本地调课 / 组件刷新这类局部改动传 false —— 用户正翻在第 N 周看课，
     * 顺手改一条就被弹回本周会很别扭。
     */
    fun saveBundle(
        context: Context,
        snapshots: List<ScheduleWeekWidgetSnapshot>,
        currentWeek: Int,
        resetViewWeek: Boolean = true,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = snapshots.firstOrNull { it.week == currentWeek } ?: snapshots.firstOrNull()
        prefs.edit()
            .putString(KEY_BUNDLE, JSONArray(snapshots.map(::encode)).toString())
            .putInt(KEY_CURRENT_WEEK, currentWeek)
            // 数据刷新后回到「跟随当前周」，避免上次翻到第 N 周的状态残留。
            .apply {
                if (resetViewWeek) putInt(KEY_VIEW_WEEK, 0)
            }
            .apply {
                if (current != null) putString(KEY_JSON, encode(current).toString())
            }
            .apply()
    }

    /** 同步时落库的当前教学周；0 表示尚未记录。 */
    fun getCurrentWeek(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_CURRENT_WEEK, 0)

    fun loadBundle(context: Context): List<ScheduleWeekWidgetSnapshot> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BUNDLE, null) ?: return runCatching { load(context)?.let(::listOf) ?: emptyList() }.getOrDefault(emptyList())
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::decode) }
        }.getOrDefault(emptyList())
    }

    /** 当前查看周：0 表示跟随当前周。 */
    fun getViewWeek(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_VIEW_WEEK, 0)

    fun setViewWeek(context: Context, week: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VIEW_WEEK, week).apply()
    }

    fun load(context: Context): ScheduleWeekWidgetSnapshot? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return null
        decode(JSONObject(raw))
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_JSON)
            .apply()
    }

    private fun encode(snapshot: ScheduleWeekWidgetSnapshot): JSONObject = JSONObject()
        .put("termCode", snapshot.termCode)
        .put("week", snapshot.week)
        .put("mondayEpochDay", snapshot.mondayEpochDay)
        .put("generatedEpochDay", snapshot.generatedEpochDay)
        .put(
            "days",
            JSONArray(snapshot.days.map { day ->
                JSONArray(day.map { course ->
                    JSONObject()
                        .put("courseName", course.courseName)
                        .put("startTime", course.startTime)
                        .put("endTime", course.endTime)
                        .put("startSection", course.startSection)
                        .put("endSection", course.endSection)
                        .put("location", course.location)
                        .put("isAdjusted", course.isAdjusted)
                        .put("weekday", course.weekday)
                        .put("week", course.week)
                        .put("weeks", course.weeks)
                        .put("teachers", course.teachers)
                        .put("colorIndex", course.colorIndex)
                })
            }),
        )

    private fun decode(root: JSONObject): ScheduleWeekWidgetSnapshot {
        val daysJson = root.optJSONArray("days") ?: JSONArray()
        val days = ArrayList<List<ScheduleWidgetCourse>>(7)
        for (dayIndex in 0 until 7) {
            val rows = daysJson.optJSONArray(dayIndex) ?: JSONArray()
            val courses = ArrayList<ScheduleWidgetCourse>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                courses += ScheduleWidgetCourse(
                    courseName = row.optString("courseName"),
                    startTime = row.optInt("startTime"),
                    endTime = row.optInt("endTime"),
                    startSection = row.optInt("startSection"),
                    endSection = row.optInt("endSection"),
                    location = row.optString("location"),
                    isAdjusted = row.optBoolean("isAdjusted", false),
                    weekday = row.optInt("weekday", dayIndex + 1),
                    week = row.optInt("week", root.optInt("week", 0)),
                    weeks = row.optString("weeks", ""),
                    teachers = row.optString("teachers", ""),
                    // 旧快照无该字段：-1 → 渲染端回退课名散列。
                    colorIndex = row.optInt("colorIndex", -1),
                )
            }
            days += courses
        }
        return ScheduleWeekWidgetSnapshot(
            termCode = root.optString("termCode"),
            week = root.optInt("week"),
            mondayEpochDay = root.optLong("mondayEpochDay", 0L),
            generatedEpochDay = root.optLong("generatedEpochDay", 0L),
            days = days,
        )
    }

    /** 快照是否属于 [today] 所在的那一周；跨周后旧数据必须当空处理，否则会画出上周的课。 */
    fun isCurrentWeek(snapshot: ScheduleWeekWidgetSnapshot, today: LocalDate = LocalDate.now()): Boolean {
        val monday = today.minusDays(today.dayOfWeek.value - 1L).toEpochDay()
        return snapshot.mondayEpochDay == monday
    }
}
