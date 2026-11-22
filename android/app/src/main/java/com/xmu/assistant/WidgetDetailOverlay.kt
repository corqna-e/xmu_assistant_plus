package com.xmu.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/**
 *小组件「点一下弹二级界面」——**在组件内部绘制**，不启动任何 Activity / 界面。
 *
 * 曾用透明主题 Activity 承载这张卡片，用户明确否掉：那样会切应用、盖住桌面，
 * 本质仍是「打开 App」。现在改为 RemoteViews 覆盖层：
 *
 *1. 两个组件的主布局根都是 FrameLayout，第二层是一张默认 `gone` 的详情覆盖层；
 *2. 点某门课 → 广播把该课详情写进 SharedPreferences → Provider 立刻重绘这份 RemoteViews，
 *覆盖层转为可见并填入内容；
 *3. 点「关闭」或点遮罩 → 广播清空状态 → 重绘回正常课表。
 *
 * 全程只在桌面进程里换一次视图，不进入应用、不申请悬浮窗权限、不会打断用户手上的操作。
 * 状态按组件来源（今日 / 周课表）分开存，两个组件同时挂在桌面上也互不干扰。
 */
internal data class WidgetCourseDetail(
    val courseName: String,
    val weekday: Int,
    val startTime: Int,
    val endTime: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: String,
    val location: String,
    val teachers: String,
    val isAdjusted: Boolean,
    /**该条是考试（考试安排并入日程）。详情里不显示「周次」「教师」，地点叫考场。 */
    val isExam: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("n", courseName)
        put("wd", weekday)
        put("st", startTime)
        put("et", endTime)
        put("ss", startSection)
        put("es", endSection)
        put("wk", weeks)
        put("loc", location)
        put("t", teachers)
        put("adj", isAdjusted)
        put("ex", isExam)
    }.toString()

    companion object {
        fun fromJson(raw: String): WidgetCourseDetail? = runCatching {
            val obj = JSONObject(raw)
            WidgetCourseDetail(
                courseName = obj.optString("n"),
                weekday = obj.optInt("wd"),
                startTime = obj.optInt("st"),
                endTime = obj.optInt("et"),
                startSection = obj.optInt("ss"),
                endSection = obj.optInt("es"),
                weeks = obj.optString("wk"),
                location = obj.optString("loc"),
                teachers = obj.optString("t"),
                isAdjusted = obj.optBoolean("adj"),
                isExam = obj.optBoolean("ex", false),
            )
        }.getOrNull()
    }
}

internal fun ScheduleWidgetCourse.toDetail(): WidgetCourseDetail = WidgetCourseDetail(
    courseName = courseName,
    weekday = weekday,
    startTime = startTime,
    endTime = endTime,
    startSection = startSection,
    endSection = endSection,
    weeks = weeks,
    location = location,
    teachers = teachers,
    isAdjusted = isAdjusted,
    isExam = isExam,
)

internal object WidgetDetailOverlay {
    /** 今日课程组件。 */
    const val SOURCE_TODAY = "today"
    /** 周课表组件。 */
    const val SOURCE_WEEK = "week"

    const val ACTION_SHOW = "com.xmu.assistant.widget.SHOW_COURSE_DETAIL"
    const val ACTION_HIDE = "com.xmu.assistant.widget.HIDE_COURSE_DETAIL"
    /** 卡片本体的点击：只为吃掉手势，避免冒泡到遮罩把卡片关掉。 */
    const val ACTION_NOOP = "com.xmu.assistant.widget.DETAIL_NOOP"
    /**
     *「打开课表」不再直接挂 `PendingIntent.getActivity`。
     *
     * 桌面在 ACTION_DOWN 时并不知道用户最终会点中哪个子控件，只要这个 RemoteViews 里
     * 存在一条会启动 Activity 的点击意图，HyperOS / MIUI 就会把整张卡片预置成「要打开应用」，
     * 播放一次放大再回弹的转场预览 —— 录屏里整卡外边界在 0.33s 内缩小后弹回，就是它。
     * 现在组件内所有点击一律是广播，由 Provider 收到后再 `startActivity`，转场只发生在
     *点课程弹详情不再触发。
     */
    const val ACTION_OPEN_SCHEDULE = "com.xmu.assistant.widget.OPEN_SCHEDULE"

