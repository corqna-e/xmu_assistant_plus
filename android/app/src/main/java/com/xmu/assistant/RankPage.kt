package com.xmu.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun rankDate(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))

/**
 * 专业排名页：三张卡，各管一件事。
 *
 * 「排名结果」只呈现名次与统计口径；「选择范围」负责选范围 + 确认（只读）；
 * 「证明文件」管本地 PDF 的导出，右下角的「重新向教务申请文件」是全页唯一的提交入口。
 * 首次进入且从未获取过时自动检查并获取。
 */
@Composable
internal fun RankPage(state: RankSectionState, onBack: () -> Unit) {
    var abandonDialog by remember { mutableStateOf(false) }
    var acquireDialog by remember { mutableStateOf(false) }
    val result = state.cache.result
    val pending = state.cache.pending
    // 首次进入：未获取过证明文件时自动检查并获取（fetch 内部会拉范围，
    // 多范围时停在「请先选择范围」，单范围则直接完成）；已获取过只预展开范围。
    // 只触发一次，避免重组反复拉；fetch/loadRanges 内部有门与 loading 保护。
    LaunchedEffect(Unit) {
        if (state.cache.result == null && state.cache.pending == null) state.fetch(auto = true)
        else state.loadRanges()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        RankResultCard(state, result)
        RankScopeCard(
            state = state,
            pending = pending,
            onAbandon = { abandonDialog = true },
        )
        RankFileCard(
            state = state,
            result = result,
            onAcquire = { acquireDialog = true },
        )
        // 可滚动页面末尾留白：否则最后一张卡的说明行贴在内容区底边，
        // 滚到底也看不到完整的一行，像被底栏裁掉。
        Spacer(Modifier.height(20.dp))
    }
    if (abandonDialog) AlertDialog(
        onDismissRequest = { abandonDialog = false },
        title = { PrivacyText("结束等待") },
        text = { PrivacyText("这不会撤销教务端的申请。申请若已受理，结果会在下次查询时读到；重复提交不会加快。") },
        confirmButton = { TextButton(onClick = { abandonDialog = false; state.abandonPending() }) { PrivacyText("结束等待") } },
        dismissButton = { TextButton(onClick = { abandonDialog = false }) { PrivacyText("继续等待") } },
    )
    if (acquireDialog) AlertDialog(
        onDismissRequest = { acquireDialog = false },
        title = { PrivacyText("重新向教务申请文件") },
        text = { PrivacyText("将向教务提交一次新的证明申请，并读取返回的结果。\n教务按批次刷新成绩数据，申请本身不会把截止日前移；若同一批次已有结果，可能不会生成新文件，此时仍显示原来的名次。") },
        confirmButton = {
            TextButton(onClick = { acquireDialog = false; state.fetch(force = true) }) { PrivacyText("提交申请") }
        },
        dismissButton = { TextButton(onClick = { acquireDialog = false }) { PrivacyText("取消") } },
    )
}

/**
 * 结果卡：名次 + 人数为一行，第二行是「学年 / 含否校选」两个标签。
 * 这两个标签只认服务端结构化字段，缺字段就不显示，不根据范围名猜。
 */
