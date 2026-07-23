package io.github.cl0ura.hypericonpack.config

import android.content.SharedPreferences

/** API 102 remote-preference contract shared by the app and launcher module. */
object IconRemoteConfig {
    const val GROUP = "icon_config"
    const val KEY_PACKAGE_NAME = "package_name"
    const val KEY_FALLBACK_SCALE = "fallback_scale"
    const val KEY_GLOBAL_MONET_ICONS = "global_monet_icons"
    const val KEY_MONET_CUSTOM_COLORS = "monet_custom_colors"
    const val KEY_MONET_BACKGROUND_COLOR = "monet_background_color"
    const val KEY_MONET_FOREGROUND_COLOR = "monet_foreground_color"
    const val KEY_SYSTEM_THEME_ACTIVE = "system_theme_active"
    const val KEY_SYSTEM_THEME_ANIMATION_BRIDGE = "system_theme_animation_bridge"
    const val KEY_REVISION = "revision"

    fun read(preferences: SharedPreferences): IconPackConfig {
        val packageName = preferences.getString(KEY_PACKAGE_NAME, null)?.takeIf(String::isNotBlank)
        return IconPackConfig(
            packageName = packageName,
            fallbackScaleMultiplier = preferences.getFloat(KEY_FALLBACK_SCALE, 0.85f)
                .coerceIn(0.65f, 1.15f),
            globalMonetIcons = preferences.getBoolean(KEY_GLOBAL_MONET_ICONS, false),
            monetCustomColors = preferences.getBoolean(KEY_MONET_CUSTOM_COLORS, false),
            monetBackgroundColor = preferences.getInt(
                KEY_MONET_BACKGROUND_COLOR,
                IconPackConfig.DEFAULT_MONET_BACKGROUND_COLOR,
            ),
            monetForegroundColor = preferences.getInt(
                KEY_MONET_FOREGROUND_COLOR,
                IconPackConfig.DEFAULT_MONET_FOREGROUND_COLOR,
            ),
            systemThemeActive = preferences.getBoolean(KEY_SYSTEM_THEME_ACTIVE, false) && packageName != null,
            systemThemeAnimationBridge = preferences.getBoolean(
                KEY_SYSTEM_THEME_ANIMATION_BRIDGE,
                false,
            ) && packageName != null,
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
    /**
     * Retired launcher animation-bridge flag. Kept only so old preference
     * snapshots deserialize safely; writes always force this to false.
     */
    val systemThemeAnimationBridge: Boolean = false,
    val revision: Long,
) {
    companion object {
        val DEFAULT_MONET_BACKGROUND_COLOR: Int = 0xFFE8DEF8.toInt()
        val DEFAULT_MONET_FOREGROUND_COLOR: Int = 0xFF4A4458.toInt()

        fun disabled() = IconPackConfig(packageName = null, revision = 0L)
    }
}
