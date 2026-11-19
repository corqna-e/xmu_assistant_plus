package com.xmu.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 考试提醒提前量上限：7 天。闹钟调度按分钟计算，本身无 60 分钟限制。 */
internal const val EXAM_REMINDER_MAX_MINUTES = 7 * 24 * 60

/** 把提前分钟数转成策略页可读文案。 */
internal fun examReminderLabel(minutes: Int): String = when (minutes) {
    0 -> "准时提醒"
    5 -> "提前 5 分钟"
    30 -> "提前 30 分钟"
    60 -> "提前 1 小时"
    1440 -> "提前 1 天"
    else -> "提前 $minutes 分钟"
}

private data class ReminderChoice(val label: String, val minutes: Int?)

private val reminderChoices = listOf(
    ReminderChoice("准时", 0),
    ReminderChoice("提前 5 分钟", 5),
    ReminderChoice("提前 30 分钟", 30),
    ReminderChoice("提前 1 小时", 60),
    ReminderChoice("提前 1 天", 1440),
)

private const val CHOICE_OFF = "off"
private const val CHOICE_CUSTOM = "custom"

/** 考试提醒时间二级页：选项列表对齐「无 / 准时 / 提前 … / 自定义」的系统日历风格。 */
@Composable
internal fun ExamReminderTimePage(
    current: ExamReminderSettings,
    onSave: (ExamReminderSettings) -> Unit,
    onBack: () -> Unit,
) {
    val preset = reminderChoices.firstOrNull { it.minutes == current.advanceMinutes }
    var choice by rememberSaveable {
        mutableStateOf(
            when {
                !current.enabled -> CHOICE_OFF
                preset != null -> preset.label
                else -> CHOICE_CUSTOM
            },
        )
    }
    var customHours by rememberSaveable { mutableStateOf((current.advanceMinutes / 60).toString()) }
    var customMinutes by rememberSaveable { mutableStateOf((current.advanceMinutes % 60).toString()) }
    val customHourValue = customHours.toIntOrNull()
    val customMinuteValue = customMinutes.toIntOrNull()
    val customValue = if (customHourValue != null && customMinuteValue != null) {
        customHourValue * 60 + customMinuteValue
    } else null
    val customInvalid = choice == CHOICE_CUSTOM && (
        customHourValue == null || customMinuteValue == null ||
            customHourValue !in 0..(EXAM_REMINDER_MAX_MINUTES / 60) ||
            customMinuteValue !in 0..59 ||
            customValue !in 0..EXAM_REMINDER_MAX_MINUTES
        )

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewSectionHeader("考试提醒时间", "设置")
        PreviewCard {
            PrivacyText("提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PrivacyText(
                "设置考试开始前的提醒时刻，保存后会重新安排全部考试闹钟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReminderOptionRow("无（关闭考试提醒）", choice == CHOICE_OFF) { choice = CHOICE_OFF }
            reminderChoices.forEach { option ->
                ReminderOptionRow(option.label, choice == option.label) { choice = option.label }
            }
            ReminderOptionRow("自定义", choice == CHOICE_CUSTOM) { choice = CHOICE_CUSTOM }
            if (choice == CHOICE_CUSTOM) {
                // 时 + 分两段输入
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = customHours,
                        onValueChange = { customHours = it.filter(Char::isDigit) },
                        label = { PrivacyText("提前小时数") },
                        singleLine = true,
                        isError = customInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter(Char::isDigit) },
                        label = { PrivacyText("分钟数（0-59）") },
                        singleLine = true,
                        isError = customInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (customInvalid) {
                    PrivacyText(
                        "请输入小时 0-${EXAM_REMINDER_MAX_MINUTES / 60}、分钟 0-59，总计不超过 ${EXAM_REMINDER_MAX_MINUTES} 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { PrivacyText("取消") }
                Button(
                    onClick = {
                        if (customInvalid) return@Button
                        val minutes = when (choice) {
                            CHOICE_OFF -> current.advanceMinutes
                            CHOICE_CUSTOM -> customValue ?: current.advanceMinutes
                            else -> reminderChoices.first { it.label == choice }.minutes ?: 0
                        }
                        onSave(
                            current.copy(
                                enabled = choice != CHOICE_OFF,
                                advanceMinutes = minutes.coerceIn(0, EXAM_REMINDER_MAX_MINUTES),
                            ),
                        )
                        onBack()
                    },
                    enabled = !customInvalid,
                    modifier = Modifier.weight(1f),
                ) { PrivacyText("确定") }
            }
        }
    }
}

@Composable
private fun ColumnScope.ReminderOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrivacyText(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) themePrimary() else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            PrivacyText("✓", color = themePrimary(), fontWeight = FontWeight.Bold)
        }
    }
}

