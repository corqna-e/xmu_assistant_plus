package com.xmu.assistant

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收起态；步进 56dp → 46dp；再收到 44dp。
 *只压缩「课名下方到下一张卡顶端」的空白，文字位置始终不动。
 * 几何账：
 *12dp 上内边距 + 24dp 课名行 + 8dp 下方空白 = 44dp。
 * 上方仍是 12dp（与 平铺一致），下方 20dp → 10dp → 8dp，即下方卡片再上移 2dp。
 * 收起态不再画结束时间那一行（见 AgendaCourseCard 的 showDetails），否则会切出半截 09:40。
 */
private val AGENDA_STACK_STEP = 44.dp
/**沿用 平铺时「已上课程置灰」的 0.5 半透明度，但只作用在整摞合成后的一层上。 */
private const val AGENDA_STACK_ALPHA = 0.5f

/**
 *按用户给的效果图重做 —— 整条浅灰轨道 + 左侧主色已选段 + 大号白色圆形滑块（灰色描边），
 * 不再有刻度点。支持点击定位和水平拖动，全程无极（不吸附）。
 */
@Composable
private fun EdgeZoomSlider(
    percent: Float,
    onPercentChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    // 尺寸参数化 —— 缩放条整体缩小后，滑块/轨道跟着按比例缩小。
    thumbRadius: Dp = 10.dp,
    trackHeight: Dp = 6.dp,
    trackAreaHeight: Dp = 38.dp,
) {
    val minPercent = 100f / 3f
    // 上限由 200% 收到 150%。对应的真实缩放上限见 MAX_GRID_SCALE。
    val maxPercent = 150f
    val range = maxPercent - minPercent
    val primary = themePrimary()
    val inactive = Color(0xFFBFBFBF)
    BoxWithConstraints(modifier.height(trackAreaHeight)) {
        val trackWidth = (maxWidth - thumbRadius * 2f).coerceAtLeast(1.dp)
        val fraction = ((percent - minPercent) / range).coerceIn(0f, 1f)
        // 轨道底色（整条浅灰）
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbRadius)
                .width(trackWidth)
                .height(trackHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(inactive),
        )
        // 已选部分（主色，从起点到滑块）
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbRadius)
                .width(trackWidth * fraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(primary),
        )
        // 滑块：白色圆面 + 灰色描边
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbRadius + trackWidth * fraction - thumbRadius)
                .size(thumbRadius * 2f)
                // 与外层一致，不带阴影，只剩白面 + 灰色描边。
                .background(Color.White, CircleShape)
                .border(1.5.dp, Color(0xFF8C8C8C), CircleShape),
        )
        // 透明手势层：点击定位 + 水平拖动，坐标按 thumb 半径内缩，保证两端可取到极值。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(minPercent, maxPercent) {
                    fun apply(xPx: Float) {
                        val inset = thumbRadius.toPx()
                        val usable = (size.width - inset * 2).coerceAtLeast(1f)
                        val ratio = ((xPx - inset) / usable).coerceIn(0f, 1f)
                        onPercentChange(minPercent + ratio * range)
                    }
                    detectTapGestures { offset -> apply(offset.x) }
                }
                .pointerInput(minPercent, maxPercent) {
                    detectHorizontalDragGestures { change, _ ->
                        val inset = thumbRadius.toPx()
                        val usable = (size.width - inset * 2).coerceAtLeast(1f)
                        val ratio = ((change.position.x - inset) / usable).coerceIn(0f, 1f)
                        onPercentChange(minPercent + ratio * range)
                        change.consume()
                    }
                },
        )
    }
}

/**
 *按需求再收一版 —— 整体缩到原来的一半左右、改成右下角的小卡片；
 * 空闲时半透明（0.55），一碰立刻全不透明，停手 2 秒淡回半透明：
 * 既不会常年压着课表，也不至于要用时找不着。
 * 缩放值由 AssistantSettings 跨重启记住（本组件只负责展示与回调）。
 */
