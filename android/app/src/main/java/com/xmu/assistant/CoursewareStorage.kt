package com.xmu.assistant

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/** 课件在公共下载目录里统一放进这个子文件夹，名字与用户在文件管理器里看到的一致。 */
internal const val COURSEWARE_FOLDER_NAME = "xmu助手课件"

/**
 * 课件保存目录（v50 重做）。
 *
 * v18~v49 走 Storage Access Framework：点「下载」先弹引导，让用户选一次目录并点
 * 「允许使用此文件夹」，课件写进授权目录。实机反馈这套流程有两大问题：
 * 一次下载的文件被劈成两处（授权目录一份、公共下载一份），用户找不到；
 * 而 Android 10 起其实根本不需要授权 —— 应用可以通过 MediaStore 往公共
 * 「下载」目录写文件，不申请任何存储权限、也不需要用户选目录。
 *
 *改为唯一落点：公共「下载/xmu助手课件」。
 * - Android 10 及以上（API 29+）：MediaStore.Downloads + RELATIVE_PATH 写入，
 *零权限、零授权；文件在「文件管理 → 下载 → xmu助手课件」里直接可见。
 * - Android 8/9（API 26~28）：系统要求存储权限才能写公共目录，这里不额外引入
 *运行时权限流程，改为留在应用专属目录（应用专属目录 → xmu助手课件），
 *并在界面上如实说明位置，不谎称已保存到公共下载目录。
 */
internal object CoursewareStorage {
    private const val TAG = "XmuCourseware"
    private const val PREFS = "courseware_storage"
    /**已下载课件 id 集合（稳定 id：activityId-index-referenceId），跨刷新/重启保留。 */
    private const val KEY_DOWNLOADED = "downloaded_ids"
    /**各课程上次抓到的课件 id 基线（用于判定"新增"）。 */
    private const val KEY_SEEN_PREFIX = "seen_ids_"
    /**各课程"待看新增"课件 id（下载完对应课件后才移除）。 */
    private const val KEY_NEW_PREFIX = "new_ids_"

    /** 公共下载目录下的相对路径（MediaStore 用），与 COURSEWARE_FOLDER_NAME 保持一致。 */
    private const val PUBLIC_RELATIVE_PATH = "Download/$COURSEWARE_FOLDER_NAME"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**记录下载成功/入口已保存的课件，供列表显示「已下载」标记。 */
    fun markDownloaded(context: Context, id: String) {
        if (id.isBlank()) return
        val ids = downloadedIds(context).toMutableSet()
        if (ids.add(id)) prefs(context).edit().putStringSet(KEY_DOWNLOADED, ids).apply()
    }

