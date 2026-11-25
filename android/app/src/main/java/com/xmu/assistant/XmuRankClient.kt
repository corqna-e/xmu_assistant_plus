package com.xmu.assistant

import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** ZYGPAPM=专业 GPA 排名，ZYJQPJFPM=专业加权平均分排名，ZYSSPJFPM=专业算术平均分排名。 */
private val RANK_FIELDS = listOf("ZYGPAPM", "ZYJQPJFPM", "ZYSSPJFPM")

/** 申请记录节点自身的标志字段：用来把「记录」和「记录容器」区分开。 */
private val RECORD_MARKERS = setOf("SFSX", "SFYX", "SQJLWID", "SQSJ", "SXSJ", "ZX")

internal data class RankResponse(val code: Int, val headers: Map<String, List<String>>, val bytes: ByteArray)
internal fun interface RankTransport { fun execute(path: String, form: Map<String, String>?, cookie: String): RankResponse }

/** No transparent retries or redirects for the non-idempotent application POST. */
internal class RankOkHttpTransport : RankTransport {
    private val http = XmuHttpClients.query.newBuilder().retryOnConnectionFailure(false).build()
    override fun execute(path: String, form: Map<String, String>?, cookie: String): RankResponse {
        require(path.startsWith("/jwapp/sys/jdfpm/") && !path.contains(".."))
        val builder = Request.Builder().url("https://jw.xmu.edu.cn$path")
            .header("Cookie", cookie).header("Referer", "https://jw.xmu.edu.cn/jwapp/sys/jdfpm/*default/index.do")
            .tag(NetworkOperation::class.java, NetworkOperation.SCORES)
        if (form != null) builder.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
        return http.newCall(builder.build()).execute().use { response ->
            val body = response.body ?: throw IOException("排名接口没有返回内容")
            // A GPA certificate is about 338KB. Bound both chunked and declared response bodies.
            require(body.contentLength() <= 5 * 1024 * 1024) { "证明文件过大，已停止读取" }
            val bytes = body.byteStream().use { it.readBytesLimited(5 * 1024 * 1024) }
            RankResponse(response.code, response.headers.toMultimap(), bytes)
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        check(out.size() + n <= limit) { "证明文件过大，已停止读取" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal class RankAuthExpired : IllegalStateException("教务登录已过期，请重新获取")
internal class RankSessionChanged : IllegalStateException("账号会话已改变，操作已停止")
internal class RankSubmitUncertain : IllegalStateException("申请可能已提交。请继续查询结果，勿重复申请。")
internal class RankSubmitRejected : IllegalStateException("教务未受理本次申请，请稍后重试")

internal class XmuRankClient(
    cookie: String,
    private val renewSession: (String) -> String,
    private val active: () -> Boolean,
    private val transport: RankTransport = RankOkHttpTransport(),
) {
    private val jar = XmuScoreCookieJar().apply { seed(cookie) }
    private var renewed = false
    fun cookieHeader(): String = jar.header()

    private fun request(path: String, form: Map<String, String>? = emptyMap(), submit: Boolean = false): RankResponse {
        if (!active()) throw RankSessionChanged()
        val response = transport.execute(path, form, jar.header())
        // Shared academic cookie jar handles Set-Cookie in the same way as existing modules.
        jar.read(response.headers)
        val text = if (response.bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) "" else response.bytes.toString(Charsets.UTF_8)
        val query = QueryHttpResponse("https://jw.xmu.edu.cn$path", response.code,
            response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull(), text, response.headers)
        if (JwSessionMarkers.isAuthFailure(response.code, query)) {
            if (!submit && !renewed && active()) {
                renewed = true
                jar.seed(renewSession(jar.header()))
                return request(path, form)
            }
            throw RankAuthExpired()
        }
        check(response.code in 200..299) { "教务接口暂不可用（HTTP ${response.code}）" }
        return response
    }

    fun records(): List<RankRecord> {
        val root = json(request("/jwapp/sys/jdfpm/api/jdjs/getJdjssq.do"))
        val data = root.optJSONObject("datas")?.opt("getJdjssq")
            ?: error("申请记录格式改变，已停止操作")
        val result = mutableListOf<RankRecord>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
                is JSONObject -> {
                    // 申请记录的层级在不同部署里不一致：WID 可能在包装层，也可能在明细层（ZX），
                    // 名次字段同理。整棵子树摊平后取字段，两个方向都能命中。
                    val direct = value.keys().asSequence().toSet()
                    val fields = flattenSubtree(value)
                    val id = fields["WID"].orEmpty()
                    if (id.isBlank() || direct.none { it in RECORD_MARKERS }) {
                        value.keys().forEach { visit(value.opt(it)) }
                    } else {
                        result += RankRecord(
                            id,
                            parseCountOrRank(fields["CYJSZYRS"]) ?: 0,
                            fields["CJFWWID"].orEmpty(),
                            fields["JSSJ"].orEmpty().ifBlank { fields["SQSJ"].orEmpty() },
                            RANK_FIELDS.firstNotNullOfOrNull { key -> parseCountOrRank(fields[key]) },
                            RANK_FIELDS.joinToString("、") { key ->
                                "$key=${fields[key].orEmpty().ifBlank { "无此字段" }}"
                            },
                            isDefunctRecord(fields),
                        )
                    }
                }
            }
        }
        visit(data)
        return result.distinctBy { it.id }
    }

    /**
     * 把一条申请记录（含嵌套明细节点）摊平成「字段名 → 原始值」，浅层优先。
     * 名次字段在这里只作兜底：证明正文带排名句时以正文为准，正文缺失才回落到这些结构化字段。
     */
    private fun flattenSubtree(root: JSONObject): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        val queue = ArrayDeque<JSONObject>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.keys().forEach { key ->
                val value = node.opt(key)
                when {
                    value is JSONObject -> queue += value
                    value is JSONArray -> Unit
                    key !in fields -> fields[key] = node.optRealString(key)
                }
            }
        }
        return fields
    }

