package io.github.cl0ura.hypericonpack.config

import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * Cross-process contract shared by the settings app and the injected launcher
 * process. It intentionally contains no user data beyond the selected package.
 */
object IconConfigContract {
    const val AUTHORITY = "io.github.cl0ura.hypericonpack.config"
    const val PATH_CONFIG = "config"
    const val PATH_ICON = "icon"
    val CONFIG_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_CONFIG")

    fun iconUri(packageName: String, revision: Long): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath(PATH_ICON)
        .appendPath(packageName)
        // The provider ignores this value, but Android treats a new revision
        // as a distinct URI and therefore cannot hand a caller stale content.
        .appendQueryParameter("revision", revision.toString())
        .build()

    const val COLUMN_PACKAGE_NAME = "package_name"
    const val COLUMN_FALLBACK_SCALE = "fallback_scale"
    const val COLUMN_GLOBAL_MONET_ICONS = "global_monet_icons"
    const val COLUMN_MONET_CUSTOM_COLORS = "monet_custom_colors"
    const val COLUMN_MONET_BACKGROUND_COLOR = "monet_background_color"
    const val COLUMN_MONET_FOREGROUND_COLOR = "monet_foreground_color"
    const val COLUMN_SYSTEM_THEME_ACTIVE = "system_theme_active"
    const val COLUMN_SYSTEM_THEME_ANIMATION_BRIDGE = "system_theme_animation_bridge"
    const val COLUMN_REVISION = "revision"

    fun read(
        context: Context,
        onError: (Throwable) -> Unit = {},
    ): IconPackConfig {
        return try {
            val cursor = context.contentResolver.query(CONFIG_URI, null, null, null, null)
                ?: return IconPackConfig.disabled()
            cursor.use { readCursor(it) }
        } catch (throwable: Throwable) {
            // A hook must never make System Launcher fail to load because the
            // companion app is disabled, being updated, or unavailable.
            onError(throwable)
            IconPackConfig.disabled()
        }
    }

    fun notifyChanged(context: Context) {
        context.contentResolver.notifyChange(CONFIG_URI, null)
    }

    private fun readCursor(cursor: Cursor): IconPackConfig {
        if (!cursor.moveToFirst()) return IconPackConfig.disabled()
        val packageName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME))
            ?.takeIf { it.isNotBlank() }
        val fallbackScaleMultiplier = cursor.getFloat(
            cursor.getColumnIndexOrThrow(COLUMN_FALLBACK_SCALE),
        ).coerceIn(0.65f, 1.15f)
        val globalMonetIcons = cursor.getInt(
            cursor.getColumnIndexOrThrow(COLUMN_GLOBAL_MONET_ICONS),
        ) != 0
        val monetCustomColors = cursor.getInt(
            cursor.getColumnIndexOrThrow(COLUMN_MONET_CUSTOM_COLORS),
        ) != 0
        val monetBackgroundColor = cursor.getInt(
            cursor.getColumnIndexOrThrow(COLUMN_MONET_BACKGROUND_COLOR),
        )
        val monetForegroundColor = cursor.getInt(
            cursor.getColumnIndexOrThrow(COLUMN_MONET_FOREGROUND_COLOR),
        )
        val systemThemeActive = cursor.getInt(
            cursor.getColumnIndexOrThrow(COLUMN_SYSTEM_THEME_ACTIVE),
        ) != 0
        val systemThemeAnimationBridge = cursor.getInt(
            cursor.getColumnIndexOrThrow(COLUMN_SYSTEM_THEME_ANIMATION_BRIDGE),
        ) != 0
        val revision = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_REVISION))
        return IconPackConfig(
            packageName = packageName,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            systemThemeActive = systemThemeActive && packageName != null,
            systemThemeAnimationBridge = systemThemeAnimationBridge && packageName != null,
            revision = revision,
        )
    }
}

data class IconPackConfig(
    val packageName: String?,
    /**
     * HyperOS receives an already normalised launcher icon before an Xposed
     * after-hook can run.  Standard appfilter's <scale> still remains the
     * primary per-pack value; this is only the final visual calibration used
     * for generated (unmapped) fallback icons.
     */
    val fallbackScaleMultiplier: Float = 0.85f,
    /**
     * Experimental offline Monet treatment for every generated icon. This is
     * deliberately a converter option, not a live Xposed colour filter: the
     * generated pixels remain stable in SystemUI, Settings and launcher
     * transitions until the user explicitly converts and applies another ZIP.
     */
    val globalMonetIcons: Boolean = false,
    /** Use a fixed two-colour palette instead of the current wallpaper palette. */
    val monetCustomColors: Boolean = false,
    val monetBackgroundColor: Int = DEFAULT_MONET_BACKGROUND_COLOR,
    val monetForegroundColor: Int = DEFAULT_MONET_FOREGROUND_COLOR,
    /** Conversion is always full-device; the field remains only to retire older scoped settings. */
    val conversionAllApplications: Boolean = true,
    /** True only after this module has installed its managed HyperOS icons ZIP. */
    val systemThemeActive: Boolean = false,
    /** Narrow launcher-only bridge that gives static themed PNGs a real outline in transitions. */
    val systemThemeAnimationBridge: Boolean = false,
    val revision: Long,
) {
    companion object {
        val DEFAULT_MONET_BACKGROUND_COLOR: Int = 0xFFE8DEF8.toInt()
        val DEFAULT_MONET_FOREGROUND_COLOR: Int = 0xFF4A4458.toInt()

        fun disabled() = IconPackConfig(packageName = null, revision = 0L)
    }
}
