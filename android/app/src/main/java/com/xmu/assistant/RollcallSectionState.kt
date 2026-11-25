package com.xmu.assistant

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 签到情况模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态；
 *会话过期时经 setPendingSessionRetry 挂起一次性安全续登（recoverExpiredModule 处理）。
 * - 请求统一走互斥门（RequestGate）：进行中签到 "rollcall"、最近十次 "rollcall_history"。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class RollcallSectionState(
    private val activity: Activity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val showWarning: (String) -> Unit = show,
    private val showError: (String) -> Unit = show,
    private val requireLogin: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    /** 当前登录用户名：本人明细匹配（user_no==username 或 is_current_user）用。 */
    private val username: () -> String,
    /** 调用方已加载的课程列表（复用免重复拉取；空则客户端自行兜底探测）。 */
    private val coursesProvider: () -> List<CourseSummary>,
    private val setPendingSessionRetry: (ModuleReadRetry) -> Unit,
    eventsInitial: List<RollcallEvent> = emptyList(),
    updatedAtMillisInitial: Long = 0L,
    /** 历史客户端工厂（测试注入假传输；生产默认 OkHttp 传输，签名与 CoursewareClient 同风格）。 */
    private val createHistoryClient: (String) -> RollcallHistoryClient = { cookieHeader ->
        RollcallHistoryClient(cookieHeader, OkHttpQueryTransport(), { size -> Executors.newFixedThreadPool(size) })
    },
    private val createEngine: (String) -> RollcallEngine = { cookieHeader ->
        val coordinates = RadarCoordinateStore(activity)
        RollcallEngine(cookieHeader, radarCoordinateProvider = { event ->
            coordinates.preferredFor(event.courseId, event.courseTitle)
        })
    },
) {
    /** 最近一次拉到的签到事件列表。 */
    var events by mutableStateOf(eventsInitial)
        private set

    /** 最近一次签到刷新成功的时间戳（毫秒）。 */
    var updatedAtMillis by mutableStateOf(updatedAtMillisInitial)
        private set

    var loading by mutableStateOf(false)
        private set

    var refreshError by mutableStateOf("")
        private set

    // ---- 最近十次签到（历史区块，独立门 "rollcall_history"）----

    var historyItems by mutableStateOf<List<RollcallHistoryItem>>(emptyList())
        private set

    var historyLoading by mutableStateOf(false)
        private set

    var historyError by mutableStateOf("")
        private set

    var historyUpdatedAtMillis by mutableStateOf(0L)
        private set

    /** 是否已成功加载过一次（首次进入自动拉取的判定依据）。 */
    var historyLoaded by mutableStateOf(false)
        private set

    /** 强制刷新签到列表。 */
    fun refresh(alreadyRetriedAfterRecovery: Boolean = false, showFeedback: Boolean = true): Boolean {
        if (!requireLogin()) return false
        if (!requestGate.tryStart("rollcall")) return false
        refreshError = ""
        loading = true
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountId = username()
        scope.runModuleRequest(
            requestGate = requestGate,
            gateKey = "rollcall",
            acceptsResult = { sessionEpoch.accepts(session, cookieHeader(), loggedIn()) },
            ioWork = { createEngine(session.cookieHeader).pollWithDetails(accountId) },
            onResult = { result ->
                result.onSuccess { list ->
                    refreshError = ""
                    events = list
                    updatedAtMillis = System.currentTimeMillis()
                    if (showFeedback) show(if (list.isEmpty()) "暂未检测到签到" else "已读取 ${list.size} 条签到")
                }
                result.onFailure { error ->
                    if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                        refreshError = "登录已过期，正在安全续登"
                        setPendingSessionRetry(ModuleReadRetry(ModuleReadRequest.ROLLCALL))
                    } else {
                        refreshError = refreshFailureMessage(error)
                        if (showFeedback) showError("签到读取失败：$refreshError")
                    }
                }
            },
            releaseLoading = { loading = false },
        )
        return true
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        loading = false
        historyLoading = false
    }

    // ---- 手动「立即进行当前签到」 ----

    /** 强制签到进行中：按钮据此置灰并显示「正在签到…」，同时防重复点击。 */
    var forceAnswering by mutableStateOf(false)
        private set

    /**
     * 无视策略、监控与自动签到开关，把当前进行中的签到全部提交掉。
     *
     * 与自动监控只共用引擎，不复用 processRollcallMonitorPoll：那里有门槛、
     * 开关、去重和轮询间隔，而这里是用户明确要求的「现在就签，签完为止」——
     * 一次点击里「拉列表 → 提交 → 再拉列表」循环到没有待提交项为止。
     * 数字码与雷达坐标仍走引擎既有逻辑：雷达没配课程坐标会明确报错，不猜位置。
     *
     * [onNoActive] 在「点击时就没有进行中的签到」时回调 —— 由 UI 侧去打开监控与
     * 自动签到（那属于首页的开关状态，不在本模块里）。
     */
    fun forceAnswerNow(onNoActive: () -> Unit) {
        if (forceAnswering) return
        if (!requireLogin()) return
        if (!requestGate.tryStart("rollcall_force")) {
            show("上一次立即签到还在进行，请稍候")
            return
        }
        forceAnswering = true
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountId = username()
        scope.runModuleRequest(
            requestGate = requestGate,
            gateKey = "rollcall_force",
            acceptsResult = { sessionEpoch.accepts(session, cookieHeader(), loggedIn()) },
            ioWork = { forceAnswerAll(session, accountId) },
            onResult = { result ->
                result.onSuccess { report ->
                    val unfinished = report.unfinished.entries.map { "${it.key}：${it.value}" }
                    when {
                        !report.hadActive -> show("当前没有进行中的签到")
                        unfinished.isEmpty() ->
                            show(if (report.answered > 0) "已完成 ${report.answered} 项签到" else "当前签到已完成")
                        report.answered > 0 -> showWarning(
                            "已提交 ${report.answered} 项，${unfinished.size} 项未完成：${unfinished.first()}",
                        )
                        else -> showWarning("签到未完成：${unfinished.first()}")
                    }
                    // 没有进行中的签到 → 打开监控与自动签到，由 UI 侧执行
                    if (!report.hadActive) onNoActive()
                    // 结果如何都重拉一次列表，卡片状态与刚提交的结果对齐
                    refresh(showFeedback = false)
                }
                result.onFailure { error ->
                    if (sessionExpiryAction(error, false) == SessionExpiryAction.RECOVER_ONCE) {
                        refreshError = "登录已过期，正在安全续登"
                        setPendingSessionRetry(ModuleReadRetry(ModuleReadRequest.ROLLCALL))
                    } else {
                        showError("立即签到失败：${refreshFailureMessage(error)}")
                    }
                }
            },
            releaseLoading = { forceAnswering = false },
        )
    }

    /**
     * 一次点击把进行中的签到全部提交：拉列表 → 提交 → 再拉列表，直到没有待提交项。
     * 已提交过的 id 不再提交（平台回执有延迟，重拉时仍可能显示进行中）。
     */
    private fun forceAnswerAll(session: SessionRequest, accountId: String): ForceAnswerReport {
        val engine = createEngine(session.cookieHeader)
        var hadActive = false
        var answered = 0
        val handled = mutableSetOf<String>()
        val unfinished = linkedMapOf<String, String>()
        var round = 0
        while (round < FORCE_ANSWER_MAX_ROUNDS) {
            round += 1
            val events = engine.pollWithDetails(accountId)
            if (round == 1) hadActive = events.isNotEmpty()
            val pending = events.filter { it.id !in handled && it.awaitingMyAnswer() }
            if (pending.isEmpty()) break
            var progressed = false
            for (event in pending) {
                val label = event.courseTitle.ifBlank { event.type }
                if (event.type != "数字签到" && event.type != "雷达签到") {
                    unfinished[label] = "该类型需手动完成"
                    continue
                }
                var submitted = false
                var reason = ""
                var attempt = 0
                while (!submitted && attempt < FORCE_ANSWER_ATTEMPTS) {
                    if (attempt > 0) Thread.sleep(FORCE_ANSWER_RETRY_GAP_MILLIS)
                    attempt += 1
                    val outcome = runCatching {
                        // 与后台监控共用提交门：监控刚提交成功的同一条记录不再重复写。
                        RollcallAnswerGate.answerOnce(
                            accountId = accountId,
                            cookieHeader = session.cookieHeader,
                            rollcallId = event.id,
                            canAnswer = { sessionEpoch.isCurrent(session) },
                            answer = { engine.answer(event) },
                        )
                    }
                    submitted = outcome.getOrDefault(false)
                    if (!submitted) {
                        val error = outcome.exceptionOrNull()
                        reason = when {
                            error is RollcallAnswerNotSubmittedException -> error.message.orEmpty()
                            error != null -> friendlyMessage(error)
                            else -> engine.lastRadarFailure.orEmpty()
                        }
                        // 本地校验失败（未配坐标/坐标无效）重试也不会变，直接收手
                        if (error is RollcallAnswerNotSubmittedException) break
                    }
                }
                if (submitted) {
                    answered += 1
                    handled += event.id
                    unfinished.remove(label)
                    progressed = true
                } else {
                    unfinished[label] = reason.ifBlank { "平台未确认提交结果" }
                }
            }
            // 一轮下来一条都没提交成功：平台拒绝/坐标缺失，再循环也是同样结果
            if (!progressed) break
        }
        return ForceAnswerReport(hadActive, answered, unfinished)
    }

    /**
     * 刷新最近十次签到（SWR：先渲缓存再后台刷新原位更新，失败保留缓存只报错）。
     * 独立互斥门 "rollcall_history"，与进行中签到的 "rollcall" 互不阻塞，
     * 顶部「刷新」按钮可让两块并行。
     */
    fun refreshHistory(alreadyRetriedAfterRecovery: Boolean = false): Boolean {
        if (!requireLogin()) return false
        if (!requestGate.tryStart("rollcall_history")) return false
        historyError = ""
        historyLoading = true
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountId = username()
        scope.launchGatedRequest(requestGate, "rollcall_history") {
            try {
                // SWR 缓存先行：IO 线程读盘 → 有缓存先上屏（含上次更新），
                // 网络回包后原位覆盖；不学 ExamSectionState 构造期同步 IO
                val cached = runCatching {
                    loadRollcallHistoryCache(rollcallHistoryCacheFile(activity), accountId)
                }.getOrNull()
                if (cached != null && cached.items.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn()) && historyItems.isEmpty()) {
                            historyItems = cached.items
                            historyUpdatedAtMillis = cached.fetchedAtMillis
                        }
                    }
                }
                val result = runCatching {
                    val client = createHistoryClient(session.cookieHeader)
                    // 阶段一：最近十条选中即上屏，每条状态位「核实中…」——绝不显示聚合值
                    val preview = client.selectRecentRollcalls(accountId, coursesProvider())
                    if (preview.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                                historyItems = preview
                            }
                        }
                    }
                    // 阶段二：并发核实本人状态，原位覆盖为准确结论
                    val resolved = client.resolveOwnStatuses(preview, accountId)
                    // 落盘放 IO 线程；失败静默（缓存只是加速路径，不阻塞展示）
                    runCatching {
                        // 写前校验会话世代：登出已删缓存文件，晚到的在途写不得复活
                        // （与同函数 UI 写的 accepts 守卫对齐）
                        if (sessionEpoch.isCurrent(session)) {
                            saveRollcallHistoryCache(
                                rollcallHistoryCacheFile(activity),
                                RollcallHistorySnapshot(accountId, System.currentTimeMillis(), resolved),
                                isSessionCurrent = { sessionEpoch.isCurrent(session) },
                            )
                        }
                    }
                    resolved
                }
                withContext(Dispatchers.Main) {
                    try {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            val items = result.getOrNull()
                            if (items != null) {
                                historyError = ""
                                historyItems = items
                                historyUpdatedAtMillis = System.currentTimeMillis()
                                historyLoaded = true
                            } else {
                                // 失败时屏上若停着未核完的预览（状态位空白），如实落为「未知」，
                                // 不让「核实中…」永久悬挂；此前渲染的旧数据/缓存语义不变
                                if (historyItems.any { it.ownStatus.isBlank() }) {
                                    historyItems = historyItems.map { item ->
                                        if (item.ownStatus.isBlank()) item.copy(ownStatus = STATUS_UNKNOWN) else item
                                    }
                                }
                                val error = result.exceptionOrNull()
                                    ?: IllegalStateException("读取失败且未携带原因")
                                if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                                    historyError = "登录已过期，正在安全续登"
                                    setPendingSessionRetry(ModuleReadRetry(ModuleReadRequest.ROLLCALL_HISTORY))
                                } else {
                                    // 失败保留已展示数据只报错（SWR 语义）
                                    historyError = refreshFailureMessage(error)
                                }
                            }
                        }
                    } finally {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            historyLoading = false
                        }
                    }
                }
            } finally {
                // 取消时也要收起「核实中」：门已由任务完成回调统一释放，这里只做 UI 收尾。
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                        historyLoading = false
                    }
                }
            }
        }
        return true
    }

    /** 登出/换号清理：清内存状态（持久化由调用方一并清理，与拆分前一致）。 */
    fun clearAll() {
        events = emptyList()
        loading = false
        refreshError = ""
        updatedAtMillis = 0L
        // 历史区块与进行中两块同清（磁盘缓存由调用方 deleteRollcallHistoryCacheFile 一并删）
        historyItems = emptyList()
        historyLoading = false
        historyError = ""
        historyUpdatedAtMillis = 0L
        historyLoaded = false
    }
}

/** 「立即进行当前签到」一次点击的结果。 */
internal data class ForceAnswerReport(
    /** 点击时是否存在进行中的签到（false → UI 侧去开启监控与自动签到）。 */
    val hadActive: Boolean,
    val answered: Int,
    /** 课名 → 未完成原因；空表示全部完成。 */
    val unfinished: Map<String, String>,
)

/** 最多循环几轮「拉列表 → 提交」：正常一轮就结束，留给平台回执延迟一点余量。 */
private const val FORCE_ANSWER_MAX_ROUNDS = 3

/** 单条签到在一轮里的提交次数（平台瞬时 5xx/超时会重试）。 */
private const val FORCE_ANSWER_ATTEMPTS = 2

private const val FORCE_ANSWER_RETRY_GAP_MILLIS = 1_000L

/** 这条签到现在还需要我提交吗：已截止、已签、迟到、请假都不算。 */
private fun RollcallEvent.awaitingMyAnswer(): Boolean {
    if (isExpired) return false
    if (remainingSeconds != null && remainingSeconds <= 0L) return false
    val own = ownStatus ?: return true
    return !isTerminalRollcallStatus(own)
}
