package com.xmu.assistant

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 长图分享落盘节流窗口：窗口内且成绩未刷新时复用同一张图，不重复写文件。 */
private const val SHARE_SAVE_WINDOW_MILLIS = 60_000L

/** 分享面板拉起后多久提示保存目录（面板盖住的瞬间提示等于没提示）。 */
private const val SHARE_LOCATION_HINT_DELAY_MILLIS = 800L

/**
 * 成绩模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态，
 *与其它模块共用同一进程级 epoch；续登写回教务 cookie（setScoreCookieHeader）
 *前同样校验 isCurrent（防登出/换号后旧账号 cookie 写回）。
 * - 存储依赖收窄为函数式注入（scoreCookieHeader/setScoreCookieHeader/scoreRecordsJson/
 *scoreUpdatedAtMillis）：组合侧绑定 AssistantSettings（加密 prefs），测试注入 fake。
 * - 风控红线：成绩刷新内部可能触发教务 CAS 续登（XmuScoreAutoQueryClient 内部续登），
 *与 TronClass 手动登录并发会打两个身份域——入口检查 accountTransitionInProgress；
 *请求统一走 "scores" 互斥门（RequestGate）。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class ScoreSectionState(
    private val activity: Activity,
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
    private val setScoreRecordsJson: (String) -> Unit,
    private val setScoreUpdatedAtMillisPref: (Long) -> Unit,
    private val readRankCache: () -> String = { "" },
    private val writeRankCache: (String, () -> Boolean) -> Boolean = { _, _ -> true },
    scoreRecordsInitial: List<XmuScoreRecord> = emptyList(),
    scoreUpdatedAtMillisInitial: Long = 0L,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val persistenceGate = LatestSnapshotWriteGate()

    /**
     * 长图分享节流：同一份成绩在 1 分钟内只落一次文件。
     *
     * 每次点分享都会生成新 PNG 落到公开目录，连点或反复分享会堆出一串几乎相同的图。
     * 这里按「成绩快照版本 + 1 分钟窗口」复用上次写出的 uri：窗口内没刷新过成绩，
     * 直接分享已有的那张；刷新成功后 [shareSnapshot] 递增，下一次分享必然重新生成
     * ——成绩页刷新意味着内容可能变了，缓存图不能再拿去分享。
     */
    private var shareSnapshot = 0
    private var lastShareAt = 0L
    private var lastShareSnapshot = -1
    private var lastShareUri: android.net.Uri? = null

    val ranking = RankSectionState(activity, requestGate, sessionEpoch, sessionOwner, scope,
        loggedIn, cookieHeader, username, password, accountTransitionInProgress,
        scoreCookieHeader, setScoreCookieHeader, readRankCache, writeRankCache, show,
        scoreRecordsInitial)

    /** 成绩记录（内存可观察；持久化在 settings，登出/换号时清）。 */
    var scoreRecords by mutableStateOf(scoreRecordsInitial)
        private set

    /** 最近一次成绩刷新成功的时间戳（毫秒）。 */
    var updatedAtMillis by mutableStateOf(scoreUpdatedAtMillisInitial)
        private set

    var loading by mutableStateOf(false)
        private set

    var refreshError by mutableStateOf("")
        private set

    /**
     * 强制刷新成绩。登录/恢复在途时禁止并发刷新：成绩刷新内部可能触发 academic CAS 续登，
     *与 TronClass 登录并发会打两个身份域。
     */
    fun refresh(): Boolean {
        if (accountTransitionInProgress()) {
            show("登录处理中，请稍候")
            return false
        }
        // 登录态检查与 schedule/rollcall/courseware 入口对齐：登出后凭据（学号密码）仍残留，
        // 缺此检查会用残留凭据直接发起教务 CAS 登录——回调虽被 loggedIn 守卫丢弃，
        // 但登录请求已打出（登出后幽灵登录 = 风控暴露）
        if (!loggedIn() || cookieHeader().isBlank()) {
            showWarning("请先登录")
            return false
        }
        if (username().isBlank() || password().isBlank()) {
            show("请先在首页填写学号和密码")
            return false
        }
        if (!requestGate.tryStart("scores")) return false
        refreshError = ""
        loading = true
        val accountUsername = username()
        val accountPassword = password()
        // client 建在 scope 外（B1 收尾）：成功/失败分支都要回写续登后的 cookie，
        // 原形态 client 建在 IO 协程内、跨 runCatching 边界；构造零 IO，经闭包传入。
        // 构造本身可能抛异常（加密存储 / Keystore 异常），必须就地归还门，
        // 否则 "scores" 门被永久占用、成绩刷新到重启前全部静默失效。
        val sessionAndClient = try {
            val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
            session to XmuScoreAutoQueryClient(
                username = accountUsername,
                password = accountPassword,
                cookieHeader = scoreCookieHeader(),
                mayRelogin = { sessionEpoch.isCurrent(session) },
            )
        } catch (error: Exception) {
            loading = false
            requestGate.finish("scores")
            refreshError = refreshFailureMessage(error)
            showError("成绩读取失败：$refreshError")
            return false
        }
        val (session, client) = sessionAndClient
        scope.runModuleRequest(
            requestGate = requestGate,
            gateKey = "scores",
            // 账号复核折叠进统一谓词（B1）：换号必推进 epoch 使 accepts 为假，语义等价
            acceptsResult = {
                sessionEpoch.accepts(session, cookieHeader(), loggedIn()) &&
                    (username() == accountUsername && password() == accountPassword)
            },
            ioWork = { client.fetchScores() },
            onResult = { result ->
                result.onSuccess { fetchResult ->
                    val records = fetchResult.records
                    refreshError = ""
                    val updatedAt = System.currentTimeMillis()
                    // 加密 prefs 写大 JSON 挪到后台线程（与课表模块同一约定）：
                    // 主线程只做内存状态更新，避免刷新完成瞬间的加密/磁盘 IO 卡顿。
                    persistSnapshot(session, client.cookieHeader(), records, updatedAt)
                    ranking.onScoresChanged(records, fetchResult.failedTermNames.isEmpty())
                    scoreRecords = records
                    updatedAtMillis = updatedAt
                    // 成绩刷新后长图内容可能变化：作废上次分享缓存，下一次分享重新生成
                    shareSnapshot++
                    lastShareUri = null
                    show(
                        when {
                            records.isEmpty() -> "暂无成绩记录"
                            fetchResult.failedTermNames.isNotEmpty() ->
                                "成绩已读取 ${records.size} 门，${fetchResult.failedTermNames.size} 个学期读取失败"
                            else -> "成绩已读取 ${records.size} 门"
                        },
                    )
                }
                result.onFailure { error ->
                    // 失败路径也回写续登后的 cookie（受 epoch 保护）：
                    // 避免下次仍用旧 cookie 再触发一次 CAS 续登（风控暴露翻倍）。
                    // 登录被服务端拒绝/限流（AcademicLoginBlockedException）时
                    // cookie 是无效中间态：**不写回**（写回会让下次操作直接 landing 死循环）。
                    if (error !is AcademicLoginBlockedException && sessionEpoch.isCurrent(session)) {
                        // 沿用最后有效成绩与其原时间，避免旧成功任务稍后覆盖新的 cookie；
                        // 从未成功读取时仅保存 cookie，不把失败写成一次空成绩缓存。
                        val validRecords = scoreRecords.takeIf { it.isNotEmpty() || updatedAtMillis > 0L }
                        persistSnapshot(session, client.cookieHeader(), validRecords, updatedAtMillis)
                    }
                    Log.e("XmuScoreDebug", "score refresh failed: ${error.javaClass.name}: ${error.message}", error)
                    if (error is AcademicLoginInProgressException) {
                        // 其他模块正在登录教务（冷启动首刷课表先起 CAS 登录最常见）：
                        // 会话本身没问题，静默等下次刷新，绝不弹「教务登录已过期」。
                        refreshError = ""
                    } else {
                        refreshError = refreshFailureMessage(error)
                        showError("成绩读取失败：$refreshError")
                    }
                }
            },
            releaseLoading = { loading = false },
        )
        return true
    }

    private fun persistSnapshot(
        session: SessionRequest,
        cookie: String,
        records: List<XmuScoreRecord>?,
        updatedAt: Long,
    ) {
        val revision = persistenceGate.nextRevision()
        // 下一次刷新会立即读取 cookie；小值同步回写，不能等大快照的后台队列。
        // 与正在写的快照共用本地写锁，旧任务退出后才交接新的 cookie。
        persistenceGate.persistIfLatest(revision) {
            if (sessionEpoch.isCurrent(session)) setScoreCookieHeader(cookie)
        }
        val json = records?.let(::xmuScoreRecordsToJson) ?: return
        scope.launch(persistenceDispatcher) {
            // 请求门已释放时下次刷新可能先完成；成功与失败共用整组快照的顺序门。
            persistenceGate.persistIfLatest(revision) {
                if (sessionEpoch.isCurrent(session)) {
                    setScoreRecordsJson(json)
                    setScoreUpdatedAtMillisPref(updatedAt)
                }
            }
        }
    }

    /** 生成成绩长图并分享；同一时刻只生成一张，旧会话或已销毁页面不再回填。 */
    fun shareLongImage() {
        val records = scoreRecords
        if (records.isEmpty()) {
            showWarning("请先刷新成绩")
            return
        }
        // 1 分钟内、且成绩没刷新过：复用上次写出的那张图，不再落新文件。
        // 分享失败（文件被用户在文件管理器里删掉等）才继续往下重新生成。
        val cached = lastShareUri
        if (cached != null && lastShareSnapshot == shareSnapshot &&
            System.currentTimeMillis() - lastShareAt < SHARE_SAVE_WINDOW_MILLIS
        ) {
            val shared = runCatching { shareScoreImage(activity, cached) }
            if (shared.isSuccess) {
                notifySharedLocation()
                return
            }
            lastShareUri = null
        }
        val snapshot = shareSnapshot
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        // 连点不再重复渲染大位图；门由任务完成回调释放，启动前取消也不会占用。
        if (!requestGate.tryStart("score_share")) return
        scope.launchGatedRequest(requestGate, "score_share", Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (!sessionEpoch.isCurrent(session)) return@launchGatedRequest
            val bitmap = runCatching { renderScoreLongImage(records, xmuScoreSummary(records)) }
                .getOrNull()
            if (bitmap == null) {
                // 长图生成失败（超长成绩单 OOM 等）：明确提示，不崩溃
                withContext(Dispatchers.Main) {
                    if (sessionEpoch.isCurrent(session) && activityAlive()) show("成绩单过长，生成长图失败")
                }
                return@launchGatedRequest
            }
            val uri = try {
                currentCoroutineContext().ensureActive()
                if (!sessionEpoch.isCurrent(session)) return@launchGatedRequest
                runCatching { saveScoreImageToSharedFolder(activity, bitmap) }
                    .onFailure { Log.w("XmuScoreDebug", "成绩长图保存失败", it) }
                    .getOrNull()
            } finally {
                bitmap.recycle()
            }
            withContext(Dispatchers.Main) {
                if (!sessionEpoch.isCurrent(session) || !activityAlive()) return@withContext
                if (uri == null) {
                    showError("生成成绩长图失败")
                } else {
                    // 只缓存本次真正落盘的那张；成绩已刷新（snapshot 变了）就不算数
                    if (snapshot == shareSnapshot) {
                        lastShareUri = uri
                        lastShareSnapshot = snapshot
                        lastShareAt = System.currentTimeMillis()
                    }
                    runCatching { shareScoreImage(activity, uri) }
                        .onSuccess { notifySharedLocation() }
                        .onFailure {
                            Log.w("XmuScoreDebug", "成绩长图分享失败", it)
                            showError("打开系统分享面板失败")
                        }
                }
            }
        }
    }

    /**
     * 分享面板拉起后延迟 0.8 秒提示落盘目录。
     *
     * 立刻弹会被系统分享面板盖住，用户根本看不到；等面板铺开再提示才读得到。
     * 提示只说目录，不打断分享流程。
     */
    private fun notifySharedLocation() {
        scope.launch {
            delay(SHARE_LOCATION_HINT_DELAY_MILLIS)
            withContext(Dispatchers.Main) {
                if (activityAlive()) show("成绩长图已保存到 ${ExportStorage.describe()}")
            }
        }
    }

    private fun activityAlive(): Boolean = !activity.isFinishing && !activity.isDestroyed

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        ranking.clearLoadingState()
        loading = false
    }

    /** 登出/换号清理：清内存状态（持久化由调用方一并清理，与拆分前一致）。 */
    fun clearAll() {
        // setter 只执行同步本地写入；返回后调用方才可清 prefs，防止旧写复活缓存。
        persistenceGate.invalidateAndWait()
        ranking.clearAll()
        scoreRecords = emptyList()
        loading = false
        refreshError = ""
        updatedAtMillis = 0L
        // 换号后旧的分享图属于上一个账号，不能复用
        lastShareUri = null
        lastShareAt = 0L
        lastShareSnapshot = -1
        shareSnapshot++
    }
}
