package com.xmu.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun RollcallStatusPage(
    events: List<RollcallEvent>,
    openedEventId: String,
    loading: Boolean,
    refreshError: String,
    updatedAtMillis: Long,
    historyItems: List<RollcallHistoryItem>,
    historyLoading: Boolean,
    historyError: String,
    historyUpdatedAtMillis: Long,
    loggedIn: Boolean = false,
    coordinateStore: RadarCoordinateStore? = null,
    onManageCoordinates: (String, String) -> Unit = { _, _ -> },
    onRefresh: () -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            PreviewCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PrivacyText("签到情况", fontWeight = FontWeight.Bold)
                        PrivacyText(
                            if (loading || historyLoading) "正在刷新" else "上次更新 ${if (updatedAtMillis > 0L) formatMonitorTime(updatedAtMillis) else "刚刚"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !loading && !historyLoading) {
                        PrivacyText(if (loading || historyLoading) "刷新中" else "刷新")
                    }
                }
                if (loading || historyLoading || refreshError.isNotBlank()) {
                    RefreshStateBanner(loading = loading || historyLoading, errorMessage = refreshError, hasData = events.isNotEmpty(), onRetry = onRefresh)
                }
                notificationEventLabel(openedEventId, events, historyItems)?.let { label ->
                    PrivacyText(label, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
        if (events.isNotEmpty()) {
            item { PreviewSectionHeader("正在签到", "${events.size} 项") }
            // 同一签到也会出现在最近记录中，LazyColumn 的 key 必须跨区块唯一。
            items(events, key = { "active:${it.id}" }) { event ->
                val preferred = if (event.type == "雷达签到") {
                    coordinateStore?.preferredFor(event.courseId, event.courseTitle)
                } else null
                ActiveRollcallPreviewCard(
                    event = event,
                    preferred = preferred,
                    onManageCoordinates = { onManageCoordinates(event.courseId, event.courseTitle) },
                )
            }
        }
        // 不随记录日期变化，
        // 放在「签到记录」旁反而让人以为列表只包含今天的记录。
        item { PreviewSectionHeader("签到记录") }
        if (historyLoading) {
            item { PrivacyText("正在读取最近签到…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
        } else if (historyError.isNotBlank()) {
            item { RefreshStateBanner(loading = false, errorMessage = historyError, hasData = historyItems.isNotEmpty(), onRetry = onRefresh) }
        }
        if (historyItems.isEmpty()) {
            item {
                if (!loggedIn) EmptyState("请先登录", "登录后拉取最近签到记录。")
                else EmptyState("暂无签到记录", "点击顶部刷新即可读取最近记录。")
            }
        } else {
            items(historyItems, key = { "history:${it.rollcallId}" }) { item ->
                CompactRollcallHistoryCard(
                    item = item,
                    preferred = if (item.type == "雷达签到") {
                        coordinateStore?.preferredFor(item.courseId, item.courseTitle)
                    } else null,
                    onManageCoordinates = { onManageCoordinates(item.courseId, item.courseTitle) },
                )
            }
        }
    }
}

@Composable
private fun ActiveRollcallPreviewCard(
    event: RollcallEvent,
    preferred: RadarCoordinate?,
    onManageCoordinates: () -> Unit,
) {
    PreviewCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PrivacyText(event.courseTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PrivacyText("发起人：${event.teacher}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PreviewBadge(event.type)
            PreviewBadge("进行中", PreviewBadgeTone.AMBER)
        }
        if (event.type == "数字签到") {
            val progress = event.progress
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PrivacyText(
                    if (progress != null && progress.total > 0) "${progress.present} / ${progress.total} 人已签到" else "签到人数暂未获取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyText(
                    event.numberCode.takeIf { it.isNotBlank() }?.let { "签到码 $it" } ?: "签到码暂未获取",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (progress != null && progress.total > 0) {
                LinearProgressIndicator(
                    progress = { (progress.present.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PreviewIconBox(R.drawable.ic_nav_location)
                Column(Modifier.weight(1f)) {
                    PrivacyText(preferred?.name ?: "未配置课程坐标", fontWeight = FontWeight.Bold, color = if (preferred == null) themeWarning() else MaterialTheme.colorScheme.onSurface)
                    PrivacyText(if (preferred == null) "配置后自动签到才会提交" else "使用本课程首选坐标", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onManageCoordinates) { PrivacyText("坐标管理 ›") }
            }
        }
    }
}

@Composable
private fun CompactRollcallHistoryCard(
    item: RollcallHistoryItem,
    preferred: RadarCoordinate?,
    onManageCoordinates: () -> Unit,
) {
    PreviewCard {
        // 缺勤/缺席/未到用红色标记；✕（U+2715）与 O 字形大小不一致，
        // 改成大写字母 X 并统一字号，两个符号视觉等大。
        val absent = item.ownStatus.trim() in setOf("缺勤", "缺席", "未到", "未签")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = if (absent) MaterialTheme.colorScheme.errorContainer else themeSoftGreen(),
                shape = RoundedCornerShape(11.dp),
            ) {
                PrivacyText(
                    if (absent) "X" else "O",
                    color = if (absent) MaterialTheme.colorScheme.error else themeSuccess(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                PrivacyText(item.courseTitle, fontWeight = FontWeight.Bold)
                val code = item.numberCode.takeIf { it.isNotBlank() }?.let { " · 签到码 $it" }.orEmpty()
                PrivacyText("${item.timeDisplay} · ${item.type}$code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PreviewBadge(
                historyRollcallDisplayStatus(item.ownStatus),
                if (absent) PreviewBadgeTone.RED else PreviewBadgeTone.GREEN,
            )
        }
        if (item.type == "雷达签到") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewIconBox(R.drawable.ic_nav_location)
                PrivacyText(
                    preferred?.name ?: "课程首选地点",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onManageCoordinates) { PrivacyText("坐标管理") }
            }
        }
    }
}

@Composable
fun ScorePage(
    records: List<XmuScoreRecord>,
    loading: Boolean,
    refreshError: String,
    loggedIn: Boolean = false,
    updatedAtMillis: Long,
    onRefresh: () -> Unit,
    onShareScore: () -> Unit,
    rankLabel: String = "尚未获取",
    onOpenRank: (() -> Unit)? = null,
) {
    var simulationRows by rememberSaveable(stateSaver = ScoreSimulationRowsSaver) {
        mutableStateOf<List<Pair<String, String>>>(emptyList())
    }
    val validSimulationCount = remember(simulationRows) {
        simulationRows.count { (score, credit) -> xmuSimulatedScoreRecord(score, credit) != null }
    }
    // The overview and the simulation panel must show the same live result. Keeping
    // simulation state inside the bottom section left the overview frozen at real grades.
    val currentSummary = remember(records) { xmuScoreSummary(records) }
    val summary = remember(records, simulationRows) { xmuSimulatedSummary(records, simulationRows) }
    val recordsByTerm = remember(records) {
        records.groupBy { it.term.ifBlank { "未标注学期" } }
            .entries
            .sortedByDescending { entry ->
                entry.value.firstOrNull()?.termCode?.takeIf { it.isNotBlank() } ?: entry.key
            }
            .map { (term, termRecords) -> term to termRecords.sortedBy { it.courseName } }
    }
    // 学期折叠（模仿课件页）：除最新两学期外默认折叠；用户手动展开/收起的学期
    // 记在 toggledTerms，与默认状态取反。转屏后 rememberSaveable 保留操作结果。
    var toggledTerms by rememberSaveable(stateSaver = ScoreToggledTermsSaver) {
        mutableStateOf<List<String>>(emptyList())
    }
    // rememberSaveable：转屏后保留滚动位置。
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PreviewCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        PrivacyText("成绩概览", fontWeight = FontWeight.Bold)
                        PrivacyText(
                            if (updatedAtMillis > 0L) "上次更新 ${formatMonitorTime(updatedAtMillis)}" else "尚未更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PreviewBadge(
                        if (validSimulationCount > 0) "${records.size} 已出 · $validSimulationCount 模拟"
                        else "${records.size} 门",
                    )
                }
                if (records.isNotEmpty() || validSimulationCount > 0) ScoreSummaryPanel(summary)
                // FlowRow：宽屏下四件单行（「刷新成绩」weight 撑满余宽，观感同原 Row）；
                // 窄屏放不下时自动换行，避免「刷新成绩」被挤压到文字裁切。
                // weight 在 FlowRow 里只按行内余宽分配（320dp 下仅剩约 44dp，主按钮
                // 文字被迫折行），故加最小宽度兜底保证「刷新成绩」始终单行可读。
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.weight(1f).widthIn(min = 112.dp),
                    ) {
                        PrivacyText(if (loading) "刷新中" else "刷新成绩")
                    }
                    // 两个次级按钮收紧内边距，配合 FlowRow 换行兜底窄屏排版
                    OutlinedButton(
                        onClick = onShareScore,
                        enabled = records.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        PrivacyText("分享长图")
                    }
                }
                RefreshStateBanner(loading = loading, errorMessage = refreshError, hasData = records.isNotEmpty(), onRetry = onRefresh)
                if (onOpenRank != null) {
                    TextButton(onClick = onOpenRank, modifier = Modifier.fillMaxWidth()) { PrivacyText("专业排名与绩点证明 ›") }
                }
            }
        }
        if (records.isEmpty()) {
            if (onOpenRank != null) item { RankEntry(rankLabel, onOpenRank) }
            item {
                // 空态三档之「未登录」：登出后成绩清空时给出登录引导而非"暂无成绩"
                if (!loggedIn) {
                    EmptyState("请先登录", "登录后点击刷新即可读取成绩。")
                } else {
                    EmptyState("暂无成绩", "刷新后显示百分制成绩和及格制等非百分制结果。")
                }
            }
        } else {
            recordsByTerm.forEachIndexed { index, (term, termRecords) ->
                // 默认折叠规则：最新两学期（index 0/1）展开，其余折叠；点一下取反
                val collapsed = (index >= 2) xor (term in toggledTerms)
                item(key = "term:$term") {
                    TermHeader(term, termRecords.size, expanded = !collapsed, onToggle = {
                        toggledTerms = if (term in toggledTerms) toggledTerms - term else toggledTerms + term
                    })
                }
                if (!collapsed) {
                    // 注意：不使用显式 key。同一学期可能存在 courseCode+courseName 相同的
                    // 重修/补考记录，若用内容 key 会重复导致 LazyColumn 崩溃；默认 index key 绝对安全。
                    items(count = termRecords.size) { recordIndex ->
                        ScoreRecordCard(termRecords[recordIndex])
                    }
                }
            }
        }
        // 模拟区固定在成绩列表下方（页面最底部，不碍眼），入口按钮滚动跳转至此
        item(key = "score-simulation") {
            ScoreSimulationSection(
                currentSummary = currentSummary,
                simulatedSummary = summary,
                simulationRows = simulationRows,
                onRowsChanged = { simulationRows = it },
            )
        }
    }
}

