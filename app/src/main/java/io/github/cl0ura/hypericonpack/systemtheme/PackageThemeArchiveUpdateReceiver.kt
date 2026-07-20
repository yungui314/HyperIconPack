package io.github.cl0ura.hypericonpack.systemtheme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.logging.AppLog

/**
 * Records package installation/update events and hands the ZIP rewrite to a
 * JobService.  Rewriting thousands of already-converted ZIP entries inside a
 * BroadcastReceiver is unreliable: Android is free to end the hosting process
 * after `onReceive`, even when a worker thread was started with `goAsync()`.
 */
class PackageThemeArchiveUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return
        // PACKAGE_ADDED with EXTRA_REPLACING is followed by PACKAGE_REPLACED.
        // Queue only the latter so an app update produces one archive rewrite.
        if (action == Intent.ACTION_PACKAGE_ADDED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val packageName = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
        if (packageName == context.packageName) return

        val appContext = context.applicationContext
        val settings = IconSettingsStore(appContext)
        val config = settings.read()
        if (!config.systemThemeActive || config.packageName == null) return
        // Updating an icon-pack APK changes source resources. Its complete
        // mapping needs an explicit user conversion; it is not a target app.
        if (packageName == config.packageName) return

        settings.enqueueThemeArchivePackageUpdate(packageName)
        val scheduled = PackageThemeArchiveUpdateScheduler.schedule(appContext)
        AppLog.info(context, "Queued $packageName for incremental theme archive update (scheduled=$scheduled)")
    }
}