@Composable
private fun FloatingZoomControl(
    gridScale: Float,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayPercent = gridScale / GRID_SCALE_BASE * 100f
    // 每次操作 +1 → 重新计时；2 秒内没再操作就淡回半透明。
    var touchSeq by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(touchSeq) {
        if (touchSeq > 0) {
            focused = true
            delay(2000)
            focused = false
        }
    }
    val controlAlpha by animateFloatAsState(if (focused) 1f else 0.55f, label = "zoomControlAlpha")
    Surface(
        modifier = modifier.alpha(controlAlpha),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // 去掉外周阴影，靠圆角与透明度区分层次即可。
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            // 高度 24→28dp（+15%），内容靠 CenterVertically 居中；
            // 左右留白统一 7dp —— 之前右侧只留 3dp，百分比数字贴着边，看起来不对称。
            Modifier.width(140.dp).height(28.dp).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrivacyText(
                "重置",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = themePrimary(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clickable {
                        touchSeq += 1
                        onScaleChange(GRID_SCALE_BASE)
                    }
                    // 去掉水平 1dp 内边距，让「重置」与右侧百分比到卡片边缘的留白都是 7dp。
                    .padding(vertical = 6.dp),
            )
            EdgeZoomSlider(
                percent = displayPercent,
                onPercentChange = {
                    touchSeq += 1
                    onScaleChange((it / 100f * GRID_SCALE_BASE).coerceIn(MIN_GRID_SCALE, MAX_GRID_SCALE))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 5.dp, end = 3.dp),
                thumbRadius = 5.dp,
                trackHeight = 3.dp,
                trackAreaHeight = 20.dp,
            )
            // 关键：百分比不能写死宽度 —— 四位数会超出卡片，必须按内容自适应。
            PrivacyText(
                "${displayPercent.toInt()}%",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
    }
}

/** 课表页选中课程详情（XmuScheduleGroup?）的 rememberSaveable Saver：
 * 转屏恢复用，字段扁平化为可保存列表（含 variants 平行教学班，避免恢复后详情回退）。 */
private val scheduleGroupListSaver = listSaver<XmuScheduleGroup?, Any>(
    save = { group ->
        if (group == null) {
            listOf(false)
        } else {
            listOf(
                true,
                group.weekday,
                group.startSection,
                group.endSection,
                group.startTime,
                group.endTime,
                normalizeScheduleBrackets(group.courseName),
                group.rooms,
                group.teachers,
                group.weeks,
                group.variants.map { variant ->
                    listOf(variant.room, variant.teacher, variant.weeks)
                },
                group.isExam,
            )
        }
    },
    restore = { saved ->
        if (saved.firstOrNull() != true) {
            null
        } else {
            val rawVariants: List<*> = (saved.getOrNull(10) as? List<*>) ?: emptyList<Any>()
            XmuScheduleGroup(
                weekday = saved[1] as Int,
                startSection = saved[2] as Int,
                endSection = saved[3] as Int,
                startTime = saved[4] as Int,
                endTime = saved[5] as Int,
                courseName = saved[6] as String,
                rooms = saved[7] as List<String>,
                teachers = saved[8] as List<String>,
                weeks = saved[9] as String,
                variants = rawVariants.mapNotNull { raw ->
                    val triple = raw as? List<*> ?: return@mapNotNull null
                    XmuScheduleVariant(
                        room = triple.getOrNull(0) as? String ?: "",
                        teacher = triple.getOrNull(1) as? String ?: "",
                        weeks = triple.getOrNull(2) as? String ?: "",
                    )
                },
                isExam = saved.getOrNull(11) as? Boolean ?: false,
            )
        }
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchedulePage(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    updatedAtMillis: Long,
    loading: Boolean,
    refreshError: String,
    loggedIn: Boolean = false,
    onRefresh: () -> Unit,
    inferredCalendar: XmuAcademicCalendar? = null,
    manualWeek: Int = 0,
    /**提示条出现时缩放条要整体上让，避免被盖住。 */
    toastVisible: Boolean = false,
    /**底栏「课表」键当页再次点击的切换请求序号（MainScreen 自增）；0 = 无请求。 */
    viewToggleSeq: Int = 0,
    /**教务抓取的考试（按日期并入当前周的课表）。 */
    examEntries: List<XmuExam> = emptyList(),
    /**用户自定义的考试安排（同样并入当前周的课表）。 */
    customExamEntries: List<CustomExam> = emptyList(),
    /**桌面小组件指定的视图（WEEK / AGENDA），空串表示不干预。 */
    requestedView: String = "",
    /**请求序号 —— 只有序号变化才应用一次，避免普通重组反复覆盖用户手动切换的视图。 */
    requestedViewSeq: Int = 0,
    /**顶部「第N周 · 本周」可点击，跳到「设置 - 当前周次」内页。 */
    onOpenManualWeek: () -> Unit = {},
    /**
     * 缩放设置的存取器，默认现场构造加密设置。
     * 测试环境（Robolectric 无 AndroidKeyStore）注入 AssistantSettings.forTest 的明文实例，
     * 否则组合期即抛 KeyStoreException。
     */
    settingsOverride: AssistantSettings? = null,
) {
    val today = LocalDate.now()
    val context = LocalContext.current
    // 每分钟刷新当前时刻：跨过下课时刻后「正在上课」高亮/下一节课判断/置灰
    // 才能随时间自动更新（此前只在重组时取一次，页面停留期间状态陈旧）。
    var nowValue by remember {
        mutableIntStateOf(LocalTime.now().let { it.hour * 100 + it.minute })
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            nowValue = LocalTime.now().let { it.hour * 100 + it.minute }
        }
    }
    // 反推/手动校准结果优先（校准过即权威），官方校历表兜底：
    // 校准写入缓存后必须优先采用，否则页面仍显示旧官方表，校准失效。
    val calendar = remember(termCode, inferredCalendar) {
        inferredCalendar ?: xmuAcademicCalendarForTerm(termCode)
    }
    // 总周数兜底 19：与反推默认一致，避免 19 周长学期第 19 周课程被钳制丢失。
    val totalWeeks = calendar?.totalWeeks ?: 19
    // 手动周次只是「校准基准」：手动指定后系统已反推开学日（inferredCalendar 非空），
    // 此后按日期自动推进（明天同周、下周一自动 +1），无需每次手动更新；
    // 仅当手动校准反推失败（无校准日历）时才以手动周次兜底显示。
    val academicWeek = remember(calendar, today, manualWeek, totalWeeks) {
        if (manualWeek in 1..totalWeeks && inferredCalendar == null) {
            XmuAcademicWeek(
                phase = XmuTermPhase.DURING,
                week = manualWeek,
                totalWeeks = totalWeeks,
                date = today,
            )
        } else {
            xmuAcademicWeekFor(calendar, today)
        }
    }
    val defaultWeek = academicWeek.week ?: 1
    // rememberSaveable：转屏（Activity 重建）后保留用户选择的周/视图/日/课程详情，
    // 而不是重置回默认值。viewMode 枚举与 selectedCourse 用 name/自定义 Saver 保存。
    // 注意 key 只含 termCode：若把 defaultWeek 放进 key，跨午夜/磁盘缓存异步加载导致
    // defaultWeek 变化时，用户正浏览的周/日会被静默重置回默认周。
    var selectedWeek by rememberSaveable(termCode) { mutableIntStateOf(defaultWeek) }
    var viewModeName by rememberSaveable { mutableStateOf(ScheduleViewMode.AGENDA.name) }
    // 周课表缩放：内部仍是真实缩放值，默认 0.75；
    // UI 把这个默认点重标为「100%」（显示值 = gridScale / 0.75 * 100），
    // 滑条范围 33%~150%（上限由 200% 收到 150%），用户看到的 100% 就是最合适的标准大小。
    // 缩放值存入加密设置，跨重启自动记住（rememberSaveable 只负责转屏恢复）。
    val appSettings = settingsOverride ?: remember(context) { AssistantSettings(context) }
    var gridScale by rememberSaveable { mutableStateOf(appSettings.scheduleGridScale) }
    fun updateGridScale(value: Float) {
        val clamped = value.coerceIn(MIN_GRID_SCALE, MAX_GRID_SCALE)
        gridScale = clamped
        appSettings.scheduleGridScale = clamped
    }
    // 从 viewModeName 派生：切换视图必须写 viewModeName（写入 State 触发重组），
    // 直接写 viewMode 只改局部变量，UI 不会更新（日程点不开的根因）。
    // valueOf 对非法持久化值（旧版本枚举/状态损坏）会抛异常，安全回退默认日程。
    val viewMode: ScheduleViewMode = ScheduleViewMode.entries.firstOrNull { it.name == viewModeName }
        ?: ScheduleViewMode.AGENDA
    // 底栏「课表」键当页再次点击 → 周课表/日程互换。
    // 视图状态仍留在本页（rememberSaveable），MainScreen 只传自增序号，避免整棵状态上提。
    LaunchedEffect(requestedViewSeq) {
        if (requestedViewSeq > 0) {
            // 桌面小组件点开时强制落到指定视图（今日课程卡 → 日程，周课表卡 → 周课表）。
            ScheduleViewMode.entries.firstOrNull { it.name == requestedView }?.let {
                viewModeName = it.name
            }
        }
    }
    LaunchedEffect(viewToggleSeq) {
        if (viewToggleSeq > 0) {
            viewModeName = if (viewMode == ScheduleViewMode.WEEK) {
                ScheduleViewMode.AGENDA.name
            } else {
                ScheduleViewMode.WEEK.name
            }
        }
    }
    var selectedDay by rememberSaveable(termCode) {
        mutableIntStateOf(if (academicWeek.week != null) xmuWeekdayFrom(today) else 1)
    }
    // defaultWeek 变化时的跟随策略：仅当用户仍停留在「旧的默认周」（未手动切周）才跟随
    // 新默认周（含启动时缓存异步就位 1→真实周次 的校正）；手动切到第 N 周则不抢。
    var lastDefaultWeek by remember(termCode) { mutableIntStateOf(defaultWeek) }
    LaunchedEffect(defaultWeek) {
        if (selectedWeek == lastDefaultWeek && selectedWeek != defaultWeek) {
            selectedWeek = defaultWeek
            selectedDay = if (academicWeek.week != null) xmuWeekdayFrom(today) else 1
        }
        lastDefaultWeek = defaultWeek
    }
    val overrideStore = remember(context) { ScheduleOverrideStore(context) }
    // 跨周搬课单独一份存储（覆盖改时段、这份改「在哪一周」）。
    val moveStore = remember(context) { ScheduleWeekMoveStore(context) }
    var overrideRevision by rememberSaveable(termCode) { mutableIntStateOf(0) }
    // 绑定 termCode：切换学期时清掉上一学期点开的课程详情，避免残留旧数据
    var selectedCourse by rememberSaveable(termCode, stateSaver = scheduleGroupListSaver) {
        mutableStateOf<XmuScheduleGroup?>(null)
    }
    var selectedBaseCourse by rememberSaveable("base-$termCode", stateSaver = scheduleGroupListSaver) {
        mutableStateOf<XmuScheduleGroup?>(null)
    }
    // 「修改安排」下拉已移除，改为顶部「调课」按键 + 弹窗。
    var swapDialogOpen by rememberSaveable { mutableStateOf(false) }

    val clampedWeek = selectedWeek.coerceIn(1, totalWeeks)
    val weekIndex = remember(entries) { indexXmuScheduleByWeek(entries) }
    // 左侧节次列的作息时间：厦大官方表打底，本学期单节课程校正（与「选择节次」面板同源）。
    val sectionWindows = remember(entries) { resolveSectionWindows(entries) }
    val rawWeekGroups = remember(entries, weekIndex, clampedWeek) {
        (weekIndex[clampedWeek] ?: emptyList()).groupForDisplay()
    }
    val weekGroupPairs = remember(rawWeekGroups, termCode, overrideRevision, clampedWeek) {
        // 按「周」解析调整 —— 单周调课只影响这一周，其余周次保持教务安排。
        // 先剔除「本周已被删除」的课（调课弹窗里的「删除该周次课」），再套用改期。
        // 再剔除搬去别周的课，并把别周搬来的课补进来（跨周搬课）。
        resolveWeekPairs(rawWeekGroups, termCode, clampedWeek, overrideStore, moveStore)
    }
    val weekGroups = remember(weekGroupPairs) {
        weekGroupPairs.map { it.second }.sortedWith(compareBy<XmuScheduleGroup> { it.weekday }.thenBy { it.startSection }.thenBy { it.courseName })
    }
    fun selectCourseAndReveal(group: XmuScheduleGroup) {
        selectedBaseCourse = weekGroupPairs.firstOrNull { it.second == group }?.first ?: group
        selectedCourse = group
    }
    val selectedWeekStart = remember(calendar, clampedWeek) { xmuWeekStart(calendar, clampedWeek) }
    // 考试并入课表 —— 落在当前周内的考试（抓取 + 自定义）换算成课表条目，
    // 与课程一起显示在周课表与日程视图里；考试块用独立配色与「考试」标记区分。
    val examGroups = remember(entries, examEntries, customExamEntries, selectedWeekStart) {
        mergeExamsIntoWeek(selectedWeekStart, entries, examEntries, customExamEntries)
    }
    val displayGroups = remember(weekGroups, examGroups) {
        (weekGroups + examGroups).sortedWith(
            compareBy<XmuScheduleGroup> { it.weekday }
                .thenBy { it.startSection }
                .thenBy { it.courseName },
        )
    }
    val selectedIsCurrent = academicWeek.week == clampedWeek
    val todayWeekday = xmuWeekdayFrom(today)
    // 明日课程：按系统日期推算，今天是周日时明天落到下一周，取那一周的课表数据。
    val tomorrow = today.plusDays(1)
    val tomorrowWeekday = xmuWeekdayFrom(tomorrow)
    val tomorrowWeek = academicWeek.week?.let { week -> if (todayWeekday == 7) week + 1 else week }
    val tomorrowGroups = remember(entries, weekIndex, tomorrowWeek, tomorrowWeekday, termCode, overrideRevision) {
        if (tomorrowWeek == null || tomorrowWeek > totalWeeks) {
            emptyList()
        } else {
            // 明日课程同样要认跨周搬课 —— 搬走的不显示，搬来的要显示。
            resolveWeekPairs(
                (weekIndex[tomorrowWeek] ?: emptyList()).groupForDisplay(),
                termCode,
                tomorrowWeek,
                overrideStore,
                moveStore,
            ).map { it.second }
                .filter { it.weekday == tomorrowWeekday }
                .sortedBy { it.startTime }
        }
    }

    // ICS 导出走 cacheDir → FileProvider → 系统分享（日历/邮件应用均可导入）；
    // 文件很小（<200KB），生成与落盘放 IO 协程只为规避主线程磁盘 StrictMode 告警。
    val scope = rememberCoroutineScope()

    /**
     * 本地课表改动（调课 / 修改安排 / 还原 / 单周停课）后重投桌面组件。
     *
     * 这些改动只落在本机偏好里，不触发教务刷新，因此组件上要等下一次联网同步才会变 ——
     * 调一次课桌面等到第二天早上 7:00 也不一定更新。这里只读缓存快照重算一遍，
     * 放 IO 线程是因为整学期投影要读两次覆盖存储。
     */
    fun refreshWidgetsAfterEdit() {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) { runCatching { refreshScheduleWidgetsFromCache(appContext) } }
    }

    var exporting by remember { mutableStateOf(false) }
    fun exportScheduleIcs() {
        val activeCalendar = calendar ?: run {
            Toast.makeText(context, "开学日尚未校准，请先在课表页校准周次，再导出。", Toast.LENGTH_LONG).show()
            return
        }
        exporting = true
        scope.launch(Dispatchers.IO) {
            try {
                val text = buildScheduleIcs(entries, activeCalendar)
                    ?: error("开学日待校准，暂无法换算上课日期")
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "xmu课表_${activeCalendar.termCode}.ics")
                file.writeText(text)
                withContext(Dispatchers.Main) {
                    exporting = false
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/calendar"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "分享课表日历"))
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    exporting = false
                    Toast.makeText(context, "导出失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    selectedCourse?.let { group ->
        val base = selectedBaseCourse ?: group
        fun closeDetails() {
            selectedCourse = null
            selectedBaseCourse = null
        }
        ModalBottomSheet(onDismissRequest = ::closeDetails, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                ScheduleCourseDetails(
                    group = group,
                    hasLocalOverride = overrideStore.has(termCode, base) || moveStore.has(termCode, base),
                    onClose = ::closeDetails,
                    onSave = { value ->
                        overrideStore.save(termCode, base, value)
                        overrideRevision += 1
                        refreshWidgetsAfterEdit()
                        selectedCourse = applyScheduleOverride(base, value)
                    },
                    onRestoreOfficial = {
                        overrideStore.remove(termCode, base)
                        // 跨周搬课也一并还原，否则课会留在搬过去的那一周。
                        moveStore.remove(termCode, base)
                        overrideRevision += 1
                        refreshWidgetsAfterEdit()
                        selectedCourse = base
                    },
                )
            }
        }
    }
    if (swapDialogOpen && weekGroups.isNotEmpty()) {
        SwapCourseDialog(
            groups = weekGroups,
            currentWeek = clampedWeek,
            totalWeeks = totalWeeks,
            onDismiss = { swapDialogOpen = false },
            onConfirm = { course, targetWeekday, targetStart, allWeeks, assignments ->
                val baseA = weekGroupPairs.firstOrNull { it.second == course }?.first ?: course
                val lengthA = course.endSection - course.startSection + 1
                val endA = (targetStart + lengthA - 1).coerceAtMost(11)

                fun pairsOfWeek(week: Int): List<Pair<XmuScheduleGroup, XmuScheduleGroup>> =
                    resolveWeekPairs(
                        (weekIndex[week] ?: emptyList()).groupForDisplay(),
                        termCode,
                        week,
                        overrideStore,
                        moveStore,
                    )

                // 目标周目标时段的占位课（排除这门课自己那一条）。
                fun occupantPairAt(week: Int): Pair<XmuScheduleGroup, XmuScheduleGroup>? =
                    pairsOfWeek(week).firstOrNull { (base, applied) ->
                        val self = normalizeCourseTitle(base.courseName) == normalizeCourseTitle(baseA.courseName) &&
                            base.weekday == baseA.weekday &&
                            base.startSection == baseA.startSection &&
                            base.endSection == baseA.endSection
                        !self &&
                            applied.weekday == targetWeekday &&
                            applied.startSection <= endA &&
                            applied.endSection >= targetStart
                    }

                fun saveInPlace(scopeWeek: Int?) {
                    overrideStore.save(
                        termCode,
                        baseA,
                        ScheduleCourseOverride(
                            weekday = targetWeekday,
                            startSection = targetStart,
                            endSection = endA,
                            room = course.rooms.joinToString("/"),
                            teacher = course.teachers.joinToString("/"),
                            weeks = course.weeks,
                            scopeWeek = scopeWeek,
                            // 调课（含对调）搬了时段 → 课名后加「[调]」。
                            moved = true,
                        ),
                    )
                }

                // 目标位置的占位课回到本课原来的时段 —— 同周写覆盖，跨周写反向搬课。
                fun swapOccupant(pair: Pair<XmuScheduleGroup, XmuScheduleGroup>, sourceWeek: Int?, targetWeek: Int?) {
                    val (baseB, other) = pair
                    val lengthB = other.endSection - other.startSection + 1
                    val endB = (course.startSection + lengthB - 1).coerceAtMost(11)
                    if (targetWeek == null) {
                        overrideStore.save(
                            termCode,
                            baseB,
                            ScheduleCourseOverride(
                                weekday = course.weekday,
                                startSection = course.startSection,
                                endSection = endB,
                                room = other.rooms.joinToString("/"),
                                teacher = other.teachers.joinToString("/"),
                                weeks = other.weeks,
                                scopeWeek = sourceWeek,
                                // 对调里被换走的那门课同样算「调课」。
                                moved = true,
                            ),
                        )
                    } else {
                        moveStore.save(
                            termCode,
                            baseB,
                            ScheduleWeekMove(
                                fromWeek = targetWeek,
                                toWeek = sourceWeek ?: targetWeek,
                                weekday = course.weekday,
                                startSection = course.startSection,
                                endSection = endB,
                                room = other.rooms.joinToString("/"),
                                teacher = other.teachers.joinToString("/"),
                                weeks = other.weeks,
                            ),
                        )
                    }
                }

                var swappedName: String? = null
                if (allWeeks) {
                    // 整学期：所有周次原地改到目标星期与节次，当前周的占位课同时换回原时段。
                    val occupantPair = weekGroupPairs.firstOrNull { (_, applied) ->
                        applied != course &&
                            applied.weekday == targetWeekday &&
                            applied.startSection <= endA &&
                            applied.endSection >= targetStart
                    }
                    saveInPlace(null)
                    if (occupantPair != null) {
                        swappedName = occupantPair.second.courseName
                        swapOccupant(occupantPair, sourceWeek = null, targetWeek = null)
                    }
                } else {
                    assignments.forEach { item ->
                        when (item) {
                            is SwapAssignment.Delete -> overrideStore.cancelWeek(termCode, baseA, item.sourceWeek)
                            is SwapAssignment.Move -> {
                                val occupantPair = occupantPairAt(item.targetWeek)
                                if (item.targetWeek == item.sourceWeek) {
                                    // 同一周内挪时段：仍走单周覆盖（含同周对调）。
                                    saveInPlace(item.sourceWeek)
                                    occupantPair?.let { swappedName = it.second.courseName; swapOccupant(it, item.sourceWeek, null) }
                                } else {
                                    // 跨周搬课 —— 源周那一次课消失，目标周补一条「[调]」课。
                                    moveStore.save(
                                        termCode,
                                        baseA,
                                        ScheduleWeekMove(
                                            fromWeek = item.sourceWeek,
                                            toWeek = item.targetWeek,
                                            weekday = targetWeekday,
                                            startSection = targetStart,
                                            endSection = endA,
                                            room = course.rooms.joinToString("/"),
                                            teacher = course.teachers.joinToString("/"),
                                            weeks = course.weeks,
                                        ),
                                    )
                                    occupantPair?.let { swappedName = it.second.courseName; swapOccupant(it, item.sourceWeek, item.targetWeek) }
                                }
                            }
                        }
                    }
                }
                overrideRevision += 1
                refreshWidgetsAfterEdit()
                selectedCourse = null
                selectedBaseCourse = null
                swapDialogOpen = false
                val moveCount = assignments.count { it is SwapAssignment.Move }
                val deleteCount = assignments.count { it is SwapAssignment.Delete }
                val title = normalizeScheduleBrackets(course.courseName)
                val message = when {
                    allWeeks && swappedName != null ->
                        "已在整学期对调：$title ⇄ ${normalizeScheduleBrackets(swappedName.orEmpty())}"
                    allWeeks ->
                        "已把《$title》整学期调到${weekdayName(targetWeekday)}第 $targetStart—$endA 节"
                    moveCount > 0 && deleteCount > 0 -> "已按配对调整 $moveCount 周、删除 $deleteCount 周"
                    moveCount > 0 && swappedName != null ->
                        "已按配对搬课 $moveCount 周，并对调《${normalizeScheduleBrackets(swappedName.orEmpty())}》"
                    moveCount > 0 -> "已按配对搬课 $moveCount 周"
                    deleteCount > 0 -> "已删除 $deleteCount 周的《$title》"
                    else -> "目标周次未选，未做调整"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
        )
    }

    // 课表页自己接管滚动。外层共享的 verticalScroll 会把任何子视图一起滚走，
    // 悬浮缩放面板必须放在滚动容器之外，才能真正「锁定」在页面右下角。
    val pageScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScrollState)
                // 给右下角悬浮面板留高度：滚到底时最后一行内容仍完整可见，不被压住。
                .padding(bottom = 96.dp),
        ) {
            SectionCard("课表", showTitle = false) {
        ScheduleAcademicHeader(
            calendar = calendar,
            academicWeek = academicWeek,
            selectedWeek = selectedWeek,
            today = today,
            loading = loading,
            onRefresh = onRefresh,
            onExport = ::exportScheduleIcs,
            canExport = !exporting && entries.isNotEmpty(),
            // 仅「本周」可点，跳「设置 - 当前周次」内页校准。
            selectedIsCurrent = academicWeek.phase == XmuTermPhase.DURING &&
                academicWeek.week == selectedWeek,
            onWeekLabelClick = onOpenManualWeek,
        )

        ScheduleWeekNavigator(
            selectedWeek = selectedWeek,
            totalWeeks = totalWeeks,
            currentWeek = academicWeek.week,
            onWeekSelected = {
                selectedWeek = it
                selectedCourse = null
                selectedBaseCourse = null
                if (academicWeek.week == it) selectedDay = todayWeekday
            },
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                ScheduleModeToggle(viewMode = viewMode, onModeChanged = { viewModeName = it.name })
            }
            TextButton(
                onClick = { swapDialogOpen = true },
                enabled = weekGroups.isNotEmpty(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                PrivacyText("调课", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = {
                selectedWeek = defaultWeek
                selectedDay = todayWeekday
                viewModeName = ScheduleViewMode.AGENDA.name
                selectedCourse = null
                selectedBaseCourse = null
            }, enabled = academicWeek.week != null, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                PrivacyText("看今天", style = MaterialTheme.typography.labelLarge)
            }
        }

        RefreshStateBanner(
            loading = loading,
            errorMessage = refreshError,
            hasData = entries.isNotEmpty(),
            // 失败提示右侧给「重试」，直接复用本页刷新动作。
            onRetry = onRefresh,
        )

        if (entries.isEmpty()) {
            // 空态三档之「未登录」：登出后清空缓存时给出登录引导而非"暂无数据"
            if (!loggedIn) {
                EmptyState("请先登录", "登录后点击刷新即可获取课表。")
            } else {
                EmptyState("暂无课表", "点击右上角刷新，从教务系统读取原始排课数据。")
            }
            return@SectionCard
        }

        if (displayGroups.isEmpty()) {
            EmptyState(
                "第${clampedWeek}周没有课程",
                if (selectedIsCurrent) "本周暂无课程，可安排其他事项。" else "切换其他周查看课程安排。",
            )
            return@SectionCard
        }

        when (viewMode) {
            ScheduleViewMode.WEEK -> {
                ScheduleWeekGrid(
                    groups = displayGroups,
                    weekStart = selectedWeekStart,
                    selectedIsCurrent = selectedIsCurrent,
                    todayWeekday = todayWeekday,
                    nowValue = nowValue,
                    onCourseSelected = ::selectCourseAndReveal,
                    scale = gridScale,
                    sectionWindows = sectionWindows,
                )
                // 缩放面板已移出卡片，固定在页面右下角悬浮（见本页末尾 Box）。
            }
            ScheduleViewMode.AGENDA -> {
                ScheduleAgendaView(
                    groups = displayGroups,
                    selectedWeek = selectedWeek,
                    selectedDay = selectedDay,
                    onDaySelected = { selectedDay = it },
                    selectedIsCurrent = selectedIsCurrent,
                    todayWeekday = todayWeekday,
                    nowValue = nowValue,
                    onCourseSelected = ::selectCourseAndReveal,
                )
                // 明日课程只在「本周且选中今天」时出现；切到别的日子/周次不再预告明天。
                if (selectedIsCurrent && selectedDay == todayWeekday) {
                    ScheduleTomorrowCard(
                        tomorrow = tomorrow,
                        groups = tomorrowGroups,
                        onCourseSelected = { group ->
                            selectedBaseCourse = group
                            selectedCourse = group
                        },
                    )
                }
            }
        }
        if (viewMode == ScheduleViewMode.WEEK) ScheduleNextCourseCard(
            groups = displayGroups,
            selectedIsCurrent = selectedIsCurrent,
            todayWeekday = todayWeekday,
            nowValue = nowValue,
            phase = academicWeek.phase,
            onCourseSelected = ::selectCourseAndReveal,
        )
        // 底部刷新时间细化为「上次刷新 X月X日X时X分X秒」，靠右对齐；
        // 校历标签仍占左侧，两者不再用「 · 」拼成一串。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            calendar?.displayLabel?.let { label ->
                PrivacyText(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            PrivacyText(
                if (updatedAtMillis > 0L) "上次刷新 ${formatRefreshTimestamp(updatedAtMillis)}" else "尚未刷新",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = if (calendar?.displayLabel == null) Modifier.fillMaxWidth() else Modifier,
            )
        }
    }
        }
        // 只在周课表且本周有课时出现：日程视图没有缩放，空态也不需要滑条。
        if (viewMode == ScheduleViewMode.WEEK && displayGroups.isNotEmpty()) {
            // 内容区底边就是底栏上沿，所以右下角只需让 10dp 就真的贴住右下角了；
            // 那个 146dp 是给「整屏悬浮层」算的，用在内容区里等于把卡片抬到半空。
            // 提示条是整屏覆盖层且通栏，出现时才临时上让（条高约 44dp + 8dp 间距）。
            val zoomBottom by animateDpAsState(
                targetValue = if (toastVisible) 56.dp else 10.dp,
                label = "zoomBarBottom",
            )
            FloatingZoomControl(
                gridScale = gridScale,
                onScaleChange = { value -> updateGridScale(value) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = zoomBottom),
            )
        }
    }
}

private enum class ScheduleViewMode { WEEK, AGENDA }

/**
 * 开学前（BEFORE）阶段的标题文案：秋季学期（...1）前是暑假，
 * 春季学期（...2）前是寒假；夏季短学期（...3）开学前是学期间隙（晚春/初夏），
 * 不能标"寒假"；未知学期显示通用「假期中」。
 */
private fun beforeTermLabel(calendar: XmuAcademicCalendar?): String = when (calendar?.termCode?.lastOrNull()) {
    '1' -> "暑假中"
    '2' -> "寒假中"
    '3' -> "短学期尚未开始"
    else -> "假期中"
}

@Composable
private fun ScheduleAcademicHeader(
    calendar: XmuAcademicCalendar?,
    academicWeek: XmuAcademicWeek,
    selectedWeek: Int,
    today: LocalDate,
    loading: Boolean,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    canExport: Boolean,
    selectedIsCurrent: Boolean = false,
    onWeekLabelClick: () -> Unit = {},
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val headline = when (academicWeek.phase) {
        XmuTermPhase.BEFORE -> beforeTermLabel(calendar)
        XmuTermPhase.DURING -> if (academicWeek.week == selectedWeek) "第${selectedWeek}周 · 本周" else "第${selectedWeek}周 · 非本周"
        XmuTermPhase.AFTER -> "学期已结束"
        XmuTermPhase.UNKNOWN -> "第${selectedWeek}周"
    }
    val supporting = when (academicWeek.phase) {
        XmuTermPhase.BEFORE -> {
            val days = calendar?.let { ChronoUnit.DAYS.between(today, it.startDate) }
            if (days != null && days >= 0) "距离开学${days}天 · 正在预览第${selectedWeek}周" else "正在预览第${selectedWeek}周"
        }
        XmuTermPhase.AFTER -> "可切换周次回顾本学期课程"
        else -> xmuWeekDateRange(calendar, selectedWeek)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 「第N周 · 本周」用与本日高亮一致的绿色，并可点击跳「设置 - 当前周次」；
            // 非本周仍是主色、不可点（此时文字是「第N周 · 非本周」，点进去没有校准意义）。
            PrivacyText(
                headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selectedIsCurrent) AppSuccess else themePrimary(),
                modifier = if (selectedIsCurrent) {
                    Modifier.clickable(onClick = onWeekLabelClick)
                } else {
                    Modifier
                },
            )
            PrivacyText(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 刷新键由「框 + 文字」改为纯图标，减少窄屏占位。
        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(36.dp)) {
            Icon(
                painterResource(R.drawable.ic_refresh),
                contentDescription = if (loading) "正在刷新" else "刷新课表",
                tint = themePrimary(),
                modifier = Modifier.size(21.dp),
            )
        }
        Box {
            TextButton(onClick = { actionsExpanded = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(40.dp)) { PrivacyText("更多", style = MaterialTheme.typography.labelMedium) }
            DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                DropdownMenuItem(text = { PrivacyText("导出日历") }, enabled = canExport, onClick = { actionsExpanded = false; onExport() })
            }
        }
    }
}

@Composable
private fun ScheduleWeekNavigator(
    selectedWeek: Int,
    totalWeeks: Int,
    currentWeek: Int?,
    onWeekSelected: (Int) -> Unit,
) {
    var choosingWeek by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { onWeekSelected(selectedWeek - 1) }, enabled = selectedWeek > 1,
            modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { PrivacyText("‹", style = MaterialTheme.typography.titleLarge) }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { choosingWeek = true }, modifier = Modifier.fillMaxWidth()) {
                PrivacyText("第${selectedWeek}周 ▾", fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = choosingWeek, onDismissRequest = { choosingWeek = false }) {
                (1..totalWeeks).forEach { week ->
                    DropdownMenuItem(
                        text = { PrivacyText(if (week == currentWeek) "第${week}周 · 本周" else "第${week}周") },
                        onClick = { choosingWeek = false; onWeekSelected(week) },
                    )
                }
            }
        }
        if (currentWeek != null && selectedWeek != currentWeek) {
            TextButton(onClick = { onWeekSelected(currentWeek) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { PrivacyText("看本周", style = MaterialTheme.typography.labelLarge) }
        }
        OutlinedButton(
            onClick = { onWeekSelected(selectedWeek + 1) }, enabled = selectedWeek < totalWeeks,
            modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { PrivacyText("›", style = MaterialTheme.typography.titleLarge) }
    }
}
@Composable
private fun ScheduleModeToggle(
    viewMode: ScheduleViewMode,
    onModeChanged: (ScheduleViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .width(172.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(ScheduleViewMode.WEEK to "周课表", ScheduleViewMode.AGENDA to "日程").forEach { (mode, label) ->
            val selected = viewMode == mode
            Surface(
                // 选中态由浅蓝底 + 主色文字表达，不再靠 1dp 硬描边。
                color = if (selected) themePrimarySoft() else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 30.dp)
                    .clickable { onModeChanged(mode) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PrivacyText(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleNextCourseCard(
    groups: List<XmuScheduleGroup>,
    selectedIsCurrent: Boolean,
    todayWeekday: Int,
    nowValue: Int,
    phase: XmuTermPhase,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
) {
    if (!selectedIsCurrent || phase != XmuTermPhase.DURING) return
    val todayGroups = groups.filter { it.weekday == todayWeekday }.sortedBy { it.startTime }
    val currentOrNext = todayGroups.firstOrNull { it.endTime >= nowValue }
    val title = when {
        todayGroups.isEmpty() -> "今天没有课"
        currentOrNext == null -> "今天的课程已结束"
        // 考试条目用「考试」措辞，避免把考试说成「正在上课」。
        currentOrNext.isExam -> if (currentOrNext.startTime <= nowValue) "正在考试" else "下一场考试"
        currentOrNext.startTime <= nowValue -> "正在上课"
        else -> "下一节课"
    }
    Surface(
        color = if (currentOrNext?.startTime?.let { it <= nowValue } == true) themeSoftGreen() else themePrimarySoft(),
        shape = AppCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> if (currentOrNext != null) base.clickable { onCourseSelected(currentOrNext) } else base },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PrivacyText(title, color = themePrimary(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            currentOrNext?.let { group ->
                PrivacyText(normalizeScheduleBrackets(group.courseName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PrivacyText(
                    "${formatXmuTime(group.startTime)}—${formatXmuTime(group.endTime)} · " +
                        scheduleLocationSummary(group),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 明日课程：按真实日期预览明天的课，跨周时取下一周数据；点击可看课程详情。
 *重做：旧版单行 Row 里教室文本没有宽度约束，长教室名会挤压课程名
 *甚至溢出；改为每节课一张两行小卡片，时间列固定宽度、正文按 weight 自适应并省略。 */
@Composable
private fun ScheduleTomorrowCard(
    tomorrow: LocalDate,
    groups: List<XmuScheduleGroup>,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
) {
    Surface(
        color = themePrimarySoft(),
        shape = AppCardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyText(
                "明日课程 · 周${xmuWeekdayShort(xmuWeekdayFrom(tomorrow))} ${tomorrow.monthValue}/${tomorrow.dayOfMonth}",
                color = themePrimary(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            if (groups.isEmpty()) {
                PrivacyText(
                    "明天没有课",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                groups.forEach { group ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = AppInnerShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCourseSelected(group) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(46.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                PrivacyText(
                                    formatXmuTime(group.startTime),
                                    color = themePrimary(),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                PrivacyText(
                                    formatXmuTime(group.endTime),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(36.dp)
                                    .background(scheduleCourseColor(group.courseName), RoundedCornerShape(999.dp)),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                PrivacyText(
                                    normalizeScheduleBrackets(group.courseName),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                PrivacyText(
                                    "${group.startSection}-${group.endSection}节 · " +
                                        scheduleLocationSummary(group).replace('\n', ' '),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScheduleGridHeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    highlighted: Boolean,
    textScale: Float = 1f,
) {
    Surface(
        color = if (highlighted) themeSelectedChip() else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) Color.White else themePrimary(),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .width(width)
            .heightIn(min = 44.dp * textScale),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val base = MaterialTheme.typography.labelMedium
            PrivacyText(
                text,
                style = base.copy(fontSize = base.fontSize * textScale),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ScheduleGridCourseBlock(
    group: XmuScheduleGroup,
    dimmed: Boolean,
    inProgress: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlapping: Boolean = false,
    textScale: Float = 1f,
) {
    // 单元格文字：按需求恢复 的紧凑排版 —— 左对齐、逐行自然换行，
    // 只保留「课程名 / 节次 / 地点」三行，起止时间不再在格子里重复（已由左侧节次列承担）。
    fun scaled(style: TextStyle) = style.copy(
        fontSize = style.fontSize * textScale,
        lineHeight = style.fontSize * textScale * 1.15f,
    )
    val dark = LocalXmuDarkTheme.current
    // 考试块统一改用暗红，不再用警示色；深色模式下提亮，保证底色与文字可读。
    val examInk = if (dark) Color(0xFFE08282) else Color(0xFF8E1B1B)
    // 考试不按课程名散列配色，避免同一场考试在不同视图里换色。
    val color = if (group.isExam) examInk else scheduleCourseColor(group.courseName)
    // 深色模式：课程色底加深、文字改浅，避免半透明色叠深底后深字不可读
    val blockColor = color.copy(alpha = if (dark) 0.28f else 0.18f).compositeOver(MaterialTheme.colorScheme.surface)
    // inProgress 在深色下也换深底浅字：浅绿底 + 近白字对比不足（审查 M2）
    val inProgressBlock = if (dark) Color(0xFF1E3A32) else Color(0xFFD9F5E7)
    val textColor = if (dark) Color(0xFFE3EBF3) else Color(0xFF16283A)
    val detailColor = if (dark) Color(0xFF9FB0C0) else Color(0xFF425B72)
    Surface(
        color = if (inProgress) inProgressBlock else blockColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(if (inProgress) 2.dp else 1.dp, if (inProgress) AppSuccess else color.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxSize()
            .alpha(if (dimmed) 0.48f else 1f)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(7.dp * textScale), verticalArrangement = Arrangement.spacedBy(4.dp * textScale)) {
            if (overlapping) PrivacyText(
                "时间重叠",
                style = scaled(MaterialTheme.typography.labelSmall),
                color = themeWarning(),
            )
            // 调课改过的课加「[调]」前缀；考试同时加「[考试]」前缀（考试不会是被调的课）。
            val displayName = buildString {
                if (group.isAdjusted) append("[调]")
                if (group.isExam) append("[考试]")
                append(sanitizeScheduleDisplay(group.courseName))
            }
            PrivacyText(
                displayName,
                style = scaled(MaterialTheme.typography.labelMedium),
                fontWeight = FontWeight.Bold,
                color = if (group.isExam) examInk else textColor,
            )
            PrivacyText(
                "${group.startSection}-${group.endSection}节",
                style = scaled(MaterialTheme.typography.labelSmall),
                color = detailColor,
            )
            // 考试时间保留。
            // startTime / endTime 是「分钟数」形式的 Int，要经 formatXmuTime 转成 09:00 再显示。
            if (group.isExam && group.startTime > 0) {
                FlowRow {
                    PrivacyText(formatXmuTime(group.startTime), style = scaled(MaterialTheme.typography.labelSmall), color = examInk)
                    PrivacyText("–${formatXmuTime(group.endTime)}", style = scaled(MaterialTheme.typography.labelSmall), color = examInk)
                }
            }
            PrivacyText(
                // 格子里走 forGrid 分支 —— 只删「楼」字；无教室仍显示「无 / 教室信息」
                // 两行（恢复，只有桌面小组件才精简成「无」）。
                glueParentheses(scheduleLocationSummary(group, forGrid = true)),
                style = scaled(MaterialTheme.typography.bodySmall),
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun ScheduleAgendaView(
    groups: List<XmuScheduleGroup>,
    selectedWeek: Int,
    selectedDay: Int,
    onDaySelected: (Int) -> Unit,
    selectedIsCurrent: Boolean,
    todayWeekday: Int,
    nowValue: Int,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (1..7).forEach { weekday ->
            val count = groups.count { it.weekday == weekday }
            val active = selectedDay == weekday
            // 只有「选中今天」时才用绿色标记；未选中时今天格与普通日子一致。
            // 旧版 isToday 优先级压过 active，导致选中别的日子时今天仍是绿色。
            val isTodaySelected = selectedIsCurrent && weekday == todayWeekday && active
            Surface(
                color = when {
                    isTodaySelected -> AppSuccess
                    active -> themeSelectedChip()
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (active) Color.White else themePrimary(),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 38.dp)
                    .clickable { onDaySelected(weekday) },
            ) {
                PrivacyText(
                    "周${xmuWeekdayShort(weekday)} $count",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
    val dayGroups = groups.filter { it.weekday == selectedDay }.sortedBy { it.startTime }
    if (dayGroups.isEmpty()) {
        EmptyState("周${xmuWeekdayShort(selectedDay)}没有课", "选择其他日期查看本周课程。")
        return
    }
    // 已上过的课不再平铺成一列，改成互相重叠的一摞。
    // 收起态里后面的卡只画「时间 / 课名 / 节次」顶部条带，不画地点与周次，
    // 露出的部分不会出现半截地点；整摞半透明但用离屏合成，遮挡关系仍然正确。
    // 点击整摞展开成 那样的正常平铺；展开状态只用 remember（不落 rememberSaveable），
    // 离开日程页 / 切到周课表再回来时组合被销毁，回来自动重新收起。
    val (stackGroups, restGroups) = if (selectedIsCurrent && selectedDay == todayWeekday) {
        dayGroups.partition { it.endTime < nowValue }
    } else {
        emptyList<XmuScheduleGroup>() to dayGroups
    }
    var stackExpanded by remember(selectedWeek, selectedDay) { mutableStateOf(false) }
    if (stackExpanded) {
        stackGroups.forEach { group ->
            AgendaCourseCard(group = group, inProgress = false, onClick = { onCourseSelected(group) })
        }
    } else if (stackGroups.isNotEmpty()) {
        AgendaPastCourseDeck(
            groups = stackGroups,
            onClick = { stackExpanded = true },
        )
    }
    restGroups.forEach { group ->
        val inProgress = selectedIsCurrent &&
            selectedDay == todayWeekday &&
            group.startTime <= nowValue &&
            group.endTime >= nowValue
        AgendaCourseCard(group = group, inProgress = inProgress, onClick = { onCourseSelected(group) })
    }
}

/** 日程里的一张课程卡：未上的课与已上的课共用这一套版式（v71）。 */
@Composable
private fun AgendaCourseCard(
    group: XmuScheduleGroup,
    inProgress: Boolean,
    onClick: () -> Unit,
    /**收起态里后面的卡只画顶部条带，不画地点 / 周次，避免露出半截地点。 */
    showDetails: Boolean = true,
    /**收起态由整摞统一响应点击，卡自身不抢点击事件。 */
    clickable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 日程里的考试卡不再用学院蓝。蓝色全部换成同色阶的红：
    // 深红用于起始时间与考试名，亮一档的红用于节次与「考试」标记，侧面条带保持暗红。
    // 与周课表格子的 examInk 同源，深浅模式各有一套，避免深底上深红不可读。
    val examInk = if (LocalXmuDarkTheme.current) Color(0xFFE08282) else Color(0xFF8E1B1B)
    // 进行中的考试卡不再套用课程的浅绿 —— 改浅红底 + 红描边。
    // 深红文字在浅红底上仍有足够对比；课程卡保持原来的浅绿 / 绿边不变。
    val inProgressContainer = if (group.isExam) themeSoftRed() else themeSoftGreen()
    val inProgressBorder = if (group.isExam) themeWarning() else AppSuccess
    Surface(
        color = if (inProgress) inProgressContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (inProgress) 2.dp else 1.dp, if (inProgress) inProgressBorder else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.width(58.dp)) {
                PrivacyText(
                    formatXmuTime(group.startTime),
                    color = if (group.isExam) examInk else themePrimary(),
                    fontWeight = FontWeight.Bold,
                )
                // 收起态条带只有一行（44dp = 12 + 24 + 8），结束时间与地点 / 周次一起不画；
                // 否则它正好落在下一张卡的上沿，会被切成半截。展开态仍完整显示。
                if (showDetails) {
                    PrivacyText(formatXmuTime(group.endTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .background(
                        if (group.isExam) themeWarning() else scheduleCourseColor(group.courseName),
                        RoundedCornerShape(999.dp),
                    ),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 调课改过的课在日程里也带「[调]」前缀。
                    PrivacyText(
                        (if (group.isAdjusted) "[调]" else "") + normalizeScheduleBrackets(group.courseName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (group.isExam) examInk else MaterialTheme.colorScheme.onSurface,
                        maxLines = if (showDetails) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 考试条目在日程里带「考试」标记，与课程区分。
                    if (group.isExam) {
                        PrivacyText(
                            "考试",
                            style = MaterialTheme.typography.labelSmall,
                            color = themeWarning(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    PrivacyText(
                        "${group.startSection}-${group.endSection}节",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (group.isExam) themeWarning() else themePrimary(),
                    )
                }
                if (showDetails) {
                    PrivacyText(
                        scheduleLocationSummary(group),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyText(
                        group.weeks.ifBlank { "周次未标注" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 *已上过的课收成一摞，整块可点，点开后由外层改成 那样的正常平铺。
 * 步进 46dp，再收到 44dp；收起态条带只画一行「起始时间 / 课名 / 节次」。
 *
 * - 后面的卡 `showDetails = false`，只画「时间 / 课名 / 节次」顶部条带，不画结束时间、
 *地点与周次；露出的高度里根本不存在第二行文字，下半截由前一张卡完整遮住。
 *课名上方空白 12dp 保持不变、下方空白 8dp，文字位置不动。
 * - 半透明施加在「整摞合成后的一层」：卡在离屏缓冲里用不透明底色按先后绘制，后画的盖住
 *前画的，再把整层按 alpha 合成到页面。前卡半透明时透出的是页面背景，而不是它压住的
 *后卡内容；若把 alpha 逐张加在卡上，被压住的文字就会从前卡里透出来（踩过的坑）。
 * - 展开状态由外层用普通 remember 保存，离开日程页即销毁，回来自动收起。
 */
@Composable
private fun AgendaPastCourseDeck(
    groups: List<XmuScheduleGroup>,
    onClick: () -> Unit,
) {
    OverlapStack(
        step = AGENDA_STACK_STEP,
        modifier = Modifier
            .graphicsLayer {
                alpha = AGENDA_STACK_ALPHA
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .clickable(onClick = onClick),
    ) {
        groups.forEachIndexed { index, group ->
            AgendaCourseCard(
                group = group,
                inProgress = false,
                showDetails = index == groups.lastIndex,
                clickable = false,
                onClick = {},
            )
        }
    }
}

/**
 * 自写 Layout；改为固定步进：第 i 张的顶部 = i * step。
 * 后提交的卡片画在上层，底色不透明，因此完整遮住前一张被压住的部分。
 * 整摞的半透明由外层统一施加，不在这里逐张设 alpha。
 */
@Composable
private fun OverlapStack(
    step: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val stepPx = step.roundToPx()
        val total = placeables.lastOrNull()?.let { last ->
            stepPx * (placeables.size - 1) + last.height
        } ?: 0
        layout(constraints.maxWidth, total) {
            placeables.forEachIndexed { index, placeable -> placeable.place(0, index * stepPx) }
        }
    }
}

@Composable
private fun ScheduleCourseDetails(
    group: XmuScheduleGroup,
    hasLocalOverride: Boolean,
    onClose: () -> Unit,
    onSave: (ScheduleCourseOverride) -> Unit,
    onRestoreOfficial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable(group.courseName, group.weekday, group.startSection, group.endSection) { mutableStateOf(false) }
    var room by remember(group) {
        mutableStateOf(
            scheduleLocationSummary(group)
                .takeUnless { it == SCHEDULE_NO_ROOM_TEXT || it == SCHEDULE_NO_ROOM_TEXT_GRID }
                .orEmpty(),
        )
    }
    var teacher by remember(group) { mutableStateOf(group.teachers.joinToString("、")) }
    var weeks by remember(group) { mutableStateOf(group.weeks) }
    var weekdayText by remember(group) { mutableStateOf(group.weekday.toString()) }
    var startSectionText by remember(group) { mutableStateOf(group.startSection.toString()) }
    var endSectionText by remember(group) { mutableStateOf(group.endSection.toString()) }
    var validationError by remember { mutableStateOf("") }

    // 考试条目只读 —— 考试不是课程，「修改安排」会写出以课程名为锚点的本地调整，
    // 一旦同名就会改到真实课程上。考试详情因此只展示，不提供编辑。
    if (group.isExam) {
        InfoCard(container = themeInfoCard(), modifier = modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PrivacyText(normalizeScheduleBrackets(group.courseName), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    PrivacyText("考试安排", style = MaterialTheme.typography.labelMedium, color = themeWarning())
                }
            }
            PrivacyText("${weekdayName(group.weekday)} · ${formatXmuTime(group.startTime)}—${formatXmuTime(group.endTime)}")
            PrivacyText("节次：${group.startSection}—${group.endSection}节")
            PrivacyText("考场：${scheduleLocationSummary(group)}")
            PrivacyText(
                "考试安排由教务系统或自定义记录生成，不可在这里修改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    InfoCard(container = themeInfoCard(), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PrivacyText(normalizeScheduleBrackets(group.courseName), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                PrivacyText("我的上课安排", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { if (editing) editing = false else editing = true }) {
                PrivacyText(if (editing) "取消编辑" else "修改安排")
            }
        }

        if (!editing) {
            if (hasLocalOverride) PreviewBadge("已修改", PreviewBadgeTone.GREEN)
            PrivacyText("${weekdayName(group.weekday)} · ${formatXmuTime(group.startTime)}—${formatXmuTime(group.endTime)}")
            PrivacyText("节次：${group.startSection}—${group.endSection}节")
            PrivacyText("周次：${group.weeks.ifBlank { "未标注" }}")
            PrivacyText("教室：${scheduleLocationSummary(group)}")
            PrivacyText("教师：${group.teachers.joinToString("、").ifBlank { "未标注" }}")
            if (hasLocalOverride) {
                OutlinedButton(onClick = onRestoreOfficial, modifier = Modifier.fillMaxWidth()) {
                    PrivacyText("恢复教务系统安排")
                }
            }
        } else {
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                label = { PrivacyText("教室或地点") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = weekdayText,
                    onValueChange = { weekdayText = it.filter(Char::isDigit).take(1) },
                    label = { PrivacyText("星期 1—7") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = startSectionText,
                    onValueChange = { startSectionText = it.filter(Char::isDigit).take(2) },
                    label = { PrivacyText("开始节次") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endSectionText,
                    onValueChange = { endSectionText = it.filter(Char::isDigit).take(2) },
                    label = { PrivacyText("结束节次") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = weeks,
                onValueChange = { weeks = it },
                label = { PrivacyText("周次") },
                placeholder = { PrivacyText("例如：第 1—16 周") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { PrivacyText("教师与教学班") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (validationError.isNotBlank()) {
                PrivacyText(
                    validationError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    val weekday = weekdayText.toIntOrNull()
                    val start = startSectionText.toIntOrNull()
                    val end = endSectionText.toIntOrNull()
                    validationError = when {
                        weekday == null || weekday !in 1..7 -> "星期请输入 1—7"
                        start == null || start !in 1..11 -> "开始节次请输入 1—11"
                        end == null || end !in start..11 -> "结束节次不能早于开始节次"
                        else -> ""
                    }
                    if (validationError.isEmpty()) {
                        onSave(
                            ScheduleCourseOverride(
                                weekday = weekday!!,
                                startSection = start!!,
                                endSection = end!!,
                                room = room,
                                teacher = teacher,
                                weeks = weeks,
                            ),
                        )
                        editing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                PrivacyText("保存")
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { PrivacyText("关闭") }
    }
}

internal val SCHEDULE_COLOR_PALETTE = listOf(
    Color(0xFF8EA9BE),
    Color(0xFF91AE9F),
    Color(0xFFC09A7A),
    Color(0xFF9B94B6),
    Color(0xFFB28E98),
    Color(0xFF7F9FA7),
)

/** 课程色在调色板里的下标。桌面小组件要用它挑对应的 drawable，故单独暴露一个入口。 */
internal fun scheduleCourseColorIndex(courseName: String): Int =
    (courseName.hashCode().toLong() and 0x7FFFFFFF).toInt() % SCHEDULE_COLOR_PALETTE.size

internal fun scheduleCourseColor(courseName: String): Color =
    SCHEDULE_COLOR_PALETTE[scheduleCourseColorIndex(courseName)]

/** 全角括号转半角：所有课表展示位统一（显示层规范，不改缓存数据）。 */
internal fun normalizeScheduleBrackets(value: String): String =
    value.replace('（', '(').replace('）', ')')

/** 周课表格子专用：括号规范化 + 删「楼」字（窄列省空间）。
 *日程、明日课程、下一节课、详情、桌面小组件都不再删「楼」。 */
internal fun sanitizeScheduleDisplay(value: String): String =
    normalizeScheduleBrackets(value).replace("楼", "")

/** 括号内容粘住不拆行：括号内每个字符后插入 U+2060（零宽 word joiner），
 *防止窄列里 "一号(1号)" 被拆成 "(1\n号)" 这种括号内断行。 */
internal fun glueParentheses(value: String): String {
    val joiner = "\u2060"
    val sb = StringBuilder(value.length * 2)
    var inParen = false
    for (c in value) {
        if (c == '(') inParen = true
        sb.append(c)
        // 右括号后不粘，允许在括号闭合后正常换行
        if (inParen && c != ')') sb.append(joiner)
        if (c == ')') inParen = false
    }
    return sb.toString()
}

/** 教室未标注：周课表窄列分两行写「无 / 教室信息」（窄格里会再折成三行，这是原本的显示效果）。 */
internal const val SCHEDULE_NO_ROOM_TEXT_GRID = "无\n教室信息"
internal const val SCHEDULE_NO_ROOM_TEXT = "无教室信息"

/**
 * 周课表缩放：内部保存的是真实缩放值，界面显示值 = 缩放值 / [GRID_SCALE_BASE] * 100，
 * 即默认值 0.75 显示为 100%（把默认点重标为 100%）。
 *显示上限由 200% 收到 150%，对应真实缩放上限 [MAX_GRID_SCALE]（0.25~1.125）。
 */
internal const val GRID_SCALE_BASE = 0.75f
internal const val MIN_GRID_SCALE = 0.25f
internal const val MAX_GRID_SCALE = 1.125f

/** forGrid=true 仅用于周课表格子：删「楼」、多教室竖排、无教室显示两行；
 *其余展示位保留「楼」、多教室用「、」连接、无教室显示完整一行。 */
internal fun scheduleLocationSummary(group: XmuScheduleGroup, forGrid: Boolean = false): String =
    group.rooms
        .map { if (forGrid) sanitizeScheduleDisplay(it.trim()) else normalizeScheduleBrackets(it.trim()) }
        .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .distinct()
        .joinToString(if (forGrid) "\n" else "、")
        .ifBlank { if (forGrid) SCHEDULE_NO_ROOM_TEXT_GRID else SCHEDULE_NO_ROOM_TEXT }

internal fun weekdayName(weekday: Int): String = when (weekday) {
    1 -> "星期一"
    2 -> "星期二"
    3 -> "星期三"
    4 -> "星期四"
    5 -> "星期五"
    6 -> "星期六"
    7 -> "星期日"
    else -> "星期$weekday"
}

/**
 *周次选择面板 —— 仿「选择节次」面板：1..totalWeeks 平铺成三列网格，
 * 点击切换选中状态，可单选也可多选。
 */
/**
 *调课弹窗的分块容器 —— 每块一个极浅描边的卡片，标题在左，操作键在右。
 * 边框用 outlineVariant 低透明度，避免四块框线太重把弹窗压得很闷。
 */
/**
 *调课弹窗专用紫色配色 —— 「全部背景色用紫色」。
 * 弹窗底色最深、每块浅一档，块内选中的键再用一档更亮的紫，层次靠明度区分而不靠灰边。
 */
@Composable
private fun swapPurpleBg(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF211932) else Color(0xFFEDE4FA)

@Composable
private fun swapPurpleSection(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF2C2342) else Color(0xFFF8F3FE)

@Composable
private fun swapPurpleSoft(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF43325F) else Color(0xFFE3D4F8)

@Composable
private fun swapPurpleInk(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFD8C6F4) else Color(0xFF4B2A85)

@Composable
private fun swapPurpleLine(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF4C3E68) else Color(0xFFDDCEF1)

/**
 *目标周次 / 目标星期 / 目标节次的框色与字色 —— #E9F2FB 框、#1C3F6A 字。
 * 深色主题下等亮度的深蓝底 + 浅蓝字，否则浅蓝块在暗底上会刺眼。
 */
@Composable
private fun swapFieldBox(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF1B2E45) else Color(0xFFE9F2FB)

@Composable
private fun swapFieldInk(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFCFE2F6) else Color(0xFF1C3F6A)

/**「调换周次」选中态 —— 与「选择周次」按钮同款淡蓝实底，配 #1C3F6A 细边框。 */
@Composable
private fun swapPickBox(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF1B2E45) else Color(0xFFEAF2FB)

@Composable
private fun swapPickLine(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF4E7CA8) else Color(0xFF1C3F6A)

@Composable
private fun swapPickInk(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFCFE2F6) else Color(0xFF1C3F6A)

@Composable
private fun SwapSection(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = swapPurpleSection(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, swapPurpleLine()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrivacyText(
                    title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = swapPurpleInk(),
                )
                action?.invoke()
            }
            content()
        }
    }
}

/** 弹窗里的紫色文字键（确认 / 展开 / 收起），与块底色同一套紫调。 */
@Composable
private fun SwapTextAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        PrivacyText(label, color = if (enabled) swapPurpleInk() else swapPurpleInk().copy(alpha = 0.4f))
    }
}

/**「调换周次 / 目标周次」里每一对的处理结果 —— 搬到某一周、删除这一次课、保持不变。 */
internal sealed interface SwapAssignment {
    val sourceWeek: Int

    data class Move(override val sourceWeek: Int, val targetWeek: Int) : SwapAssignment
    data class Delete(override val sourceWeek: Int) : SwapAssignment
}

/** 调课弹窗里「目标周次」的槽位：搬到第 N 周 / 删除 / 不变。按顺序与「调换周次」一一对应。 */
private sealed interface SwapSlot {
    data class Week(val week: Int) : SwapSlot
    object Delete : SwapSlot
    object Keep : SwapSlot
}

private fun encodeSwapSlot(slot: SwapSlot): String = when (slot) {
    is SwapSlot.Week -> "w${slot.week}"
    SwapSlot.Delete -> "d"
    SwapSlot.Keep -> "k"
}

private fun decodeSwapSlot(raw: String): SwapSlot? = when {
    raw == "d" -> SwapSlot.Delete
    raw == "k" -> SwapSlot.Keep
    raw.startsWith("w") -> raw.drop(1).toIntOrNull()?.let { SwapSlot.Week(it) }
    else -> null
}

/**序号气泡 —— 红底白字，标出这是第几个被选中的周次。 */
@Composable
private fun OrderBadge(order: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 14.dp, minHeight = 14.dp)
            .background(Color(0xFFE04A3F), RoundedCornerShape(7.dp))
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        PrivacyText(
            "$order",
            fontSize = 9.sp,
            lineHeight = 10.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 *周次网格 —— 「调换周次」与「目标周次」共用。
 * 每行 6 个只写数字；选中格右上角带红色气泡角标标出选中顺序，只选 1 个时不显示。
 * 两块配色各传一套：调换周次走淡蓝实底 + 深蓝细边框，「选择周次」按钮同款；
 * 目标周次未选中是浅蓝底深蓝字，选中仍是弹窗原来的紫色。
 */
@Composable
private fun WeekChoiceGrid(
    totalWeeks: Int,
    isSelected: (Int) -> Boolean,
    orderOf: (Int) -> Int?,
    showBadges: Boolean,
    selectedFill: Color,
    selectedLine: Color,
    selectedInk: Color,
    plainFill: Color,
    plainLine: Color,
    plainInk: Color,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..totalWeeks.coerceAtLeast(1)).chunked(6).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { week ->
                    val selected = isSelected(week)
                    Surface(
                        color = if (selected) selectedFill else plainFill,
                        contentColor = if (selected) selectedInk else plainInk,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (selected) selectedLine else plainLine),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clickable { onPick(week) },
                    ) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            PrivacyText(
                                "$week",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) selectedInk else plainInk,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            if (selected && showBadges) {
                                orderOf(week)?.let { order ->
                                    OrderBadge(order, Modifier.align(Alignment.TopEnd).padding(2.dp))
                                }
                            }
                        }
                    }
                }
                repeat(6 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 调课弹窗里的紧凑选择键：均分整行、一排放下。
 *未选中改为浅蓝底（#E9F2FB）+ 深蓝字（#1C3F6A），选中态保持原来的紫色。
 */
@Composable
private fun CompactChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .border(1.dp, if (selected) swapPurpleInk() else swapFieldBox(), shape)
            .background(if (selected) swapPurpleSoft() else swapFieldBox())
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PrivacyText(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !enabled -> swapFieldInk().copy(alpha = 0.35f)
                selected -> swapPurpleInk()
                else -> swapFieldInk()
            },
            maxLines = 1,
        )
    }
}

/** 「目标周次」的配对预览：1→删除　11→第 4 周　9→不变。 */
private fun swapPairingText(sourceWeeks: List<Int>, slots: List<SwapSlot>): String =
    sourceWeeks.mapIndexed { index, week ->
        val label = when (val slot = slots.getOrNull(index)) {
            is SwapSlot.Week -> "第 ${slot.week} 周"
            SwapSlot.Delete -> "删除"
            else -> "不变"
        }
        "$week→$label"
    }.joinToString("　")

/**「目标周次」块左下的槽位键（不变 / 删除），浅蓝底深蓝字，可反复点击追加。 */
@Composable
private fun SwapSlotKey(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .border(1.dp, swapFieldBox(), shape)
            .background(swapFieldBox())
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        PrivacyText(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = swapFieldInk().copy(alpha = if (enabled) 1f else 0.35f),
            maxLines = 1,
        )
    }
}

/**
 *调课弹窗 —— 选一门课、选目标星期与起始节次；目标时段已有课就提示「两门互换」，
 *必须点确认才落盘。全部写成课表本地调整，不动教务数据。
 *
 *调整对象由「整门课的所有周次」改为「某一周的那一次课」。
 *生效范围只留「全部周次 / 选择周次」两个按钮；选择周次后平铺周次网格，可单选可多选。
 *「选择课程」行末尾增加展开键，展开后全部课程上下分排；目标星期只显示「一」「二」短标签。
 *两块标题去掉「范围」二字；「调换周次」改成有序多选（红气泡标第几个选中），
 *选中态与「选择周次」按钮统一；「目标周次」按顺序一一对应，可以是某一周、不变或删除；
 *删除不再立即执行，统一由「确认调课」落盘；目标周次默认不选 = 不调换。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SwapCourseDialog(
    groups: List<XmuScheduleGroup>,
    currentWeek: Int,
    totalWeeks: Int,
    onDismiss: () -> Unit,
    // allWeeks = 整学期全部周次（原地改时段）；assignments 按「调换周次 → 目标周次」顺序配对。
    onConfirm: (XmuScheduleGroup, Int, Int, Boolean, List<SwapAssignment>) -> Unit,
) {
    // 「目标周次」「调换周次」固定铺到 18 周；学期更长时按学期实际周数显示。
    val weekCount = maxOf(totalWeeks, 18)
    var courseIndex by rememberSaveable { mutableIntStateOf(0) }
    val course = groups.getOrNull(courseIndex.coerceIn(0, groups.lastIndex))
    var targetWeekday by rememberSaveable { mutableIntStateOf(course?.weekday ?: 1) }
    var targetStart by rememberSaveable { mutableIntStateOf(course?.startSection ?: 1) }
    // 选择课程：折叠时横向滚动，末尾的展开键可把全部课程上下分排铺开。
    var coursesExpanded by rememberSaveable { mutableStateOf(false) }
    // 生效范围：默认「选择周次」并落在当前显示周，维持「调这一次课」的既有语义。
    var scopeMode by rememberSaveable { mutableIntStateOf(1) }
    var weekPickerExpanded by rememberSaveable { mutableStateOf(false) }
    // 「调换周次」按选中顺序排列，顺序决定与「目标周次」的配对。
    var sourceWeeksText by rememberSaveable {
        mutableStateOf(currentWeek.coerceIn(1, weekCount.coerceAtLeast(1)).toString())
    }
    val sourceWeeks = remember(sourceWeeksText, weekCount) {
        sourceWeeksText.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..weekCount }
            .distinct()
    }
    // 「目标周次」是槽位序列 —— 搬到某一周 / 不变 / 删除；默认空 = 不调换。
    var slotsText by rememberSaveable { mutableStateOf("") }
    val slots = remember(slotsText) { slotsText.split(',').mapNotNull { decodeSwapSlot(it.trim()) } }
    // 两级 —— 0 显示「选择调换科目」+「调换周次」，确认后目标周次 / 目标星期 / 起始节次一起出现。
    var step by rememberSaveable { mutableIntStateOf(0) }
    val length = course?.let { it.endSection - it.startSection + 1 } ?: 2
    val start = targetStart.coerceIn(1, 11)
    val end = (start + length - 1).coerceAtMost(11)
    // 全周调课（不跨周）时当前周内的占位课：用于提示「确认后两门课对调」。
    val occupant = course?.let { moving ->
        groups.firstOrNull { other ->
            other != moving &&
                other.weekday == targetWeekday &&
                other.startSection <= end &&
                other.endSection >= start
        }
    }

    fun setSlots(value: List<SwapSlot>) {
        slotsText = value.joinToString(",") { encodeSwapSlot(it) }
    }

    fun toggleSourceWeek(week: Int) {
        val index = sourceWeeks.indexOf(week)
        if (index < 0) {
            sourceWeeksText = (sourceWeeks + week).joinToString(",")
            return
        }
        // 取消某一周时把它的配对一并去掉，剩下的仍然按顺序对应。
        val kept = slots.filterIndexed { slotIndex, _ -> slotIndex != index }
        sourceWeeksText = (sourceWeeks - week).joinToString(",")
        setSlots(kept.take((sourceWeeks.size - 1).coerceAtLeast(0)))
    }

    fun addSlot(slot: SwapSlot) {
        if (scopeMode == 0) return
        if (sourceWeeks.isEmpty() || slots.size >= sourceWeeks.size) return
        if (slot is SwapSlot.Week && slots.any { it is SwapSlot.Week && it.week == slot.week }) return
        setSlots(slots + slot)
    }

    fun removeWeekSlot(week: Int) {
        val index = slots.indexOfFirst { it is SwapSlot.Week && it.week == week }
        if (index < 0) return
        setSlots(slots.filterIndexed { slotIndex, _ -> slotIndex != index })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = swapPurpleBg(),
        titleContentColor = swapPurpleInk(),
        textContentColor = swapPurpleInk(),
        title = { PrivacyText("调课") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrivacyText(
                    "把某一周的一次课与其他时段互换；仅修改本机课表，可在课程详情中还原。",
                    style = MaterialTheme.typography.bodySmall,
                    color = swapPurpleInk().copy(alpha = 0.8f),
                )
                // —— 1. 选择调换科目（默认显示，选中即生效，不需要再点确认）——
                SwapSection(
                    title = "选择调换科目",
                    action = {
                        TextButton(onClick = { coursesExpanded = !coursesExpanded }) {
                            PrivacyText(if (coursesExpanded) "收起" else "展开", color = swapPurpleInk())
                        }
                    },
                ) {
                    if (coursesExpanded) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            groups.forEachIndexed { index, item ->
                                FilterChip(
                                    selected = index == courseIndex,
                                    onClick = { courseIndex = index },
                                    label = {
                                        PrivacyText("${normalizeScheduleBrackets(item.courseName)} · ${weekdayName(item.weekday)}${item.startSection}—${item.endSection}")
                                    },
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            groups.forEachIndexed { index, item ->
                                FilterChip(
                                    selected = index == courseIndex,
                                    onClick = { courseIndex = index },
                                    label = {
                                        PrivacyText("${normalizeScheduleBrackets(item.courseName)} · ${weekdayName(item.weekday)}${item.startSection}—${item.endSection}")
                                    },
                                )
                            }
                        }
                    }
                }

                // —— 2. 调换周次（有序多选，整块只有一个确认键）——
                SwapSection(title = "调换周次") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = scopeMode == 0,
                            onClick = {
                                scopeMode = 0
                                weekPickerExpanded = false
                            },
                            label = { PrivacyText("全部周次") },
                        )
                        FilterChip(
                            selected = scopeMode == 1 && weekPickerExpanded,
                            onClick = {
                                val opening = !(scopeMode == 1 && weekPickerExpanded)
                                weekPickerExpanded = opening
                                scopeMode = 1
                                if (sourceWeeks.isEmpty()) {
                                    sourceWeeksText = currentWeek.coerceIn(1, weekCount.coerceAtLeast(1)).toString()
                                }
                                // 重新展开周次网格时收起下面三块，但它们的已选内容全部保留。
                                if (opening) step = 0
                            },
                            label = { PrivacyText("选择周次") },
                        )
                    }
                    if (scopeMode == 1 && weekPickerExpanded) {
                        PrivacyText(
                            "可多选，再次点击取消该周；选中顺序决定与「目标周次」的对应关系。",
                            style = MaterialTheme.typography.bodySmall,
                            color = swapPurpleInk().copy(alpha = 0.75f),
                        )
                        WeekChoiceGrid(
                            totalWeeks = weekCount,
                            isSelected = { week -> week in sourceWeeks },
                            orderOf = { week -> sourceWeeks.indexOf(week).takeIf { it >= 0 }?.plus(1) },
                            showBadges = sourceWeeks.size > 1,
                            selectedFill = swapPickBox(),
                            selectedLine = swapPickLine(),
                            selectedInk = swapPickInk(),
                            plainFill = Color.Transparent,
                            plainLine = swapPurpleLine(),
                            plainInk = swapPurpleInk(),
                            onPick = ::toggleSourceWeek,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SwapTextAction(
                            label = "确认",
                            enabled = scopeMode == 0 || sourceWeeks.isNotEmpty(),
                            onClick = {
                                weekPickerExpanded = false
                                step = 1
                            },
                        )
                    }
                }

                // —— 3. 目标周次（按顺序一一对应「调换周次」，可搬去某周 / 不变 / 删除）——
                // —— 4. 目标星期 ——
                // —— 5. 起始节次 ——
                // 三块在「调换周次」确认后一起显示，改选即时生效。
                if (step >= 1) {
                    SwapSection(title = "目标周次") {
                        if (scopeMode == 0) {
                            PrivacyText(
                                "全部周次不做跨周搬课：确认后只把每一次课改到目标星期与节次。",
                                style = MaterialTheme.typography.bodySmall,
                                color = swapFieldInk().copy(alpha = 0.85f),
                            )
                        } else {
                            PrivacyText(
                                "按顺序与「调换周次」对应：点周次即搬过去，也可选「不变」或「删除」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = swapFieldInk().copy(alpha = 0.85f),
                            )
                            WeekChoiceGrid(
                                totalWeeks = weekCount,
                                isSelected = { week -> slots.any { it is SwapSlot.Week && it.week == week } },
                                orderOf = { week ->
                                    slots.indexOfFirst { it is SwapSlot.Week && it.week == week }
                                        .takeIf { it >= 0 }
                                        ?.plus(1)
                                },
                                showBadges = slots.size > 1,
                                selectedFill = swapPurpleSoft(),
                                selectedLine = swapPurpleInk(),
                                selectedInk = swapPurpleInk(),
                                plainFill = swapFieldBox(),
                                plainLine = swapFieldBox(),
                                plainInk = swapFieldInk(),
                                onPick = { week ->
                                    if (slots.any { it is SwapSlot.Week && it.week == week }) {
                                        removeWeekSlot(week)
                                    } else {
                                        addSlot(SwapSlot.Week(week))
                                    }
                                },
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val canAppend = sourceWeeks.isNotEmpty() && slots.size < sourceWeeks.size
                                    SwapSlotKey("不变", enabled = canAppend) { addSlot(SwapSlot.Keep) }
                                    SwapSlotKey("删除", enabled = canAppend) { addSlot(SwapSlot.Delete) }
                                }
                                PrivacyText(
                                    "已配 ${slots.size}/${sourceWeeks.size} 周",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = swapPurpleInk(),
                                )
                            }
                            if (sourceWeeks.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PrivacyText(
                                        swapPairingText(sourceWeeks, slots),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = swapPurpleInk(),
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 「重置」清空目标周次的全部配对（含不变 / 删除），回到「不调换」。
                                    TextButton(
                                        onClick = { setSlots(emptyList()) },
                                        enabled = slots.isNotEmpty(),
                                    ) {
                                        PrivacyText(
                                            "重置",
                                            color = swapFieldInk().copy(alpha = if (slots.isNotEmpty()) 1f else 0.35f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SwapSection(title = "目标星期") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { day ->
                                CompactChip(
                                    label = xmuWeekdayShort(day),
                                    selected = targetWeekday == day,
                                    modifier = Modifier.weight(1f),
                                    onClick = { targetWeekday = day },
                                )
                            }
                        }
                    }

                    SwapSection(title = "起始节次") {
                        (1..11).chunked(7).forEach { rowItems ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                rowItems.forEach { section ->
                                    CompactChip(
                                        label = "$section",
                                        selected = start == section,
                                        modifier = Modifier.weight(1f),
                                        onClick = { targetStart = section },
                                    )
                                }
                            }
                            repeat(7 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                val scopeText = when {
                    scopeMode == 0 -> "整学期全部周次"
                    sourceWeeks.isEmpty() -> "未选择周次"
                    else -> "第 ${sourceWeeks.joinToString("、")} 周"
                }
                PrivacyText(
                    "$scopeText：${weekdayName(targetWeekday)} 第 $start—$end 节（共 $length 节）",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = swapPurpleInk(),
                )
                PrivacyText(
                    when {
                        scopeMode == 0 && occupant != null ->
                            "该时段已有《${normalizeScheduleBrackets(occupant.courseName)}》（${weekdayName(occupant.weekday)} ${occupant.startSection}—${occupant.endSection} 节），确认后两门课对调。"
                        scopeMode == 0 -> "该时段没有冲突课程，确认后完成调整。"
                        slots.isEmpty() -> "目标周次未选，确认后不搬课也不删除。"
                        else -> "确认后源周那一次课消失，目标周按目标星期与节次显示；目标周该时段原有课时两门课对调。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scopeMode == 0 && occupant != null) themeWarning() else swapPurpleInk().copy(alpha = 0.8f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    course?.let { moving ->
                        val assignments = if (scopeMode == 0) {
                            emptyList()
                        } else {
                            sourceWeeks.mapIndexedNotNull { index, week ->
                                when (val slot = slots.getOrNull(index)) {
                                    is SwapSlot.Week -> SwapAssignment.Move(week, slot.week)
                                    SwapSlot.Delete -> SwapAssignment.Delete(week)
                                    else -> null
                                }
                            }
                        }
                        onConfirm(moving, targetWeekday, start, scopeMode == 0, assignments)
                    }
                },
                enabled = course != null &&
                    step >= 1 &&
                    (if (scopeMode == 0) true else sourceWeeks.isNotEmpty() && slots.isNotEmpty()),
            ) { PrivacyText(if (scopeMode == 0 && occupant != null) "确认对调" else "确认调课") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { PrivacyText("取消", color = swapPurpleInk()) } },
    )
}
