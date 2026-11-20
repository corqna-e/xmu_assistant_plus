package com.xmu.assistant

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 单项检测结果：PASS 已满足 / FAIL 未满足 / MANUAL 系统不提供查询接口，需人工确认。 */
internal enum class AuditState { PASS, FAIL, MANUAL }

/** 一条运行环境检查项。 */
internal data class AuditEntry(
    val key: String,
    val title: String,
    val summary: String,
    val state: AuditState,
    val detail: String,
)

/**
 * 运行环境权限自检：实机逐项检测通知/电池/自启动/后台/闹钟/目录/定位，
 * 并提供跳转到对应系统设置页的入口。所有系统调用一律 runCatching 兜底，
 * 任何异常都降级为 FAIL 或 MANUAL，绝不向外抛出。
 */
internal object PermissionAudit {

    /** 实机检测，返回固定 7 项、顺序固定。任何异常都必须降级为 FAIL 或 MANUAL，绝不抛出。 */
    fun inspect(context: Context): List<AuditEntry> = listOf(
        notificationEntry(context),
        batteryEntry(context),
        autostartEntry(),
        backgroundEntry(context),
        exactAlarmEntry(context),
        storageEntry(context),
        locationEntry(context),
    )

    /** 打开该项对应的系统设置页。返回 true 表示已成功发起跳转。 */
    fun openSettings(context: Context, key: String): Boolean = when (key) {
        "notification" -> openNotificationSettings(context)
        "battery" -> openBatterySettings(context)
        "autostart" -> openAutostartSettings(context)
        "background", "storage", "location" -> openAppDetails(context)
        "exact_alarm" -> ExamReminder.openExactAlarmSettings(context) || openAppDetails(context)
        else -> false
    }

    // ---------- 各项检测 ----------

    /** 通知：API 33+ 需 POST_NOTIFICATIONS 运行时授权，且总开关与渠道均未关闭。 */
    private fun notificationEntry(context: Context): AuditEntry {
        val runtimeGranted = runCatching {
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val notificationsEnabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
        val pass = runtimeGranted && notificationsEnabled
        return AuditEntry(
            key = "notification",
            title = "通知权限",
            summary = if (pass) "已允许，签到与考试提醒可正常送达"
            else "未允许，所有提醒都无法显示",
            state = if (pass) AuditState.PASS else AuditState.FAIL,
            detail = "在系统设置中允许本应用发送通知，并确认「签到提醒」渠道未被关闭。授权后返回本页会自动重新检查。",
        )
    }

    /** 电池优化：PowerManager.isIgnoringBatteryOptimizations 实测。 */
    private fun batteryEntry(context: Context): AuditEntry {
        val ignoring = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)
        return AuditEntry(
            key = "battery",
            title = "电池优化",
            summary = if (ignoring) "已忽略电池优化，后台不受电量策略限制"
            else "仍受电池优化限制，长时后台可能被系统休眠",
            state = if (ignoring) AuditState.PASS else AuditState.FAIL,
            detail = "将本应用设为「不限制」或「允许后台活动」。此项与自启动同时生效时，锁屏后被清理的概率显著下降。",
        )
    }

    // ---------- 自启动人工确认（改为落盘持久化） ----------
    //
    // 自启动没有系统查询接口，只能由用户现场核对后确认。确认结果以前只存在页面内存里，
    // 每次重新进入本页（进程未变也会因 remember 重建）都要重新确认，实际使用时每次
    // 打开教程页都被要求点一遍，属于打扰而非保护。现改为写入本机 prefs：确认一次长期
    // 有效，只有用户主动点「重新确认」或清除应用数据才会回到待确认状态。

    /** 已确认允许自启动的时间戳（毫秒）；0 表示尚未确认。 */
    fun autostartConfirmedAt(context: Context): Long = runCatching {
        context.getSharedPreferences(AUDIT_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_AUTOSTART_CONFIRMED_AT, 0L)
    }.getOrDefault(0L)

    /** 记录「已确认允许自启动」，同时返回本次确认的时间戳。 */
    fun confirmAutostart(context: Context): Long {
        val now = System.currentTimeMillis()
        runCatching {
            context.getSharedPreferences(AUDIT_PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_AUTOSTART_CONFIRMED_AT, now).apply()
        }
        return now
    }

    /** 撤销确认：回到「需人工确认」状态。 */
    fun clearAutostartConfirmation(context: Context) {
        runCatching {
            context.getSharedPreferences(AUDIT_PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_AUTOSTART_CONFIRMED_AT).apply()
        }
    }

    /**
     * 自启动：系统无公开查询接口，检测结果恒为 MANUAL，不编造。
     * 是否已允许由用户在现场核对后确认，确认结果落盘保存。
     */
    private fun autostartEntry(): AuditEntry = AuditEntry(
        key = "autostart",
        title = "自启动",
        summary = "系统不提供查询接口，需人工确认",
        state = AuditState.MANUAL,
        detail = "在手机管家或系统设置的自启动管理中允许本应用自启动，否则设备重启后签到监控不会自动恢复。确认允许后点击右侧「确认正常」。",
    )

