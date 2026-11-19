package com.xmu.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 页面标识。
 *
 * [id] 是跨进程契约值：深链 extra「open_page」、res/xml/shortcuts.xml、
 * 桌面小组件与通知的 PendingIntent 全都按这个字符串匹配。改一个字就是功能静默失效，
 * 所以保留中文原值，只把「比较」从裸字符串换成枚举——编译器从此能查出拼错的名字。
 *
 * 显示名不放这里：页面标题来自 [AppDestination.label]，未登记页面回退到 id
 * （原行为如此，签到页标题就是「签到情况」而不是底栏的「签到」）。
 */
enum class Page(val id: String) {
    HOME("首页"),
    SCHEDULE("课表"),
    ROLLCALL("签到情况"),
    MORE("更多"),
    COURSEWARE("课程课件"),
    EXAMS("考试安排"),
    SCORES("成绩"),
    EVALUATION("教评助手"),
    NOTIFICATION("通知"),
    SETTINGS("策略"),
    COURSE_LOCATION("课程坐标"),
    TUTORIAL("教程"),
    RANKING("专业排名"),
    AUTO_ANSWER("自动签到策略"),
    EXAM_REMINDER_TIME("考试提醒时间"),
    CURRENT_WEEK("当前周次"),
    ADD_PLACE("添加地点"),
    ON_SITE_CAPTURE("现场采点"),
    ;

    companion object {
        /** 外部入口（深链 / 快捷方式 / 组件）字符串 → 页面。未知值返回 null，由调用方兜底回首页。 */
        fun fromId(id: String): Page? = entries.firstOrNull { it.id == id }
    }
}

internal data class AppDestination(
    val page: Page,
    val label: String,
    val icon: Int,
    val description: String = "",
    /** 非空时表示该入口尚未开放：卡片展示状态字样，且不响应点击。 */
    val statusLabel: String = "",
)

internal val primaryDestinations = listOf(
    AppDestination(Page.HOME, "首页", R.drawable.ic_nav_home),
    AppDestination(Page.SCHEDULE, "课表", R.drawable.ic_nav_calendar),
    AppDestination(Page.ROLLCALL, "签到", R.drawable.ic_nav_check),
    AppDestination(Page.MORE, "更多", R.drawable.ic_nav_grid),
)

internal val moreDestinations = listOf(
    AppDestination(Page.COURSEWARE, "课程课件", R.drawable.ic_nav_folder, "批量下载课件"),
    AppDestination(Page.EXAMS, "考试安排", R.drawable.ic_nav_calendar, "考试时间与考场"),
    AppDestination(Page.SCORES, "成绩", R.drawable.ic_nav_scores, "查询成绩与绩点 · 模拟成绩 · 专业排名"),
    // 教评助手功能尚未添加，入口暂时保留但标记「开发中」且不跳转；下级界面代码保留。
    AppDestination(Page.EVALUATION, "教评助手", R.drawable.ic_nav_help, "教学评价入口与使用说明", statusLabel = "开发中"),
    AppDestination(Page.NOTIFICATION, "通知与外观", R.drawable.ic_nav_bell, "通知渠道配置 · 主题外观 · 桌面小组件"),
    AppDestination(Page.SETTINGS, "设置", R.drawable.ic_nav_settings, "签到策略 · 考试提醒 · 周次设置"),
    // 课程坐标是「设置」下的三级界面，不在更多页的分组中展示。
    AppDestination(Page.COURSE_LOCATION, "课程坐标", R.drawable.ic_nav_location, "维护雷达签到的课程地点"),
    // 教程入口移到「更多」页最底部；首页保留同一页的快捷跳转。
    // 入口名「使用准备与教程」改为业内通用的「使用指南」，与页面四个板块对应。
    AppDestination(Page.TUTORIAL, "使用指南", R.drawable.ic_nav_help, "环境检查 · 使用准备 · 常见问题"),
)

