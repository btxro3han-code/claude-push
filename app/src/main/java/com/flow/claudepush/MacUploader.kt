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
                conn.readTimeout = 600_000 // 10 min for large files

                // Calculate total content length to use fixed-length streaming
                // This avoids buffering the entire file in memory (OOM on 300MB+)
                // and avoids chunked TE which Python's HTTPServer handles poorly
                val headerBytes = ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
                val tailBytes = "\r\n--$boundary--\r\n".toByteArray()
                val fileSize = getFileSize(context, uri)
                val totalLength = headerBytes.size.toLong() + fileSize + tailBytes.size.toLong()
                conn.setFixedLengthStreamingMode(totalLength)

                val input = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                conn.outputStream.buffered(256 * 1024).use { out ->
                    out.write(headerBytes)
                    input.use { it.copyTo(out, bufferSize = 256 * 1024) }
                    out.write(tailBytes)
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

    private fun getFileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
        }
        // Fallback: read through to count (rare)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            var size = 0L
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) size += n
            return size
        }
        throw Exception("Cannot determine file size")
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
