package io.github.cl0ura.hypericonpack.systemtheme

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import io.github.cl0ura.hypericonpack.iconpack.IconPackMapping
import io.github.cl0ura.hypericonpack.iconpack.ParsedIconPack

/** Resolves raw and icon-pack Drawables without owning archive or threading policy. */
internal class IconDrawableResolver(
    private val context: Context,
    private val pack: ParsedIconPack?,
    private val fallbackScaleMultiplier: Float,
    private val globalMonetIcons: Boolean,
    private val systemAdaptiveMask: AdaptiveIconDrawable?,
) {
    fun resolveExplicit(
        mapping: IconPackMapping,
        catalog: InstalledAppCatalog,
    ): PngRenderSource? {
        val mapped = loadMappedDrawable(mapping.drawableName, mapping.component)
        val applicationInfo = catalog.applicationsByPackage[mapping.component.packageName]
        val activityInfo = catalog.launchableActivitiesByComponent[mapping.component]
        val rawIcon = activityInfo?.let(::rawActivityIcon)
            ?: applicationInfo?.let(::rawApplicationIcon)
        val fallback = if (mapped == null && rawIcon != null) {
            bestPackageDrawable(
                packageName = mapping.component.packageName,
                rawIcon = rawIcon,
                fallbackComponent = mapping.component,
            )
        } else {
            null
        }
        val rawNative = if (mapped == null) {
            activityInfo?.let { activity -> nativeMonochromeSource(rawActivityIcon(activity)) }
                ?: applicationInfo?.let { application -> nativeMonochromeSource(rawApplicationIcon(application)) }
        } else {
            null
        }
        val native = rawNative.withPreparedSilhouette(fallback)
        val drawable = mapped ?: native?.glyph ?: fallback ?: return null
        return PngRenderSource(drawable = drawable, nativeMonochrome = native)
    }

    fun resolveInstalledPackage(applicationInfo: ApplicationInfo): PngRenderSource? {
        val packageName = applicationInfo.packageName
        val mapped = mappedPackageDrawable(packageName)
        val rawIcon = rawApplicationIcon(applicationInfo)
        val fallback = if (mapped == null) {
            bestPackageDrawable(
                packageName = packageName,
                rawIcon = rawIcon,
                fallbackComponent = ComponentName(packageName, packageName),
            )
        } else {
            null
        }
        val rawNative = if (mapped == null) nativeMonochromeSource(rawIcon) else null
        val native = rawNative.withPreparedSilhouette(fallback)
        val drawable = mapped ?: native?.glyph ?: fallback ?: return null
        return PngRenderSource(drawable = drawable, nativeMonochrome = native)
    }

    fun resolveInstalledPackage(packageName: String): PngRenderSource? {
        val applicationInfo = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        return resolveInstalledPackage(applicationInfo)
    }

    private fun loadMappedDrawable(drawableName: String, component: ComponentName): Drawable? {
        val resolvedPack = pack ?: return null
        return if (globalMonetIcons) {
            resolvedPack.loadMappedDrawableForMonet(drawableName, component)
        } else {
            resolvedPack.loadMappedDrawable(drawableName)
        }
    }

    private fun bestPackageDrawable(
        packageName: String,
        rawIcon: Drawable?,
        fallbackComponent: ComponentName,
    ): Drawable? {
        fun systemShapedRawIcon(): Drawable? = rawIcon?.let { source ->
            SystemIconShapeDrawable(source = source, systemMask = systemAdaptiveMask)
        }
        val resolvedPack = pack ?: return systemShapedRawIcon()
        mappedPackageDrawable(packageName)?.let { return it }
        if (rawIcon == null) return null
        if (!resolvedPack.supportsFallbackStyle) return systemShapedRawIcon()
        return resolvedPack.createFallback(
            original = rawIcon,
            component = fallbackComponent,
            scaleMultiplier = fallbackScaleMultiplier,
        ) ?: systemShapedRawIcon()
    }

    private fun mappedPackageDrawable(packageName: String): Drawable? {
        val resolvedPack = pack ?: return null
        val component = ComponentName(packageName, packageName)
        return resolvedPack.mappedDrawableNamesForPackage(packageName)
            .asSequence()
            .mapNotNull { drawableName -> loadMappedDrawable(drawableName, component) }
            .firstOrNull()
    }

    private fun rawApplicationIcon(applicationInfo: ApplicationInfo): Drawable? {
        val packageManager = context.packageManager
        val resourceId = applicationInfo.icon
        val declared = if (resourceId != 0) {
            runCatching {
                packageManager.getDrawable(applicationInfo.packageName, resourceId, applicationInfo)
            }.getOrNull()
        } else {
            null
        }
        return declared ?: runCatching { packageManager.getApplicationIcon(applicationInfo) }.getOrNull()
    }

    private fun rawActivityIcon(activityInfo: ActivityInfo): Drawable? {
        val resourceId = activityInfo.icon
        return if (resourceId != 0) {
            runCatching {
                context.packageManager.getDrawable(
                    activityInfo.packageName,
                    resourceId,
                    activityInfo.applicationInfo,
                )
            }.getOrNull()
        } else {
            rawApplicationIcon(activityInfo.applicationInfo)
        }
    }

    private fun nativeMonochromeSource(drawable: Drawable?): NativeMonochromeSource? {
        if (!globalMonetIcons || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val adaptiveIcon = drawable as? AdaptiveIconDrawable ?: return null
        val monochrome = adaptiveIcon.monochrome ?: return null
        val glyph = monochrome.constantState?.newDrawable()?.mutate() ?: monochrome.mutate()
        val rawSilhouette = adaptiveIcon.constantState?.newDrawable()?.mutate() ?: adaptiveIcon.mutate()
        return NativeMonochromeSource(
            glyph = glyph,
            silhouette = SystemIconShapeDrawable(source = rawSilhouette, systemMask = null),
            recoveryDrawable = rawSilhouette,
        )
    }

    private fun NativeMonochromeSource?.withPreparedSilhouette(
        fallback: Drawable?,
    ): NativeMonochromeSource? = this?.let { source ->
        fallback?.let { silhouette ->
            source.copy(silhouette = silhouette, recoveryDrawable = silhouette)
        } ?: source
    }

    companion object {
        fun resolveSystemAdaptiveMask(
            context: Context,
            applications: List<ApplicationInfo>,
        ): AdaptiveIconDrawable? {
            val resolver = IconDrawableResolver(
                context = context,
                pack = null,
                fallbackScaleMultiplier = 1f,
                globalMonetIcons = false,
                systemAdaptiveMask = null,
            )
            val packageManager = context.packageManager
            val preferredPackages = listOf("com.miui.home", "com.android.settings", "com.android.systemui")
            val preferred = preferredPackages.asSequence().mapNotNull { packageName ->
                applications.firstOrNull { application -> application.packageName == packageName }
                    ?: runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            }
            return (preferred + applications.asSequence())
                .mapNotNull { application -> resolver.rawApplicationIcon(application) as? AdaptiveIconDrawable }
                .firstOrNull()
                ?.let { drawable ->
                    drawable.constantState?.newDrawable()?.mutate() as? AdaptiveIconDrawable ?: drawable
                }
        }
    }
}
