package com.xmu.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.graphics.Color
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb

/**
 *周课表桌面小组件的 Provider。
 *
 * 与「今日课程」小组件相互独立：同一个课表快照源，但版式、尺寸策略、空态都单独一套。
 * 尺寸变化（拖动缩放）时按新的宽高重新用 Canvas 绘制一次位图。
 */
class ScheduleWeekWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateWidgets(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        updateWidgets(context, manager, intArrayOf(id))
    }

    /**顶栏左右箭头 / 看今天按钮点击。 */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 「打开课表」由广播中转后才真正启动应用，组件里不挂 getActivity 意图。
        if (intent.action == WidgetDetailOverlay.ACTION_OPEN_SCHEDULE) {
            WidgetDetailOverlay.openSchedule(context, intent)
            return
        }
        // SHOW 延后 0.5s 绘制，先 goAsync() 撑住进程到延迟任务执行完。
        val deferred = if (WidgetDetailOverlay.isDeferredShow(intent)) goAsync() else null
        // 课程详情覆盖层的展开 / 收起 —— 组件内部重绘，不启动任何界面。
        // 只重绘覆盖层这一层，课表网格不重建。
        if (WidgetDetailOverlay.handle(context, intent, deferred) { ctx, detail ->
                applyOverlayState(ctx, detail)
            }
        ) return
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT, ACTION_TODAY -> {
                val bundle = ScheduleWeekWidgetData.loadBundle(context)
                if (bundle.isEmpty()) return
                val current = currentWeekOf(context, bundle)
                val weeks = bundle.map { it.week }.sorted()
                val nowView = ScheduleWeekWidgetData.getViewWeek(context).takeIf { it in weeks }
                    ?: current
                val nextView = when (intent.action) {
                    ACTION_TODAY -> 0
                    ACTION_PREV -> {
                        val idx = weeks.indexOf(nowView).coerceAtLeast(0)
                        weeks[(idx - 1).coerceAtLeast(0)]
                    }
                    ACTION_NEXT -> {
                        val idx = weeks.indexOf(nowView).coerceAtLeast(0)
                        weeks[(idx + 1).coerceAtMost(weeks.lastIndex)]
                    }
                    else -> nowView
                }
                ScheduleWeekWidgetData.setViewWeek(context, nextView)
                refreshAll(context)
            }
        }
    }

    companion object {
        /**七天的表头控件、时间列、列容器。顺序固定，索引即星期。 */
        private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
        private val HEAD_CELL_IDS = intArrayOf(
            R.id.week_head_cell_0, R.id.week_head_cell_1, R.id.week_head_cell_2, R.id.week_head_cell_3,
            R.id.week_head_cell_4, R.id.week_head_cell_5, R.id.week_head_cell_6,
        )
        private val HEAD_WEEKDAY_IDS = intArrayOf(
            R.id.week_head_w_0, R.id.week_head_w_1, R.id.week_head_w_2, R.id.week_head_w_3,
            R.id.week_head_w_4, R.id.week_head_w_5, R.id.week_head_w_6,
        )
        private val HEAD_DATE_IDS = intArrayOf(
            R.id.week_head_d_0, R.id.week_head_d_1, R.id.week_head_d_2, R.id.week_head_d_3,
            R.id.week_head_d_4, R.id.week_head_d_5, R.id.week_head_d_6,
        )
        private val COLUMN_IDS = intArrayOf(
            R.id.week_col_0, R.id.week_col_1, R.id.week_col_2,
            R.id.week_col_3, R.id.week_col_4, R.id.week_col_5, R.id.week_col_6,
        )
        private val TIME_IDS = intArrayOf(
            R.id.week_time_1, R.id.week_time_2, R.id.week_time_3, R.id.week_time_4,
            R.id.week_time_5, R.id.week_time_6, R.id.week_time_7, R.id.week_time_8,
            R.id.week_time_9, R.id.week_time_10, R.id.week_time_11,
        )
        /**左侧节次列的节号，与 [TIME_IDS] 一一对应（同格上下两行）。 */
        private val TIME_SECTION_IDS = intArrayOf(
            R.id.week_time_s_1, R.id.week_time_s_2, R.id.week_time_s_3, R.id.week_time_s_4,
            R.id.week_time_s_5, R.id.week_time_s_6, R.id.week_time_s_7, R.id.week_time_s_8,
            R.id.week_time_s_9, R.id.week_time_s_10, R.id.week_time_s_11,
        )

        /** 课程块布局：跨 N 个节次用第 N 个（layout_weight=N）。 */
        private val BLOCK_LAYOUT_IDS = intArrayOf(
            R.layout.schedule_week_block_1, R.layout.schedule_week_block_2,
            R.layout.schedule_week_block_3, R.layout.schedule_week_block_4,
            R.layout.schedule_week_block_5, R.layout.schedule_week_block_6,
            R.layout.schedule_week_block_7, R.layout.schedule_week_block_8,
            R.layout.schedule_week_block_9, R.layout.schedule_week_block_10,
            R.layout.schedule_week_block_11,
        )
        /** 课程块左侧色条，与 SCHEDULE_COLOR_PALETTE 一一对应；
         *底色（浅/深两套）由 [WidgetPalette.blockFills] 按主题提供。 */
        private val BLOCK_BAR_IDS = intArrayOf(
            R.drawable.week_block_bar_0, R.drawable.week_block_bar_1, R.drawable.week_block_bar_2,
            R.drawable.week_block_bar_3, R.drawable.week_block_bar_4, R.drawable.week_block_bar_5,
        )

        const val ACTION_PREV = "com.xmu.assistant.weekwidget.PREV"
        const val ACTION_NEXT = "com.xmu.assistant.weekwidget.NEXT"
        const val ACTION_TODAY = "com.xmu.assistant.weekwidget.TODAY"

        /**
         * 当前教学周的判定顺序 —— 先读同步时落库的值，再退回按周一的 epochDay 比对。
         * 之前只有 epochDay 一种判据，学期日历一日偏差就会整周匹配不上，
         * 兜底又落到「最后一周」，桌面上这张卡片就成了空白。
         */
        private fun currentWeekOf(context: Context, bundle: List<ScheduleWeekWidgetSnapshot>): Int {
            val stored = ScheduleWeekWidgetData.getCurrentWeek(context)
            if (stored in 1..30 && bundle.any { it.week == stored }) return stored
            val today = java.time.LocalDate.now()
            val monday = today.minusDays(today.dayOfWeek.value - 1L).toEpochDay()
            return bundle.firstOrNull { it.mondayEpochDay == monday }?.week
                ?: bundle.firstOrNull { it.week > 0 }?.week
                ?: 0
        }

        /**覆盖层开 / 合只推「覆盖层动作」，并且走 `partiallyUpdateAppWidget`。
         *不整块重建（7 列网格与课程块一个都不动）、不发多尺寸集合（否则桌面会重排版式并
         *播放重排动画），点击时卡片不再弹一下。 */
        private fun applyOverlayState(context: Context, detail: WidgetCourseDetail?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWeekWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = WidgetDetailOverlay.stateViews(
                context, R.layout.schedule_week_widget, detail,
                ScheduleWeekWidgetProvider::class.java, WidgetDetailOverlay.SOURCE_WEEK,
            )
            ids.forEach { id -> runCatching { manager.partiallyUpdateAppWidget(id, views) } }
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWeekWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateWidgets(context, manager, ids)
        }

        /** 桌面小部件列表里也允许直接添加：返回是否成功发起系统「添加」请求。 */
        fun requestPin(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            return runCatching {
                manager.requestPinAppWidget(ComponentName(context, ScheduleWeekWidgetProvider::class.java), null, null)
            }.getOrDefault(false)
        }

        private fun updateWidgets(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val bundle = ScheduleWeekWidgetData.loadBundle(context)
            val currentWeek = currentWeekOf(context, bundle)
            val storedView = ScheduleWeekWidgetData.getViewWeek(context)
            // 翻到的周必须真的有这一周的记录，否则退回当前周 —— 免得整张卡片空着。
            val viewWeek = if (storedView in 1..30 && bundle.any { it.week == storedView }) storedView else currentWeek
            // 考试（教务 + 自定义）也要上图：周课表快照只有课程，渲染前按周补一次。
            val (exams, customExams) = loadExamsForWidget(context)
            val snapshot = (bundle.firstOrNull { it.week == viewWeek }
                ?: bundle.firstOrNull { it.week == currentWeek }
                ?: bundle.firstOrNull())?.withExams(exams, customExams)
            // 只有「跟随当前周」时才做日期新鲜度校验；主动翻到的周不校验。
            val renderWeek = if (viewWeek == currentWeek) 0 else viewWeek
            val today = java.time.LocalDate.now()
            val titleText = snapshot?.takeIf { it.week in 1..30 }?.let { "第${it.week}周" }
                ?: "${today.monthValue}/${today.dayOfMonth}"
            // 结构完全由权重分配（11 节次等分高、7 天等分宽），与卡片像素尺寸无关，
            // 因此一次构建即可供所有实例共用 —— 不再推断 min/max/尺寸档，也不缩放任何内容。
            val views = runCatching {
                buildWeekViews(
                    context, snapshot, renderWeek, titleText,
                    // 组件内当前展开的课程详情（null 表示正常显示课表）。
                    WidgetDetailOverlay.load(context, WidgetDetailOverlay.SOURCE_WEEK),
                )
            }.getOrNull() ?: return
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, views) } }
            // 记下这一版用的是深色还是浅色：系统切换深色时据此判断要不要重画。
            WidgetThemeState.setRenderedDark(context, widgetDark(context))
        }

        /**
         *把 11 个节次 × 7 天铺进卡片。
         *
         * 为什么不用 Canvas 位图：位图必须知道卡片的确切像素尺寸才能 1:1 铺满，
         * 而桌面只上报一个 min/max 区间（本机 206/284，实际卡片比两者都高）。
         * 比例对不上时 fitCenter 一侧留白、fitXY 把字拉扁。
         * 现在高度/宽度全部由 layout_weight 分配，任何尺寸都自然铺满且零缩放。
         */
        private fun buildWeekViews(
            context: Context,
            snapshot: ScheduleWeekWidgetSnapshot?,
            renderWeek: Int,
            titleText: String,
            detail: WidgetCourseDetail? = null,
        ): RemoteViews {
            val today = java.time.LocalDate.now()
            // 主题跟随 App 内「外观」设置，而不是系统 uiMode；颜色逐个控件显式写入。
            val dark = widgetDark(context)
            val palette = widgetPalette(context, dark)
            val views = RemoteViews(context.packageName, R.layout.schedule_week_widget)
            views.setInt(R.id.week_widget_root, "setBackgroundResource", palette.cardBackground)
            views.setTextColor(R.id.week_nav_title_left, palette.primary)
            views.setTextColor(R.id.week_nav_title, palette.accent)
            views.setTextColor(R.id.week_nav_prev, palette.accent)
            views.setTextColor(R.id.week_nav_next, palette.accent)
            views.setTextColor(R.id.week_month, palette.accent)
            views.setTextColor(R.id.week_empty, palette.secondary)
            TIME_SECTION_IDS.forEach { views.setTextColor(it, palette.accent) }
            TIME_IDS.forEach { views.setTextColor(it, palette.secondary) }
            views.setTextViewText(R.id.week_nav_title, titleText)
            views.setTextViewText(R.id.week_nav_title_left, context.getString(R.string.widget_week_title))
            // 已经在「本周」时「看今天」变灰（不可再点），翻到别的周时用主色提示可返回。
            views.setTextColor(
                R.id.week_nav_today,
                if (renderWeek == 0) palette.secondary else palette.accent,
            )
            // 点课程块 → 组件内展开该课详情。
            // 不再给整卡（week_widget_root）挂点击 —— 整卡一段 PendingIntent 会被桌面当成
            // 「整张卡片可按压」，按下即整卡缩放回弹。
            // 卡片空白处点击现在完全不响应；进 App 只走详情卡里的「打开课表」。
            runCatching {
                views.setOnClickPendingIntent(R.id.week_nav_prev, actionPendingIntent(context, ACTION_PREV, 2202))
                views.setOnClickPendingIntent(R.id.week_nav_next, actionPendingIntent(context, ACTION_NEXT, 2203))
                views.setOnClickPendingIntent(R.id.week_nav_today, actionPendingIntent(context, ACTION_TODAY, 2204))
            }
            views.setContentDescription(R.id.week_widget_root, weekWidgetDescription(snapshot))

            // —— 空态：整张卡片换成一段说明，表格整体隐藏 ——
            // 无论从哪条分支返回，都先把内容画完，再把详情覆盖层叠上去。
            fun finish(): RemoteViews {
                applyDetailOverlay(
                    views, context, palette, detail,
                    ScheduleWeekWidgetProvider::class.java, WidgetDetailOverlay.SOURCE_WEEK,
                )
                return views
            }
            fun showEmpty(message: String): RemoteViews {
                views.setViewVisibility(R.id.week_body, View.GONE)
                views.setViewVisibility(R.id.week_empty, View.VISIBLE)
                views.setTextViewText(R.id.week_empty, message)
                return finish()
            }
            if (snapshot == null) return showEmpty("课表还未同步\n打开课表刷新后即可查看")
            // 只有「跟随当前周」时才做日期新鲜度校验；主动翻到的周不校验。
            if (renderWeek == 0 && !ScheduleWeekWidgetData.isCurrentWeek(snapshot, today)) {
                return showEmpty("周课表待更新\n点击打开课表刷新")
            }
            if (snapshot.days.all { it.isEmpty() }) return showEmpty("本周没有课\n好好休息")
            views.setViewVisibility(R.id.week_body, View.VISIBLE)
            views.setViewVisibility(R.id.week_empty, View.GONE)

            // —— 表头：月份 + 七格「星期 / 日号」，今天那一格加圆角底片 ——
            views.setTextViewText(R.id.week_month, "${today.monthValue}月")
            val monday = runCatching { java.time.LocalDate.ofEpochDay(snapshot.mondayEpochDay) }.getOrNull()
            val highlightToday = renderWeek == 0
            val todayColumn = today.dayOfWeek.value
            val secondary = palette.secondary
            val onChip = palette.onChip
            for (index in 0 until 7) {
                val isToday = highlightToday && index + 1 == todayColumn
                views.setTextViewText(HEAD_WEEKDAY_IDS[index], WEEKDAY_LABELS[index])
                val dayOfMonth = monday?.plusDays(index.toLong())?.dayOfMonth ?: (index + 1)
                views.setTextViewText(HEAD_DATE_IDS[index], dayOfMonth.toString())
                views.setInt(
                    HEAD_CELL_IDS[index], "setBackgroundResource",
                    if (isToday) palette.todayChip else 0,
                )
                views.setTextColor(HEAD_WEEKDAY_IDS[index], if (isToday) onChip else secondary)
                views.setTextColor(HEAD_DATE_IDS[index], if (isToday) onChip else secondary)
            }

            // —— 左侧节次列：节号在上、起始时间在下，11 格等分高度 ——
            val windows = officialSectionWindows()
            for (section in 1..11) {
                views.setTextViewText(TIME_SECTION_IDS[section - 1], section.toString())
                val start = windows[section]?.first
                views.setTextViewText(TIME_IDS[section - 1], start?.let { formatMinuteOfDay(it) }.orEmpty())
            }

            // —— 7 天：空格子 weight=1，课程块 weight=跨度，两者加起来必须正好 11 份 ——
            val accent = palette.accent
            for (day in 0 until 7) {
                val columnId = COLUMN_IDS[day]
                views.removeAllViews(columnId)
                val isToday = highlightToday && day + 1 == todayColumn
                // 今天的整列铺一层极淡的主色，表头底片与之呼应。
                views.setInt(
                    columnId, "setBackgroundColor",
                    if (isToday) {
                        Color.argb(12, Color.red(accent), Color.green(accent), Color.blue(accent))
                    } else {
                        Color.TRANSPARENT
                    },
                )
                var row = 1
                val courses = snapshot.days.getOrNull(day).orEmpty()
                    // 重叠时考试优先占格：考试占的就是那一格时间，
                    // 课程被跳过总比考试完全不显示更容易被接受。
                    .sortedWith(compareBy({ it.startSection }, { if (it.isExam) 0 else 1 }))
                for (course in courses) {
                    val start = course.startSection.coerceIn(1, 11)
                    val end = course.endSection.coerceIn(start, 11)
                    if (end < row) continue // 与已排入的课程重叠，跳过
                    while (row < start && row <= 11) {
                        views.addView(columnId, RemoteViews(context.packageName, R.layout.schedule_week_grid_gap))
                        row++
                    }
                    val span = (end - row + 1).coerceIn(1, 12 - row)
                    views.addView(
                        columnId,
                        weekBlockViews(context, course, span, dark, palette),
                    )
                    row += span
                }
                while (row <= 11) {
                    views.addView(columnId, RemoteViews(context.packageName, R.layout.schedule_week_grid_gap))
                    row++
                }
            }
            return finish()
        }

        /**单个课程块。跨节次数量决定用哪套权重布局与显示几行文字。 */
        private fun weekBlockViews(
            context: Context,
            course: ScheduleWidgetCourse,
            span: Int,
            dark: Boolean,
            palette: WidgetPalette,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, BLOCK_LAYOUT_IDS[span - 1])
            // 色号优先用快照里投影好的（按整学期邻接分配），旧快照才回退课名散列。
            val index = if (course.colorIndex in SCHEDULE_COLOR_PALETTE.indices) {
                course.colorIndex
            } else {
                scheduleCourseColorIndex(course.courseName)
            }
            // 考试不按课名散列配色：固定暗红，与 App 内日程的考试块、今日组件同一口径。
            views.setInt(
                R.id.week_block_root, "setBackgroundResource",
                if (course.isExam) palette.examBlockFill else palette.blockFills[index],
            )
            views.setInt(
                R.id.week_block_bar, "setBackgroundResource",
                if (course.isExam) palette.examBlockBar else BLOCK_BAR_IDS[index],
            )
            val argb = if (course.isExam) {
                if (dark) 0xFFE08282.toInt() else 0xFF8E1B1B.toInt()
            } else {
                SCHEDULE_COLOR_PALETTE[index].toArgb()
            }
            // 课名在淡色块上偏淡，单独加深一档（更实、更清楚）；地点沿用原色，
            // 只让课名承担视觉重点，其余位置不动。
            // 暗色下地点原先混白 0.45、课名只混白 0.28，反而地点更亮；
            // 现在让课名成为最亮的一层，地点退回次要亮度。
            val nameColor = if (dark) blendWithWhite(argb, 0.58f) else blendWithBlack(argb, 0.40f)
            val locColor = if (dark) blendWithWhite(argb, 0.32f) else argb
            views.setTextColor(R.id.week_block_name, nameColor)
            views.setTextColor(R.id.week_block_loc, locColor)

            // 跨 4 节及以上的课程块高度充足，课名不再做 8 字硬截断，完整显示；
            // 短块仍沿用原来的 8 字省略，避免一行课挤爆。
            val fullName = span >= 4
            val nameText = if (fullName) course.courseName else
                course.courseName.let { if (it.length > 8) it.take(8) + "\u2026" else it }
            views.setTextViewText(
                R.id.week_block_name,
                buildString {
                    if (course.isAdjusted) append("[调]")
                    if (course.isExam) append("[考]")
                } + nameText,
            )
            // 地点一律完整显示 —— 不省略、不隐藏，由 TextView 按列宽自动换行。
            // fullName 的行数按跨度放宽到 4~10 行（wrap_content，只作上限不占额外高度）。
            val nameLines = when {
                fullName -> (span * 2).coerceIn(4, 10)
                span <= 1 -> 1
                span == 2 -> 2
                else -> 3
            }
            views.setInt(R.id.week_block_name, "setMaxLines", nameLines)
            views.setViewVisibility(R.id.week_block_loc, View.VISIBLE)
            // 地点行数按跨度收紧并以省略号收尾 —— 跨 2 节的窄块高度只够「课名 2 行 + 地点 2 行」，
            // 之前给到 8 行会让第三行被父布局裁成半截（显得地名缺字）。
            views.setInt(R.id.week_block_loc, "setMaxLines", if (span <= 2) 3 else 6)
            views.setTextViewText(R.id.week_block_loc, weekBlockLocation(course))
            // 点课程块 → 组件内部覆盖层展开该课详情（不启动界面、不跳 App）。
            views.setOnClickPendingIntent(
                R.id.week_block_root,
                WidgetDetailOverlay.showPendingIntent(
                    context, ScheduleWeekWidgetProvider::class.java, course,
                    WidgetDetailOverlay.SOURCE_WEEK,
                ),
            )
            return views
        }

        /** 地点：删「楼」字、多教室合并；「无教室信息」按小组件约定显示成「无」。
         * 不做字数截断，交给 TextView 自动换行完整显示。
         * 不做「右括号后强制换行」—— 地点区只有两行空间，
         *主动换行会让「学武(1号)A401」变三行、直接显示不下。靠 8dp 字号自然折行即可。 */
        private fun weekBlockLocation(course: ScheduleWidgetCourse): String {
            val text = course.location
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" ")
                .replace("楼", "")
            return when {
                text.isBlank() -> "无"
                text == SCHEDULE_NO_ROOM_TEXT || text == "无 教室信息" -> "无"
                else -> text
            }
        }

        /**把课程色向黑色混合，让浅色卡片上的课名更实、更清楚。 */
        private fun blendWithBlack(color: Int, ratio: Float): Int {
            fun mix(channel: Int) = (channel * (1f - ratio)).toInt().coerceIn(0, 255)
            return Color.rgb(
                mix(Color.red(color)),
                mix(Color.green(color)),
                mix(Color.blue(color)),
            )
        }

        private fun blendWithWhite(color: Int, ratio: Float): Int {
            fun mix(channel: Int) = (channel + (255 - channel) * ratio).toInt().coerceIn(0, 255)
            return Color.rgb(
                mix(Color.red(color)),
                mix(Color.green(color)),
                mix(Color.blue(color)),
            )
        }

        private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, ScheduleWeekWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun weekWidgetDescription(snapshot: ScheduleWeekWidgetSnapshot?): String {
            if (snapshot == null) return "周课表，尚未同步"
            val courseCount = snapshot.days.sumOf { it.size }
            return "第${snapshot.week}周课表，共${courseCount}门课，点击打开课表"
        }
    }
}
