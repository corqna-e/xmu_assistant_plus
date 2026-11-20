package com.xmu.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
     * 取代 startSection/endSection「起始-结束区间」：现在每个块独立可选、
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
    private const val BACKUP_NAME = "custom_exams.json.bak"
    private const val TAG = "CustomExamStore"

    /** 写锁：保存由多条 IO 协程发起，共用同一个 .tmp 会字节交错（torn-write）。 */
    private val writeLock = Any()

    /** 读取结果。damaged=true 表示主文件存在但无法解析（已尝试备份），调用方据此提示。 */
    data class LoadResult(val exams: List<CustomExam>, val damaged: Boolean)

    /** 读取全部自定义考试；文件缺失按空列表处理，解析失败才置 damaged。 */
    fun load(context: Context): List<CustomExam> = loadResult(context).exams

    fun loadResult(context: Context): LoadResult {
        val main = File(context.filesDir, FILE_NAME)
        parse(main)?.let { return LoadResult(it, damaged = false) }
        // 文件不存在 = 新装或从未保存过，不算损坏
        if (!main.exists()) return LoadResult(emptyList(), damaged = false)
        parse(File(context.filesDir, BACKUP_NAME))?.let {
            Log.w(TAG, "自定义考试主文件损坏，已从备份恢复")
            return LoadResult(it, damaged = true)
        }
        Log.w(TAG, "自定义考试主文件与备份均无法解析")
        return LoadResult(emptyList(), damaged = true)
    }

    /** 解析失败返回 null，与「解析成功但为空列表」区分开。 */
    private fun parse(file: File): List<CustomExam>? {
        if (!file.exists()) return null
        return runCatching { decode(file.readText()) }.getOrNull()
    }

    private fun decode(raw: String): List<CustomExam> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
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
    }

    /**
     * 全量原子写入（调用方负责在 IO 线程调用）。
     *
     * 先写 `custom_exams.json.tmp`，把旧文件复制成 `.bak`，再 ATOMIC_MOVE 替换正式文件；
     * 任何一步失败都不动正式文件，返回 false 并留日志。与 ScheduleCache 同一范式：
     * 直接用 writeText 覆盖时，进程写到一半被杀会留下半截 JSON，下次读取静默按空处理。
     */
    fun save(context: Context, exams: List<CustomExam>): Boolean {
        val target = File(context.filesDir, FILE_NAME)
        val backup = File(context.filesDir, BACKUP_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        return synchronized(writeLock) {
            try {
                temp.writeText(encode(exams))
                // 覆盖前留一份备份；备份失败不阻断本次写入。
                // 只备份「能解析」的旧文件——损坏的主文件不许覆盖掉好备份。
                if (target.exists() && parse(target) != null) {
                    runCatching {
                        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                // 原子替换，避免写一半崩溃留下损坏文件
                var replaced = replace(temp, target)
                for (attempt in 1..3) {
                    if (replaced) break
                    Thread.sleep(20)
                    replaced = replace(temp, target)
                }
                if (!replaced) throw IOException("custom exam atomic replace failed")
                true
            } catch (e: Exception) {
                Log.w(TAG, "自定义考试写入失败：${e.message}", e)
                false
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    private fun replace(temp: File, target: File): Boolean = runCatching {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    }.getOrDefault(false)

    private fun encode(exams: List<CustomExam>): String {
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
        return array.toString()
    }
}
