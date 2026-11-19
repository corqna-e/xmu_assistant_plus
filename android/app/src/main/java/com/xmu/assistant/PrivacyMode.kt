package com.xmu.assistant

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * 隐私模式（v92 重做）：
 *
 * 1. 屏幕顶部大标题（[BrandHeader] 的标题）在二三级页逐字换成随机字母，
 *字母个数等于原标题字数（「课表」→ 两个随机字母）；一级页不遮蔽，首页显示「首页」。
 *该行为可在规则弹窗里单独关闭（[PrivacyModeState.titleMasked]，v108）；
 * 2. 全 App 任意位置的敏感词按 [PRIVACY_SUBSTITUTIONS] 换词（签到 → 前导、成绩 → 乘积、
 *监控 → 查看、雷达 → 类沓、考试 → 测评、考场 → 地点、排名 → 座位、绩点 → 机电、课 → ？），
 *桌面小组件（RemoteViews）不在范围内；
 * 3. 被改动的文字**配色与字重全部沿用原样式**，不做任何颜色/字号改动。
 *
 * 实现方式：开关值走 [LocalPrivacyMode]，所有界面文本最终走 [PrivacyText]。
 * 开关落 [AssistantSettings.privacyMode]，内存副本在 [PrivacyModeState]。
 */

/**
 * 一条替换规则：原文 [from] → 目标词 [to]，[enabled] 为 false 时该词保持原样。
 * 规则集可由用户在「外观 - 隐私模式 - ?」里逐条开关、改目标词，也可一键恢复默认。
 */
data class PrivacyRule(
    val from: String,
    val to: String,
    val enabled: Boolean = true,
)

/** 默认换词表（顺序即弹窗里的展示顺序，单字项放最后）。 */
internal val DEFAULT_PRIVACY_RULES: List<PrivacyRule> = listOf(
    PrivacyRule("签到", "前导"),
    PrivacyRule("成绩", "乘积"),
    PrivacyRule("监控", "查看"),
    PrivacyRule("雷达", "类沓"),
    PrivacyRule("考试", "测评"),
    PrivacyRule("考场", "地点"),
    PrivacyRule("排名", "座位"),
    PrivacyRule("绩点", "机电"),
    // 课程类字样一律遮蔽成问号（课表→？表、课件→？件）。桌面小组件走
    // RemoteViews，不经过 PrivacyText，因此在范围外。
    // 默认关闭 —— 课程类字样出现的频率最高，默认不遮蔽，可在规则弹窗里逐条打开。
    PrivacyRule("课", "？", enabled = false),
)

/** 隐私模式开关，由 [XmuMobileTheme] 下发；默认关闭，行为与旧版一致。 */
val LocalPrivacyMode = compositionLocalOf { false }

/**
 * 隐私模式全局状态。
 *
 * 开关住在外观页（三级界面），读取点却在整棵界面树上；为免把开关
 * 一路透传 MainActivity → MainScreen 的参数表，值挂在进程单例上，
 * 由 [XmuMobileTheme] 读取后下发到 [LocalPrivacyMode]（读到即为订阅）。
 * 内存值是唯一事实来源，prefs 只负责重启后恢复。
 */
internal object PrivacyModeState {
    var enabled by mutableStateOf(false)
        private set

    /** 当前生效的规则集，读取即订阅（改了目标词，全 App 文本立即重排）。 */
    var rules by mutableStateOf(DEFAULT_PRIVACY_RULES)
        private set

    /**
     * 顶部大标题是否替换为随机字母（v108）。默认开；关掉后二三级页标题原样显示，
     * 与换词规则互不影响。读取即订阅，开关改动后顶部标题立即重绘。
     */
    var titleMasked by mutableStateOf(true)
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    fun updateRules(list: List<PrivacyRule>) {
        rules = list
    }

    fun updateTitleMasked(value: Boolean) {
        titleMasked = value
    }

    fun loadTitleMasked(value: Boolean) {
        titleMasked = value
    }

    /** 恢复默认：词表、标题替换开关与隐私总开关之外的选项一起回到出厂设置。 */
    fun resetRules() {
        rules = DEFAULT_PRIVACY_RULES
        titleMasked = true
    }

    /** 从 prefs 里的 JSON 恢复；解析失败或为空则回默认。 */
    fun loadRules(json: String?) {
        rules = decodePrivacyRules(json)
    }
}

