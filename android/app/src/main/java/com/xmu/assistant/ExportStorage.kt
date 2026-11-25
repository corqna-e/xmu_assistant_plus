package com.xmu.assistant

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.OutputStream

/**
 * 统一导出目录：公共「下载/xmu助手plus」。
 *
 * 成绩长图、绩点证明 PDF、课程课件三类文件同放一个目录，用户在文件管理器里
 * 只需要记一处。（此前长图在「图片/xmu助手」，课件在「下载/xmu助手课件」，PDF 由用户
 * 自己选位置，三处分散。）
 *
 * 写入方式：
 * - Android 10+（API 29）：MediaStore.Downloads + RELATIVE_PATH，零权限、零授权。
 * - Android 8/9（API 26~28）：系统要求存储权限才能写公共目录，这里不引入运行时权限，
 * 改为写应用专属目录下的同名文件夹，界面如实说明位置，不谎称已存到公共下载目录。
 */
internal const val EXPORT_FOLDER_NAME = "xmu助手plus"

/** 公共下载目录下的相对路径（MediaStore 用），与 [EXPORT_FOLDER_NAME] 保持一致。 */
internal const val EXPORT_RELATIVE_PATH = "Download/$EXPORT_FOLDER_NAME"

internal object ExportStorage {
    private const val TAG = "XmuExport"

    fun canWritePublic(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 给用户看的保存位置描述（不暴露 content:// 之类的技术串）。 */
    fun describe(): String =
        if (canWritePublic()) "下载/$EXPORT_FOLDER_NAME" else "应用专属目录/$EXPORT_FOLDER_NAME"

    /** 应用专属目录下的同名文件夹（Android 8/9 的落点，也用作下载中转目录）。 */
    fun privateFolder(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, EXPORT_FOLDER_NAME).apply { runCatching { mkdirs() } }
    }

    /**
     * 把字节写进统一目录，返回给用户看的相对路径（如「下载/xmu助手plus/绩点证明-….pdf」）；
     * 失败返回 null，调用方必须如实报错，不能谎称已保存。
     */
    fun saveBytes(context: Context, displayName: String, mimeType: String, bytes: ByteArray): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = insertPending(context, displayName, mimeType) ?: return null
            return writeAndPublish(context, uri, displayName) { out -> out.write(bytes) }
        }
        return runCatching {
            File(privateFolder(context), displayName).outputStream().use { it.write(bytes) }
            "${describe()}/$displayName"
        }.getOrElse {
            Log.w(TAG, "写入应用专属目录失败：${it.message}", it)
            null
        }
    }

    /**
     * 保存位图（成绩长图）。API 29+ 返回可直接分享的 MediaStore uri；
     * Android 8/9 写应用专属目录后由调用方经 FileProvider 分享。
     */
    fun saveBitmap(context: Context, displayName: String, bitmap: Bitmap): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = insertPending(context, displayName, "image/png") ?: return null
        val saved = writeAndPublish(context, uri, displayName) { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "位图编码失败" }
        }
        return if (saved != null) uri else null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertPending(context: Context, displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            // 写完并关闭输出流之前不发布，避免其它应用读到半份文件。
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return runCatching {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrElse {
            Log.w(TAG, "创建下载目录记录失败：${it.message}", it)
            null
        }
    }

    /** 写内容并发布；失败删除残留记录，返回给用户看的完整路径。 */
    private inline fun writeAndPublish(
        context: Context,
        uri: Uri,
        displayName: String,
        write: (OutputStream) -> Unit,
    ): String? {
        val resolver: ContentResolver = context.contentResolver
        return try {
            val stream = resolver.openOutputStream(uri) ?: error("无法打开输出流")
            stream.use(write)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            "${describe()}/$displayName"
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Log.w(TAG, "写入统一导出目录失败：${error.message}", error)
            null
        }
    }
}
