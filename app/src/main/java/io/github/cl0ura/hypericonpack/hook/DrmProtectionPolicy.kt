package io.github.cl0ura.hypericonpack.hook

/**
 * Pure decision helper for the system_server DRM bypass.
 *
 * ThemeReceiver validates every non-whitelist file under /data/system/theme.
 * Hand-installed icons lack a Xiaomi-signed rights file, so the validator would
 * otherwise delete them.  We only short-circuit the check when this app still
 * owns the active icons module (marker present) and the candidate path is one
 * of the two archives we install.
 */
internal object DrmProtectionPolicy {
    private const val ICONS = "/data/system/theme/icons"
    private const val DYNAMIC_ICONS = "/data/system/theme/dynamicicons"

    fun shouldBypass(
        contentPath: String?,
        managedThemeMarkerPresent: Boolean,
    ): Boolean {
        if (!managedThemeMarkerPresent) return false
        return isProtectedThemePath(contentPath)
    }

    fun isProtectedThemePath(contentPath: String?): Boolean {
        val normalized = contentPath
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf(String::isNotEmpty)
            ?: return false
        return normalized == ICONS || normalized == DYNAMIC_ICONS
    }
}