    const val EXTRA_OPEN_VIEW = "open_schedule_view"

    private const val PREFS = "widget_course_detail"
    private const val EXTRA_DETAIL = "detail_json"
    private const val EXTRA_SOURCE = "detail_source"

    fun save(context: Context, source: String, detail: WidgetCourseDetail) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(source, detail.toJson()).apply()
        }
    }

    fun load(context: Context, source: String): WidgetCourseDetail? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(source, null) ?: return null
        WidgetCourseDetail.fromJson(raw)
    }.getOrNull()

    fun clear(context: Context, source: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(source).apply()
        }
    }

    /**
     *点课程后延迟 [SHOW_DELAY_MS] 才把详情覆盖层画出来。
     *
     * 真机已确认：荣耀桌面在 ACTION_DOWN/UP 会对整个 AppWidgetHostView 播一次缩放回弹，
     * 点组件空白区（App 完全不响应）照样播，与我们的重绘、覆盖层、PendingIntent 类型都无关。
     * 既然动效改不掉，就把弹窗推迟到动效播完：卡片已经静止，遮罩和详情卡就不会跟着一起
     * 被「按进去」再弹出来。0.5s 是的时长。
     *
     * 收起（HIDE）不延迟，否则关闭会显得迟钝；关闭同时取消尚未执行的 SHOW，
     * 避免 0.5s 后弹窗又自己冒出来。两个组件按 source 各自排队，互不取消。
     *
     * BroadcastReceiver 在 onReceive 返回后随时可能被回收，所以 SHOW 分支由调用方
     * `goAsync()` 取一个 PendingResult，这里在延迟任务执行完（或被取消）后才 finish。
     */
    const val SHOW_DELAY_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())

    private class DeferredShow(
        val task: Runnable,
        val result: BroadcastReceiver.PendingResult?,
    )

    /** 按组件来源排队：今日与周课表同时挂桌面时，一个组件的点击不会取消另一个。 */
    private val pendingShows = HashMap<String, DeferredShow>()

    /** 这条广播是否需要调用方用 goAsync() 撑住进程。 */
    fun isDeferredShow(intent: Intent): Boolean = intent.action == ACTION_SHOW

    @Synchronized
    private fun cancelPendingShow(source: String) {
        pendingShows.remove(source)?.let {
            mainHandler.removeCallbacks(it.task)
            runCatching { it.result?.finish() }
        }
    }

    /**
     * Provider 收到点击广播后统一走这里：写入 / 清空详情，然后让调用方**只重绘覆盖层**。
     *
     *只推一份含覆盖层动作的 RemoteViews，课表网格不重建，避免整块刷新时网格空一帧。
     *SHOW 延后绘制；HIDE 立即绘制并取消同来源尚未执行的 SHOW。
     * [pendingResult] 由 SHOW 分支负责 finish，其余分支不使用。
     *
     * 返回 true 表示这条广播是被详情机制消费掉的（调用方不必再刷新一遍）。
     */
    fun handle(
        context: Context,
        intent: Intent,
        pendingResult: BroadcastReceiver.PendingResult? = null,
        render: (Context, WidgetCourseDetail?) -> Unit,
    ): Boolean = when (intent.action) {
        ACTION_NOOP -> true
        ACTION_SHOW -> {
            val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
            val detail = intent.getStringExtra(EXTRA_DETAIL)
                ?.let { WidgetCourseDetail.fromJson(it) }
            // 同一组件连续点击只保留最后一次；旧广播的 PendingResult 一并归还。
            if (source.isNotEmpty()) cancelPendingShow(source)
            if (source.isEmpty() || detail == null) {
                runCatching { pendingResult?.finish() }
                true
            } else {
                val appContext = context.applicationContext
                // 状态立即落盘：即使延迟期间进程被回收，下次组件刷新也能把弹窗补上。
                save(appContext, source, detail)
                val task = Runnable {
                    synchronized(WidgetDetailOverlay) { pendingShows.remove(source) }
                    // 期间点了关闭或换了另一门课，状态已变，这次延迟任务作废。
                    if (load(appContext, source) == detail) {
                        render(appContext, detail)
                    }
                    runCatching { pendingResult?.finish() }
                }
                synchronized(WidgetDetailOverlay) {
                    pendingShows[source] = DeferredShow(task, pendingResult)
                }
                mainHandler.postDelayed(task, SHOW_DELAY_MS)
                true
            }
        }
        ACTION_HIDE -> {
            val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
            if (source.isNotEmpty()) cancelPendingShow(source)
            if (source.isNotEmpty() && load(context, source) != null) {
                clear(context, source)
                render(context, null)
            }
            true
        }
        else -> false
    }

    /**
     * Provider 收到「打开课表」广播后才真正启动应用。
     * 只有这一步会走系统的打开应用转场；点课程弹详情完全留在组件内部。
     */
    fun openSchedule(context: Context, intent: Intent) {
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("open_page", "课表")
            putExtra("open_schedule_view", intent.getStringExtra(EXTRA_OPEN_VIEW).orEmpty())
        }
        runCatching { context.startActivity(launch) }
    }

    /**
     * 只影响覆盖层的 RemoteViews：可见性 / 文字 / 颜色 / 点击，不改任何子视图结构。
     *
     * 与完整构建的 RemoteViews 共用同一个布局 id，桌面进程按「局部重新应用」处理，
     * 已有视图树（课表网格、今日课程行）原封不动，因此不会出现空帧闪动。
     */
    fun stateViews(
        context: Context,
        layoutId: Int,
        detail: WidgetCourseDetail?,
        provider: Class<*>,
        source: String,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        applyDetailOverlay(views, context, widgetPalette(context), detail, provider, source)
        return views
    }

    /** 点某门课：把详情塞进广播，Provider 收到后原地重绘覆盖层。 */
    fun showPendingIntent(
        context: Context,
        provider: Class<*>,
        course: ScheduleWidgetCourse,
        source: String,
    ): PendingIntent {
        val detail = course.toDetail()
        val intent = Intent(context, provider).apply {
            action = ACTION_SHOW
            // data 必须带课程唯一信息：PendingIntent 的匹配比对 data，
            // 只靠 extras 区分会让不同课程复用同一个实例、内容互相覆盖。
            data = Uri.parse(
                "xmuwidget://detail/$source/${course.weekday}/${course.startSection}" +
                    "/${course.startTime}?n=${Uri.encode(course.courseName)}",
            )
            putExtra(EXTRA_DETAIL, detail.toJson())
            putExtra(EXTRA_SOURCE, source)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_SHOW, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 关闭覆盖层。 */
    fun hidePendingIntent(context: Context, provider: Class<*>, source: String): PendingIntent {
        val intent = Intent(context, provider).apply {
            action = ACTION_HIDE
            data = Uri.parse("xmuwidget://detail-close/$source")
            putExtra(EXTRA_SOURCE, source)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_HIDE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 卡片本体：无动作，只吃掉点击。 */
    fun noopPendingIntent(context: Context, provider: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_NOOP,
            Intent(context, provider).setAction(ACTION_NOOP)
                .setData(Uri.parse("xmuwidget://detail-noop")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REQUEST_SHOW = 2501
    private const val REQUEST_HIDE = 2502
    private const val REQUEST_NOOP = 2503
}

/**
 * 「打开课表」：今日组件落日程页，周课表组件落周课表页。
 *
 *这里改为 `PendingIntent.getBroadcast`。组件里只要还有一条会启动 Activity 的
 * 点击意图，桌面按下时就会把整张卡片当作「即将打开应用」做转场预览（放大回弹）。
 * 现在组件内所有点击都是广播，转场只在用户真的按「打开课表」时发生。
 */
internal fun widgetOpenSchedulePendingIntent(
    context: Context,
    provider: Class<*>,
    source: String,
): PendingIntent {
    val intent = Intent(context, provider).apply {
        action = WidgetDetailOverlay.ACTION_OPEN_SCHEDULE
        // data 必须带来源：两个组件的 PendingIntent 不能复用同一个实例。
        data = Uri.parse("xmuwidget://open-schedule/$source")
        putExtra(
            WidgetDetailOverlay.EXTRA_OPEN_VIEW,
            if (source == WidgetDetailOverlay.SOURCE_WEEK) {
                ScheduleViewModeNames.WEEK
            } else {
                ScheduleViewModeNames.AGENDA
            },
        )
    }
    return PendingIntent.getBroadcast(
        context,
        if (source == WidgetDetailOverlay.SOURCE_WEEK) 2311 else 2312,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 把详情写进覆盖层；[detail] 为 null 时隐藏覆盖层回到正常课表。
 *
 * 返回 true 表示当前处于「详情展开」状态 —— 调用方据此决定要不要跳过原本的
 * 空态 / 尺寸计算逻辑（那些逻辑只为绘制课表本身服务）。
 */
internal fun applyDetailOverlay(
    views: RemoteViews,
    context: Context,
    palette: WidgetPalette,
    detail: WidgetCourseDetail?,
    provider: Class<*>,
    source: String,
): Boolean {
    if (detail == null) {
        // 用 INVISIBLE 而不是 GONE。覆盖层是 match_parent 的整层，GONE↔VISIBLE
        // 会让根 FrameLayout 走一次完整的重新测量/布局，桌面会把它当成组件尺寸变化并做
        // 缩放动画（实测整卡从 498px 涨到 506px 再弹回）。INVISIBLE 始终参与布局，
        // 切换时几何完全不变，只改绘制与触摸，桌面不会再动整卡。
        views.setViewVisibility(R.id.detail_overlay, View.INVISIBLE)
        return false
    }
    views.setViewVisibility(R.id.detail_overlay, View.VISIBLE)
    views.setInt(R.id.detail_card, "setBackgroundResource", palette.detailCard)
    views.setTextColor(R.id.detail_title, palette.primary)
    views.setTextColor(R.id.detail_subtitle, palette.secondary)
    views.setTextColor(R.id.detail_time, palette.primary)
    views.setTextColor(R.id.detail_section, palette.primary)
    views.setTextColor(R.id.detail_weeks, palette.primary)
    views.setTextColor(R.id.detail_room, palette.primary)
    views.setTextColor(R.id.detail_teacher, palette.primary)
    views.setTextColor(R.id.detail_open, palette.accent)
    views.setTextColor(R.id.detail_close, palette.accent)

    views.setTextViewText(
        R.id.detail_title,
        normalizeScheduleBrackets(detail.courseName).ifBlank { "未命名课程" },
    )
    views.setTextViewText(
        R.id.detail_subtitle,
        if (detail.isAdjusted) "我的上课安排 · 已调课" else "我的上课安排",
    )
    val day = if (detail.weekday in 1..7) "${weekdayName(detail.weekday)} · " else ""
    views.setTextViewText(
        R.id.detail_time,
        "$day${formatXmuTime(detail.startTime)}—${formatXmuTime(detail.endTime)}",
    )
    views.setTextViewText(
        R.id.detail_section,
        "节次：${detail.startSection}—${detail.endSection}节",
    )
    // 考试没有周次与教师：硬套「周次：考试」字段名与内容对不上，地点也该叫考场。
    if (detail.isExam) {
        views.setTextViewText(R.id.detail_weeks, "考试")
        views.setTextViewText(
            R.id.detail_room,
            "考场：${detail.location.replace('\n', ' ').ifBlank { "未标注" }}",
        )
        views.setViewVisibility(R.id.detail_teacher, View.GONE)
    } else {
        views.setTextViewText(R.id.detail_weeks, "周次：${detail.weeks.ifBlank { "未标注" }}")
        views.setTextViewText(
            R.id.detail_room,
            "教室：${detail.location.replace('\n', ' ').ifBlank { "未标注" }}",
        )
        views.setTextViewText(R.id.detail_teacher, "教师：${detail.teachers.ifBlank { "未标注" }}")
    }

    // 点卡片本体不关，点遮罩或「关闭」才退出。
    views.setOnClickPendingIntent(
        R.id.detail_card,
        WidgetDetailOverlay.noopPendingIntent(context, provider),
    )
    views.setOnClickPendingIntent(
        R.id.detail_overlay,
        WidgetDetailOverlay.hidePendingIntent(context, provider, source),
    )
    views.setOnClickPendingIntent(
        R.id.detail_close,
        WidgetDetailOverlay.hidePendingIntent(context, provider, source),
    )
    views.setOnClickPendingIntent(
        R.id.detail_open,
        widgetOpenSchedulePendingIntent(context, provider, source),
    )
    return true
}