@Composable
private fun RankResultCard(state: RankSectionState, result: RankResult?) {
    PreviewCard {
        PreviewSectionHeader("排名结果")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyText(
                result?.position?.toString() ?: "—",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = themePrimary(),
            )
            PrivacyText(
                result?.participants?.let { "/ $it 人" } ?: "尚无排名结果",
                modifier = Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result == null) {
            PrivacyText(
                "提交申请并读到结果后，这里显示专业名次、统计人数与所属区间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val term = rankTermOf(result)
            val elective = rankElectiveOf(result)
            if (term.isNotEmpty() || elective.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (term.isNotEmpty()) PreviewBadge(term)
                    if (elective.isNotEmpty()) PreviewBadge(elective)
                }
            }
            PrivacyText(
                if (result.calculatedAt.isNotBlank()) "成绩数据截至 ${result.calculatedAt}"
                else "申请于 ${rankDate(result.requestedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.stale) {
                PrivacyText(
                    "成绩已变化，以下为上次排名。教务刷新成绩数据后，可在「证明文件」里重新向教务申请。",
                    color = themeWarning(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 选择范围卡：选范围 + 确认，是全页唯一的「读取」入口。
 * 确认永远走只读路径：所选范围在教务有有效记录就复用；没有才提交一次申请
 * （新学年、新学期第一次必然如此，不提交就没有结果）。
 * 强制重新申请不在这里 —— 那是「证明文件」卡里的「重新向教务申请文件」。
 */
@Composable
private fun RankScopeCard(
    state: RankSectionState,
    pending: RankPending?,
    onAbandon: () -> Unit,
) {
    PreviewCard {
        PreviewSectionHeader("选择范围")
        if (pending == null && state.ranges.isNotEmpty()) RankRangePicker(state)
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { state.fetch() },
            enabled = !state.loading && (pending != null || state.ranges.size <= 1 || state.selectedRange.isNotBlank()),
            shape = ButtonDefaults.shape,
            // 视觉高度 43dp（48 的 90%），触摸区仍由系统最小交互尺寸补回 48dp。
            modifier = Modifier.fillMaxWidth().heightIn(min = 43.dp),
        ) {
            PrivacyText(
                when {
                    state.loading -> state.stage
                    pending != null -> "继续查询结果"
                    // 多范围必须先选：按钮此时禁用，直接说明原因，避免「点了没反应」
                    state.ranges.size > 1 && state.selectedRange.isBlank() -> "请先选择范围"
                    else -> "确认范围"
                },
            )
        }
        if (state.notice.isNotBlank()) {
            PrivacyText(
                state.notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.error.isNotBlank()) {
            PrivacyText(
                state.error,
                color = themeWarning(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (pending != null && !state.loading) {
            PrivacyText(
                "本次申请：${rankRangeLabel(pending.range)}\n提交于 ${rankDate(pending.requestedAt)}；继续查询只读取结果，不会新增申请。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onAbandon) { PrivacyText("结束本次等待") }
        }
    }
}

/**
 * 范围选择：服务端给的是「学年 × 含否校选」矩阵，按学年分一行、含/不含校选用两枚芯片，
 * 8 个单选按钮压成 3 行，范围名再长也不会把行撑换行。
 * 不标学期数：教务发的是整学年区间，标出来反而被当成「已累计 N 个学期」。
 */
@Composable
private fun RankRangePicker(state: RankSectionState) {
    // 只按学年分组。同一学年若出现多组区间（历史数据），同校选的两枚芯片合并成一枚，
    // 宁可少一个选项也不把「N 学期」这种会被误读的口径放回界面上。
    val groups = state.ranges.groupBy { rankTermLabel(it.term) }
    // 选中态只换底色与字重，形状恒为药丸：选中变直角方框会与同组圆角芯片明显不搭。
    val chipShape = RoundedCornerShape(999.dp)
    groups.forEach { (term, options) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PrivacyText(
                term.ifEmpty { options.first().name },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            options.distinctBy { it.withElectives }.forEach { range ->
                val selected = state.selectedRange == range.id
                // 用 Surface(onClick) 而非 Modifier.clickable：后者的 ripple 按矩形绘制，
                // 不被圆角 shape 裁剪，选中时会露出方框，已在真机验证。
                Surface(
                    shape = chipShape,
                    color = if (selected) themePrimarySoft() else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
                    enabled = !state.loading,
                    onClick = { state.selectRange(range.id) },
                ) {
                    PrivacyText(
                        rankElectiveLabel(range.withElectives).ifEmpty { "选择" },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 证明文件卡：本地 PDF 的导出，以及全页唯一的提交入口「重新向教务申请文件」。
 * 首次获取不在这里 —— 那是「选择范围」确认键的职责，所以没有结果时不显示这枚键。
 * 它是唯一会提交申请的动作，点它必须先二次确认。
 */
@Composable
private fun RankFileCard(
    state: RankSectionState,
    result: RankResult?,
    onAcquire: () -> Unit,
) {
    PreviewCard {
        PreviewSectionHeader("证明文件")
        if (result != null) {
            RankFileRow(state, result)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // 全页唯一的提交入口：其余动作都只读，不会再偷偷提一笔申请。
                TextButton(
                    onClick = onAcquire,
                    enabled = !state.loading,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { PrivacyText("重新向教务申请文件") }
            }
        } else {
            PrivacyText(
                "尚无本地证明。在「选择范围」确认后获取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 文件区：标题行右侧是动作，说明单独占一行，窄屏不会把「含 GPA 与专业排名」挤成两行。 */
@Composable
private fun RankFileRow(state: RankSectionState, result: RankResult) {
    // 文件只在导出时才需要：名次、人数、数据日期都存在别处，文件没了照样显示结果。
    val hasFile = result.pdfFile.isNotBlank()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PrivacyText(
                "证明文件",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (hasFile) {
                TextButton(
                    onClick = { state.exportPdf(result.recordId) },
                    enabled = !state.loading,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { PrivacyText("导出") }
            }
        }
        // 文件卡也要标清这份 PDF 属于哪个范围：结果卡的范围会被下一次选择顶掉，
        // 文件却是按范围落在下载目录里的，导出前先能对上号。
        val rangeText = listOf(rankTermOf(result), rankElectiveOf(result))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        PrivacyText(
            if (!hasFile) "本地证明文件已不在，名次与日期仍保留；导出时会重新取回，不会提交申请"
            else if (rangeText.isNotEmpty()) "绩点证明 PDF · $rangeText · 含 GPA 与专业排名"
            else "绩点证明 PDF · 含 GPA 与专业排名",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
