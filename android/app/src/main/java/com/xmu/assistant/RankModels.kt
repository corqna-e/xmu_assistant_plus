package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun rankDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Includes corrected marks, credits and retakes, not just the number of courses. */
internal fun rankScoreFingerprint(records: List<XmuScoreRecord>): String = rankDigest(
    records.map { xmuScoreRecordsToJson(listOf(it)) }.sorted().joinToString("\n"),
)

internal data class RankRange(
    val id: String, val name: String,
    /** 教务给的统计区间，例如「2025-2026学年 第一学期至…」；「自入学以来」原样保留。 */
    val term: String = "",
    /** 是否含校选（服务端 SFXGXK）；教务没给时为 null，界面不猜。 */
    val withElectives: Boolean? = null,
    /** 区间覆盖的学期数（JSXNXQDM 尾段，「2025-2026-3」= 3）；0 表示教务没给。 */
    val semesters: Int = 0,
)
internal data class RankRecord(
    val id: String, val participants: Int, val rangeId: String = "",
    val calculatedAt: String = "",
    /** 教务在计算记录里直接给出的名次（ZYGPAPM 等）；未开放排名时服务端填 "*"。 */
    val declaredRank: Int? = null,
    /** 名次字段的原始值摘要，仅在取不到名次时用于自证原因（不参与业务判定）。 */
    val rankFields: String = "",
    /**
     * 教务标记该申请已失效（SFSX=true / SFYX=false）。教务只保留有限有效期，
     * 过期记录仍在列表里，但证明往往已不可下载；当成「已有记录」复用会卡在取证明这一步。
     */
    val defunct: Boolean = false,
)
internal data class RankNumbers(val position: Int, val participants: Int)
internal data class RankResult(
    val recordId: String, val position: Int?, val participants: Int?, val rangeName: String,
    val requestedAt: Long, val obtainedAt: Long, val calculatedAt: String,
    val fingerprint: String,
    /**
     * 证明 PDF 在应用私有目录 `filesDir/rank/` 下的文件名，空串表示本地没有文件。
     * 名次、人数、日期等解析结果始终留在 prefs，删掉文件不影响界面显示。
     */
    val pdfFile: String,
    /** 结果所属范围的统计区间与含否校选，随结果一起落盘，重启后卡片仍能标注。 */
    val term: String = "", val withElectives: Boolean? = null,
    /** 该区间覆盖的学期数；0 表示未知。 */
    val semesters: Int = 0,
    /** 旧版本把 base64 直接写在 JSON 里：读到这种值要迁移成文件，迁移前不吃掉它。 */
    val pdfLegacy: Boolean = false,
)
internal data class RankPending(
    val beforeIds: Set<String>, val range: RankRange, val requestedAt: Long,
    val fingerprint: String, val recordId: String = "",
)
internal data class RankCache(
    val owner: String, val result: RankResult? = null, val pending: RankPending? = null,
    /**
     * 上一次真正向教务提交申请的时刻与范围。只用于节流 —— 教务对短时间重复申请有风控，
     * 客户端先自己拦住，别把「点了没反应」留给用户猜。
     */
    val lastSubmitAt: Long = 0, val lastSubmitRangeId: String = "",
    /** 上次选中的计算范围 ID。服务端的范围列表会随时间增减，恢复时要先确认它还在列表里。 */
    val selectedRangeId: String = "",
    /**
     * 「自入学以来 · 不含校选」这一档最近一次的结果。成绩页右下角固定展示这个口径的名次，
     * 而 [result] 会随用户在排名页切换范围而变，混用会让成绩页的名次莫名其妙地跟着变。
     */
    val overall: RankResult? = null,
)

/**
 * 把教务的长区间描述压成一句「2025-2026学年」。
 * 服务端给的是「2025-2026学年 第一学期至2025-2026学年 第三学期」，直接上屏会把卡片撑满；
 * 「自入学以来」这种没有学年数字的原样返回。
 */
internal fun rankTermLabel(term: String): String {
    val trimmed = term.trim()
    if (trimmed.isEmpty()) return ""
    Regex("\\d{4}-\\d{4}\\s*学年").find(trimmed)?.let { return it.value.replace(" ", "") }
    return trimmed
}

/**
 * 区间覆盖的学期数。教务的区间码（JSXNXQDM「2025-2026-3」）尾段就是截止学期，
 * 缺码时从「第一学期至…第三学期」里取最大的那个中文序数。
 * 「自入学以来」两种都没有，返回 0。
 */