    /** 服务端可能给 "12"、"12.0"、"12/146"、"*"：取第一段数字，星号与 0 视为无值。 */
    private fun parseCountOrRank(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "*") return null
        return Regex("\\d+").find(text)?.value?.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * 教务用 SFSX（是否失效）/ SFYX（是否有效）标记每条申请的状态，实测两者互补。
     * 只在明确给出时才判失效：换部署、字段缺失或值不认识时一律当有效 ——
     * 判死的代价是每次都重新申请（更招风控），比用一份旧结果更糟。
     */
    private fun isDefunctRecord(fields: Map<String, String>): Boolean {
        fields["SFYX"]?.trim()?.lowercase()?.let { return it == "false" || it == "0" }
        fields["SFSX"]?.trim()?.lowercase()?.let { return it == "true" || it == "1" }
        return false
    }

    fun ranges(): List<RankRange> {
        val root = json(request("/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do"))
        val rows = root.optJSONObject("datas")?.optJSONObject("cxxskxcjfw")?.optJSONArray("rows")
            ?: root.optJSONArray("rows") ?: error("无法识别可选成绩范围，未提交申请")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull {
            val id = it.optRealString("WID"); val name = it.optRealString("XSMC")
            // 区间与含否校选是服务端结构化字段（JSXNXQ / SFXGXK），不要从名字里猜：
            // XSMC 的分隔符不稳定（「含校选」前有时带空格有时不带）。
            if (id.isBlank() || name.isBlank()) null else RankRange(
                id, name, it.optRealString("JSXNXQ"),
                when (it.optRealString("SFXGXK_DISPLAY")) { "是" -> true; "否" -> false; else -> null },
                rankSemesterCount(it.optRealString("JSXNXQDM"), it.optRealString("JSXNXQ")),
            )
        }.distinctBy { it.id }.also { check(it.isNotEmpty()) { "当前没有可申请的成绩范围" } }
    }

    fun submit(rangeId: String) {
        try {
            val root = json(request("/jwapp/sys/jdfpm/api/jdjs/addJdjssq.do", mapOf("CJFWWID" to rangeId), submit = true))
            if (root.has("success") && root.opt("success")?.toString() == "false") throw RankSubmitRejected()
            // Even an unfamiliar success response is reconciled by new WID, never blindly resubmitted.
        } catch (changed: RankSessionChanged) { throw changed }
        catch (rejected: RankSubmitRejected) { throw rejected }
        catch (error: Exception) { throw RankSubmitUncertain() }
    }

    fun certificate(id: String): ByteArray {
        val encoded = URLEncoder.encode(id, "UTF-8")
        // 参数沿用一直在用的组合，不做推测性改动：
        // 至今的版本能稳定从证明正文里读出 GPA 名次，说明 SFXYPM=1 时服务端会把
        // 「GPA 与专业排名」整段写进证明；取 0 时该段整段消失（正文只剩计算方法）。
        // 曾按第三方复刻工程把参数改成 type=1&DYNR=2&SFXYPM=0，实测导出件
        // 有个人信息但没有任何 GPA 数值，已回退。
        val bytes = request("/jwapp/sys/jdfpm/api/jdjs/printZm.do?wid=$encoded&type=0&DYNR=0&SFXYPM=1", null).bytes
        check(bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
            "教务尚未返回有效 PDF。可继续查询本次结果。"
        }
        return bytes
    }

    private fun json(response: RankResponse): JSONObject = try {
        JSONObject(response.bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
    } catch (e: Exception) { throw IllegalStateException("排名接口返回格式异常", e) }
}