/** 页面大标题：更多页里的入口名与 BrandHeader 保持一致。未登记的页面回退到 id。 */
internal fun pageTitle(page: Page): String =
    moreDestinations.firstOrNull { it.page == page }?.label ?: page.id

internal fun primaryPage(page: Page): Page =
    when {
        page == Page.RANKING -> Page.MORE
        primaryDestinations.any { it.page == page } -> page
        else -> Page.MORE
    }

internal fun parentPage(page: Page): Page =
    when (page) {
        // 成绩已从底栏移入「更多」，但专业排名仍然是从成绩页进的，
        // 返回必须回成绩。
        Page.RANKING -> Page.SCORES
        Page.ADD_PLACE, Page.ON_SITE_CAPTURE -> Page.COURSE_LOCATION
        // 课程坐标默认从「设置」进入，返回时回到设置。
        // 首页「自动签到」卡片也能直接进课程坐标，真实返回目标改由 nestedReturnPage 记录，
        // 这里只作为默认值（未见过来源时的兜底）。
        Page.COURSE_LOCATION -> Page.SETTINGS
        // 「自动签到策略」是设置页的下一级界面，默认返回设置。
        // 首页卡片也能直接进自动签到策略，同上由 nestedReturnPage 覆盖。
        Page.AUTO_ANSWER, Page.EXAM_REMINDER_TIME, Page.CURRENT_WEEK -> Page.SETTINGS
        else -> if (moreDestinations.any { it.page == page }) Page.MORE else Page.HOME
    }

/**
 * 「设置」下面的下一级界面（自动签到策略 / 考试提醒时间 / 当前周次 / 课程坐标）。
 *
 *这些页有两个入口 —— 设置页里逐级点进来，或首页「自动签到」卡片直接进入。
 * 返回目标因此不能写死，必须按「进入时的来源页」决定（MainActivity.nestedReturnPage）。
 */
internal val nestedSettingPages = setOf(Page.AUTO_ANSWER, Page.EXAM_REMINDER_TIME, Page.CURRENT_WEEK, Page.COURSE_LOCATION)

/** [nestedSettingPages] 里页面自己的下一级界面：从这些页面返回上级时不更新来源页。 */
internal val nestedSettingChildPages = setOf(Page.ADD_PLACE, Page.ON_SITE_CAPTURE)

/** 返回目标由「进入时的来源页」决定、且底栏「更多」键应显示返回态的页面集合。 */
internal fun isBackTargetPage(page: Page): Boolean = page in toolPages || page in nestedSettingPages


/** 工具页（更多页入口们）：返回目标由「进入时的来源页」决定（首页进→回首页，更多进→回更多）。
 *课程坐标排除在外，它作为设置页的三级界面，返回固定回「设置」。 */
internal val toolPages = moreDestinations.map { it.page }.toSet() - setOf(Page.COURSE_LOCATION)

/** Keep each page's small UI state while its composition is absent; discard it on account changes. */
@Composable
internal fun PageStateHost(page: Page, owner: String, content: @Composable () -> Unit) {
    key(owner) {
        val holder = rememberSaveableStateHolder()
        holder.SaveableStateProvider(page.id, content)
    }
}

@Composable
internal fun AppBackNavigation(page: Page, backTarget: Page, onSelected: (Page) -> Unit) {
    BackHandler(enabled = page != Page.HOME) { onSelected(backTarget) }
}

