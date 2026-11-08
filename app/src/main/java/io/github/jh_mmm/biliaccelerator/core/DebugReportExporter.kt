package io.github.jh_mmm.biliaccelerator.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DebugReportExporter {

    private const val MIME_TYPE = "text/plain"
    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)

    fun createFileName(now: Long = System.currentTimeMillis()): String {
        val timestamp = java.time.LocalDateTime.ofInstant(
            Instant.ofEpochMilli(now),
            ZoneId.systemDefault()
        ).format(fileNameFormatter)
        return "BiliAccelerator-debug-${timestamp}.txt"
    }

    val supportsDirectDownloads: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveFileToDownloads(context: Context, fileName: String, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("无法在 Download 目录创建报告文件")
        if (!file.isFile || file.length() <= 0L) {
            resolver.delete(uri, null, null)
            throw IOException("Debug 报告缓存文件不存在")
        }
        var committed = false
        try {
            resolver.openOutputStream(uri, "wt").use { stream ->
                if (stream == null) throw IOException("无法打开报告文件输出流")
                file.inputStream().use { input -> input.copyTo(stream) }
                stream.flush()
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = resolver.update(uri, publishValues, null, null)
            if (updated <= 0) throw IOException("发布报告文件失败")
            committed = true
            return uri
        } finally {
            if (!committed) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    fun writeCacheFile(context: Context, fileName: String, content: String): File {
        val directory = File(context.cacheDir, "debug-reports")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建 Debug 报告缓存目录")
        }
        val safeName = File(fileName).name
        if (safeName.isBlank() || safeName != fileName) {
            throw IOException("非法 Debug 报告文件名")
        }
        val file = File(directory, safeName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun copyCacheFileToUri(context: Context, uri: Uri, file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("Debug 报告缓存文件不存在")
        }
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            if (output == null) throw IOException("无法打开所选报告文件")
            file.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        }
    }

    fun deleteCacheFile(file: File) {
        runCatching { file.delete() }
    }
}
