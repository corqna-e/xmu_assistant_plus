package com.xmu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Locale

/** 通知配置是否缺少必填项（用于顶部标签角标提示）。 */
fun notificationSettingsMissing(settings: NotificationSettings): Boolean {
    val pushMissing = settings.pushPlusEnabled && settings.pushPlusToken.isBlank()
    val qqMissing = settings.qqMailEnabled &&
        (settings.qqMailSender.isBlank() || settings.qqMailPassword.isBlank() || settings.qqMailRecipient.isBlank())
    return pushMissing || qqMissing
}

/** 通知通道状态文案。 */
fun notificationChannelStatus(enabled: Boolean, missing: Boolean): String = when {
    !enabled -> "未开启"
    missing -> "缺少配置"
    else -> "可用"
}

/** 监控状态文案（首页状态区）。 */
fun monitorStatusText(running: Boolean, failures: Int): String = when {
    !running -> "未启动"
    failures >= 3 -> "系统可能限制后台"
    failures > 0 -> "最近失败"
    else -> "运行中"
}

/** 时间戳格式化（HH:mm:ss，首页/各页"上次更新"用）。
 *DateTimeFormatter 线程安全可做单例（SimpleDateFormat 每次新建有分配开销且非线程安全）。 */
private val monitorTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatMonitorTime(millis: Long): String {
    if (millis <= 0L) return "-"
    return monitorTimeFormatter.format(
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()),
    )
}

/** 完整刷新时间戳（M月d日HH时mm分ss秒）：课表页底部「上次刷新」用。 */
private val refreshTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("M月d日HH时mm分ss秒")

fun formatRefreshTimestamp(millis: Long): String {
    if (millis <= 0L) return "-"
    return refreshTimeFormatter.format(
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()),
    )
}

/** 发送本机测试通知。 */
fun sendLocalTestNotification(activity: ComponentActivity) {
    val manager = activity.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("xmu_assistant_test", "虾大助手Plus通知测试", NotificationManager.IMPORTANCE_DEFAULT))
    val pendingIntent = PendingIntent.getActivity(
        activity,
        2001,
        Intent(activity, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    manager.notify(
        2001,
        Notification.Builder(activity, "xmu_assistant_test")
            .setContentTitle("虾大助手Plus 测试通知")
            .setContentText("如果你收到这条消息，说明本机通知可用。")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build(),
    )
}

/**
 * 当前是否深色（由 XmuMobileTheme 按 themeMode 决定：跟随系统 / 强制浅色 / 强制深色）。
 * 所有 theme* 变体函数读取它而不是直接读 isSystemInDarkTheme()，
 * 这样用户手动选择主题后，课表格子/功能底色/警示色等全部跟随。
 */
internal val LocalXmuDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun XmuMobileTheme(themeMode: String = THEME_MODE_SYSTEM, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        THEME_MODE_LIGHT -> false
        THEME_MODE_DARK -> true
        else -> systemDark
    }
    // 隐私模式与深色开关同层下发，整棵树里的 PrivacyText / BrandHeader 即时生效。
    androidx.compose.runtime.CompositionLocalProvider(
        LocalXmuDarkTheme provides dark,
        LocalPrivacyMode provides PrivacyModeState.enabled,
    ) {
        // 体检报告 P1-7：色板必须 remember(dark)。M3 Colors 不覆写 equals，
        // 内联构造会让每次重组都产生新实例 → CompositionLocal 判定变化 →
        // 所有读取 colorScheme 的组件全量重组（登录页每敲一个字符即重跑整棵树）。
        // 整体色板由低饱和灰蓝换成「白卡片 + 极浅蓝白背景 + 中等明度学院蓝」。
        // 主色刻意控制在 #3369B0 一带（不是高饱和亮蓝），避免刺眼，同时与蓝色 logo 协调。
        // 深色模式同步对齐新色系，不再出现灰闷的蓝灰块。
        val colorScheme = remember(dark) {
            if (dark) {
                darkColorScheme(
                    primary = Color(0xFF9CBEE8),
                    onPrimary = Color(0xFF0B2240),
                    primaryContainer = Color(0xFF1E3F65),
                    onPrimaryContainer = Color(0xFFDCEBFA),
                    secondary = Color(0xFF8FB2D6),
                    onSecondary = Color(0xFF0B2240),
                    secondaryContainer = Color(0xFF253C57),
                    onSecondaryContainer = Color(0xFFDCEBFA),
                    surface = Color(0xFF16212E),
                    onSurface = Color(0xFFEDF3FB),
                    surfaceVariant = Color(0xFF25364A),
                    onSurfaceVariant = Color(0xFFA7B8CC),
                    surfaceTint = Color(0xFF9CBEE8),
                    background = Color(0xFF0E1621),
                    onBackground = Color(0xFFEDF3FB),
                    outline = Color(0xFF7E93AC),
                    outlineVariant = Color(0xFF2A3A4E),
                    error = Color(0xFFFF8A80),
                    onError = Color(0xFF1A0A0A),
                    errorContainer = Color(0xFF3A2020),
                    onErrorContainer = Color(0xFFFFDAD6),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF3369B0),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFE3EDFA),
                    onPrimaryContainer = Color(0xFF173A68),
                    secondary = Color(0xFF5B86B8),
                    onSecondary = Color.White,
                    secondaryContainer = Color(0xFFEAF2FB),
                    onSecondaryContainer = Color(0xFF1C3F6B),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF16283F),
                    surfaceVariant = Color(0xFFEFF4FA),
                    onSurfaceVariant = Color(0xFF5E7189),
                    surfaceTint = Color(0xFF3369B0),
                    background = Color(0xFFF5F8FC),
                    onBackground = Color(0xFF16283F),
                    outline = Color(0xFF9AAEC4),
                    outlineVariant = Color(0xFFE4EBF4),
                    error = Color(0xFFC6382B),
                    onError = Color.White,
                    errorContainer = Color(0xFFFFECEC),
                    onErrorContainer = Color(0xFF7A1A12),
                )
            }
        }
        // 方案一：全局形状放大一级，让 M3 的 Button/Card/TextField/Chip/Dialog
        // 与显式卡片（AppCardShape 18dp）落在同一套大圆角语言上。
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