@Composable
internal fun AppNavigationBar(
    selected: Page,
    notificationSettings: NotificationSettings,
    downloadingCount: Int,
    onSelected: (Page) -> Unit,
    moreTapTarget: Page? = null,
    /**当页（一级页）再次点击底栏键时的动作；仅首页/课表/签到三键有行为。 */
    onReselect: (Page) -> Unit = {},
) {
    // 底栏改为「贴边 + 顶部一条极浅细分隔线」。
    // 去掉四边硬描边与选中胶囊色块：上方内容区已经是白卡片，再叠胶囊会分不清层级。
    // 横向不再有父容器的 13dp 内边距／760dp 宽度上限（见 MainScreen），直接铺满屏幕。
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                primaryDestinations.forEach { destination ->
                    val isSelected = primaryPage(selected) == destination.page
                    // 工具页（二级界面）里底栏「更多」键进入返回态 —— 图标换成返回弯箭头，
                    // 文字同步改为「返回」，底部下划线改用半透明主色。
                    // 判断提到循环开头，图标 / 文字 / 下划线共用同一份结果，避免三处各算一遍走岔。
                    // 判断集合从「工具页」扩到「设置下的下一级界面」—— 首页卡片直接进
                    // 自动签到策略 / 课程坐标时，底栏「更多」键同样应显示返回态。
                    val isBackMode = destination.page == Page.MORE &&
                        moreTapTarget != null && isBackTargetPage(selected) && selected != Page.MORE
                    NavigationBarItem(
                        modifier = Modifier.semantics {
                            if (destination.page == Page.MORE) {
                                stateDescription = when {
                                    // 返回态优先：此刻这键的动作是返回，不是查看更多入口。
                                    isBackMode -> "返回上一页"
                                    downloadingCount > 0 -> "$downloadingCount 个文件下载中"
                                    notificationSettingsMissing(notificationSettings) -> "有通知渠道待配置"
                                    else -> "校园工具与设置"
                                }
                            }
                        },
                        selected = isSelected,
                        onClick = {
                            if (selected != destination.page) {
                                // 在工具页（二级界面）里时，底部「更多」键变成返回弯箭头：
                                // 点它回到进入时的来源页（首页进→首页，更多进→更多）。
                                if (destination.page == Page.MORE && moreTapTarget != null &&
                                    isBackTargetPage(selected) && selected != Page.MORE
                                ) {
                                    onSelected(moreTapTarget)
                                } else {
                                    onSelected(destination.page)
                                }
                            } else if (destination.page != Page.MORE) {
                                // 已在当页（一级页）再次点击 —— 首页滚到底、
                                // 课表切周课表/日程、签到刷新；动作由 MainScreen 分发。
                                onReselect(destination.page)
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            // 选中态颜色加深，与未选中灰拉开差距。
                            selectedIconColor = themePrimaryDeep(),
                            selectedTextColor = themePrimaryDeep(),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        icon = {
                            BadgedBox(badge = {
                                if (destination.page == Page.MORE) {
                                    when {
                                        downloadingCount > 0 -> Badge { PrivacyText(if (downloadingCount > 99) "99+" else "$downloadingCount") }
                                        notificationSettingsMissing(notificationSettings) -> Badge { PrivacyText("!") }
                                    }
                                }
                            }) {
                                // 处于工具页时「更多」键显示返回弯箭头（返回/撤回），否则显示原九宫格。
                                Icon(
                                    painterResource(if (isBackMode) R.drawable.ic_nav_back else destination.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(23.dp),
                                )
                            }
                        },
                        label = {
                            // 选中态在文字下方补一条同色圆角细横线，选择态更明确。
                            // 返回态文字改「返回」，下划线用 40% 主色 —— 返回不是「选中」，
                            // 实色下划线会让人以为当前停在「更多」页。
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PrivacyText(
                                    if (isBackMode) "返回" else destination.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // 底栏是导航坐标，「课表」的「课」保持原样，
                                    // 否则「？表」认不出是哪个页签。其余换词规则照旧生效。
                                    skipRules = setOf("课"),
                                )
                                Box(
                                    Modifier
                                        .padding(top = 2.dp)
                                        .width(16.dp)
                                        .height(2.dp)
                                        .background(
                                            when {
                                                isBackMode -> themePrimaryDeep().copy(alpha = 0.4f)
                                                isSelected -> themePrimaryDeep()
                                                else -> Color.Transparent
                                            },
                                            RoundedCornerShape(999.dp),
                                        ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MorePage(
    notificationSettings: NotificationSettings,
    downloadingCount: Int,
    onSelected: (Page) -> Unit,
) {
    // 按业务域分成两组。学习工具组沿用主色图标；
    // 偏好设置组三张卡改中性灰底 + 深色图标，与工具入口在视觉上分开。
    val learning = listOf(Page.COURSEWARE, Page.EXAMS, Page.SCORES, Page.EVALUATION)
        .mapNotNull { page -> moreDestinations.firstOrNull { it.page == page } }
    val preferences = listOf(Page.SETTINGS, Page.NOTIFICATION, Page.TUTORIAL)
        .mapNotNull { page -> moreDestinations.firstOrNull { it.page == page } }
    val mainIconBackground = themePrimarySoft()
    val mainIconTint = themePrimary()
    val dark = LocalXmuDarkTheme.current
    val neutralIconBackground = if (dark) Color(0xFF303740) else Color(0xFFE9EAEC)
    val neutralIconTint = if (dark) Color(0xFFE6E8EB) else Color(0xFF1F2328)

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // 删除页面大标题「校园工具」——两个分组小标题已足够表明页面结构，
        // 与通知与外观等二级页一样，页面顶部直接从第一个分组标题开始。
        MoreSection(
            title = "学习工具",
            destinations = learning,
            notificationSettings = notificationSettings,
            downloadingCount = downloadingCount,
            iconBackground = mainIconBackground,
            iconTint = mainIconTint,
            onSelected = onSelected,
        )
        Spacer(Modifier.height(6.dp))
        MoreSection(
            title = "偏好设置",
            destinations = preferences,
            notificationSettings = notificationSettings,
            downloadingCount = downloadingCount,
            iconBackground = neutralIconBackground,
            iconTint = neutralIconTint,
            onSelected = onSelected,
        )
    }
}

/**更多页的一个分组：小标题 + 若干入口卡。 */
@Composable
private fun ColumnScope.MoreSection(
    title: String,
    destinations: List<AppDestination>,
    notificationSettings: NotificationSettings,
    downloadingCount: Int,
    iconBackground: Color,
    iconTint: Color,
    onSelected: (Page) -> Unit,
) {
    PreviewSectionHeader(title)
    destinations.forEach { destination ->
        MoreEntryCard(
            destination = destination,
            notificationSettings = notificationSettings,
            downloadingCount = downloadingCount,
            iconBackground = iconBackground,
            iconTint = iconTint,
            onSelected = onSelected,
        )
    }
}

@Composable
private fun MoreEntryCard(
    destination: AppDestination,
    notificationSettings: NotificationSettings,
    downloadingCount: Int,
    iconBackground: Color,
    iconTint: Color,
    onSelected: (Page) -> Unit,
) {
    // 标记「开发中」的入口只展示状态，不进入下级界面。
    val available = destination.statusLabel.isEmpty()
    Surface(
        onClick = { if (available) onSelected(destination.page) },
        enabled = available,
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = AppCardElevation,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreviewIconBox(destination.icon, containerColor = iconBackground, iconTint = iconTint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrivacyText(destination.label, fontWeight = FontWeight.Bold)
                    if (!available) {
                        PreviewBadge(destination.statusLabel, PreviewBadgeTone.AMBER)
                    }
                }
                PrivacyText(destination.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    destination.page == Page.NOTIFICATION && notificationSettingsMissing(notificationSettings) ->
                        PrivacyText("有通知渠道待配置", style = MaterialTheme.typography.labelMedium, color = themeWarning())
                    destination.page == Page.COURSEWARE && downloadingCount > 0 ->
                        PrivacyText("$downloadingCount 个文件下载中", style = MaterialTheme.typography.labelMedium, color = themePrimary())
                }
            }
            if (available) {
                PrivacyText("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
