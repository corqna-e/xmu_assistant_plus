package com.xmu.assistant

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 *教程页四个模块的滚动定位状态。四个模块依次为
 * 运行环境检查 / 使用准备 / 功能说明 / 常见问题；右侧悬浮上下键据此跳转。
 */
internal class TutorialJumpState {
    /** 各模块顶部在滚动内容中的像素位置（onGloballyPositioned 回填）。 */
    val sectionOffsets = IntArray(4)
    /** 当前所处模块（0..3），随滚动实时更新。 */
    var activeSection by mutableIntStateOf(0)
}

@Composable
internal fun TutorialPage(
    scrollState: androidx.compose.foundation.ScrollState,
    navigate: (String) -> Unit,
    onOpenBackgroundSettings: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    /**右侧悬浮跳模块键的定位状态（由 MainScreen 持有）。 */
    jumpState: TutorialJumpState? = null,
) {
    val context = LocalContext.current
    // 滚动时更新当前模块，供右侧悬浮键判断上/下一条与边界禁用。
    if (jumpState != null) {
        LaunchedEffect(Unit) {
            snapshotFlow { scrollState.value }.collect { y ->
                var current = 0
                jumpState.sectionOffsets.forEachIndexed { index, offset ->
                    if (index > 0 && offset > 0 && offset <= y + 100) current = index
                }
                jumpState.activeSection = current
            }
        }
    }
    var entries by remember { mutableStateOf(PermissionAudit.inspect(context)) }
    var checkedAtMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    // 自启动没有系统查询接口，只能由用户现场核对后点「确认正常」。
    // 确认结果落盘，进入本页时直接读回——不再每次进页面都要求确认。
    // 确认绑定「一次检测结果」——点「重新检测」等同于重新审一遍，人工确认随之作废，
    // 需要再点一次「确认正常」；进入本页、从系统设置返回只刷新检测项，不作废确认。
    var autostartConfirmedAt by remember { mutableLongStateOf(PermissionAudit.autostartConfirmedAt(context)) }
    val autostartConfirmed = autostartConfirmedAt > 0L
    val recheck: (Boolean) -> Unit = { dropAutostartConfirmation ->
        entries = PermissionAudit.inspect(context)
        checkedAtMillis = System.currentTimeMillis()
        if (dropAutostartConfirmation) {
            PermissionAudit.clearAutostartConfirmation(context)
            autostartConfirmedAt = 0L
        }
    }
    // 从系统设置页返回时自动重新检测一次，与行内点击后的立即刷新形成双保险。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val passed = entries.count { it.state == AuditState.PASS } + if (autostartConfirmed) 1 else 0
    val total = entries.size
    // 子项目默认折叠；列表只渲染未完成项，已通过项自动隐藏，全部通过时整段明细不显示。
    var detailExpanded by rememberSaveable { mutableStateOf(false) }
    val pending = entries.filter {
        it.state != AuditState.PASS && !(it.key == "autostart" && autostartConfirmed)
    }
    val checkedAtText = remember(checkedAtMillis) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(checkedAtMillis))
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // 运行环境检查卡重做 —— 与页面其它白卡统一（去掉浅蓝整卡与 1.5dp 粗描边），
        // 用「计数 + 细进度条 + 单行状态」替代层层嵌套的彩色块；明细展开后仍逐项可跳设置。
        // 不用 PreviewCard 的统一 contentPadding/spacing —— 需要四边不等的卡底留白
        // 与「重新检测」行的上间距特例，改为直接 Surface + 手动间距（等价原 spacedBy(12)）。
        Surface(
            modifier = Modifier
                .onGloballyPositioned { jumpState?.sectionOffsets?.set(0, it.positionInParent().y.toInt()) }
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = AppCardShape,
            shadowElevation = AppCardElevation,
        ) {
            Column(
                // 卡底 15dp —— 比四边统一 16dp 略松一点，与页面其它卡片的留白节奏一致。
                // 间距一律用 dp 表达，不按截图量像素换算（换设备或改系统字体大小都会失配）。
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 15.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PreviewIconBox(R.drawable.ic_nav_check)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        PrivacyText("运行环境检查", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        PrivacyText(
                            "七项设置决定签到监控与提醒能否稳定送达",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        PrivacyText(
                            "$passed/$total",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (passed == total) themeSuccess() else themePrimary(),
                        )
                        PrivacyText(
                            "项通过",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 通过率细进度条：不引入新颜色，全通过转绿、否则保持主色蓝。
                val accent = if (passed == total) themeSuccess() else themePrimary()
                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(passed.toFloat() / total.coerceAtLeast(1))
                            .height(5.dp)
                            .background(accent, RoundedCornerShape(999.dp)),
                    )
                }
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (pending.isEmpty()) themeSuccess() else themeOnAmber(), CircleShape),
                    )
                    PrivacyText(
                        if (pending.isEmpty()) "七项检查全部通过，后台提醒可正常送达"
                        else "有 ${pending.size} 项待处理，系统可能中断后台提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pending.isNotEmpty()) {
                    Surface(
                        onClick = { detailExpanded = !detailExpanded },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                        color = themeInfoCard(),
                        shape = AppInnerShape,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrivacyText(
                                if (detailExpanded) "收起待处理明细" else "查看 ${pending.size} 项待处理明细",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = themePrimary(),
                                modifier = Modifier.weight(1f),
                            )
                            PrivacyText(
                                if (detailExpanded) "▴" else "▾",
                                style = MaterialTheme.typography.bodyMedium,
                                color = themePrimary(),
                            )
                        }
                    }
                    if (detailExpanded) {
                        pending.forEachIndexed { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                modifier = Modifier.padding(top = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )
                            }
                            val dotColor = when (item.state) {
                                AuditState.PASS -> themeSuccess()
                                AuditState.FAIL -> themeWarning()
                                AuditState.MANUAL -> themeOnAmber()
                            }
                            Row(
                                Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth()
                                    .clickable {
                                        if (item.key == "notification") onRequestNotificationPermission()
                                        else PermissionAudit.openSettings(context, item.key)
                                        recheck(false)
                                    }
                                    .padding(vertical = 11.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    Modifier
                                        .padding(top = 5.dp)
                                        .size(8.dp)
                                        .background(dotColor, CircleShape),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    PrivacyText(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    PrivacyText(item.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PrivacyText(
                                        item.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        // 处置指引多为 40~50 字，labelSmall 在 320dp 宽度下每行约 20 字，
                                        // 限 2 行会截断后半句（如「授权后返回本页会自动重新检测」），故放到 3 行。
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    when (item.state) {
                                        AuditState.PASS -> PreviewBadge("正常", PreviewBadgeTone.GREEN)
                                        AuditState.FAIL -> PreviewBadge("待处理", PreviewBadgeTone.AMBER)
                                        AuditState.MANUAL -> PreviewBadge("需确认", PreviewBadgeTone.AMBER)
                                    }
                                    // 自启动没有系统查询接口，只能由用户在现场核对后手动确认；
                                    // 确认结果写入本机设置，进入本页时直接读回。
                                    if (item.key == "autostart") {
                                        Surface(
                                            onClick = {
                                                autostartConfirmedAt = PermissionAudit.confirmAutostart(context)
                                            },
                                            color = themePrimarySoft(),
                                            shape = RoundedCornerShape(999.dp),
                                        ) {
                                            PrivacyText(
                                                "确认正常",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = themePrimary(),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 压掉 TextButton 的垂直内边距与 48dp 最小触摸下限（的
                    // LocalMinimumInteractiveComponentSize 姿势），行的上下空白改由
                    // 外层 top 16dp 与卡片底部留白（dp）控制。
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        TextButton(
                            onClick = { recheck(true) },
                            modifier = Modifier.height(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        ) { PrivacyText("重新检测") }
                    }
                    PrivacyText("上次检测 $checkedAtText", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 已删：不再显示「自启动已于 … 确认为允许 · 重新确认」记录行。
                // 撤销确认的唯一入口就是「重新检测」——重新检测本身就会作废确认，
                // 两者功能重复，删掉这一行不损失任何能力。
            }
            }
        // 使用准备：四步，编号方块 + 标题 + 说明，第 2 步指向本页检查卡、不跳转。
        Box(Modifier.fillMaxWidth().onGloballyPositioned { jumpState?.sectionOffsets?.set(1, it.positionInParent().y.toInt()) }) {
            PreviewSectionHeader("使用准备", "按顺序完成四项")
        }
        val steps = listOf(
            Triple("01", "登录校园账号", "在首页填写学号与密码完成登录；可选择在本机记住账号，便于后续一键切换。") to "首页",
            Triple("02", "完成运行环境检查", "逐项处理上方检查卡中标记为「待处理」与「需确认」的项目，全部通过后后台提醒才可靠。") to "",
            Triple("03", "配置通知渠道", "在「通知与外观」中选择本机通知、微信 PushPlus 或邮箱，并发送测试通知确认可达。") to "通知",
            Triple("04", "启动签到监控", "在首页开启自动签到；轮询间隔与自动处理时机可在「设置 → 自动签到策略」中调整。") to "策略",
        )
        steps.forEach { (step, target) ->
            PreviewCard(onClick = if (target.isBlank()) null else ({ navigate(target) })) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = themePrimary(), shape = RoundedCornerShape(10.dp)) {
                        PrivacyText(step.first, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        PrivacyText(step.second, fontWeight = FontWeight.Bold)
                        PrivacyText(step.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (target.isNotBlank()) {
                            PrivacyText("前往 ›", style = MaterialTheme.typography.labelMedium, color = themePrimary(), modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }
        // 功能说明：按使用场景的静态说明，整卡不可点、无跳转箭头。
        Box(Modifier.fillMaxWidth().onGloballyPositioned { jumpState?.sectionOffsets?.set(2, it.positionInParent().y.toInt()) }) {
            PreviewSectionHeader("功能说明", "按使用场景")
        }
        val features = listOf(
            Triple(R.drawable.ic_nav_grid, "课表", "提供周课表与日程两种视图，可用右下角缩放条调整密度与字号；「调课」仅修改本机显示，可随时还原。"),
            Triple(R.drawable.ic_nav_bell, "签到与自动处理", "按设定间隔轮询签到事件，数字签到、雷达签到等类型按策略提醒或自动处理；开启自动监控后仅在课程时段内运行，其余时间待机，签到记录在「签到」页查看。自动处理未能完成、或名单里无法确认本人状态时，会弹出需手动确认的提醒（同一场签到只提醒一次）。"),
            Triple(R.drawable.ic_nav_location, "课程坐标", "维护雷达签到的课程地点，可从校区地点中选择，也可地图选点或教室现场采点。"),
            Triple(R.drawable.ic_nav_scores, "成绩与专业排名", "查询各学期成绩与绩点，支持模拟成绩预估与分享成绩长图；专业排名与绩点证明由成绩页进入。"),
            Triple(R.drawable.ic_nav_folder, "课程课件", "按课程浏览与批量下载课件，统一保存到手机「下载 → xmu助手课件」文件夹，无需额外授权；平台未提供下载地址的资源仅保存入口。下载区的「检查」会扫描本机已下载的文件并补标为已下载；标记为已下载、本地却找不到文件的会显示「？已下载」，「取消」停止尚未开始的下载并清理未完成的残片。"),
            Triple(R.drawable.ic_nav_calendar, "考试安排与提醒", "查看考试时间与考场，可手动添加考试；可设置提前提醒分钟数与全屏提醒，准点触发依赖精确闹钟权限。"),
            Triple(R.drawable.ic_nav_home, "桌面小组件", "今日课程小卡片与周课表小部件均在「通知与外观」页添加，添加后随课表数据自动刷新。"),
            Triple(R.drawable.ic_nav_settings, "隐私模式", "在「通知与外观」开启后，页面文字随机替换为无意义内容以防旁人窥屏；桌面小组件不受影响。"),
        )
        features.forEach { (icon, title, description) ->
            PreviewCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PreviewIconBox(icon)
                    Column(Modifier.weight(1f)) {
                        PrivacyText(title, fontWeight = FontWeight.Bold)
                        PrivacyText(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 常见问题：点击展开/收起，同一时间只展开一项，默认全部收起。
        var expandedFaq by rememberSaveable { mutableStateOf("") }
        val faqs = listOf(
            Triple(R.drawable.ic_nav_grid, "课程或签到数据为空", "请先确认已登录且账号状态正常。校园网或 VPN 异常会导致数据请求失败；退出后重新登录并刷新可排除会话过期。"),
            Triple(R.drawable.ic_nav_bell, "锁屏后收不到提醒", "系统会限制后台应用。请在本页检查卡中完成通知、电池优化、自启动与后台运行四项授权，并确认自动监控的时段设置正确。"),
            Triple(R.drawable.ic_nav_bell, "邮件或微信通知未送达", "请核对 SMTP 授权码、收件地址与端口，或 PushPlus Token；核对后发送测试通知验证。"),
            Triple(R.drawable.ic_nav_folder, "课件无法下载", "平台未提供下载地址时，应用不会绕过平台限制，仅保存入口并说明原因；请到平台端确认资源是否开放下载。"),
            Triple(R.drawable.ic_nav_location, "地图选点无法加载", "地图底图通过网络加载，空白时检查网络后重试；现场采点需要定位权限。"),
            Triple(R.drawable.ic_nav_calendar, "考试提醒不准时", "请确认已授予精确闹钟权限，且系统未对本应用启用省电限制；偏差较大时可在「考试提醒」页调整提前分钟数。"),
        )
        PreviewCard(modifier = Modifier.onGloballyPositioned {
            jumpState?.sectionOffsets?.set(3, it.positionInParent().y.toInt())
        }) {
            PreviewSectionHeader("常见问题", "${faqs.size} 项 · 点击展开")
            faqs.forEach { (icon, question, answer) ->
                val expanded = expandedFaq == question
                PreviewListRow(
                    icon = icon,
                    title = question,
                    subtitle = if (expanded) "点击收起" else "点击查看处理方法",
                    onClick = { expandedFaq = if (expanded) "" else question },
                    trailing = {
                        PrivacyText(
                            if (expanded) "▾" else "›",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                if (expanded) {
                    PrivacyText(
                        answer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 46.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun NotificationSettingsPreviewPage(
    settings: AssistantSettings,
    current: NotificationSettings,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    // 隐私模式 —— 顶部大标题逐字换字母；「签到」「成绩」字样换成「举手」「游戏点」，样式不变。
    privacyMode: Boolean = false,
    onPrivacyModeChanged: (Boolean) -> Unit = {},
    onTest: (NotificationSettings) -> Unit,
    // 桌面小组件从「策略」搬来，与本页的「外观」同属显示类设置。
    // 注意 onSaved 必须保持最后一个参数：调用方用尾随 lambda 传它。
    onAddWidget: () -> Unit,
    // 周课表小部件与今日课程分开添加，默认空实现兼容旧调用点。
    onAddWeekWidget: () -> Unit = {},
    onSaved: (NotificationSettings) -> Unit,
) {
    var systemEnabled by rememberSaveable { mutableStateOf(current.systemEnabled) }
    var pushEnabled by rememberSaveable { mutableStateOf(current.pushPlusEnabled) }
    var pushToken by rememberSaveable { mutableStateOf(current.pushPlusToken) }
    var qqEnabled by rememberSaveable { mutableStateOf(current.qqMailEnabled) }
    var qqSender by rememberSaveable { mutableStateOf(current.qqMailSender) }
    var qqPassword by rememberSaveable { mutableStateOf(current.qqMailPassword) }
    var qqRecipient by rememberSaveable { mutableStateOf(current.qqMailRecipient) }
    var qqPorts by rememberSaveable { mutableStateOf(current.qqMailPorts.ifBlank { "465,587" }) }
    var qqHost by rememberSaveable { mutableStateOf(current.qqMailHost) }
    // 故障转移兜底开关（不属于 NotificationSettings，直接读写设置，改完立即生效）。
    var failoverFullScreen by rememberSaveable { mutableStateOf(settings.rollcallFailoverFullScreen) }
    val invalidPorts = !smtpPortsValid(qqPorts)
    fun snapshot() = NotificationSettings(
        systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient,
        qqPorts.ifBlank { "465,587" }, qqHost.trim(),
    )

    // 通知设置改为自动保存，删除底部「保存通知设置」按钮。
    // 字段变化后 250ms 防抖落盘；离开页面时用最新快照兜底保存一次，
    // 避免「改完立刻返回」时协程被取消导致丢改动；首次进入不重复写盘。
    var autoSaveInitialized by rememberSaveable { mutableStateOf(false) }
    val latestSnapshot by rememberUpdatedState(if (invalidPorts) null else snapshot())
    LaunchedEffect(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts, qqHost) {
        if (!autoSaveInitialized) {
            autoSaveInitialized = true
            return@LaunchedEffect
        }
        delay(250)
        if (invalidPorts) return@LaunchedEffect
        val saved = snapshot()
        withContext(Dispatchers.IO) { settings.saveNotifications(saved) }
        onSaved(saved)
    }
    DisposableEffect(Unit) {
        onDispose {
            val saved = latestSnapshot ?: return@onDispose
            settings.saveNotifications(saved)
            onSaved(saved)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // 补小标题——「通知」与下方「外观」「桌面小组件」同级。
        PreviewSectionHeader("通知")
        PreviewCard {
            ChannelHeader(R.drawable.ic_nav_bell, "本机通知", "签到、监控异常与考试提醒", systemEnabled) { systemEnabled = it }
            PreviewBadge("系统权限正常", PreviewBadgeTone.GREEN)
        }
        PreviewCard {
            // 故障转移兜底：自动流程放弃（重试耗尽 / 本人状态无法判定）时要求人工确认。
            // 锁屏全屏需要系统「全屏通知」授权，未授权时退化为高优先级横幅，提醒不丢。
            SettingSwitch(
                title = "自动失败时全屏提醒",
                subtitle = "自动签到未能完成，或名单里无法确认本人状态时，弹出提醒要求手动确认并签到；同一场签到只提醒一次。",
                checked = failoverFullScreen,
                onChecked = {
                    failoverFullScreen = it
                    settings.rollcallFailoverFullScreen = it
                },
            )
        }
        PreviewCard {
            ChannelHeader(R.drawable.ic_nav_check, "微信 PushPlus", "把签到状态推送到微信", pushEnabled) { pushEnabled = it }
            PreviewBadge("服务需约 3.5 元实名费用", PreviewBadgeTone.AMBER)
            if (pushEnabled) {
                OutlinedTextField(pushToken, { pushToken = it }, label = { PrivacyText("PushPlus Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onTest(snapshot()) }, modifier = Modifier.fillMaxWidth()) { PrivacyText("发送测试通知") }
            }
        }
        PreviewCard {
            ChannelHeader(R.drawable.ic_nav_folder, "邮箱", "免费 · 任意邮箱 · SMTP 授权码", qqEnabled) { qqEnabled = it }
            if (qqEnabled) {
                OutlinedTextField(qqSender, { qqSender = it }, label = { PrivacyText("发件邮箱") }, placeholder = { PrivacyText("支持 QQ/163/Gmail 等任意邮箱") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(qqPassword, { qqPassword = it }, label = { PrivacyText("SMTP 授权码") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(qqRecipient, { qqRecipient = it }, label = { PrivacyText("收件邮箱") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    qqHost,
                    { qqHost = it },
                    label = { PrivacyText("SMTP 服务器（可选）") },
                    placeholder = { PrivacyText("留空按发件邮箱自动推断，QQ 默认 smtp.qq.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    qqPorts,
                    { qqPorts = it.filter { ch -> ch.isDigit() || ch == ',' } },
                    label = { PrivacyText("SMTP 端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalidPorts,
                )
                OutlinedButton(onClick = { if (!invalidPorts) onTest(snapshot()) }, enabled = !invalidPorts, modifier = Modifier.fillMaxWidth()) { PrivacyText("发送测试通知") }
            }
        }
        PreviewSectionHeader("外观")
        PreviewCard {
            SegmentedOptions(
                options = listOf(THEME_MODE_SYSTEM to "跟随系统", THEME_MODE_LIGHT to "浅色", THEME_MODE_DARK to "深色"),
                selected = themeMode,
                onSelected = onThemeModeChanged,
            )
        }
        PreviewCard {
            // 隐私模式。开启后各页顶部大标题逐字换随机字母（个数等于原字数），
            // 敏感词按 PRIVACY_SUBSTITUTIONS 换词，字体与配色保持不变。
            // 说明文案按需求原样保留关键词，故本行屏蔽替换，否则「考」「课」「成绩」「签到」
            // 会被自身规则改写、看不懂在讲什么。行尾问号图标点开全量替换规则。
            var privacyRulesVisible by rememberSaveable { mutableStateOf(false) }
            androidx.compose.runtime.CompositionLocalProvider(LocalPrivacyMode provides false) {
                SettingSwitch(
                    title = "隐私模式",
                    subtitle = "将顶部大标题替换为乱码；将敏感词“考”“课”“成绩”“签到”替换为其他词。",
                    checked = privacyMode,
                    action = {
                        Surface(
                            onClick = { privacyRulesVisible = true },
                            shape = CircleShape,
                            color = themePrimarySoft(),
                            modifier = Modifier.padding(end = 10.dp).size(20.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                PrivacyText(
                                    "?",
                                    color = themePrimary(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    },
                    onChecked = onPrivacyModeChanged,
                )
                if (privacyRulesVisible) {
                    PrivacyRulesDialog(settings) { privacyRulesVisible = false }
                }
            }
        }
        // 从策略页搬入。只搬「小组件」相关，当前周次仍留在策略页。
        // 删除「桌面小卡片总开关」，两张卡片改由系统桌面直接添加。
        PreviewSectionHeader("桌面小组件")
        PreviewCard {
            PreviewListRow(
                icon = R.drawable.ic_nav_calendar,
                title = "今日课程小卡片",
                subtitle = "桌面显示当天课程与教室",
                trailing = {},
            )
            OutlinedButton(onClick = onAddWidget, modifier = Modifier.fillMaxWidth()) { PrivacyText("添加到桌面") }
        }
        PreviewCard {
            // 周课表小部件（整周表格，独立绘制）。
            PreviewListRow(
                icon = R.drawable.ic_nav_grid,
                title = "周课表小部件",
                subtitle = "桌面显示整周课程表",
                trailing = {},
            )
            OutlinedButton(onClick = onAddWeekWidget, modifier = Modifier.fillMaxWidth()) { PrivacyText("添加周课表小部件") }
        }
    }
}

@Composable
private fun ChannelHeader(icon: Int, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PreviewIconBox(icon)
        Column(Modifier.weight(1f)) {
            PrivacyText(title, fontWeight = FontWeight.Bold)
            PrivacyText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}

/**
 *原「设置」页「自动签到策略」分组的两张卡片（轮询间隔 + 数字/雷达签到、
 * 自动处理时机）合并为独立三级界面。状态与保存逻辑原样搬迁：改动后
 * 点「保存设置更改」才落盘，否则保持原值。
 */
@Composable
internal fun AutoSignInStrategyPage(
    settings: AssistantSettings,
    current: RollcallSettings,
    onSaved: (RollcallSettings) -> Unit,
) {
    var interval by rememberSaveable { mutableStateOf(current.pollIntervalSeconds.toString()) }
    var number by rememberSaveable { mutableStateOf(current.autoAnswerNumber) }
    var radar by rememberSaveable { mutableStateOf(current.autoAnswerRadar) }
    var waitMode by rememberSaveable { mutableStateOf(current.waitBeforeAnswerMode) }
    // 人数/比例必须可编辑：此前只保存模式，阈值一直被 current 覆盖，改了也不生效。
    var waitCount by rememberSaveable { mutableStateOf(current.waitBeforeAnswerCount.toString()) }
    var waitPercent by rememberSaveable { mutableStateOf(current.waitBeforeAnswerPercent.toString()) }
    val countValue = waitCount.toIntOrNull()
    val percentValue = waitPercent.toIntOrNull()
    val countInvalid = waitMode == WAIT_BEFORE_ANSWER_COUNT && (countValue == null || countValue < 1)
    val percentInvalid = waitMode == WAIT_BEFORE_ANSWER_PERCENT && (percentValue == null || percentValue !in 1..100)

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewSectionHeader("自动签到策略", "已保存")
        PreviewCard {
            OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { PrivacyText("轮询间隔（秒）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SettingSwitch("数字签到", "自动处理", number) { number = it }
            SettingSwitch("雷达签到", "使用课程坐标自动处理", radar) { radar = it }
        }
        PreviewCard {
            PrivacyText("自动处理时机", fontWeight = FontWeight.Bold)
            SegmentedOptions(
                options = listOf(WAIT_BEFORE_ANSWER_NONE to "立即", WAIT_BEFORE_ANSWER_COUNT to "达到人数", WAIT_BEFORE_ANSWER_PERCENT to "达到比例"),
                selected = waitMode,
                onSelected = { waitMode = it },
            )
            if (waitMode == WAIT_BEFORE_ANSWER_COUNT) {
                OutlinedTextField(
                    waitCount,
                    { waitCount = it.filter(Char::isDigit) },
                    label = { PrivacyText("达到人数（至少 1 人）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = countInvalid,
                    supportingText = { if (countInvalid) PrivacyText("请输入至少 1 的整数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (waitMode == WAIT_BEFORE_ANSWER_PERCENT) {
                OutlinedTextField(
                    waitPercent,
                    { waitPercent = it.filter(Char::isDigit) },
                    label = { PrivacyText("达到比例（1-100%）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = percentInvalid,
                    supportingText = { if (percentInvalid) PrivacyText("请输入 1-100 的整数比例") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Button(
                onClick = {
                    if (countInvalid || percentInvalid) return@Button
                    val next = current.copy(
                        pollIntervalSeconds = (interval.toIntOrNull() ?: 30).coerceIn(1, 300),
                        autoAnswerNumber = number,
                        autoAnswerRadar = radar,
                        waitBeforeAnswerMode = waitMode,
                        waitBeforeAnswerCount = (countValue ?: current.waitBeforeAnswerCount).coerceAtLeast(1),
                        waitBeforeAnswerPercent = (percentValue ?: current.waitBeforeAnswerPercent).coerceIn(1, 100),
                    )
                    settings.saveRollcall(next)
                    onSaved(next)
                },
                enabled = !countInvalid && !percentInvalid,
                modifier = Modifier.fillMaxWidth(),
            ) { PrivacyText("保存设置更改") }
        }
    }
}

@Composable
internal fun StrategyPreviewPage(
    settings: AssistantSettings,
    termCode: String,
    onSaved: (RollcallSettings) -> Unit,
    examReminder: ExamReminderSettings,
    onExamReminderChanged: (ExamReminderSettings) -> Unit,
    onOpenExamAlarmSettings: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenExamReminderTime: () -> Unit,
    onOpenManualWeek: () -> Unit,
    // 课程坐标收进设置页，作为三级界面。
    onOpenCourseCoordinates: () -> Unit = {},
    // 自动签到策略收进设置页，作为三级界面（原两张卡片合并）。
    onOpenAutoSignInStrategy: () -> Unit = {},
) {
    var reminderEnabled by rememberSaveable { mutableStateOf(examReminder.enabled) }
    var fullScreen by rememberSaveable { mutableStateOf(examReminder.fullScreenEnabled) }
    val week by rememberSaveable(termCode) { mutableStateOf(settings.manualAcademicWeek(termCode)) }
    // 监控策略：服务内时段自查的开关（课表为空时无法判断，会退化成全程检查）。
    var autoMonitor by rememberSaveable { mutableStateOf(settings.autoMonitorWindow) }
    // 把「时段限制到底生不生效」直接显示出来——旧版课表读错数据源，用户看到开关开着却全天轮询。
    val context = LocalContext.current
    var scheduleEntryCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        scheduleEntryCount = withContext(Dispatchers.IO) {
            loadScheduleSnapshotFromFile(context).entries.size
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewSectionHeader("监控策略", "已保存")
        PreviewCard {
            SettingSwitch(
                "自动监控",
                "只在有课的前 30 分钟至结束后 30 分钟内检查签到，其余时段待机",
                autoMonitor,
            ) { enabled ->
                autoMonitor = enabled
                settings.autoMonitorWindow = enabled
            }
            PrivacyText(
                if (!autoMonitor) {
                    "已关闭时段限制：监控运行期间全程检查签到。"
                } else when {
                    scheduleEntryCount == null -> "正在读取课表…"
                    scheduleEntryCount == 0 -> "还没读到课表，时段限制暂时无法生效——请先到「课表」页刷新一次。"
                    else -> "课表已读取（${scheduleEntryCount} 节）：当天没课或课间会自动待机，不发请求。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 原「自动签到策略」的两张卡片已合并进三级界面，二级页只留入口；
        // 按需求，本入口与「课程坐标」同归「签到设置」分组。
        PreviewSectionHeader("签到设置")
        PreviewCard(onClick = onOpenAutoSignInStrategy) {
            PreviewListRow(
                icon = R.drawable.ic_nav_check,
                title = "自动签到策略",
                subtitle = "轮询间隔 · 数字/雷达签到 · 自动处理时机",
            )
        }
        PreviewCard(onClick = onOpenCourseCoordinates) {
            PreviewListRow(
                icon = R.drawable.ic_nav_location,
                title = "课程坐标",
                subtitle = "维护雷达签到的课程地点",
            )
        }
        PreviewSectionHeader("考试提醒")
        PreviewCard {
            // 编辑按钮在左侧紧跟「考试提醒」标题，开关靠右
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                PrivacyText("考试提醒", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                SmallEditButton(onOpenExamReminderTime)
                Spacer(Modifier.weight(1f))
                Switch(reminderEnabled, onCheckedChange = {
                    reminderEnabled = it
                    onExamReminderChanged(examReminder.copy(enabled = it))
                })
            }
            PrivacyText(
                if (reminderEnabled) examReminderLabel(examReminder.advanceMinutes) else "已关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitch("全屏提醒", "锁屏时显示横幅", fullScreen) {
                fullScreen = it
                onExamReminderChanged(examReminder.copy(enabled = reminderEnabled, fullScreenEnabled = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenExamAlarmSettings, modifier = Modifier.weight(1f)) { PrivacyText("闹钟权限") }
                OutlinedButton(onClick = onOpenFullScreenSettings, modifier = Modifier.weight(1f)) { PrivacyText("全屏权限") }
            }
        }
        // 分组改名「课表」；小组件部分已搬到「通知与外观」，此处只留当前周次。
        PreviewSectionHeader("课表")
        PreviewCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PrivacyText("当前周次", fontWeight = FontWeight.Bold)
                    PrivacyText(if (week > 0) "第 $week 周 · 手动校准" else "自动判断", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onOpenManualWeek) { PrivacyText("修改") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    action: (@Composable () -> Unit)? = null,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PrivacyText(title, fontWeight = FontWeight.Bold)
            PrivacyText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) action()
        Switch(checked, onChecked)
    }
}

/**
 *隐私模式替换规则弹窗。逐条可开关、可改目标词，左下角恢复默认。
 *
 * 内容需原样可读，因此整段屏蔽替换（否则规则里的「签到」「成绩」「考」「课」
 * 会被自己的规则改写，连改哪个词都看不出来）。
 */
@Composable
private fun PrivacyRulesDialog(settings: AssistantSettings, onDismiss: () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPrivacyMode provides false) {
        val rules = PrivacyModeState.rules
        val titleMasked = PrivacyModeState.titleMasked
        fun apply(next: List<PrivacyRule>) {
            PrivacyModeState.updateRules(next)
            settings.privacyRules = encodePrivacyRules(next)
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { PrivacyText("隐私模式替换规则") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 标题替换也做成开关。它不属于换词表（标题是逐字换字母、
                    // 没有「目标词」），因此单独一行放在词表之前。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            PrivacyText(
                                "顶部大标题替换为随机字母",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            PrivacyText(
                                "只影响二三级页标题；关闭后标题原样显示。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = titleMasked,
                            onCheckedChange = { on ->
                                PrivacyModeState.updateTitleMasked(on)
                                settings.privacyTitleMasked = on
                            },
                            modifier = Modifier.scale(0.85f),
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
                    )
                    PrivacyText(
                        "开关决定该词是否替换，中间可改目标词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    rules.forEach { rule ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrivacyText(
                                rule.from,
                                modifier = Modifier.width(40.dp),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            // 箭头改成「替换为」，比符号更直白。
                            PrivacyText(
                                "替换为",
                                modifier = Modifier.width(46.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 输入框原来 56dp 高、占满整行，视觉过重。
                            // 高度压 45%（31dp）、宽度压 25%（75%），两者都保持居中。
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .height(31.dp)
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline,
                                            shape = RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    BasicTextField(
                                        value = rule.to,
                                        onValueChange = { typed ->
                                            apply(
                                                rules.map {
                                                    if (it.from == rule.from) it.copy(to = typed) else it
                                                },
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                    )
                                }
                            }
                            // 开关整体缩到 85%（-15%），与缩小的输入框同比例。
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { on ->
                                    apply(
                                        rules.map {
                                            if (it.from == rule.from) it.copy(enabled = on) else it
                                        },
                                    )
                                },
                                modifier = Modifier.scale(0.85f),
                            )
                        }
                    }
                    PrivacyText(
                        "桌面小组件不参与替换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        PrivacyModeState.resetRules()
                        settings.privacyRules = encodePrivacyRules(DEFAULT_PRIVACY_RULES)
                        // 恢复默认连标题替换开关一起复位（resetRules 已置 true）。
                        settings.privacyTitleMasked = true
                    },
                ) { PrivacyText("恢复默认") }
            },
            confirmButton = { TextButton(onClick = onDismiss) { PrivacyText("完成") } },
        )
    }
}

@Composable
private fun SegmentedOptions(options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            Surface(
                onClick = { onSelected(value) },
                // 去描边，选中态改用主色浅底区分（仍是同一色系，不改业务配色）。
                color = if (selected == value) themePrimarySoft() else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                PrivacyText(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp), color = if (selected == value) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 *课程分组标题用「学年 · 学期」而不是只看 term。
 * CoursewareClient 把 academic_year 放进 term（学年），真正的学期在 semesterCode；
 *之前 groupBy { it.term } 会把同一学年两个学期混成一组。
 */
private fun courseTermGroupLabel(course: CourseSummary): String {
    val year = courseYear(course.term) ?: course.term.trim().ifBlank { "未标注学年" }
    val semester = courseSemesterLabel(course.semesterCode, course.term)
    return if (semester == "全部") year else "$year · $semester"
}

@Composable
internal fun CoursewarePreviewPage(
    courses: List<CourseSummary>,
    selectedCourseIds: Set<String>,
    coursewareItems: List<CoursewareUiItem>,
    selectedIds: Set<String>,
    onRefreshCourses: () -> Unit,
    onCoursesSelected: (Set<String>) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onRefreshCourseware: () -> Unit,
    onOpenPlatform: () -> Unit,
    downloadProgress: String,
    coursesLoading: Boolean,
    coursesRefreshError: String,
    coursewareLoading: Boolean,
    coursewareRefreshError: String,
    loggedIn: Boolean,
    coursewareCounts: Map<String, Int> = emptyMap(),
    itemsForCourse: (String) -> List<CoursewareUiItem> = { emptyList() },
    prefetchRemaining: Int = 0,
    // 常驻下载进度区所需的状态与「打开目录」回调；给默认值兼容旧调用点/单测。
    downloadUiState: CoursewareDownloadUiState = CoursewareDownloadUiState(),
    downloadDirectoryPath: String = "",
    onOpenDownloadDirectory: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    /**下载卡「检查」按钮：核对本地已落盘文件并补标记。 */
    onCheckLocalFiles: () -> Unit = {},
    /**下载卡「取消」按钮：停止尚未开始的下载并清理 .part。 */
    onCancelDownload: () -> Unit = {},
    /**「检查」扫出来「标记为已下载、但本地找不到文件」的课件 id，角标据此换成「？已下载」。 */
    missingLocalIds: Set<String> = emptySet(),
    /**提示条出现时下载键临时上让（内容区底边就是底栏上沿，不需要长期留 146dp）。 */
    toastVisible: Boolean = false,
) {
    val context = LocalContext.current
    // 已下载标记每次重组从落盘记录读取（下载状态变化会触发重组，及时刷新）。
    val downloadedIds = CoursewareStorage.downloadedIds(context)
    // 课件统一写公共「下载/xmu助手课件」，无需选目录、无需授权，
    // 因此不再有「选择保存目录」的引导弹窗与授权状态机。
    val startDownload = {
        // 没勾课件时交给状态机提示「请先勾选课件」。
        onDownload()
    }
    var query by rememberSaveable { mutableStateOf("") }
    // 折叠状态只在本页生命周期内保留，默认全部展开（按学期一屏可见）。
    var collapsedTerms by remember { mutableStateOf(setOf<String>()) }
    // 课程行就地展开：右侧箭头展开/收起该课程的课件详情，与左侧勾选互不干扰。
    var expandedCourseIds by remember { mutableStateOf(setOf<String>()) }
    val filtered = courses.filter {
        query.isBlank() || it.displayName.contains(query, ignoreCase = true) ||
            it.term.contains(query, ignoreCase = true) ||
            courseTermGroupLabel(it).contains(query, ignoreCase = true)
    }
    val grouped = filtered.groupBy(::courseTermGroupLabel)
        .toList()
        .sortedByDescending { (_, list) -> list.maxOfOrNull { it.semesterCode }.orEmpty() }
    val counts = coursewareCounts(coursewareItems)
    // 下载不再锁定界面 —— 下载中仍可继续勾选并再点一次追加一批，
    // FAB 只有「一个都没选」时才置灰。
    val canDownload = selectedIds.isNotEmpty()

    // 进度区常驻页面顶部，与下方列表之间留 8dp，避免两张卡阴影相接看起来叠在一起。
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DownloadProgressPanel(
            state = downloadUiState,
            directoryPath = downloadDirectoryPath,
            // 课件统一落在公共「下载/xmu助手课件」，这里直接打开该目录。
            onOpenDirectory = { onOpenDownloadDirectory() },
            onRetryFailed = onRetryFailed,
            directoryActionLabel = "打开下载目录",
            onCheckLocalFiles = onCheckLocalFiles,
            onCancelDownload = onCancelDownload,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 底部留白，避免最后几项被悬浮下载键挡住。
            contentPadding = PaddingValues(bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        item {
            // 安卓窄屏适配：卡片内边距与控件尺寸整体收紧，列表替代横向 chips。
            PreviewCard(contentPadding = 11.dp, spacing = 7.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PrivacyText("选择课程", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        PrivacyText(
                            "按学期分组 · 可多选课程后合并下载课件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = onRefreshCourses,
                        enabled = !coursesLoading,
                        modifier = Modifier.heightIn(min = 34.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                    ) { PrivacyText(if (coursesLoading) "刷新中" else "刷新", style = MaterialTheme.typography.labelMedium) }
                }
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { PrivacyText("搜索课程或学期") },
                    placeholder = { PrivacyText("课程名称或学期") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                RefreshStateBanner(coursesLoading, coursesRefreshError, courses.isNotEmpty(), onRetry = onRefreshCourses)
                if (filtered.isEmpty()) {
                    PrivacyText(if (!loggedIn) "登录后可读取课程" else "没有匹配课程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PrivacyText(
                            "已选 ${selectedCourseIds.size} 门课程",
                            style = MaterialTheme.typography.labelMedium,
                            color = themePrimary(),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { onCoursesSelected(emptySet()) },
                            enabled = selectedCourseIds.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) { PrivacyText("清空", style = MaterialTheme.typography.labelSmall) }
                    }
                    grouped.forEach { (term, termCourses) ->
                        val expanded = term !in collapsedTerms
                        val allSelected = termCourses.all { it.id in selectedCourseIds }
                        Surface(
                            onClick = { collapsedTerms = if (expanded) collapsedTerms + term else collapsedTerms - term },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PrivacyText(if (expanded) "▾" else "▸", color = themePrimary())
                                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                    PrivacyText(term, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    PrivacyText("${termCourses.size} 门课程", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(
                                    onClick = {
                                        val ids = termCourses.map { it.id }.toSet()
                                        onCoursesSelected(if (allSelected) selectedCourseIds - ids else selectedCourseIds + ids)
                                    },
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(7.dp),
                                ) {
                                    PrivacyText(
                                        if (allSelected) "取消" else "全选",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = themePrimary(),
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        if (expanded) {
                            termCourses.forEach { course ->
                                val courseItems = itemsForCourse(course.id)
                                val pickedInCourse = courseItems.count { it.id in selectedIds }
                                val courseToggleState = when {
                                    courseItems.isNotEmpty() && pickedInCourse == courseItems.size -> ToggleableState.On
                                    pickedInCourse > 0 -> ToggleableState.Indeterminate
                                    else -> ToggleableState.Off
                                }
                                val checked = course.id in selectedCourseIds
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onCoursesSelected(
                                                if (checked) selectedCourseIds - course.id else selectedCourseIds + course.id,
                                            )
                                        }
                                        .padding(vertical = 2.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 部分选中显示半选填充态，与整科全选明确区分。
                                    TriStateCheckbox(
                                        state = courseToggleState,
                                        onClick = {
                                            when (courseToggleState) {
                                                ToggleableState.On -> onCoursesSelected(selectedCourseIds - course.id)
                                                ToggleableState.Off -> onCoursesSelected(selectedCourseIds + course.id)
                                                // 半选 → 把本科尚未勾选的课件一次补齐（不取消已选）
                                                ToggleableState.Indeterminate -> courseItems.forEach { item ->
                                                    if (item.id !in selectedIds) onToggle(item.id)
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(30.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                    PrivacyText(
                                        course.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    PrivacyText(
                                        courseTermGroupLabel(course),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // 课程行状态角标。优先级 有新增课件 > 已全部下载 > 条数/抓取中/未抓取。
                                // "有新增课件"覆盖"已全部下载"，直到这批新课件全部下载完才退回。
                                // 两个「有内容」态角标都带条数，与“只自动勾选新增”的规则对应。
                                val count = coursewareCounts[course.id]
                                val pendingNew = CoursewareStorage.pendingNewIds(context, course.id)
                                val allDownloaded = courseItems.isNotEmpty() && courseItems.all { it.id in downloadedIds }
                                // 课程内只要有一条「标记已下载但本地扫不到」，角标就不能算完成：
                                // 换成淡橙底 + 暗橙字，提示这批里有文件对不上。
                                val hasMissingLocal = courseItems.any { it.id in missingLocalIds }
                                when {
                                    pendingNew.isNotEmpty() -> PreviewBadge("有新增课件·${pendingNew.size}条", PreviewBadgeTone.AMBER)
                                    allDownloaded && hasMissingLocal ->
                                        PreviewBadge("已全部下载·${courseItems.size}条", PreviewBadgeTone.ORANGE)
                                    allDownloaded -> PreviewBadge("已全部下载·${courseItems.size}条", PreviewBadgeTone.GREEN)
                                    count != null -> PreviewBadge("$count 条")
                                    prefetchRemaining > 0 -> PreviewBadge("抓取中…")
                                    else -> PreviewBadge("未抓取")
                                }
                                PrivacyText(
                                    if (course.id in expandedCourseIds) "▾" else "▸",
                                    color = themePrimary(),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            expandedCourseIds = if (course.id in expandedCourseIds) {
                                                expandedCourseIds - course.id
                                            } else {
                                                expandedCourseIds + course.id
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                            if (course.id in expandedCourseIds) {
                                val courseItems = itemsForCourse(course.id)
                                if (courseItems.isEmpty()) {
                                    PrivacyText(
                                        "该课程暂无已抓到的课件；勾选后点“刷新”读取。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 32.dp, bottom = 4.dp),
                                    )
                                } else {
                                    // 展开的课件卡片之间补间距，否则多张卡紧贴像叠在一起。
                                    // 卡片之间加灰色细分隔线。两端按用户标注对齐：
                                    // 左端起于复选框左缘（= 卡片内容左缘，故内缩 12dp = 卡片内边距），
                                    // 右端止于「✓ 已下载」的右缘（= 内容右缘再左移 17dp，合计 29dp）。
                                    Column(
                                        Modifier.padding(start = 30.dp, bottom = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        courseItems.forEachIndexed { index, item ->
                                            if (index > 0) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(start = 12.dp, end = 29.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                                )
                                            }
                                            CoursewareItemCard(
                                                item,
                                                checked = item.id in selectedIds,
                                                onToggle = { onToggle(item.id) },
                                                downloaded = item.id in downloadedIds,
                                                missingLocal = item.id in missingLocalIds,
                                            )
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
        if (selectedCourseIds.isNotEmpty()) {
            item { PreviewSectionHeader("已选课程 · 课件", "${coursewareItems.size} 条") }
            item {
                PreviewCard(contentPadding = 11.dp, spacing = 7.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PreviewBadge("已选 ${selectedIds.size} 项")
                        PrivacyText(
                            "可直接下载 ${counts.direct} · 入口 ${counts.entry} · 受限 ${counts.limited}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (downloadProgress.isNotBlank()) PrivacyText(downloadProgress, color = themePrimary(), fontWeight = FontWeight.Bold)
                    RefreshStateBanner(coursewareLoading, coursewareRefreshError, coursewareItems.isNotEmpty(), onRetry = onRefreshCourseware)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onSelectAll,
                            modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                        ) { PrivacyText("全选", style = MaterialTheme.typography.labelMedium) }
                        OutlinedButton(
                            onClick = onRefreshCourseware,
                            enabled = !coursewareLoading,
                            modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                        ) { PrivacyText("刷新", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if (coursewareItems.isEmpty() && !coursewareLoading) {
                item { EmptyState("暂无课件", "勾选课程后点击刷新，读取平台提供的课程资料。") }
            } else {
                coursewareItems.groupBy { it.courseId }.forEach { (courseId, items) ->
                    item {
                        PrivacyText(
                            courses.firstOrNull { it.id == courseId }?.title ?: "课程 $courseId",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = themePrimary(),
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                        )
                    }
                    items(items) { item ->
                        CoursewareItemCard(item, checked = item.id in selectedIds, onToggle = { onToggle(item.id) })
                    }
                }
            }
            item { OutlinedButton(onClick = onOpenPlatform, modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp)) { PrivacyText("打开课程平台") } }
        } else {
            item { EmptyState("尚未选择课程", "按学期展开列表，勾选一门或多门课程。") }
        }
        }
        // 右下角悬浮下载键：显示已勾选课件数，任何滚动位置都能点。
        // 下载中不再置灰，文案提示「追加下载」，新勾选的课件可作为新一批立即开下。
        // FAB 与课表缩放条同一套底距规则 —— 平时贴右下角（12dp），
        // 提示条（整屏通栏覆盖层）出现时才临时上让到 60dp。
        val fabBottom by animateDpAsState(
            targetValue = if (toastVisible) 60.dp else 12.dp,
            label = "downloadFabBottom",
        )
        ExtendedFloatingActionButton(
            onClick = startDownload,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = fabBottom),
            containerColor = if (canDownload) FloatingActionButtonDefaults.containerColor
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            PrivacyText(
                if (downloadUiState.active) "↓ 追加下载（${selectedIds.size}）" else "↓ 下载（${selectedIds.size}）",
                fontWeight = FontWeight.Bold,
            )
        }
        }
    }
}

/**课件页常驻下载区 —— 进度条 + 计数 + 保存位置 + 打开/复制路径。
 *进度条用自绘 Box，避免不同 Material3 版本 LinearProgressIndicator 参数差异。 */
@Composable
private fun DownloadProgressPanel(
    state: CoursewareDownloadUiState,
    directoryPath: String,
    onOpenDirectory: () -> Unit,
    onRetryFailed: () -> Unit = {},
    directoryActionLabel: String = "打开下载目录",
    /**待下载态的「检查」：扫本地目录，把已存在的文件补标为已下载。 */
    onCheckLocalFiles: () -> Unit = {},
    /**下载中态的「取消」：停掉尚未开始的课件并清理 .part 残片。 */
    onCancelDownload: () -> Unit = {},
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    PreviewCard(contentPadding = 12.dp, spacing = 6.dp) {
        // 状态徽标与右侧动作按钮统一为 30dp 高、至少 64dp 宽、同一套 labelSmall 粗体，
        // 避免「待下载」小胶囊和「检查 / 取消」描边按钮看起来像两种尺寸的控件。
        val pillModifier = Modifier
            .height(30.dp)
            .widthIn(min = 64.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PrivacyText(
                "下载",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            when {
                state.active -> PreviewBadge("进行中 ${state.completed}/${state.total}", modifier = pillModifier)
                state.finished -> PreviewBadge("已完成", PreviewBadgeTone.GREEN, pillModifier)
                else -> PreviewBadge("待下载", modifier = pillModifier)
            }
            // 状态徽标右侧的动作位：空闲时可「检查」本地文件，下载中可「取消」。
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Surface(
                    onClick = if (state.active) onCancelDownload else onCheckLocalFiles,
                    modifier = pillModifier,
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    contentColor = themePrimary(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        PrivacyText(
                            if (state.active) "取消" else "检查",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (state.total > 0) {
            val fraction = (state.completed.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(themePrimary(), RoundedCornerShape(999.dp)),
                )
            }
            PrivacyText(
                "已完成 ${state.completed}/${state.total} · 文件 ${state.successFiles} · 入口 ${state.successEntries} · 失败 ${state.failed}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 下载失败时把具体原因显示出来，并给一键重试入口。
        if (state.failed > 0) {
            PrivacyText(
                "最近失败原因：${state.lastFailureReason.ifBlank { "未返回具体原因" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!state.active) {
                OutlinedButton(
                    onClick = onRetryFailed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                ) { PrivacyText("重试失败的课件", style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (directoryPath.isNotBlank()) {
            PrivacyText(
                "保存位置：$directoryPath",
                style = MaterialTheme.typography.labelSmall,
                color = themePrimary(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenDirectory,
                    modifier = Modifier.weight(1f).heightIn(min = 34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                ) { PrivacyText(directoryActionLabel, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(directoryPath)) },
                    modifier = Modifier.heightIn(min = 34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                ) { PrivacyText("复制路径", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
internal fun ExamPreviewPage(
    summary: XmuTermExamSummary?,
    validTerms: List<String>,
    selectedTerm: String,
    loading: Boolean,
    refreshError: String,
    loggedIn: Boolean,
    onSelectTerm: (String) -> Unit,
    onRefresh: () -> Unit,
    /**用户自定义的考试安排。 */
    customExams: List<CustomExam> = emptyList(),
    /**新增或更新一条自定义考试。 */
    onSaveCustom: (CustomExam) -> Unit = {},
    /**删除一条自定义考试。 */
    onDeleteCustom: (CustomExam) -> Unit = {},
    /**
     *本学期课表条目 —— 用于推导「节次 → 真实时间」，
     * 让「选择节次」里显示的是真实作息时间（无课表时用兜底作息）。
     */
    scheduleEntries: List<XmuScheduleEntry> = emptyList(),
) {
    val exams = summary?.takeIf { it.termCode == selectedTerm }?.exams.orEmpty()
    // 自定义考试独立成段，卡片样式与教务抓取项区分开。
    var editingCustom by remember { mutableStateOf<CustomExam?>(null) }
    var customDialogOpen by rememberSaveable { mutableStateOf(false) }
    // 节次时间窗 = 厦大官方作息时间表（教务处公布），并用本学期单节课程校正；
    // 1-11 节全部有值，不会出现空白格。
    val sectionWindows = remember(scheduleEntries) { resolveSectionWindows(scheduleEntries) }
    // 与官方表不一致的节次（本学期实测）→ 在面板里注明以课表为准
    val sectionTimesFromSchedule = remember(sectionWindows) {
        sectionWindows != officialSectionWindows()
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            PreviewCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PrivacyText(selectedTerm.ifBlank { "考试安排" }, fontWeight = FontWeight.Bold)
                        PrivacyText(
                            "${exams.size + customExams.size} 场考试 · 提醒可在设置页配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !loading) { PrivacyText(if (loading) "刷新中" else "刷新") }
                }
                if (validTerms.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        validTerms.forEach { term ->
                            Surface(onClick = { onSelectTerm(term) }, color = if (term == selectedTerm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                                PrivacyText(termLabelShort(term), color = if (term == selectedTerm) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                RefreshStateBanner(loading, refreshError, summary != null, onRetry = onRefresh)
                OutlinedButton(
                    onClick = {
                        editingCustom = null
                        customDialogOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { PrivacyText("添加自定义考试") }
            }
        }
        if (summary == null && !loading && customExams.isEmpty()) {
            item { EmptyState(if (!loggedIn) "请先登录" else "暂无考试安排", if (!loggedIn) "登录后刷新即可读取考试。" else "当前学期没有读取到考试。") }
        } else if (exams.isNotEmpty()) {
            item { PreviewSectionHeader("教务考试安排", "${exams.size} 场") }
            items(exams) { exam -> PreviewExamCard(exam) }
        }
        if (customExams.isNotEmpty()) {
            item { PreviewSectionHeader("自定义安排", "${customExams.size} 场") }
            items(customExams) { exam ->
                PreviewCustomExamCard(
                    exam = exam,
                    onEdit = {
                        editingCustom = exam
                        customDialogOpen = true
                    },
                    onDelete = { onDeleteCustom(exam) },
                )
            }
        }
        val unarranged = summary?.unarranged.orEmpty()
        if (unarranged.isNotEmpty()) {
            item { PreviewSectionHeader("未安排", "${unarranged.size} 门") }
            items(unarranged) { item -> PreviewCard(soft = true) { PrivacyText(item.courseName, fontWeight = FontWeight.Bold) } }
        }
    }
    if (customDialogOpen) {
        CustomExamDialog(
            initial = editingCustom,
            termCode = selectedTerm,
            sectionWindows = sectionWindows,
            sectionTimesFromSchedule = sectionTimesFromSchedule,
            onDismiss = {
                customDialogOpen = false
                editingCustom = null
            },
            onSave = { exam ->
                onSaveCustom(exam)
                customDialogOpen = false
                editingCustom = null
            },
        )
    }
}

/**
 *自定义考试卡片 —— 与教务抓取卡片明确区分：
 * 主色描边 + 浅蓝底 + 「自定义」标记，并提供编辑与删除。
 */
@Composable
private fun PreviewCustomExamCard(
    exam: CustomExam,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val parts = exam.date.split('-')
    val day = parts.lastOrNull().orEmpty().trimStart('0').ifBlank { "—" }
    val month = parts.getOrNull(parts.lastIndex - 1)?.trimStart('0').orEmpty()
    Surface(
        color = themePrimarySoft(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = AppCardShape,
        shadowElevation = AppCardElevation,
        border = BorderStroke(1.dp, themePrimary()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Surface(color = themeSurface(), shape = RoundedCornerShape(11.dp)) {
                Column(
                    Modifier.size(58.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PrivacyText(day, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = themePrimary())
                    PrivacyText("${month}月", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrivacyText(exam.courseName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PrivacyText("自定义", style = MaterialTheme.typography.labelSmall, color = themePrimary(), fontWeight = FontWeight.Bold)
                }
                PrivacyText(
                    buildString {
                        append(exam.timeRange)
                        // 显示用户点选的节次集合（并入课表时按这些节落位）。
                        if (exam.sections.isNotEmpty()) {
                            append(" · ${formatSections(exam.sections)} 节")
                        }
                        if (exam.room.isNotBlank()) append(" · ${exam.room}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        PrivacyText("编辑", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        PrivacyText("删除", style = MaterialTheme.typography.labelMedium, color = themeWarning())
                    }
                }
            }
        }
    }
}

/**
 *自定义考试的输入弹窗（新增与编辑共用）。
 *
 * - **日期**：拆成「月」「日」两栏，年份自动推断（编辑既有条目时保留原年份）；
 * - **时间段**：拆成「起始」「结束」两栏，右侧「选择」键铺开 1-11 节次点选；
 * - 选中节次后起止时间按该节次的真实时间（来自本学期课表）自动填好，仍可手动改。
 * - 考试并入课表时**按所选节次落位，但显示的仍是填写的真实时间**。
 */
@Composable
private fun CustomExamDialog(
    initial: CustomExam?,
    termCode: String,
    sectionWindows: Map<Int, IntRange>,
    sectionTimesFromSchedule: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomExam) -> Unit,
) {
    val today = remember { java.time.LocalDate.now() }
    var name by rememberSaveable { mutableStateOf(initial?.courseName.orEmpty()) }
    var monthText by rememberSaveable {
        mutableStateOf(initial?.date?.let { parseExamMonth(it)?.toString() }.orEmpty())
    }
    var dayText by rememberSaveable {
        mutableStateOf(initial?.date?.let { parseExamDay(it)?.toString() }.orEmpty())
    }
    var startTimeText by rememberSaveable {
        mutableStateOf(initial?.timeRange?.substringBefore('-')?.trim().orEmpty())
    }
    var endTimeText by rememberSaveable {
        mutableStateOf(initial?.timeRange?.substringAfter('-', "")?.trim().orEmpty())
    }
    var room by rememberSaveable { mutableStateOf(initial?.room.orEmpty()) }
    // 节次多选。Set 不能直接被 rememberSaveable 保存，用逗号串持久化再解析回集合。
    var sectionsKey by rememberSaveable {
        mutableStateOf(initial?.sections?.sorted()?.joinToString(",").orEmpty())
    }
    val selectedSections = remember(sectionsKey) {
        sectionsKey.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..11 }
            .toSet()
    }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    // 编辑既有条目时保留原年份；新增时才按学期/当前日期自动推断。
    val fixedYear = remember(initial?.date) { initial?.date?.let { parseExamYear(it) } }

    val monthValue = monthText.toIntOrNull()
    val dayValue = dayText.toIntOrNull()
    val inferredYear = remember(monthValue, dayValue, fixedYear, termCode) {
        when {
            fixedYear != null -> fixedYear
            monthValue != null -> inferExamYear(monthValue, dayValue ?: 1, termCode, today)
            else -> null
        }
    }
    val inferredDate = remember(inferredYear, monthValue, dayValue) {
        if (inferredYear == null || monthValue == null || dayValue == null) null
        else runCatching { java.time.LocalDate.of(inferredYear, monthValue, dayValue) }.getOrNull()
    }
    val sectionLabel = if (selectedSections.isNotEmpty()) {
        val window = sectionsTimeWindow(selectedSections, sectionWindows)
        buildString {
            append("${formatSections(selectedSections)} 节")
            if (window != null) {
                append("（${formatMinuteOfDay(window.first)}-${formatMinuteOfDay(window.second)}）")
            }
        }
    } else {
        "未指定节次（并入课表时按时间自动匹配）"
    }

    // 点选节次 = 多选切换；起止时间取所选块中最早/最晚的真实作息时刻（可再手动改）。
    fun toggleSectionPick(section: Int) {
        val next = toggleSection(selectedSections, section)
        sectionsKey = next.sorted().joinToString(",")
        sectionsTimeWindow(next, sectionWindows)?.let { (start, end) ->
            startTimeText = formatMinuteOfDay(start)
            endTimeText = formatMinuteOfDay(end)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            PrivacyText(
                when {
                    pickerOpen -> "选择节次"
                    initial == null -> "添加自定义考试"
                    else -> "编辑自定义考试"
                }
            )
        },
        text = {
            if (pickerOpen) {
                SectionPickerGrid(
                    sectionWindows = sectionWindows,
                    sectionTimesFromSchedule = sectionTimesFromSchedule,
                    selectedSections = selectedSections,
                    onPick = { toggleSectionPick(it) },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { PrivacyText("课程名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 日期：只填月、日，年份自动推断
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = monthText,
                            onValueChange = { monthText = it.filter(Char::isDigit).take(2) },
                            label = { PrivacyText("月") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = dayText,
                            onValueChange = { dayText = it.filter(Char::isDigit).take(2) },
                            label = { PrivacyText("日") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    PrivacyText(
                        when {
                            inferredDate != null ->
                                "推断日期：${inferredDate.year} 年 ${inferredDate.monthValue} 月 ${inferredDate.dayOfMonth} 日（${weekdayName(inferredDate.dayOfWeek.value)}）"
                            monthValue != null && dayValue != null ->
                                "该月没有 ${dayValue} 日，请检查日期"
                            else -> "年份自动推断，只需填写月与日"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 时间段：拆起始、结束两栏，右侧「选择」键点节次
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = startTimeText,
                            onValueChange = { startTimeText = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                            label = { PrivacyText("起始") },
                            placeholder = { PrivacyText("08:00") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endTimeText,
                            onValueChange = { endTimeText = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                            label = { PrivacyText("结束") },
                            placeholder = { PrivacyText("10:00") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { pickerOpen = true },
                            modifier = Modifier.heightIn(min = 40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { PrivacyText("选择") }
                    }
                    PrivacyText(
                        "节次：$sectionLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = room,
                        onValueChange = { room = it },
                        label = { PrivacyText("考场（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error.isNotBlank()) {
                        PrivacyText(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (pickerOpen) {
                TextButton(onClick = { pickerOpen = false }) { PrivacyText("完成") }
            } else {
                TextButton(onClick = {
                    val trimmedName = name.trim()
                    val trimmedRoom = room.trim()
                    val startMinute = parseMinuteOfDay(startTimeText)
                    val endMinute = parseMinuteOfDay(endTimeText)
                    when {
                        trimmedName.isEmpty() -> error = "请填写课程名称"
                        monthValue == null || monthValue !in 1..12 -> error = "请填写月份（1-12）"
                        dayValue == null || dayValue !in 1..31 -> error = "请填写日期（1-31）"
                        inferredYear == null -> error = "年份无法推断，请检查日期"
                        inferredDate == null -> error = "该日期不存在，请检查月与日"
                        startMinute == null -> error = "起始时间格式应为 08:00"
                        endMinute == null -> error = "结束时间格式应为 10:00"
                        endMinute <= startMinute -> error = "结束时间需晚于起始时间"
                        else -> onSave(
                            CustomExam(
                                id = initial?.id ?: System.currentTimeMillis().toString(),
                                courseName = trimmedName,
                                date = formatExamDate(inferredYear, monthValue, dayValue),
                                timeRange = "${formatMinuteOfDay(startMinute)}-${formatMinuteOfDay(endMinute)}",
                                room = trimmedRoom,
                                sections = selectedSections,
                            )
                        )
                    }
                }) { PrivacyText("保存") }
            }
        },
        dismissButton = {
            if (pickerOpen) {
                TextButton(onClick = {
                    sectionsKey = ""
                    pickerOpen = false
                }) { PrivacyText("清除节次") }
            } else {
                TextButton(onClick = onDismiss) { PrivacyText("取消") }
            }
        },
    )
}

/**
 *节次选择面板 —— 1-11 节平铺成网格，点中即选、再点取消，可多选、无先后与起止之分。
 * 每格下方显示该节次的真实时间（来自本学期课表；无课表时用默认作息）。
 */
@Composable
private fun SectionPickerGrid(
    sectionWindows: Map<Int, IntRange>,
    sectionTimesFromSchedule: Boolean,
    selectedSections: Set<Int>,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrivacyText(
            if (sectionTimesFromSchedule) {
                "点选节次（可多选，再点取消）；时间为厦大官方作息表，本学期课表有出入的以课表为准。"
            } else {
                "点选节次（可多选，再点取消）；时间为厦大官方作息时间表。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 每格三行：节号 / 起始时刻 / 结束时刻（与教务作息表一致）。
        (1..11).chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { section ->
                    val window = sectionWindows[section]
                    val selected = section in selectedSections
                    Surface(
                        color = if (selected) themePrimarySoft() else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) themePrimary() else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) themePrimary() else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 62.dp)
                            .clickable { onPick(section) },
                    ) {
                        Column(
                            Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PrivacyText(
                                section.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            PrivacyText(
                                window?.let { formatMinuteOfDay(it.first) }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            PrivacyText(
                                window?.let { formatMinuteOfDay(it.last) }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PreviewExamCard(exam: XmuExam) {
    val parts = exam.date.split('-')
    val day = parts.lastOrNull().orEmpty().trimStart('0').ifBlank { "—" }
    val month = parts.getOrNull(parts.lastIndex - 1)?.trimStart('0').orEmpty()
    PreviewCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(color = themePrimarySoft(), shape = RoundedCornerShape(11.dp)) {
                Column(Modifier.size(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    PrivacyText(day, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = themePrimary())
                    PrivacyText("${month}月", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                PrivacyText(exam.courseName, fontWeight = FontWeight.Bold)
                PrivacyText("${exam.timeRange} · ${exam.room}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
