package io.github.cl0ura.hypericonpack.systemtheme

/**
 * Keeps UI-private "managed theme is active" state aligned with the real
 * Root-owned HyperOS theme files.
 *
 * A normal Root/preflight failure is intentionally not treated as inactive:
 * in that case we simply do not know.  Only the explicit status sentinels from
 * [RootThemeIconInstaller.status] mean the managed theme has been removed or
 * replaced outside this app, for example by Theme Manager's RestoreDefault
 * flow deleting /data/system/theme/icons and our marker.
 */
internal object ManagedThemeStateReconciler {
    fun shouldClearManagedThemeState(
        localSystemThemeActive: Boolean,
        status: RootThemeIconInstaller.Result?,
    ): Boolean {
        if (!localSystemThemeActive || status == null || status.success) return false
        return status.output.contains("HYPER_ICONPACK_THEME_INACTIVE") ||
            status.output.contains("HYPER_ICONPACK_THEME_REPLACED")
    }
}