internal fun rankSemesterCount(code: String, term: String): Int {
    Regex("-(\\d{1,2})$").find(code.trim())?.groupValues?.get(1)?.toIntOrNull()
        ?.let { if (it in 1..12) return it }
    val last = Regex("第([一二三四五六七八九十]+)学期").findAll(term)
        .mapNotNull { cnToNumber(it.groupValues[1]) }.maxOrNull()
    return last ?: 0
}

private fun cnToNumber(text: String): Int? {
    val digits = "一二三四五六七八九"
    return when {
        text == "十" -> 10
        text.startsWith("十") -> digits.indexOf(text.getOrElse(1) { '零' }) + 1 + 10
        text.length == 1 -> digits.indexOf(text).takeIf { it >= 0 }?.plus(1)
        text.length == 2 && text[1] == '十' -> digits.indexOf(text[0]).plus(1) * 10
        else -> null
    }
}

/**
 * 含否校选：只有教务明确给了值才标注，未知时返回空串。
 * 学期数（JSXNXQDM 尾段）只用来解析，不再上屏 —— 教务发的是整学年区间，
 * 「第一学期至第三学期」是区间定义而不是实际累计学期，标成「3 个学期」会误导。
 */
internal fun rankElectiveLabel(withElectives: Boolean?): String = when (withElectives) {
    true -> "含校选"
    false -> "不含校选"
    null -> ""
}

/**
 * 此前落盘的缓存只有范围名，名里同样带着学年与校选（「…第三学期不含校选」）。
 * 结构化字段缺失时退回从这个名字里取，老用户不必为了看到标注重新申请一次计算。
 */
internal fun rankElectiveFromName(name: String): String = when {
    name.contains("不含校选") -> "不含校选"
    name.contains("含校选") -> "含校选"
    else -> ""
}

/** 结果卡用的两个标注：结构化字段优先，旧缓存退回范围名。 */
internal fun rankTermOf(result: RankResult): String =
    rankTermLabel(result.term).ifEmpty { rankTermLabel(result.rangeName) }

internal fun rankElectiveOf(result: RankResult): String =
    rankElectiveLabel(result.withElectives).ifEmpty { rankElectiveFromName(result.rangeName) }