/** 当前周次二级页：自动判断 / 手动选择，手动用原生滚轮（时钟翻动）选周。 */
@Composable
internal fun ManualWeekPage(
    termCode: String,
    totalWeeks: Int,
    autoWeek: Int?,
    currentManualWeek: Int,
    onSet: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var manualMode by rememberSaveable { mutableStateOf(currentManualWeek > 0) }
    var pickedWeek by rememberSaveable { mutableStateOf(if (currentManualWeek > 0) currentManualWeek else (autoWeek ?: 1)) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // 可滚动内容区：确定/取消固定在页面底部
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            PreviewSectionHeader("当前周次", termCode.ifBlank { "课表" })
            PreviewCard {
                PrivacyText("判断方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ModeChip("自动判断", !manualMode, Modifier.weight(1f)) { manualMode = false }
                    ModeChip("手动选择", manualMode, Modifier.weight(1f)) { manualMode = true }
                }
                PrivacyText(
                    if (manualMode) {
                        "手动指定第几周后，仍会按日期自动推进（下周自动 +1）。"
                    } else {
                        autoWeek?.let { "按教务校历自动判断：当前第 $it 周。" }
                            ?: "按教务校历自动判断；开学后会自动校准。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (manualMode) {
                PreviewCard {
                    PrivacyText("选择周次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    WeekPickerWheel(
                        pickedWeek = pickedWeek,
                        totalWeeks = totalWeeks.coerceAtLeast(1),
                        onPicked = { pickedWeek = it },
                    )
                    PrivacyText(
                        "上下滑动选择周次，共 $totalWeeks 周。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { PrivacyText("取消") }
            Button(
                onClick = {
                    onSet(if (manualMode) pickedWeek else 0)
                    onBack()
                },
                modifier = Modifier.weight(1f),
            ) { PrivacyText("确定") }
        }
    }
}

/**
 * 周次滚轮（Compose 原生实现）：惯性滑动 + snap 吸附居中，中心高亮，
 * 替代原生 NumberPicker（动画生硬、深色主题适配差）。
 */
@Composable
private fun WeekPickerWheel(pickedWeek: Int, totalWeeks: Int, onPicked: (Int) -> Unit) {
    val itemHeight = 44.dp
    val viewportHeight = itemHeight * 5
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }.roundToInt()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (pickedWeek - 1).coerceAtLeast(0))
    // snap 吸附：滚动结束后稳定停在中心项，动画平滑
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // 滚动中实时映射中心项 → 选中周；snap 结束后 offset 趋近 0，中心项即 firstVisibleItemIndex+1
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .map { (index, offset) -> index + if (offset > itemHeightPx / 2) 1 else 0 }
            .distinctUntilChanged()
            .collect { centered -> onPicked((centered + 1).coerceIn(1, totalWeeks)) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(viewportHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
    ) {
        // 中心高亮条
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(itemHeight)
                .background(themePrimarySoft(), RoundedCornerShape(10.dp)),
        )
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = (viewportHeight - itemHeight) / 2),
            modifier = Modifier.fillMaxSize(),
        ) {
            items((1..totalWeeks).toList()) { week ->
                val centered = week == pickedWeek
                Box(Modifier.height(itemHeight), contentAlignment = Alignment.Center) {
                    PrivacyText(
                        "第 $week 周",
                        style = if (centered) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (centered) FontWeight.Bold else FontWeight.Normal,
                        color = if (centered) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) themePrimary() else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
            PrivacyText(
                label,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**策略页等处复用的小型编辑按钮：直接用「编辑」字样。 */
@Composable
internal fun SmallEditButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = themePrimarySoft(),
        modifier = Modifier.heightIn(min = 30.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            PrivacyText("编辑", color = themePrimary(), style = MaterialTheme.typography.labelLarge)
        }
    }
}
