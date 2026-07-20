package io.github.cl0ura.hypericonpack.ui

import android.content.Context
import io.github.cl0ura.hypericonpack.logging.AppLog
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Small, explicit root-command bridge for user-initiated UI actions only.
 * Nothing runs automatically at process start, and commands are fixed here
 * rather than accepting arbitrary shell text from preferences or Intents.
 */
internal object RootAccess {
    private const val COMMAND_TIMEOUT_SECONDS = 12L

    data class Result(
        val success: Boolean,
        val output: String,
    )

    fun check(): Result {
        val result = runFixed("id")
        return result.copy(success = result.success && result.output.contains("uid=0"))
    }

    fun restartLauncher(): Result = runFixed(
        "am force-stop com.miui.home; " +
            "cmd activity start-activity -a android.intent.action.MAIN -c android.intent.category.HOME",
    )

    fun restartSystemUi(): Result = runFixed("pkill -f com.android.systemui")

    /**
     * Reloads long-lived icon consumers exactly once after a user applies or
     * restores an archive. Launcher and SystemUI are allowed to restart
     * immediately. Personal Assistant owns HyperOS' widget picker and keeps a
     * separate app-icon cache, so it is stopped and left to restart lazily the
     * next time the picker or one of its widgets is opened.
     */
    fun restartIconSurfaces(): Result = runFixed(
        command = RESTART_ICON_SURFACES_COMMAND,
        timeoutSeconds = 20L,
    )

    /** User-initiated full reboot for surfaces whose caches survive process restarts. */
    fun rebootSystem(): Result = runFixed("svc power reboot")

    /** Reads the bounded persistent log, which survives logcat rotation and reboots. */
    fun readAppLogs(context: Context): Result = Result(true, AppLog.read(context))

    /** Reads LSPosed bridge output from both logcat and LSPosed's module log files. */
    fun readXposedLogs(): Result = runFixed(
        "(logcat -b all -d -v threadtime 2>/dev/null | " +
            "grep -E 'LSPosed|Xposed' | grep -F HyperIconPack; " +
            "tail -n 3000 /data/adb/lspd/log/modules_*.log " +
            "/data/adb/lspd/log/verbose_*.log 2>/dev/null) | " +
            "grep -F HyperIconPack | tail -n 500",
        timeoutSeconds = 20L,
    )

    /**
     * Executes one of the module's fixed, internal Root command templates.
     * It is intentionally internal: settings and Intent data must never be
     * promoted to arbitrary shell text.
     */
    internal fun runFixed(command: String, timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS): Result =
        run(command, input = null, timeoutSeconds = timeoutSeconds)

    /** Streams trusted converter output to a fixed Root command through stdin. */
    internal fun runFixedWithInput(
        command: String,
        input: InputStream,
        timeoutSeconds: Long,
    ): Result {
        return try {
            input.use { source -> run(command, input = source, timeoutSeconds = timeoutSeconds) }
        } catch (throwable: Throwable) {
            Result(false, throwable.javaClass.simpleName + ": " + (throwable.message ?: "无法读取主题归档"))
        }
    }

    private fun run(
        command: String,
        input: InputStream?,
        timeoutSeconds: Long,
    ): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            // Root and LSPosed can easily produce more than the process pipe's
            // ~64 KiB buffer. Drain stdout while the command is running;
            // waiting first makes both sides block and falsely reports a
            // timeout even though the shell command already produced data.
            val output = StringBuilder()
            var readFailure: Throwable? = null
            val readerThread = Thread({
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        val buffer = CharArray(8 * 1024)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            output.append(buffer, 0, count)
                        }
                    }
                } catch (throwable: Throwable) {
                    readFailure = throwable
                }
            }, "HyperIconPack-root-output").apply {
                isDaemon = true
                start()
            }
            if (input != null) {
                process.outputStream.use { destination -> input.copyTo(destination) }
            } else {
                process.outputStream.close()
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                readerThread.join(1_000L)
                return Result(false, "命令超时；Root 管理器可能没有授予权限。")
            }
            readerThread.join(2_000L)
            if (readerThread.isAlive) {
                return Result(false, "命令已结束，但读取输出超时。")
            }
            readFailure?.let { throw it }
            Result(process.exitValue() == 0, output.toString().trim())
        } catch (throwable: Throwable) {
            Result(false, throwable.javaClass.simpleName + ": " + (throwable.message ?: "无法执行 su"))
        }
    }

    private val RESTART_ICON_SURFACES_COMMAND = """
        old_launcher="${'$'}(pidof com.miui.home 2>/dev/null || true)"
        old_systemui="${'$'}(pidof com.android.systemui 2>/dev/null || true)"
        old_widget_picker="${'$'}(pidof com.miui.personalassistant 2>/dev/null || true)"

        for process_id in ${'$'}old_launcher; do kill -TERM "${'$'}process_id" 2>/dev/null || true; done
        for process_id in ${'$'}old_systemui; do kill -TERM "${'$'}process_id" 2>/dev/null || true; done
        for process_id in ${'$'}old_widget_picker; do kill -TERM "${'$'}process_id" 2>/dev/null || true; done

        wait_for_restart() {
          package_name="${'$'}1"
          old_processes="${'$'}2"
          attempt=0
          while [ "${'$'}attempt" -lt 40 ]; do
            current_processes="${'$'}(pidof "${'$'}package_name" 2>/dev/null || true)"
            if [ -n "${'$'}current_processes" ] && [ "${'$'}current_processes" != "${'$'}old_processes" ]; then
              echo "${'$'}package_name restarted: ${'$'}current_processes"
              return 0
            fi
            sleep 0.25
            attempt=${'$'}((attempt + 1))
          done
          return 1
        }

        launcher_result=0
        systemui_result=0
        wait_for_restart com.miui.home "${'$'}old_launcher" || launcher_result=${'$'}?
        wait_for_restart com.android.systemui "${'$'}old_systemui" || systemui_result=${'$'}?

        if [ "${'$'}launcher_result" -ne 0 ]; then
          cmd activity start-activity -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
          sleep 1
          [ -n "${'$'}(pidof com.miui.home 2>/dev/null || true)" ] || exit 74
        fi
        [ "${'$'}systemui_result" -eq 0 ] || exit 75
        if [ -n "${'$'}old_widget_picker" ]; then
          echo "com.miui.personalassistant stopped for lazy restart: ${'$'}old_widget_picker"
        fi
        echo 'HYPER_ICONPACK_SURFACES_RESTARTED'
    """.trimIndent()
}
