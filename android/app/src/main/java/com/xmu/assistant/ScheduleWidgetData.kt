package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Widget 专用的「今日课程摘要」缓存。
 *
 * 与课表主缓存（EncryptedSharedPreferences 加密大 JSON）分离：
 * - 只含课程名 / 时间 / 地点 / 周次等非敏感信息，不含学号密码 Cookie；
 * - 明文存储在普通 SharedPreferences，AppWidgetProvider 渲染时无需解密大 JSON；
 * - 在课表刷新成功后由 App 写入，并通知 Widget 更新。
 */
data class ScheduleWidgetCourse(
    val courseName: String,
    val startTime: Int,
    val endTime: Int,
    val startSection: Int,
    val endSection: Int,
    val location: String,
    /**该节次被本地调课改过，显示时加「[调]」前缀。 */
    val isAdjusted: Boolean = false,
    /**点开课程详情要用的完整字段。全部带默认值，旧缓存解码时留空即可。 */
    val weekday: Int = 0,
    val week: Int = 0,
    val weeks: String = "",
    val teachers: String = "",
    /**该行是考试（考试安排并入日程后的行），渲染时标「[考]」而不是课名前缀。 */
    val isExam: Boolean = false,
    /**
     * 课程色号（`SCHEDULE_COLOR_PALETTE` 下标），由投影时按整学期课表的邻接关系分配。
     *
     * -1 表示旧快照没有该字段，渲染端回退到课名散列。写进快照是为了让桌面组件
     * 不用自己再算一遍：同一门课在 App、今日组件、周课表组件里必须是同一个色号。
     */
    val colorIndex: Int = -1,
)

data class ScheduleWidgetSnapshot(
    val weekday: Int,
    val week: Int,
    val termCode: String,
    val courses: List<ScheduleWidgetCourse>,
    /** 快照生成时的本地日期（epochDay）：跨日后 Widget 渲染时判断是否还是「今日课程」。 */
    val savedEpochDay: Long = java.time.LocalDate.now().toEpochDay(),
)

object ScheduleWidgetData {
    private const val PREFS = "schedule_widget"
    private const val KEY_JSON = "today_summary"
    private const val KEY_DAYS = "upcoming_summaries"

    fun save(context: Context, snapshot: ScheduleWidgetSnapshot) {
        saveUpcoming(context, listOf(snapshot))
    }

    fun saveUpcoming(context: Context, snapshots: List<ScheduleWidgetSnapshot>) {
        if (snapshots.isEmpty()) return clear(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, encode(snapshots.first()).toString())
            .putString(KEY_DAYS, JSONArray(snapshots.map(::encode)).toString())
            .apply()
    }

    private fun encode(snapshot: ScheduleWidgetSnapshot): JSONObject = JSONObject()
            .put("weekday", snapshot.weekday)
            .put("week", snapshot.week)
            .put("termCode", snapshot.termCode)
            .put("savedEpochDay", snapshot.savedEpochDay)
            .put(
                "courses",
                JSONArray(snapshot.courses.map { course ->
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
                        .put("isExam", course.isExam)
                        .put("colorIndex", course.colorIndex)
                }),
            )

    fun load(context: Context, today: LocalDate = LocalDate.now()): ScheduleWidgetSnapshot? = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A failed or delayed network refresh must not make a known timetable disappear at midnight.
        val days = runCatching { JSONArray(preferences.getString(KEY_DAYS, "[]")) }.getOrDefault(JSONArray())
        for (index in 0 until days.length()) {
            val day = days.optJSONObject(index) ?: continue
            if (day.optLong("savedEpochDay") == today.toEpochDay()) return decode(day)
        }
        val raw = preferences.getString(KEY_JSON, null)
            ?: return null
        decode(JSONObject(raw))
    }.getOrNull()

    /**
     *按日期精确取某一天的摘要（用「明日课程」预告）。
     * 与 [load] 的区别：不做「取不到就回退到最近一条」的兜底，
     * 拿不到就是拿不到，避免把上周的课当成明天的课显示。
     */
    fun loadDay(context: Context, epochDay: Long): ScheduleWidgetSnapshot? = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val days = runCatching { JSONArray(preferences.getString(KEY_DAYS, "[]")) }.getOrDefault(JSONArray())
        for (index in 0 until days.length()) {
            val day = days.optJSONObject(index) ?: continue
            if (day.optLong("savedEpochDay") == epochDay) return decode(day)
        }
        null
    }.getOrNull()

    private fun decode(root: JSONObject): ScheduleWidgetSnapshot {
        val rows = root.optJSONArray("courses") ?: JSONArray()
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
                weekday = row.optInt("weekday", 0),
                week = row.optInt("week", 0),
                weeks = row.optString("weeks", ""),
                teachers = row.optString("teachers", ""),
                isExam = row.optBoolean("isExam", false),
                // 旧快照无该字段：-1 → 渲染端回退课名散列。
                colorIndex = row.optInt("colorIndex", -1),
            )
        }
        return ScheduleWidgetSnapshot(
            weekday = root.optInt("weekday"),
            week = root.optInt("week"),
            termCode = root.optString("termCode"),
            courses = courses,
            // 旧快照无该字段：optLong 默认 0，视为「未知日期」→ Widget 提示刷新
            savedEpochDay = root.optLong("savedEpochDay", 0L),
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_JSON)
            .remove(KEY_DAYS)
            .apply()
    }
}
