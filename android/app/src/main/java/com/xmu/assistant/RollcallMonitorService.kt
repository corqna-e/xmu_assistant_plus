package com.xmu.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** 自动监控窗口外的待机复查间隔：短到能在窗口开始后及时恢复，长到不空耗电。 */
private const val AUTO_MONITOR_IDLE_WAIT_MILLIS = 60_000L

class RollcallMonitorService : Service() {
    @Volatile private var workerToken: Long? = null

    // 时段自查用的课表快照缓存：服务每轮都会判一次窗口，而课表文件几十 KB，
    // 每分钟读一次盘没必要；60 秒内复用同一份，课表刷新后最迟一分钟后生效。
    private var windowSnapshotCache: XmuScheduleSnapshot? = null
    private var windowSnapshotCachedAt = 0L

    /** 优先文件缓存（当前唯一真实来源），回退旧版 prefs，两者都空视为「无课表」。 */
    private fun monitorWindowSnapshot(settings: AssistantSettings): XmuScheduleSnapshot {
        val now = SystemClock.elapsedRealtime()
        windowSnapshotCache?.let { cached ->
            if (now - windowSnapshotCachedAt < 60_000L) return cached
        }
        val fromFile = runCatching { loadScheduleSnapshotFromFile(this) }
            .getOrDefault(XmuScheduleSnapshot())
        val snapshot = if (fromFile.entries.isNotEmpty()) {
            fromFile
        } else {
            runCatching { xmuScheduleSnapshotFromJson(settings.scheduleCacheJson) }
                .getOrDefault(XmuScheduleSnapshot())
        }
        windowSnapshotCache = snapshot
        windowSnapshotCachedAt = now
        return snapshot
    }

