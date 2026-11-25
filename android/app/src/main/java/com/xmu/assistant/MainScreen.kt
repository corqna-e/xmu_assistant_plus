package com.xmu.assistant

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 考试安排模块组合侧持有者：rememberSaveable + Saver 恢复用户选择的学期与"是否手动选择"标记。
 * （从 XmuAssistantApp 提取，调用位置不变；summary/validTerms 等其余状态由
 *ExamSectionState 构造后的 init 协程从磁盘缓存异步回填。）
 */
@Composable
internal fun rememberExamSection(
    activity: ComponentActivity,
    requestGate: RequestGate,
    sessionEpoch: SessionEpoch,
    sessionOwner: SessionOwner,
    scope: CoroutineScope,
    show: (String) -> Unit,
    showWarning: (String) -> Unit,
    showError: (String) -> Unit,
    loggedIn: () -> Boolean,
    cookieHeader: () -> String,
    username: () -> String,
    password: () -> String,
    accountTransitionInProgress: () -> Boolean,
    scoreCookieHeader: () -> String,
    setScoreCookieHeader: (String) -> Unit,
    reminderSettings: () -> ExamReminderSettings,
): ExamSectionState {
    return rememberSaveable(
        saver = Saver(
            save = { state -> state.savedState() },
            restore = { saved ->
                ExamSectionState(
                    activity = activity,
                    requestGate = requestGate,
                    sessionEpoch = sessionEpoch,
                    sessionOwner = sessionOwner,
                    scope = scope,
                    show = show,
                    showWarning = showWarning,
                    showError = showError,
                    loggedIn = loggedIn,
                    cookieHeader = cookieHeader,
                    username = username,
                    password = password,
                    accountTransitionInProgress = accountTransitionInProgress,
                    scoreCookieHeader = scoreCookieHeader,
                    setScoreCookieHeader = setScoreCookieHeader,
                    reminderSettings = reminderSettings,
                    selectedTermInitial = (saved as? List<*>)?.getOrNull(0) as? String ?: "",
                    manuallySelectedInitial = (saved as? List<*>)?.getOrNull(1) as? Boolean ?: false,
                )
            },
        ),
    ) {
        ExamSectionState(
            activity = activity,
            requestGate = requestGate,
            sessionEpoch = sessionEpoch,
            sessionOwner = sessionOwner,
            scope = scope,
            show = show,
            showWarning = showWarning,
            showError = showError,
            loggedIn = loggedIn,
            cookieHeader = cookieHeader,
            username = username,
            password = password,
            accountTransitionInProgress = accountTransitionInProgress,
            scoreCookieHeader = scoreCookieHeader,
            setScoreCookieHeader = setScoreCookieHeader,
            reminderSettings = reminderSettings,
        )
    }
}

/**
 * 首页只读渲染态分组：
 * 仅聚合「值语义」的展示输入；凡读写器成对的 stale-guard 参数组
 * （accountTransition 設置器、monitorTransition 全家、busyNow 读取器等）
 * 一律保持独立成对参数，不收拢以免破坏守卫语义。
 * 体内经 component 解构回原名，渲染逻辑与契约锚定文本零变更。
 */
internal data class IdentityUiState(
    val username: String,
    val password: String,
    val loggedIn: Boolean,
)

/** 监控运行展示六件套（B2 方案 A 并入 transition 值字段）：值语义展示输入。
 *写路径（setMonitorTransition*）与 *Now 读取器仍是独立成对参数——异步回调读最新值
 *防世代误判（见形参区注释），不随 UiState 快照重组（方案 A 保守边界，方案 B 另议）。 */
internal data class MonitorUiState(
    val monitorRunning: Boolean,
    val monitorConsecutiveFailures: Int,
    val monitorLastCheckMillis: Long,
    val monitorLastError: String,
    val monitorTransitionInProgress: Boolean,
    val monitorTransitionId: Long,
)

/** 课程列表页读取态：配套 setter 仍在 MainScreen 形参（组合侧写路径）。 */
internal data class CoursesUiState(
    val courses: List<CourseSummary>,
    val selectedCourse: CourseSummary?,
    val coursesLoading: Boolean,
    val coursesRefreshError: String,
)

/**
 * 瞬时状态展示三件套（参数收敛第二批）：单条 toast 文案 / busy 文案 / 下载角标数，
 * 外加 toast 的显式级别（B4，默认 INFO）。
 * 值语义展示输入；busy 的写路径（setBusy）与 toast 的清空仍走组合侧独立参数。
 */
internal data class StatusUiState(
    val toast: String,
    val toastSeverity: ToastSeverity,
    val busy: String,
    val downloadingCount: Int,
)

/** 监控状态写路径分组（B6 第二批，沿 e04e381 归组手法）：纯写通道聚合，
 *字段名与原独立参数逐字相同；*Now 读取器保持独立参数（世代守卫，不随组）。 */
internal class MonitorWritePath(
    val setMonitorRunning: (Boolean) -> Unit,
    val setMonitorTransitionId: (Long) -> Unit,
    val setMonitorTransitionInProgress: (Boolean) -> Unit,
    val setMonitorLastCheckMillis: (Long) -> Unit,
    val setMonitorConsecutiveFailures: (Int) -> Unit,
    val setMonitorLastError: (String) -> Unit,
)

/** 课程列表页写路径分组（同上）：纯写通道聚合，字段名与原独立参数逐字相同。 */
internal class CoursesWritePath(
    val setCourses: (List<CourseSummary>) -> Unit,
    val setSelectedCourseId: (String?) -> Unit,
    val setCoursesLoading: (Boolean) -> Unit,
    val setCoursesRefreshError: (String) -> Unit,
)

