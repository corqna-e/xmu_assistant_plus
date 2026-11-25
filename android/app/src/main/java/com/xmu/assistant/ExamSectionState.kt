package com.xmu.assistant

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 考试安排模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 转屏/进程重建恢复：selectedTerm 与 manuallySelected 由组合侧
 *rememberSaveable + Saver 保存/恢复（见 MainActivity 中的声明）；
 *summary/validTerms 等其余状态由 init 协程从磁盘缓存异步回填
 *（IO 线程读盘 → 主线程写入，构造期不再做主线程磁盘 IO）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态，
 *与其它模块共用同一进程级 epoch；续登写回教务 cookie（setScoreCookieHeader）
 *前同样校验 isCurrent（防登出/换号后旧账号 cookie 写回）。
 * - 存储依赖收窄为函数式注入（scoreCookieHeader/setScoreCookieHeader/reminderSettings）：
 *组合侧绑定 AssistantSettings（加密 prefs），测试注入 fake，互不依赖。
 * - 风控红线：考试内部可能触发教务 CAS 续登（XmuExamClient.fetchTermExams 统一续登，
 *进程级单飞门），与 TronClass 手动登录并发会打两个身份域——refresh/checkChanges 等
 *入口检查 accountTransitionInProgress；请求统一走 "exam" 互斥门，
 *提醒重排走 "exam-reminder" 门。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class ExamSectionState(
    private val activity: ComponentActivity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val showWarning: (String) -> Unit = show,
    private val showError: (String) -> Unit = show,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    private val username: () -> String,
    private val password: () -> String,
    private val accountTransitionInProgress: () -> Boolean,
    private val scoreCookieHeader: () -> String,
    private val setScoreCookieHeader: (String) -> Unit,
    private val reminderSettings: () -> ExamReminderSettings,
    // null = 未指定（首次创建）：取缓存学期列表的第一个；显式空串（Saver 恢复无选择）保持空
    selectedTermInitial: String? = null,
    manuallySelectedInitial: Boolean = false,
    private val cacheDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 当前选中的学期（默认最近一个有效学期；进程重建由 Saver 恢复）。 */
    var selectedTerm by mutableStateOf(selectedTermInitial.orEmpty())
        private set

    /**未手动选过时默认始终校正到最近学期。 */
    var manuallySelected by mutableStateOf(manuallySelectedInitial)
        private set

    /** 当前学期展示数据（缓存优先，悄悄检查发现变化才更新）。 */
    var summary by mutableStateOf<XmuTermExamSummary?>(null)
        private set

    /** 有效学期列表（新→旧，最近在前）。 */
    var validTerms by mutableStateOf(emptyList<String>())
        private set

    var loading by mutableStateOf(false)
        private set

    var refreshError by mutableStateOf("")
        private set

    /** 悄悄检查/切学期检测到变化并自动更新（页面显示「检测到变化，自动更新」横幅）。 */
    var autoUpdated by mutableStateOf(false)
        private set

    /**
     *用户自定义的考试安排（手动输入，不来自教务抓取）。
     * 与抓取项分开存放、分开展示；同样会被并入课表。
     * 属于用户自己的笔记，登出/换号不清（clearAll 只清教务侧缓存与提醒）。
     */
    var customExams by mutableStateOf(emptyList<CustomExam>())
        private set

    // 状态世代（仅主线程读写）：clearAll 递增，磁盘缓存回填携带起始世代，
    // 晚于登出清理落地的回填直接丢弃（防登出后旧缓存回灌界面）。
    private var stateGeneration = 0

    /** 自定义考试落盘串行化锁（见 persistCustomExams）。 */
    private val persistMutex = Mutex()

    init {
        // 构造期不再做主线程磁盘 IO：此前 loadTerms + loadTerm 在组合期间
        // 同步读 prefs；现改为 IO 线程读缓存、主线程回填（RollcallSectionState
        // 「缓存先行」范式）。回填仅在字段仍为初始值时写入，避免晚到的缓存
        // 覆盖静默刷新已带回的新数据或用户已切换的选择。
        val generationAtStart = stateGeneration
        scope.launch(cacheDispatcher) {
            val restoredTerms = ExamCache.loadTerms(activity)
            val restoredTerm = selectedTermInitial ?: restoredTerms.firstOrNull().orEmpty()
            val restoredSummary = ExamCache.loadTerm(activity, restoredTerm)
            val restoredCustom = CustomExamStore.loadResult(activity)
            withContext(Dispatchers.Main) {
                if (stateGeneration != generationAtStart) return@withContext
                if (validTerms.isEmpty()) validTerms = restoredTerms
                if (selectedTerm.isBlank()) selectedTerm = restoredTerm
                if (summary == null && selectedTerm == restoredTerm) summary = restoredSummary
                if (customExams.isEmpty()) customExams = restoredCustom.exams
                // 只有真损坏才提示一次，正常启动不打扰
                if (restoredCustom.damaged) showWarning("自定义考试记录损坏，已尽量恢复")
            }
        }
    }

    /**新增或更新一条自定义考试（同 id 视为更新）。 */
    fun saveCustomExam(exam: CustomExam) {
        val index = customExams.indexOfFirst { it.id == exam.id }
        val next = if (index >= 0) {
            customExams.toMutableList().also { it[index] = exam }
        } else {
            customExams + exam
        }
        customExams = next
        persistCustomExams()
    }

    /**删除一条自定义考试。 */
    fun deleteCustomExam(id: String) {
        val next = customExams.filterNot { it.id == id }
        if (next.size == customExams.size) return
        customExams = next
        persistCustomExams()
    }

    /**
     * 落盘放在 IO 线程，避免主线程写文件。
     *
     * 用 [persistMutex] 串行化：连续两次保存会各起一个 IO 协程，不互斥时写操作交错，
     * 后到的旧快照还可能覆盖新状态。锁内重读 [customExams] 而不是用调用时的快照，
     * 因此无论谁先拿到锁，最终落盘的都是当前内存态。
     */
    private fun persistCustomExams() {
        scope.launch(cacheDispatcher) {
            persistMutex.withLock { CustomExamStore.save(activity, customExams) }
            // 自定义考试同样并入明日课程与桌面组件：落盘完成后再重投，
            // 否则组件读到的是旧文件。
            withContext(Dispatchers.Main) {
                runCatching { ScheduleWidgetProvider.refreshAll(activity) }
            }
        }
    }

    /** 学期条点击：立即复用缓存结果，后台悄悄检查变化；无缓存且已登录才拉取。 */
    fun selectTerm(term: String) {
        selectedTerm = term
        manuallySelected = true
        autoUpdated = false
        val cached = ExamCache.loadTerm(activity, term)
        if (cached != null) {
            // 有缓存：立即复用上次结果，后台悄悄检查变化
            summary = cached
            refreshError = ""
            checkChanges(term)
        } else if (!loggedIn() || cookieHeader().isBlank() || accountTransitionInProgress()) {
            // 登录/恢复在途且无该学期缓存：若保留旧学期 summary，ExamPage 不按
            // selectedTerm 过滤会把「旧学期数据」显示到新切换的标签下（错位）。
            // 此处清空摘要防串显，并提示用户稍后重试（页面以提示代替错位数据）。
            summary = null
            show("登录处理中，请稍候")
        } else {
            // 无缓存：拉取该学期（首次查看，允许加载指示）
            summary = null
            refresh()
        }
    }

    /** 获取教务域会话 cookie（与课表/成绩同域，直接复用 settings 存储）。
     *
     *注意：**不再调用 ensureScheduleSession**。实测：
     * ensureScheduleSession 会打开课表应用（appShow wdkbapp），而 jwapp 是
     * 单应用会话模型——课表应用会话占用后，考试应用（studentWdksapApp）的
     * 业务请求一律被 Tomcat 拒绝（403），每次操作都逼出一次完整 CAS 续登
     * （风控暴露翻倍 + 请求量翻倍），且续登后的有效 cookie 写回后仍被下一轮
     * ensureScheduleSession 顶掉，形成固定循环。
     * 考试链路改为：直接用存储 cookie → XmuExamClient 内部 appOpen（考试应用
     * 入口）→ 业务请求；会话过期（401/403/登录页）时由 fetchTermExams 统一
     * 续登一次并重试，成功后把有效 cookie 写回 settings（下次操作直接复用，
     * 不再循环）。
     */
    fun ensureAcademicSession(): String {
        // 后台执行时复查登录在途：考试续登与 TronClass 登录并发会打两个身份域
        if (accountTransitionInProgress()) throw ExamLoginInProgressException()
        return scoreCookieHeader()
    }

    /** 四条刷新链路共用的客户端构造（B2 收敛）：教务会话取用 + 续登门绑定会话纪元。 */
    private fun newExamClient(
        session: SessionRequest,
        accountUsername: String,
        accountPassword: String,
    ): XmuExamClient = XmuExamClient(
        ensureAcademicSession(),
        accountUsername,
        accountPassword,
        mayRelogin = { sessionEpoch.isCurrent(session) },
    )

    /** 按当前设置重排考试提醒闹钟（幂等：先取消旧计划再排新计划）。
     *聚合所有已缓存学期的考试（跨学期提醒不丢）；执行挪到后台线程，不阻塞主线程。 */
    fun rescheduleReminders() {
        // 考试安排变了，桌面小组件也要重算：考试会并入今日课程与明日预告，
        // 只等组件自己 30 分钟轮询会让用户以为「考试没同步」。
        runCatching { ScheduleWidgetProvider.refreshAll(activity) }
        if (!requestGate.tryStart("exam-reminder")) return
        val reminderSettings = reminderSettings()
        val context = activity
        val plan = ExamReminderPlans.snapshot(context)
        scope.launchGatedRequest(requestGate, "exam-reminder") {
            if (scoreCookieHeader().isBlank()) return@launchGatedRequest
            ExamReminder.ensureChannel(context)
            // 缓存读取期间若已登出/重排，计划标识在同一锁内拒绝旧任务写回。
            ExamReminder.schedule(context, reminderSettings, ExamCache.loadAllExams(context), expectedPlan = plan)
        }
    }

    /** 强制刷新当前学期考试数据（手动刷新按钮）。写回前校验会话纪元。 */
    fun fetchData(term: String, session: SessionRequest, gateKey: String) {
        val accountUsername = username()
        val accountPassword = password()
        // 后台线程不读 Compose 状态：落空摘要的判定用发起时的快照。
        val selectedSnapshot = selectedTerm
        scope.runModuleRequest(
            requestGate = requestGate,
            gateKey = gateKey,
            acceptsResult = { sessionEpoch.accepts(session, cookieHeader(), loggedIn()) },
            ioWork = {
                // 考试接口在教务域（jw.xmu.edu.cn）：复用课表/成绩的教务会话，
                // 会话过期时客户端内部走与课表/成绩同构的安全续登（进程级单飞门，不并发打登录）。
                val client = newExamClient(session, accountUsername, accountPassword)
                val fetched = client.fetchTermExams(term)
                // 落缓存放 IO 线程（主线程 onResult 里不写盘），并逐次复核会话：
                // 登出清理后旧账号数据不会复活。
                val isCurrent = { sessionEpoch.isCurrent(session) }
                val cacheTarget = fetched ?: XmuTermExamSummary(term, term, emptyList(), emptyList())
                    .takeIf { term == selectedSnapshot }
                cacheTarget?.let { ExamCache.saveTerm(activity, it, isCurrent) }
                fetched to client.currentCookie()
            },
            onResult = { result ->
                result.onSuccess { (fetched, updatedCookie) ->
                    setScoreCookieHeader(updatedCookie)
                    refreshError = ""
                    if (fetched != null) {
                        // 仅当仍为当前选中学期才更新显示
                        if (term == selectedTerm) {
                            summary = fetched
                            autoUpdated = false
                        }
                        // 数据更新后重排提醒（聚合所有缓存学期）
                        rescheduleReminders()
                    } else if (term == selectedTerm) {
                        // 该学期两接口都空（无考试也无已选课未安排）：显示空状态，
                        // 不设错误文案（"暂无数据"不是刷新失败）。
                        // 空摘要已在 IO 线程落缓存 + 这里重排提醒，清掉已删除考试的旧闹钟。
                        summary = XmuTermExamSummary(term, term, emptyList(), emptyList())
                        rescheduleReminders()
                    }
                }
                result.onFailure { error ->
                    refreshError = refreshFailureMessage(error)
                    showError("考试安排读取失败：$refreshError")
                }
            },
            releaseLoading = { loading = false },
        )
    }

    fun refresh() {
        // 登录/恢复在途时禁止并发刷新：考试内部可能触发 academic CAS 续登（XmuExamClient 统一续登），
        // 与 TronClass 手动登录并发会打两个身份域。
        if (accountTransitionInProgress()) {
            show("登录处理中，请稍候")
            return
        }
        if (cookieHeader().isBlank()) {
            showWarning("请先登录")
            return
        }
        if (username().isBlank() || password().isBlank()) {
            showWarning("请先在首页填写学号和密码")
            return
        }
        val gateKey = "exam"
        if (!requestGate.tryStart(gateKey)) {
            show("正在刷新，请稍候")
            return
        }
        loading = true
        refreshError = ""
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val term = selectedTerm
        val accountUsername = username()
        val accountPassword = password()
        val knownTerms = validTerms
        if (term.isBlank()) {
            // 还没有选中学期：先探测有效学期，再取最近一个。
            // 门只在真正移交给 fetchData 之前归本任务所有：完成回调也覆盖「协程体尚未启动
            // 就被取消」的情况，移交后绝不提前释放另一个任务的门。
            val ownsGate = java.util.concurrent.atomic.AtomicBoolean(true)
            scope.launch(Dispatchers.IO) {
                val probe = runCatching {
                    val client = newExamClient(session, accountUsername, accountPassword)
                    val terms = client.probeValidTerms(knownTerms = knownTerms)
                    terms to client.currentCookie()
                }
                val result = probe.getOrNull()?.first ?: ExamProbeResult(emptyList(), null)
                val terms = result.terms
                val probeError = probe.exceptionOrNull()
                val target = terms.firstOrNull()
                // 每次提交在缓存清理共用的锁内复核会话：登出后晚到的在途写不得回填旧账号数据。
                val isCurrent = { sessionEpoch.isCurrent(session) }
                if (probeError == null) ExamCache.markProbed(activity, isCurrent)
                result.summaries.forEach { (_, s) -> ExamCache.saveTerm(activity, s, isCurrent) }
                withContext(Dispatchers.Main) {
                    if (!sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                        loading = false
                        return@withContext
                    }
                    // 续登后的 Cookie 在主线程接纳处写回，避免检查与写回之间被登出插入。
                    probe.getOrNull()?.second?.let(setScoreCookieHeader)
                    validTerms = terms
                    ExamCache.saveTerms(activity, terms)
                    if (target != null) {
                        // 默认选中最近一个学期；用户已手动选择且仍有效则保留
                        val desired = selectedTerm.takeIf { it in terms } ?: target
                        selectedTerm = desired
                        ownsGate.set(false)
                        fetchData(desired, session, gateKey)
                    } else {
                        loading = false
                        // 探测失败要明确提示（会话过期/网络异常），不能静默成"没有数据"
                        refreshError = probeError?.let { refreshFailureMessage(it) } ?: "没有找到考试数据"
                    }
                }
            }.invokeOnCompletion {
                if (ownsGate.get()) requestGate.finish(gateKey)
            }
            return
        }
        fetchData(term, session, gateKey)
    }

    /** 切换学期/进入页面后的悄悄检查：有变化（列表增删/未完成→已完成）才更新。 */
    fun checkChanges(term: String) {
        if (!loggedIn() || cookieHeader().isBlank()) return
        // 登录/恢复在途时跳过：考试检查可能触发 academic CAS 续登（XmuExamClient 统一续登），
        // 与 TronClass 手动登录并发会打两个身份域。
        if (accountTransitionInProgress()) return
        val cached = summary?.takeIf { it.termCode == term } ?: return
        // 与手动刷新/静默刷新共用同一互斥 key：任一考试请求在途时静默跳过（避免并发请求与乱序写回）
        val gateKey = "exam"
        if (!requestGate.tryStart(gateKey)) return
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountUsername = username()
        val accountPassword = password()
        scope.launchGatedRequest(requestGate, gateKey) {
            try {
                val outcome = runCatching {
                    val client = newExamClient(session, accountUsername, accountPassword)
                    client.fetchTermExams(term) to client.currentCookie()
                }
                withContext(Dispatchers.Main) {
                    if (!sessionEpoch.accepts(session, cookieHeader(), loggedIn())) return@withContext
                    // 续登后的 Cookie 在接纳处写回，与登出清理同线程顺序。
                    outcome.getOrNull()?.second?.let(setScoreCookieHeader)
                    val latest = outcome.getOrNull()?.first
                    // 结果仅落缓存，不改显示
                    if (term != selectedTerm) {
                        latest?.let { ExamCache.saveTerm(activity, it) }
                        return@withContext
                    }
                    if (latest == null && outcome.exceptionOrNull() != null) {
                        // 请求失败（网络/接口不可用/会话过期/单飞门被占）：fetchTermExams
                        // 失败一律抛异常，与「真空学期返回 null」的成功态被 runCatching
                        // 折叠成同一个 null，必须用 exceptionOrNull 区分。失败与
                        // silentRefresh 的失败分支同款：保留显示与缓存静默跳过，等
                        // 下一轮/手动刷新——绝不把失败误判成「教务清空该学期」清缓存撤提醒。
                        return@withContext
                    }
                    if (latest == null) {
                        // 非空→变空（教务清空该学期，成功返回真空）：与 silentRefresh 同构——
                        // 显示空态、落空缓存、重排提醒（清掉已删考试的旧闹钟），避免切学期
                        // 路径下空壳学期长期显示过期数据。
                        if (cached.exams.isNotEmpty() || cached.unarranged.isNotEmpty()) {
                            val empty = XmuTermExamSummary(term, term, emptyList(), emptyList())
                            summary = empty
                            ExamCache.saveTerm(activity, empty)
                            autoUpdated = true
                            rescheduleReminders()
                        }
                        return@withContext
                    }
                    val changed = examsChanged(cached, latest)
                    if (changed) {
                        summary = latest
                        ExamCache.saveTerm(activity, latest)
                        autoUpdated = true
                        // 数据变化后重新调度提醒（新增考试/时间变动）
                        rescheduleReminders()
                    }
                }
            } finally {
                // 取消时也收起加载态；门由任务完成回调统一释放。
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) loading = false
                }
            }
        }
    }

    /**
     *进入考试页的默认路径：
     * - 立即复用缓存结果（多学期缓存 + 学期列表缓存），不显示"正在刷新"表达；
     *仅无任何缓存可复用时（首次）显示加载指示
     * - 后台静默拉取：当前学期数据每次进入都检查；学期列表按节流间隔（6 小时）重探
     * - 检测到变化（学期列表/考试数据）才更新，并提示「检测到变化，自动更新」
     * - 默认选中最近一个学期；用户已手动选择且仍有效则保留
     */
    fun silentRefresh() {
        // 与手动刷新/切学期检查共用同一互斥 key：任一考试请求在途时静默跳过
        val gateKey = "exam"
        if (!requestGate.tryStart(gateKey)) return
        if (summary == null) loading = true
        // 主线程一次性快照 Compose 状态，后台线程只读快照（避免跨线程读 MutableState）
        val snapshotSelectedTerm = selectedTerm
        val snapshotManuallySelected = manuallySelected
        val snapshotValidTerms = validTerms
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountUsername = username()
        val accountPassword = password()
        scope.launchGatedRequest(requestGate, gateKey) {
            try {
                val result = runCatching {
                    val client = newExamClient(session, accountUsername, accountPassword)
                    val reProbe = ExamCache.shouldReProbe(activity) || snapshotValidTerms.isEmpty()
                    var terms: List<String>
                    var summaries: Map<String, XmuTermExamSummary> = emptyMap()
                    if (reProbe) {
                        // probe 返回新→旧（最近在前）+ 全部有效学期数据（probe 已拉取，复用省请求）；
                        // 传入已缓存学期（knownTerms）：窗口收敛只探测新增/边界学期，省请求
                        val probe = client.probeValidTerms(knownTerms = snapshotValidTerms)
                        terms = probe.terms
                        summaries = probe.summaries
                        // 逐学期提交各自复核会话：登出清理之后，旧批次不会继续回填。
                        val isCurrent = { sessionEpoch.isCurrent(session) }
                        ExamCache.markProbed(activity, isCurrent)
                        summaries.forEach { (_, s) -> ExamCache.saveTerm(activity, s, isCurrent) }
                    } else {
                        terms = snapshotValidTerms
                    }
                    // 目标学期与 UI 校正用同一规则（未手动选择时始终取最近学期 = 列表第一个）
                    val target = if (snapshotManuallySelected) {
                        snapshotSelectedTerm.takeIf { it in terms } ?: terms.firstOrNull()
                    } else {
                        terms.firstOrNull()
                    }
                    // 目标为最近学期且 probe 已拉取时直接复用，不再重复请求（提速）
                    val fetched = summaries[target] ?: target?.let { client.fetchTermExams(it) }
                    SilentRefreshResult(terms, target, fetched, summaries) to client.currentCookie()
                }
                withContext(Dispatchers.Main) {
                    try {
                        if (!sessionEpoch.accepts(session, cookieHeader(), loggedIn())) return@withContext
                        result.getOrNull()?.second?.let(setScoreCookieHeader)
                        result.onSuccess { (refreshed, _) ->
                            val (terms, target, latest, _) = refreshed
                            refreshError = ""
                            validTerms = terms
                            ExamCache.saveTerms(activity, terms)
                            // 默认最近一个学期（未手动选择时始终校正到最近 = 列表第一个）
                            val desired = if (manuallySelected) {
                                selectedTerm.takeIf { it in terms } ?: terms.firstOrNull()
                            } else {
                                terms.firstOrNull()
                            }
                            if (desired != null && desired != selectedTerm) {
                                selectedTerm = desired
                                summary = ExamCache.loadTerm(activity, desired)
                                autoUpdated = false
                            }
                            // 结果仅落缓存（后台已存），不改显示与横幅
                            if (target == null || target != selectedTerm) {
                                return@onSuccess
                            }
                            val shown = summary
                            if (shown == null || shown.termCode != target) {
                                // 该学期没有可复用的上次结果：直接显示最新数据（首次查看）
                                summary = latest
                                latest?.let { ExamCache.saveTerm(activity, it) }
                                autoUpdated = false
                            } else if (latest == null) {
                                // 学期数据非空→变空（考试/未安排被教务清空）：视为变化，显示空态；
                                // 落空缓存 + 重排提醒（清掉已删考试的旧闹钟，避免残留误触发）
                                if (shown.exams.isNotEmpty() || shown.unarranged.isNotEmpty()) {
                                    val empty = XmuTermExamSummary(target, target, emptyList(), emptyList())
                                    summary = empty
                                    ExamCache.saveTerm(activity, empty)
                                    autoUpdated = true
                                    rescheduleReminders()
                                } else {
                                    autoUpdated = false
                                }
                            } else if (examsChanged(shown, latest)) {
                                // 悄悄检查发现变化：更新数据 + 提示 + 重排提醒
                                summary = latest
                                ExamCache.saveTerm(activity, latest)
                                autoUpdated = true
                                rescheduleReminders()
                            } else {
                                // 无变化：清除上次的更新横幅（避免残留误导）
                                autoUpdated = false
                            }
                        }
                        result.onFailure { error ->
                            // 有缓存时静默失败：保留上次结果，下次进入/手动刷新再试；
                            // 无缓存可复用（首次）才明确提示，避免空页面无解释。
                            // 教务登录在途（其他模块正在打 CAS 登录）不提示：会话没问题。
                            if (summary == null) {
                                refreshError = when (error) {
                                    is ExamLoginInProgressException, is AcademicLoginInProgressException -> ""
                                    else -> refreshFailureMessage(error)
                                }
                            }
                        }
                    } finally {
                        // 与全仓其余骨架齐一（A2 统一）：loading 在世代守卫内释放。登出/换号
                        // 竞态下不在此复位——由 invalidateMainSessionUi → clearLoadingState() 兜底。
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            loading = false
                        }
                    }
                }
            } finally {
                // 取消时也收起加载态；门由任务完成回调统一释放。
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) loading = false
                }
            }
        }
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。
     *缺失会导致：刷新在途时会话失效，fetchData/silentRefresh 回调被 sessionEpoch 守卫
     *丢弃，finally 的守卫复位同样被跳过，loading 永真、刷新按钮永久转圈。 */
    fun clearLoadingState() {
        loading = false
    }

    /** 登出/换号清理：清状态 + 磁盘缓存 + 作废提醒计划，与 clearLoggedOutUi 语义对齐。
     *考试数据不含个人信息，但跟随账号会话，换号时也必须清（防串号串提醒）。 */
    fun clearAll() {
        stateGeneration++
        summary = null
        validTerms = emptyList()
        selectedTerm = ""
        manuallySelected = false
        loading = false
        refreshError = ""
        autoUpdated = false
        ExamCache.clear(activity)
        // 取消会推进持久化计划标识，旧缓存读取和已排队广播都不能复活提醒。
        ExamReminder.cancelAll(activity)
    }

    /** rememberSaveable Saver 使用的可保存快照（顺序固定：selectedTerm, manuallySelected）。 */
    fun savedState(): List<Any> = listOf(selectedTerm, manuallySelected)
}

/** 悄悄检查的结果：有效学期列表 + 目标学期 + 最新考试数据 + 窗口内全部学期数据。 */
internal data class SilentRefreshResult(
    val terms: List<String>,
    val target: String?,
    val latest: XmuTermExamSummary?,
    val summaries: Map<String, XmuTermExamSummary> = emptyMap(),
)
