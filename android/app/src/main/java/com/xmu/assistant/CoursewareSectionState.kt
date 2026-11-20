package com.xmu.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit

/**
 * 课程/课件模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态；
 *会话过期时经 setPendingSessionRetry 挂起一次性安全续登（recoverExpiredModule 处理）。
 * - 课程列表（courses/academicCache/selectedCourseId）留在组合侧（课程刷新 refreshCourses
 *与 CoursewarePage 编排共用），本分块只承接课件部分的状态与动作，通过函数式注入读写。
 * - 下载写回 busy（组合侧全局忙碌指示）用读写器注入，与拆分前行为一致。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
/**结构化下载进度，供课件页常驻进度区展示（旧的文本进度仍并行维护）。
 *补最近一次失败原因，便于用户看清「下载失败」到底是什么问题。 */
internal data class CoursewareDownloadUiState(
    val total: Int = 0,
    val completed: Int = 0,
    val successFiles: Int = 0,
    val successEntries: Int = 0,
    val failed: Int = 0,
    val active: Boolean = false,
    val finished: Boolean = false,
    val lastFailureReason: String = "",
)

/**
 * 「检查」的分类结果。
 *
 * @param hits 本地有文件、但还没记过下载标记的课件（要补标为已下载）。
 * @param missing 记过下载标记、但本地扫不到文件的课件（界面显示「？已下载」）。
 */
internal data class LocalCheckResult(val hits: Set<String>, val missing: Set<String>)

/**
 * 按「已知课件 / 已下载标记 / 本地扫到的文件」三份集合分类，不涉及 IO，便于单测。
 *
 * 只针对 [knownIds] 判断：早已不在列表里的旧下载记录（课程换了学期、课件被撤下）
 * 不参与，否则会平白多出一批「找不到文件」的提示。
 */
internal fun classifyLocalCheck(
    knownIds: Collection<String>,
    downloaded: Set<String>,
    presentIds: Set<String>,
): LocalCheckResult = LocalCheckResult(
    hits = knownIds.filter { it in presentIds && it !in downloaded }.toSet(),
    missing = knownIds.filter { it in downloaded && it !in presentIds }.toSet(),
)