/**
 * 主界面 UI 骨架（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 组合侧（XmuAssistantApp）只保留状态与编排，本文件只做页面渲染：
 * - 各模块状态由 SectionState 对象承载（exam/rollcall/scores/schedule/courseware），
 *通过参数注入；组合侧状态用读写器（函数式注入，与 SectionState 模式一致）。
 * - 页面内联回调（登录/登出/监控开关/桌面小卡片/手动周次等）与拆分前逐字对齐，
 *全部依赖经构造参数注入，行为零变更。
 */
@Composable
internal fun MainScreen(
    activity: ComponentActivity,
    workScope: CoroutineScope,
    homeActions: HomeActions,
    page: Page,
    identity: IdentityUiState,
    setUsername: (String) -> Unit,
    setPassword: (String) -> Unit,
    notificationSettings: NotificationSettings,
    onNotificationSettingsSaved: (NotificationSettings) -> Unit,
    status: StatusUiState,
    accountTransitionInProgress: Boolean,
    monitor: MonitorUiState,
    monitorWrites: MonitorWritePath,
    // 回调守卫用「当前值读取器」：组合侧状态经 setter 写入后，重组是异步的，
    // 协程回调里读参数快照拿到的是点击时的旧值（导致守卫误判提前 return）。
    // 因此暂停/登出等异步完成回调必须经读取器取最新值，与 SectionState 的函数式注入一致。
    rollcallSettings: RollcallSettings,
    setRollcallSettings: (RollcallSettings) -> Unit,
    themeMode: String,
    setThemeMode: (String) -> Unit,
    widgetGuideOpen: Boolean,
    setWidgetGuideOpen: (Boolean) -> Unit,
    widgetPermissionGuideOpen: Boolean,
    setWidgetPermissionGuideOpen: (Boolean) -> Unit,
    courseState: CoursesUiState,
    courseWrites: CoursesWritePath,
    selectedCourseIds: List<String>,
    setSelectedCourseIds: (List<String>) -> Unit,
    coursesUpdatedAtMillis: Long,
    coursewareUpdatedAtMillis: Long,
    examReminder: ExamReminderSettings,
    onExamReminderChanged: (ExamReminderSettings) -> Unit,
    onOpenExamAlarmSettings: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    rollcall: RollcallSectionState,
    scores: ScoreSectionState,
    exam: ExamSectionState,
    schedule: ScheduleSectionState,
    courseware: CoursewareSectionState,
    settings: AssistantSettings,
    requireLogin: () -> Boolean,
    show: (String) -> Unit,
    onModuleEntered: (Page) -> Unit,
    backTarget: Page,
    testNotifications: (NotificationSettings) -> Unit,
    onRefreshCourses: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    refreshMonitorHealth: () -> Unit,
    requestNotificationPermissionIfNeeded: () -> Unit,
    /**底栏「首页」键当页再次点击 → 刷新各页面数据（课表/签到/考试/成绩/课件）。 */
    onHomeRefresh: () -> Unit = {},
    /**桌面小组件指定的课表视图与请求序号（WEEK / AGENDA）。 */
    scheduleViewRequest: String = "",
    scheduleViewRequestSeq: Int = 0,
    /**长按图标「自动签到」快捷方式的开关请求序号（0 表示本次没有请求）。 */
    autoToggleSeq: Int = 0,
) {
    // App 内切换外观、或系统深浅色切换导致 Activity 重建时，立刻重建两张桌面卡片，
    // 让组件配色与 App 内「外观」设置一致（RemoteViews 存的是静态颜色，不会自己跟着变）。
    LaunchedEffect(themeMode) {
        runCatching {
            ScheduleWidgetProvider.refreshAll(activity)
            ScheduleWeekWidgetProvider.refreshAll(activity)
        }
    }
    // 只读态就地解构回原参数名：本文件既有渲染代码零改动
    // （字段名与拆分前形参一一对应）。
    val (username, password, loggedIn) = identity
    val (monitorRunning, monitorConsecutiveFailures, monitorLastCheckMillis, monitorLastError) = monitor
    // 写路径组解构回原名（B6）：体内既有调用点零改动；仅取实际使用的通道。
    val setMonitorRunning = monitorWrites.setMonitorRunning
    val (courses, _, coursesLoading, coursesRefreshError) = courseState
    val (toast, toastSeverity, busy, downloadingCount) = status
    // 设置页写入的是 prefs，不是 Compose State；递增此值让课表页重读校准周次。
    val manualWeekRevision = remember { mutableIntStateOf(0) }
    // 底栏键当页再次点击的行为载体 ——
    // 课表视图切换用自增序号传给 SchedulePage；首页滚动状态由渲染侧注册上来。
    val scheduleViewToggle = remember { mutableIntStateOf(0) }
    var homeScrollState by remember { mutableStateOf<ScrollState?>(null) }
    // 教程页右侧悬浮跳模块键 —— 滚动状态与四模块定位状态由外层持有。
    var tutorialScrollState by remember { mutableStateOf<ScrollState?>(null) }
    val tutorialJump = remember { TutorialJumpState() }
    // 考试并入课表 —— 从本地缓存读全部学期的考试（纯本地读盘，不发请求），
    // 这样不必先进入考试页，课表里也能看到考试；exam.summary 变化时重读一次。
    var cachedExams by remember { mutableStateOf(emptyList<XmuExam>()) }
    LaunchedEffect(exam.summary) {
        cachedExams = withContext(Dispatchers.IO) { ExamCache.loadAllExams(activity) }
    }
    // 策略页与「当前周次」二级页共用同一套校准流程：写手动周次 → 反推开学日 → 同步 widget。
    fun applyManualWeek(week: Int) {
        settings.setManualAcademicWeek(schedule.termCode, week)
        manualWeekRevision.intValue++
        // 手动指定周次时，用「手动周次 + 当天日期」反推开学日并缓存。
        // 手动路径不受 60 天窗口限制；
        // 但仍校验开学日落在合理月份窗口，防暑假把日期反推到假期里。
        if (week > 0 && schedule.termCode.isNotBlank()) {
            val inferred = xmuTryInferCalendar(
                termCode = schedule.termCode,
                currentWeek = week,
                today = LocalDate.now(),
                maxStartAgeDays = 200L,
            )
            if (inferred != null) {
                schedule.setInferredCalendar(schedule.termCode, inferred)
                show("已设为第 ${week} 周，开学日期已校准")
            } else {
                show(
                    "已设为第 ${week} 周，但日期不在合理开学窗口内，" +
                        "开学后会自动校准（或确认当前周次后重试）",
                )
            }
        } else if (week == 0) {
            // 恢复自动判断：清除该学期的手动校准缓存，
            // 让页面回到官方校历/自动反推，而不是继续用旧的手动结果。
            schedule.removeInferredCalendar(schedule.termCode)
            show("已恢复自动判断周次")
        }
        // 手动校准后同步桌面 Widget，保持与课表页一致
        if (schedule.termCode.isNotBlank()) {
            syncScheduleWidget(
                activity,
                schedule.entries,
                schedule.termCode,
                inferredCalendar = schedule.cache.inferredCalendars[schedule.termCode],
                manualWeek = settings.manualAcademicWeek(schedule.termCode),
            )
        }
    }
    // 启动提醒的唯一入口：首页「启动提醒」按钮与「打开自动签到时自动启动」共用，
    // 避免两处逻辑漂移。
    // 返回是否真的启动了；调用方据此决定提示文案。
    fun startMonitor(toastOnStarted: Boolean = true): Boolean {
        if (monitor.monitorTransitionInProgress || accountTransitionInProgress || !requireLogin()) return false
        requestNotificationPermissionIfNeeded()
        settings.monitorDesired = true
        activity.startForegroundService(Intent(activity, RollcallMonitorService::class.java))
        MonitorControlTileService.requestResync(activity)
        setMonitorRunning(true)
        refreshMonitorHealth()
        if (toastOnStarted) show("签到监控已开启")
        return true
    }
    // 自动签到的唯一写入口：首页卡片开关与长按图标快捷方式共用，行为不会两处漂移。
    // stopMonitorWhenDisabled 仅快捷方式用 —— 它的语义是「开关自动签到的同时启闭监控」，
    // 而首页开关按原设计只改策略、保留签到提醒。
    fun applyAutoAnswer(enabled: Boolean, stopMonitorWhenDisabled: Boolean = false) {
        val next = toggleAllAutoAnswer(rollcallSettings, enabled)
        setRollcallSettings(next)
        settings.saveRollcall(next)
        // 长按菜单上的文案跟着翻转（「开启自动签到 / 关闭自动签到」）。
        AppShortcuts.update(activity)
        when {
            enabled -> {
                if (!monitorRunning) {
                    val started = startMonitor(toastOnStarted = false)
                    show(
                        if (started) "已开启自动签到 · 已自动启动签到监控"
                        else "已开启数字和雷达自动处理"
                    )
                } else {
                    show("已开启数字和雷达自动处理")
                }
            }
            stopMonitorWhenDisabled && monitorRunning -> {
                show("已关闭自动签到，正在停止签到监控")
                homeActions.onStopMonitor(monitor.monitorTransitionInProgress, monitor.monitorTransitionId)
            }
            else -> show("已关闭数字和雷达自动处理，仍会保留签到提醒")
        }
    }
    // 长按图标「自动签到」快捷方式：进 App 后按当前状态取反执行。
    // 用序号消费（不是布尔值）：连点同一条快捷方式每次都要生效。
    // consumed 走 rememberSaveable：转屏重建时 intent 里的 extra 仍在，
    // 序号会被重新解析成 1，若 consumed 归零就会把开关再翻一次。
    var consumedAutoToggleSeq by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(autoToggleSeq) {
        if (autoToggleSeq == 0 || autoToggleSeq <= consumedAutoToggleSeq) return@LaunchedEffect
        consumedAutoToggleSeq = autoToggleSeq
        if (!loggedIn) {
            show("请先登录，再开启自动签到")
            return@LaunchedEffect
        }
        val autoOn = rollcallSettings.autoAnswerNumber || rollcallSettings.autoAnswerRadar
        applyAutoAnswer(enabled = !autoOn, stopMonitorWhenDisabled = true)
    }
    val radarCoordinateStore = remember(activity) { RadarCoordinateStore(activity) }
    var coordinateTarget by remember(username, loggedIn) { mutableStateOf<CourseSummary?>(null) }
    val currentSemesterCoordinateCourses = remember(courses, schedule.termCode, schedule.entries) {
        currentTermCoordinateCourses(courses, schedule.termCode, schedule.entries)
    }
    val currentTermCoordinateTarget = remember(coordinateTarget, currentSemesterCoordinateCourses) {
        coordinateTarget?.let { target ->
            currentSemesterCoordinateCourses.firstOrNull { course ->
                (course.id.isNotBlank() && course.id == target.id) ||
                    normalizeCourseTitle(course.title) == normalizeCourseTitle(target.title)
            }
        }
    }

    // 桌面小卡片 pin 结果延时校验（批次四·两段重复 postDelayed 合并 + 可取消化）：
    // 3 秒后 widget 实例数未增加即判定失败并触发对应回退引导。
    // 原实现用裸 Handler.postDelayed，不随组合销毁取消——切页/退出后定时器
    // 仍会命中并弹引导；改走 workScope 协程随组合销毁自动取消。
    fun verifyPinAdded(onPinFailed: () -> Unit) {
        val provider = ComponentName(activity, ScheduleWidgetProvider::class.java)
        val before = AppWidgetManager.getInstance(activity)
            .getAppWidgetIds(provider).size
        workScope.launch {
            delay(3000)
            val after = AppWidgetManager.getInstance(activity)
                .getAppWidgetIds(provider).size
            if (after <= before) onPinFailed()
        }
    }

    XmuMobileTheme(themeMode = themeMode) {
        // 课件统一写公共「下载/xmu助手plus」（与长图、证明 PDF 同目录），不需要授权目录。
        // 「打开下载目录」让系统文件管理器定位到主存储的 Download 目录；
        // 部分文件管理器不响应这个 Intent，给出可照抄的路径兜底提示。
        fun openCoursewareDownloadDirectory() {
            if (CoursewareStorage.openFolder(activity)) return
            show("请在文件管理器里进入：${CoursewareStorage.describe()}")
        }
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val dark = LocalXmuDarkTheme.current
        DisposableEffect(activity, dark) {
            val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            onDispose { }
        }
        AppBackNavigation(page, backTarget, onSelected = onModuleEntered)
        // 外层用 surface（白）承接系统状态栏/导航栏区域，
        // 内容区的浅蓝白背景改为画在 safeDrawingPadding() 之后（只覆盖安全区内部）。
        // 之前 background 画在 safeDrawingPadding() 之前，把底部手势条/三键区域也刷成
        // 浅蓝白，看起来像「底部导航栏没铺满白色」。
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // 底部提示的「值」先算好，但渲染移到本 Box 末尾的覆盖层，
                // 不再作为 Column 的普通子项参与高度计算（否则 toast 一出现就压矮
                // 内容区，课件页右下角的「下载」悬浮键会被顶上去）。
                // 单条优先级：警告类 toast > 进行中 busy > 后台成绩刷新。
                val toastSubstringWarning = toast.contains("失败") || toast.contains("请先")
                val activeToast: String = when {
                    toast.isNotBlank() -> toast
                    busy.isNotBlank() -> busy
                    // 跨页不残留「正在刷新成绩」：仅成绩页计入其 loading
                    scores.loading && page == Page.SCORES -> "正在刷新成绩"
                    else -> ""
                }
                val activeSeverity = when {
                    toast.isNotBlank() && toastSeverity != ToastSeverity.INFO -> toastSeverity
                    toast.isNotBlank() && toastSubstringWarning -> ToastSeverity.WARNING
                    else -> ToastSeverity.INFO
                }
                // 不再给整个 Column 加水平内边距或 760dp 宽度上限，
                // 否则底部导航栏永远左右留白、宽屏还会居中收窄。
                // 水平 13dp 内边距下放到标题栏／内容区／提示条三个子项，底栏保持铺满。
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        // 去掉底部 6dp，让底栏直接贴到安全区底，
                        // 与下方白色系统导航条区域连成完整一块。
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp)) {
                        BrandHeader(
                            loggedIn = loggedIn,
                            page = page,
                            titleOverride = if (page == Page.COURSE_LOCATION && currentTermCoordinateTarget != null) {
                                "${currentTermCoordinateTarget.title} · 课程坐标"
                            } else null,
                            showBack = page != Page.HOME && primaryDestinations.none { it.page == page },
                            onBack = { onModuleEntered(backTarget) },
                            // 底栏四页是一级界面，标题不做字母遮蔽。
                            primaryPage = page == Page.HOME || primaryDestinations.any { it.page == page },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp),
                    ) {
                        PageStateHost(page = page, owner = if (loggedIn) username else "") {
                        when (page) {
                            // 课表页自带滚动容器 + 右下角悬浮缩放面板，
                            // 必须放在共享 verticalScroll 之外，否则面板会随内容滚走。
                            Page.SCHEDULE -> key(page) {
                                SchedulePage(
                                    entries = schedule.entries,
                                    termCode = schedule.termCode,
                                    updatedAtMillis = schedule.updatedAtMillis,
                                    loading = schedule.loading,
                                    refreshError = schedule.refreshError,
                                    loggedIn = loggedIn,
                                    onRefresh = { schedule.refresh() },
                                    inferredCalendar = schedule.cache.inferredCalendars[schedule.termCode],
                                    // 学期切换或设置页校准后重读，避免课表继续显示旧周次。
                                    manualWeek = remember(schedule.termCode, manualWeekRevision.intValue) {
                                        settings.manualAcademicWeek(schedule.termCode)
                                    },
                                    // 缩放条靠它决定要不要临时上让提示条。
                                    toastVisible = activeToast.isNotBlank(),
                                    // 底栏「课表」键当页再次点击 → 周课表/日程互换。
                                    viewToggleSeq = scheduleViewToggle.intValue,
                                    // 考试并入课表（抓取 + 自定义）。
                                    examEntries = cachedExams,
                                    customExamEntries = exam.customExams,
                                    // 桌面小组件点开时落到指定视图。
                                    requestedView = scheduleViewRequest,
                                    requestedViewSeq = scheduleViewRequestSeq,
                                    // 课表页顶部「第N周 · 本周」点击 → 设置 - 当前周次内页。
                                    onOpenManualWeek = { onModuleEntered(Page.CURRENT_WEEK) },
                                )
                            }
                            Page.ROLLCALL -> RollcallStatusPage(
                                events = rollcall.events,
                                loading = rollcall.loading,
                                refreshError = rollcall.refreshError,
                                updatedAtMillis = rollcall.updatedAtMillis,
                                historyItems = rollcall.historyItems,
                                historyLoading = rollcall.historyLoading,
                                historyError = rollcall.historyError,
                                historyUpdatedAtMillis = rollcall.historyUpdatedAtMillis,
                                loggedIn = loggedIn,
                                coordinateStore = radarCoordinateStore,
                                onManageCoordinates = { courseId, courseTitle ->
                                    coordinateTarget = CourseSummary(courseId, courseTitle)
                                    onModuleEntered(Page.COURSE_LOCATION)
                                },
                                forceAnswering = rollcall.forceAnswering,
                                // 「立即进行当前签到」：不看策略、监控与自动签到开关，
                                // 直接把进行中的签到提交掉；没有进行中的签到才按约定
                                // 打开监控与自动签到。
                                onForceAnswer = {
                                    rollcall.forceAnswerNow(
                                        onNoActive = {
                                            val next = toggleAllAutoAnswer(rollcallSettings, true)
                                            setRollcallSettings(next)
                                            settings.saveRollcall(next)
                                            AppShortcuts.update(activity)
                                            if (!monitorRunning) startMonitor(toastOnStarted = false)
                                            show("当前没有进行中的签到，已开启监控与自动签到")
                                        },
                                    )
                                },
                                onRefresh = {
                                    // 顶部按钮一键刷两块（独立互斥门，可并行）
                                    rollcall.refresh()
                                    rollcall.refreshHistory()
                                },
                            )
                            Page.COURSE_LOCATION -> CourseCoordinatePage(
                                courses = currentSemesterCoordinateCourses,
                                initialCourse = currentTermCoordinateTarget,
                                store = radarCoordinateStore,
                                show = show,
                                onCourseSelected = { coordinateTarget = it },
                                onAddPlace = { onModuleEntered(Page.ADD_PLACE) },
                                onCapture = { onModuleEntered(Page.ON_SITE_CAPTURE) },
                            )
                            Page.ADD_PLACE -> AddRadarPlacePage(
                                courses = currentSemesterCoordinateCourses,
                                initialCourse = currentTermCoordinateTarget,
                                store = radarCoordinateStore,
                                show = show,
                                onSaved = { onModuleEntered(Page.COURSE_LOCATION) },
                                onCapture = { onModuleEntered(Page.ON_SITE_CAPTURE) },
                            )
                            Page.ON_SITE_CAPTURE -> OnsiteRadarCapturePage(
                                courses = currentSemesterCoordinateCourses,
                                initialCourse = currentTermCoordinateTarget,
                                store = radarCoordinateStore,
                                show = show,
                                onSaved = { onModuleEntered(Page.COURSE_LOCATION) },
                            )
                            Page.SCORES -> ScorePage(
                                records = scores.scoreRecords,
                                loading = scores.loading,
                                refreshError = scores.refreshError,
                                loggedIn = loggedIn,
                                updatedAtMillis = scores.updatedAtMillis,
                                onRefresh = { scores.refresh() },
                                onShareScore = { scores.shareLongImage() },
                                rankLabel = scores.ranking.label,
                                overallRank = scores.ranking.overallPosition,
                                onOpenRank = { onModuleEntered(Page.RANKING) },
                            )
                            Page.RANKING -> RankPage(scores.ranking) { onModuleEntered(Page.SCORES) }
                            Page.EXAMS -> ExamPreviewPage(
                                summary = exam.summary,
                                validTerms = exam.validTerms,
                                selectedTerm = exam.selectedTerm,
                                loading = exam.loading,
                                refreshError = exam.refreshError,
                                loggedIn = loggedIn,
                                onSelectTerm = exam::selectTerm,
                                onRefresh = exam::refresh,
                                // 自定义考试（手动输入）与抓取项分开展示、可增删改。
                                customExams = exam.customExams,
                                onSaveCustom = exam::saveCustomExam,
                                onDeleteCustom = { exam.deleteCustomExam(it.id) },
                                // 节次选择用的真实作息时间（来自本学期课表）。
                                scheduleEntries = schedule.entries,
                            )
                            Page.COURSEWARE -> {
                                // 进入课件页后预取各课程课件数目（缓存优先，并发 4，失败静默）。
                                LaunchedEffect(courses, loggedIn) {
                                    if (loggedIn && courses.isNotEmpty()) courseware.prefetchAll(courses)
                                }
                                CoursewarePreviewPage(
                                courses = courses,
                                selectedCourseIds = selectedCourseIds.toSet(),
                                coursewareItems = courseware.coursewareItems,
                                selectedIds = courseware.selectedCoursewareIds,
                                onRefreshCourses = onRefreshCourses,
                                onCoursesSelected = { ids ->
                                    val added = ids - selectedCourseIds.toSet()
                                    setSelectedCourseIds(ids.toList())
                                    courseware.setActiveCourses(ids)
                                    // 新勾选的课程才发起读取；缓存新鲜时 load 内部直接返回。
                                    added.forEach { id ->
                                        courses.firstOrNull { it.id == id }?.let { course -> courseware.load(course) }
                                    }
                                },
                                onToggle = courseware::toggleSelected,
                                onSelectAll = courseware::toggleSelectAll,
                                onDownload = courseware::downloadSelected,
                                onRefreshCourseware = {
                                    val targets = courses.filter { it.id in selectedCourseIds }
                                    if (targets.isEmpty()) show("请先选择课程")
                                    else targets.forEach { courseware.load(it, forceRefresh = true) }
                                },
                                onOpenPlatform = courseware::openSelected,
                                toastVisible = activeToast.isNotBlank(),
                                downloadProgress = courseware.coursewareDownloadProgress,
                                coursesLoading = coursesLoading,
                                coursesRefreshError = coursesRefreshError,
                                coursewareLoading = courseware.loadingIds.any { it in selectedCourseIds },
                                coursewareRefreshError = courseware.refreshErrors
                                    .filterKeys { it in selectedCourseIds }
                                    .values
                                    .filter(String::isNotBlank)
                                    .joinToString("；"),
                                loggedIn = loggedIn,
                                coursewareCounts = courseware.coursewareCountsByCourse,
                                itemsForCourse = courseware::itemsForCourse,
                                prefetchRemaining = courseware.prefetchRemaining,
                                downloadUiState = courseware.downloadUiState,
                                downloadDirectoryPath = courseware.downloadDirectoryPath,
                                downloadDirectoryAbsolutePath = courseware.downloadDirectoryAbsolutePath,
                                onOpenDownloadDirectory = { openCoursewareDownloadDirectory() },
                                onRetryFailed = courseware::retryFailed,
                                onCheckLocalFiles = courseware::checkLocalFiles,
                                onCancelDownload = courseware::cancelDownloads,
                                missingLocalIds = courseware.missingLocalIds,
                            )
                            }
                            // 当前周次是「整页布局」（内容区 weight + 固定底部确定/取消），
                            // 放进外层共享 verticalScroll 后 weight 子项高度被算成 0，整页内容消失。
                            // 与课表页同理，移到滚动容器之外，自己吃满高度。
                            Page.CURRENT_WEEK -> ManualWeekPage(
                                termCode = schedule.termCode,
                                // 滚轮上限固定 20 周
                                totalWeeks = maxOf(
                                    schedule.cache.inferredCalendars[schedule.termCode]?.totalWeeks
                                        ?: xmuAcademicCalendarForTerm(schedule.termCode)?.totalWeeks ?: 19,
                                    20,
                                ),
                                autoWeek = xmuAcademicWeekFor(
                                    xmuAcademicCalendarForTerm(schedule.termCode),
                                    LocalDate.now(),
                                ).week,
                                currentManualWeek = settings.manualAcademicWeek(schedule.termCode),
                                onSet = { week -> applyManualWeek(week) },
                                // 返回按进入来源回（课表进→回课表，设置进→回设置），
                                // 不再固定回「策略」，否则从课表点进来的用户会被丢到设置页。
                                onBack = { onModuleEntered(backTarget) },
                            )
                            else -> key(page) {
                                // 每页独立滚动状态：共享同一 ScrollState 会让切页后
                                // 继承上一页的滚动位置（教程滚到底，首页/课表跟着从底部开始）。
                                // rememberSaveable(saver = ScrollState.Saver)：转屏后保留滚动位置。
                                val pageScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
                                // 首页滚动状态注册到外层 —— 底栏「首页」键当页再次点击
                                // 时由 onReselect 滚到底部；离开首页后引用失效也无碍（仅首页用）。
                                if (page == Page.HOME) {
                                    SideEffect { homeScrollState = pageScrollState }
                                }
                                if (page == Page.TUTORIAL) {
                                    SideEffect { tutorialScrollState = pageScrollState }
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(pageScrollState)
                                        // 滚动视口会裁掉最后一张卡底部溢出的阴影/圆角，
                                        // 统一补 12dp 底部留白，所有走这个容器的页面一起修好。
                                        .padding(bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 加密 prefs 的读取 + JSON 解析不能放在每次重组里
                                    // （toast / busy 一变整棵树重组，这里会白解密一遍）。
                                    // 只在切页、登录态变化或「记住密码」开关变化时重读。
                                    val savedAccountsForHome = remember(
                                        page,
                                        accountTransitionInProgress,
                                        settings.rememberAccounts,
                                    ) { settings.savedAccounts() }
                                    when (page) {
                                        Page.MORE -> MorePage(notificationSettings, downloadingCount, onSelected = onModuleEntered)
                                        Page.NOTIFICATION -> NotificationSettingsPreviewPage(
                                            settings,
                                            notificationSettings,
                                            themeMode,
                                            onThemeModeChanged = { mode ->
                                                setThemeMode(mode)
                                                settings.themeMode = mode
                                                // 组件颜色是写死在 RemoteViews 里的，改外观后必须主动重投一次，
                                                // 否则桌面要等下次同步才换肤。
                                                runCatching { redrawScheduleWidgetsForTheme(activity) }
                                                show("外观已更新")
                                            },
                                            // 隐私模式开关。读取即订阅，开关本身写 prefs 兜底重启恢复。
                                            privacyMode = PrivacyModeState.enabled,
                                            onPrivacyModeChanged = { enabled ->
                                                settings.privacyMode = enabled
                                                PrivacyModeState.set(enabled)
                                                show(if (enabled) "隐私模式已开启" else "隐私模式已关闭")
                                            },
                                            onTest = testNotifications,
                                            // 桌面小组件设置从「策略」整体搬入本页。
                                            // 总开关（widgetEnabled）已无实际作用，
                                            // 其回调与 UI 早已摘除，这里不再传 onWidgetToggle。
                                            onAddWidget = {
                                                if (knownPinBlockedOem(activity)) {
                                                    setWidgetGuideOpen(true)
                                                } else if (needsShortcutPermission(activity)) {
                                                    if (requestScheduleWidget(activity)) {
                                                        show("已请求添加桌面小卡片，请按系统提示确认…")
                                                        verifyPinAdded { setWidgetPermissionGuideOpen(true) }
                                                    } else {
                                                        setWidgetPermissionGuideOpen(true)
                                                    }
                                                } else if (requestScheduleWidget(activity)) {
                                                    show("已请求添加桌面小卡片，请按系统提示确认…")
                                                    verifyPinAdded { setWidgetGuideOpen(true) }
                                                } else {
                                                    setWidgetGuideOpen(true)
                                                }
                                            },
                                            onAddWeekWidget = {
                                                if (ScheduleWeekWidgetProvider.requestPin(activity)) {
                                                    show("已请求添加周课表小部件，请按系统提示确认…")
                                                } else {
                                                    show("请长按桌面 → 小部件 → 虾大助手Plus，手动添加周课表")
                                                }
                                            },
                                        ) {
                                            // 改为自动保存，不再弹「通知设置已保存」提示。
                                            onNotificationSettingsSaved(it)
                                        }
                                        Page.EVALUATION -> EvaluationHelperPage(show)
                                        Page.TUTORIAL -> TutorialPage(
                                            pageScrollState,
                                            { onModuleEntered(Page.fromId(it) ?: Page.HOME) },
                                            onOpenBackgroundSettings,
                                            requestNotificationPermissionIfNeeded,
                                            jumpState = tutorialJump,
                                        )
                                        Page.SETTINGS -> StrategyPreviewPage(
                                            settings,
                                            schedule.termCode,
                                            onSaved = {
                                                setRollcallSettings(it)
                                                show("设置已保存")
                                            },
                                            examReminder = examReminder,
                                            onExamReminderChanged = onExamReminderChanged,
                                            onOpenExamAlarmSettings = onOpenExamAlarmSettings,
                                            onOpenFullScreenSettings = onOpenFullScreenSettings,
                                            onOpenExamReminderTime = { onModuleEntered(Page.EXAM_REMINDER_TIME) },
                                            onOpenManualWeek = { onModuleEntered(Page.CURRENT_WEEK) },
                                            onOpenCourseCoordinates = { onModuleEntered(Page.COURSE_LOCATION) },
                                            onOpenAutoSignInStrategy = { onModuleEntered(Page.AUTO_ANSWER) },
                                        )
                                        // 原「设置」页两张自动签到卡片合并成的三级界面。
                                        Page.AUTO_ANSWER -> AutoSignInStrategyPage(
                                            settings = settings,
                                            current = rollcallSettings,
                                            onSaved = {
                                                setRollcallSettings(it)
                                                show("设置已保存")
                                            },
                                        )
                                        Page.EXAM_REMINDER_TIME -> ExamReminderTimePage(
                                            current = examReminder,
                                            onSave = onExamReminderChanged,
                                            onBack = { onModuleEntered(backTarget) },
                                        )
                                        // 当前周次已移到外层（不走共享滚动容器），此处不再处理。
                                        else -> HomePage(
                                            username = username,
                                            password = password,
                                            loggedIn = loggedIn,
                                            monitorRunning = monitorRunning,
                                            accountTransitionInProgress = accountTransitionInProgress,
                                            monitorTransitionInProgress = monitor.monitorTransitionInProgress,
                                            monitorStatus = monitorStatusText(monitorRunning, monitorConsecutiveFailures),
                                            monitorLastCheck = formatMonitorTime(monitorLastCheckMillis),
                                            monitorFailureCount = monitorConsecutiveFailures,
                                            monitorLastError = monitorLastError,
                                            rollcallSettings = rollcallSettings,
                                            onUsername = setUsername,
                                            onPassword = setPassword,
                                            onLogin = { homeActions.onLogin(username, password, accountTransitionInProgress, monitor.monitorTransitionId) },
                                            onLogout = { homeActions.onLogout(accountTransitionInProgress) },
                                            onStartMonitor = { startMonitor() },
                                            onStopMonitor = { homeActions.onStopMonitor(monitor.monitorTransitionInProgress, monitor.monitorTransitionId) },
                                            onAutoChanged = { enabled -> applyAutoAnswer(enabled) },
                                            onNavigate = { onModuleEntered(Page.fromId(it) ?: Page.HOME) },
                                            savedAccounts = savedAccountsForHome,
                                            rememberAccounts = settings.rememberAccounts,
                                            onRememberAccountsChanged = { enabled ->
                                                settings.rememberAccounts = enabled
                                                show(if (enabled) "已开启记住账号密码（仅本机）" else "已关闭并清除已存账号")
                                            },
                                            onSwitchAccount = { account, secret ->
                                                setUsername(account)
                                                setPassword(secret)
                                                homeActions.onLogin(account, secret, accountTransitionInProgress, monitor.monitorTransitionId)
                                            },
                                            currentRollcalls = rollcall.events,
                                            rollcallLoading = rollcall.loading,
                                            rollcallError = rollcall.refreshError,
                                            rollcallUpdatedAtMillis = rollcall.updatedAtMillis,
                                            // 首页两条失败提示接上真实动作 —— 签到重拉列表，
                                            // 监控重新起一次前台服务（等价「重新检测」）。
                                            onRetryRollcall = { rollcall.refresh() },
                                            onRetryMonitor = {
                                                if (!startMonitor(toastOnStarted = false)) {
                                                    show("监控没能在当前状态下重启，请先确认已登录")
                                                } else {
                                                    show("已重新启动监控检测")
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                // 底部提示已移到外层 Box 末尾的悬浮覆盖层，此处不再占位。
                if (!keyboardVisible) AppNavigationBar(
                    selected = page,
                    notificationSettings = notificationSettings,
                    downloadingCount = downloadingCount,
                    onSelected = onModuleEntered,
                    moreTapTarget = backTarget,
                    // 当页（一级页）再次点击底栏键 —— 首页滚到底、课表切视图、签到刷新。
                    // 二级页时 selected 不等于所点键，走正常导航，不触发这里的动作。
                    onReselect = { tapped ->
                        when (tapped) {
                            // 首页键不再「滚到底」，改为刷新各页面数据（课表/签到/考试/成绩/课件）。
                            Page.HOME -> onHomeRefresh()
                            Page.SCHEDULE -> scheduleViewToggle.intValue += 1
                            Page.ROLLCALL -> {
                                rollcall.refresh()
                                rollcall.refreshHistory()
                            }
                            // 只有底栏四个一级键会走到这里，「更多」键不触发重选动作。
                            else -> Unit
                        }
                    },
                )
            }

            // 教程页右侧两枚半透明悬浮圆圈 —— 上/下箭头跳相邻模块。
            // 放在外层 Box（不随内容滚动）；到第一个/最后一个模块时对应箭头变淡且点击无效。
            // UI 重做为「描边箭头图标 + 半透明圆形 + 细描边」，不再用文字箭头；
            // 位置从垂直居中改到靠下（底栏上沿之上），不遮正文；提示条出现时再上让一段。
            if (page == Page.TUTORIAL && !keyboardVisible) {
                val currentSection = tutorialJump.activeSection
                val jumpBottom by animateDpAsState(
                    targetValue = if (activeToast.isNotBlank()) 146.dp else 100.dp,
                    label = "tutorialJumpBottom",
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = jumpBottom),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(
                        R.drawable.ic_scroll_up to currentSection - 1,
                        R.drawable.ic_scroll_down to currentSection + 1,
                    ).forEach { (icon, target) ->
                        val enabled = target in 0..3
                        // 圆内那块不透明八边形是 3dp 投影从 0.88 半透明填充里透出来的。
                        // 改为不透明填充 + 细描边，彻底取消投影；禁用态用整块 alpha 表达，
                        // 视觉深浅与原来一致，但圆内不会再出现多边形阴影。
                        Surface(
                            onClick = {
                                if (!enabled) return@Surface
                                tutorialScrollState?.let { st ->
                                    val y = tutorialJump.sectionOffsets.getOrNull(target) ?: 0
                                    workScope.launch { st.animateScrollTo(y.coerceAtLeast(0)) }
                                }
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .alpha(if (enabled) 1f else 0.32f),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            ),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(icon),
                                    contentDescription = if (target < currentSection) "上一个模块" else "下一个模块",
                                    tint = themePrimary().copy(alpha = if (enabled) 1f else 0.35f),
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 提示条改为「贴底」——紧贴底部导航栏上沿（87dp = 1dp 分隔线 + 80dp 底栏 + 6dp 间隙）。
            // 悬浮控件（下载键、课表缩放条）统一上移到 FloatingControlBottomInset(146dp)，
            // 比提示条顶部再高 11dp，两者不再互相遮盖。
            if (activeToast.isNotBlank()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 13.dp, end = 13.dp, bottom = ToastBottomInset),
                ) {
                    ToastBar(activeToast, severity = activeSeverity)
                }
            }

            if (widgetGuideOpen) {
                // 一加/OPPO/realme/vivo（ColorOS 系）没有可直达「小部件」面板的公共 Intent，
                // Settings.ACTION_HOME_SETTINGS 在这些机型上只会打开「选择默认桌面应用」选择器，
                // 反而把用户带偏。因此 ColorOS 上不跳，只给清晰的手动步骤。
                val isColorOS = knownPinBlockedOem(activity)
                AlertDialog(
                    onDismissRequest = { setWidgetGuideOpen(false) },
                    confirmButton = {
                        if (isColorOS) {
                            // ColorOS 上只有一个按钮，避免出现两个「知道了」的重复按钮。
                            TextButton(onClick = { setWidgetGuideOpen(false) }) {
                                PrivacyText(stringResource(R.string.widget_guide_ok))
                            }
                        } else {
                            TextButton(onClick = {
                                setWidgetGuideOpen(false)
                                // 三星/Pixel/通用 AOSP 等：该 Intent 是真正的「主屏幕」设置入口。
                                runCatching {
                                    activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                                }
                            }) {
                                PrivacyText(stringResource(R.string.widget_guide_open_settings))
                            }
                        }
                    },
                    dismissButton = if (isColorOS) {
                        // 只保留一个「好的」按钮
                        null
                    } else {
                        {
                            TextButton(onClick = { setWidgetGuideOpen(false) }) {
                                PrivacyText(stringResource(R.string.widget_guide_got_it))
                            }
                        }
                    },
                    title = { PrivacyText(stringResource(R.string.widget_guide_title)) },
                    text = {
                        PrivacyText(
                            if (isColorOS) {
                                stringResource(R.string.widget_guide_coloros_body)
                            } else {
                                stringResource(R.string.widget_guide_generic_body)
                            },
                        )
                    },
                )
            }

            if (widgetPermissionGuideOpen) {
                AlertDialog(
                    onDismissRequest = { setWidgetPermissionGuideOpen(false) },
                    confirmButton = {
                        TextButton(onClick = {
                            setWidgetPermissionGuideOpen(false)
                            // 一键跳到本应用设置页，让用户手动开启「桌面快捷方式」权限。
                            runCatching {
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", activity.packageName, null),
                                    ),
                                )
                            }
                        }) {
                            PrivacyText(stringResource(R.string.widget_permission_guide_open))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { setWidgetPermissionGuideOpen(false) }) {
                            PrivacyText(stringResource(R.string.widget_permission_guide_got_it))
                        }
                    },
                    title = { PrivacyText(stringResource(R.string.widget_permission_guide_title)) },
                    text = {
                        PrivacyText(
                            stringResource(R.string.widget_permission_guide_body),
                        )
                    },
                )
            }
        }
    }
}
}
