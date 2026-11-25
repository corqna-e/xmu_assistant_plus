package com.xmu.assistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class RankSectionState(
    private val context: Context,
    private val gate: RequestGate,
    private val epoch: SessionEpoch,
    private val owner: SessionOwner,
    private val scope: CoroutineScope,
    private val loggedIn: () -> Boolean,
    private val cookie: () -> String,
    private val username: () -> String,
    private val password: () -> String,
    private val transitioning: () -> Boolean,
    private val academicCookie: () -> String,
    private val saveCookie: (String) -> Unit,
    private val readCache: () -> String,
    private val writeCache: (String, () -> Boolean) -> Boolean,
    private val show: (String) -> Unit,
    initialScores: List<XmuScoreRecord>,
) {
    /** 等待新记录的轮询节奏：没有可兜底的旧记录时要等满全部机会。 */
    private val pollDelays = listOf(3000L, 3000L, 8000L, 8000L, 8000L, 8000L, 8000L)

    /** 已有可兜底记录时的缩短节奏：新记录多半不会到来，尽量少让用户干等。 */
    private val fallbackPollDelays = listOf(3000L, 5000L, 8000L)

    /** 任意两次提交的最小间隔：教务对短时间重复申请有风控，客户端先挡一层。 */
    private val anySubmitGap = 30_000L

    /** 同一范围的重复提交间隔：成绩变了要重算，但不该被连点成「刷排名」。 */
    private val sameRangeSubmitGap = 10 * 60_000L

    private fun throttleMessage(waitMs: Long, sameRange: Boolean): String {
        val seconds = (waitMs + 999) / 1000
        val reason = if (sameRange && seconds > 60) "同一范围刚申请过，请约 ${(seconds + 59) / 60} 分钟后再试。"
        else "申请过于频繁，请 $seconds 秒后再试。"
        // 只在真正被节流时才说这句；平时卡片不常驻，避免用户面对一堆读不完的说明。
        return "$reason\n短时间内的重复提交会被客户端拦截，请按需操作。"
    }
    var cache by mutableStateOf(rankCacheFromJson(readCache(), rankDigest(username())))
        private set
    var loading by mutableStateOf(false)
        private set
    var stage by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set

    /**
     * 中性提示（不是错误）：例如「所选范围已有计算记录，已直接读取复用」。
     * 这类信息放在卡片内、与警示同一位置同一字号，避免两条红字上下挤在一起。
     */
    var notice by mutableStateOf("")
        private set
    var ranges by mutableStateOf(emptyList<RankRange>())
        private set
    var selectedRange by mutableStateOf(
        if (cache.owner == rankDigest(username())) cache.selectedRangeId else "",
    )
    private var fingerprint by mutableStateOf(rankScoreFingerprint(initialScores))

    init {
        migrateLegacyPdf()
        backfillOverallSnapshot()
    }

    /**
     * 老缓存只有一份 result：它若正好是「入学以来 · 不含校选」，直接当作总体名次用，
     * 用户不必为了在成绩页看到名次再进一次排名页。只改内存，下一次落盘自然带上。
     */
    private fun backfillOverallSnapshot() {
        if (cache.overall != null) return
        val only = cache.result ?: return
        if (isOverallWithoutElectives(only.term, only.withElectives, only.rangeName)) {
            cache = cache.copy(overall = only)
        }
    }

    /**
     * 旧版本把整份证明 base64 写进 prefs。这里把它落成文件、字段换成文件名再写回：
     * 迁移失败（解不开）也把字段清空，不能让 prefs 一直背着几 MB 的字符串。
     */
    private fun migrateLegacyPdf() {
        val legacy = cache.result?.takeIf { it.pdfLegacy } ?: return
        val session = epoch.snapshot(owner, cookie())
        scope.launch(Dispatchers.Main) {
            val name = withContext(Dispatchers.IO) { RankPdfStore.migrateLegacy(context, legacy) }
            if (!epoch.isCurrent(session)) return@launch
            val current = cache.result?.takeIf { it.recordId == legacy.recordId } ?: return@launch
            val next = cache.copy(result = current.copy(pdfFile = name.orEmpty(), pdfLegacy = false))
            withContext(Dispatchers.IO) {
                runCatching { writeCache(rankCacheToJson(next)) { epoch.isCurrent(session) } }
            }
            if (epoch.isCurrent(session)) cache = next
        }
    }

    /** 记住选中的范围：下次进页面/刷新列表后仍停在同一枚芯片上。 */
    fun selectRange(id: String) {
        if (selectedRange == id) return
        selectedRange = id
        val next = cache.copy(selectedRangeId = id)
        cache = next
        val session = epoch.snapshot(owner, cookie())
        scope.launch(Dispatchers.IO) {
            runCatching { writeCache(rankCacheToJson(next)) { epoch.isCurrent(session) } }
        }
    }

    /**
     * 拉到服务端范围列表后恢复选中： remembered ID 仍在列表里就用它；
     * 已被教务移除则退回单范围自动选中，多范围留空让用户重新选。
     */
    private fun restoreSelectedRange(choices: List<RankRange>) {
        if (choices.isEmpty()) return
        val remembered = cache.selectedRangeId
        when {
            remembered.isNotBlank() && choices.any { it.id == remembered } -> selectedRange = remembered
            selectedRange.isNotBlank() && choices.none { it.id == selectedRange } -> selectedRange = ""
        }
        if (selectedRange.isBlank() && choices.size == 1) selectedRange = choices.first().id
    }
    val stale get() = cache.result?.let { it.fingerprint != fingerprint } == true

    /**
     * 成绩页右下角的专业排名：固定取「入学以来 · 不含校选」这一档，
     * 与排名页当前选中的范围无关。没取过这一档时为 null，界面显示占位符。
     */
    val overallPosition: Int? get() = cache.overall?.position
    val label get() = when {
        loading -> stage
        cache.pending != null -> "等待本次结果"
        stale -> "待更新"
        cache.result?.position != null -> "${cache.result!!.position} / ${cache.result!!.participants}"
        cache.result != null -> "证明已获取 · 未含排名"
        else -> "尚未获取"
    }

    fun onScoresChanged(records: List<XmuScoreRecord>, complete: Boolean) {
        // Partial semester failures must not mark an otherwise matching result as obsolete.
        if (complete) fingerprint = rankScoreFingerprint(records)
    }

    fun clearAll() {
        cache = RankCache(rankDigest(username()))
        writeCache("", { true })
        // 证明文件属于个人数据，换号/登出时一并删掉，别留给下一个账号。
        scope.launch(Dispatchers.IO) { runCatching { RankPdfStore.clear(context) } }
        ranges = emptyList(); selectedRange = ""; loading = false; stage = ""; error = ""; notice = ""
        fingerprint = rankScoreFingerprint(emptyList())
    }

    fun clearLoadingState() { loading = false }

    /**
     * 进入排名页即拉一次可选范围，让「计算范围」直接展开。
     * 只读取、不提交任何申请；失败静默（用户点按钮时 fetch 会重新拉并报错），
     * 避免刚进页面就弹出一串读不完的提示。与 fetch 共用 rankings 门，不会并发。
     */
    fun loadRanges() {
        if (ranges.isNotEmpty() || loading || !loggedIn() || cookie().isBlank()) return
        if (!gate.tryStart("rankings")) return
        val account = username(); val pass = password()
        val session = epoch.snapshot(owner, cookie())
        val active = { epoch.isCurrent(session) }
        scope.launchGatedRequest(gate, "rankings", Dispatchers.Main) {
            try {
                val client = XmuRankClient(academicCookie(), { old ->
                    XmuScoreAutoQueryClient(account, pass, old, mayRelogin = active).forceAcademicLogin()
                }, active)
                val choices = withContext(Dispatchers.IO) { client.ranges() }
                check(active()) { ErrorCodes.SESSION_CHANGED }
                if (active()) {
                    ranges = choices
                    restoreSelectedRange(choices)
                }
                withContext(NonCancellable) { runCatching { saveCookie(client.cookieHeader()) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 预加载失败不影响使用：fetch 会再拉一次并给出真实原因。
            }
        }
    }

    /**
     * [auto] 仅用于进页面时首次自动获取：登录未就绪 / 正在切号这类暂时条件静默跳过，
     * 不弹 Toast 打扰；用户随后点任意按钮会正常提示。真正的网络失败仍写进 [error]
     * 显示在卡内，自动获取失败时用户看得到原因并用「获取证明」小键重试。
     */
    fun fetch(force: Boolean = false, auto: Boolean = false) {
        if (transitioning()) { if (!auto) show("登录处理中，请稍候"); return }
        if (!loggedIn() || cookie().isBlank()) { if (!auto) show("请先登录"); return }
        if (loading || !gate.tryStart("rankings")) return
        val account = username(); val pass = password()
        val session = epoch.snapshot(owner, cookie())
        val active = { epoch.isCurrent(session) }
        val startFingerprint = fingerprint
        val stored = if (cache.owner == rankDigest(account)) cache else RankCache(rankDigest(account))
        // 有未完成的申请时只该继续查询，「强制重算」不能把等待中的申请顶掉再提一笔。
        val forceSubmit = force && stored.pending == null
        loading = true; stage = "正在查询教务记录"; error = ""; notice = ""
        // 客户端构造放进受保护的协程：原先在拿到门之后、协程启动之前构造，
        // 构造抛异常会直接逃出入口，"rankings" 门永久占用、后续查询全被判为进行中。
        // 门由任务完成回调释放，启动前取消也能归还。
        scope.launchGatedRequest(gate, "rankings", Dispatchers.Main) {
            val client = try {
                XmuRankClient(academicCookie(), { old ->
                    XmuScoreAutoQueryClient(account, pass, old, mayRelogin = active).forceAcademicLogin()
                }, active)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (active()) {
                    error = failure.message ?: "排名客户端初始化失败，请重试"
                    loading = false
                    stage = ""
                }
                return@launchGatedRequest
            }
            var working = stored
            suspend fun update(value: RankCache) {
                // 用户可能在请求期间点过芯片：写回时以当前选中为准，别用发起时的旧值覆盖。
                val json = rankCacheToJson(value.copy(selectedRangeId = selectedRange))
                val saved = withContext(Dispatchers.IO) { writeCache(json, active) }
                check(saved) { "无法保存申请状态，已停止操作" }
                ensureActive()
                check(active()) { ErrorCodes.SESSION_CHANGED }
                working = value.copy(selectedRangeId = selectedRange); cache = working
            }
            suspend fun finishWith(record: RankRecord, pending: RankPending, reuseNote: String?) {
                stage = "正在下载证明"
                val pdf = withContext(Dispatchers.IO) { client.certificate(record.id) }
                stage = "正在解析排名"
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        PDDocument.load(pdf).use { document ->
                            check(document.numberOfPages in 1..10)
                            PDFTextStripper().getText(document)
                        }
                    }.getOrNull()
                }
                // 证明正文必带学号。连学号都没有，说明教务这次没渲染出数据（空模板），
                // 存下来只会把"已保存"变成假象，直接放弃并让用户重试。
                if (text != null && !Regex("\\d{10,16}").containsMatchIn(text)) {
                    stage = ""
                    error = "教务返回的是空白证明（无学号与个人信息），已放弃保存。多为该次教务计算尚未生成，稍后重试。"
                    return
                }
                val numbers = text?.let { rankNumbersFromText(it) }
                // 复用旧记录时沿用该记录原有的成绩指纹（若已持有同一记录），让"待更新"判定仍然可信
                val fingerprintForResult = cache.result?.takeIf { it.recordId == record.id }?.fingerprint
                    ?: pending.fingerprint
                // 名次以证明正文为准（SFXYPM=1 时服务端把「专业人数 + GPA 名次」写进正文，
                // 一直如此）；正文没有这句时才退回计算记录的结构化字段。
                val position = numbers?.position ?: record.declaredRank
                val total = numbers?.participants ?: record.participants.takeIf { it > 0 }
                val obtained = System.currentTimeMillis()
                // 证明落成文件，prefs 只留文件名：名次等解析结果独立保存，删掉文件也不影响显示。
                val pdfFile = withContext(Dispatchers.IO) {
                    RankPdfStore.write(context, record.id, obtained, pdf)
                }
                val result = RankResult(record.id, position, total, pending.range.name,
                    pending.requestedAt, obtained, record.calculatedAt, fingerprintForResult,
                    pdfFile,
                    pending.range.term, pending.range.withElectives, pending.range.semesters)
                // 「入学以来 · 不含校选」另存一份：成绩页固定展示这一档，不随当前选择漂移。
                val overallNext = if (isOverallWithoutElectives(
                        pending.range.term, pending.range.withElectives, pending.range.name,
                    )
                ) result else working.overall
                update(working.copy(result = result, pending = null, overall = overallNext))
                stage = ""
                if (position == null) {
                    error = "证明已保存；教务记录未返回名次（${record.rankFields}），界面不显示排名。"
                }
                // 复用旧记录属于正常路径，不当错误渲染（两条红字挤一起既不美观也分不清轻重）
                notice = reuseNote.orEmpty()
            }
            try {
                if (working.pending == null) {
                    val choices = withContext(Dispatchers.IO) { client.ranges() }
                    check(active()) { ErrorCodes.SESSION_CHANGED }
                    ranges = choices
                    restoreSelectedRange(choices)
                    val choice = choices.singleOrNull() ?: choices.singleOrNull { it.id == selectedRange }
                    if (choice == null) {
                        stage = "请先选择范围"
                        return@launchGatedRequest
                    }
                    val snapshot = withContext(Dispatchers.IO) { client.records() }
                    check(active()) { ErrorCodes.SESSION_CHANGED }
                    // 先探测：所选范围已有有效记录就直接复用并说明原因，绝不重复提交
                    // （教务对已存在的记录会忽略新申请，先申请再等只会白等）。
                    // 强制重算例外：成绩已变时复用旧记录会让「待更新」永远消不掉，
                    // 这条路径必须真的提交一次。
                    val existing = if (forceSubmit) null else latestCompleteRecordForRange(snapshot, choice.id)
                    if (existing != null) {
                        stage = "已有教务计算记录"
                        finishWith(
                            existing,
                            RankPending(snapshot.map { it.id }.toSet(), choice, System.currentTimeMillis(), startFingerprint),
                            reuseNote = "所选范围已有教务计算记录（成绩数据截至 ${existing.calculatedAt.ifBlank { "未知时间" }}），已直接读取复用，未重复提交申请。",
                        )
                        return@launchGatedRequest
                    }
                    // 节流：教务对短时间重复申请有风控，客户端先自己拦住并说清还要等多久。
                    val now = System.currentTimeMillis()
                    val sameRange = working.lastSubmitRangeId == choice.id
                    val cooldown = if (sameRange) sameRangeSubmitGap else anySubmitGap
                    val waited = now - working.lastSubmitAt
                    if (working.lastSubmitAt > 0 && waited >= 0 && waited < cooldown) {
                        stage = ""
                        error = throttleMessage(cooldown - waited, sameRange)
                        return@launchGatedRequest
                    }
                    val before = snapshot.map { it.id }.toSet()
                    val pending = RankPending(before, choice, now, startFingerprint)
                    // Persist BEFORE the POST. Process death or a lost response must never cause an automatic resubmit.
                    update(working.copy(pending = pending, lastSubmitAt = now, lastSubmitRangeId = choice.id))
                    stage = "正在提交申请"
                    try { withContext(Dispatchers.IO) { client.submit(choice.id) } }
                    catch (e: RankSubmitRejected) { update(working.copy(pending = null)); throw e }
                }

                stage = "正在等待教务计算"
                var fallback: RankRecord? = null
                var ambiguity: String? = null
                var attempt = 0
                while (true) {
                    ensureActive(); check(active()) { ErrorCodes.SESSION_CHANGED }
                    val pending = working.pending ?: error("申请状态缺失")
                    val records = withContext(Dispatchers.IO) { client.records() }
                    // 最后一次查询也可能跨过换号/清空；在解释结果或显示等待提示前重新验世代。
                    ensureActive(); check(active()) { ErrorCodes.SESSION_CHANGED }
                    // 多笔新申请并存时无法归因本次申请，但不妨碍兜底采用本范围最近记录
                    val record = try { identifyRankRecord(records, pending) }
                    catch (e: IllegalStateException) { ambiguity = e.message; null }
                    if (record != null) {
                        if (pending.recordId.isBlank()) update(working.copy(pending = pending.copy(recordId = record.id)))
                        if (record.participants > 0) {
                            finishWith(record, pending, reuseNote = null)
                            return@launchGatedRequest
                        }
                    }
                    latestCompleteRecordForRange(records, pending.range.id)?.let { fallback = it }
                    val delays = if (fallback != null) fallbackPollDelays else pollDelays
                    if (attempt >= delays.size) break
                    delay(delays[attempt])
                    attempt++
                }
                val adopt = fallback
                if (adopt != null) {
                    // 服务端未生成新记录（所选范围已有有效记录时申请会被合并/忽略）：采用最近一次
                    // 完整计算，明确标注来源，避免用户无限等待；下次点击仍会重新尝试申请。
                    finishWith(adopt, working.pending ?: error("申请状态缺失"),
                        reuseNote = "教务未生成新记录（同一批次内重复申请不会产生新交付），仍采用所选范围最近一次结果（成绩数据截至 ${adopt.calculatedAt.ifBlank { "未知时间" }}）。")
                    return@launchGatedRequest
                }
                error = ambiguity ?: "本次教务计算尚未完成。点击继续查询，不会重复申请。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (active()) error = if (e is java.io.IOException) "网络连接失败；若申请已提交，请继续查询本次结果。" else e.message ?: "获取失败，请稍后重试"
            } finally {
                withContext(NonCancellable) {
                    if (active()) {
                        // 小值在主线程接纳后立即提交，与登出/换号清理保持同线程顺序；
                        // 保存失败不能连累 loading 与门的释放。
                        try {
                            saveCookie(client.cookieHeader())
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = "教务会话保存失败，请重试"
                        } finally {
                            loading = false
                        }
                    }
                    // 门由 launchGatedRequest 的完成回调统一释放。
                }
            }
        }
    }

    fun abandonPending() = mutateCache { it.copy(pending = null) }

    private fun mutateCache(transform: (RankCache) -> RankCache) {
        if (loading || !gate.tryStart("rankings")) return
        val session = epoch.snapshot(owner, cookie())
        val next = transform(cache)
        loading = true; stage = "正在保存"
        scope.launchGatedRequest(gate, "rankings", Dispatchers.Main) {
            try {
                val saved = withContext(Dispatchers.IO) { writeCache(rankCacheToJson(next)) { epoch.isCurrent(session) } }
                if (epoch.isCurrent(session)) {
                    if (saved) { cache = next; error = "" } else error = "本地保存失败，请重试"
                }
            } finally {
                if (epoch.isCurrent(session)) {
                    loading = false
                    stage = ""
                }
            }
        }
    }

    /**
     * 导出证明 PDF 到统一导出目录「下载/xmu助手plus」。
     *
     * 不再弹系统选目录对话框：长图、证明、课件统一一个落点，用户不必每次选位置。
     * Android 8/9 写不了公共目录，落到应用专属目录并在提示里如实说明。
     *
     * 本地只存文件：有文件直接写出去；文件丢了（系统清理、旧版本迁移失败）时按记录号
     * **只读**补取一次同一份证明，绝不在导出里提交申请。
     */
    fun exportPdf(expectedRecord: String) {
        val result = cache.result
        if (result == null || result.recordId != expectedRecord) { show("证明已改变，请重新导出"); return }
        if (!loggedIn() || cookie().isBlank()) { show("请先登录"); return }
        if (loading || !gate.tryStart("rankings")) return
        val account = username(); val pass = password()
        val session = epoch.snapshot(owner, cookie())
        val active = { epoch.isCurrent(session) }
        // 文件名带范围与具体时间：范围让多份证明分得清，时间让同范围多次导出分得清。
        // 用导出时刻而不是 result.obtainedAt —— 后者对同一份结果是固定值，连导两次仍会撞名，
        // 只能靠 MediaStore 加「(1)」，等于没解决。
        // 变量名不能叫 scope：会遮蔽类的 CoroutineScope，下面的 scope.launchGatedRequest 就编译不过。
        val rangePart = listOf(rankTermOf(result), rankElectiveOf(result))
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .replace(Regex("""[\\/:*?"<>|\s]"""), "")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val name = listOf("绩点证明", rangePart, stamp).filter { it.isNotEmpty() }.joinToString("-") + ".pdf"
        loading = true; stage = "正在准备导出"; error = ""; notice = ""
        scope.launchGatedRequest(gate, "rankings", Dispatchers.Main) {
            try {
                var bytes = withContext(Dispatchers.IO) { RankPdfStore.read(context, result.pdfFile) }
                if (bytes == null) {
                    stage = "正在取回文件"
                    val client = XmuRankClient(academicCookie(), { old ->
                        XmuScoreAutoQueryClient(account, pass, old, mayRelogin = active).forceAcademicLogin()
                    }, active)
                    bytes = withContext(Dispatchers.IO) { client.certificate(result.recordId) }
                    val stored = withContext(Dispatchers.IO) {
                        RankPdfStore.write(context, result.recordId, result.obtainedAt, bytes)
                    }
                    check(active()) { ErrorCodes.SESSION_CHANGED }
                    cache.result?.takeIf { it.recordId == result.recordId }?.let { current ->
                        val next = cache.copy(result = current.copy(pdfFile = stored, pdfLegacy = false))
                        if (withContext(Dispatchers.IO) { writeCache(rankCacheToJson(next), active) }) cache = next
                    }
                    withContext(NonCancellable) { runCatching { saveCookie(client.cookieHeader()) } }
                }
                stage = "正在写入文件"
                check(active()) { "账号已改变，导出已停止" }
                val path = withContext(Dispatchers.IO) {
                    ExportStorage.saveBytes(context, name, "application/pdf", bytes)
                        ?: error("无法写入 ${ExportStorage.describe()}")
                }
                // 路径里的「绩点证明」是真实文件名，隐私模式下必须照原样报给用户。
                show("已保存到 ${privacyLiteral(path)}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (active()) error = failure.message ?: "导出失败"
            } finally {
                if (active()) { loading = false; stage = "" }
            }
        }
    }
}
