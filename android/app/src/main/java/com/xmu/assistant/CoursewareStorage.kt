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

/**
 * 课件不再单独占一个目录，与成绩长图、绩点证明 PDF 统一放进
 * 「下载/xmu助手plus」（见 [EXPORT_FOLDER_NAME]），课件再按课程分子文件夹。
 *
 * 旧目录（旧版本写出的「下载/xmu助手课件」）的识别与搬移全部移除。
 * 换过安装来源后旧文件属于上一个 uid，Android 11 起平台拒绝读取，零授权没有任何解法；
 * 留着入口只会让用户点一个必定失败的按钮。旧文件请用户自行在文件管理器处理。
 */

/**
 * 课件保存目录。
 *
 * 下载走 Storage Access Framework：点「下载」先弹引导，让用户选一次目录并点
 * 「允许使用此文件夹」，课件写进授权目录。实机反馈这套流程有两大问题：
 * 一次下载的文件被劈成两处（授权目录一份、公共下载一份），用户找不到；
 * 而 Android 10 起其实根本不需要授权 —— 应用可以通过 MediaStore 往公共
 * 「下载」目录写文件，不申请任何存储权限、也不需要用户选目录。
 *
 * 改为公共下载目录写入；与成绩长图、绩点证明 PDF 统一到
 * 「下载/xmu助手plus」，课件再按课程分子文件夹。
 * - Android 10 及以上（API 29+）：MediaStore.Downloads + RELATIVE_PATH 写入，
 *零权限、零授权；文件在「文件管理 → 下载 → xmu助手plus」里直接可见。
 * - Android 8/9（API 26~28）：系统要求存储权限才能写公共目录，这里不额外引入
 *运行时权限流程，改为留在应用专属目录（应用专属目录 → xmu助手plus），
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
    /**
     * 「标记为已下载、但本地扫不到文件」的课件 id。
     *
     * 只由「检查」写入：提示用户的文件可能被挪走/改名/删掉，界面上把角标显示成
     * 「？已下载」。与 [KEY_DOWNLOADED] 分开存 —— 已下载标记只增不减，这里可以随扫描
     * 结果增减，不会把用户下载过的记录抹掉。
     */
    private const val KEY_MISSING_LOCAL = "missing_local_ids"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**记录下载成功/入口已保存的课件，供列表显示「已下载」标记。 */
    fun markDownloaded(context: Context, id: String) {
        if (id.isBlank()) return
        val ids = downloadedIds(context).toMutableSet()
        if (ids.add(id)) prefs(context).edit().putStringSet(KEY_DOWNLOADED, ids).apply()
    }

    fun downloadedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DOWNLOADED, emptySet()).orEmpty()

    /** 「检查」的扫描结果：标记为已下载、但本地目录里找不到文件的课件 id。 */
    fun missingLocalIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MISSING_LOCAL, emptySet()).orEmpty()

    /** 整份覆盖缺失集合（每次「检查」按当时的扫描结果重写）。 */
    fun setMissingLocalIds(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_MISSING_LOCAL, ids).apply()
    }

    /** 某课件文件已确认存在（补标命中 / 重新下载成功）：把它从缺失集合里去掉。 */
    fun clearMissingLocal(context: Context, id: String) {
        if (id.isBlank()) return
        val ids = missingLocalIds(context).toMutableSet()
        if (ids.remove(id)) prefs(context).edit().putStringSet(KEY_MISSING_LOCAL, ids).apply()
    }

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

    /**
     * 真实路径，用于复制到剪贴板后直接粘进文件管理器地址栏。
     *
     * 与 [describe] 分开：[describe] 是给人读的说明文案（「我的文件 → 下载 → …」），
     * 带箭头和中文层级说明，复制出去不是能用的路径。
     */
    fun absolutePath(context: Context): String = if (canWritePublicDownloads()) {
        File(Environment.getExternalStorageDirectory(), EXPORT_RELATIVE_PATH).absolutePath
    } else {
        privateFolder(context).absolutePath
    }

    /** 给用户看的保存位置描述（不要暴露 content:// 之类的技术串）。 */
    fun describe(): String = if (canWritePublicDownloads()) {
        "我的文件 → 下载 → $EXPORT_FOLDER_NAME"
    } else {
        "应用专属目录 → $EXPORT_FOLDER_NAME"
    }

    /**
     * 把已落盘的文件发布到公共「下载/xmu助手plus/<课程文件夹>」，同名覆盖。
     * @param courseFolder 课程名+学期生成的子文件夹；空串则退回根目录（兼容旧调用）。
     * 返回 false 表示没有写入成功，调用方应如实报错（文件仍留在中转目录）。
     */
    fun publishToPublicDownloads(context: Context, file: File, courseFolder: String = ""): Boolean {
        if (!canWritePublicDownloads()) return false
        return runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = if (courseFolder.isBlank()) EXPORT_RELATIVE_PATH else "$EXPORT_RELATIVE_PATH/$courseFolder"
            // 先找公共目录里同名同目录的旧记录，有就覆盖写。
            // 旧实现每次都 insert，同一份课件重复下载会在统一目录里
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
     * 打开课件所在目录（交给系统文件管理器），失败返回 false 由调用方给文字指引。
     * 先定位到「下载/xmu助手plus」本身（统一目录），部分文件管理器不认这个
     * tree URI 时退回主存储的 Download 目录。
     */
    fun openFolder(context: Context): Boolean {
        val targets = listOf(
            "primary:Download/$EXPORT_FOLDER_NAME",
            "primary:Download",
        )
        targets.forEach { target ->
            val treeUri = Uri.parse(
                "content://com.android.externalstorage.documents/tree/" + Uri.encode(target),
            )
            val opened = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrElse {
                Log.w(TAG, "打开目录失败（$target）：${it.message}")
                false
            }
            if (opened) return true
        }
        return false
    }

    /** 应用专属目录下的课件文件夹（Android 8/9 的落点，也用作下载中转目录）。 */
    fun privateFolder(context: Context): File = ExportStorage.privateFolder(context)

    /**
     * 一次本地扫描的结果：索引 + 各来源的文件数。
     *
     * 计数用于让「检查」自证：报「新目录 0、应用目录 0」时，问题在目录不存在或匹配规则，
     * 而不是课件真被删了。
     */
    data class LocalScan(
        val index: Map<String, Set<String>>,
        val newFolder: Int,
        val privateFolder: Int,
    ) {
        val total: Int get() = newFolder + privateFolder
    }

    /**
     * 扫描本地已落盘的课件文件：返回「课程文件夹 → 文件名集合」，根目录用空串表示。
     *
     * 两条来源：MediaStore 下的新目录（正常路径，一次查询搞定）、直接文件路径列同一目录
     * （MediaStore 看不见但文件存在时兜底），再加应用专属目录（Android 8/9 的落点）。
     */
    fun scanLocal(context: Context): LocalScan {
        val result = mutableMapOf<String, MutableSet<String>>()
        val counts = intArrayOf(0, 0)
        fun add(bucket: Int, folder: String, name: String) {
            if (name.isBlank()) return
            val set = result.getOrPut(folder) { mutableSetOf() }
            if (set.add(name)) counts[bucket] += 1
        }
        if (canWritePublicDownloads()) {
            queryPublicFiles(context, EXPORT_RELATIVE_PATH).forEach { (folder, name) -> add(0, folder, name) }
        }
        // MediaStore 看不到、但文件确实存在时，直接列一遍目录补齐。
        runCatching {
            listDirectPublic(EXPORT_RELATIVE_PATH).forEach { (folder, name) -> add(0, folder, name) }
        }
        runCatching {
            privateFolder(context)?.listFiles()?.forEach { file ->
                if (file.isFile && !isPartialArtifact(file.name)) add(1, "", file.name)
            }
        }
        return LocalScan(result, counts[0], counts[1])
    }

    fun localFileIndex(context: Context): Map<String, Set<String>> = scanLocal(context).index

    /**
     * 查询某个公共目录根下的文件，返回「课程文件夹 → 文件名」。
     * LIKE 只能做前缀粗筛，必须在代码里再判一次「等于根目录或以 根目录/ 开头」，
     * 否则「下载/xmu助手plus备份」这类相邻目录的文件会被算进来。
     */
    private fun queryPublicFiles(context: Context, root: String): List<Pair<String, String>> {
        val base = root.trimEnd('/')
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("$base%"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0).orEmpty()
                    val path = cursor.getString(1).orEmpty().trimEnd('/')
                    if (path != base && !path.startsWith("$base/")) continue
                    // 根目录（下载/xmu助手plus 本身）的 folder 记空串；
                    // 更深的层级理论上不存在，取最后一段兜底。
                    val folder = path.removePrefix(base).trim('/').substringAfterLast('/')
                    rows += folder to name
                }
            }
        }.onFailure { Log.w(TAG, "查询公共下载目录失败（$base）：${it.message}") }
        return rows
    }

    /**
     * 直接用文件路径列举新目录下的文件（不经过 MediaStore）。
     *
     * Android 11+ 允许应用以路径方式访问自己写入的文件；若系统拒绝，listFiles 返回 null，
     * 这里静默跳过 —— 有 MediaStore 那条路兜着，不会因此报错。
     */
    private fun listDirectPublic(relativePath: String): List<Pair<String, String>> {
        val root = File(Environment.getExternalStorageDirectory(), relativePath)
        if (!root.isDirectory) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val children = runCatching { root.listFiles() }.getOrNull() ?: return emptyList()
        children.forEach { child ->
            if (child.isDirectory) {
                runCatching { child.listFiles() }.getOrNull()?.forEach { file ->
                    if (file.isFile && !isPartialArtifact(file.name)) result += child.name to file.name
                }
            } else if (child.isFile && !isPartialArtifact(child.name)) {
                result += "" to child.name
            }
        }
        return result
    }

    /**
     * 该课程文件夹下是否已有这个文件（含下载时为避重名生成的「名字 (2).ext」编号副本）。
     * 同名的文件被用户挪去别处就查不到 —— 这正是「不以本地扫描结果反向取消已下载标记」的原因。
     */
    fun containsLocalFile(index: Map<String, Set<String>>, folder: String, fileName: String): Boolean {
        val names = index[folder] ?: return false
        if (fileName in names) return true
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val suffix = if (dot > 0) fileName.substring(dot) else ""
        val numbered = Regex("^${Regex.escape(stem)} \\(\\d+\\)${Regex.escape(suffix)}$")
        return names.any { numbered.matches(it) }
    }

    /** 中断/取消下载留下的 .part 残片（含续传元数据），返回删除个数。 */
    fun deletePartialArtifacts(context: Context): Int {
        var deleted = 0
        runCatching {
            privateFolder(context).listFiles()?.forEach { file ->
                if (file.isFile && isPartialArtifact(file.name) &&
                    runCatching { file.delete() }.getOrDefault(false)
                ) {
                    deleted += 1
                }
            }
        }
        return deleted
    }

    private fun isPartialArtifact(name: String): Boolean =
        name.endsWith(".part") || name.endsWith(".part.meta") || name.endsWith(".part.meta.tmp")

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
