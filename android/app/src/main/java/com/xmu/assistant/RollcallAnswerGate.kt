package com.xmu.assistant

import java.security.MessageDigest

/**
 * 前台「立即签到」与后台监控共用的一次性提交门。
 *
 * 两条路径都可能抓到同一条未签记录（平台回执有延迟，重拉仍显示进行中），
 * 各自发一次写请求就是重复提交。这里按「账号 + Cookie 指纹 + 记录 ID」串行化，
 * 只记住平台确认成功的结果，失败或回执不明不记，仍允许重试。
 *
 * 不保存明文 Cookie：指纹用 SHA-256。
 */
internal object RollcallAnswerGate {
    private const val ACCEPTED_TTL_MILLIS = 10 * 60 * 1000L
    private const val MAX_ACCEPTED = 512
    private val lock = Any()
    private val accepted = linkedMapOf<String, Long>()

    /**
     * 提交一次签到；同一 key 已确认成功时直接返回 true，不再发请求。
     *
     * [canAnswer] 在拿到锁之后、发请求之前再复核：等待期间可能已登出、
     * 换号或监控被停，此时抛 [RollcallAnswerNotSubmittedException] 表示「本地失效、未提交」，
     * 与平台拒绝区分开，不消耗监控的重试预算。
     */
    fun answerOnce(
        accountId: String,
        cookieHeader: String,
        rollcallId: String,
        canAnswer: () -> Boolean = { true },
        answer: () -> Boolean,
    ): Boolean = synchronized(lock) {
        if (!canAnswer()) throw RollcallAnswerNotSubmittedException("请求已失效，未提交签到")
        val key = "$accountId:${cookieFingerprint(cookieHeader)}:$rollcallId"
        val now = elapsedMillis()
        accepted.entries.removeIf { now - it.value >= ACCEPTED_TTL_MILLIS }
        if (key in accepted) return@synchronized true
        val result = answer()
        if (result) {
            accepted[key] = elapsedMillis()
            while (accepted.size > MAX_ACCEPTED) accepted.remove(accepted.keys.first())
        }
        result
    }

    private fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L

    private fun cookieFingerprint(cookieHeader: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(cookieHeader.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
