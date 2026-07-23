package io.github.cl0ura.hypericonpack.systemtheme

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.logging.AppLog
import io.github.cl0ura.hypericonpack.root.RootAccess
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Schedules durable, process-safe updates after an app is installed or removed. */
internal object PackageThemeArchiveUpdateScheduler {
    private const val JOB_ID = 0x48495031 // "HIP1"

    fun schedule(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, PackageThemeArchiveUpdateJobService::class.java),
        )
            // Coalesce a burst of installs/uninstalls into one archive rewrite
            // and one native HyperOS THEME_FLAG_ICON configuration refresh.
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

internal data class PendingPackageChanges(
    val updates: List<String>,
    val removals: List<String>,
)

internal object PendingPackageChangeResolver {
    fun resolve(
        pendingUpdates: Collection<String>,
        pendingRemovals: Collection<String>,
        installedPackageNames: Set<String>,
        sourcePackageName: String,
    ): PendingPackageChanges {
        val packages = (pendingUpdates + pendingRemovals)
            .asSequence()
            .filter(String::isNotBlank)
            .filterNot { it == sourcePackageName }
            .distinct()
            .sorted()
            .toList()
        return PendingPackageChanges(
            updates = packages.filter(installedPackageNames::contains),
            removals = packages.filterNot(installedPackageNames::contains),
        )
    }
}

/**
 * Background owner of the incremental archive mutation.  It reads the queues
 * saved by [PackageThemeArchiveUpdateReceiver], so package changes survive a
 * process kill and are retried by JobScheduler if root/theme storage is briefly
 * unavailable.
 */
class PackageThemeArchiveUpdateJobService : JobService() {
    private class JobRun(val params: JobParameters) {
        val cancelled = AtomicBoolean(false)

        @Volatile
        var thread: Thread? = null
    }

    private val runLock = Any()
    private var activeRun: JobRun? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val run = JobRun(params)
        val worker = Thread({
            val retry = runCatching {
                PackageThemeArchiveUpdateWorker.updatePending(
                    context = applicationContext,
                    isCancelled = {
                        run.cancelled.get() || Thread.currentThread().isInterrupted
                    },
                )
            }.getOrElse { throwable ->
                PackageThemeArchiveUpdateScheduler.log(
                    applicationContext,
                    "PACKAGE_UPDATE worker crashed",
                    throwable,
                )
                true
            }
            val shouldFinish = synchronized(runLock) {
                if (activeRun !== run) {
                    false
                } else {
                    activeRun = null
                    !run.cancelled.get()
                }
            }
            if (shouldFinish) jobFinished(params, retry)
        }, "HyperIconPack-PackageArchiveUpdate")
        run.thread = worker
        synchronized(runLock) {
            activeRun?.let(::cancelRun)
            activeRun = run
        }
        worker.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(runLock) {
            activeRun?.takeIf { it.params.jobId == params.jobId }?.let { run ->
                cancelRun(run)
                activeRun = null
            }
        }
        return true
    }

    private fun cancelRun(run: JobRun) {
        run.cancelled.set(true)
        run.thread?.interrupt()
    }
}

private class PackageArchiveUpdateCancelledException : RuntimeException()

private object PackageThemeArchiveUpdateWorker {
    fun updatePending(
        context: Context,
        isCancelled: () -> Boolean = { false },
    ): Boolean = try {
        ThemeArchiveMutationGate.withLock {
        checkCancelled(isCancelled)
        val settings = IconSettingsStore(context)
        val pendingAdds = settings.pendingThemeArchivePackageUpdates()
        val pendingRemoves = settings.pendingThemeArchivePackageRemovals()
        if (pendingAdds.isEmpty() && pendingRemoves.isEmpty()) return@withLock false
        val config = settings.read()
        if (!config.systemThemeActive || config.packageName == null) {
            pendingAdds.forEach(settings::markThemeArchivePackageUpdateComplete)
            pendingRemoves.forEach(settings::markThemeArchivePackageRemovalComplete)
            return@withLock false
        }

        val status = RootThemeIconInstaller.status()
        if (ManagedThemeStateReconciler.shouldClearManagedThemeState(config.systemThemeActive, status)) {
            settings.markManagedThemeInactive()
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch canceled: managed theme is no longer active (${status.output})",
            )
            return@withLock false
        }
        if (!status.success) {
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch deferred: unable to verify active managed theme (${status.output})",
            )
            return@withLock true
        }

        // Self-updates of the active icon pack source are not incremental targets.
        pendingAdds.filter { it == config.packageName }.forEach(settings::markThemeArchivePackageUpdateComplete)
        pendingRemoves.filter { it == config.packageName }.forEach(settings::markThemeArchivePackageRemovalComplete)