    /** 后台运行：API 28+ 读 ActivityManager.isBackgroundRestricted，受限即 FAIL；API 26-27 一律 PASS。 */
    private fun backgroundEntry(context: Context): AuditEntry {
        val restricted = Build.VERSION.SDK_INT >= 28 && runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isBackgroundRestricted ?: true
        }.getOrDefault(true)
        return AuditEntry(
            key = "background",
            title = "后台运行",
            summary = if (restricted) "系统已限制本应用后台运行"
            else "后台运行未受系统限制",
            state = if (restricted) AuditState.FAIL else AuditState.PASS,
            detail = "关闭系统对本应用的「后台限制」，并避免长期开启省电模式。受限时监控服务会被随时停止。",
        )
    }

    /** 精确闹钟：API 31 以下不需要授权，直接 PASS；31+ 读 AlarmManager.canScheduleExactAlarms。 */
    private fun exactAlarmEntry(context: Context): AuditEntry {
        val canSchedule = Build.VERSION.SDK_INT < 31 || runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.canScheduleExactAlarms() ?: false
        }.getOrDefault(false)
        return AuditEntry(
            key = "exact_alarm",
            title = "精确闹钟",
            summary = if (canSchedule) "已授权，考试提醒可准点触发"
            else "未授权，考试提醒可能延迟数分钟",
            state = if (canSchedule) AuditState.PASS else AuditState.FAIL,
            detail = "在「闹钟和提醒」中允许本应用设定精确闹钟。仅影响考试提醒的准点性，不影响签到监控。",
        )
    }

    /**
     * 课件保存位置：课件统一写公共「下载/xmu助手课件」，无需任何授权，
     * 因此这一项不再有「待处理」状态，只需如实告知落点。
     */
    private fun storageEntry(context: Context): AuditEntry {
        val publicWritable = runCatching { CoursewareStorage.canWritePublicDownloads() }.getOrDefault(false)
        return AuditEntry(
            key = "storage",
            title = "课件保存位置",
            summary = if (publicWritable) {
                "课件保存到手机「下载 → xmu助手课件」，无需授权"
            } else {
                "当前系统版本无法写入公共下载目录，课件保存在应用专属目录"
            },
            state = AuditState.PASS,
            detail = "下载课件无需选目录、无需授权；文件保存后可在课件页点「打开下载目录」直接查看。",
        )
    }

    /** 定位权限：仅检测前台精确定位是否已授予，不主动请求、不涉及后台定位。 */
    private fun locationEntry(context: Context): AuditEntry {
        val granted = runCatching {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return AuditEntry(
            key = "location",
            title = "定位权限",
            summary = if (granted) "已授权，可用于现场采点"
            else "未授权，现场采点无法读取当前位置",
            state = if (granted) AuditState.PASS else AuditState.FAIL,
            detail = "仅在手动点击现场采点、或地图选点定位时读取一次位置，应用不做后台定位。",
        )
    }

    // ---------- 设置页跳转 ----------

    /** 应用详情页：所有项的最终兜底跳转目标。 */
    private fun openAppDetails(context: Context): Boolean = tryStart(context) {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 通知设置：优先直达本应用的通知页，失败回退应用详情页。 */
    private fun openNotificationSettings(context: Context): Boolean {
        val direct = tryStart(context) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return direct || openAppDetails(context)
    }

    /**
     * 电池优化：先试本应用的白名单请求弹窗（需 manifest 中的
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，携带 package: URI），
     * 失败退到系统电池优化列表页，再失败退到应用详情页。
     */
    private fun openBatterySettings(context: Context): Boolean {
        val direct = tryStart(context) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (direct) return true
        val list = tryStart(context) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return list || openAppDetails(context)
    }

    /** 各厂商自启动管理页的显式组件，按常见度排序，逐一 resolveActivity 探测。 */
    private val autostartComponents = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
    )

    /** 自启动：依次探测厂商管理页组件，可解析即跳转；三星不单独提供，全部落空回退应用详情页。 */
    private fun openAutostartSettings(context: Context): Boolean {
        val pm = context.packageManager
        for ((pkg, cls) in autostartComponents) {
            val started = runCatching {
                val intent = Intent()
                    .setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (started) return true
        }
        return openAppDetails(context)
    }

    /** 统一启动入口：所有 startActivity 都 runCatching 包裹，失败仅返回 false，不崩。 */
    private fun tryStart(context: Context, build: () -> Intent): Boolean =
        runCatching { context.startActivity(build()) }.isSuccess

    private const val AUDIT_PREFS = "permission_audit"
    private const val KEY_AUTOSTART_CONFIRMED_AT = "autostart_confirmed_at"
}