internal val AppPrimary = Color(0xFF3369B0)
internal val AppHeaderBrush = Brush.linearGradient(
    listOf(Color(0xFF2F609F), Color(0xFF4E83BE)),
)
internal val AppSuccess = Color(0xFF2E8B6B)

/** 深色模式下把浅色功能底换成深色变体，避免深色界面出现刺眼亮块。 */
@Composable
internal fun themeSoftGreen(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF173329) else Color(0xFFE3F2EA)

/** 未登录/警示底色：深色模式换深红变体，避免深色界面出现刺眼亮块。 */
@Composable
internal fun themeSoftRed(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF3A2020) else Color(0xFFFFECEC)

/** 品牌角标（XMU）底色：深色模式换深琥珀变体，避免刺眼亮块。 */
@Composable
internal fun themeSoftAmber(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF362B22) else Color(0xFFFFF3E2)

/** 品牌角标（XMU）文字色：深色下用更亮的琥珀保证深底可读。 */
@Composable
internal fun themeOnAmber(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFE0B886) else Color(0xFF8A6220)

/** 「已下载但本地找不到文件」的淡橙底：比琥珀更偏暖，和「有新增课件」区分开。 */
@Composable
internal fun themeSoftOrange(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF3A2A1D) else Color(0xFFFBEEE1)

/** 「已下载但本地找不到文件」的暗橙字，深色下用亮橙保证深底可读。 */
@Composable
internal fun themeOnOrange(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFE9A468) else Color(0xFFA85B15)

@Composable
internal fun themeSurface(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF16212E) else Color(0xFFFFFFFF)

@Composable
internal fun themePrimarySoft(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF1E3F65) else Color(0xFFE7F0FB)

/** 警示文字色：深色模式下用更亮的红，保证深底上的可读性。 */
@Composable
internal fun themeWarning(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFC6382B)

/** 成功文字色：深色模式下用更亮的绿，保证深底上的可读性。 */
@Composable
internal fun themeSuccess(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF8FD2B6) else Color(0xFF257A5E)

/** 顶部标签选中底色：深色模式下用稍亮的蓝，保证深底上的区分度。 */
@Composable
internal fun themeSelectedTab(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF3F6FA6) else AppPrimary

/** 课表/日程选中的 chip 底色：与 themeSelectedTab 同源，深色下用浅蓝区分深底。 */
@Composable
internal fun themeSelectedChip(): Color = themeSelectedTab()

/** 主色文字变体：深色模式下用浅蓝，避免学院蓝 AppPrimary 在深底上不可读。 */
@Composable
internal fun themePrimary(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF9CBEE8) else Color(0xFF3369B0)

/**底栏选中态用的加深主色 —— 与未选中灰形成更明确的选择态。 */
@Composable
internal fun themePrimaryDeep(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF7FA9DB) else Color(0xFF23508C)

@Composable
internal fun themeTodayColumn(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF1C3550) else Color(0xFFEDF4FC)

@Composable
internal fun themeInfoCard(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF172B3E) else Color(0xFFF4F8FD)

@Composable
internal fun themeFailedCard(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF3A2323) else Color(0xFFFFF1EF)

