package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户自定义考试安排（手动输入，不来自教务抓取）。
 *
 * 与教务抓取的考试分开存放、分开展示：
 * - 抓取项：教务系统排出的考试安排；
 * - 自定义项：用户自己补充的安排（如旁听、临时调换、补考）。
 *
 * 两者在考试页用不同卡片样式区分，自定义项带「自定义」标记且可删除；
 * 自定义项同样会被并入课表（见 ExamScheduleMerge）。
 *
 * 与 [XmuExam] 同为公开数据类：SchedulePage 是公开函数，参数里会出现本类型。
 */
data class CustomExam(
    /** 稳定 id：用于编辑、删除定位；新建时用时间戳生成。 */
    val id: String,
    /** 课程/考试名称。 */
    val courseName: String,
    /** 考试日期，格式 yyyy-MM-dd。 */
    val date: String,
    /** 时间段，如 09:00-11:00。 */
    val timeRange: String,
    /** 考场/地点，可空。 */
    val room: String,
    /**
     *用户点选的节次集合（1-11，升序去重）；空集 = 未指定节次，
     * 此时并入课表按时间段与课表时间窗的重叠自动判定节次。
     *
     * 相比 startSection/endSection「起始-结束区间」的旧格式：现在每个块独立可选、
     * 可多选、可非连续（如只选第 1、3 节）。落在课表哪几行完全由本集合决定；
     * 单元格里显示的时间仍是 [timeRange] 里的真实时间。
     */
    val sections: Set<Int> = emptySet(),
)

/**
 * 自定义考试的本地存储（明文文件，不含账号信息）。
 *
 * 存 filesDir 下的 custom_exams.json：与应用其它缓存一致，不进 SharedPreferences
 * （条目可能较多，且需要整体读写）。登出/换号不清理——这是用户自己记的安排，
 * 属于个人笔记而非账号数据。
 */
internal object CustomExamStore {
    private const val FILE_NAME = "custom_exams.json"

    /** 读取全部自定义考试；文件缺失或损坏时返回空列表（不让一条坏数据拖垮整页）。 */
    fun load(context: Context): List<CustomExam> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CustomExam(
                id = id,
                courseName = obj.optString("courseName"),
                date = obj.optString("date"),
                timeRange = obj.optString("timeRange"),
                room = obj.optString("room"),
                // 优先读新的节次集合；旧文件只有 startSection/endSection，
                // 把它们还原成该区间内的全部节次，保证升级后已存的考试仍落在原来的行。
                sections = run {
                    val array = obj.optJSONArray("sections")
                    when {
                        array != null -> (0 until array.length())
                            .mapNotNull { array.optInt(it).takeIf { n -> n in 1..11 } }
                            .toSet()
                        else -> {
                            val start = obj.optInt("startSection", 0)
                            val end = obj.optInt("endSection", 0)
                            if (start in 1..11) (start..end.coerceIn(start, 11)).toSet() else emptySet()
                        }
                    }
                },
            )
        }
    }.getOrDefault(emptyList())

    /** 全量覆盖写入（调用方负责在 IO 线程调用）。 */
    fun save(context: Context, exams: List<CustomExam>) {
        runCatching {
            val array = JSONArray()
            exams.forEach { exam ->
                array.put(
                    JSONObject().apply {
                        put("id", exam.id)
                        put("courseName", exam.courseName)
                        put("date", exam.date)
                        put("timeRange", exam.timeRange)
                        put("room", exam.room)
                        // 写集合本身（升序稳定序列化）；保留两个旧字段作为降级兼容，
                        // 老版本 App 仍能按 startSection..endSection 读出大致落位。
                        put("sections", JSONArray(exam.sections.sorted()))
                        val first = exam.sections.minOrNull() ?: 0
                        val last = exam.sections.maxOrNull() ?: 0
                        put("startSection", first)
                        put("endSection", last)
                    }
                )
            }
            File(context.filesDir, FILE_NAME).writeText(array.toString())
        }
    }
}
