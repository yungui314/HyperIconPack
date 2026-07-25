package io.github.cl0ura.hypericonpack.systemtheme

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Loads declared icon resources from the application APK, outside Xiaomi's active icon theme. */
internal class UnthemedApplicationIconLoader(
    private val context: Context,
) {
    private val resourcesBySource = ConcurrentHashMap<String, LoadedResources>()
    private val unavailableSources = ConcurrentHashMap.newKeySet<String>()

    fun load(applicationInfo: ApplicationInfo, resourceId: Int): Drawable? {
        if (resourceId == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val sourceKey = buildSourceKey(applicationInfo)
        if (sourceKey in unavailableSources) return null
        val loaded = resourcesBySource[sourceKey] ?: runCatching {
            createResources(applicationInfo)
        }.getOrElse {
            unavailableSources += sourceKey
            return null
        }.also { resourcesBySource.putIfAbsent(sourceKey, it) }
        return runCatching { loaded.resources.getDrawable(resourceId, loaded.theme) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createResources(applicationInfo: ApplicationInfo): LoadedResources {
        val providers = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.let(::addAll)
        }.map { sourcePath ->
            ParcelFileDescriptor.open(File(sourcePath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ResourcesProvider.loadFromApk(descriptor)
            }
        }
        require(providers.isNotEmpty()) { "Application has no readable APK resources" }
        val loader = ResourcesLoader().apply {
            providers.forEach(::addProvider)
        }
        val frameworkContext = context.createPackageContext(
            "android",
            Context.CONTEXT_IGNORE_SECURITY,
        ).createConfigurationContext(Configuration(context.resources.configuration))
        val resources = frameworkContext.resources.apply {
            addLoaders(loader)
        }
        val theme = resources.newTheme().apply {
            applicationInfo.theme.takeIf { it != 0 }?.let { style ->
                runCatching { applyStyle(style, true) }
            }
        }
        return LoadedResources(resources, theme, loader, providers)
    }

    private fun buildSourceKey(applicationInfo: ApplicationInfo): String = buildString {
        append(applicationInfo.packageName).append('|').append(applicationInfo.sourceDir)
        applicationInfo.splitSourceDirs?.forEach { append('|').append(it) }
    }

    /** Retains loaders and providers for as long as lazily inflated Drawables may reference them. */
    private data class LoadedResources(
        val resources: Resources,
        val theme: Resources.Theme,
        @Suppress("unused") val loader: ResourcesLoader,
        @Suppress("unused") val providers: List<ResourcesProvider>,
    )
}