    fun downloadedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DOWNLOADED, emptySet()).orEmpty()

    /**把某课程本次抓到的 id 集合交给判定器，更新基线/待看新增。 */
    fun observeFetched(context: Context, courseId: String, ids: Set<String>) {
        if (courseId.isBlank()) return
        val p = prefs(context)
        val previous = if (p.contains(KEY_SEEN_PREFIX + courseId)) {
            p.getStringSet(KEY_SEEN_PREFIX + courseId, emptySet()).orEmpty()
        } else null
        val pending = p.getStringSet(KEY_NEW_PREFIX + courseId, emptySet()).orEmpty()
        val state = CoursewareNewMarker.observeFetch(previous, ids, pending)
        p.edit()
            .putStringSet(KEY_SEEN_PREFIX + courseId, state.seen)
            .putStringSet(KEY_NEW_PREFIX + courseId, state.pendingNew)
            .apply()
    }

    /**某课程当前待看新增的课件 id（尚未下载的新课件）。 */
    fun pendingNewIds(context: Context, courseId: String): Set<String> =
        prefs(context).getStringSet(KEY_NEW_PREFIX + courseId, emptySet()).orEmpty()

    /**某科所有待看新增 id（供 UI 批量判断"有新增"）。 */
    fun allPendingNewIds(context: Context): Map<String, Set<String>> {
        val p = prefs(context)
        val result = mutableMapOf<String, Set<String>>()
        p.all.forEach { (key, value) ->
            if (key.startsWith(KEY_NEW_PREFIX) && value is Set<*>) {
                result[key.removePrefix(KEY_NEW_PREFIX)] = value.filterIsInstance<String>().toSet()
            }
        }
        return result
    }

    /**下载成功一个课件后，把它从所有课程的"待看新增"里移除（id 全局唯一）。 */
    fun consumeNewId(context: Context, id: String) {
        if (id.isBlank()) return
        val p = prefs(context)
        val editor = p.edit()
        var changed = false
        p.all.forEach { (key, value) ->
            if (key.startsWith(KEY_NEW_PREFIX) && value is Set<*>) {
                val ids = value.filterIsInstance<String>().toSet()
                if (id in ids) {
                    editor.putStringSet(key, CoursewareNewMarker.afterDownload(ids, id))
                    changed = true
                }
            }
        }
        if (changed) editor.apply()
    }

    /**按课程名+学年学期生成下载子文件夹名；替换文件系统禁用字符，避免非法路径。
     *与课件页分组标题一致："2025-2026 · 第一学期"。 */
    fun courseFolderName(course: CourseSummary): String {
        val year = courseYear(course.term) ?: course.term.trim().ifBlank { "未标注学年" }
        val semester = courseSemesterLabel(course.semesterCode, course.term)
        val label = if (semester == "全部" || semester.isBlank()) year else "$year · $semester"
        val raw = "${course.title.trim()} ($label)"
        val sanitized = raw.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").take(80).trim()
        return sanitized.ifBlank { "未命名课程" }
    }

    /** 当前系统能否免授权写公共「下载」目录。 */
    fun canWritePublicDownloads(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 给用户看的保存位置描述（不要暴露 content:// 之类的技术串）。 */
    fun describe(context: Context): String = if (canWritePublicDownloads()) {
        "我的文件 → 下载 → $COURSEWARE_FOLDER_NAME → 各课程文件夹"
    } else {
        "应用专属目录 → $COURSEWARE_FOLDER_NAME → 各课程文件夹"
    }

    /**
     * 把已落盘的文件发布到公共「下载/xmu助手课件/<课程文件夹>」，同名覆盖。
     *@param courseFolder 课程名+学期生成的子文件夹；空串则退回根目录（兼容旧调用）。
     * 返回 false 表示没有写入成功，调用方应如实报错（文件仍留在中转目录）。
     */
    fun publishToPublicDownloads(context: Context, file: File, courseFolder: String = ""): Boolean {
        if (!canWritePublicDownloads()) return false
        return runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = if (courseFolder.isBlank()) PUBLIC_RELATIVE_PATH else "$PUBLIC_RELATIVE_PATH/$courseFolder"
            // 先找公共目录里同名同目录的旧记录，有就覆盖写。
            // 旧实现每次都 insert，同一份课件重复下载会在「下载/xmu助手课件」里
            // 堆出「课件(1).pdf」「课件(2).pdf」…，白占手机空间。
            // RELATIVE_PATH 在不同 ROM 上可能带结尾斜杠，故按同名取候选后在代码里
            // 归一化比对目录；不能用 LIKE "路径%" —— 那会把「数学」和「数学进阶」
            // 两个科目的同名文件认成同一份，覆盖掉另一科的文件。
            val existingId = runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH),
                    "${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(file.name),
                    null,
                )?.use { cursor ->
                    val targetDir = relativePath.trimEnd('/')
                    var found: Long? = null
                    while (found == null && cursor.moveToNext()) {
                        val storedDir = cursor.getString(1).orEmpty().trimEnd('/')
                        if (storedDir == targetDir) found = cursor.getLong(0)
                    }
                    found
                }
            }.getOrNull()
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, guessMimeType(file.name))
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = if (existingId != null) {
                ContentUris.withAppendedId(collection, existingId).also { target ->
                    resolver.update(target, values, null, null)
                }
            } else {
                resolver.insert(collection, values) ?: return@runCatching false
            }
            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (copied) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                resolver.delete(uri, null, null)
                false
            }
        }.getOrElse {
            Log.w(TAG, "写入公共下载目录失败：${it.message}", it)
            false
        }
    }

    /**
     * 打开公共「下载」目录（交给系统文件管理器），失败返回 false 由调用方给文字指引。
     * 用 DocumentsUI 的 tree URI 定位到主存储的 Download 目录，不需要任何授权。
     */
    fun openFolder(context: Context): Boolean {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse {
            Log.w(TAG, "打开下载目录失败：${it.message}", it)
            false
        }
    }

    /** 应用专属目录下的课件文件夹（Android 8/9 的落点，也用作下载中转目录）。 */
    fun privateFolder(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, COURSEWARE_FOLDER_NAME)
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
