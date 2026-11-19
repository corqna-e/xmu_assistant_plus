package com.xmu.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机诊断环形缓冲（v112）。
 *
 * 背景：全项目 175 处 runCatching 把失败折叠成空值（getOrNull / getOrDefault），
 * 用户看到「暂无数据」时无法区分「平台上真的没有」和「接口字段改了、解析静默失败」。
 * 这里保留最近 [CAPACITY] 条失败记录，只记「时间 + 模块 + 异常类型 + 一句调用方描述」，
 * **不记异常 message**（可能带 URL 查询串、Cookie 片段），不记任何凭据。
 *
 * 存储为普通 SharedPreferences（不碰 Keystore：诊断本身要能在加密存储损坏时工作）。
 * 出口在「更多 - 使用指南」页，一键复制全文，用户可直接贴给开发者。
 */
object Diagnostics {
    private const val CAPACITY = 200
    private const val PREFS_NAME = "diagnostics"
    private const val KEY_LINES = "lines"
    private const val MAX_DETAIL = 120

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    /** 在进程入口调用一次；未安装时只写内存，不落盘。 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = runCatching { prefs()?.getString(KEY_LINES, "").orEmpty() }.getOrDefault("")
        if (raw.isNotBlank()) {
            raw.lineSequence().filter { it.isNotBlank() }.forEach { lines.addLast(it) }
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    /**
     * 记录一次失败。[detail] 由调用方给一句人话（如 "课表 JSON 解析"），
     * 不要塞 URL、Cookie、Cookie 值或学号。
     */
    fun record(module: String, error: Throwable? = null, detail: String = "") {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val type = error?.javaClass?.simpleName.orEmpty()
        val line = buildString {
            append(stamp).append("  [").append(module).append(']')
            if (type.isNotBlank()) append(' ').append(type)
            if (detail.isNotBlank()) append(" · ").append(detail.take(MAX_DETAIL))
        }
        synchronized(lock) {
            ensureLoaded()
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
            runCatching { prefs()?.edit()?.putString(KEY_LINES, lines.joinToString("\n"))?.apply() }
        }
    }

    /** 最近 [CAPACITY] 条记录，最新在前；空时返回一句占位说明。 */
    fun snapshot(): String {
        synchronized(lock) {
            ensureLoaded()
            if (lines.isEmpty()) return "暂无诊断记录。"
            return buildString {
                append("虾大助手 Plus 诊断记录（最近 ").append(lines.size).append(" 条，不含账号与密码）\n")
                append("版本 ").append(BuildConfig.VERSION_NAME).append("（").append(BuildConfig.VERSION_CODE).append("）\n")
                append("-".repeat(32)).append('\n')
                lines.reversed().forEach { append(it).append('\n') }
            }
        }
    }

    fun count(): Int = synchronized(lock) { ensureLoaded(); lines.size }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            loaded = true
            runCatching { prefs()?.edit()?.remove(KEY_LINES)?.apply() }
        }
    }
}
