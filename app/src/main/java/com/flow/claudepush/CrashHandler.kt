package com.flow.claudepush

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Saves crash stack traces to a file so they can be retrieved on next launch.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            pw.println("Thread: ${t.name}")
            pw.println()
            e.printStackTrace(pw)
            pw.flush()

            val file = getCrashFile(context)
            file.writeText(sw.toString())
        } catch (_: Exception) {
        }
        // Let the default handler kill the process
        defaultHandler?.uncaughtException(t, e)
    }

    companion object {
        fun getCrashFile(context: Context): File {
            return File(context.filesDir, "last_crash.txt")
        }

        fun getLastCrash(context: Context): String? {
            val file = getCrashFile(context)
            if (file.exists()) {
                val text = file.readText()
                file.delete()
                return text
            }
            return null
        }
    }
}
