package io.github.cl0ura.hypericonpack.systemtheme

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.cl0ura.hypericonpack.iconpack.IconPackMapping
import io.github.cl0ura.hypericonpack.iconpack.ParsedIconPack
import io.github.cl0ura.hypericonpack.logging.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds and validates one complete HyperOS icon archive. */
internal object FullIconArchiveBuilder {
    fun build(
        context: Context,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
        pack: ParsedIconPack?,
        mappings: List<IconPackMapping>,
        catalog: InstalledAppCatalog,
        palette: GlobalMonetPalette?,
        onProgress: ((HyperOsIconArchiveConverter.ConversionProgress) -> Unit)?,
        isCancelled: () -> Boolean,
    ): HyperOsIconArchiveConverter.Result {
        fun checkCancelled() {
            if (isCancelled()) throw ConversionCancelledException()
        }

        checkCancelled()
        val installedApplications = catalog.applications
        val fallbackActivities = catalog.launchableActivities
        val fallbackWorkTotal = fallbackActivities.size + installedApplications.size
        val progressTotal = mappings.size + fallbackWorkTotal
        onProgress?.invoke(
            HyperOsIconArchiveConverter.ConversionProgress(
                phase = HyperOsIconArchiveConverter.ConversionPhase.EXPLICIT_MAPPINGS,
                completed = 0,
                total = progressTotal,
                explicitTotal = mappings.size,
                fallbackTotal = fallbackWorkTotal,
            ),
        )

        val archiveVariant = IconArchiveVariant(
            iconPackPackage = iconPackPackage,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = globalMonetIcons && monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            applicationScopeFingerprint = IconArchiveCache.applicationScopeFingerprint(context),
            monetPaletteFingerprint = palette?.cacheFingerprint.orEmpty(),
        )
        val archive = IconArchiveCache.archiveFile(
            context = context,
            variant = archiveVariant,
            createDirectory = true,
        )
        val temporaryArchive = File(archive.parentFile, "${archive.name}.new")
        val drawableResolver = IconDrawableResolver(
            context = context,
            pack = pack,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            systemAdaptiveMask = IconDrawableResolver.resolveSystemAdaptiveMask(
                context = context,
                applications = installedApplications,
            ),
        )
        val packageRenderer = InstalledPackageIconRenderer(
            resolver = drawableResolver,
            palette = palette,
            isCancelled = isCancelled,
        )
        AppLog.info(
            context,
            "Conversion scope: apps=${installedApplications.size}, launchers=${fallbackActivities.size}, mappings=${mappings.size}",
        )

        var explicitConverted = 0
        var fallbackConverted = 0
        var packageDefaultsConverted = 0
        var skipped = 0
        var monetCompatibilityRenders = 0
        var monetEmergencyFallbacks = 0
        var nativeMonochromeIcons = 0
        var failedRenders = 0
        val failedRenderSamples = LinkedHashSet<String>()
        val entryNames = HashSet<String>()
        val successfullyConvertedComponents = HashSet<ComponentName>()

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporaryArchive))).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                IconArchiveFormat.writeMetadata(zip, archiveVariant)
                IconArchiveFormat.writeTransformConfig(zip, useDynamicIcon = false)

                fun appendPng(entryName: String, png: ByteArray) {
                    zip.putNextEntry(ZipEntry(entryName))
                    try {
                        zip.write(png)
                    } finally {
                        zip.closeEntry()
                    }
                }

                fun recordRenderResult(key: String, result: PngRenderResult) {
                    when (result.fallback) {
                        PngRenderFallback.SIMPLIFIED -> monetCompatibilityRenders++
                        PngRenderFallback.GUARANTEED -> monetEmergencyFallbacks++
                        PngRenderFallback.FAILED -> {
                            failedRenders++
                            if (failedRenderSamples.size < MAX_FAILED_RENDER_SAMPLES) {
                                val reason = result.failure?.javaClass?.simpleName
                                    ?.takeIf(String::isNotBlank)
                                    ?: "unresolved"
                                failedRenderSamples += "$key:$reason"
                            }
                        }

                        PngRenderFallback.NONE -> Unit
                    }
                }

                if (pack != null) {
                    mappings.chunked(PNG_RENDER_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                        checkCancelled()
                        val tasks = ArrayList<PngRenderTask>(batch.size)
                        val componentsByEntry = HashMap<String, ComponentName>(batch.size)
                        batch.forEach { mapping ->
                            checkCancelled()
                            val entryName = IconArchiveEntryNames.archiveEntryName(mapping.component)
                            if (entryNames.contains(entryName) || componentsByEntry.containsKey(entryName)) {
                                skipped++
                                return@forEach
                            }
                            tasks += PngRenderTask(key = entryName) {
                                drawableResolver.resolveExplicit(mapping, catalog)
                            }
                            componentsByEntry[entryName] = mapping.component
                        }
                        val rendered = IconPngRenderer.renderParallelTasks(
                            tasks = tasks,
                            palette = palette,
                            isCancelled = isCancelled,
                        )
                        tasks.forEach { task ->
                            checkCancelled()
                            val result = rendered[task.key]
                            if (result == null || result.png == null) {
                                if (result != null) recordRenderResult(task.key, result)
                                skipped++
                                return@forEach
                            }
                            recordRenderResult(task.key, result)
                            appendPng(task.key, result.png)
                            entryNames.add(task.key)
                            componentsByEntry[task.key]?.let(successfullyConvertedComponents::add)
                            explicitConverted++
                            if (result.usedNativeMonochrome) nativeMonochromeIcons++
                        }
                        val completed = ((batchIndex + 1) * PNG_RENDER_BATCH_SIZE)
                            .coerceAtMost(mappings.size)
                        reportProgress(
                            callback = onProgress,
                            phase = HyperOsIconArchiveConverter.ConversionPhase.EXPLICIT_MAPPINGS,
                            phaseCompleted = completed,
                            phaseTotal = mappings.size,
                            totalCompleted = completed,
                            total = progressTotal,
                            explicitCompleted = completed,
                            explicitTotal = mappings.size,
                            fallbackCompleted = 0,
                            fallbackTotal = fallbackWorkTotal,
                            force = completed == mappings.size,
                        )
                    }
                }

                val activitiesByPackage = fallbackActivities.groupBy { activity -> activity.packageName }
                val fallbackWorks = installedApplications.map { application ->
                    application to activitiesByPackage[application.packageName].orEmpty()
                }
                var fallbackCompleted = 0
                fallbackWorks.chunked(PNG_RENDER_BATCH_SIZE).forEach { batch ->
                    checkCancelled()
                    val pendingActivitiesByPackage = HashMap<String, List<String>>(batch.size)
                    val needsPackageEntry = HashSet<String>(batch.size)
                    val applicationsToRender = ArrayList<ApplicationInfo>(batch.size)
                    batch.forEach { (application, activities) ->
                        checkCancelled()
                        val packageName = application.packageName
                        val pendingActivityEntries = activities.mapNotNull { activity ->
                            val component = InstalledAppCatalog.componentName(activity) ?: return@mapNotNull null
                            if (component in successfullyConvertedComponents) return@mapNotNull null
                            IconArchiveEntryNames.archiveEntryName(component).takeUnless(entryNames::contains)
                        }.distinct()
                        pendingActivitiesByPackage[packageName] = pendingActivityEntries
                        val packageEntryName = IconArchiveEntryNames.packageArchiveEntryName(packageName)
                        if (!entryNames.contains(packageEntryName)) needsPackageEntry += packageName
                        if (pendingActivityEntries.isNotEmpty() || packageName in needsPackageEntry) {
                            applicationsToRender += application
                        }
                    }
                    val renderedByPackage = packageRenderer.renderApplications(applicationsToRender)
                    batch.forEach { (application, activities) ->
                        checkCancelled()
                        val packageName = application.packageName
                        val activityEntries = pendingActivitiesByPackage[packageName].orEmpty()
                        val writePackageEntry = packageName in needsPackageEntry
                        val result = renderedByPackage[packageName]
                        if (result != null) recordRenderResult(packageName, result)
                        val png = result?.png
                        if (png == null) {
                            skipped += activityEntries.size
                            if (writePackageEntry) skipped++
                        } else {
                            var wroteEntry = false
                            activityEntries.forEach { entryName ->
                                appendPng(entryName, png)
                                entryNames.add(entryName)
                                fallbackConverted++
                                wroteEntry = true
                            }
                            if (writePackageEntry) {
                                val entryName = IconArchiveEntryNames.packageArchiveEntryName(packageName)
                                appendPng(entryName, png)
                                entryNames.add(entryName)
                                packageDefaultsConverted++
                                wroteEntry = true
                            }
                            if (wroteEntry && result.usedNativeMonochrome) nativeMonochromeIcons++
                        }
                        fallbackCompleted += activities.size + 1
                    }
                    reportProgress(
                        callback = onProgress,
                        phase = HyperOsIconArchiveConverter.ConversionPhase.FALLBACK_ACTIVITIES,
                        phaseCompleted = fallbackCompleted,
                        phaseTotal = fallbackWorkTotal,
                        totalCompleted = mappings.size + fallbackCompleted,
                        total = progressTotal,
                        explicitCompleted = mappings.size,
                        explicitTotal = mappings.size,
                        fallbackCompleted = fallbackCompleted,
                        fallbackTotal = fallbackWorkTotal,
                        force = fallbackCompleted == fallbackWorkTotal,
                    )
                }
            }

            val totalConverted = explicitConverted + fallbackConverted
            require(totalConverted + packageDefaultsConverted > 0) { "所选应用的图标资源均无法渲染为 PNG" }
            onProgress?.invoke(
                HyperOsIconArchiveConverter.ConversionProgress(
                    phase = HyperOsIconArchiveConverter.ConversionPhase.VALIDATING,
                    completed = progressTotal,
                    total = progressTotal,
                    explicitCompleted = mappings.size,
                    explicitTotal = mappings.size,
                    fallbackCompleted = fallbackWorkTotal,
                    fallbackTotal = fallbackWorkTotal,
                ),
            )
            IconArchiveFormat.validate(temporaryArchive, totalConverted + packageDefaultsConverted)
            val dynamicArchive = DynamicCalendarArchive.generate(
                pack = pack,
                installedPackageNames = catalog.packageNames,
                launchableComponents = catalog.launchableComponents,
                iconArchive = archive,
                palette = palette,
                fallbackScaleMultiplier = fallbackScaleMultiplier,
                isCancelled = isCancelled,
            )
            try {
                DynamicCalendarArchive.replaceEmbeddedEntries(
                    iconArchive = temporaryArchive,
                    dynamicArchive = dynamicArchive,
                    isCancelled = isCancelled,
                )
            } finally {
                dynamicArchive?.delete()
            }
            IconArchiveFormat.validate(temporaryArchive, totalConverted + packageDefaultsConverted)
            ArchiveFileCommitter.replace(temporaryArchive, archive, "无法完成主题归档写入")
        } catch (throwable: Throwable) {
            temporaryArchive.delete()
            throw throwable
        }

        if (failedRenders > 0) {
            AppLog.warning(
                context,
                "PNG render failures=$failedRenders, samples=${failedRenderSamples.joinToString()}",
            )
        }
        AppLog.info(
            context,
            "Conversion archive: explicit=$explicitConverted, activities=$fallbackConverted, packages=$packageDefaultsConverted, skipped=$skipped, monetSimplified=$monetCompatibilityRenders, monetGuaranteed=$monetEmergencyFallbacks",
        )
        onProgress?.invoke(
            HyperOsIconArchiveConverter.ConversionProgress(
                phase = HyperOsIconArchiveConverter.ConversionPhase.COMPLETED,
                completed = progressTotal,
                total = progressTotal,
                explicitCompleted = mappings.size,
                explicitTotal = mappings.size,
                fallbackCompleted = fallbackWorkTotal,
                fallbackTotal = fallbackWorkTotal,
            ),
        )
        return HyperOsIconArchiveConverter.Result(
            archive = archive,
            requestedExplicitMappings = mappings.size,
            convertedExplicitMappings = explicitConverted,
            fallbackCandidates = installedApplications.size,
            convertedFallbackMappings = fallbackConverted,
            convertedPackageDefaults = packageDefaultsConverted,
            nativeMonochromeIcons = nativeMonochromeIcons,
            skippedMappings = skipped,
            archiveSha256 = IconArchiveCache.sha256(archive),
        )
    }

    private fun reportProgress(
        callback: ((HyperOsIconArchiveConverter.ConversionProgress) -> Unit)?,
        phase: HyperOsIconArchiveConverter.ConversionPhase,
        phaseCompleted: Int,
        phaseTotal: Int,
        totalCompleted: Int,
        total: Int,
        explicitCompleted: Int,
        explicitTotal: Int,
        fallbackCompleted: Int,
        fallbackTotal: Int,
        force: Boolean = false,
    ) {
        if (callback == null || (!force && phaseCompleted % PROGRESS_REPORT_INTERVAL != 0)) return
        callback(
            HyperOsIconArchiveConverter.ConversionProgress(
                phase = phase,
                completed = totalCompleted,
                total = total,
                explicitCompleted = explicitCompleted,
                explicitTotal = explicitTotal,
                fallbackCompleted = fallbackCompleted,
                fallbackTotal = fallbackTotal,
            ),
        )
    }

    private const val PROGRESS_REPORT_INTERVAL = 12
    private const val PNG_RENDER_BATCH_SIZE = 12
    private const val MAX_FAILED_RENDER_SAMPLES = 8
}
