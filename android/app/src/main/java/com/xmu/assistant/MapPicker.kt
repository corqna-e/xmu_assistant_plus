package com.xmu.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 *地图选点已改为自绘瓦片（见 TileMapPicker.kt），彻底不依赖高德 SDK。
 *公开栅格瓦片不需要任何 Key，此前的「地图设置 / 填写高德 Key」对话框
 * （MapKeyDialog）与 amapKey / saveAmapKey 兼容层已无调用点，一并删除。
 *
 * 本文件只剩两个仍在使用的符号：选点对话框转发与本地用量闸门。
 */

/** 每日允许打开地图选点的次数上限：纯本地闸门。 */
internal const val MAP_PICK_DAILY_LIMIT = 30

/** 兼容转发：真正的实现在 TileMapPicker.kt。 */
@Composable
internal fun MapPickerDialog(
    initialLatitude: Double?,
    initialLongitude: Double?,
    onPicked: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    TileMapPickerDialog(initialLatitude, initialLongitude, onPicked, onDismiss)
}

/** 本地用量闸门：按自然日计数，超过上限当天不再打开地图。 */
internal object MapUsageGuard {
    private const val PREFS = "map_usage_guard"
    private const val KEY_DATE = "date"
    private const val KEY_COUNT = "count"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun usedToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DATE, "") != today()) return 0
        return prefs.getInt(KEY_COUNT, 0)
    }

    fun tryConsume(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today()
        val used = if (prefs.getString(KEY_DATE, "") == day) prefs.getInt(KEY_COUNT, 0) else 0
        if (used >= MAP_PICK_DAILY_LIMIT) return false
        prefs.edit().putString(KEY_DATE, day).putInt(KEY_COUNT, used + 1).apply()
        return true
    }
}
