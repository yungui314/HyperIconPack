package io.github.cl0ura.hypericonpack.systemtheme

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.logging.AppLog
import io.github.cl0ura.hypericonpack.ui.RootAccess

/** Schedules durable, process-safe updates after an app is installed. */
internal object PackageThemeArchiveUpdateScheduler {
    private const val JOB_ID = 0x48495031 // "HIP1"

    fun schedule(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, PackageThemeArchiveUpdateJobService::class.java),
        )
            // Coalesce a burst of installs into one archive update and one
            // native HyperOS THEME_FLAG_ICON configuration refresh.
            .setMinimumLatency(0L)
            .setOverrideDeadline(3_000L)
            // Keep package-install archive updates eligible while idle/charging
            // constraints would otherwise defer HyperOS icon refresh.
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    fun log(context: Context, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            AppLog.info(context, message)
        } else {
            AppLog.warning(context, message, throwable)
        }
    }
}

/**
 * Background owner of the incremental archive mutation.  It reads the queue
 * saved by [PackageThemeArchiveUpdateReceiver], so package additions survive a
 * process kill and are retried by JobScheduler if root/theme storage is briefly
 * unavailable.
 */
class PackageThemeArchiveUpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val retry = PackageThemeArchiveUpdateWorker.updatePending(applicationContext)
            jobFinished(params, retry)
        }, "HyperIconPack-PackageArchiveUpdate").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

private object PackageThemeArchiveUpdateWorker {
    fun updatePending(context: Context): Boolean {
        val settings = IconSettingsStore(context)
        val pending = settings.pendingThemeArchivePackageUpdates()
        if (pending.isEmpty()) return false
        val config = settings.read()
        if (!config.systemThemeActive || config.packageName == null) {
            pending.forEach(settings::markThemeArchivePackageUpdateComplete)
            return false
        }
        val themeStatus = RootThemeIconInstaller.status()
        if (ManagedThemeStateReconciler.shouldClearManagedThemeState(config.systemThemeActive, themeStatus)) {
            settings.markManagedThemeInactive()
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch canceled: managed theme is no longer active (${themeStatus.output})",
            )
            return false
        }
        if (!themeStatus.success) {
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch deferred: unable to verify active managed theme (${themeStatus.output})",
            )
            return true
        }
        pending.filter { it == config.packageName }.forEach(settings::markThemeArchivePackageUpdateComplete)
        val targetPackages = pending.filter { it != config.packageName }
        if (targetPackages.isEmpty()) return false

        val baseArchive = settings.readActiveArchive()
            ?: HyperOsIconArchiveConverter.cachedArchiveInfos(context)
                .asSequence()
                .filter { info ->
                    info.iconPackPackage == config.packageName &&
                        info.globalMonetIcons == config.globalMonetIcons &&
                        info.monetCustomColors == (config.globalMonetIcons && config.monetCustomColors) &&
                        (!config.globalMonetIcons || !config.monetCustomColors ||
                            (info.monetBackgroundColor == config.monetBackgroundColor &&
                                info.monetForegroundColor == config.monetForegroundColor)) &&
                        info.fallbackScaleMultiplier?.let {
                            kotlin.math.abs(it - config.fallbackScaleMultiplier) < 0.001f
                        } == true
                }
                .maxByOrNull { info -> info.archive.lastModified() }
                ?.archive
        if (baseArchive == null) {
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch deferred: no current managed archive (${targetPackages.size} packages)",
            )
            return true
        }

        return try {
            val updated = HyperOsIconArchiveConverter.updateInstalledPackages(
                context = context,
                baseArchive = baseArchive,
                iconPackPackage = config.packageName,
                fallbackScaleMultiplier = config.fallbackScaleMultiplier,
                globalMonetIcons = config.globalMonetIcons,
                monetCustomColors = config.monetCustomColors,
                monetBackgroundColor = config.monetBackgroundColor,
                monetForegroundColor = config.monetForegroundColor,
                packageNames = targetPackages,
            )
            val install = RootThemeIconInstaller.install(updated)
            if (!install.success) {
                PackageThemeArchiveUpdateScheduler.log(
                    context,
                    "PACKAGE_UPDATE batch install failed: ${install.output}",
                )
                true
            } else {
                val refresh = RootAccess.refreshIconSurfaces()
                if (!refresh.success) {
                    PackageThemeArchiveUpdateScheduler.log(
                        context,
                        "PACKAGE_UPDATE batch refresh failed: ${refresh.output}",
                    )
                    return true
                }
                settings.writeActiveArchive(updated)
                targetPackages.forEach(settings::markThemeArchivePackageUpdateComplete)
                settings.touch()
                PackageThemeArchiveUpdateScheduler.log(
                    context,
                    "PACKAGE_UPDATE batch persisted and refreshed ${targetPackages.size} packages " +
                        "into ${updated.name}",
                )
                settings.pendingThemeArchivePackageUpdates().isNotEmpty()
            }
        } catch (throwable: Throwable) {
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch failed (${targetPackages.size} packages)",
                throwable,
            )
            true
        }
    }
}
