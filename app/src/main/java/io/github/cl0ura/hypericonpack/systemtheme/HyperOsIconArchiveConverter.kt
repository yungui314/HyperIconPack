package io.github.cl0ura.hypericonpack.systemtheme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.cl0ura.hypericonpack.config.IconPackConfig
import io.github.cl0ura.hypericonpack.iconpack.ParsedIconPack
import java.io.File
import java.util.zip.ZipFile

/**
 * Converts the public, de-facto standard appfilter.xml contract into the
 * exact icon archive layout consumed by HyperOS ThemeResourcesSystem.
 *
 * HyperOS opens /data/system/theme/icons as a normal ZIP and asks it for
 * entries such as:
 *
 *   res/drawable-xxhdpi/com.example.MainActivity.png
 *   res/drawable-xxhdpi/com.example#ExternalActivity.png
 *
 * The archive is intentionally generated before any Root operation.  This
 * keeps conversion inspectable and makes installation a separate, reversible
 * step.
 */
internal object HyperOsIconArchiveConverter {
    const val ORIGINAL_ICON_PACKAGE = "io.github.cl0ura.hypericonpack.source.original"
    const val ORIGINAL_ICON_LABEL = "本机原始图标"

    /**
     * The current format retains the icon pack's complete appfilter contract, then
     * adds precise resources for desktop-launchable components and every
     * installed package.  This deliberately avoids baking thousands of
     * internal Activities into one archive: HyperOS can stop resolving icons
     * reliably when its private theme ZIP grows excessively large.  For
     * Global Monet, an application's API 33+ native monochrome layer is used
     * only when the selected icon pack has no usable mapping for that app.
     */
    // A xxhdpi launcher icon is normally displayed at roughly 144 px on the
    // target device.  256 px leaves comfortable downsampling headroom while
    // reducing Monet's per-pixel work and temporary bitmap allocation by more
    // than half compared with the old 384 px output.
    enum class ConversionPhase {
        PARSING,
        EXPLICIT_MAPPINGS,
        FALLBACK_ACTIVITIES,
        VALIDATING,
        COMPLETED,
    }

    /** A thread-safe, value-only snapshot delivered by [convert]. */
    data class ConversionProgress(
        val phase: ConversionPhase,
        val completed: Int,
        val total: Int,
        val explicitCompleted: Int = 0,
        val explicitTotal: Int = 0,
        val fallbackCompleted: Int = 0,
        val fallbackTotal: Int = 0,
    ) {
        val fraction: Float
            get() = if (total <= 0) 0f else completed.toFloat() / total.toFloat()
    }

    data class Result(
        val archive: File,
        val requestedExplicitMappings: Int,
        val convertedExplicitMappings: Int,
        val fallbackCandidates: Int,
        val convertedFallbackMappings: Int,
        val convertedPackageDefaults: Int,
        val nativeMonochromeIcons: Int,
        val skippedMappings: Int,
        val archiveSha256: String,
    ) {
        val convertedMappings: Int
            get() = convertedExplicitMappings + convertedFallbackMappings
    }

    fun isOriginalIconSource(packageName: String?): Boolean = packageName == ORIGINAL_ICON_PACKAGE

    fun sourceLabel(packageName: String?): String = when {
        packageName == null -> "未选择图标来源"
        isOriginalIconSource(packageName) -> ORIGINAL_ICON_LABEL
        else -> packageName
    }

    /** Resolves the user-facing icon-pack application name when installed. */
    fun sourceLabel(context: Context, packageName: String?): String {
        if (packageName == null || isOriginalIconSource(packageName)) return sourceLabel(packageName)
        return runCatching {
            val info = context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            context.packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
        }.getOrDefault(packageName)
    }