/** 范围选项的显示名：学年 + 含否校选，服务端缺字段时退回原名，不猜。 */
internal fun rankRangeLabel(range: RankRange): String =
    listOf(
        rankTermLabel(range.term).ifEmpty { range.name },
        rankElectiveLabel(range.withElectives),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * 是否是「自入学以来 · 不含校选」这一档。成绩页右下角固定展示这个口径的名次，
 * 与排名页当前选中的范围解耦 —— 否则用户在排名页换一档，成绩页的名次会跟着跳。
 * 教务给「自入学以来」的 term 可能是空串，因此同时看 term 与范围名。
 */
internal fun isOverallWithoutElectives(term: String, withElectives: Boolean?, rangeName: String): Boolean {
    if (withElectives != false) return false
    val label = rankTermLabel(term).ifEmpty { rankTermLabel(rangeName) }
    return label == "自入学以来" || label.contains("入学以来")
}

internal fun rankNumbersFromText(text: String): RankNumbers? {
    val normalized = text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ")
    val matches = Regex("total number of students in the major is\\s+([0-9,]+)\\s*,?\\s*with a GPA rank of\\s+([0-9,]+)", RegexOption.IGNORE_CASE)
        .findAll(normalized).mapNotNull {
            val total = it.groupValues[1].replace(",", "").toIntOrNull() ?: return@mapNotNull null
            val rank = it.groupValues[2].replace(",", "").toIntOrNull() ?: return@mapNotNull null
            if (rank in 1..total) RankNumbers(rank, total) else null
        }.distinct().toList()
    return matches.singleOrNull()
}

internal fun rankCacheToJson(cache: RankCache): String = JSONObject().apply {
    put("owner", cache.owner)
    cache.result?.let { r -> put("result", rankResultToJson(r)) }
    cache.overall?.let { r -> put("overall", rankResultToJson(r)) }
    cache.pending?.let { p -> put("pending", JSONObject().apply {
        put("before", JSONArray(p.beforeIds.toList())); put("rangeId", p.range.id); put("rangeName", p.range.name)
        put("rangeTerm", p.range.term); put("rangeElectives", p.range.withElectives ?: JSONObject.NULL)
        put("rangeSemesters", p.range.semesters)
        put("requested", p.requestedAt); put("fingerprint", p.fingerprint); put("id", p.recordId)
    }) }
    // 节流时间点必须跨进程存活：任务被系统杀掉后重进，仍要认得「刚提交过」。
    put("lastSubmitAt", cache.lastSubmitAt); put("lastSubmitRangeId", cache.lastSubmitRangeId)
    put("selectedRangeId", cache.selectedRangeId)
}.toString()

/** 一份结果的落盘形态：`result`（当前范围）与 `overall`（入学以来口径）共用。 */
private fun rankResultToJson(r: RankResult): JSONObject = JSONObject().apply {
    put("id", r.recordId); put("rank", r.position); put("total", r.participants)
    put("range", r.rangeName); put("requested", r.requestedAt); put("obtained", r.obtainedAt)
    put("calculated", r.calculatedAt); put("fingerprint", r.fingerprint); put("pdf", r.pdfFile)
    // 含否校选用三态：true/false 照写，未知写 null，恢复时不会被误判成「不含校选」。
    put("term", r.term); put("electives", r.withElectives ?: JSONObject.NULL); put("semesters", r.semesters)
}

private fun parseRankResult(it: JSONObject): RankResult {
    val pdf = it.optString("pdf")
    // 旧版本存的是整份 base64（没有 .pdf 后缀）；新版本只存文件名。
    val legacy = pdf.isNotBlank() && !pdf.endsWith(".pdf", ignoreCase = true)
    return RankResult(it.getString("id"), if (it.isNull("rank")) null else it.getInt("rank"),
        if (it.isNull("total")) null else it.getInt("total"), it.getString("range"),
        it.getLong("requested"), it.getLong("obtained"), it.optString("calculated"),
        it.getString("fingerprint"), pdf,
        it.optString("term"), it.optElectives("electives"), it.optInt("semesters"), legacy)
}

internal fun rankCacheFromJson(json: String, owner: String): RankCache = runCatching {
    val root = JSONObject(json)
    if (root.getString("owner") != owner) return RankCache(owner)
    val result = root.optJSONObject("result")?.let { parseRankResult(it) }
    val overall = root.optJSONObject("overall")?.let { parseRankResult(it) }
    val pending = root.optJSONObject("pending")?.let {
        val before = it.getJSONArray("before")
        RankPending((0 until before.length()).map { i -> before.getString(i) }.toSet(),
            RankRange(it.getString("rangeId"), it.getString("rangeName"),
                it.optString("rangeTerm"), it.optElectives("rangeElectives"), it.optInt("rangeSemesters")),
            it.getLong("requested"), it.getString("fingerprint"), it.optString("id"))
    }
    RankCache(owner, result, pending, root.optLong("lastSubmitAt"),
        root.optString("lastSubmitRangeId"), root.optString("selectedRangeId"), overall)
}.getOrDefault(RankCache(owner))

/** 旧缓存没有这两个键，缺键与显式 null 都按「未知」处理，绝不默认成不含校选。 */
private fun JSONObject.optElectives(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

/** Never pick the first historical record or guess among simultaneous new requests. */
internal fun identifyRankRecord(records: List<RankRecord>, pending: RankPending): RankRecord? {
    if (pending.recordId.isNotBlank()) return records.singleOrNull { it.id == pending.recordId }
    val candidates = records.filter {
        it.id !in pending.beforeIds && !it.defunct &&
            (it.rangeId.isBlank() || it.rangeId == pending.range.id)
    }
    check(candidates.size <= 1) { "发现多条新申请，无法确认本次记录。请在教务系统核对后继续。" }
    return candidates.singleOrNull()
}

/**
 * 所选范围内最近一次**有效**的完整计算记录（参与者 > 0、未被教务标记失效），供兜底采用。
 * 失效记录不算：教务只给有限有效期，过期后证明往往拉不下来，
 * 拿它当「已有记录」会把用户卡在取证明那一步，且每次都挑同一条。
 * 只接受成绩范围 ID 精确匹配的记录，避免把其他范围的结果当成本范围的排名。
 */
internal fun latestCompleteRecordForRange(records: List<RankRecord>, rangeId: String): RankRecord? =
    records.filter { it.rangeId == rangeId && it.participants > 0 && !it.defunct }
        .maxByOrNull { it.calculatedAt }
