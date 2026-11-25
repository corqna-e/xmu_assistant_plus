package com.xmu.assistant

import android.content.Context
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 证明 PDF 的本地落盘位置：`filesDir/rank/<记录号>-<取得时间>.pdf`。
 *
 * 名次、人数、数据日期等解析结果仍写在 prefs 里，所以文件被删掉也照样能显示结果；
 * 文件只承担「导出 / 再次分享」这一件事，prefs 里因此不再背着几百 KB 的 base64。
 */
internal object RankPdfStore {
    private const val DIR = "rank"

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun nameFor(recordId: String, obtainedAt: Long): String {
        val safeId = recordId.filter { it.isLetterOrDigit() }.take(24).ifBlank { "rank" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date(obtainedAt))
        return "$safeId-$stamp.pdf"
    }

    fun write(context: Context, recordId: String, obtainedAt: Long, bytes: ByteArray): String {
        val dir = dir(context).apply { mkdirs() }
        val name = nameFor(recordId, obtainedAt)
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun read(context: Context, name: String): ByteArray? {
        if (name.isBlank()) return null
        val file = File(dir(context), name)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    fun exists(context: Context, name: String): Boolean =
        name.isNotBlank() && File(dir(context), name).isFile

    fun delete(context: Context, name: String) {
        if (name.isBlank()) return
        runCatching { File(dir(context), name).delete() }
    }

    /** 退出登录 / 换号时整目录清掉：证明属于个人数据，不该留给下一个账号。 */
    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /**
     * 旧版本直接把整份 base64 写在 prefs 的 `pdf` 字段里。首次读到这种值时落成文件，
     * 写回时字段换成文件名，prefs 里的大字符串随之消失。
     */
    fun migrateLegacy(context: Context, result: RankResult): String? {
        if (!result.pdfLegacy || result.pdfFile.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(result.pdfFile, Base64.NO_WRAP)
            if (bytes.isEmpty()) null else write(context, result.recordId, result.obtainedAt, bytes)
        }.getOrNull()
    }
}