    /**
     * Produces a validated ZIP in the app's external-files directory.  Root
     * can read that directory on stock Android, while ordinary apps cannot
     * modify the result without the module app's storage access.
     */
    fun convert(
        context: Context,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean = false,
        monetCustomColors: Boolean = false,
        monetBackgroundColor: Int = IconPackConfig.DEFAULT_MONET_BACKGROUND_COLOR,
        monetForegroundColor: Int = IconPackConfig.DEFAULT_MONET_FOREGROUND_COLOR,
        onProgress: ((ConversionProgress) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): Result {
        fun checkCancelled() {
            if (isCancelled()) {
                throw ConversionCancelledException()
            }
        }
        checkCancelled()
        require(iconPackPackage.isNotBlank()) { "请先选择一个图标来源" }

        onProgress?.invoke(
            ConversionProgress(
                phase = ConversionPhase.PARSING,
                completed = 0,
                total = 0,
            ),
        )
        var parseFailure: Throwable? = null
        val pack = if (isOriginalIconSource(iconPackPackage)) {
            null
        } else {
            ParsedIconPack.load(context, iconPackPackage) { parseFailure = it }
                ?: throw IllegalStateException(
                    "无法读取 $iconPackPackage 的 appfilter.xml",
                    parseFailure,
                )
        }
        val allMappings = pack?.explicitMappings().orEmpty()
        if (pack != null) {
            require(allMappings.isNotEmpty()) { "图标包不含可转换的 ComponentInfo 图标映射" }
        }
        val catalog = InstalledAppCatalog.load(context)
        val mappings = if (pack == null) {
            emptyList()
        } else {
            allMappings
        }
        val palette = loadMonetPalette(
            context = context,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
        )
        return FullIconArchiveBuilder.build(
            context = context,
            iconPackPackage = iconPackPackage,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            pack = pack,
            mappings = mappings,
            catalog = catalog,
            palette = palette,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    /**
     * Looks up the durable cache for one exact pack/scale combination. A
     * matching legacy archive is recognised as a one-time migration path.
     */
    fun existingArchive(
        context: Context,
        iconPackPackage: String?,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean = false,
        monetCustomColors: Boolean = false,
        monetBackgroundColor: Int = IconPackConfig.DEFAULT_MONET_BACKGROUND_COLOR,
        monetForegroundColor: Int = IconPackConfig.DEFAULT_MONET_FOREGROUND_COLOR,
    ): File? = IconArchiveCache.existingArchive(
        context = context,
        iconPackPackage = iconPackPackage,
        fallbackScaleMultiplier = fallbackScaleMultiplier,
        globalMonetIcons = globalMonetIcons,
        monetCustomColors = monetCustomColors,
        monetBackgroundColor = monetBackgroundColor,
        monetForegroundColor = monetForegroundColor,
    )

    /**
     * Reads only the small metadata entry written by [convert].  Older
     * archives deliberately remain usable; they are reported with null
     * metadata instead of being mistaken for an absent conversion.
     */
    fun existingArchiveInfo(
        context: Context,
        iconPackPackage: String?,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean = false,
        monetCustomColors: Boolean = false,
        monetBackgroundColor: Int = IconPackConfig.DEFAULT_MONET_BACKGROUND_COLOR,
        monetForegroundColor: Int = IconPackConfig.DEFAULT_MONET_FOREGROUND_COLOR,
    ): IconArchiveInfo? = existingArchive(
        context = context,
        iconPackPackage = iconPackPackage,
        fallbackScaleMultiplier = fallbackScaleMultiplier,
        globalMonetIcons = globalMonetIcons,
        monetCustomColors = monetCustomColors,
        monetBackgroundColor = monetBackgroundColor,
        monetForegroundColor = monetForegroundColor,
    )
        ?.let(IconArchiveFormat::readInfo)

    /** Reads metadata for the exact durable archive selected by the user. */
    fun archiveInfo(archive: File?): IconArchiveInfo? = archive
        ?.takeIf(File::isFile)
        ?.let(IconArchiveFormat::readInfo)

    /**
     * Adds/replaces just one installed package in an already validated static
     * archive. This is intentionally not a second full conversion: all of an
     * icon pack's explicit appfilter mappings are copied verbatim and only the
     * new app's package default plus its launcher aliases are rendered.
     *
     * The resulting archive uses the new installed-package fingerprint and is
     * installed by [PackageThemeArchiveUpdateReceiver] after PACKAGE_ADDED.
     */
    internal fun updateInstalledPackage(
        context: Context,
        baseArchive: File,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
        packageName: String,
    ): File = updateInstalledPackages(
        context = context,
        baseArchive = baseArchive,
        iconPackPackage = iconPackPackage,
        fallbackScaleMultiplier = fallbackScaleMultiplier,
        globalMonetIcons = globalMonetIcons,
        monetCustomColors = monetCustomColors,
        monetBackgroundColor = monetBackgroundColor,
        monetForegroundColor = monetForegroundColor,
        packageNames = listOf(packageName),
    )

    /**
     * Persists a coalesced package-install batch with one icon-pack parse and
     * one ZIP rewrite. App stores commonly update several packages together;
     * rewriting and installing the complete 30-150 MB archive once per package
     * wastes I/O and can keep Launcher under storage pressure for minutes.
     */
    internal fun updateInstalledPackages(
        context: Context,
        baseArchive: File,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
        packageNames: Collection<String>,
    ): File {
        val pack = loadParsedIconPack(context, iconPackPackage)
        val palette = loadMonetPalette(
            context = context,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
        )
        val catalog = InstalledAppCatalog.load(context)
        val launchableComponents = catalog.launchableComponents
        val launchEntriesByPackage = launchableComponents
            .map { component -> component.packageName to archiveEntryName(component) }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val requestedLaunchEntries = packageNames
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .flatMap { packageName -> launchEntriesByPackage[packageName].orEmpty().asSequence() }
            .toSet()
        val explicitLaunchEntries = pack?.explicitMappings()
            ?.asSequence()
            ?.map { mapping -> archiveEntryName(mapping.component) }
            ?.filter(requestedLaunchEntries::contains)
            ?.toSet()
            .orEmpty()
        val preservedEntryNames = if (explicitLaunchEntries.isEmpty()) {
            emptySet()
        } else {
            ZipFile(baseArchive).use { zip ->
                explicitLaunchEntries.filterTo(LinkedHashSet()) { entryName ->
                    zip.getEntry(entryName) != null
                }
            }
        }
        val updated = PackageArchiveMutator.updateInstalledPackages(
            context = context,
            baseArchive = baseArchive,
            iconPackPackage = iconPackPackage,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            monetPaletteFingerprint = palette?.cacheFingerprint.orEmpty(),
            packageNames = packageNames,
            preservedEntryNames = preservedEntryNames,
            launchEntryNames = { packageName -> launchEntriesByPackage[packageName].orEmpty() },
            renderPackages = { requestedPackages ->
                renderInstalledPackagesPng(
                    context = context,
                    pack = pack,
                    palette = palette,
                    fallbackScaleMultiplier = fallbackScaleMultiplier,
                    globalMonetIcons = globalMonetIcons,
                    packageNames = requestedPackages,
                )
            },
        )
        return refreshDynamicCalendarEntries(
            context = context,
            archive = updated,
            pack = pack,
            palette = palette,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            catalog = catalog,
        )
    }


    /**
     * Drops generated package-default and launch-component PNG entries (and
     * embedded classic dynamic calendar trees) for uninstalled packages without
     * a full reconversion. Explicit appfilter mappings remain available for a
     * later reinstall.
     */
    internal fun removeInstalledPackages(
        context: Context,
        baseArchive: File,
        packageNames: Collection<String>,
    ): PackageArchiveMutation {
        val info = IconArchiveFormat.readInfo(baseArchive)
            ?: throw IllegalStateException("当前主题归档元数据无法读取")
        val iconPackPackage = info.iconPackPackage
            ?: throw IllegalStateException("当前主题归档缺少图标来源")
        val fallbackScaleMultiplier = info.fallbackScaleMultiplier
            ?: throw IllegalStateException("当前主题归档缺少适配比例")
        val palette = loadMonetPalette(
            context = context,
            globalMonetIcons = info.globalMonetIcons,
            monetCustomColors = info.monetCustomColors,
            monetBackgroundColor = info.monetBackgroundColor,
            monetForegroundColor = info.monetForegroundColor,
        )
        val pack = loadParsedIconPack(context, iconPackPackage)
        val catalog = InstalledAppCatalog.load(context)
        val requestedPackages = packageNames.filter(String::isNotBlank).toSet()
        val preservedEntryNames = pack?.explicitMappings()
            ?.asSequence()
            ?.filter { mapping -> mapping.component.packageName in requestedPackages }
            ?.map { mapping -> archiveEntryName(mapping.component) }
            ?.toSet()
            .orEmpty()
        val mutation = PackageArchiveMutator.removeInstalledPackages(
            context = context,
            baseArchive = baseArchive,
            packageNames = packageNames,
            monetPaletteFingerprint = palette?.cacheFingerprint.orEmpty(),
            preservedEntryNames = preservedEntryNames,
            protectedPackageNames = catalog.packageNames,
        )
        if (!mutation.changed) return mutation
        return mutation.copy(
            archive = refreshDynamicCalendarEntries(
                context = context,
                archive = mutation.archive,
                pack = pack,
                palette = palette,
                fallbackScaleMultiplier = fallbackScaleMultiplier,
                catalog = catalog,
            ),
        )
    }

    internal fun entryBelongsToPackage(entryName: String, packageName: String): Boolean =
        IconArchiveEntryNames.entryBelongsToPackage(entryName, packageName)

    /**
     * Renders one installed package for the exported read-only icon provider.
     * This is the live equivalent of an incremental archive entry and uses the
     * exact same pack fallback, shape and Monet rules as offline conversion.
     */
    internal fun renderInstalledPackagePng(
        context: Context,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
        packageName: String,
    ): ByteArray? = runCatching {
        renderInstalledPackagesPng(
            context = context,
            iconPackPackage = iconPackPackage,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            packageNames = listOf(packageName),
        )[packageName]
    }.getOrNull()

    private fun renderInstalledPackagesPng(
        context: Context,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
        packageNames: List<String>,
    ): Map<String, ByteArray?> {
        val pack = loadParsedIconPack(context, iconPackPackage)
        val palette = loadMonetPalette(
            context = context,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
        )
        return renderInstalledPackagesPng(
            context = context,
            pack = pack,
            palette = palette,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            packageNames = packageNames,
        )
    }

    private fun renderInstalledPackagesPng(
        context: Context,
        pack: ParsedIconPack?,
        palette: GlobalMonetPalette?,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        packageNames: List<String>,
    ): Map<String, ByteArray?> {
        val resolver = IconDrawableResolver(
            context = context,
            pack = pack,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            systemAdaptiveMask = null,
        )
        val rendered = InstalledPackageIconRenderer(
            resolver = resolver,
            palette = palette,
            isCancelled = { false },
        ).renderPackages(packageNames)
        return packageNames.associateWith { packageName -> rendered[packageName]?.png }
    }

    private fun refreshDynamicCalendarEntries(
        context: Context,
        archive: File,
        pack: ParsedIconPack?,
        palette: GlobalMonetPalette?,
        fallbackScaleMultiplier: Float,
        catalog: InstalledAppCatalog? = null,
    ): File {
        val resolvedCatalog = catalog ?: InstalledAppCatalog.load(context)
        val dynamicArchive = DynamicCalendarArchive.generate(
            pack = pack,
            installedPackageNames = resolvedCatalog.packageNames,
            launchableComponents = resolvedCatalog.launchableComponents,
            iconArchive = archive,
            palette = palette,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            isCancelled = { false },
        )
        try {
            DynamicCalendarArchive.replaceEmbeddedEntries(
                iconArchive = archive,
                dynamicArchive = dynamicArchive,
            )
        } finally {
            dynamicArchive?.delete()
        }
        return archive
    }

    private fun loadParsedIconPack(context: Context, iconPackPackage: String): ParsedIconPack? =
        if (isOriginalIconSource(iconPackPackage)) {
            null
        } else {
            ParsedIconPack.load(context, iconPackPackage)
                ?: throw IllegalStateException("无法读取当前图标包")
        }

    private fun loadMonetPalette(
        context: Context,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
    ): GlobalMonetPalette? = if (globalMonetIcons) {
        SystemMonetIconPalette.global(
            context,
            monetCustomColors,
            monetBackgroundColor,
            monetForegroundColor,
        )
    } else {
        null
    }

    /** All valid cached archives, used by the settings UI for fast switching. */
    fun cachedArchiveInfos(context: Context): List<IconArchiveInfo> =
        IconArchiveCache.cachedArchiveInfos(context)

    /**
     * Finds the newest valid archive for a source, regardless of the installed
     * application fingerprint embedded in its filename.  The fingerprint is
     * intentionally part of the conversion cache key, so installing or
     * removing an app can make the exact-key lookup miss even while the
     * previously applied archive is still the active system theme.
     */
    fun latestCachedArchiveForSource(
        context: Context,
        iconPackPackage: String?,
    ): IconArchiveInfo? = IconArchiveCache.latestCachedArchiveForSource(context, iconPackPackage)

    /**
     * Removes only a converter-owned cache file. The active HyperOS archive
     * under /data/system/theme is intentionally untouched: deleting a cache
     * must never silently change the icon theme currently visible to the user.
     */
    fun deleteCachedArchive(context: Context, archive: File): Boolean =
        ThemeArchiveMutationGate.withLock {
            IconArchiveCache.deleteCachedArchive(context, archive)
        }

    /** Keeps the archive's dynamic lookup flag aligned with the installer. */
    fun ensureUseDynamicIconTransformConfig(
        iconArchive: File,
        enableDynamicIcons: Boolean,
    ): File = IconArchiveFormat.ensureTransformConfig(iconArchive, enableDynamicIcons)

    fun applicationScopeFingerprint(context: Context): String =
        IconArchiveCache.applicationScopeFingerprint(context)
    fun sha256(file: File): String = IconArchiveCache.sha256(file)

    private fun archiveEntryName(component: ComponentName): String =
        IconArchiveEntryNames.archiveEntryName(component)

}

/** Thrown when a long-running archive conversion is aborted by the user or service. */
internal class ConversionCancelledException(
    message: String = "图标包转换已取消",
) : RuntimeException(message)
