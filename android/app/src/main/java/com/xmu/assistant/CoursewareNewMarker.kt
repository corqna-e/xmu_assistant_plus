package com.xmu.assistant

/**
 *课件「有新增课件」判定的纯逻辑（无 Android 依赖，可独立编译自测）。
 *
 * 规则：
 * - 某科第一次抓到课件时只建立基线，不标「新增」；
 * - 之后每次抓到新列表，与上次基线比对，多出来的 id 记为该科的「待看新增」；
 * - 待看新增里的某个 id 下载成功后即移除；全部下载完 → 标记自然消失，
 *课程行回落到「已全部下载 / N 条」。
 * - 平台上已删除的课件会从待看新增里剔除，避免永远消不掉的角标。
 */
internal data class CoursewareSeenState(
    val seen: Set<String> = emptySet(),
    val pendingNew: Set<String> = emptySet(),
)

internal object CoursewareNewMarker {
    /**
     * @param previousSeen null 表示该科从未抓过（第一次），此时只建基线。
     * @return 新的基线与待看新增集合。
     */
    fun observeFetch(
        previousSeen: Set<String>?,
        currentIds: Set<String>,
        existingPending: Set<String>,
    ): CoursewareSeenState {
        if (previousSeen == null) {
            return CoursewareSeenState(currentIds, existingPending intersect currentIds)
        }
        val added = currentIds - previousSeen
        return CoursewareSeenState(
            seen = currentIds,
            pendingNew = (existingPending + added) intersect currentIds,
        )
    }

    /** 某个课件下载成功后，从该科的待看新增里去掉。 */
    fun afterDownload(pending: Set<String>, downloadedId: String): Set<String> = pending - downloadedId
}
