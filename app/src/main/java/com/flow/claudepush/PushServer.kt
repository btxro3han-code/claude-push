package com.flow.claudepush

import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val PORT = 18080

class PushServer(
    private val repo: FileRepository,
    private val onFileReceived: () -> Unit,
    private val onMacDetected: ((String, Int) -> Unit)? = null,
    private val onClipboardReceived: ((String) -> Unit)? = null
) : NanoHTTPD(PORT) {

    @Volatile
    private var lastDetectedMacIp: String? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Capture sender IP — if it's a Mac running our receiver, auto-save (deduped)
        val remoteIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
        if (remoteIp != null && method == Method.POST && remoteIp != lastDetectedMacIp) {
            detectMac(remoteIp)
        }

        return try {
            when {
                method == Method.GET && uri == "/status" -> serveStatus()
                method == Method.GET && uri == "/files" -> serveFileList()
                method == Method.DELETE && uri.startsWith("/files/") -> serveDelete(uri)
                method == Method.POST && uri == "/push" -> servePushFile(session)
                method == Method.POST && uri == "/push/text" -> servePushText(session)
                method == Method.POST && uri == "/clipboard" -> serveSetClipboard(session)
                method == Method.GET && uri == "/clipboard" -> serveGetClipboard()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message))
        }
    }

    /** Check if the sender is a Mac running our receiver */
    private fun detectMac(ip: String) {
        Thread {
            try {
                val port = NsdHelper.MAC_PORT
                val conn = URL("http://$ip:$port/status").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                if (code != 200) { conn.disconnect(); return@Thread }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (body.contains("macOS")) {
                    lastDetectedMacIp = ip
                    Log.i("PushServer", "Mac auto-detected from push: $ip:$port")
                    onMacDetected?.invoke(ip, port)
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun serveStatus(): Response {
        val obj = JSONObject()
            .put("device", Build.MODEL)
            .put("brand", Build.BRAND)
            .put("port", 18080)
            .put("version", "1.0")
            .put("files", repo.dir.listFiles()?.size ?: 0)
            .put("free_space_mb", repo.dir.freeSpace / 1024 / 1024)
            .put("device_id", getDeviceId())
            .put("platform", "Android")
        return json(Response.Status.OK, obj)
    }

    private fun getDeviceId(): String {
        // Stable device fingerprint: BRAND + MODEL + SERIAL (or ANDROID_ID fallback)
        val base = "${Build.BRAND}_${Build.MODEL}_${Build.FINGERPRINT.hashCode()}"
        return base.replace(" ", "-")
    }

    private fun serveFileList(): Response {
        val arr = JSONArray()
        for (f in repo.list()) {
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("size", f.size)
                    .put("timestamp", f.timestamp)
                    .put("type", f.type)
            )
        }
        return json(Response.Status.OK, JSONObject().put("files", arr))
    }

    private fun serveDelete(uri: String): Response {
        val name = uri.removePrefix("/files/")
        return if (repo.delete(name)) {
            onFileReceived()
            json(Response.Status.OK, JSONObject().put("deleted", name))
        } else {
            json(Response.Status.NOT_FOUND, JSONObject().put("error", "File not found"))
        }
    }

    private fun servePushFile(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)

        val tmpPath = files["file"]
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "No file field"))

        val params = session.parms
        val origName = params["filename"]
            ?: params["file"]?.takeIf { !it.contains("/") && it.contains(".") }
            ?: extractFilename(session)
            ?: "upload_${System.currentTimeMillis()}"

        val tmpFile = File(tmpPath)
        val saved = repo.save(tmpFile.inputStream(), origName)
        tmpFile.delete()
        onFileReceived()

        return json(
            Response.Status.OK,
            JSONObject()
                .put("name", saved.name)
                .put("size", saved.size)
                .put("type", saved.type)
        )
    }

    private fun readBody(session: IHTTPSession): String {
        val size = (session.headers["content-length"] ?: "0").toIntOrNull() ?: 0
        if (size <= 0) return ""
        val buf = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = session.inputStream.read(buf, read, size - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun servePushText(session: IHTTPSession): Response {
        val text = readBody(session)

        if (text.isBlank()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Empty text"))
        }

        val title = session.parms?.get("title")
        val filename = (title ?: "text_${System.currentTimeMillis()}") + ".txt"
        val saved = repo.save(text.byteInputStream(), filename)
        onFileReceived()

        return json(
            Response.Status.OK,
            JSONObject()
                .put("name", saved.name)
                .put("size", saved.size)
                .put("type", saved.type)
        )
    }

    private fun serveSetClipboard(session: IHTTPSession): Response {
        val body = readBody(session)

        val text = try {
            JSONObject(body).getString("text")
        } catch (_: Exception) {
            body
        }

        if (text.isBlank()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "empty"))
        }

        onClipboardReceived?.invoke(text)
        return json(Response.Status.OK, JSONObject().put("ok", true).put("chars", text.length))
    }

    private fun serveGetClipboard(): Response {
        // Can't read clipboard from background thread on Android, return empty
        return json(Response.Status.OK, JSONObject().put("text", "").put("note", "use UI to send clipboard"))
    }

    private fun extractFilename(session: IHTTPSession): String? {
        val contentDisp = session.headers?.entries
            ?.firstOrNull { it.key.equals("content-disposition", ignoreCase = true) }
            ?.value ?: return null
        val match = Regex("filename=\"?([^\"]+)\"?").find(contentDisp)
        return match?.groupValues?.get(1)
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())
}
