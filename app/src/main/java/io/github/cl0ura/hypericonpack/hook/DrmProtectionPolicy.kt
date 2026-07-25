package io.github.cl0ura.hypericonpack.hook

import java.io.File
import java.util.zip.ZipFile

/**
 * Pure decision helper for the system_server DRM bypass.
 *
 * ThemeReceiver validates every non-whitelist file under /data/system/theme.
 * Hand-installed icons lack a Xiaomi-signed rights file, so the validator would
 * otherwise delete them. We only short-circuit the check when the candidate is
 * /data/system/theme/icons and that ZIP contains Hyper Icon Pack's private
 * conversion metadata. This keeps the bypass self-identifying and avoids
 * changing permissions on Xiaomi's DRM-whitelisted .runtime directory merely
 * so system_server can inspect an external marker.
 */
internal object DrmProtectionPolicy {
    private const val ICONS = "/data/system/theme/icons"
    private const val MANAGED_ARCHIVE_METADATA_ENTRY =
        "META-INF/hypericonpack-conversion.properties"

    fun shouldBypass(
        contentPath: String?,
        managedArchive: Boolean,
    ): Boolean {
        if (!managedArchive) return false
        return isProtectedThemePath(contentPath)
    }

    fun isProtectedThemePath(contentPath: String?): Boolean {
        val normalized = contentPath
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf(String::isNotEmpty)
            ?: return false
        return normalized == ICONS
    }

    fun isManagedIconArchive(file: File): Boolean {
        if (!isProtectedThemePath(canonicalPath(file))) return false
        return hasConversionMetadata(file)
    }

    internal fun hasConversionMetadata(file: File): Boolean {
        return runCatching {
            ZipFile(file).use { zip ->
                val metadata = zip.getEntry(MANAGED_ARCHIVE_METADATA_ENTRY)
                metadata != null && !metadata.isDirectory && metadata.size != 0L
            }
        }.getOrDefault(false)
    }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}
