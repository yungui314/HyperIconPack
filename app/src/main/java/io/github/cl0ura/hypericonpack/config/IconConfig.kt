package io.github.cl0ura.hypericonpack.config

import android.content.SharedPreferences

/**
 * Minimal API 102 remote-preference contract.
 *
 * Icon replacement uses native HyperOS theme archives. The Xposed module only
 * needs to know whether this app still owns the active theme so DRM bypass can
 * stay scoped; Monet/scale settings are local conversion inputs only.
 */
object IconRemoteConfig {
    const val GROUP = "icon_config"
    const val KEY_PACKAGE_NAME = "package_name"
    const val KEY_SYSTEM_THEME_ACTIVE = "system_theme_active"
    const val KEY_REVISION = "revision"

    fun read(preferences: SharedPreferences): IconPackConfig {
        val packageName = preferences.getString(KEY_PACKAGE_NAME, null)?.takeIf(String::isNotBlank)
        return IconPackConfig(
            packageName = packageName,
            systemThemeActive = preferences.getBoolean(KEY_SYSTEM_THEME_ACTIVE, false) && packageName != null,
            revision = preferences.getLong(KEY_REVISION, 0L),
        )
    }
}

data class IconPackConfig(
    val packageName: String?,
    /** Final visual calibration for generated, unmapped fallback icons. */
    val fallbackScaleMultiplier: Float = 0.85f,
    /** Offline Monet treatment baked into the generated system theme archive. */
    val globalMonetIcons: Boolean = false,
    /** Use a fixed two-colour palette instead of the current wallpaper palette. */
    val monetCustomColors: Boolean = false,
    val monetBackgroundColor: Int = DEFAULT_MONET_BACKGROUND_COLOR,
    val monetForegroundColor: Int = DEFAULT_MONET_FOREGROUND_COLOR,
    /** Conversion is always full-device; retained to retire older settings. */
    val conversionAllApplications: Boolean = true,
    /** True only after this module has installed its managed HyperOS icons ZIP. */
    val systemThemeActive: Boolean = false,
    val revision: Long,
) {
    companion object {
        val DEFAULT_MONET_BACKGROUND_COLOR: Int = 0xFFE8DEF8.toInt()
        val DEFAULT_MONET_FOREGROUND_COLOR: Int = 0xFF4A4458.toInt()

        fun disabled() = IconPackConfig(packageName = null, revision = 0L)
    }
}
