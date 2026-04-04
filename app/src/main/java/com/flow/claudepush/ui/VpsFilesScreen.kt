package com.flow.claudepush.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flow.claudepush.ApkInstaller
import com.flow.claudepush.NsdHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

data class VpsFile(
    val name: String,
    val size: String,       // raw size text from listing
    val sizeBytes: Long,    // parsed bytes for sorting
    val dateText: String,   // raw date text from listing
    val isDirectory: Boolean
)

private const val VPS_FILE_PORT = 6080
private val VPS_BASE_URL = "http://${NsdHelper.VPS_HOST}:$VPS_FILE_PORT"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpsFilesScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<VpsFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentPath by remember { mutableStateOf("/") }

    // Download state
    var downloadingFile by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var confirmFile by remember { mutableStateOf<VpsFile?>(null) }

    fun fetchListing(path: String) {
        loading = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = URL("$VPS_BASE_URL$path")
                    val conn = url.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val parsed: List<VpsFile>
                    try {
                        val html = conn.inputStream.bufferedReader().readText()
                        parsed = parseDirectoryListing(html)
                    } finally {
                        conn.disconnect()
                    }
                    // Fetch file sizes via HEAD requests
                    parsed.map { file ->
                        if (file.isDirectory || file.sizeBytes > 0) return@map file
                        try {
                            val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                            val filePath = if (path.endsWith("/")) "$path$encodedName" else "$path/$encodedName"
                            val fileUrl = URL("$VPS_BASE_URL$filePath")
                            val headConn = fileUrl.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                            headConn.requestMethod = "HEAD"
                            headConn.connectTimeout = 3000
                            headConn.readTimeout = 3000
                            val bytes = headConn.contentLengthLong
                            headConn.disconnect()
                            if (bytes > 0) {
                                file.copy(size = formatSize(bytes), sizeBytes = bytes)
                            } else file
                        } catch (_: Exception) { file }
                    }
                }
                files = result
                currentPath = path
                loading = false
            } catch (e: Exception) {
                error = e.message ?: "连接失败"
                loading = false
            }
        }
    }

    // Initial fetch
    LaunchedEffect(Unit) {
        fetchListing("/")
    }

    // Download confirmation dialog
    if (confirmFile != null) {
        val file = confirmFile!!
        AlertDialog(
            onDismissRequest = { confirmFile = null },
            title = { Text("下载文件") },
            text = {
                Text("${file.name}\n大小: ${file.size.ifEmpty { "未知" }}\n\n确认下载?")
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = confirmFile!!
                    confirmFile = null
                    downloadingFile = f.name
                    downloadProgress = 0f
                    scope.launch {
                        val savedFile = downloadFile(
                            context = context,
                            path = currentPath,
                            fileName = f.name,
                            onProgress = { downloadProgress = it }
                        )
                        downloadingFile = null
                        withContext(Dispatchers.Main) {
                            if (savedFile != null) {
                                Toast.makeText(context, "已下载: ${f.name}", Toast.LENGTH_SHORT).show()
                                if (f.name.lowercase().endsWith(".apk")) {
                                    if (ApkInstaller.canInstall(context)) {
                                        ApkInstaller.install(context, savedFile)
                                    } else {
                                        Toast.makeText(context, "请先允许安装未知应用", Toast.LENGTH_LONG).show()
                                        ApkInstaller.openInstallSettings(context)
                                    }
                                }
                            } else {
                                Toast.makeText(context, "下载失败: ${f.name}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFile = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPS文件") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath != "/") {
                            val parent = currentPath.trimEnd('/').substringBeforeLast('/') + "/"
                            fetchListing(if (parent == "/") "/" else parent)
                        } else {
                            onBack()
                        }
                    }) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = { fetchListing(currentPath) }) {
                        Text("↻", style = MaterialTheme.typography.titleMedium)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Path indicator
            if (currentPath != "/") {
                Text(
                    currentPath,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Download progress bar
            if (downloadingFile != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "正在下载: $downloadingFile",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "无法连接VPS",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { fetchListing(currentPath) }) {
                                Text("重试")
                            }
                        }
                    }
                }
                files.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "目录为空",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Text(
                        "${files.size} 个文件",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files, key = { it.name }) { file ->
                            VpsFileRow(
                                file = file,
                                isDownloading = downloadingFile == file.name,
                                onClick = {
                                    if (file.isDirectory) {
                                        val newPath = if (currentPath.endsWith("/"))
                                            "$currentPath${file.name}/"
                                        else
                                            "$currentPath/${file.name}/"
                                        fetchListing(newPath)
                                    } else if (downloadingFile == null) {
                                        confirmFile = file
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VpsFileRow(
    file: VpsFile,
    isDownloading: Boolean,
    onClick: () -> Unit
) {
    val icon = when {
        file.isDirectory -> "\uD83D\uDCC2"  // open folder
        file.name.lowercase().endsWith(".apk") -> "\uD83D\uDCE6"  // package
        file.name.lowercase().let {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
            it.endsWith(".gif") || it.endsWith(".webp")
        } -> "\uD83D\uDDBC"  // picture
        file.name.lowercase().let {
            it.endsWith(".txt") || it.endsWith(".md") || it.endsWith(".json") ||
            it.endsWith(".log") || it.endsWith(".csv")
        } -> "\uD83D\uDCC4"  // document
        else -> "\uD83D\uDCC1"  // folder/file
    }

    ListItem(
        modifier = Modifier.clickable(enabled = !isDownloading, onClick = onClick),
        headlineContent = {
            Text(
                file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            if (!file.isDirectory) {
                Text("${file.size}  |  ${file.dateText}")
            } else {
                Text(file.dateText)
            }
        },
        leadingContent = {
            Text(icon, style = MaterialTheme.typography.headlineMedium)
        },
        trailingContent = {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else if (!file.isDirectory) {
                Text(
                    "↓",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
    HorizontalDivider()
}

/**
 * Parse Python http.server HTML directory listing.
 * The format is an HTML table with columns: Name, Last modified, Size, Description
 */
private fun parseDirectoryListing(html: String): List<VpsFile> {
    val files = mutableListOf<VpsFile>()

    // Python http.server generates <li> items or <a href="..."> links in a <pre> block
    // or a table. Let's handle both formats.

    // Try table format first (Python 3 http.server uses table)
    val rowPattern = Pattern.compile(
        """<a href="([^"]+)">([^<]+)</a>\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s+(-|[\d.]+[KMG]?)""",
        Pattern.CASE_INSENSITIVE
    )
    val matcher = rowPattern.matcher(html)
    while (matcher.find()) {
        val href = matcher.group(1) ?: continue
        val name = matcher.group(2)?.trim() ?: continue
        val dateText = matcher.group(3)?.trim() ?: ""
        val sizeText = matcher.group(4)?.trim() ?: "-"

        // Skip parent directory link
        if (name == "../" || name == "..") continue

        val isDir = href.endsWith("/")
        val displayName = if (isDir) name.trimEnd('/') else name
        if (displayName.isBlank() || displayName == ".") continue

        files.add(VpsFile(
            name = displayName,
            size = if (isDir) "-" else sizeText,
            sizeBytes = parseSizeToBytes(sizeText),
            dateText = dateText,
            isDirectory = isDir
        ))
    }

    // If table parsing found nothing, try simpler <li><a href> pattern (Python http.server simple mode)
    if (files.isEmpty()) {
        val simplePattern = Pattern.compile("""<a href="([^"]+)">([^<]+)</a>""")
        val simpleMatcher = simplePattern.matcher(html)
        while (simpleMatcher.find()) {
            val href = simpleMatcher.group(1) ?: continue
            val name = simpleMatcher.group(2)?.trim() ?: continue
            if (name == "../" || name == ".." || name == "." || name.isBlank()) continue
            val isDir = href.endsWith("/")
            val displayName = if (isDir) name.trimEnd('/') else name
            files.add(VpsFile(
                name = displayName,
                size = "",
                sizeBytes = 0,
                dateText = "",
                isDirectory = isDir
            ))
        }
    }

    // Sort: directories first, then by name
    return files.sortedWith(compareByDescending<VpsFile> { it.isDirectory }.thenBy { it.name })
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "-"
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun parseSizeToBytes(sizeText: String): Long {
    if (sizeText == "-" || sizeText.isBlank()) return 0
    val text = sizeText.uppercase()
    return try {
        when {
            text.endsWith("G") -> (text.dropLast(1).toDouble() * 1024 * 1024 * 1024).toLong()
            text.endsWith("M") -> (text.dropLast(1).toDouble() * 1024 * 1024).toLong()
            text.endsWith("K") -> (text.dropLast(1).toDouble() * 1024).toLong()
            else -> text.toLongOrNull() ?: 0
        }
    } catch (_: Exception) { 0 }
}

/**
 * Download a file from VPS to the phone's Downloads folder.
 * Uses MediaStore on Android Q+, direct file on older.
 */
private suspend fun downloadFile(
    context: Context,
    path: String,
    fileName: String,
    onProgress: (Float) -> Unit
): File? = withContext(Dispatchers.IO) {
    try {
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val urlPath = if (path.endsWith("/")) "$path$encodedName" else "$path/$encodedName"
        val url = URL("$VPS_BASE_URL$urlPath")
        val conn = url.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000

        val totalSize = conn.contentLength.toLong()
        val inputStream = conn.inputStream

        // Save to Downloads via MediaStore (Android Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClaudePush")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { out ->
                val buf = ByteArray(8192)
                var downloaded = 0L
                var n: Int
                while (inputStream.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (totalSize > 0) {
                        onProgress(downloaded.toFloat() / totalSize)
                    }
                }
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            conn.disconnect()
            onProgress(1f)

            // For APK install, we need a real File path — copy to cache
            if (fileName.lowercase().endsWith(".apk")) {
                val cacheFile = File(context.cacheDir, fileName)
                resolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return@withContext cacheFile
            }

            // Return a placeholder file (MediaStore handled the actual save)
            val downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            File(downloadDir, "ClaudePush/$fileName")
        } else {
            // Legacy: direct file write
            val downloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ClaudePush"
            ).also { it.mkdirs() }
            val outFile = File(downloadDir, fileName)
            outFile.outputStream().use { out ->
                val buf = ByteArray(8192)
                var downloaded = 0L
                var n: Int
                while (inputStream.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (totalSize > 0) {
                        onProgress(downloaded.toFloat() / totalSize)
                    }
                }
            }
            conn.disconnect()
            onProgress(1f)
            outFile
        }
    } catch (e: Exception) {
        android.util.Log.e("VpsFiles", "Download failed: ${e.message}", e)
        null
    }
}
