package com.xmu.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/** 教学评价系统的入口地址（与「厦大教评助手」扩展一致，该校全部评价页都在此域名下）。 */
private const val EVALUATION_HOME_URL = "https://iqa.xmu.edu.cn/"

/**
 *教评助手。
 *
 * 目前只做「入口 + 使用说明」，不做自动提交：原浏览器扩展是靠操作页面按钮完成评价的，
 * 它没有使用后端接口，所以无法直接搬到 App 后台批量。要变成后台自动化，需要先拿到
 * 一次真实提交请求的格式（接口地址 / 参数），这一步留到评价系统开放后再做。
 */
@Composable
internal fun EvaluationHelperPage(show: (String) -> Unit) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                    PrivacyText(
                        "教评",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PrivacyText("教学评价", fontWeight = FontWeight.Bold)
                    PrivacyText(
                        "入口与使用说明 · 当前为手动评价",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PreviewBadge("未开放自动化", PreviewBadgeTone.AMBER)
            }
            PrivacyText(
                "教学评价位于独立系统（iqa.xmu.edu.cn），需单独登录一次。此处提供直达入口，" +
                    "评价过程仍由用户自行完成，所有评价均应如实填写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = { openEvaluationSystem(context, show) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
        ) { PrivacyText("打开教学评价系统") }

        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("教评系统", EVALUATION_HOME_URL))
                show("网址已复制，可粘贴到浏览器打开")
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
        ) { PrivacyText("复制网址") }

        PreviewSectionHeader("使用方法")
        PreviewCard(soft = true) {
            StepRow("01", "登录", "用校园统一身份认证登录，首次进入会要求补充确认。")
            StepRow("02", "选择课程", "按本学期开课列表逐门进入评价问卷。")
            StepRow("03", "如实填写并提交", "问卷多为选择题，个别课程附带文字评语，建议写具体建议。")
            StepRow("04", "确认提交成功", "提交后回到列表，该课程会标记为已评价。")
        }

        PreviewSectionHeader("关于自动化")
        PreviewCard(soft = true) {
            PrivacyText(
                "尚未提供「一键评价」的原因",
                fontWeight = FontWeight.Bold,
            )
            PrivacyText(
                "网上流传的教评脚本（包括此前使用的浏览器扩展）均通过模拟点击网页按钮实现，" +
                    "未调用后端接口。课表、成绩可后台自动刷新，是因为对应功能直接请求接口。" +
                    "实现同样的后台批量功能，需先记录一次真实的提交请求，该步骤有待评价系统开放后配合完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PreviewCard(soft = true) {
            PrivacyText(
                "提醒：评价结果会影响教师考核，请如实填写，避免全部选择同一选项。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StepRow(index: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            PrivacyText(
                index,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PrivacyText(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            PrivacyText(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 用系统浏览器打开教评系统；没有可用的浏览器时退回复制网址，避免点了没反应。 */
private fun openEvaluationSystem(context: Context, show: (String) -> Unit) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(EVALUATION_HOME_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("教评系统", EVALUATION_HOME_URL))
        show("没有可用的浏览器，网址已复制")
    }
}
