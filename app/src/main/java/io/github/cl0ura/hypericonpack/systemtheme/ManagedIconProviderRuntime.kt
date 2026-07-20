package io.github.cl0ura.hypericonpack.systemtheme

import android.content.Context
import android.util.LruCache
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import java.util.zip.ZipFile

/**
 * Module-process source of truth for live icon requests.
 *
 * Hooked vendor/system processes must not parse third-party icon packs
 * directly: package visibility and resource ownership differ in every target
 * process. This provider first serves the exact PNG from the selected durable
 * cache archive. Only a genuinely new package is rendered on demand.
 */
internal object ManagedIconProviderRuntime {
    private const val ENTRY_DIRECTORY = "res/drawable-xxhdpi/"
    private const val MAX_CACHE_KB = 16 * 1024

    private val cache = object : LruCache<String, ByteArray>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: ByteArray): Int = (value.size / 1024).coerceAtLeast(1)
    }

    fun loadPackageIconPng(context: Context, packageName: String): ByteArray? {
        val settings = IconSettingsStore(context)
        val config = settings.read()
        val sourcePackage = config.packageName ?: return null
        if (!config.systemThemeActive || packageName.isBlank()) return null

        val key = "${config.revision}|$packageName"
        synchronized(cache) { cache.get(key) }?.let { return it }

        val archived = settings.readActiveArchive()?.let { archive ->
            runCatching {
                ZipFile(archive).use { zip ->
                    val entry = zip.getEntry("$ENTRY_DIRECTORY$packageName.png") ?: return@use null
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            }.getOrNull()
        }
        val rendered = archived ?: HyperOsIconArchiveConverter.renderInstalledPackagePng(
            context = context,
            iconPackPackage = sourcePackage,
            fallbackScaleMultiplier = config.fallbackScaleMultiplier,
            globalMonetIcons = config.globalMonetIcons,
            monetCustomColors = config.monetCustomColors,
            monetBackgroundColor = config.monetBackgroundColor,
            monetForegroundColor = config.monetForegroundColor,
            packageName = packageName,
        )
        if (rendered != null) synchronized(cache) { cache.put(key, rendered) }
        return rendered
    }
}