@Composable
fun BrandHeader(
    loggedIn: Boolean,
    page: Page = Page.HOME,
    /** 标题特例（如「高数 · 课程坐标」）：非空时优先于 [page] 推导出的标题。 */
    titleOverride: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    // 一级页（底栏四页）不做字母遮蔽，只有二三级页才换成随机字母。
    primaryPage: Boolean = false,
) {
    // 方案一：顶部栏与内容卡片用同一套「大圆角 + 柔和阴影 + 无硬描边」语言。
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = AppCardShape,
        shadowElevation = AppCardElevation,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                Surface(onClick = onBack, color = themePrimarySoft(), shape = androidx.compose.foundation.shape.CircleShape) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        PrivacyText("‹", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                AppLogo(Modifier.size(34.dp), null)
            }
            Spacer(Modifier.width(12.dp))
            // 一级页标题保持可读，只有下级页逐字换随机字母。
            // 首页标题独立于隐私模式：默认「首页」，点一下换成「厦大助手」，再点切回。
            // 离开首页再回来时回到「首页」（key 带上 page，切页即重置）。
            var homeAliasShown by remember(page) { mutableStateOf(false) }
            val privacy = LocalPrivacyMode.current
            val rawTitle = titleOverride ?: if (page == Page.HOME) "厦大助手" else pageTitle(page)
            val homeTitleToggle = titleOverride == null && page == Page.HOME
            val shownTitle = when {
                homeTitleToggle -> if (homeAliasShown) rawTitle else "首页"
                privacy && !primaryPage -> privacyTitle(rawTitle, true)
                else -> rawTitle
            }
            // 点击区域限定在标题文字本身，右侧留白不响应，避免误触。
            Box(Modifier.weight(1f)) {
                PrivacyText(
                    // 标题走 pageTitle()，与底栏／更多页入口名保持一致
                    // （此前底栏已改「设置」，顶部却仍显示内部页面 id「策略」）。
                    shownTitle,
                    modifier = if (homeTitleToggle) {
                        Modifier.clickable { homeAliasShown = !homeAliasShown }
                    } else {
                        Modifier
                    },
                    color = themePrimary(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    // 一级页标题用于定位当前页，与底栏一致保留「课」字（否则「？表」认不出）。
                    skipRules = if (primaryPage) setOf("课") else emptySet(),
                )
            }
            Surface(
                color = if (loggedIn) themeSoftGreen() else themeSoftRed(),
                shape = RoundedCornerShape(999.dp),
            ) {
                PrivacyText(
                    if (loggedIn) "已登录" else "未登录",
                    color = if (loggedIn) themeSuccess() else themeWarning(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun LargeLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.xmu_assistant_logo_large),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun AppLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.xmu_assistant_mark),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun StatusStack(
    account: String,
    monitor: String,
    monitorLastCheck: String,
    monitorFailureCount: Int,
    monitorLastError: String,
    auto: String,
    recent: String,
) {
    SectionCard("守护状态") {
        StatusRow("账号", account)
        StatusRow("监控", monitor)
        StatusRow("最近检查", monitorLastCheck)
        if (monitorFailureCount > 0) {
            StatusRow("连续失败", "${monitorFailureCount} 次")
        }
        if (monitorLastError.isNotBlank()) {
            StatusRow("失败原因", monitorLastError)
        }
        StatusRow("自动签到", auto)
        StatusRow("最近签到", recent)
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrivacyText(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Spacer(Modifier.width(12.dp))
        PrivacyText(value, modifier = Modifier.weight(0.65f), fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RefreshStateBanner(
    loading: Boolean,
    errorMessage: String,
    hasData: Boolean,
    /**失败时在右侧给「重试」，调用方传入本页自己的刷新动作；为 null 则只显示文案。 */
    onRetry: (() -> Unit)? = null,
    /**重试按钮的文案（首页监控用「重新检测」，其余用「重试」）。 */
    retryLabel: String = "重试",
    /**允许把这条失败提示收起来（首页常驻红条太吵）。 */
    onDismiss: (() -> Unit)? = null,
) {
    when {
        loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            PrivacyText("正在刷新…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        errorMessage.isNotBlank() -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrivacyText(
                    refreshStateText(errorMessage, hasData, withRetryHint = onRetry != null),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        PrivacyText(
                            retryLabel,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        PrivacyText(
                            "关闭",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 *底部纵向账本。
 * 底栏 81dp（1dp 分隔线 + 80dp NavigationBar）+ 6dp 间隙 = 87dp → 提示条底距；
 * 提示条实测高约 48dp，再加 11dp 间隙 → 悬浮控件（下载键/缩放条）底距 146dp。
 */
internal val ToastBottomInset = 87.dp
internal val FloatingControlBottomInset = 146.dp

@Composable
fun ToggleRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onChanged)
        Spacer(Modifier.width(10.dp))
        PrivacyText(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionCard(title: String, showTitle: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    // 去硬描边，改大圆角 + 柔和阴影（与 PreviewCard 同一套语言）。
    Card(
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = AppCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = AppCardElevation),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showTitle) PrivacyText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = AppInnerShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            content()
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(themeInfoCard(), RoundedCornerShape(12.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLogo(Modifier.size(64.dp), null)
            PrivacyText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PrivacyText(
                body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * 瞬时提示级别（B4 显式化）：show() 默认 INFO；showWarning/showError 显式标注，
 * 文案子串匹配（"失败"/"请先"）仅作未迁移调用点的兜底，保留不删。
 * WARNING/ERROR 共用 errorContainer 视觉（历史 warning 二态样式不变）。
 */
enum class ToastSeverity { INFO, WARNING, ERROR, SUCCESS }

@Composable
fun ToastBar(message: String, severity: ToastSeverity) {
    // 成功提示单独走绿色（原来只有深底/红底两态，成功文案里带「失败 0」也被染红）。
    val success = severity == ToastSeverity.SUCCESS
    val warning = severity == ToastSeverity.WARNING || severity == ToastSeverity.ERROR
    // 改成满宽深色条 + 高阴影 —— 旧版半屏浅灰条在浅蓝白页面上几乎看不见，
    // 而且只占左侧一半显得很滑稽。位置由调用方摆在悬浮键上方，两者互不遮挡。
    Surface(
        color = when {
            success -> AppSuccess
            warning -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.inverseSurface
        },
        contentColor = when {
            success -> Color.White
            warning -> MaterialTheme.colorScheme.onError
            else -> MaterialTheme.colorScheme.inverseOnSurface
        },
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (warning || success) {
                Surface(
                    color = (if (success) Color.White else MaterialTheme.colorScheme.onError).copy(alpha = 0.18f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.size(20.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PrivacyText(
                            if (success) "✓" else "!",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            // 中性提示用深底浅字（inverseSurface），不再用中灰撞在浅色卡上。
            PrivacyText(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun ScoreSummaryPanel(summary: XmuScoreSummary) {
    ScoreSummaryPanelSurface {
        ScoreSummaryMetricRows(summary)
        ScoreMetric("已修总学分", scoreMetricText(summary.completedCredits), Modifier.fillMaxWidth())
    }
}

/** 四项统计的公共两行布局：平均与加权各自成行，顶部概览与模拟面板共用。 */
@Composable
private fun ScoreSummaryMetricRows(summary: XmuScoreSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ScoreMetric("平均绩点", scoreMetricText(summary.averageGpa), Modifier.weight(1f))
        ScoreMetric("加权绩点", scoreMetricText(summary.weightedGpa), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ScoreMetric("平均分数", scoreMetricText(summary.averageScore), Modifier.weight(1f))
        ScoreMetric("加权分数", scoreMetricText(summary.weightedScore), Modifier.weight(1f))
    }
}

/**
 * 模拟成绩面板：每项并排显示「当前 → 计入模拟课程后」，四项都会随输入变化。
 * 平均按课程等权、加权按学分；模拟课不计入已修学分。
 */
@Composable
fun ScoreSimulationSummaryPanel(current: XmuScoreSummary, simulated: XmuScoreSummary) {
    ScoreSummaryPanelSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SimulationDeltaMetric("平均绩点", current.averageGpa, simulated.averageGpa, "sim-avg-gpa", Modifier.weight(1f))
            SimulationDeltaMetric("加权绩点", current.weightedGpa, simulated.weightedGpa, "sim-weighted-gpa", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SimulationDeltaMetric("平均分数", current.averageScore, simulated.averageScore, "sim-avg-score", Modifier.weight(1f))
            SimulationDeltaMetric("加权分数", current.weightedScore, simulated.weightedScore, "sim-weighted-score", Modifier.weight(1f))
        }
        PrivacyText(
            "箭头左侧为当前成绩，右侧为计入模拟课程后的结果；模拟课不计入已修总学分。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单项：左值当前、右值计入模拟后；两值并排显示，较小变化也不会被误读成未生效。 */
@Composable
private fun SimulationDeltaMetric(
    label: String,
    before: Double?,
    after: Double?,
    afterTag: String,
    modifier: Modifier = Modifier,
) {
    // 卡片内的小指标块去描边，靠白底与浅蓝面板的对比分层，不加嵌套阴影。
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = AppInnerShape,
        modifier = modifier.height(68.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            PrivacyText(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PrivacyText(
                    scoreMetricText(before),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                PrivacyText("→", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivacyText(
                    scoreMetricText(after),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.testTag(afterTag),
                )
            }
        }
    }
}

@Composable
private fun ScoreSummaryPanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun ScoreMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = AppInnerShape,
        modifier = modifier.height(68.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            PrivacyText(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            PrivacyText(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun TermHeader(term: String, count: Int, expanded: Boolean = true, onToggle: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrivacyText(term, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        PrivacyText("${count} 门", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        // 折叠指示符：与课件页 ▸/▾ 一致；onToggle 为 null（不可折叠）时不显示
        if (onToggle != null) {
            PrivacyText(
                if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ScoreRecordCard(record: XmuScoreRecord) {
    PreviewCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(color = if (scoreIsExcellent(record)) themeSoftGreen() else themePrimarySoft(), shape = RoundedCornerShape(11.dp)) {
                // 徽章随内容自适应：等级制「优秀」「A-」等不会在固定 48dp 方框里被挤压。
                Box(
                    Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PrivacyText(record.score?.let(::scoreMetricText) ?: scoreResultText(record), fontWeight = FontWeight.Bold, color = if (scoreIsExcellent(record)) themeSuccess() else themePrimary())
                }
            }
            Column(Modifier.weight(1f)) {
                PrivacyText(record.courseName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PrivacyText(
                    "${scoreMetricText(record.credit)} 学分 · 绩点 ${scoreMetricText(record.gradePoint)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 等级制高等级文本（视觉上等同百分制 ≥90 分的绿色高亮）。 */
private val ExcellentGradeTexts = setOf("A+", "A", "A-", "优", "优秀")

/** 百分制 ≥90 或等级制拿到 A/优，都用成功色高亮；其余（含 P/NP）用主题色。
 *等级制补绩点后 countsForStatistics 会变 true，不能再拿它当
 *「是否等级制」的判据，否则 A 会失去绿色高亮。 */
fun scoreIsExcellent(record: XmuScoreRecord): Boolean =
    (record.score ?: 0.0) >= 90.0 || scoreResultText(record) in ExcellentGradeTexts

fun scoreMetricText(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "-"

fun scoreRecordDetail(record: XmuScoreRecord): String =
    if (record.score != null) {
        "成绩 ${scoreMetricText(record.score)} · 绩点 ${scoreMetricText(record.gradePoint)} · 学分 ${scoreMetricText(record.credit)}"
    } else {
        val result = scoreResultText(record)
        // 等级制补出的绩点也展示出来；中文五级制/P/NP 仍无绩点。
        val point = record.gradePoint?.let { " · 绩点 ${scoreMetricText(it)}" }.orEmpty()
        if (isTwoLevelScore(record)) {
            "成绩 $result$point · 学分 ${scoreMetricText(record.credit)}"
        } else {
            "成绩 $result · ${scoreGradeModeText(record)}$point · 学分 ${scoreMetricText(record.credit)}"
        }
    }

/** 成绩模式名不是成绩本身：旧缓存可能存了「等级制」，显示时降级为「—」。 */
private val gradeModeDisplayNames = setOf("百分制", "等级制", "五级制", "两级制", "非百分制", "P/NP")

fun scoreResultText(record: XmuScoreRecord): String {
    val rawResult = (record.resultText.ifBlank { record.gradeMode.ifBlank { "已记录" } })
        .takeUnless { it in gradeModeDisplayNames } ?: "—"
    return if (isTwoLevelScore(record)) {
        if (rawResult.equals("NP", ignoreCase = true) || !record.countsForCompletedCredit) "NP" else "P"
    } else {
        rawResult
    }
}

fun scoreGradeModeText(record: XmuScoreRecord): String =
    if (isTwoLevelScore(record)) "P/NP" else record.gradeMode.ifBlank { "非百分制" }

fun isTwoLevelScore(record: XmuScoreRecord): Boolean =
    record.gradeMode == "两级制" ||
        record.gradeMode.equals("P/NP", ignoreCase = true) ||
        record.resultText == "两级制"

@Composable
fun OptionRow(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    PrivacyText(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            if (selected == option) {
                Button(
                    onClick = { onSelected(option) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { PrivacyText(option) }
            } else {
                OutlinedButton(
                    onClick = { onSelected(option) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { PrivacyText(option) }
            }
        }
    }
}

@Composable
fun CoursewareItemCard(
    item: CoursewareUiItem,
    checked: Boolean,
    onToggle: () -> Unit,
    /**该课件曾成功落盘（持久化标记），显示「已下载」。 */
    downloaded: Boolean = false,
    /**「检查」扫到标记已下载、但本地目录里没有该文件：角标改「？已下载」暗橙字。 */
    missingLocal: Boolean = false,
) {
    val failed = item.downloadStatus.startsWith("下载失败")
    InfoCard(container = if (failed) themeFailedCard() else MaterialTheme.colorScheme.surface) {
        // 整行可点（toggleable）：只有 Checkbox 可点命中率太低，触控/无障碍都不达标。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .defaultMinSize(minHeight = 48.dp),
        ) {
            // 整行已是 toggleable(role=Checkbox)：内层 Checkbox 清除自身语义，
            // 避免读屏对同一勾选状态重复播报两次
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Column(modifier = Modifier.weight(1f)) {
                // 课件条目是课程的子项，标题不得比课程名（bodyMedium）更大；
                // 去掉 Bold，避免视觉上压过同字号的课程名。
                PrivacyText(item.title, style = MaterialTheme.typography.bodyMedium)
                if (item.moduleName.isNotBlank()) PrivacyText("章节：${item.moduleName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivacyText("文件名：${item.filename.ifBlank { "入口文件" }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivacyText("类型：${item.type}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PrivacyText(
                        "下载状态：${item.downloadStatus}",
                        style = MaterialTheme.typography.labelMedium,
                        color = coursewareStatusColor(item.downloadStatus),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloaded) {
                        // 「已下载」是完成态，用成功绿；原来的品牌深蓝与正文蓝色同族，
                        // 在浅底上一眼分不出「可下载」和「已下载」。
                        // 检查发现本地文件不在（被挪走/改名/删掉）时换成暗橙的「？已下载」，
                        // 提示这条标记不可靠；下载标记本身不动，重新下载或扫到文件即恢复。
                        // 靠右贴边，且末字「载」与课程行角标末字「条」同列。
                        // 实测量得（1200×2664 / 520dpi）：卡片内容右缘 1080px，角标「条」墨迹右缘 1026px，
                        // 差 54px ≈ 17dp —— 这里就用 17dp 右侧内缩把这 17dp 让出来。
                        PrivacyText(
                            if (missingLocal) "？已下载" else "✓ 已下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (missingLocal) themeOnOrange() else themeSuccess(),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 17.dp),
                        )
                    }
                }
                val reason = item.failureReason.ifBlank {
                    item.downloadStatus.substringAfter("下载失败（", "").substringBefore("）")
                }
                if (reason.isNotBlank() && failed) {
                    PrivacyText("失败原因：$reason", color = coursewareStatusColor(item.downloadStatus))
                }
            }
        }
    }
}

fun friendlyMessage(error: Throwable): String {
    // 类型优先：会话过期已在源头类型化（MainSessionExpiredException），
    // 消息匹配仅作兼容兜底
    if (error is MainSessionExpiredException) return "登录已过期，请重新登录"
    val message = error.message.orEmpty()
    return when (shortCoursewareError(message)) {
        ErrorCodes.LOGIN_EXPIRED -> "登录已过期，请重新登录"
        ErrorCodes.NETWORK_FAILURE -> "网络连接失败，请稍后重试"
        ErrorCodes.URL_MISSING -> "平台没有提供可用地址"
        else -> message.ifBlank { "操作失败" }
    }
}

fun friendlyNotificationMessage(error: Throwable, settings: NotificationSettings): String {
    val message = error.message.orEmpty()
    val lowered = message.lowercase()
    return when {
        message.contains("请先开启") -> message
        settings.pushPlusEnabled && (settings.pushPlusToken.isBlank() || "token" in lowered || "pushplus" in lowered) -> "Token 可能不正确"
        settings.qqMailEnabled && (
            settings.qqMailSender.isBlank() ||
                settings.qqMailPassword.isBlank() ||
                settings.qqMailRecipient.isBlank() ||
                listOf("smtp", "auth", "mail", "login").any { it in lowered }
            ) -> "QQ 邮箱授权码或 SMTP 配置可能有误"
        listOf("timeout", "connect", "network", "dns", "unknownhost").any { it in lowered } -> "网络连接失败"
        else -> "测试通知发送失败"
    }
}

private val courseYearPattern = Regex("""20\d{2}\s*[-/]\s*20\d{2}""")

fun courseYear(term: String): String? =
    courseYearPattern.find(term)?.value?.replace(" ", "")

fun courseSemesterLabel(semesterCode: String, term: String): String {
    val code = semesterCode.trim()
    if (code.endsWith("-3")) return "第三学期"
    if (code.endsWith("-2")) return "第二学期"
    if (code.endsWith("-1")) return "第一学期"
    // 兜底：老缓存没有 semesterCode 时，从学期名里猜
    val lowered = term.lowercase()
    return when {
        "第三" in term || "third" in lowered || "summer" in lowered -> "第三学期"
        "第二" in term || "second" in lowered || "spring" in lowered -> "第二学期"
        "第一" in term || "first" in lowered || "fall" in lowered || "autumn" in lowered -> "第一学期"
        else -> "全部"
    }
}

/** 课件下载状态色：深色模式下用更亮的变体，保证深底上的可读性。 */
@Composable
fun coursewareStatusColor(status: String): Color = when {
    status == COURSEWARE_STATUS_SUCCESS || status == COURSEWARE_STATUS_ENTRY_SAVED ->
        if (LocalXmuDarkTheme.current) Color(0xFF6FCF97) else Color(0xFF067647)
    status == COURSEWARE_STATUS_DOWNLOADING ->
        if (LocalXmuDarkTheme.current) Color(0xFF7FB5E8) else Color(0xFF0B4F8A)
    status.startsWith(COURSEWARE_STATUS_FAILED) || status == COURSEWARE_STATUS_LIMITED ->
        if (LocalXmuDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFB42318)
    else -> if (LocalXmuDarkTheme.current) Color(0xFF9FB0C0) else Color(0xFF55708A)
}

/**
 * 尝试通过系统「固定 Widget」流程把「今日课程」小卡片 pin 到桌面。
 *
 * 返回 true 表示已成功发起（系统确认框会/已弹出），false 表示当前环境根本不支持
 * [AppWidgetManager.requestPinAppWidget]（如 Android < P，或桌面明确不支持）。
 *
 * 不同厂商对第三方 pin 的态度差异很大，这里按「硬封禁 / 需权限 / 标准」三层处理：
 *
 * 1. 硬封禁（ColorOS 系 + vivo）：一加 / ColorOS 实测机型上，桌面
 *[com.android.launcher3.dragndrop.AddItemActivity.isAllowedAddWidget] 直接在
 *onCreate 里返回 false，[AppWidgetManager.requestPinAppWidget] 调用后 58ms 内
 *finish，既不弹窗也无回调，纯靠 API 永成功不了。这类直接引导用户走系统
 *「小部件」入口，不做注定失败的 pin 请求。
 *
 * 2. 需权限（小米 / Redmi 的 MIUI / HyperOS）：系统声称支持，但若应用没拿到
 *「桌面快捷方式」权限，[AppWidgetManager.requestPinAppWidget] 会「谎报成功、实际
 *静默失败」——AddItemActivity 立即 finish，既无回调也无法靠返回值判断。所以仍要
 *发起 pin，但由调用方延时校验成功与否；失败再回退到「去设置开权限」引导。
 *
 * 3. 标准（三星 / Pixel / 通用 AOSP / 华为 / 荣耀 / 其他）：直接发起 pin + 延时校验兜底。
 *
 * 不论哪一类，调用方都应做「延时校验 getAppWidgetIds 数量是否真的增加」来兜底，
 * 失败再回退手动指引或对应引导。
 */
private val PIN_BLOCKED_BRANDS = setOf(
    "oneplus", "oppo", "realme", "vivo",
)

/**
 * 这些厂商（小米 / Redmi 的 MIUI / HyperOS）支持第三方 pin，但前提是应用已获得
 * 「桌面快捷方式」权限；否则 [AppWidgetManager.requestPinAppWidget] 会「谎报成功、
 * 实际静默失败」，无法靠返回值判断。即便返回 true 仍要发起 pin + 延时校验；
 * 校验失败时引导用户去应用设置页开启「桌面快捷方式」权限。
 */
private val SHORTCUT_PERMISSION_BRANDS = setOf(
    "xiaomi", "redmi",
)

/**
 * 这些厂商的默认桌面会硬拒第三方 [AppWidgetManager.requestPinAppWidget]
 * （[com.android.launcher3.dragndrop.AddItemActivity.isAllowedAddWidget] 直接返回 false，
 * 实测一加 / ColorOS 机型即如此），标准 API 无法一键加卡片。对它们直接引导用户走系统
 * 「小部件」入口，而不是做注定失败的 pin 请求。
 */
fun knownPinBlockedOem(context: Context): Boolean {
    val brand = Build.BRAND?.lowercase() ?: ""
    return brand in PIN_BLOCKED_BRANDS
}

/**
 * 该设备是否走「需桌面快捷方式权限」的引导分支（小米 / Redmi）。
 * 注意：即便返回 true，仍应发起 pin 尝试并延时校验；仅当校验失败（缺权限）时，
 * 才回退到「去应用设置开权限」引导，省得用户白点一次。
 */
fun needsShortcutPermission(context: Context): Boolean {
    val brand = Build.BRAND?.lowercase() ?: ""
    return brand in SHORTCUT_PERMISSION_BRANDS
}

fun requestScheduleWidget(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val manager = AppWidgetManager.getInstance(context)
    val component = ComponentName(context, ScheduleWidgetProvider::class.java)
    if (!manager.isRequestPinAppWidgetSupported) return false
    return try {
        manager.requestPinAppWidget(component, null, null)
    } catch (_: Throwable) {
        false
    }
}

/**
 * 构造「按周解析本地调整」的回调，供组件投影使用。
 *
 * 组件数据（`ScheduleWidgetData` / `ScheduleWeekWidgetData`）只存投影结果，
 * 而调课、跨周搬课、单周停课都存在本机偏好里，投影时必须显式套一遍，
 * 否则桌面上永远显示教务原始安排。本学期一条调整都没有时直接返回 null，
 * 让投影走零开销的原路径。
 */
internal fun scheduleWidgetWeekResolver(
    context: Context,
    termCode: String,
): ScheduleWeekGroupResolver? {
    if (termCode.isBlank()) return null
    val overrides = ScheduleOverrideStore(context)
    val moves = ScheduleWeekMoveStore(context)
    if (!overrides.hasAny(termCode) && !moves.hasAny(termCode)) return null
    return { groups, week ->
        resolveWeekPairs(groups, termCode, week, overrides, moves).map { it.second }
    }
}

/**
 * 课表刷新成功后同步桌面 Widget 的「今日课程」摘要，并通知所有 Widget 更新。
 * 与课表页一致：反推/校准结果优先，官方表兜底；手动周次只是校准基准，
 * 校准成功后按日期自动推进，仅当校准反推失败时才以手动周次兜底。
 */
fun syncScheduleWidget(
    context: Context,
    entries: List<XmuScheduleEntry>,
    termCode: String,
    inferredCalendar: XmuAcademicCalendar? = null,
    manualWeek: Int = 0,
) {
    val calendar = inferredCalendar ?: xmuAcademicCalendarForTerm(termCode)
    // 手动周次只是校准基准：有反推日历时不参与，避免两套周次互相打架。
    val effectiveManualWeek = if (inferredCalendar == null) manualWeek else 0
    // 本地调课 / 搬课也要进组件，否则联网刷新后又回到教务原始安排。
    val resolveWeek = scheduleWidgetWeekResolver(context, termCode)
    val days = projectScheduleWidgetDays(
        entries, termCode, calendar,
        manualWeek = effectiveManualWeek,
        resolveWeek = resolveWeek,
    )
    ScheduleWidgetData.saveUpcoming(context, days)
    ScheduleWidgetProvider.refreshAll(context)
    // 周课表小组件一次投影整学期各周，供桌面左右箭头翻周；当前周仍落在 KEY_JSON。
    val weekBundle = projectScheduleWeekWidgetBundle(
        entries, termCode, calendar,
        manualWeek = effectiveManualWeek,
        resolveWeek = resolveWeek,
    )
    val currentWeek = if (effectiveManualWeek in 1..(calendar?.totalWeeks ?: 19)) {
        effectiveManualWeek
    } else {
        xmuAcademicWeekFor(calendar, java.time.LocalDate.now()).week ?: 0
    }
    ScheduleWeekWidgetData.saveBundle(context, weekBundle, currentWeek)
    ScheduleWeekWidgetProvider.refreshAll(context)
}

/**
 * 不联网、只按「缓存课表 + 本地调整」重投两个桌面组件。
 *
 * 调课 / 修改安排 / 还原 / 单周停课改的都是本机数据，不会触发教务刷新，
 * 此前这些动作做完后桌面上要等下一次联网同步才更新（大多数时候永远不更新）。
 * 现在这几个提交点做完就立刻重投，桌面上看到的就是最新安排。
 */
internal fun refreshScheduleWidgetsFromCache(context: Context) {
    val appContext = context.applicationContext
    val snapshot = runCatching { loadScheduleSnapshotFromFile(appContext) }.getOrNull()
        ?: return
    if (snapshot.entries.isEmpty()) return
    val termCode = snapshot.termCode
    val settings = runCatching { AssistantSettings(appContext) }.getOrNull() ?: return
    val inferred = snapshot.inferredCalendars[termCode]
    val calendar = inferred ?: xmuAcademicCalendarForTerm(termCode)
    val manualWeek = settings.manualAcademicWeek(termCode)
    // 与 syncScheduleWidget 同一口径：有反推日历时不拿手动周次参与。
    val effectiveManualWeek = if (inferred == null) manualWeek else 0
    val resolveWeek = scheduleWidgetWeekResolver(appContext, termCode)
    val days = projectScheduleWidgetDays(
        snapshot.entries, termCode, calendar,
        manualWeek = effectiveManualWeek,
        resolveWeek = resolveWeek,
    )
    ScheduleWidgetData.saveUpcoming(appContext, days)
    ScheduleWidgetProvider.refreshAll(appContext)
    val totalWeeks = calendar?.totalWeeks ?: 19
    val weekBundle = projectScheduleWeekWidgetBundle(
        snapshot.entries, termCode, calendar,
        manualWeek = effectiveManualWeek,
        resolveWeek = resolveWeek,
    )
    val currentWeek = if (effectiveManualWeek in 1..totalWeeks) {
        effectiveManualWeek
    } else {
        xmuAcademicWeekFor(calendar, java.time.LocalDate.now()).week ?: 0
    }
    // 编辑是局部改动，不要把用户在桌面翻到的周次打回当前周。
    ScheduleWeekWidgetData.saveBundle(appContext, weekBundle, currentWeek, resetViewWeek = false)
    ScheduleWeekWidgetProvider.refreshAll(appContext)
}
