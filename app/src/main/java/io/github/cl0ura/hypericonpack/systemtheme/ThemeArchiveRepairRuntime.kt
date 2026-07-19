package io.github.cl0ura.hypericonpack.systemtheme

import android.content.Context
import android.util.Log
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort static archive repair started by a successful provider request.
 *
 * JobScheduler defers ordinary jobs while HyperOS is dozing, which is exactly
 * when Theme Manager may have removed the archive. The provider-backed icon is
 * already available to the caller, so this short root transaction can run on
 * its own background thread without blocking Launcher. If Android kills the
 * process, the next provider request simply retries.
 */
internal object ThemeArchiveRepairScheduler {
    private const val TAG = "HyperIconPack"
    private val running = AtomicBoolean(false)

    fun scheduleOnce(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            try {
                val settings = IconSettingsStore(appContext)
                val config = settings.read()
                val archive = settings.readActiveArchive()
                if (!config.systemThemeActive || config.packageName == null || archive == null) return@Thread
                val status = RootThemeIconInstaller.status()
                if (!status.success) {
                    val install = RootThemeIconInstaller.install(archive)
                    if (install.success) {
                        settings.touch()
                        Log.i(TAG, "Restored missing HyperOS icon archive from ${archive.name}")
                    } else {
                        Log.w(TAG, "Unable to restore missing icon archive: ${install.output}")
                    }
                }
            } catch (throwable: Throwable) {
                Log.w(TAG, "Theme archive repair failed", throwable)
            } finally {
                running.set(false)
            }
        }, "HyperIconPack-ArchiveRepair").start()
    }
}
