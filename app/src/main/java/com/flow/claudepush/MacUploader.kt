package com.flow.claudepush

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MacUploader {

    fun upload(
        context: Context,
        uri: Uri,
        host: String,
        port: Int,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        Thread {
            try {
                val filename = getFileName(context, uri)
                val encoded = URLEncoder.encode(filename, "UTF-8")
                val boundary = "----${System.currentTimeMillis()}"

                val url = URL("http://$host:$port/push?filename=$encoded")

                // Bind connection to WiFi network to avoid routing over cellular
                val wifiNetwork = PushService.getWifiNetwork()
                val conn = if (wifiNetwork != null) {
                    Log.d("MacUploader", "Using WiFi-bound connection")
                    wifiNetwork.openConnection(url) as HttpURLConnection
                } else {
                    Log.d("MacUploader", "No WiFi network bound, using default connection")
                    url.openConnection() as HttpURLConnection
                }

                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.connectTimeout = 5000
                conn.readTimeout = 300_000 // 5 min for large files

                val input = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                conn.outputStream.buffered().use { out ->
                    val header = "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                    out.write(header.toByteArray())
                    input.use { it.copyTo(out) }
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                    out.flush()
                }

                val code = conn.responseCode
                conn.disconnect()

                if (code == 200) {
                    onResult(true, filename)
                } else {
                    onResult(false, "HTTP $code")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Upload failed")
            }
        }.start()
    }

    private fun getFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return "file_${System.currentTimeMillis()}"
    }
}
