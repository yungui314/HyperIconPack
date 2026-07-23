package io.github.cl0ura.hypericonpack.systemtheme

import android.content.pm.ApplicationInfo

/** Parallel package renderer whose workers resolve and draw each icon together. */
internal class InstalledPackageIconRenderer(
    private val resolver: IconDrawableResolver,
    private val palette: GlobalMonetPalette?,
    private val isCancelled: () -> Boolean,
) {
    fun renderApplications(
        applications: Collection<ApplicationInfo>,
    ): Map<String, PngRenderResult> {
        val tasks = applications
            .distinctBy(ApplicationInfo::packageName)
            .map { application ->
                PngRenderTask(key = application.packageName) {
                    resolver.resolveInstalledPackage(application)
                }
            }
        return IconPngRenderer.renderParallelTasks(
            tasks = tasks,
            palette = palette,
            isCancelled = isCancelled,
        )
    }

    fun renderPackages(packageNames: Collection<String>): Map<String, PngRenderResult> {
        val tasks = packageNames
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .map { packageName ->
                PngRenderTask(key = packageName) {
                    resolver.resolveInstalledPackage(packageName)
                }
            }
            .toList()
        return IconPngRenderer.renderParallelTasks(
            tasks = tasks,
            palette = palette,
            isCancelled = isCancelled,
        )
    }
}
