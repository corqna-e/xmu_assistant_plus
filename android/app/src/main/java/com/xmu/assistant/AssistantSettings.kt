package com.xmu.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 外观主题模式常量：跟随系统。 */
const val THEME_MODE_SYSTEM = "system"
/** 外观主题模式常量：强制浅色。 */
const val THEME_MODE_LIGHT = "light"
/** 外观主题模式常量：强制深色。 */
const val THEME_MODE_DARK = "dark"

/** 故障转移提醒的已提醒记录上限（同一场签到只提醒一次，历史只留最近若干条）。 */
const val ROLLCALL_FAILOVER_HISTORY = 50

/**
 * 加密设置存储。内部对 EncryptedSharedPreferences 做进程级单例缓存：
 * MasterKey 构建与加密 prefs 初始化较慢（每次实例化都重建），
 * 缓存后各模块（Activity/监控服务/Widget）首次访问才初始化一次，
 * 冷启动与后台唤醒更快。
 */
class AssistantSettings private constructor(
    context: Context,
    prefsOverride: android.content.SharedPreferences? = null,
) {
    private val appContext = context.applicationContext
    private val prefs: android.content.SharedPreferences = prefsOverride ?: encryptedPrefs(appContext)
    private val autoLoginPolicyStore = AutoLoginPolicyStore(
        readStored = { prefs.getString("auto_login_policy", null) },
        readCookie = { cookieHeader },
        writeStored = { value -> prefs.edit().putString("auto_login_policy", value).apply() },
    )

    val rankCacheJson: String get() = synchronized(RankStorageLock) { prefs.getString("rank_cache_json", "").orEmpty() }

    /** Synchronous durable marker before a non-idempotent POST; called off the UI thread. */
    fun storeRankCache(value: String, active: () -> Boolean): Boolean = synchronized(RankStorageLock) {
        if (!active()) false else prefs.edit().putString("rank_cache_json", value).commit()
    }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    var cookieHeader: String
        get() = prefs.getString("cookie_header", "") ?: ""
        set(value) = prefs.edit().putString("cookie_header", value).apply()

    /** 监控策略：仅在有课窗口（课前 30 分钟至课后 30 分钟）内检查，默认开启；异常时服务侧降级放行。 */
    var autoMonitorWindow: Boolean
        get() = prefs.getBoolean("auto_monitor_window", true)
        set(value) = prefs.edit().putBoolean("auto_monitor_window", value).apply()

    /**周课表缩放值（真实缩放 0.25~1.125，默认 0.75 = 显示 100%），跨重启自动记住。
     *上限随滑动条收到 150%，旧版本存下的更大值读回时被夹到新上限。 */
    var scheduleGridScale: Float
        get() = prefs.getFloat("schedule_grid_scale", GRID_SCALE_BASE)
            .coerceIn(MIN_GRID_SCALE, MAX_GRID_SCALE)
        set(value) = prefs.edit()
            .putFloat("schedule_grid_scale", value.coerceIn(MIN_GRID_SCALE, MAX_GRID_SCALE))
            .apply()

    /** 记住登录过的账号密码（仅本机），默认开启；关闭时立即清空已存列表。 */
    var rememberAccounts: Boolean
        get() = prefs.getBoolean("remember_accounts", true)
        set(value) {
            prefs.edit().putBoolean("remember_accounts", value).apply()
            if (!value) saveSavedAccounts(emptyList())
        }

    /** 已记住的账号列表，JSON 存本机 prefs。 */
    fun savedAccounts(): List<Pair<String, String>> {
        val json = prefs.getString("saved_accounts_json", "") ?: ""
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = obj.optString("u")
                val p = obj.optString("p")
                if (u.isNotBlank() && p.isNotBlank()) u to p else null
            }
        }.getOrDefault(emptyList())
    }

    /** 登录成功后登记账号（开关关闭时不记）。 */
    fun rememberAccount(username: String, password: String) {
        if (!rememberAccounts) return
        if (username.isBlank() || password.isBlank()) return
        val rest = savedAccounts().filterNot { it.first == username }
        // 最近使用的排前面，上限 10 个防无限膨胀
        val next = (listOf(username to password) + rest).take(10)
        saveSavedAccounts(next)
    }

    private fun saveSavedAccounts(accounts: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        accounts.forEach { (u, p) ->
            arr.put(org.json.JSONObject().put("u", u).put("p", p))
        }
        prefs.edit().putString("saved_accounts_json", arr.toString()).apply()
    }

    var autoLoginPolicy: AutoLoginPolicy
        get() = autoLoginPolicyStore.policy
        set(value) { autoLoginPolicyStore.policy = value }

    var scoreCookieHeader: String
        get() = prefs.getString("score_cookie_header", "") ?: ""
        set(value) = prefs.edit().putString("score_cookie_header", value).apply()

    var scoreRecordsJson: String
        get() = prefs.getString("score_records_json", "") ?: ""
        set(value) = prefs.edit().putString("score_records_json", value).apply()

    var scoreUpdatedAtMillis: Long
        get() = prefs.getLong("score_updated_at_millis", 0L)
        set(value) = prefs.edit().putLong("score_updated_at_millis", value).apply()

    var academicCacheJson: String
        get() = prefs.getString("academic_cache_json", "") ?: ""
        set(value) = prefs.edit().putString("academic_cache_json", value).apply()

    var scheduleCacheJson: String
        get() = prefs.getString("schedule_cache_json", "") ?: ""
        set(value) = prefs.edit().putString("schedule_cache_json", value).apply()

    /**
     * 外观主题模式："system" 跟随系统 / "light" 强制浅色 / "dark" 强制深色。
     * 默认跟随系统，保持旧行为。
     */
    var themeMode: String
        get() = prefs.getString("theme_mode", THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /**
     * 隐私模式（默认关）：开启后页面大标题显示为占位符、界面「签到」字样改为「举手」，
     * 见 [LocalPrivacyMode]。属于显示偏好，非敏感数据。
     */
    var privacyMode: Boolean
        get() = prefs.getBoolean("privacy_mode", false)
        set(value) = prefs.edit().putBoolean("privacy_mode", value).apply()

    /**
     * 隐私模式的自定义换词规则（JSON，见 [decodePrivacyRules]）。
     * 空串 = 用默认词表（「课」默认不替换，故换了新键，避免旧存档把默认值压回去）；用户在外观页逐条开关/改词后写入。属于显示偏好，非敏感数据。
     */
    var privacyRules: String
        get() = prefs.getString("privacy_rules_v2", "") ?: ""
        set(value) = prefs.edit().putString("privacy_rules_v2", value).apply()

    /**
     *隐私模式下是否把顶部大标题（二三级页）替换为随机字母（默认开）。
     * 关掉后标题原样显示，换词规则不受影响。属于显示偏好，非敏感数据。
     */
    var privacyTitleMasked: Boolean
        get() = prefs.getBoolean("privacy_title_masked", true)
        set(value) = prefs.edit().putBoolean("privacy_title_masked", value).apply()

    /** 考试提醒总开关（默认关）。 */
    var examReminderEnabled: Boolean
        get() = prefs.getBoolean("exam_reminder_enabled", false)
        set(value) = prefs.edit().putBoolean("exam_reminder_enabled", value).apply()

    /** 考试提前提醒分钟数（0-EXAM_REMINDER_MAX_MINUTES，默认 30；支持提前 1 天等长提前量）。 */
    var examReminderAdvanceMinutes: Int
        get() = prefs.getInt("exam_reminder_advance_minutes", 30)
        set(value) = prefs.edit().putInt("exam_reminder_advance_minutes", value.coerceIn(0, EXAM_REMINDER_MAX_MINUTES)).apply()

    /** 是否使用全屏通知（闹钟式锁屏提醒），需额外授权。 */
    var examReminderFullScreen: Boolean
        get() = prefs.getBoolean("exam_reminder_full_screen", false)
        set(value) = prefs.edit().putBoolean("exam_reminder_full_screen", value).apply()

    /**
     * 自动签到故障转移：自动流程放弃（提交失败达上限 / 本人状态无法判定）时，
     * 是否用全屏提醒要求人工确认（默认开）。关闭后只发普通高优先级通知。
     */
    var rollcallFailoverFullScreen: Boolean
        get() = prefs.getBoolean("rollcall_failover_full_screen", true)
        set(value) = prefs.edit().putBoolean("rollcall_failover_full_screen", value).apply()

    /** 已发过故障转移提醒的签到 id（同一场签到只提醒一次）。 */
    fun rollcallFailoverNotified(): Set<String> = runCatching {
        val raw = prefs.getString("rollcall_failover_notified", "") ?: ""
        if (raw.isBlank()) emptySet() else {
            val array = org.json.JSONArray(raw)
            buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
        }
    }.getOrDefault(emptySet())

    /** 记录一次故障转移提醒；只保留最近 [ROLLCALL_FAILOVER_HISTORY] 条，避免无限增长。 */
    fun markRollcallFailoverNotified(eventId: String) {
        if (eventId.isBlank()) return
        val kept = (rollcallFailoverNotified() - eventId).toMutableList()
        kept += eventId
        val trimmed = kept.takeLast(ROLLCALL_FAILOVER_HISTORY)
        val array = org.json.JSONArray().also { target -> trimmed.forEach(target::put) }
        prefs.edit().putString("rollcall_failover_notified", array.toString()).apply()
    }

    var monitorDesired: Boolean
        get() = prefs.getBoolean("monitor_desired", false)
        set(value) = prefs.edit().putBoolean("monitor_desired", value).apply()

    var monitorLastCheckMillis: Long
        get() = prefs.getLong("monitor_last_check_millis", 0L)
        set(value) = prefs.edit().putLong("monitor_last_check_millis", value).apply()

    var monitorConsecutiveFailures: Int
        get() = prefs.getInt("monitor_consecutive_failures", 0)
        set(value) = prefs.edit().putInt("monitor_consecutive_failures", value).apply()

    var monitorLastError: String
        get() = prefs.getString("monitor_last_error", "") ?: ""
        set(value) = prefs.edit().putString("monitor_last_error", value).apply()

    fun recordMonitorSuccess() {
        prefs.edit()
            .putLong("monitor_last_check_millis", System.currentTimeMillis())
            .putInt("monitor_consecutive_failures", 0)
            .putString("monitor_last_error", "")
            .apply()
    }

    fun recordMonitorFailure(error: String) {
        prefs.edit()
            .putInt("monitor_consecutive_failures", monitorConsecutiveFailures + 1)
            .putString("monitor_last_error", error)
            .apply()
    }

    fun markLoginSucceeded() {
        autoLoginPolicy = AutoLoginPolicy.ENABLED
        // 登录态镜像置 true：Widget 周期 worker 的 CAS 续登复核依据（见 writeWidgetLoggedInMirror）
        writeWidgetLoggedInMirror(appContext, true)
    }

    fun markAutoLoginFailed() { autoLoginPolicy = AutoLoginPolicy.BLOCKED }

    fun markUserLoggedOut() { autoLoginPolicy = AutoLoginPolicy.USER_LOGGED_OUT }

    fun rollcall(): RollcallSettings = RollcallSettings(
        pollIntervalSeconds = prefs.getInt("poll_interval_seconds", 30).coerceIn(1, 300),
        autoAnswerNumber = prefs.getBoolean("auto_answer_number", false),
        autoAnswerRadar = prefs.getBoolean("auto_answer_radar", false),
        waitBeforeAnswerMode = prefs.getString("wait_before_answer_mode", WAIT_BEFORE_ANSWER_NONE)
            .takeIf { it in setOf(WAIT_BEFORE_ANSWER_NONE, WAIT_BEFORE_ANSWER_COUNT, WAIT_BEFORE_ANSWER_PERCENT) }
            ?: WAIT_BEFORE_ANSWER_NONE,
        waitBeforeAnswerCount = prefs.getInt("wait_before_answer_count", 5).coerceAtLeast(1),
        waitBeforeAnswerPercent = prefs.getInt("wait_before_answer_percent", 15).coerceIn(1, 100),
    )

    fun saveRollcall(settings: RollcallSettings) {
        prefs.edit()
            .putInt("poll_interval_seconds", settings.pollIntervalSeconds.coerceIn(1, 300))
            .putBoolean("auto_answer_number", settings.autoAnswerNumber)
            .putBoolean("auto_answer_radar", settings.autoAnswerRadar)
            .putString(
                "wait_before_answer_mode",
                settings.waitBeforeAnswerMode.takeIf {
                    it in setOf(WAIT_BEFORE_ANSWER_NONE, WAIT_BEFORE_ANSWER_COUNT, WAIT_BEFORE_ANSWER_PERCENT)
                } ?: WAIT_BEFORE_ANSWER_NONE,
            )
            .putInt("wait_before_answer_count", settings.waitBeforeAnswerCount.coerceAtLeast(1))
            .putInt("wait_before_answer_percent", settings.waitBeforeAnswerPercent.coerceIn(1, 100))
            .apply()
    }

    /**
     *手动指定的教学周，**按学期分别存储**。
     * 手动指定只是「校准基准」：系统据此反推开学日，之后仍按日期自动推进周次
     * （明天同周、下周自动 +1），直到再次手动校准或清除校准。
     * 换学期后各学期互不干扰：**不回退旧版单值**（旧学期的手动基准不得错误作用到新学期）。
     */
    fun manualAcademicWeek(termCode: String): Int {
        if (termCode.isBlank()) return prefs.getInt("manual_academic_week", 0).coerceIn(0, 25)
        return manualWeekMap()[termCode] ?: 0
    }

    fun setManualAcademicWeek(termCode: String, week: Int) {
        if (termCode.isBlank()) {
            prefs.edit().putInt("manual_academic_week", week.coerceIn(0, 25)).apply()
            return
        }
        val map = manualWeekMap().toMutableMap()
        if (week <= 0) map.remove(termCode) else map[termCode] = week.coerceIn(0, 25)
        @Suppress("UNCHECKED_CAST")
        val json = org.json.JSONObject(map as Map<Any?, Any?>)
        prefs.edit().putString("manual_academic_week_by_term", json.toString()).apply()
    }

    private fun manualWeekMap(): Map<String, Int> = runCatching {
        val raw = prefs.getString("manual_academic_week_by_term", "") ?: ""
        if (raw.isBlank()) return@runCatching emptyMap()
        val obj = org.json.JSONObject(raw)
        obj.keys().asSequence().mapNotNull { key ->
            obj.optInt(key, 0).takeIf { it in 1..25 }?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 清空所有学期的手动周次（退出登录时调用）。 */
    fun clearManualAcademicWeeks() {
        prefs.edit()
            .remove("manual_academic_week_by_term")
            .remove("manual_academic_week")
            .apply()
    }

    /**
     * 清理旧版加密 prefs 里的课表缓存残留。
     * 课表缓存已迁移到明文文件（schedule_cache.json），加密残留只在
     * 迁移成功且读回校验通过后删除一次，避免重复迁移和加密数据长期残留。
     */
    fun clearScheduleCacheLegacyPref() {
        prefs.edit().remove("schedule_cache_json").apply()
    }

    fun notifications(): NotificationSettings = NotificationSettings(
        systemEnabled = prefs.getBoolean("notify_system", true),
        pushPlusEnabled = prefs.getBoolean("notify_pushplus", false),
        pushPlusToken = prefs.getString("pushplus_token", "") ?: "",
        qqMailEnabled = prefs.getBoolean("notify_qq_mail", false),
        qqMailSender = prefs.getString("qq_sender", "") ?: "",
        qqMailPassword = prefs.getString("qq_password", "") ?: "",
        qqMailRecipient = prefs.getString("qq_recipient", "") ?: "",
        qqMailPorts = prefs.getString("qq_ports", "465,587") ?: "465,587",
        qqMailHost = prefs.getString("mail_host", "") ?: "",
    )

    fun saveNotifications(settings: NotificationSettings) {
        prefs.edit()
            .putBoolean("notify_system", settings.systemEnabled)
            .putBoolean("notify_pushplus", settings.pushPlusEnabled)
            .putString("pushplus_token", settings.pushPlusToken)
            .putBoolean("notify_qq_mail", settings.qqMailEnabled)
            .putString("qq_sender", settings.qqMailSender)
            .putString("qq_password", settings.qqMailPassword)
            .putString("qq_recipient", settings.qqMailRecipient)
            .putString("qq_ports", settings.qqMailPorts.ifBlank { "465,587" })
            .putString("mail_host", settings.qqMailHost.trim())
            .apply()
    }

    fun clearSession() {
        storeRankCache("", { true })
        prefs.edit()
            .remove("cookie_header")
            .remove("score_cookie_header")
            .remove("score_records_json")
            .remove("score_updated_at_millis")
            .remove("academic_cache_json")
            .remove("schedule_cache_json")
            .remove("monitor_desired")
            .remove("monitor_last_check_millis")
            .remove("monitor_consecutive_failures")
            .remove("monitor_last_error")
            .apply()
        // 会话终止（登出/恢复失败清理）：镜像置 false，Widget 周期 worker
        // 绝不以残留凭据发起 CAS（登出后幽灵登录，见 writeWidgetLoggedInMirror 注释）
        writeWidgetLoggedInMirror(appContext, false)
    }

    companion object {
        /** 明文镜像 prefs 文件名与键名（布尔开关非敏感）。 */
        const val WIDGET_MIRROR_PREFS = "widget_mirror"
        const val WIDGET_LOGGED_IN_MIRROR_KEY = "widget_logged_in"

        /**
         * 登录态镜像唯一写入口（非敏感布尔，明文 prefs）。
         *
         * 写点：[markLoginSucceeded] → true；[clearSession] / clearLoggedOutUi → false。
         * 周期 Widget 刷新 worker 运行在后台进程、读不到组合内存登录态，而凭据
         * （学号/密码）按设计登出残留——worker 不复核登录态会以残留凭据发起教务
         * CAS 登录（登出后幽灵登录 = 风控暴露，同款守卫见 ScoreSectionState.refresh）。
         *
         * 只存明文镜像，不再双写加密 prefs：此前两次独立的 apply()，进程在中间被杀
         * 会出现「渲染路径读到已登出、逻辑路径读到已登录」的分叉。
         *
         * 不提供 Boolean getter：「缺键」必须能表示成未知，不能被某个默认值吞掉
         * （这里以前有默认 true 的 getter，与注释正好相反）。
         */
        fun writeWidgetLoggedInMirror(context: Context, loggedIn: Boolean) {
            context.applicationContext
                .getSharedPreferences(WIDGET_MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WIDGET_LOGGED_IN_MIRROR_KEY, loggedIn)
                .apply()
        }

        /**
         * Widget 周期 worker 专用登录态读取（三态）：null = 键缺失（升级前旧装、
         * 登录态未知，调用方按「允许」处理维持现状），false = 已登出（禁止 CAS）。
         * 只读明文镜像，不触碰加密 prefs（Keystore）。
         */
        fun readWidgetLoggedInMirror(context: Context): Boolean? {
            val plain = context.applicationContext
                .getSharedPreferences(WIDGET_MIRROR_PREFS, Context.MODE_PRIVATE)
            if (plain.contains(WIDGET_LOGGED_IN_MIRROR_KEY)) {
                return plain.getBoolean(WIDGET_LOGGED_IN_MIRROR_KEY, true)
            }
            return null
        }

        @Volatile
        private var instance: AssistantSettings? = null

        /** 进程级单例：加密 prefs 只初始化一次，后续复用，加快冷启动与后台唤醒。 */
        operator fun invoke(context: Context): AssistantSettings {
            val current = instance
            if (current != null) return current
            return synchronized(this) {
                instance ?: AssistantSettings(context).also { instance = it }
            }
        }

        /** 加密 prefs 是否已构建完成（组合期门禁快路径：已就绪则无需等待占位帧）。 */
        fun isReady(): Boolean = cachedPrefs != null

        /** 加密 prefs 进程级缓存：MasterKey/EncryptedSharedPreferences 只建一次。 */
        @Volatile
        private var cachedPrefs: android.content.SharedPreferences? = null

        private const val ENCRYPTED_PREFS_NAME = "xmu_assistant"

        /** AndroidKeyStore 里 MasterKey 的默认别名（androidx.security 的固定值）。 */
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        /** 存储重置痕迹放明文 prefs：重置瞬间加密 prefs 正不可用，不能存在里面。 */
        private const val STORAGE_FLAG_PREFS = "xmu_storage_flags"
        private const val STORAGE_RESET_AT_KEY = "secure_storage_reset_at"

        private fun encryptedPrefs(context: Context): android.content.SharedPreferences {
            val current = cachedPrefs
            if (current != null) return current
            return synchronized(this) {
                cachedPrefs ?: openEncrypted(context).also { cachedPrefs = it }
            }
        }

        /**
         *Keystore 失效自愈。
         *
         * EncryptedSharedPreferences 在系统升级、ROM 恢复、切换用户、备份还原之后，
         * 可能抛 AEADBadTagException / KeyStoreException / IOException。此前没有任何兜底，
         * 异常从组合期 produceState 抛进 IO 协程 → 用户打开就闪退，且除卸载重装无路可走。
         * 现在改为：删掉损坏的 prefs 与失效的 master key，重建一份空存储，并留一次性提示标记。
         * 代价是丢登录态，远好于永久闪退。
         */
        private fun openEncrypted(context: Context): android.content.SharedPreferences =
            try {
                buildEncrypted(context)
            } catch (error: Throwable) {
                if (!isStorageFailure(error)) throw error
                resetBrokenStorage(context, error)
                buildEncrypted(context)
            }

        private fun buildEncrypted(context: Context): android.content.SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            // AEADBadTagException 只在真正解密内容时才抛，create 本身不校验已有内容。
            // 主动读一次，让损坏在这里暴露，而不是拖到某个模块的解析路径里静默变成空数据。
            prefs.getString("username", null)
            return prefs
        }

        /** 只认存储类异常，别把 OOM / 编程错误也吞成「重置存储」。 */
        private fun isStorageFailure(error: Throwable): Boolean =
            error is java.security.GeneralSecurityException ||
                error is java.io.IOException ||
                error.javaClass.name.contains("InvalidProtocolBufferException")

        private fun resetBrokenStorage(context: Context, error: Throwable) {
            runCatching { context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME) }
            runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(MASTER_KEY_ALIAS)
            }
            context.getSharedPreferences(STORAGE_FLAG_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(STORAGE_RESET_AT_KEY, System.currentTimeMillis())
                .apply()
            android.util.Log.w("AssistantSettings", "安全存储已重置（Keystore 失效），登录态已丢弃", error)
        }

        /**
         * 消费一次性「安全存储已重置」提示。返回 true 表示本次启动发生过自愈，
         * 调用方应提示用户重新登录。标记读取即清除，保证只提示一次。
         */
        fun consumeStorageResetNotice(context: Context): Boolean {
            val flags = context.applicationContext
                .getSharedPreferences(STORAGE_FLAG_PREFS, Context.MODE_PRIVATE)
            if (!flags.contains(STORAGE_RESET_AT_KEY)) return false
            flags.edit().remove(STORAGE_RESET_AT_KEY).apply()
            return true
        }
    }
}

private object RankStorageLock
