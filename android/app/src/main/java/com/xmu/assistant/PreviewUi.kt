package com.xmu.assistant

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 *统一卡片语言 —— 18dp 大圆角 + 极柔和阴影，取代 1dp 硬描边。
 * 卡片内的小块（指标、徽章）用 14dp 圆角且不加阴影，避免嵌套阴影显脏。
 */
internal val AppCardShape = RoundedCornerShape(18.dp)
internal val AppInnerShape = RoundedCornerShape(14.dp)
/**降到 1dp。 */
internal val AppCardElevation: Dp = 1.dp

/** Shared implementation of the preview card language. */
@Composable
internal fun PreviewCard(
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    spacing: Dp = 10.dp,
    // 单张卡需要更强阴影时按卡片覆写，全局默认仍为 1dp。
    shadowElevation: Dp = AppCardElevation,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth()
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
    // 「卡片整块显示异常」的修复：soft 卡原先用半透明 surfaceVariant(.58f)，
    // 叠加 shadowElevation 后阴影会透过半透明底色显出来，整块发灰/像黑块。
    // 改为不透明底色，并显式指定 contentColor，杜绝 contentColorFor 的异常推断。
    val cardColor = if (soft) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface
    val cardContentColor = MaterialTheme.colorScheme.onSurface
    if (onClick == null) {
        Surface(
            modifier = base,
            color = cardColor,
            contentColor = cardContentColor,
            shape = AppCardShape,
            shadowElevation = shadowElevation,
            tonalElevation = 0.dp,
            content = body,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = base,
            color = cardColor,
            contentColor = cardContentColor,
            shape = AppCardShape,
            shadowElevation = shadowElevation,
            tonalElevation = 0.dp,
            content = body,
        )
    }
}

@Composable
internal fun PreviewSectionHeader(title: String, trailing: String = "") {
    // 分组标题加一小段主色竖条，与顶部栏标题形成同一套设计语言。
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .background(themePrimary(), RoundedCornerShape(999.dp)),
        )
        PrivacyText(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (trailing.isNotBlank()) {
            PrivacyText(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal enum class PreviewBadgeTone { BLUE, GREEN, AMBER, RED }

@Composable
internal fun PreviewBadge(text: String, tone: PreviewBadgeTone = PreviewBadgeTone.BLUE) {
    val background: Color
    val foreground: Color
    when (tone) {
        PreviewBadgeTone.GREEN -> {
            background = themeSoftGreen()
            foreground = themeSuccess()
        }
        PreviewBadgeTone.RED -> {
            background = MaterialTheme.colorScheme.errorContainer
            foreground = MaterialTheme.colorScheme.error
        }
        PreviewBadgeTone.AMBER -> {
            background = themeSoftAmber()
            foreground = themeOnAmber()
        }
        PreviewBadgeTone.BLUE -> {
            background = themePrimarySoft()
            foreground = themePrimary()
        }
    }
    Surface(color = background, shape = RoundedCornerShape(999.dp)) {
        PrivacyText(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**containerColor / iconTint 可覆盖配色，供更多页「偏好设置」组用中性灰底 + 深色图标。 */
@Composable
internal fun PreviewIconBox(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = themePrimarySoft(),
    iconTint: Color = themePrimary(),
) {
    Surface(modifier = modifier.size(42.dp), color = containerColor, shape = AppInnerShape) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
internal fun PreviewListRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {
        PrivacyText("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PreviewIconBox(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PrivacyText(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PrivacyText(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
internal fun PreviewMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = AppInnerShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PrivacyText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrivacyText(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
