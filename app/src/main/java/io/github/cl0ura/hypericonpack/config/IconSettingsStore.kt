package io.github.cl0ura.hypericonpack.config

import android.content.Context
import io.github.cl0ura.hypericonpack.ui.AppColorMode
import io.github.cl0ura.hypericonpack.ui.AppAccentColor
import io.github.cl0ura.hypericonpack.ui.AppThemeColorSpec
import io.github.cl0ura.hypericonpack.ui.AppThemePaletteStyle
import io.github.cl0ura.hypericonpack.ui.AppUiMode
import io.github.cl0ura.hypericonpack.ui.UiThemePreferences
import io.github.cl0ura.hypericonpack.ui.withDynamicColor
import io.github.cl0ura.hypericonpack.xposed.XposedServiceBridge
import java.io.File

/** Private storage used only by the module's own UI process. */
class IconSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): IconPackConfig {
        val packageName = preferences.getString(KEY_PACKAGE_NAME, null)?.takeIf { it.isNotBlank() }
        return IconPackConfig(
            packageName = packageName,
            fallbackScaleMultiplier = preferences.getFloat(KEY_FALLBACK_SCALE, DEFAULT_FALLBACK_SCALE)
                .coerceIn(MIN_FALLBACK_SCALE, MAX_FALLBACK_SCALE),
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
            conversionAllApplications = true,
            systemThemeActive = preferences.getBoolean(KEY_SYSTEM_THEME_ACTIVE, false),
            systemThemeAnimationBridge = preferences.getBoolean(KEY_SYSTEM_THEME_ANIMATION_BRIDGE, false),
            revision = preferences.getLong(KEY_REVISION, 0L),
        )
    }

    fun write(config: IconPackConfig): IconPackConfig {
        val packageName = config.packageName?.takeIf { it.isNotBlank() }
        val normalized = config.copy(
            packageName = packageName,
            revision = System.currentTimeMillis(),
        )
        preferences.edit()
            .putString(KEY_PACKAGE_NAME, normalized.packageName)
            .putFloat(
                KEY_FALLBACK_SCALE,
                normalized.fallbackScaleMultiplier.coerceIn(MIN_FALLBACK_SCALE, MAX_FALLBACK_SCALE),
            )
            .putBoolean(KEY_GLOBAL_MONET_ICONS, normalized.globalMonetIcons)
            .putBoolean(KEY_MONET_CUSTOM_COLORS, normalized.monetCustomColors)
            .putInt(KEY_MONET_BACKGROUND_COLOR, normalized.monetBackgroundColor)
            .putInt(KEY_MONET_FOREGROUND_COLOR, normalized.monetForegroundColor)
            .putBoolean(KEY_CONVERSION_ALL_APPLICATIONS, normalized.conversionAllApplications)
            .putBoolean(KEY_SYSTEM_THEME_ACTIVE, normalized.systemThemeActive)
            .putBoolean(KEY_SYSTEM_THEME_ANIMATION_BRIDGE, normalized.systemThemeAnimationBridge)
            .putLong(KEY_REVISION, normalized.revision)
            // Retire state from the previous live Drawable replacement path.
            .remove("enabled")
            .remove("style_unmapped")
            .remove("replace_system_ui")
            // Retire the v0.9.7 fallback-only Monet experiment. Its caches
            // use a separate identity and cannot be mistaken for this global
            // luminance-preserving renderer.
            .remove("experimental_monet_fallbacks")
            .apply()
        XposedServiceBridge.publishConfig(normalized)
        return normalized
    }

    /** Re-emits the current configuration so an alive launcher refreshes its animation bridge. */
    fun touch(): IconPackConfig = write(read())

    /**
     * The active cache path is UI-private bookkeeping for PACKAGE_ADDED. It is
     * never exposed through the Xposed provider and is validated again by the
     * incremental archive updater before it is used.
     */
    fun readActiveArchive(): File? = preferences.getString(KEY_ACTIVE_ARCHIVE_PATH, null)
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isFile)

    fun writeActiveArchive(archive: File?) {
        preferences.edit()
            .putString(KEY_ACTIVE_ARCHIVE_PATH, archive?.absolutePath)
            .apply()
    }

    /**
     * Persists package updates before scheduling background work.  Android can
     * stop a manifest receiver as soon as `onReceive` returns, so a receiver
     * must not be the sole owner of a multi-second ZIP rewrite.
     */
    fun enqueueThemeArchivePackageUpdate(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(PENDING_UPDATE_LOCK) {
            val pending = preferences.getStringSet(KEY_PENDING_ARCHIVE_UPDATES, emptySet())
                .orEmpty()
                .toMutableSet()
            if (pending.add(packageName)) {
                preferences.edit().putStringSet(KEY_PENDING_ARCHIVE_UPDATES, pending).apply()
            }
        }
    }

    fun pendingThemeArchivePackageUpdates(): List<String> = synchronized(PENDING_UPDATE_LOCK) {
        preferences.getStringSet(KEY_PENDING_ARCHIVE_UPDATES, emptySet())
            .orEmpty()
            .asSequence()
            .filter(String::isNotBlank)
            .sorted()
            .toList()
    }

    fun markThemeArchivePackageUpdateComplete(packageName: String) {
        synchronized(PENDING_UPDATE_LOCK) {
            val pending = preferences.getStringSet(KEY_PENDING_ARCHIVE_UPDATES, emptySet())
                .orEmpty()
                .toMutableSet()
            if (pending.remove(packageName)) {
                preferences.edit().putStringSet(KEY_PENDING_ARCHIVE_UPDATES, pending).apply()
            }
        }
    }

    /**
     * UI state intentionally lives beside the module configuration rather than
     * in a second preference file.  It is private to this process and is never
     * exposed through the hook ContentProvider.
     */
    fun readUiThemePreferences(): UiThemePreferences {
        // The old combined mode encoded both light/dark and Monet. Retain its
        // dynamic setting as the default for both Box-style independent
        // switches so updating the module does not unexpectedly recolour the
        // UI on first launch.
        val legacyColorMode = AppColorMode.fromStorage(
            preferences.getInt(KEY_COLOR_MODE, AppColorMode.MONET_SYSTEM.storageValue),
        )
        return UiThemePreferences(
            uiMode = AppUiMode.fromStorage(preferences.getString(KEY_UI_MODE, AppUiMode.MATERIAL.storageValue)),
            colorMode = legacyColorMode.withDynamicColor(false),
            pureBlackDarkTheme = preferences.getBoolean(KEY_PURE_BLACK_DARK_THEME, false),
            materialMonetEnabled = preferences.getBoolean(KEY_MATERIAL_MONET_ENABLED, legacyColorMode.usesMonet),
            miuixMonetEnabled = preferences.getBoolean(KEY_MIUIX_MONET_ENABLED, false),
            accentColor = AppAccentColor.entries.getOrElse(
                preferences.getInt(KEY_ACCENT_COLOR, AppAccentColor.INDIGO.ordinal),
            ) { AppAccentColor.INDIGO },
            accentUseDefault = preferences.getBoolean(KEY_ACCENT_USE_DEFAULT, true),
            paletteStyle = AppThemePaletteStyle.entries.getOrElse(
                preferences.getInt(KEY_THEME_PALETTE_STYLE, AppThemePaletteStyle.TONAL_SPOT.ordinal),
            ) { AppThemePaletteStyle.TONAL_SPOT },
            colorSpec = AppThemeColorSpec.entries.getOrElse(
                preferences.getInt(KEY_THEME_COLOR_SPEC, AppThemeColorSpec.MATERIAL_2021.ordinal),
            ) { AppThemeColorSpec.MATERIAL_2021 },
            enableBlur = preferences.getBoolean(KEY_ENABLE_BLUR, true),
            floatingBottomBar = preferences.getBoolean(KEY_FLOATING_BOTTOM_BAR, false),
            floatingBottomBarBlur = preferences.getBoolean(KEY_FLOATING_BOTTOM_BAR_BLUR, false),
            pageScale = preferences.getFloat(KEY_PAGE_SCALE, 1f).coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE),
        ).normalized()
    }

    fun writeUiThemePreferences(preferencesValue: UiThemePreferences): UiThemePreferences {
        val normalized = preferencesValue.normalized()
        preferences.edit()
            .putString(KEY_UI_MODE, normalized.uiMode.storageValue)
            .putInt(KEY_COLOR_MODE, normalized.colorMode.storageValue)
            .putBoolean(KEY_PURE_BLACK_DARK_THEME, normalized.pureBlackDarkTheme)
            .putBoolean(KEY_MATERIAL_MONET_ENABLED, normalized.materialMonetEnabled)
            .putBoolean(KEY_MIUIX_MONET_ENABLED, normalized.miuixMonetEnabled)
            .putInt(KEY_ACCENT_COLOR, normalized.accentColor.ordinal)
            .putBoolean(KEY_ACCENT_USE_DEFAULT, normalized.accentUseDefault)
            .putInt(KEY_THEME_PALETTE_STYLE, normalized.paletteStyle.ordinal)
            .putInt(KEY_THEME_COLOR_SPEC, normalized.colorSpec.ordinal)
            .putBoolean(KEY_ENABLE_BLUR, normalized.enableBlur)
            .putBoolean(KEY_FLOATING_BOTTOM_BAR, normalized.floatingBottomBar)
            .putBoolean(KEY_FLOATING_BOTTOM_BAR_BLUR, normalized.floatingBottomBarBlur)
            .putFloat(KEY_PAGE_SCALE, normalized.pageScale)
            .apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES = "icon_pack_settings"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_FALLBACK_SCALE = "fallback_scale"
        const val KEY_GLOBAL_MONET_ICONS = "global_monet_icons"
        const val KEY_MONET_CUSTOM_COLORS = "monet_custom_colors"
        const val KEY_MONET_BACKGROUND_COLOR = "monet_background_color"
        const val KEY_MONET_FOREGROUND_COLOR = "monet_foreground_color"
        const val KEY_CONVERSION_ALL_APPLICATIONS = "conversion_all_applications"
        const val KEY_SYSTEM_THEME_ACTIVE = "system_theme_active"
        const val KEY_SYSTEM_THEME_ANIMATION_BRIDGE = "system_theme_animation_bridge"
        const val KEY_REVISION = "revision"
        const val KEY_ACTIVE_ARCHIVE_PATH = "active_archive_path"
        const val KEY_PENDING_ARCHIVE_UPDATES = "pending_archive_updates"
        const val KEY_UI_MODE = "ui_mode"
        const val KEY_COLOR_MODE = "color_mode"
        const val KEY_PURE_BLACK_DARK_THEME = "pure_black_dark_theme"
        const val KEY_MATERIAL_MONET_ENABLED = "material_monet_enabled"
        const val KEY_MIUIX_MONET_ENABLED = "miuix_monet_enabled"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_ACCENT_USE_DEFAULT = "accent_use_default"
        const val KEY_THEME_PALETTE_STYLE = "theme_palette_style"
        const val KEY_THEME_COLOR_SPEC = "theme_color_spec"
        const val KEY_ENABLE_BLUR = "enable_blur"
        const val KEY_FLOATING_BOTTOM_BAR = "floating_bottom_bar"
        const val KEY_FLOATING_BOTTOM_BAR_BLUR = "floating_bottom_bar_blur"
        const val KEY_PAGE_SCALE = "page_scale"
        const val DEFAULT_FALLBACK_SCALE = 0.85f
        const val MIN_FALLBACK_SCALE = 0.65f
        const val MAX_FALLBACK_SCALE = 1.15f
        const val MIN_PAGE_SCALE = 0.8f
        const val MAX_PAGE_SCALE = 1.2f
        val PENDING_UPDATE_LOCK = Any()
    }
}
