package com.xmu.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun HomePage(
    username: String,
    password: String,
    loggedIn: Boolean,
    monitorRunning: Boolean,
    accountTransitionInProgress: Boolean,
    monitorTransitionInProgress: Boolean,
    monitorStatus: String,
    monitorLastCheck: String,
    monitorFailureCount: Int,
    monitorLastError: String,
    rollcallSettings: RollcallSettings,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onStartMonitor: () -> Unit,
    onStopMonitor: () -> Unit,
    onAutoChanged: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    savedAccounts: List<Pair<String, String>> = emptyList(),
    rememberAccounts: Boolean = true,
    onRememberAccountsChanged: (Boolean) -> Unit = {},
    onSwitchAccount: (String, String) -> Unit = { _, _ -> },
    currentRollcalls: List<RollcallEvent> = emptyList(),
    rollcallLoading: Boolean = false,
    rollcallError: String = "",
    rollcallUpdatedAtMillis: Long = 0L,
    // 首页两条失败提示终于能真正重试了（此前只有文案里一句「请重试」，无按钮，
    // 且失败原因一直在，红条就天天挂在首页）。监控走重新启动一次检测，签到走重拉列表。
    onRetryRollcall: () -> Unit = {},
    onRetryMonitor: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var showSwitchAccount by rememberSaveable { mutableStateOf(false) }
    // 被用户手动收起的失败提示（按错误原文记，换了原因会重新出现）。
    var dismissedErrors by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    fun dismissError(text: String) {
        dismissedErrors = ArrayList(dismissedErrors).apply { add(text) }
    }
    val monitorErrorVisible = monitorFailureCount > 0 &&
        monitorLastError.ifBlank { "最近检查失败，请检查网络与后台设置。" } !in dismissedErrors
    val rollcallErrorVisible = rollcallError.isNotBlank() && rollcallError !in dismissedErrors
    val canLogin = username.isNotBlank() && password.isNotBlank() && !accountTransitionInProgress
    val autoEnabled = rollcallSettings.autoAnswerNumber || rollcallSettings.autoAnswerRadar
    fun submitLogin() {
        if (canLogin) {
            focus.clearFocus()
            onLogin()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // 未登录时监控卡仍在顶部作欢迎语；已登录时下移到「自动签到快捷开关」上方。
        if (!loggedIn) {
            MonitorStatusCard(
                loggedIn = false,
                monitorRunning = false,
                monitorStatus = "",
                monitorLastCheck = "",
                monitorTransitionInProgress = false,
                accountTransitionInProgress = accountTransitionInProgress,
                onStartMonitor = onStartMonitor,
                onStopMonitor = onStopMonitor,
            )
        }

        if (!loggedIn) {
            PreviewCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    PrivacyText("登录校园账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PreviewBadge("仅保存在本机")
                }
                OutlinedTextField(
                    value = username, onValueChange = onUsername, label = { PrivacyText("学号") },
                    modifier = Modifier.fillMaxWidth(), enabled = !accountTransitionInProgress, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = password, onValueChange = onPassword, label = { PrivacyText("密码") },
                    modifier = Modifier.fillMaxWidth(), enabled = !accountTransitionInProgress, singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            PrivacyText(if (passwordVisible) "隐藏" else "显示")
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrivacyText(
                        "记住密码（本机）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = rememberAccounts, onCheckedChange = onRememberAccountsChanged)
                }
                Button(onClick = { submitLogin() }, enabled = canLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    PrivacyText(if (accountTransitionInProgress) "正在登录…" else "登录")
                }
                PrivacyText("用于读取课表、成绩、考试与畅课签到", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rememberAccounts && savedAccounts.isNotEmpty()) {
                PreviewCard {
                    PrivacyText("快速切换账号", fontWeight = FontWeight.Bold)
                    PrivacyText(
                        "点击已记住的账号直接登录；密码仅保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        savedAccounts.forEach { (account, secret) ->
                            OutlinedButton(
                                onClick = { onSwitchAccount(account, secret) },
                                enabled = !accountTransitionInProgress,
                            ) { PrivacyText(account.take(3) + "****" + account.takeLast(4)) }
                        }
                    }
                }
            }
            PreviewCard(soft = true) {
                PreviewListRow(R.drawable.ic_nav_check, "账号数据仅保存在本机", "密码、Cookie 与通知配置不会上传到其他服务器。", trailing = {})
            }
        } else {
            // 整卡高度对齐首页「使用准备与教程」卡 —— 两处都用同一套
            // PreviewCard（16dp 内边距）+ PreviewListRow（上下 10dp、42dp 图标盒、标题+副标题），
            // 高度由同一套版式决定，不再各自算 padding。
            // 右侧两键仍竖排、同色，放进 PreviewListRow 的 trailing 槽位。
            PreviewCard {
                PreviewListRow(
                    icon = R.drawable.ic_nav_check,
                    title = "校园账号已连接",
                    subtitle = username.take(3) + "****" + username.takeLast(4) + " · 登录状态正常",
                    trailing = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            CompactPillButton("退出登录", enabled = !accountTransitionInProgress) { confirmLogout = true }
                            CompactPillButton("切换账号", enabled = !accountTransitionInProgress) { showSwitchAccount = true }
                        }
                    },
                )
            }
        }

        if (loggedIn) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeShortcut("课程课件", "查找与下载课件", Modifier.weight(1f)) { onNavigate("课程课件") }
                HomeShortcut("考试安排", "查看时间与考场", Modifier.weight(1f)) { onNavigate("考试安排") }
            }
            // 签到监控卡片下移到自动签到快捷开关上方，右上角带设置入口（跳策略页）。
            MonitorStatusCard(
                loggedIn = true,
                monitorRunning = monitorRunning,
                monitorStatus = monitorStatus,
                monitorLastCheck = monitorLastCheck,
                monitorTransitionInProgress = monitorTransitionInProgress,
                accountTransitionInProgress = accountTransitionInProgress,
                onStartMonitor = onStartMonitor,
                onStopMonitor = onStopMonitor,
                onOpenSettings = { onNavigate("策略") },
            )
            PreviewCard {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = autoEnabled, role = Role.Switch,
                        enabled = !accountTransitionInProgress, onValueChange = onAutoChanged,
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        PrivacyText("自动签到", fontWeight = FontWeight.Bold)
                        PrivacyText(
                            homeAutoAnswerSummary(rollcallSettings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoEnabled, onCheckedChange = null, enabled = !accountTransitionInProgress)
                }
                PrivacyText(
                    "此开关与设置页同步，同时开启或关闭数字与雷达签到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 自动签到依赖前台服务，手动「暂停提醒」后服务停止，
                // 开关只是配置、不会有任何自动应答。此处把失效提示加回，避免误以为仍在生效。
                // 打开自动签到时会自动启动监控，因此这行只会在用户主动暂停后出现。
                if (autoEnabled && !monitorRunning) {
                    PrivacyText(
                        "尚未生效，请先启动签到监控",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (monitorErrorVisible) {
                    val text = monitorLastError.ifBlank { "最近检查失败，请检查网络与后台设置。" }
                    RefreshStateBanner(
                        loading = false,
                        errorMessage = text,
                        hasData = false,
                        onRetry = onRetryMonitor,
                        retryLabel = "重新检测",
                        onDismiss = { dismissError(text) },
                    )
                }
                // 沿用美化前“监控与提醒”内的摘要行，不另建签到模块或重复详情卡片。
                val current = currentRollcalls.firstOrNull()
                if (current != null) {
                    val count = if (currentRollcalls.size > 1) " · 共 ${currentRollcalls.size} 项" else ""
                    StatusRow("当前签到", "${current.courseTitle} / ${current.type}$count")
                    TextButton(onClick = { onNavigate("签到情况") }) { PrivacyText("查看签到详情") }
                } else if (rollcallLoading) {
                    StatusRow("当前签到", "正在读取…")
                } else if (rollcallUpdatedAtMillis > 0L && rollcallError.isBlank()) {
                    StatusRow("当前签到", "暂无进行中的签到")
                }
                if (rollcallErrorVisible) {
                    RefreshStateBanner(
                        loading = false,
                        errorMessage = rollcallError,
                        hasData = currentRollcalls.isNotEmpty(),
                        onRetry = onRetryRollcall,
                        onDismiss = { dismissError(rollcallError) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    // 直接进设置下的「自动签到策略」页（原来只回到设置首页，还要再点一层）；
                    // 按钮文案随之改为「调整签到策略」。返回目标由 MainActivity 的来源页记录决定。
                    OutlinedButton(onClick = { onNavigate("自动签到策略") }, modifier = Modifier.weight(1f)) { PrivacyText("调整签到策略") }
                    OutlinedButton(onClick = { onNavigate("课程坐标") }, modifier = Modifier.weight(1f)) { PrivacyText("管理课程坐标") }
                }
            }
        }
        // 使用教程移到首页底部并更名；「后台提醒正常」已收入教程页的「使用前准备」板块。
        // 更多页底部也提供同一入口；此处保留首页快捷跳转，返回目标按工具页规则处理。
        // 首页这张卡阴影单独加到 2dp，与账号卡形成主次（其余卡片仍 1dp）。
        // 曾加过顶端渐隐的浅阴影，已删去 —— 只保留卡片自身的 2dp 阴影。
        PreviewCard(
            onClick = { onNavigate("教程") },
            shadowElevation = 2.dp,
        ) {
            PreviewListRow(
                icon = R.drawable.ic_nav_help,
                title = "使用指南",
                subtitle = "环境检查 · 使用准备 · 常见问题",
                trailing = {
                    PrivacyText("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
    }
    if (showSwitchAccount && loggedIn) {
        val otherAccounts = savedAccounts.filter { it.first != username }
        AlertDialog(
            onDismissRequest = { showSwitchAccount = false },
            title = { PrivacyText("切换账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (otherAccounts.isEmpty()) {
                        PrivacyText("本机尚未保存其他账号。退出后可输入新账号登录。")
                    } else {
                        PrivacyText("选择已记住的账号直接登录：")
                        otherAccounts.forEach { (account, secret) ->
                            TextButton(
                                onClick = { showSwitchAccount = false; onSwitchAccount(account, secret) },
                                enabled = !accountTransitionInProgress,
                            ) { PrivacyText(account.take(3) + "****" + account.takeLast(4)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSwitchAccount = false; onLogout() },
                    enabled = !accountTransitionInProgress,
                ) { PrivacyText("用其他账号登录") }
            },
            dismissButton = { TextButton(onClick = { showSwitchAccount = false }) { PrivacyText("取消") } },
        )
    }
    if (confirmLogout && loggedIn) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { PrivacyText("退出当前账号") },
            text = { PrivacyText("退出后将暂停签到监控，再次登录即可恢复使用。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }, enabled = !accountTransitionInProgress) { PrivacyText("确认退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { PrivacyText("取消") } },
        )
    }
}

/** 签到监控状态卡：整体压低，右上角设置按钮跳策略页。 */
@Composable
private fun MonitorStatusCard(
    loggedIn: Boolean,
    monitorRunning: Boolean,
    monitorStatus: String,
    monitorLastCheck: String,
    monitorTransitionInProgress: Boolean,
    accountTransitionInProgress: Boolean,
    onStartMonitor: () -> Unit,
    onStopMonitor: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            // 首页头部渐变卡与内容卡统一大圆角，并补一层柔和投影。
            .shadow(AppCardElevation, RoundedCornerShape(20.dp))
            .background(AppHeaderBrush, RoundedCornerShape(20.dp))
            .heightIn(min = 104.dp)
            .padding(16.dp),
    ) {
        Column(
            // Box 内必须显式贴底+占满宽度：否则文字会上浮并可能和右上角齿轮重叠，
            // 且「启动提醒」按钮的 fillMaxWidth 会按内容宽度收缩。
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Bottom,
        ) {
            PrivacyText("XMU / 校园日常", style = MaterialTheme.typography.labelMedium, color = Color(0xFFCFE2F5))
            PrivacyText(
                if (!loggedIn) "校园日常，从这里开始" else if (monitorRunning) "签到监控已开启" else "准备好，开始新的一天",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White,
            )
            PrivacyText(
                when {
                    !loggedIn -> "登录后查看课表、成绩与课件。"
                    monitorRunning -> "最近检查 $monitorLastCheck · $monitorStatus"
                    else -> "开启后自动检查课堂签到并按策略提醒。"
                },
                color = Color(0xFFEAF2FB), style = MaterialTheme.typography.bodySmall,
            )
            if (loggedIn) {
                Button(
                    onClick = if (monitorRunning) onStopMonitor else onStartMonitor,
                    enabled = !monitorTransitionInProgress && !accountTransitionInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color.White, contentColor = AppPrimary,
                    ),
                ) {
                    PrivacyText(if (monitorTransitionInProgress) "正在切换…" else if (monitorRunning) "暂停监控" else "启动监控")
                }
            }
        }
        // 右上角设置按钮：半透明白圆底 + 齿轮图标，点击跳策略页
        if (onOpenSettings != null) {
            Surface(
                onClick = onOpenSettings,
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.White.copy(alpha = 0.24f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_nav_settings),
                        contentDescription = "监控设置",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

internal fun autoAnswerTypeSummary(settings: RollcallSettings): String = when {
    settings.autoAnswerNumber && settings.autoAnswerRadar -> "数字与雷达"
    settings.autoAnswerNumber -> "仅数字"
    settings.autoAnswerRadar -> "仅雷达"
    else -> "仅提醒"
}

internal fun waitBeforeAnswerSummary(settings: RollcallSettings): String = when (settings.waitBeforeAnswerMode) {
    WAIT_BEFORE_ANSWER_COUNT -> "达到 ${settings.waitBeforeAnswerCount} 人"
    WAIT_BEFORE_ANSWER_PERCENT -> "达到 ${settings.waitBeforeAnswerPercent}%"
    else -> "不等待人数条件"
}

internal fun homeAutoAnswerSummary(settings: RollcallSettings): String =
    "${autoAnswerTypeSummary(settings)} · ${waitBeforeAnswerSummary(settings)}"

/**
 * 账号卡右侧的紧凑胶囊按钮：两键同一套配色、竖排右对齐。
 *
 * 用 Surface(onClick) 做胶囊时，clickable 会套上 48dp 最小触摸尺寸
 * （minimumInteractiveComponentSize），两枚竖排合计约 98dp，把整张账号卡锁死在
 * 约 114dp —— 减 padding、缩字号都无效。这里显式关掉该下限，胶囊回到内容高度
 * （约 17dp），外观、ripple、点击语义、enabled 态全部保留。
 * 关掉 48dp 下限后按钮会几乎贴在一起，故把按钮垂直内边距回调到 4dp、两键间距 5dp、卡片上下留白 10dp。
 *账号卡改用与「使用准备与教程」卡同构的 PreviewListRow 后，两键竖排加起来比左侧两行文字高，
 * 反而把卡片顶高，教程卡不动，两张卡就不齐。按钮垂直内边距 4→2dp 后两键总高降到低于左侧两行文字，
 * 卡片高度重新由文字决定，与教程卡对齐。
 * 注意：这里的间距一律用 dp 表达，不按截图量像素反推 —— 换设备、换桌面网格、改系统字体大小都会失配。
 * 实测依据一：TextButton 同样套用 48dp 最小尺寸，改回 TextButton 并不能降低高度。
 * 实测依据二：material3 1.3.0 中 LocalMinimumInteractiveComponentEnforcement（Boolean）
 * 已弃用且不再被 MinimumInteractiveModifierNode 读取，真正生效的是
 * LocalMinimumInteractiveComponentSize（Dp）；两个一起提供，兼容新旧读取路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactPillButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentEnforcement provides false,
        LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.widthIn(min = 68.dp),
            color = themePrimarySoft(),
            shape = RoundedCornerShape(999.dp),
        ) {
            PrivacyText(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

internal fun toggleAllAutoAnswer(settings: RollcallSettings, enabled: Boolean): RollcallSettings =
    settings.copy(autoAnswerNumber = enabled, autoAnswerRadar = enabled)

@Composable
private fun HomeShortcut(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = AppCardElevation,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreviewIconBox(if (title == "课程课件") R.drawable.ic_nav_folder else R.drawable.ic_nav_calendar, Modifier.size(34.dp))
            PrivacyText(title, fontWeight = FontWeight.Bold)
            PrivacyText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