/** 规则集序列化：`[{"f":"签到","t":"前导","e":true}, …]`。 */
internal fun encodePrivacyRules(list: List<PrivacyRule>): String {
    val arr = org.json.JSONArray()
    list.forEach { rule ->
        arr.put(
            org.json.JSONObject()
                .put("f", rule.from)
                .put("t", rule.to)
                .put("e", rule.enabled),
        )
    }
    return arr.toString()
}

/**
 * 规则集反序列化。始终以默认词表的词为骨架：存档里缺的词补默认值，
 * 这样以后新增默认词也能被老存档继承；存档里多出来的词忽略。
 */
internal fun decodePrivacyRules(json: String?): List<PrivacyRule> {
    if (json.isNullOrBlank()) return DEFAULT_PRIVACY_RULES
    val stored = runCatching {
        val arr = org.json.JSONArray(json)
        buildMap {
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index) ?: continue
                val from = item.optString("f")
                if (from.isEmpty()) continue
                put(
                    from,
                    PrivacyRule(
                        from = from,
                        to = item.optString("t", ""),
                        enabled = item.optBoolean("e", true),
                    ),
                )
            }
        }
    }.getOrNull() ?: return DEFAULT_PRIVACY_RULES
    if (stored.isEmpty()) return DEFAULT_PRIVACY_RULES
    return DEFAULT_PRIVACY_RULES.map { rule ->
        stored[rule.from]?.let { saved ->
            rule.copy(to = saved.to.ifEmpty { rule.to }, enabled = saved.enabled)
        } ?: rule
    }
}

/** 随机字母的下标上限（A..Z）。 */
private const val TITLE_LETTER_COUNT = 26

/**
 * 顶部大标题取值：隐私模式开启、且标题替换开关未关时，逐字符换成随机字母，
 * 字母个数等于原标题字数（空白保留）；否则原样返回。
 *
 *随机源用 `title.hashCode` 做种子：同一标题每次重组、每次重启都得到同一串字母，
 * 否则页面一滚动字母就跳变，看着像故障。
 */
fun privacyTitle(title: String, privacy: Boolean): String {
    // 标题替换也可单独关闭（规则弹窗里的开关）。此处读取 state 以便重绘。
    if (!privacy || !PrivacyModeState.titleMasked) return title
    val random = kotlin.random.Random(title.hashCode())
    return buildString(title.length) {
        title.forEach { ch ->
            if (ch.isWhitespace()) {
                append(ch)
            } else {
                append('A' + random.nextInt(TITLE_LETTER_COUNT))
            }
        }
    }
}

/**
 * 纯函数：按 [rules] 做敏感词替换（只认 enabled 且未被 [skipRules] 排除的词），
 * 关闭隐私或空串时原样返回。
 */
fun privacySubstituted(
    text: String,
    privacy: Boolean,
    rules: List<PrivacyRule> = PrivacyModeState.rules,
    skipRules: Set<String> = emptySet(),
): String {
    if (!privacy || text.isEmpty()) return text
    val active = rules.filter { it.enabled && it.to.isNotEmpty() && it.from !in skipRules }
    if (active.isEmpty()) return text
    val replaced = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val hit = active.firstOrNull { rule -> text.startsWith(rule.from, i) }
        if (hit != null) {
            replaced.append(hit.to)
            i += hit.from.length
        } else {
            replaced.append(text[i])
            i++
        }
    }
    return replaced.toString()
}

/**
 * 带隐私处理的文本：签名与 material3.Text（String 版）一致，
 * 因此各页面把 `Text(...)` 换成 `PrivacyText(...)` 即为全部改动。
 * 只换文字，不碰颜色、字号、字重等任何样式参数。
 */
@Composable
fun PrivacyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    // 个别位置要保留某个词原样（如底栏「课表」的「课」），按原文词排除。
    skipRules: Set<String> = emptySet(),
) {
    val privacy = LocalPrivacyMode.current
    if (!privacy) {
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = style,
        )
        return
    }

    val shown = remember(text, skipRules, PrivacyModeState.rules) {
        privacySubstituted(text, privacy = true, rules = PrivacyModeState.rules, skipRules = skipRules)
    }
    // 无替换时无需再走一遍组合，直接原样渲染。
    if (shown == text) {
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = style,
        )
        return
    }

    androidx.compose.material3.Text(
        text = shown,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}
