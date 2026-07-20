package io.github.cl0ura.hypericonpack.logging

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent ring log for user-visible application diagnostics.
 *
 * Android's logcat buffer can be cleared by a reboot or overwritten before a
 * problem is noticed. These records are written only for lifecycle and theme
 * transactions, stay below a fixed size, and use logcat's threadtime format so
 * the existing log viewer can render them without a second parser.
 */
internal object AppLog {
    private const val TAG = "HyperIconPack"
    private const val MAX_BYTES = 256 * 1024L
    private const val RETAINED_LINES = 600
    private const val DISPLAY_LINES = 500
    private val lock = Any()

    fun info(context: Context, message: String) {
        Log.i(TAG, message)
        append(context, "I", message, null)
    }

    fun warning(context: Context, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
        append(context, "W", message, throwable)
    }

    fun error(context: Context, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
        append(context, "E", message, throwable)
    }

    fun read(context: Context): String = synchronized(lock) {
        val file = logFile(context)
        if (!file.isFile) return@synchronized ""
        runCatching {
            file.readLines().takeLast(DISPLAY_LINES).joinToString("\n")
        }.getOrDefault("")
    }

    private fun append(context: Context, level: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            runCatching {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                if (file.length() > MAX_BYTES) compact(file)

                val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val header = "$timestamp ${Process.myPid()} ${Process.myTid()} $level $TAG: "
                val body = buildString {
                    append(message.trim())
                    if (throwable != null) {
                        append('\n')
                        append(Log.getStackTraceString(throwable).trimEnd())
                    }
                }
                val record = body.lineSequence().mapIndexed { index, line ->
                    if (index == 0) header + line else "    $line"
                }.joinToString("\n")
                file.appendText(record + "\n")
                if (file.length() > MAX_BYTES) compact(file)
            }
        }
    }

    private fun compact(file: File) {
        val retained = file.readLines().takeLast(RETAINED_LINES)
        file.writeText(retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"))
    }

    private fun logFile(context: Context): File = File(context.applicationContext.filesDir, "logs/app.log")
}