    // 第三方推送专用单线程执行器：签到事件可能短时集中到达，原先每个事件裸起
    // 一个 daemon 线程，线程数随事件数无界增长；推送发送本身慢（各自 15s 超时）
    // 且相互独立，串行单线程足够并天然保序，onDestroy 时统一关停。
    private val thirdPartyPushExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xmu-notify-third-party").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Android 14+ (targetSdk 34+) 系统要求：两参 startForeground 在某些 ROM
        // （实测 ColorOS/Android 16 的 ForegroundServiceTypeLoggerModule 报
        // "does not have any types"）不会向下游传 FGS 类型。改三参 + 显式类型，
        // 让系统始终把它当作带类型的前台服务，避免被判为"无类型服务"被停。
        // 三参重载要求 API29+；API26-28 走两参（无类型语义可传，系统按默认处理），
        // 否则旧设备 onCreate 即 NoSuchMethodError 崩溃。
        //
        // 类型分派：Android 15（API 35，targetSdk 35 起）对 dataSync 有 24h 内
        // 6 小时的强制超时——全天候签到监控必然触顶，超时未停即被系统 ANR。
        // API 35+ 改传 specialUse（无该超时，用途声明见 manifest 的
        // PROPERTY_SPECIAL_USE_FGS_SUBTYPE）；API 29-34 的 dataSync 无此超时，
        // 维持原类型；API 26-28 两参。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                foregroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                foregroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification())
        }
    }

    /** dataSync 超时兜底（API 35 回调）：正常路径 API 35+ 已用 specialUse（无超时）
     *不会进这里；若个别 ROM 仍按 dataSync 判定超时，优雅自停而不是被系统以
     *ForegroundServiceDidNotStopInTimeException 杀进程。
     *用户从首页/磁贴重新点「启动提醒」即可恢复。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "前台服务超时回调（type=$fgsType）：优雅自停，等待用户手动重启监控")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = AssistantSettings(this)
        if (!shouldStartMonitorWorker(settings.monitorDesired, settings.cookieHeader)) {
            monitorWorkerCoordinator.requestInvalidateCurrent()
            workerToken = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        monitorWorkerCoordinator.start(settings.monitorDesired)?.let { token ->
            workerToken = token
            thread(name = "xmu-rollcall-monitor", isDaemon = true) { monitorLoop(token) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        workerToken?.let(monitorWorkerCoordinator::requestInvalidate)
        // 停止接收新推送任务；在途/排队中的发送走完（daemon 线程不阻塞进程退出）
        thirdPartyPushExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isMonitorRunActive(token: Long, settings: AssistantSettings): Boolean =
        monitorWorkerCoordinator.isCurrent(token, settings.monitorDesired)

    /**
     * 分段可中断等待：每 500ms 检查一次监控是否仍在运行，让「停止监控」立即生效，
     * 而不是等满整个轮询间隔（最长 300s）。等待本身不发起任何网络请求。
     */
    private fun awaitInterruptible(token: Long, settings: AssistantSettings, totalMillis: Long) {
        val deadline = SystemClock.elapsedRealtime() + totalMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isMonitorRunActive(token, settings)) return
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * 冷启动首轮专用：默认网络可能尚未就绪（刚开机时 Wi-Fi 仍在关联、校园网尚未认证），
     * 此时发请求必然抛 IOException，失败会直接顶到首页横幅。最多等 [STARTUP_NETWORK_WAIT_MILLIS]，
     * 每 400ms 复查一次默认网络是否具备 INTERNET 能力；已就绪立即返回，超时也照常尝试。
     */
    private fun awaitNetworkAvailable(token: Long, settings: AssistantSettings) {
        val manager = runCatching { getSystemService(ConnectivityManager::class.java) }.getOrNull() ?: return
        val deadline = SystemClock.elapsedRealtime() + STARTUP_NETWORK_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isMonitorRunActive(token, settings)) return
            val ready = runCatching {
                val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
                capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.getOrDefault(false)
            if (ready) return
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun monitorLoop(token: Long) {
        val settings = AssistantSettings(this)
        // seen 去重集合持久化（观察记录③，自由区修复）：
        // 进程被杀后重启，恢复已处理过的签到 id，避免重复通知/重复应答。
        // 约束：绑定账号（cookie 变化即清空）；恢复后不跳过判读（pollOnce 照常，
        // seen 仅用于去重轮询间隔内的重复返回，不改变"什么算新签到"的判定）。
        // 按处理先后保序恢复：超限驱逐删最旧，不会误删最近处理过的事件。
        // cookie 快照：整个运行周期以此为准，持久化也绑定快照——
        // 若读实时 cookie，运行中换号会把旧账号已处理 id 记到新账号名下，
        // 新账号同 id 签到随后被 restoreSeen 误判「已处理」，不通知也不应答。
        val cookieSnapshot = settings.cookieHeader
        val restoredLegacy = restoreLegacySeenRollcalls(cookieSnapshot)
        val notified = ArrayDeque(restoreRollcallIds(cookieSnapshot, NOTIFIED_LIST_KEY, restoredLegacy))
        val notifiedSet = notified.toMutableSet()
        val completed = ArrayDeque(restoreRollcallIds(cookieSnapshot, COMPLETED_LIST_KEY, restoredLegacy))
        val completedSet = completed.toMutableSet()
        val answerAttempts = restoreAnswerAttempts(cookieSnapshot).toMap(linkedMapOf())
        // 冷启动首轮的失败判定单独放宽。
        // 服务随开机或进程重建被拉起时，网络栈、校园网认证、DNS 缓存都还在就绪途中，
        // 第一发请求失败属于时序问题而非真实断网；直接 recordMonitorFailure 会让
        // 首页「自动签到」下方立刻出现「网络连接失败」。首轮先等网络可用，仍失败则
        // 静默重试 [STARTUP_POLL_RETRY_LIMIT] 次，之后才按常规流程计入失败。
        var startupRetries = 0
        var firstPollSettled = false
        try {
            while (isMonitorRunActive(token, settings)) {
                val rollcallSettings = settings.rollcall()
                val intervalSeconds = rollcallSettings.pollIntervalSeconds.coerceIn(1, 300)
                // 监控策略·时段自查：仅在有课窗口（课前 30 分钟至课后 30 分钟）内执行
                // 网络检查；其余时间（含当天没课、课间空隙）只待机等待，不触发失败计数。
                // 课表数据改从文件缓存读（旧的 prefs 字段已是空壳，导致开关形同虚设）。
                if (settings.autoMonitorWindow) {
                    val now = java.time.LocalTime.now()
                    val inWindow = autoMonitorWindowActive(
                        monitorWindowSnapshot(settings),
                        java.time.LocalDate.now().dayOfWeek.value,
                        now.hour * 60 + now.minute,
                    )
                    if (!inWindow) {
                        if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                                updateForegroundNotification(intervalSeconds, "不在上课时段")
                            }
                        ) return
                        awaitInterruptible(token, settings, AUTO_MONITOR_IDLE_WAIT_MILLIS)
                        continue
                    }
                }
                try {
                    val liveCookie = settings.cookieHeader
                    if (liveCookie.isBlank()) error("登录已过期，请重新登录")
                    // 运行中换号/重登：seen 与新账号不匹配，退出循环，由重登流程重启监控
                    if (liveCookie != cookieSnapshot) return
                    val coordinateStore = RadarCoordinateStore(this)
                    val engine = RollcallEngine(
                        liveCookie,
                        radarCoordinateProvider = { event ->
                            coordinateStore.preferredFor(event.courseId, event.courseTitle)
                        },
                    )
                    // 同一轮详情 GET 同时供进度门槛、本人状态和数字码使用。
                    if (!firstPollSettled) awaitNetworkAvailable(token, settings)
                    val allEvents = engine.pollWithDetails(settings.username)
                    firstPollSettled = true
                    // 自适应轮询节奏（与桌面端 MonitorWorker 对齐）：存在进行中的签到
                    // （remainingSeconds > 0）时切密集轮询，空闲时段维持用户设定间隔。
                    // 平均发现延迟从 interval/2 降到 ACTIVE_POLL_INTERVAL_SECONDS/2；
                    // 密集期只覆盖课堂签到窗口，日均请求量几乎不变。
                    val activeRollcall = allEvents.any { (it.remainingSeconds ?: 0L) > 0 }
                    try {
                        processRollcallMonitorPoll(
                            events = allEvents,
                            settings = rollcallSettings,
                            notifiedIds = notifiedSet,
                            completedIds = completedSet,
                            answerAttempts = answerAttempts,
                            runIfActive = { action ->
                                monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired, action)
                            },
                            onNotify = { notifyRollcall(it) },
                            // 与前台「立即签到」共用提交门：同一记录只写一次。
                            // 拿到锁后再复核本次监控是否仍在跑、账号与签到设置是否未变，
                            // 等待期间被停或换号就本地放弃，不发写请求、不消耗重试预算。
                            onAnswer = { event ->
                                RollcallAnswerGate.answerOnce(
                                    accountId = settings.username,
                                    cookieHeader = cookieSnapshot,
                                    rollcallId = event.id,
                                    canAnswer = {
                                        isMonitorRunActive(token, settings) &&
                                            settings.cookieHeader == cookieSnapshot &&
                                            settings.rollcall() == rollcallSettings
                                    },
                                    answer = { engine.answer(event) },
                                )
                            },
                            onSuccess = settings::recordMonitorSuccess,
                            settingsStillCurrent = {
                                settings.cookieHeader == cookieSnapshot && settings.rollcall() == rollcallSettings
                            },
                            // 故障转移：自动流程放弃时弹需手动确认提醒（同一场只弹一次）。
                            onFailover = { event, reason -> notifyFailover(event, reason) },
                        )
                    } finally {
                        while (answerAttempts.size > MAX_ANSWER_ATTEMPTS_TRACKED) {
                            answerAttempts.remove(answerAttempts.keys.first())
                        }
                        syncOrderedIds(notified, notifiedSet, MAX_SEEN_ROLLCALLS)
                        syncOrderedIds(completed, completedSet, MAX_SEEN_ROLLCALLS)
                        persistRollcallIds(cookieSnapshot, NOTIFIED_LIST_KEY, notified)
                        persistRollcallIds(cookieSnapshot, COMPLETED_LIST_KEY, completed)
                        persistAnswerAttempts(cookieSnapshot, answerAttempts)
                    }
                    if (!isMonitorRunActive(token, settings)) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            updateForegroundNotification(intervalSeconds)
                        }
                    ) return
                    if (!isMonitorRunActive(token, settings)) return
                    // 分段可中断等待：停止监控时最多 500ms 响应，不等满整个轮询间隔；
                    // 有进行中的签到时用密集间隔（与桌面端自适应轮询一致）
                    val waitSeconds =
                        if (activeRollcall) minOf(intervalSeconds, ACTIVE_POLL_INTERVAL_SECONDS)
                        else intervalSeconds
                    awaitInterruptible(token, settings, waitSeconds * 1000L)
                } catch (error: MainSessionExpiredException) {
                    // 会话过期是终态：不再每 5s 无限空转重试（否则后台永久弱扫描服务器且
                    // 永不触发重登）。记录一次失败 + 通知一次后退出 worker，交由上层
                    // 会话恢复/重登流程接管（Service 内不自行发起 CAS 登录）。
                    if (!isMonitorRunActive(token, settings)) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            settings.recordMonitorFailure(friendlyMessage(error))
                        }
                    ) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            notifyMonitorProblem("登录已过期，请重新登录")
                        }
                    ) return
                    return
                } catch (error: Exception) {
                    if (!isMonitorRunActive(token, settings)) return
                    val message = friendlyMessage(error)
                    // 冷启动首轮：网络就绪时序问题居多，先静默重试，不落失败状态、不发通知。
                    if (!firstPollSettled && startupRetries < STARTUP_POLL_RETRY_LIMIT) {
                        startupRetries += 1
                        awaitInterruptible(token, settings, STARTUP_POLL_RETRY_WAIT_MILLIS)
                        continue
                    }
                    firstPollSettled = true
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            settings.recordMonitorFailure(message)
                        }
                    ) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            updateForegroundNotification(intervalSeconds)
                        }
                    ) return
                    // 连续失败计数持久化：进程重启后可能已 ≥ 阈值，用 == 相等判断会
                    // 永远不再通知；改为每累计满阈值次通知一次（3/6/9…），跨重启仍有效。
                    if (settings.monitorConsecutiveFailures % ERROR_NOTIFY_THRESHOLD == 0) {
                        if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                                notifyMonitorProblem(message)
                            }
                        ) return
                    }
                    if (!isMonitorRunActive(token, settings)) return
                    awaitInterruptible(token, settings, 5000L)
                }
            }
        } finally {
            monitorWorkerCoordinator.complete(token)
            if (workerToken == token) workerToken = null
            // worker 因会话过期/换号/停止退出后，Service 若继续前台常驻会顶着
            // 「监控运行中」的过期快照空转。没有活跃 run
            // 时自停，前台通知随服务移除；若监控仍被需要，START_STICKY/重登流程
            // 会重新拉起并 start 新 worker（hasCurrent 为原子查询，不会误杀新 run）。
            runCatching {
                if (!monitorWorkerCoordinator.hasCurrent()) stopSelf()
            }
        }
    }

    internal fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "虾大助手Plus后台监控", NotificationManager.IMPORTANCE_LOW)
        )
        // 旧通道已创建为 LOW，Android 不允许应用直接提升其重要性。提醒使用独立 ID，
        // 常驻服务仍保持安静；后续创建不会覆盖用户对提醒通道的静音/禁用选择。
        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL_ID, "虾大助手Plus签到与监控提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // 故障转移需要横幅甚至在锁屏弹出，必须单独一个高重要性通道（已有通道的重要性不可上调）。
        manager.createNotificationChannel(
            NotificationChannel(FAILOVER_CHANNEL_ID, "虾大助手Plus需手动确认", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** true 表示本地已提交系统，或远程任务已接受；远程是否送达仍由异步发送结果决定。 */
    internal fun notifyRollcall(
        event: RollcallEvent,
        notify: NotificationSettings = AssistantSettings(this).notifications(),
    ): Boolean {
        val actionUrl = "xmurollcall://rollcall/${event.id}"
        val pendingIntent = PendingIntent.getActivity(
            this,
            event.id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl), this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 本地通知（快速、无网络）：留在 gate 互斥区内，保证登出/暂停时不会漏发本地提醒
        val localPosted = postReminderNotification(event.id.hashCode(), notify.systemEnabled) {
            Notification.Builder(this, REMINDER_CHANNEL_ID)
                .setContentTitle("虾大助手Plus 签到提醒")
                .setContentText("${event.courseTitle} / ${event.type}")
                .setStyle(Notification.BigTextStyle().bigText(rollcallNotificationBody(event, actionUrl)))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        }

        // 第三方推送（PushPlus/QQMail）网络发送较慢（各自 15s 超时），
        // 移出互斥区到独立线程：否则登出/暂停要等几十秒（观察记录②，自由区修复）。
        if (!notify.pushPlusEnabled && !notify.qqMailEnabled) return localPosted
        // onDestroy 后执行器已 shutdown，提交会被拒绝（与轮询线程存在短暂竞速）；
        // 此时服务已销毁、推送无意义，记日志兜住即可，不让异常冒泡成监控失败。
        val remoteQueued = runCatching {
            thirdPartyPushExecutor.execute {
                // 第三方推送失败不重试不阻断本地通知，但必须留下日志：
                // token/授权码失效后该通道会长期静默死亡，无日志则完全无法察觉。
                if (notify.pushPlusEnabled) {
                    runCatching {
                        PushPlusSender(notify.pushPlusToken).send("虾大助手Plus 签到提醒", rollcallNotificationBody(event, actionUrl))
                    }.onFailure { Log.w(TAG, "PushPlus 推送失败：${it.message}", it) }
                }
                if (notify.qqMailEnabled) {
                    runCatching {
                        QQMailSender(notify.qqMailSender, notify.qqMailPassword, notify.qqMailRecipient, notify.qqMailPorts, notify.qqMailHost)
                            .send("虾大助手Plus 签到提醒", rollcallNotificationBody(event, actionUrl))
                    }.onFailure { Log.w(TAG, "邮箱推送失败：${it.message}", it) }
                }
            }
        }.onFailure { Log.w(TAG, "第三方推送任务提交失败（服务已销毁）：${it.message}") }.isSuccess
        // 远程渠道沿用每事件只尝试一次的策略，避免每次轮询都排队造成重复外发。
        // 所有渠道均不可用时返回 false，权限/设置恢复后仍可提醒尚未完成的签到。
        return localPosted || remoteQueued
    }

    internal fun notifyMonitorProblem(
        message: String,
        systemEnabled: Boolean = AssistantSettings(this).notifications().systemEnabled,
    ): Boolean = postReminderNotification(MONITOR_PROBLEM_NOTIFICATION_ID, systemEnabled) {
        Notification.Builder(this, REMINDER_CHANNEL_ID)
            .setContentTitle("虾大助手Plus 后台监控异常")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainPendingIntent())
            .setAutoCancel(true)
            .build()
    }

    /**
     * 故障转移提醒：自动流程放弃时要求人工确认并手动签到。
     *
     * 同一场签到只提醒一次（记录落盘，服务重启后不重复）。是否锁屏全屏弹出受
     * [AssistantSettings.rollcallFailoverFullScreen] 与系统「全屏通知」授权共同控制，
     * 未授权时退化为高优先级横幅通知——提醒本身不丢。
     */
    internal fun notifyFailover(
        event: RollcallEvent,
        reason: String,
        notify: NotificationSettings = AssistantSettings(this).notifications(),
    ): Boolean {
        if (event.id.isBlank()) return false
        val settings = AssistantSettings(this)
        if (event.id in settings.rollcallFailoverNotified()) return false
        settings.markRollcallFailoverNotified(event.id)
        val reasonText = when (reason) {
            FAILOVER_ANSWER_EXHAUSTED -> "自动签到已重试多次仍未成功"
            FAILOVER_OWN_STATUS_UNKNOWN -> "名单里的本人状态无法确认"
            else -> "自动处理未能完成"
        }
        val openIntent = PendingIntent.getActivity(
            this,
            event.id.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_page", Page.ROLLCALL.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = buildList {
            add(reasonText)
            add("课程：${event.courseTitle}")
            add("类型：${event.type}")
            if (event.remainingSeconds != null) add("剩余：${event.remainingSeconds} 秒")
            add("请打开签到页确认，并手动完成签到。")
        }.joinToString("\n")
        val builder = Notification.Builder(this, FAILOVER_CHANNEL_ID)
            .setContentTitle("需手动确认签到：${event.courseTitle}")
            .setContentText("$reasonText · ${event.type}")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .setPriority(Notification.PRIORITY_HIGH)
        if (settings.rollcallFailoverFullScreen && ExamReminder.canUseFullScreenIntent(this)) {
            builder.setFullScreenIntent(openIntent, true)
        }
        return postReminderNotification(
            id = event.id.hashCode(),
            systemEnabled = notify.systemEnabled,
            channelId = FAILOVER_CHANNEL_ID,
        ) { builder.build() }
    }

    private fun postReminderNotification(
        id: Int,
        systemEnabled: Boolean,
        channelId: String = REMINDER_CHANNEL_ID,
        notification: () -> Notification,
    ): Boolean {
        if (!systemEnabled) return false
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        return try {
            manager.notify(id, notification())
            true
        } catch (error: SecurityException) {
            // 检查后用户仍可能收回权限；不把通知拒绝升级为签到监控失败。
            Log.w(TAG, "系统通知未获授权：${error.message}")
            false
        }
    }

    private fun updateForegroundNotification(intervalSeconds: Int, idleNote: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_NOTIFICATION_ID, foregroundNotification(intervalSeconds, idleNote))
    }

    private fun foregroundNotification(
        intervalSeconds: Int? = null,
        idleNote: String? = null,
    ): Notification {
        val settings = AssistantSettings(this)
        val lastCheck = formatMonitorTime(settings.monitorLastCheckMillis)
        val failures = settings.monitorConsecutiveFailures
        val interval = intervalSeconds ?: settings.rollcall().pollIntervalSeconds
        val text = when {
            // 待机时如实告知“没在检查”，否则用户看到常驻通知会以为它一直在跑。
            idleNote != null -> "$idleNote · 待机中不发请求"
            failures > 0 -> "最近失败 $failures 次：${settings.monitorLastError.ifBlank { "请检查网络或登录状态" }}"
            else -> "监控中 · 最近检查：$lastCheck · 间隔 $interval 秒"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("虾大助手Plus")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainPendingIntent())
            .setOngoing(true)
            .build()
    }

    private fun mainPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            2104,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private val monitorWorkerCoordinator = MonitorWorkerCoordinator()

        fun requestInvalidateActiveRun() {
            monitorWorkerCoordinator.requestInvalidateCurrent()
        }

        fun awaitActiveRunQuiescence() {
            monitorWorkerCoordinator.awaitQuiescence()
        }

        private const val SEEN_PREFS = "rollcall_seen"
        private const val SEEN_COOKIE_KEY = "cookie"
        private const val SEEN_SET_KEY = "seen_ids"
        private const val SEEN_LIST_KEY = "seen_ids_ordered"
        private const val NOTIFIED_LIST_KEY = "notified_ids_ordered"
        private const val COMPLETED_LIST_KEY = "completed_ids_ordered"
        private const val ANSWER_ATTEMPTS_KEY = "answer_attempts"
        private const val TAG = "RollcallMonitorService"
        private const val CHANNEL_ID = "xmu_assistant_monitor"
        private const val REMINDER_CHANNEL_ID = "xmu_assistant_rollcall_reminders"
        /** 故障转移提醒专用通道：需要横幅/全屏，重要性必须为 HIGH。 */
        private const val FAILOVER_CHANNEL_ID = "xmu_assistant_rollcall_failover"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val MONITOR_PROBLEM_NOTIFICATION_ID = 1002
        private const val FAILOVER_NOTIFICATION_ID = 1003
        private const val ERROR_NOTIFY_THRESHOLD = 3
        private const val MAX_SEEN_ROLLCALLS = 300
        private const val MAX_ANSWER_ATTEMPTS_TRACKED = 512
        /** 自适应轮询：存在进行中的签到时的密集轮询间隔上限（秒），与桌面端对齐。 */
        private const val ACTIVE_POLL_INTERVAL_SECONDS = 5
        /** 冷启动首轮等待默认网络就绪的上限（毫秒）。 */
        private const val STARTUP_NETWORK_WAIT_MILLIS = 4_000L
        /** 冷启动首轮请求失败后的静默重试次数与间隔。 */
        private const val STARTUP_POLL_RETRY_LIMIT = 2
        private const val STARTUP_POLL_RETRY_WAIT_MILLIS = 2_000L
    }

    /** 恢复已处理过的签到 id 列表（进程被杀后去重不丢失，按处理先后保序）。
     *绑定账号：cookie 变化（换账号/重登）即返回空，避免把新账号的签到误判为已处理。 */
    private fun restoreLegacySeenRollcalls(cookieHeader: String): List<String> {
        if (cookieHeader.isBlank()) return emptyList()
        val prefs = getSharedPreferences(SEEN_PREFS, MODE_PRIVATE)
        // 只比对 SHA-256 指纹（P1-4）：prefs 里不再保存原始会话凭据；
        // 旧版本的明文记录与指纹必然不等，首次升级后 seen 列表重建一次（自愈）。
        val storedCookie = prefs.getString(SEEN_COOKIE_KEY, "") ?: ""
        if (storedCookie != cookieFingerprint(cookieHeader)) return emptyList()
        prefs.getString(SEEN_LIST_KEY, null)?.let { ordered ->
            return ordered.split('\n').filter { it.isNotBlank() }
        }
        // 兼容旧版 StringSet 格式（无序，下次持久化时自动迁移为有序列表）
        return prefs.getStringSet(SEEN_SET_KEY, emptySet())?.toList().orEmpty()
    }

    private fun restoreRollcallIds(cookieHeader: String, key: String, legacy: List<String>): List<String> {
        if (cookieHeader.isBlank()) return emptyList()
        val prefs = getSharedPreferences(SEEN_PREFS, MODE_PRIVATE)
        if (prefs.getString(SEEN_COOKIE_KEY, "") != cookieFingerprint(cookieHeader)) return emptyList()
        return prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() } ?: legacy
    }

    /** 持久化 seen 列表（只存签到 id 与 cookie 的 SHA-256 指纹，均非敏感；保序存储保证驱逐最旧）。
     *绑定账号指纹防止跨账号误用——完整 Cookie 属加密存储范畴，绝不落入普通 prefs。 */
    private fun persistRollcallIds(cookieHeader: String, key: String, ids: Collection<String>) {
        if (cookieHeader.isBlank()) return
        getSharedPreferences(SEEN_PREFS, MODE_PRIVATE)
            .edit()
            .putString(SEEN_COOKIE_KEY, cookieFingerprint(cookieHeader))
            .putString(key, ids.joinToString("\n"))
            .remove(SEEN_SET_KEY)
            .apply()
    }

    private fun restoreAnswerAttempts(cookieHeader: String): List<Pair<String, Int>> {
        if (cookieHeader.isBlank()) return emptyList()
        val prefs = getSharedPreferences(SEEN_PREFS, MODE_PRIVATE)
        if (prefs.getString(SEEN_COOKIE_KEY, "") != cookieFingerprint(cookieHeader)) return emptyList()
        return prefs.getString(ANSWER_ATTEMPTS_KEY, "").orEmpty().lineSequence().mapNotNull { line ->
            val id = line.substringBefore('\t').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // 正数=回执不明（兼容旧版），负数=平台明确拒绝；绝对值为尝试次数。
            val count = line.substringAfter('\t', "").toIntOrNull()?.takeIf { it != 0 } ?: return@mapNotNull null
            id to count
        }.take(MAX_ANSWER_ATTEMPTS_TRACKED).toList()
    }

    private fun persistAnswerAttempts(cookieHeader: String, attempts: Map<String, Int>) {
        if (cookieHeader.isBlank()) return
        val text = attempts.entries
            .filter { (id, count) -> id.isNotBlank() && '\n' !in id && '\t' !in id && count != 0 }
            .take(MAX_ANSWER_ATTEMPTS_TRACKED)
            .joinToString("\n") { (id, count) -> "$id\t$count" }
        getSharedPreferences(SEEN_PREFS, MODE_PRIVATE).edit()
            .putString(SEEN_COOKIE_KEY, cookieFingerprint(cookieHeader))
            .putString(ANSWER_ATTEMPTS_KEY, text)
            .apply()
    }
}