/**学期折叠开关的持久化：List<String> 直接可存。 */
private val ScoreToggledTermsSaver = listSaver<List<String>, String>(
    save = { it.toList() },
    restore = { it.toList() },
)

/** 模拟行输入的持久化：List<Pair<String, String>> 展平为字符串列表（旋转重建不丢）。 */
private val ScoreSimulationRowsSaver = listSaver<List<Pair<String, String>>, String>(
    save = { rows -> rows.flatMap { listOf(it.first, it.second) } },
    restore = { flat -> List(flat.size / 2) { index -> flat[2 * index] to flat[2 * index + 1] } },
)

private fun List<Pair<String, String>>.withRowReplaced(
    index: Int,
    row: Pair<String, String>,
): List<Pair<String, String>> = mapIndexed { i, current -> if (i == index) row else current }

/**
 * 成绩页底部的「模拟成绩」区：追加若干门预计出分课程（百分制成绩 + 学分），
 * 实时显示「当前 → 计入后」的平均成绩、平均绩点、加权成绩、加权绩点。
 * 纯本地换算（xmuSimulatedSummary），零网络请求；无效行不参与计算、不打断报错。
 */
@Composable
private fun ScoreSimulationSection(
    currentSummary: XmuScoreSummary,
    simulatedSummary: XmuScoreSummary,
    simulationRows: List<Pair<String, String>>,
    onRowsChanged: (List<Pair<String, String>>) -> Unit,
) {
    val validRowCount = simulationRows.count { (score, credit) -> xmuSimulatedScoreRecord(score, credit) != null }
    SectionCard("模拟成绩") {
        PrivacyText(
            "填入预计成绩与学分，平均和加权四项都会实时更新；模拟课不计入已修总学分。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        simulationRows.forEachIndexed { index, (scoreText, creditText) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = scoreText,
                    onValueChange = { onRowsChanged(simulationRows.withRowReplaced(index, it to creditText)) },
                    label = { PrivacyText("预计成绩") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = creditText,
                    onValueChange = { onRowsChanged(simulationRows.withRowReplaced(index, scoreText to it)) },
                    label = { PrivacyText("学分") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedButton(
                    onClick = { onRowsChanged(simulationRows.filterIndexed { i, _ -> i != index }) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { PrivacyText("删除") }
            }
        }
        OutlinedButton(onClick = { onRowsChanged(simulationRows + ("" to "")) }) {
            PrivacyText("+ 添加一门")
        }
        if (simulationRows.isNotEmpty() && validRowCount == 0) {
            PrivacyText(
                "当前行尚未计入：成绩需在 0–100、学分需大于 0",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ScoreSimulationSummaryPanel(currentSummary, simulatedSummary)
    }
}


@Composable
fun NotificationSettingsPage(
    settings: AssistantSettings,
    current: NotificationSettings,
    onTest: (NotificationSettings) -> Unit,
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
    // 端口只允许数字和逗号；输入过程中即时校验，非法时保存前给出提示而不是静默钳制
    val portsInvalid = !smtpPortsValid(qqPorts)
    val preview = NotificationSettings(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts)
    SectionCard("通知") {
        val status = when {
            notificationSettingsMissing(preview) -> "当前状态：缺少配置"
            systemEnabled || pushEnabled || qqEnabled -> "当前状态：已配置"
            else -> "当前状态：未开启"
        }
        PrivacyText(status, fontWeight = FontWeight.Bold)
        StatusRow("本机通知", notificationChannelStatus(systemEnabled, false))
        StatusRow("微信 PushPlus", notificationChannelStatus(pushEnabled, pushToken.isBlank()))
        StatusRow("QQ 邮箱", notificationChannelStatus(qqEnabled, qqSender.isBlank() || qqPassword.isBlank() || qqRecipient.isBlank()))
        PrivacyText("温馨提示：PushPlus 会收取约 3.5 元实名费用；QQ 邮箱免费使用。", color = themeWarning())
        ToggleRow("开启系统通知", systemEnabled) { systemEnabled = it }
        ToggleRow("开启微信通知", pushEnabled) { pushEnabled = it }
        OutlinedTextField(value = pushToken, onValueChange = { pushToken = it }, label = { PrivacyText("PushPlus Token") }, placeholder = { PrivacyText("从 PushPlus 个人中心复制 token") }, modifier = Modifier.fillMaxWidth())
        ToggleRow("开启 QQ 邮箱提醒", qqEnabled) { qqEnabled = it }
        OutlinedTextField(value = qqSender, onValueChange = { qqSender = it }, label = { PrivacyText("发件 QQ 邮箱") }, placeholder = { PrivacyText("例如 student@example.invalid") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = qqPassword, onValueChange = { qqPassword = it }, label = { PrivacyText("SMTP 授权码") }, placeholder = { PrivacyText("不是 QQ 密码，是邮箱生成的授权码") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(value = qqRecipient, onValueChange = { qqRecipient = it }, label = { PrivacyText("收件邮箱") }, placeholder = { PrivacyText("接收提醒的邮箱") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = qqPorts,
            onValueChange = { qqPorts = it.filter { ch -> ch.isDigit() || ch == ',' } },
            label = { PrivacyText("SMTP 端口") },
            placeholder = { PrivacyText("465,587") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = portsInvalid,
            supportingText = {
                if (portsInvalid) PrivacyText("端口需为逗号分隔的 1-65535 数字，例如 465,587")
            },
        )
        PrivacyText("可填写多个端口，例如 465,587；465 使用 SSL，587 使用 STARTTLS。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (portsInvalid) return@Button
                    val saved = NotificationSettings(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts.ifBlank { "465,587" })
                    settings.saveNotifications(saved)
                    onSaved(saved)
                },
                modifier = Modifier.weight(1f),
            ) { PrivacyText("保存通知设置") }
            OutlinedButton(
                onClick = {
                    // 与保存按钮同一校验：onTest 内部会持久化设置，
                    // 非法端口（如 99999）一旦写入，监控链路每次发邮件都拿非法端口连接
                    if (portsInvalid) return@OutlinedButton
                    val saved = NotificationSettings(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts.ifBlank { "465,587" })
                    onTest(saved)
                },
                modifier = Modifier.weight(1f),
            ) { PrivacyText("发送测试通知") }
        }
    }
}


/**
 * 本地可编辑的布尔副本，并在每次进入组合时以「已保存值」为准重同步。
 *
 * 为什么不能用 `rememberSaveable(saved) { ... }` 的 inputs 同步：inputs 只在**组合内**
 * 做失效检测，**状态恢复路径不校验**——SaveableStateHolder 恢复出的旧值优先于 init，
 * 因此跨页面保留的本地副本会一直沿用旧值。策略页正是这种情况：首页改写了同一份
 * RollcallSettings 的自动签到开关，回到策略页仍是旧值，按「确认更改」就把刚开启的
 * 自动签到静默写回（引入 PageStateHost 后可达）。LaunchedEffect 在每次进入
 * 组合时必跑，故用它重同步；值未变时写入同值不触发重组。
 */
@Composable
internal fun rememberSyncedBoolean(saved: Boolean): MutableState<Boolean> {
    val state = rememberSaveable { mutableStateOf(saved) }
    LaunchedEffect(saved) { state.value = saved }
    return state
}

internal fun notificationEventLabel(
    openedEventId: String,
    events: List<RollcallEvent>,
    historyItems: List<RollcallHistoryItem>,
): String? {
    if (openedEventId.isBlank()) return null
    val known = events.any { it.id == openedEventId } || historyItems.any { it.rollcallId == openedEventId }
    if (!known) return "从通知打开的签到未在当前记录中"
    val safeId = openedEventId.filterNot { it == '\r' || it == '\n' || it.isISOControl() }.take(64)
    return "从通知打开：$safeId"
}

@Composable
internal fun rememberSyncedText(saved: String): MutableState<String> {
    val state = rememberSaveable { mutableStateOf(saved) }
    LaunchedEffect(saved) { state.value = saved }
    return state
}

internal fun strategyWaitInputError(mode: String, countText: String, percentText: String): String? = when (mode) {
    WAIT_BEFORE_ANSWER_COUNT ->
        if (countText.toIntOrNull()?.let { it >= 1 } == true) null else "请输入至少 1 的整数"
    WAIT_BEFORE_ANSWER_PERCENT ->
        if (percentText.toIntOrNull()?.let { it in 1..100 } == true) null else "请输入 1-100 的整数比例"
    else -> null
}

internal fun strategyRollcallSaveEnabled(
    intervalText: String,
    mode: String,
    countText: String,
    percentText: String,
): Boolean = pollIntervalSecondsValid(intervalText) && strategyWaitInputError(mode, countText, percentText) == null

@Composable
fun StrategyPage(
    settings: AssistantSettings,
    termCode: String,
    current: RollcallSettings,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    onSaved: (RollcallSettings) -> Unit,
    onAddWidget: () -> Unit,
    onManualWeekSet: (Int) -> Unit,
    examReminder: ExamReminderSettings = ExamReminderSettings(),
    onExamReminderChanged: (ExamReminderSettings) -> Unit = {},
    onOpenExamAlarmSettings: () -> Unit = {},
    onOpenFullScreenSettings: () -> Unit = {},
) {
    var interval by rememberSaveable { mutableStateOf(current.pollIntervalSeconds.toString()) }
    // 自动签到两开关：首页的自动签到开关会改写同一份 RollcallSettings，本地副本
    // 必须能跟随，否则回到本页按「确认更改」会把刚开启的自动签到静默写回
    // （修复机理见 rememberSyncedBoolean 的注释）。
    val number = rememberSyncedBoolean(current.autoAnswerNumber)
    val radar = rememberSyncedBoolean(current.autoAnswerRadar)
    var waitMode by rememberSyncedText(current.waitBeforeAnswerMode)
    var waitCount by rememberSyncedText(current.waitBeforeAnswerCount.toString())
    var waitPercent by rememberSyncedText(current.waitBeforeAnswerPercent.toString())
    // 手动周次用本地 State 持有，确保切换开关/选周时 UI 立即重组（settings 非 State）。
    // 以 termCode 为键：学期变化（新学期/缓存重载）后重读该学期的校准值，
    // 否则本地副本停留在旧学期，与 SchedulePage 实际生效值不一致。
    var manualWeek by rememberSaveable(termCode) { mutableStateOf(settings.manualAcademicWeek(termCode)) }
    // 轮询间隔即时校验：只允许数字，非空时必须为 1-300；空串保存时回退默认 30
    val intervalInvalid = !pollIntervalSecondsValid(interval)
    val waitInputError = strategyWaitInputError(waitMode, waitCount, waitPercent)
    val hasUnsavedRollcallChanges = interval != current.pollIntervalSeconds.toString() ||
        number.value != current.autoAnswerNumber || radar.value != current.autoAnswerRadar ||
        waitMode != current.waitBeforeAnswerMode || waitCount != current.waitBeforeAnswerCount.toString() ||
        waitPercent != current.waitBeforeAnswerPercent.toString()
    // 校准按钮范围：官方校历表内学期用真实周数（18/19），表外学期按 19 兜底，
    // 与反推默认总周数一致，保证 19 周长学期也能手动校准到第 19 周。
    val calibrationMaxWeek = xmuAcademicCalendarForTerm(termCode)?.totalWeeks ?: 19
    // 考试提醒：本地 State + 变更即回调（由 MainActivity 持久化并重排闹钟）
    var reminderEnabled by rememberSaveable { mutableStateOf(examReminder.enabled) }
    var reminderMinutes by rememberSaveable { mutableStateOf(examReminder.advanceMinutes.toString()) }
    var reminderFullScreen by rememberSaveable { mutableStateOf(examReminder.fullScreenEnabled) }
    val reminderMinutesInvalid = reminderMinutes.toIntOrNull()?.let { it in 0..EXAM_REMINDER_MAX_MINUTES } != true && reminderMinutes.isNotBlank()
    fun emitReminder() {
        onExamReminderChanged(
            ExamReminderSettings(
                enabled = reminderEnabled,
                advanceMinutes = reminderMinutes.toIntOrNull()?.coerceIn(0, EXAM_REMINDER_MAX_MINUTES) ?: 30,
                fullScreenEnabled = reminderFullScreen,
            ),
        )
    }
    SectionCard("设置") {
        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit) },
            label = { PrivacyText("默认轮询间隔（1-300 秒）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = intervalInvalid,
            supportingText = {
                if (intervalInvalid) PrivacyText("请输入 1-300 的整数；留空将使用默认 30 秒")
            },
        )
        PrivacyText(
            "这里可分别选择自动处理类型；保存后会与首页快捷开关同步。首页快捷开关会同时开启或关闭数字、雷达签到。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow("数字签到自动处理", number.value) { number.value = it }
        ToggleRow("雷达签到自动处理", radar.value) { radar.value = it }
        PrivacyText("自动处理时机", fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                WAIT_BEFORE_ANSWER_NONE to "检测到立即处理",
                WAIT_BEFORE_ANSWER_COUNT to "达到人数",
                WAIT_BEFORE_ANSWER_PERCENT to "达到比例",
            ).forEach { (value, label) ->
                OutlinedButton(onClick = { waitMode = value }, enabled = waitMode != value) { PrivacyText(label) }
            }
        }
        if (waitMode == WAIT_BEFORE_ANSWER_COUNT) {
            OutlinedTextField(
                value = waitCount,
                onValueChange = { waitCount = it.filter(Char::isDigit) },
                label = { PrivacyText("达到人数后自动处理（至少 1 人）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = waitInputError != null,
                supportingText = { if (waitInputError != null) PrivacyText(waitInputError) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (waitMode == WAIT_BEFORE_ANSWER_PERCENT) {
            OutlinedTextField(
                value = waitPercent,
                onValueChange = { waitPercent = it.filter(Char::isDigit) },
                label = { PrivacyText("达到比例后自动处理（1-100%）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = waitInputError != null,
                supportingText = { if (waitInputError != null) PrivacyText(waitInputError) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                if (intervalInvalid) return@Button
                val count = if (waitMode == WAIT_BEFORE_ANSWER_COUNT) {
                    waitCount.toIntOrNull() ?: return@Button
                } else current.waitBeforeAnswerCount
                val percent = if (waitMode == WAIT_BEFORE_ANSWER_PERCENT) {
                    waitPercent.toIntOrNull() ?: return@Button
                } else current.waitBeforeAnswerPercent
                if (waitMode == WAIT_BEFORE_ANSWER_COUNT && count < 1) return@Button
                if (waitMode == WAIT_BEFORE_ANSWER_PERCENT && percent !in 1..100) return@Button
                val saved = RollcallSettings(
                    pollIntervalSeconds = (interval.toIntOrNull() ?: 30).coerceIn(1, 300),
                    autoAnswerNumber = number.value,
                    autoAnswerRadar = radar.value,
                    waitBeforeAnswerMode = waitMode,
                    waitBeforeAnswerCount = count.coerceAtLeast(1),
                    waitBeforeAnswerPercent = percent.coerceIn(1, 100),
                )
                settings.saveRollcall(saved)
                onSaved(saved)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = strategyRollcallSaveEnabled(interval, waitMode, waitCount, waitPercent),
        ) { PrivacyText("保存设置更改") }
        PrivacyText(
            if (hasUnsavedRollcallChanges) {
                "当前修改尚未保存；点击“保存设置更改”后生效。"
            } else {
                "当前显示已保存设置。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SectionCard("考试提醒") {
        PrivacyText(
            "每场未完成考试在开考前提醒一次（强提醒：响铃 + 震动）。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow("开启考试提醒", reminderEnabled) {
            reminderEnabled = it
            emitReminder()
        }
        OutlinedTextField(
            value = reminderMinutes,
            // 防抖提交：仅更新本地文本，停止输入 500ms 后由 LaunchedEffect 提交一次，
            // 避免逐字符触发全量闹钟重排（100 次 cancelAll + 全量 setExact）与 gate 占用丢值。
            onValueChange = { reminderMinutes = it.filter(Char::isDigit) },
            label = { PrivacyText("提前提醒时间（0-$EXAM_REMINDER_MAX_MINUTES 分钟）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = reminderMinutesInvalid,
            supportingText = {
                if (reminderMinutesInvalid) PrivacyText("请输入 0-$EXAM_REMINDER_MAX_MINUTES 的整数")
            },
        )
        LaunchedEffect(reminderMinutes) {
            if (reminderMinutes != examReminder.advanceMinutes.toString()) {
                kotlinx.coroutines.delay(500)
                // 非法/空白输入不提交：否则会被静默钳制（999→60、空→30）并持久化 +
                // 触发全量闹钟重排，与端口校验「非法时提示而不是静默钳制」的约定矛盾
                if (reminderMinutesInvalid || reminderMinutes.isBlank()) return@LaunchedEffect
                emitReminder()
            }
        }
        ToggleRow("锁屏全屏提醒（闹钟式）", reminderFullScreen) {
            reminderFullScreen = it
            emitReminder()
        }
        PrivacyText(
            "全屏提醒需要系统授权「闹钟与提醒」和「全屏通知」；未授权时仍会以高优先级通知提醒。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onOpenExamAlarmSettings,
                modifier = Modifier.weight(1f),
            ) { PrivacyText("授权精确闹钟") }
            OutlinedButton(
                onClick = onOpenFullScreenSettings,
                modifier = Modifier.weight(1f),
            ) { PrivacyText("授权全屏通知") }
        }
    }
    SectionCard("课表周次") {
        PrivacyText(
            "课表默认按教务系统自动判断当前周次；如果判断不准（如假期或新学期），" +
                "校准一次当前周次即可，之后仍会按日期自动推进（明天同周、下周自动 +1）。" +
                "校准不是关闭自动判断，两者同时生效。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 状态行：自动判断始终开启；手动校准只是额外基准，不与之对立
        PrivacyText(
            if (manualWeek == 0) {
                "当前：自动判断周次"
            } else {
                "当前：已校准为第 ${manualWeek} 周为基准，仍在自动推进"
            },
            fontWeight = FontWeight.Bold,
            color = if (manualWeek == 0) themePrimary() else themeWarning(),
        )
        var calibrating by remember { mutableStateOf(false) }
        if (calibrating) {
            PrivacyText("点击指定当前是第几周：", fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                (1..calibrationMaxWeek).forEach { week ->
                    OutlinedButton(
                        onClick = {
                            calibrating = false
                            manualWeek = week
                            onManualWeekSet(week)
                        },
                        shape = RoundedCornerShape(12.dp),
                        // 触控目标 ≥48dp（无障碍基线，与其余交互组件一致）；行本身可横向滚动，加大不挤布局
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        PrivacyText("$week", fontWeight = if (manualWeek == week) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            OutlinedButton(
                onClick = { calibrating = false },
                modifier = Modifier.fillMaxWidth(),
            ) { PrivacyText("取消") }
        } else {
            Button(
                onClick = { calibrating = true },
                modifier = Modifier.fillMaxWidth(),
            ) { PrivacyText(if (manualWeek == 0) "校准当前周次" else "重新校准当前周次") }
        }
        if (manualWeek != 0) {
            OutlinedButton(
                onClick = {
                    manualWeek = 0
                    onManualWeekSet(0)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { PrivacyText("清除校准，恢复纯自动判断") }
        }
    }
    SectionCard("桌面小卡片") {
        PrivacyText("在手机桌面添加「今日课程」小卡片，不用打开 App 也能看到当天课程、时间和地点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 删除总开关，改由系统桌面直接添加。
        Button(
            onClick = onAddWidget,
            modifier = Modifier.fillMaxWidth(),
        ) { PrivacyText("添加到桌面") }
    }
    SectionCard("外观") {
        PrivacyText("选择主题模式：", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OptionRow(
            label = "主题",
            options = listOf("跟随系统", "浅色", "深色"),
            selected = when (themeMode) {
                THEME_MODE_LIGHT -> "浅色"
                THEME_MODE_DARK -> "深色"
                else -> "跟随系统"
            },
        ) { selectedLabel ->
            onThemeModeChanged(
                when (selectedLabel) {
                    "浅色" -> THEME_MODE_LIGHT
                    "深色" -> THEME_MODE_DARK
                    else -> THEME_MODE_SYSTEM
                },
            )
        }
    }
}
