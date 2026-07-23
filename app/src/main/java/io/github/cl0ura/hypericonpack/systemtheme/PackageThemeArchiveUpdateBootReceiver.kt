package io.github.cl0ura.hypericonpack.systemtheme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.logging.AppLog

/**
 * After reboot, re-schedule any durable package-archive work that was queued
 * before power-off. The queues live in SharedPreferences, so this receiver only
 * needs to wake JobScheduler when the managed theme is still active.
 */
class PackageThemeArchiveUpdateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val settings = IconSettingsStore(appContext)
        val config = settings.read()
        val pendingAdds = settings.pendingThemeArchivePackageUpdates()
        val pendingRemoves = settings.pendingThemeArchivePackageRemovals()
        if (
            !config.systemThemeActive ||
            config.packageName == null ||
            (pendingAdds.isEmpty() && pendingRemoves.isEmpty())
        ) {
            return
        }
        val scheduled = PackageThemeArchiveUpdateScheduler.schedule(appContext)
        AppLog.info(
            appContext,
            "BOOT_COMPLETED re-scheduled package archive work " +
                "adds=${pendingAdds.size} removes=${pendingRemoves.size} (scheduled=$scheduled)",
        )
    }
}