internal class CoursewareSectionState(
    private val activity: Activity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val showWarning: (String) -> Unit = show,
    private val showError: (String) -> Unit = show,
    /**明确的成功提示（绿色），避免文案里带「失败 0」被当成告警染红。 */
    private val showSuccess: (String) -> Unit = show,
    private val requireLogin: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    private val busy: () -> String,
    private val setBusy: (String) -> Unit,
    private val selectedCourseId: () -> String?,
    private val setSelectedCourseId: (String?) -> Unit,
    private val academicCache: () -> AcademicCacheSnapshot,
    private val setAcademicCache: (AcademicCacheSnapshot) -> Long,
    private val setAcademicCacheJson: (String) -> Unit,
    private val isSelectedCourse: (String) -> Boolean,
    private val setPendingSessionRetry: (ModuleReadRetry) -> Unit,
) {
    var coursewareItems by mutableStateOf(emptyList<CoursewareUiItem>())
        private set

    /**
     * 标记为已下载、但最近一次「检查」在本地目录里没找到文件的课件 id。
     *
     * 只影响显示（角标变「？已下载」），不改下载标记本身：文件可能被用户挪走或改名，
     * 找不到不等于没下过。重新下载成功或下次检查扫到文件就自动清掉。
     */
    var missingLocalIds by mutableStateOf(CoursewareStorage.missingLocalIds(activity))
        private set

    /** 各课程各自的课件结果；多选课程时按 activeCourseIds 合并展示。 */
    private var itemsByCourse by mutableStateOf(emptyMap<String, List<CoursewareUiItem>>())

    /** 当前勾选的课程 ID 集合（组合侧多选状态镜像）。 */
    private var activeCourseIds by mutableStateOf(emptySet<String>())

    var selectedCoursewareIds by mutableStateOf(setOf<String>())
        private set

    var coursewareDownloadProgress by mutableStateOf("")
        private set

    var loadingIds by mutableStateOf(emptySet<String>())
        private set

    var refreshErrors by mutableStateOf(emptyMap<String, String>())
        private set

    /** 各课程已抓到的课件数目（缓存+本次会话合并），未选课程时列表徽标用。 */
    var coursewareCountsByCourse by mutableStateOf(emptyMap<String, Int>())
        private set

    /** 正在预取的课程数；>0 时徽标可显示“抓取中”。 */
    var prefetchRemaining by mutableStateOf(0)
        private set

    var downloadLoading by mutableStateOf(false)
        private set

    /**下载进度（多批累计），主线程写。 */
    var downloadUiState by mutableStateOf(CoursewareDownloadUiState())
        private set

    /**展示课件的保存位置（公共「下载/xmu助手课件」），不再涉及授权目录。 */
    val downloadDirectoryPath: String
        get() = CoursewareStorage.describe(activity)

    private var downloadBatchSeq = 0
    private var activeDownloadBatches = 0

    /** 取消请求：置位后本批尚未开始的课件直接跳过（在跑的那几个照常收尾）。 */
    private val cancelRequested = AtomicBoolean(false)

    /**
     * 下载世代：登出/换号走 [clearAll] 时递增。
     *
     * 批次开始前快照，回调与收尾时比对 —— 旧批次即使晚到也不能再把进度、
     * 条目状态写回已被清空的界面（否则换号后进度条会冒出「成功 N」，
     * 或上一门课的条目被重新塞回列表）。
     */
    private var downloadGeneration = 0

    /**自动勾选规则不再把它们补回。 */
    private var manuallyDeselectedIds by mutableStateOf(setOf<String>())

    /** 课程勾选集合变化时重算合并列表：新勾选课程自动勾选其课件，取消勾选则移除。 */
    fun setActiveCourses(ids: Set<String>) {
        val removed = activeCourseIds - ids
        // 先快照被取消课程曾加载过的课件，用于清理勾选与「手动取消」记录；
        // 这样下次重新勾选这门课时，课件能再次自动全选。
        val removedItemIds = if (removed.isEmpty()) emptySet() else {
            val cache = academicCache()
            removed.flatMap { id ->
                itemsByCourse[id].orEmpty() + cache.coursewareByCourse[id].orEmpty()
            }.map { it.id }.toSet()
        }
        activeCourseIds = ids
        val cache = academicCache()
        itemsByCourse = ids.associateWith { id -> itemsByCourse[id] ?: cache.coursewareByCourse[id].orEmpty() }
        selectedCoursewareIds = selectedCoursewareIds - removedItemIds
        manuallyDeselectedIds = manuallyDeselectedIds - removedItemIds
        rebuildItems()
    }

    private fun rebuildItems() {
        coursewareItems = activeCourseIds.flatMap { id -> itemsByCourse[id].orEmpty() }
        // 勾选集合的有效域不能只算「已选课程合并后的列表」—— 用户可以在展开的课程行里
        // 单独勾一门没被整体选中的课的某个课件，那条 id 不在 coursewareItems 里，
        // 旧实现下一次 rebuild 就把它裁掉，点下载时便提示「请先勾选课件」。
        val known = allKnownItemIds()
        selectedCoursewareIds = selectedCoursewareIds.intersect(known)
        manuallyDeselectedIds = manuallyDeselectedIds.intersect(known)
        // 选中课程 = 自动勾选其课件：课件异步到达（load / 预取完成）时也会补齐，
        // 但用户手动取消过的不会被自动重新选中。
        // 有新增课件的课程不再整科全勾，只自动勾选这一批新增条目 —— 用户勾这门课
        // 的目的是看/下新增，把几十条旧课件一起勾上会让「下载（N）」虚高并重复下载。
        // 无新增的课程维持原规则（选中课程即全勾其课件）。
        val pendingByCourse = activeCourseIds.associateWith { id -> CoursewareStorage.pendingNewIds(activity, id) }
        val autoPick = coursewareItems.asSequence()
            .filter { it.id !in selectedCoursewareIds && it.id !in manuallyDeselectedIds }
            .filter { item ->
                val pendingNew = pendingByCourse[item.courseId].orEmpty()
                pendingNew.isEmpty() || item.id in pendingNew
            }
            .map { it.id }
            .toSet()
        if (autoPick.isNotEmpty()) selectedCoursewareIds = selectedCoursewareIds + autoPick
    }

    /** 会话内已抓到 + 本地缓存里的全部课件（按 id 去重，会话结果优先）。 */
    private fun allKnownItems(): List<CoursewareUiItem> {
        val fromSession = itemsByCourse.values.flatten()
        val sessionIds = fromSession.map { it.id }.toHashSet()
        val fromCache = academicCache().coursewareByCourse.values.flatten().filter { it.id !in sessionIds }
        return fromSession + fromCache
    }

    private fun allKnownItemIds(): Set<String> = allKnownItems().map { it.id }.toHashSet()

    /** 某课程的课件详情：优先本次会话结果，否则回退本地缓存（展开课程行用）。 */
    fun itemsForCourse(id: String): List<CoursewareUiItem> =
        itemsByCourse[id] ?: academicCache().coursewareByCourse[id].orEmpty()

    private fun refreshCountMap() {
        val cache = academicCache().coursewareByCourse
        coursewareCountsByCourse =
            cache.mapValues { it.value.size } +
            itemsByCourse.filterKeys { it !in cache }.mapValues { it.value.size }
    }

    /**
     * 预取全部课程的课件列表（并发 4），供课程列表显示数目；已有缓存的课程跳过。
     * 失败静默降级：数目徽标只是辅助信息，不弹提示不挂重试，绝不影响主流程。
     */
    fun prefetchAll(courses: List<CourseSummary>) {
        if (!requireLogin()) return
        refreshCountMap()
        val missing = courses.filter { !academicCache().coursewareByCourse.containsKey(it.id) }
        if (missing.isEmpty()) return
        val cookie = cookieHeader()
        val session = sessionEpoch.snapshot(sessionOwner, cookie)
        prefetchRemaining = missing.size
        scope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                missing.forEach { course ->
                    launch {
                        gate.withPermit {
                            runCatching {
                                withContext(Dispatchers.IO) { CoursewareClient(activity, cookie).fetchCourseware(course.id) }
                            }.onSuccess { list ->
                                // 会话世代校验：登出/换号后不把旧账号课件写回缓存
                                if (sessionEpoch.isCurrent(session)) {
                                    val updatedCache = academicCache().withCourseware(course.id, list, System.currentTimeMillis())
                                    setAcademicCache(updatedCache)
                                    setAcademicCacheJson(academicCacheToJson(updatedCache))
                                    refreshCountMap()
                                    // 预取也是一次抓取，记入基线（首次）或判定新增。
                                    CoursewareStorage.observeFetched(activity, course.id, list.map { it.id }.toSet())
                                }
                            }
                            prefetchRemaining = (prefetchRemaining - 1).coerceAtLeast(0)
                        }
                    }
                }
            }
        }
    }

    /** 加载某课程的课件列表。缓存优先：命中且未过期直接返回；强制刷新（forceRefresh）跳过缓存。 */
    fun load(
        course: CourseSummary,
        forceRefresh: Boolean = false,
        alreadyRetriedAfterRecovery: Boolean = false,
    ): Boolean {
        if (!requireLogin()) return false
        setSelectedCourseId(course.id)
        val cached = academicCache().coursewareByCourse[course.id].orEmpty()
        itemsByCourse = itemsByCourse + (course.id to cached)
        rebuildItems()
        coursewareDownloadProgress = ""
        if (!forceRefresh &&
            academicCache().coursewareByCourse.containsKey(course.id) &&
            isAcademicCacheFresh(academicCache().coursewareUpdatedAtMillis[course.id] ?: 0L)
        ) return false
        val gateKey = "courseware:${course.id}"
        if (!requestGate.tryStart(gateKey)) return false
        refreshErrors = refreshErrors - course.id
        loadingIds = loadingIds + course.id
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        scope.runModuleRequest(
            requestGate = requestGate,
            gateKey = gateKey,
            acceptsResult = { sessionEpoch.accepts(session, cookieHeader(), loggedIn()) },
            ioWork = { CoursewareClient(activity, session.cookieHeader).fetchCourseware(course.id) },
            onResult = { result ->
                result.onSuccess { list ->
                    refreshErrors = refreshErrors - course.id
                    val updatedAt = System.currentTimeMillis()
                    val updatedCache = academicCache().withCourseware(course.id, list, updatedAt)
                    val cacheRevision = setAcademicCache(updatedCache)
                    // 大 JSON 序列化+加密 prefs 写入挪后台线程（镜像
                    // ScheduleSectionState.persistSnapshot 范式）；写前校验会话世代，
                    // 防陈旧请求把登出后已清空的缓存写回。
                    val persistCache = updatedCache
                    scope.launch(Dispatchers.IO) {
                        val persistJson = academicCacheToJson(persistCache)
                        if (sessionEpoch.isCurrent(session)) {
                            AcademicCacheSnapshot.persistIfLatest(cacheRevision) {
                                if (sessionEpoch.isCurrent(session)) {
                                    setAcademicCacheJson(persistJson)
                                }
                            }
                        }
                    }
                    itemsByCourse = itemsByCourse + (course.id to list)
                    // 每次抓取都记基线/判新增；首次抓取只建基线，不标"有新增"。
                    // 判定要排在 rebuildItems 之前 —— 自动勾选按「待看新增」收窄，
                    // 若先重算列表再判新增，本次新出现的课件还没进 pending，会被漏勾。
                    CoursewareStorage.observeFetched(activity, course.id, list.map { it.id }.toSet())
                    rebuildItems()
                    show("已读取 ${list.size} 条课件")
                }
                result.onFailure { error ->
                    if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                        refreshErrors = refreshErrors +
                            (course.id to "登录已过期，正在安全续登")
                        setPendingSessionRetry(ModuleReadRetry(ModuleReadRequest.COURSEWARE, course))
                    } else {
                        val errorMessage = refreshFailureMessage(error)
                        refreshErrors = refreshErrors + (course.id to errorMessage)
                        showError("课件读取失败：$errorMessage")
                    }
                }
            },
            releaseLoading = { loadingIds = loadingIds - course.id },
        )
        return true
    }

    /** 勾选状态切换（组合侧 CoursewarePage onToggle 委托）。手动取消的课件不会被自动补回。 */
    fun toggleSelected(id: String) {
        if (id in selectedCoursewareIds) {
            selectedCoursewareIds = selectedCoursewareIds - id
            manuallyDeselectedIds = manuallyDeselectedIds + id
        } else {
            selectedCoursewareIds = selectedCoursewareIds + id
            manuallyDeselectedIds = manuallyDeselectedIds - id
        }
    }

    /** 全选/取消全选（组合侧 CoursewarePage onSelectAll 委托）。 */
    fun toggleSelectAll() {
        val all = coursewareItems.map { it.id }.toSet()
        if (selectedCoursewareIds.size == coursewareItems.size) {
            selectedCoursewareIds = emptySet()
            // 取消全选是用户明确意图，标记为手动取消，避免下一次 rebuild 又自动全选回来
            manuallyDeselectedIds = all
        } else {
            selectedCoursewareIds = all
            manuallyDeselectedIds = emptySet()
        }
    }

    /** 下载勾选的课件：后台并行下载，进度/结果逐条写回 UI。
     *改为非阻塞后台任务 —— 下载期间仍可继续勾选课程/课件，并可再次点下载
     *追加一批（只挑没在下载中的项）；每批独立 requestGate key，互不阻塞。 */
    fun downloadSelected() {
        if (!requireLogin()) return
        // 目标从「全部已知课件」里挑 —— 支持只勾某门课里的几个课件（不必把整门课都选上）。
        // 旧实现只看 coursewareItems（= 已整体选中课程的合并列表），单勾零散课件时 targets 为空，
        // 于是按钮显示「下载 (1)」却提示「请先勾选课件」。
        val targets = allKnownItems().filter {
            it.id in selectedCoursewareIds && it.downloadStatus != COURSEWARE_STATUS_DOWNLOADING
        }
        if (targets.isEmpty()) {
            show(if (downloadUiState.active) "选中的课件都在下载中，可继续勾选其它课件" else "请先勾选课件")
            return
        }
        val batchKey = "downloads-${++downloadBatchSeq}"
        val generation = downloadGeneration
        if (!requestGate.tryStart(batchKey)) return
        cancelRequested.set(false)
        activeDownloadBatches++
        downloadLoading = true
        // 新一批开始时，若上一批已经收尾（active=false），先归零再累计。
        // 旧实现无条件 `downloadUiState.total + targets.size`，而 completed 从上一批
        // 遗留值继续加，导致第二次下载的进度条一打开就显示「已完成」，数字全是错的。
        val batchBase = if (downloadUiState.active) downloadUiState else CoursewareDownloadUiState()
        downloadUiState = batchBase.copy(
            total = batchBase.total + targets.size,
            active = true,
            finished = false,
        )
        val sessionCookie = cookieHeader()
        // 课程名+学期 → 公共下载目录内的子文件夹；一次快照供整批使用。
        val folderByCourse = academicCache().courses.associate { it.id to CoursewareStorage.courseFolderName(it) }
        // 下载不再固定使用发起瞬间的会话快照 —— 应用级全量刷新会续登并改写
        // Cookie，固定快照会让整批请求 401。改为每项下载前读取当下 Cookie，
        // 会话过期时再用最新值重试一次。
        val targetIds = targets.map { it.id }.toSet()
        // 本批所属课程：用户切到别的课程后，进度与条目状态不再写回（见回调处的 scope 判定）。
        val batchCourseIds = targets.map { it.courseId }.toSet()
        // 点下载即取消这批课件的勾选，不影响 targets 快照本身。
        selectedCoursewareIds = selectedCoursewareIds - targetIds
        coursewareItems = coursewareItems.map { item ->
            if (item.id in targetIds) {
                item.copy(downloadStatus = COURSEWARE_STATUS_DOWNLOADING, failureReason = "")
            } else item
        }
        // 只覆盖有课件的课程 key，保留其它课程已抓到的列表，避免多选时被清空。
        itemsByCourse = itemsByCourse + coursewareItems.groupBy { it.courseId }
        coursewareDownloadProgress = "下载进度：下载中 ${targets.size} / 成功 0 / 失败 0"
        // 不再 setBusy(DOWNLOADING_COURSEWARE)；进度改由页面常驻进度区展示，
        // 底部 busy ToastBar 也不再长期占位。
        scope.launch(Dispatchers.IO) {
            // 本批开始前先清掉过期断点残片（实测能堆到 72MB），再走正常下载。
            runCatching { CoursewareClient(activity, sessionCookie).pruneStalePartials() }
            val skipped = AtomicInteger(0)
            try {
                val files = AtomicInteger(0)
                val entries = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val done = AtomicInteger(0)
                val sessionExpired = AtomicBoolean(false)
                runCatching {
                    downloadCoursewareInParallel(targets) { item ->
                        // 取消：本批还没开始的课件直接跳过，状态退回「可下载」。
                        // 已开始的那几个不打断 —— 中断写盘只会在中转目录留残缺 .part。
                        if (cancelRequested.get()) {
                            skipped.incrementAndGet()
                            activity.runOnUiThread {
                                if (generation != downloadGeneration) return@runOnUiThread
                                val release: (CoursewareUiItem) -> CoursewareUiItem = { existing ->
                                    if (existing.id == item.id &&
                                        existing.downloadStatus == COURSEWARE_STATUS_DOWNLOADING
                                    ) {
                                        existing.copy(downloadStatus = COURSEWARE_STATUS_AVAILABLE, failureReason = "")
                                    } else existing
                                }
                                coursewareItems = coursewareItems.map(release)
                                itemsByCourse = itemsByCourse.mapValues { (_, items) -> items.map(release) }
                            }
                            return@downloadCoursewareInParallel
                        }
                        // 每项都用当下 Cookie 建客户端；会话过期再用最新 Cookie 重试一次。
                        val cookieNow = cookieHeader().ifBlank { sessionCookie }
                        val courseFolder = folderByCourse[item.courseId].orEmpty()
                        val first = runCatching { CoursewareClient(activity, cookieNow).download(item, courseFolder) }
                        val outcome = if (first.exceptionOrNull() is MainSessionExpiredException) {
                            sessionExpired.set(true)
                            val fresh = cookieHeader()
                            if (fresh.isNotBlank() && fresh != cookieNow) {
                                runCatching { CoursewareClient(activity, fresh).download(item, courseFolder) }
                            } else first
                        } else first
                        outcome.onSuccess { result ->
                            // 落盘成功后写持久化标记，课件列表显示「已下载」。
                            if (result == COURSEWARE_STATUS_SUCCESS || result == COURSEWARE_STATUS_ENTRY_SAVED) {
                                CoursewareStorage.markDownloaded(activity, item.id)
                                // 重新落盘成功即说明文件存在，之前「检查」记下的缺失标记作废。
                                CoursewareStorage.clearMissingLocal(activity, item.id)
                                missingLocalIds = missingLocalIds - item.id
                                // 该课件从「待看新增」移除；全部下载完则角标自然消失。
                                CoursewareStorage.consumeNewId(activity, item.id)
                            }
                            if (result == COURSEWARE_STATUS_ENTRY_SAVED) entries.incrementAndGet() else files.incrementAndGet()
                        }.onFailure {
                            failed.incrementAndGet()
                        }
                        val error = outcome.exceptionOrNull()
                        val status = outcome.getOrElse { e ->
                            coursewareFailureStatus(
                                if (e is MainSessionExpiredException) ErrorCodes.LOGIN_EXPIRED else e.message.orEmpty()
                            )
                        }
                        // 保留平台返回的原始信息，别把「HTTP 403 / 保存失败」压成一个
                        // 笼统的「网络失败」，否则用户只看到「下载失败」却无从判断原因。
                        val reason = error?.let { e ->
                            android.util.Log.w("XmuCourseware", "download failed: ${item.title}", e)
                            if (e is MainSessionExpiredException) "登录已过期，请重新登录"
                            else {
                                val raw = e.message.orEmpty().ifBlank { e.javaClass.simpleName }
                                val kind = shortCoursewareError(raw)
                                if (raw == kind) kind else "$kind：$raw"
                            }
                        }.orEmpty()
                        val completed = done.incrementAndGet()
                        // boundedParallelMap 的 transform 是同步回调（非 suspend 上下文），
                        // 用 runOnUiThread 切主线程写 Compose 状态（与拆分前行为一致）。
                        activity.runOnUiThread {
                            // 换号/登出已清理界面：旧批次的结果一律丢弃。
                            if (generation != downloadGeneration) return@runOnUiThread
                            val current = downloadUiState
                            downloadUiState = current.copy(
                                completed = current.completed + 1,
                                successFiles = current.successFiles + if (status == COURSEWARE_STATUS_SUCCESS) 1 else 0,
                                successEntries = current.successEntries + if (status == COURSEWARE_STATUS_ENTRY_SAVED) 1 else 0,
                                failed = current.failed + if (outcome.isFailure) 1 else 0,
                                lastFailureReason = if (reason.isNotBlank()) reason else current.lastFailureReason,
                            )
                            // 写回不再依赖 sessionEpoch.accepts(...)。全量刷新/续登会
                            // 推进会话世代或改写 Cookie，旧实现会把整批结果吞掉，界面永远
                            // 停在「下载中」，看起来就像下载失败。登出时 clearAll() 已清空列表，
                            // 旧回调即使回来也匹配不到条目，不会污染新账号数据。
                            if (loggedIn()) {
                                val updatedItem = item.copy(
                                    downloadStatus = status,
                                    failureReason = if (outcome.isSuccess) "" else reason,
                                )
                                coursewareItems = coursewareItems.map { existing ->
                                    if (existing.courseId == item.courseId && existing.id == item.id) {
                                        updatedItem
                                    } else existing
                                }
                                // 未整体选中的课程也可能单独下了几个课件，按课程列表一并写回，
                                // 否则展开行里的状态一直停在「可下载」。
                                itemsByCourse[item.courseId]?.let { perCourse ->
                                    itemsByCourse = itemsByCourse + (
                                        item.courseId to perCourse.map { existing ->
                                            if (existing.id == item.id) updatedItem else existing
                                        }
                                        )
                                }
                                // 用户仍停留在这批课程的上下文才刷新进度文案；切到别的课程后
                                // 不回填，避免新课程页面冒出上一批的「下载进度」。
                                // 文件与「已下载」标记已持久化，切回时自然呈现。
                                val stillOnBatchCourse = activeCourseIds.any { it in batchCourseIds } ||
                                    selectedCourseId()?.let { it in batchCourseIds } == true
                                if (stillOnBatchCourse) {
                                    coursewareDownloadProgress =
                                        "下载进度：下载中 ${targets.size - completed} / 成功 ${files.get() + entries.get()} / 失败 ${failed.get()}"
                                }
                            }
                        }
                    }
                }.onFailure { error ->
                    // 协程取消不是「下载失败」：重抛，不弹失败 toast（转屏/离开页面属正常取消）
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    withContext(Dispatchers.Main) {
                        showError("下载失败：${friendlyMessage(error)}")
                    }
                }
                if (sessionExpired.get() && generation == downloadGeneration) {
                    // 会话确实过期：登记一次性安全续登，恢复后重新点下载即可。
                    withContext(Dispatchers.Main) {
                        setPendingSessionRetry(ModuleReadRetry(ModuleReadRequest.COURSES))
                    }
                }
                withContext(Dispatchers.Main) {
                    if (generation == downloadGeneration && loggedIn() && done.get() == targets.size) {
                        val message = "本批下载完成：文件 ${files.get()}，入口 ${entries.get()}，" +
                            "失败 ${failed.get()} · 已保存到 $downloadDirectoryPath"
                        // 无失败走绿色成功提示；有失败才用红色告警。
                        if (failed.get() == 0) showSuccess(message) else showError(message)
                    }
                }
            } finally {
                // 收尾（复位 busy/loading、释放互斥门）必须随取消也能执行
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (busy() == BusyStates.DOWNLOADING_COURSEWARE) setBusy(BusyStates.IDLE)
                    // 世代已变说明 clearAll 复位过计数与界面，旧批次不再重复收尾
                    // （否则会 -1 到负数、或把刚清空的进度条标成「已完成」）。
                    if (generation == downloadGeneration) {
                        activeDownloadBatches--
                        if (activeDownloadBatches <= 0) {
                            activeDownloadBatches = 0
                            downloadLoading = false
                            // 取消只在全部批次停下后才收尾：此时没有写盘任务在跑，
                            // 删 .part 才是安全的。
                            if (cancelRequested.getAndSet(false)) {
                                val removed = withContext(Dispatchers.IO) {
                                    CoursewareStorage.deletePartialArtifacts(activity)
                                }
                                coursewareDownloadProgress = ""
                                downloadUiState = CoursewareDownloadUiState()
                                val skippedCount = skipped.get()
                                show(
                                    if (skippedCount > 0) "已取消：跳过 $skippedCount 项未开始的课件，已清理 $removed 个残片"
                                    else "已取消下载"
                                )
                            } else {
                                downloadUiState = downloadUiState.copy(active = false, finished = true)
                            }
                        }
                    }
                    requestGate.finish(batchKey)
                }
            }
        }
    }

    /**重试上一批下载失败的课件（须等当前批次结束后）。 */
    fun retryFailed() {
        if (activeDownloadBatches > 0) {
            show("请等当前下载结束再重试")
            return
        }
        val failedIds = coursewareItems
            .filter { it.downloadStatus.startsWith(COURSEWARE_STATUS_FAILED) }
            .map { it.id }
            .toSet()
        if (failedIds.isEmpty()) {
            show("没有需要重试的课件")
            return
        }
        coursewareItems = coursewareItems.map { item ->
            if (item.id in failedIds) {
                item.copy(downloadStatus = COURSEWARE_STATUS_AVAILABLE, failureReason = "")
            } else item
        }
        itemsByCourse = itemsByCourse + coursewareItems.groupBy { it.courseId }
        selectedCoursewareIds = selectedCoursewareIds + failedIds
        manuallyDeselectedIds = manuallyDeselectedIds - failedIds
        coursewareDownloadProgress = ""
        downloadUiState = CoursewareDownloadUiState()
        downloadSelected()
    }

    /**
     * 「检查」：扫一遍本地目录，做两件事。
     *
     * 1. 补标：下载过、但本机没记住标记的课件，本地有文件就补成「已下载」。
     * 2. 报缺：已经标记为已下载、本地目录却找不到文件的，记进 [missingLocalIds]，
     *    界面上把角标显示成「？已下载」，课程行的「已全部下载」也换成警示色。
     *
     * 下载标记本身只增不减：文件可能被用户挪走、改名或删在别的相册/网盘里，
     * 找不到不等于没下过，所以这里不改 [CoursewareStorage.downloadedIds]。
     * 匹配按（课程文件夹 + 落盘文件名）精确比对，不做跨科目模糊匹配。
     */
    fun checkLocalFiles() {
        if (downloadUiState.active) {
            show("下载进行中，请先取消再检查")
            return
        }
        val known = allKnownItems()
        if (known.isEmpty()) {
            show("当前没有需要检查的课件")
            return
        }
        val downloaded = CoursewareStorage.downloadedIds(activity)
        val folderByCourse = academicCache().courses.associate { it.id to CoursewareStorage.courseFolderName(it) }
        scope.launch(Dispatchers.IO) {
            val index = CoursewareStorage.localFileIndex(activity)
            val presentIds = known.filter { item ->
                val folder = folderByCourse[item.courseId].orEmpty()
                localFileNames(item).any { CoursewareStorage.containsLocalFile(index, folder, it) }
            }.map { it.id }.toSet()
            val result = classifyLocalCheck(known.map { it.id }, downloaded, presentIds)
            // 补标：本地有文件、但还没标记的。
            result.hits.forEach { id ->
                CoursewareStorage.markDownloaded(activity, id)
                CoursewareStorage.clearMissingLocal(activity, id)
                CoursewareStorage.consumeNewId(activity, id)
            }
            // 报缺：标记过已下载、本地扫不到的。
            val missing = result.missing
            CoursewareStorage.setMissingLocalIds(activity, missing)
            val hitIds = result.hits
            val markedCount = known.count { it.id in downloaded }
            withContext(Dispatchers.Main) {
                missingLocalIds = missing
                if (hitIds.isNotEmpty()) {
                    coursewareItems = coursewareItems.map { applyLocalHit(it, hitIds) }
                    itemsByCourse = itemsByCourse.mapValues { (_, items) -> items.map { applyLocalHit(it, hitIds) } }
                }
                show(
                    when {
                        hitIds.isNotEmpty() && missing.isNotEmpty() ->
                            "已标记 ${hitIds.size} 项为已下载；另有 ${missing.size} 项标记为已下载但本地找不到文件"
                        hitIds.isNotEmpty() -> "已标记 ${hitIds.size} 项为已下载"
                        missing.isNotEmpty() -> "${missing.size} 项标记为已下载，但本地找不到文件"
                        markedCount == 0 -> "本地未找到这些课件的文件"
                        else -> "已下载的 $markedCount 项本地文件都在"
                    },
                )
            }
        }
    }

    private fun applyLocalHit(item: CoursewareUiItem, hitIds: Set<String>): CoursewareUiItem =
        if (item.id in hitIds) item.copy(downloadStatus = COURSEWARE_STATUS_SUCCESS, failureReason = "") else item

    /** 单个课件可能的落盘文件名：正常文件用条目文件名，入口类下载的是「标题.url」快捷方式。 */
    private fun localFileNames(item: CoursewareUiItem): List<String> =
        listOf(
            coursewareFileName(item),
            coursewareSafeFileName("${item.filename.ifBlank { item.title }}.url"),
        ).distinct()

    /**
     * 「取消」：本批尚未开始的课件直接跳过（已开始的几个照常收尾，中断写盘只会留下
     * 残缺 .part），全部批次停下后再统一删掉 .part 残片并把进度区复位成「待下载」。
     */
    fun cancelDownloads() {
        if (!downloadUiState.active || activeDownloadBatches <= 0) {
            show("当前没有进行中的下载")
            return
        }
        cancelRequested.set(true)
        show("正在取消尚未开始的下载")
    }

    /** 打开选中的课件（平台内链优先，缺省回退到课程学习页）。 */
    fun openSelected() {
        val item = coursewareItems.firstOrNull { it.id in selectedCoursewareIds } ?: coursewareItems.firstOrNull()
        if (item == null) {
            show("请先选择课件")
            return
        }
        val url = item.sourceUrl.ifBlank { "https://lnt.xmu.edu.cn/course/${item.courseId}/learning-activity#/${item.activityId}" }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            show("无法打开平台页面")
        }
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        loadingIds = emptySet()
        downloadLoading = false
    }

    /** 登出/换号清理：清课件内存状态（课程列表/缓存由组合侧一并清理，与拆分前一致）。 */
    fun clearAll() {
        coursewareItems = emptyList()
        itemsByCourse = emptyMap()
        activeCourseIds = emptySet()
        loadingIds = emptySet()
        refreshErrors = emptyMap()
        selectedCoursewareIds = emptySet()
        coursewareDownloadProgress = ""
        downloadUiState = CoursewareDownloadUiState()
        activeDownloadBatches = 0
        downloadLoading = false
        // 作废在飞的批次：旧回调按世代丢弃，不会把进度与条目写回新账号。
        downloadGeneration++
    }
}
