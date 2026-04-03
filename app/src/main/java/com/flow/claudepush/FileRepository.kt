package com.flow.claudepush

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.InputStream

data class ReceivedFile(
    val name: String,
    val size: Long,
    val timestamp: Long,
    val type: String
)

class FileRepository(private val context: Context) {
    val dir: File = File(context.getExternalFilesDir(null), "received").also { it.mkdirs() }
    val outboxDir: File = File(context.getExternalFilesDir(null), "outbox").also { it.mkdirs() }
    private val hiddenFile = File(dir, ".hidden")
    private var hiddenCache: MutableSet<String>? = null

    private fun loadHidden(): MutableSet<String> {
        hiddenCache?.let { return it }
        val set = if (hiddenFile.exists()) hiddenFile.readLines().filter { it.isNotBlank() }.toMutableSet()
            else mutableSetOf()
        hiddenCache = set
        return set
    }

    private fun saveHidden(set: Set<String>) {
        hiddenFile.writeText(set.joinToString("\n"))
        hiddenCache = set.toMutableSet()
    }

    fun list(): List<ReceivedFile> {
        val hidden = loadHidden()
        return dir.listFiles()
            ?.filter { !it.name.startsWith(".") && it.name !in hidden }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.toReceivedFile() }
            ?: emptyList()
    }

    /** 从列表隐藏，不删除文件 */
    fun hide(name: String) {
        val set = loadHidden()
        set.add(name)
        saveHidden(set)
    }

    fun save(input: InputStream, filename: String): ReceivedFile {
        val safe = filename.replace(Regex("[/\\\\]"), "_")
        var target = File(dir, safe)
        if (target.exists()) {
            val base = safe.substringBeforeLast(".")
            val ext = safe.substringAfterLast(".", "")
            var i = 1
            while (target.exists()) {
                target = File(dir, if (ext.isNotEmpty()) "${base}_$i.$ext" else "${base}_$i")
                i++
            }
        }
        input.use { src -> target.outputStream().use { src.copyTo(it) } }

        // 图片/视频自动写入公共相册
        val received = target.toReceivedFile()
        if (received.type == "image" || received.type == "video") {
            copyToMediaStore(target, received)
        }

        return received
    }

    private fun copyToMediaStore(file: File, info: ReceivedFile) {
        try {
            val resolver = context.contentResolver
            val isVideo = info.type == "video"

            val collection = if (isVideo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val mimeType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                else -> if (isVideo) "video/*" else "image/*"
            }

            val subDir = if (isVideo) "Movies/ClaudePush" else "Pictures/ClaudePush"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, subDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(collection, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Log.i("FileRepository", "Saved to gallery: ${file.name} -> $uri")
            }
        } catch (e: Exception) {
            Log.e("FileRepository", "Failed to save to gallery: ${file.name}", e)
        }
    }

    fun get(name: String): File? {
        val f = File(dir, name)
        return if (f.exists() && f.parentFile == dir) f else null
    }

    fun delete(name: String): Boolean {
        val f = File(dir, name)
        return f.exists() && f.parentFile == dir && f.delete()
    }

    private fun File.toReceivedFile() = ReceivedFile(
        name = name,
        size = length(),
        timestamp = lastModified(),
        type = when (extension.lowercase()) {
            "apk" -> "apk"
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image"
            "mp4", "mov", "avi", "mkv", "webm" -> "video"
            "txt", "md", "json", "log", "csv", "xml", "html" -> "text"
            else -> "file"
        }
    )
}