        @Suppress("DEPRECATION")
        val installedPackageNames = runCatching {
            context.packageManager
                .getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
                .mapTo(HashSet()) { it.packageName }
        }.getOrElse { throwable ->
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch deferred: unable to read installed packages",
                throwable,
            )
            return@withLock true
        }
        val changes = PendingPackageChangeResolver.resolve(
            pendingUpdates = settings.pendingThemeArchivePackageUpdates(),
            pendingRemovals = settings.pendingThemeArchivePackageRemovals(),
            installedPackageNames = installedPackageNames,
            sourcePackageName = config.packageName,
        )
        changes.updates.forEach(settings::enqueueThemeArchivePackageUpdate)
        changes.removals.forEach(settings::enqueueThemeArchivePackageRemoval)
        val targetAdds = changes.updates
        val targetRemoves = changes.removals
        if (targetAdds.isEmpty() && targetRemoves.isEmpty()) return@withLock false

        checkCancelled(isCancelled)

        val paletteFingerprint = currentMonetPaletteFingerprint(
            context = context,
            globalMonetIcons = config.globalMonetIcons,
            monetCustomColors = config.monetCustomColors,
            monetBackgroundColor = config.monetBackgroundColor,
            monetForegroundColor = config.monetForegroundColor,
        )
        fun matchesConfiguration(info: IconArchiveInfo): Boolean =
            info.isCurrentFormat &&
                info.iconPackPackage == config.packageName &&
                info.globalMonetIcons == config.globalMonetIcons &&
                info.monetCustomColors == (config.globalMonetIcons && config.monetCustomColors) &&
                (!config.globalMonetIcons || !config.monetCustomColors ||
                    (info.monetBackgroundColor == config.monetBackgroundColor &&
                        info.monetForegroundColor == config.monetForegroundColor)) &&
                info.monetPaletteFingerprint == paletteFingerprint &&
                info.fallbackScaleMultiplier?.let {
                    kotlin.math.abs(it - config.fallbackScaleMultiplier) < 0.001f
                } == true

        val baseArchive = settings.readActiveArchive()
            ?.takeIf { archive ->
                HyperOsIconArchiveConverter.archiveInfo(archive)?.let(::matchesConfiguration) == true
            }
            ?: HyperOsIconArchiveConverter.cachedArchiveInfos(context)
                .asSequence()
                .filter(::matchesConfiguration)
                .maxByOrNull { info -> info.archive.lastModified() }
                ?.archive
        if (baseArchive == null) {
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch deferred: no current managed archive " +
                    "(adds=${targetAdds.size}, removes=${targetRemoves.size})",
            )
            return@withLock true
        }

        return@withLock try {
            checkCancelled(isCancelled)
            var working = baseArchive
            var mutated = false
            val supersededArchives = LinkedHashSet<File>()

            if (targetRemoves.isNotEmpty()) {
                val previous = working
                val removal = HyperOsIconArchiveConverter.removeInstalledPackages(
                    context = context,
                    baseArchive = working,
                    packageNames = targetRemoves,
                )
                working = removal.archive
                if (removal.changed) {
                    mutated = true
                    if (previous.absolutePath != working.absolutePath) supersededArchives += previous
                } else {
                    // No matching entries remained; drop the queue items.
                    targetRemoves.forEach(settings::markThemeArchivePackageRemovalComplete)
                }
            }

            if (targetAdds.isNotEmpty()) {
                val previous = working
                working = HyperOsIconArchiveConverter.updateInstalledPackages(
                    context = context,
                    baseArchive = working,
                    iconPackPackage = config.packageName,
                    fallbackScaleMultiplier = config.fallbackScaleMultiplier,
                    globalMonetIcons = config.globalMonetIcons,
                    monetCustomColors = config.monetCustomColors,
                    monetBackgroundColor = config.monetBackgroundColor,
                    monetForegroundColor = config.monetForegroundColor,
                    packageNames = targetAdds,
                )
                mutated = true
                if (previous.absolutePath != working.absolutePath) supersededArchives += previous
            }

            if (!mutated) {
                return@withLock settings.pendingThemeArchivePackageUpdates().isNotEmpty() ||
                    settings.pendingThemeArchivePackageRemovals().isNotEmpty()
            }

            checkCancelled(isCancelled)
            val install = RootThemeIconInstaller.install(working)
            if (!install.success) {
                PackageThemeArchiveUpdateScheduler.log(
                    context,
                    "PACKAGE_UPDATE batch install failed: ${install.output}",
                )
                true
            } else {
                val refresh = RootAccess.refreshIconSurfaces(context)
                if (!refresh.success) {
                    PackageThemeArchiveUpdateScheduler.log(
                        context,
                        "PACKAGE_UPDATE batch refresh failed: ${refresh.output}",
                    )
                    return@withLock true
                }
                settings.writeActiveArchive(working)
                supersededArchives
                    .asSequence()
                    .filter { archive -> archive.absolutePath != working.absolutePath }
                    .forEach { archive ->
                        if (!HyperOsIconArchiveConverter.deleteCachedArchive(context, archive)) {
                            PackageThemeArchiveUpdateScheduler.log(
                                context,
                                "PACKAGE_UPDATE retained superseded cache ${archive.name}",
                            )
                        }
                    }
                targetAdds.forEach(settings::markThemeArchivePackageUpdateComplete)
                targetRemoves.forEach(settings::markThemeArchivePackageRemovalComplete)
                settings.touch()
                PackageThemeArchiveUpdateScheduler.log(
                    context,
                    "PACKAGE_UPDATE batch persisted and refreshed " +
                        "adds=${targetAdds.size} removes=${targetRemoves.size} into ${working.name}",
                )
                settings.pendingThemeArchivePackageUpdates().isNotEmpty() ||
                    settings.pendingThemeArchivePackageRemovals().isNotEmpty()
            }
        } catch (throwable: Throwable) {
            if (throwable is PackageArchiveUpdateCancelledException) return@withLock false
            PackageThemeArchiveUpdateScheduler.log(
                context,
                "PACKAGE_UPDATE batch failed (adds=${targetAdds.size}, removes=${targetRemoves.size})",
                throwable,
            )
            true
        }
        }
    } catch (_: PackageArchiveUpdateCancelledException) {
        false
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw PackageArchiveUpdateCancelledException()
    }
}