internal fun shouldStartMonitorWorker(monitorDesired: Boolean, cookieHeader: String): Boolean =
    monitorDesired && cookieHeader.isNotBlank()

/**
 * 会话 Cookie 快照的 SHA-256 指纹（小写 hex）。
 *
 * 用途：监控去重集合需要「绑定当时的登录态」防跨账号误判，但完整 Cookie 属于
 *加密凭据，不能明文落普通 SharedPreferences——指纹足以完成
 * 等值比对且不可逆推回原凭据。空串约定返回空串，便于调用方短路。
 */
internal fun cookieFingerprint(cookieHeader: String): String {
    if (cookieHeader.isBlank()) return ""
    val digest = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(cookieHeader.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * seen 环溢出逐出：新事件 id 入队入集后调用，超过上限时从最老端逐出并同步
 * 集合（防重复通知的内存上界，PC 端孪生有行为测试）。纯逻辑抽离便于直测。
 */
internal fun evictSeenOverflow(seen: ArrayDeque<String>, seenSet: MutableSet<String>, maxSeen: Int) {
    while (seen.size > maxSeen) {
        seenSet.remove(seen.removeFirst())
    }
}

internal fun syncOrderedIds(order: ArrayDeque<String>, ids: MutableSet<String>, maxSize: Int) {
    val orderedSet = order.toSet()
    ids.filterNot { it in orderedSet }.forEach(order::addLast)
    evictSeenOverflow(order, ids, maxSize)
}
