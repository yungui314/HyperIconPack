package io.github.cl0ura.hypericonpack.systemtheme

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import io.github.cl0ura.hypericonpack.config.IconSettingsStore

/** Schedules durable, process-safe updates after an app is installed. */
internal object PackageThemeArchiveUpdateScheduler {
    private const val TAG = "HyperIconPack"
    private const val JOB_ID = 0x48495031 // "HIP1"

    fun schedule(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, PackageThemeArchiveUpdateJobService::class.java),
        )
            // The live ZIP-miss bridge gives the visual result immediately.
            // This job is the durable follow-up and is allowed to coalesce a
            // burst of installs into one sequence of small updates.
            .setMinimumLatency(0L)
            .setOverrideDeadline(3_000L)
            .build()
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.i(TAG, message) else Log.w(TAG, message, throwable)
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

        var needsRetry = false
        var installedAny = false
        pending.forEach { packageName ->
            try {
                val config = settings.read()
                if (!config.systemThemeActive || config.packageName == null || packageName == config.packageName) {
                    settings.markThemeArchivePackageUpdateComplete(packageName)
                    return@forEach
                }
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
                    // This happens only before the user has applied one v16
                    // archive. Keep the event in the queue; the immediate
                    // launcher bridge continues to cover the icon meanwhile.
                    PackageThemeArchiveUpdateScheduler.log(
                        "PACKAGE_UPDATE $packageName deferred: no current managed archive",
                    )
                    needsRetry = true
                    return@forEach
                }
                val updated = HyperOsIconArchiveConverter.updateInstalledPackage(
                    context = context,
                    baseArchive = baseArchive,
                    iconPackPackage = config.packageName,
                    fallbackScaleMultiplier = config.fallbackScaleMultiplier,
                    globalMonetIcons = config.globalMonetIcons,
                    monetCustomColors = config.monetCustomColors,
                    monetBackgroundColor = config.monetBackgroundColor,
                    monetForegroundColor = config.monetForegroundColor,
                    packageName = packageName,
                )
                val install = RootThemeIconInstaller.install(updated)
                if (install.success) {
                    settings.writeActiveArchive(updated)
                    settings.markThemeArchivePackageUpdateComplete(packageName)
                    installedAny = true
                    PackageThemeArchiveUpdateScheduler.log(
                        "PACKAGE_UPDATE $packageName persisted into ${updated.name}",
                    )
                } else {
                    needsRetry = true
                    PackageThemeArchiveUpdateScheduler.log(
                        "PACKAGE_UPDATE $packageName archive install failed: ${install.output}",
                    )
                }
            } catch (throwable: Throwable) {
                needsRetry = true
                PackageThemeArchiveUpdateScheduler.log("PACKAGE_UPDATE $packageName failed", throwable)
            }
        }
        if (installedAny) {
            // One revision notification after the complete queued batch avoids
            // repeatedly clearing HyperOS icon caches for back-to-back installs.
            settings.touch()
        }
        return needsRetry || settings.pendingThemeArchivePackageUpdates().isNotEmpty()
    }
}
