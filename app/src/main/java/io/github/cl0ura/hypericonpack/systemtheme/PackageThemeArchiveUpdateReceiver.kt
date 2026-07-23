package io.github.cl0ura.hypericonpack.systemtheme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.logging.AppLog

/**
 * Queues installed/replaced packages for a durable archive update, and queues
 * fully removed packages so their theme entries do not linger forever.
 */
class PackageThemeArchiveUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val packageName = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
        val appContext = context.applicationContext
        val settings = IconSettingsStore(appContext)
        val config = settings.read()
        if (!config.systemThemeActive || config.packageName == null) return
        // Updating an icon-pack APK changes source resources. Its complete
        // mapping needs an explicit user conversion; it is not a target app.
        if (packageName == config.packageName) return

        when (action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            -> {
                settings.enqueueThemeArchivePackageUpdate(packageName)
                val scheduled = PackageThemeArchiveUpdateScheduler.schedule(appContext)
                AppLog.info(
                    context,
                    "Queued $packageName for incremental theme archive update (scheduled=$scheduled)",
                )
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                // Upgrades also emit PACKAGE_REMOVED with EXTRA_REPLACING=true.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                settings.enqueueThemeArchivePackageRemoval(packageName)
                val scheduled = PackageThemeArchiveUpdateScheduler.schedule(appContext)
                AppLog.info(
                    context,
                    "Queued $packageName for theme archive removal (scheduled=$scheduled)",
                )
            }
        }
    }
}
