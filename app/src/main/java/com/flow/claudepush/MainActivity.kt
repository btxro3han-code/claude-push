package com.flow.claudepush

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.flow.claudepush.ui.FileListScreen
import com.flow.claudepush.ui.ImageViewScreen
import com.flow.claudepush.ui.TextViewScreen
import com.flow.claudepush.ui.theme.ClaudePushTheme
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    companion object {
        private const val MAC_NOT_FOUND_MSG = "Mac未发现，请先用/push从Mac推一个文件过来"
    }

    private lateinit var repo: FileRepository
    private val fileChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshTrigger.value++
        }
    }
    private val networkChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            networkTrigger.value++
        }
    }
    private val refreshTrigger = mutableIntStateOf(0)
    private val networkTrigger = mutableIntStateOf(0)

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* don't care about result, service works without it */ }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) sendLatestScreenshot()
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            uploadToMac(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install crash handler to capture stack traces
        CrashHandler(this).install()

        // Check for crash from last run
        val lastCrash = CrashHandler.getLastCrash(this)
        if (lastCrash != null) {
            Log.e("MainActivity", "Last crash:\n$lastCrash")
            // Save crash log to push dir so user can see it
            try {
                val crashFile = java.io.File(filesDir, "crash_report.txt")
                crashFile.writeText(lastCrash)
            } catch (_: Exception) {}
        }

        repo = FileRepository(this)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Request battery optimization whitelist (prevents vivo from killing service)
        requestBatteryWhitelist()

        // Auto-start server
        try { startPushService() } catch (_: Exception) {}

        // Handle share intent
        handleShareIntent(intent)

        setContent {
            ClaudePushTheme {
                // Show crash report dialog if available
                if (lastCrash != null) {
                    var showCrash by remember { mutableStateOf(true) }
                    if (showCrash) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showCrash = false },
                            title = { androidx.compose.material3.Text("上次崩溃日志") },
                            text = {
                                androidx.compose.foundation.layout.Box(
                                    modifier = androidx.compose.ui.Modifier.heightIn(max = 400.dp)
                                ) {
                                    androidx.compose.foundation.lazy.LazyColumn {
                                        item {
                                            androidx.compose.material3.Text(
                                                lastCrash,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = { showCrash = false }) {
                                    androidx.compose.material3.Text("OK")
                                }
                            }
                        )
                    }
                }
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(PushService.ACTION_FILE_CHANGED)
        val networkFilter = IntentFilter(PushService.ACTION_NETWORK_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fileChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(networkChangeReceiver, networkFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fileChangeReceiver, filter)
            registerReceiver(networkChangeReceiver, networkFilter)
        }
        refreshTrigger.value++
        // Re-check Mac connection when coming back to app
        Thread { PushService.recheckMac() }.start()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(fileChangeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(networkChangeReceiver) } catch (_: Exception) {}
    }

    @Composable
    private fun App() {
        val trigger by refreshTrigger
        val netTrigger by networkTrigger
        val files = remember(trigger) { repo.list() }
        val ip = remember(netTrigger) { PushService.getWifiIp(this@MainActivity) }
        val wifiName = remember(netTrigger) { getWifiName() }
        val hasWifi = remember(netTrigger) { PushService.getWifiNetwork() != null }
        var isRunning by remember { mutableStateOf(true) }
        var viewingFile by remember { mutableStateOf<Pair<File, String>?>(null) }

        // Poll Mac connection status
        var macConnected by remember { mutableStateOf(false) }
        LaunchedEffect(netTrigger) {
            while (true) {
                macConnected = PushService.macHost != null
                kotlinx.coroutines.delay(2000)
            }
        }

        val current = viewingFile
        if (current != null) {
            val (file, type) = current
            when (type) {
                "image" -> ImageViewScreen(file) { viewingFile = null }
                "text" -> TextViewScreen(file) { viewingFile = null }
            }
        } else {
            FileListScreen(
                ip = ip,
                wifiName = wifiName,
                hasWifi = hasWifi,
                isRunning = isRunning,
                files = files,
                macConnected = macConnected,
                onToggleServer = {
                    if (isRunning) {
                        stopPushService()
                        isRunning = false
                    } else {
                        startPushService()
                        isRunning = true
                    }
                },
                onFileClick = { rf ->
                    val file = repo.get(rf.name) ?: return@FileListScreen
                    when (rf.type) {
                        "apk" -> {
                            if (ApkInstaller.canInstall(this@MainActivity)) {
                                ApkInstaller.install(this@MainActivity, file)
                            } else {
                                Toast.makeText(this@MainActivity, "Please allow installing unknown apps", Toast.LENGTH_LONG).show()
                                ApkInstaller.openInstallSettings(this@MainActivity)
                            }
                        }
                        "image" -> viewingFile = file to "image"
                        "text" -> viewingFile = file to "text"
                        else -> {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this@MainActivity, "${packageName}.fileprovider", file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try { startActivity(intent) } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "No app to open this file", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onDeleteFile = { rf ->
                    repo.delete(rf.name)
                    refreshTrigger.intValue++
                },
                onHideFile = { rf ->
                    repo.hide(rf.name)
                    refreshTrigger.intValue++
                },
                onSendToMac = {
                    withMac { _, _ -> filePicker.launch("*/*") }
                },
                onSendClipboard = { sendClipboardToMac() },
                onSendScreenshot = { requestScreenshotSend() }
            )
        }
    }

    // ── WiFi Info ─────────────────────────────────────────────

    private fun getWifiName(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val ssid = info?.ssid
            if (ssid != null && ssid != "<unknown ssid>" && ssid != "0x") {
                ssid.removeSurrounding("\"")
            } else null
        } catch (_: Exception) { null }
    }

    // ── Mac host resolution ──────────────────────────────────

    private fun getMacHost(): String? {
        return PushService.macHost
            ?: getSharedPreferences("claude_push", MODE_PRIVATE).getString("mac_host", null)
    }

    private fun getMacPort(): Int {
        return if (PushService.macHost != null) PushService.macPort
            else getSharedPreferences("claude_push", MODE_PRIVATE).getInt("mac_port", 18081)
    }

    /** Run block with Mac host/port, or show toast if Mac not found. */
    private inline fun withMac(block: (host: String, port: Int) -> Unit) {
        val host = getMacHost()
        if (host == null) {
            Toast.makeText(this, MAC_NOT_FOUND_MSG, Toast.LENGTH_LONG).show()
            return
        }
        block(host, getMacPort())
    }

    // ── Upload to Mac ───────────────────────────────────────

    private fun uploadToMac(uri: Uri) {
        val host = getMacHost()
        val port = getMacPort()

        Log.i("MainActivity", "uploadToMac: host=$host port=$port")

        if (host == null) {
            Toast.makeText(this, MAC_NOT_FOUND_MSG, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "正在发送到 $host:$port ...", Toast.LENGTH_SHORT).show()
        MacUploader.upload(this, uri, host, port) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "已发送: $msg", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "发送失败: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Clipboard to Mac ────────────────────────────────────

    private fun sendClipboardToMac() = withMac { host, port ->
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isBlank()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在发送剪贴板到Mac...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val json = JSONObject().put("text", text)
                val url = URL("http://$host:$port/clipboard")
                val wifiNetwork = PushService.getWifiNetwork()
                val conn = if (wifiNetwork != null) {
                    wifiNetwork.openConnection(url) as HttpURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                runOnUiThread {
                    if (code == 200) {
                        val preview = if (text.length > 30) text.take(30) + "..." else text
                        Toast.makeText(this, "已发送到Mac剪贴板: $preview", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "发送失败: HTTP $code", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ── Screenshot to Mac ───────────────────────────────────

    private fun requestScreenshotSend() = withMac { _, _ ->
        // Check permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                storagePermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
                return
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                return
            }
        }
        sendLatestScreenshot()
    }

    private fun sendLatestScreenshot() {
        // Query MediaStore for latest screenshot
        val uri = findLatestScreenshot()
        if (uri == null) {
            Toast.makeText(this, "未找到截图", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在发送最新截图到Mac...", Toast.LENGTH_SHORT).show()
        uploadToMac(uri)
    }

    private fun findLatestScreenshot(): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        // Look for screenshots - common paths on different phones
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%Screenshot%", "%screenshot%")

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idCol)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    // ── Share Intent ────────────────────────────────────────

    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val uris = mutableListOf<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) uris.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (list != null) uris.addAll(list)
            }
            else -> return
        }

        if (uris.isEmpty()) return

        Toast.makeText(this, "准备发送 ${uris.size} 个文件到Mac...", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            for (uri in uris) {
                uploadToMac(uri)
            }
        }, 1500)
    }

    // ── Battery Optimization ────────────────────────────────

    private fun requestBatteryWhitelist() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // ── Service Control ─────────────────────────────────────

    private fun startPushService() {
        val intent = Intent(this, PushService::class.java)
        startForegroundService(intent)
    }

    private fun stopPushService() {
        stopService(Intent(this, PushService::class.java))
    }
}
