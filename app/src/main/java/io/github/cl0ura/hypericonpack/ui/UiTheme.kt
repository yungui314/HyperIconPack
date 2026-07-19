package io.github.cl0ura.hypericonpack.ui

import android.app.Activity
import android.app.WallpaperManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Kept for preference migration; the active renderer is always Miuix. */
enum class AppUiMode(val storageValue: String, val label: String) {
    MATERIAL("material", "Material 3"),
    MIUIX("miuix", "Miuix"),
    ;

    companion object {
        fun fromStorage(value: String?): AppUiMode = entries.firstOrNull { it.storageValue == value } ?: MIUIX
    }
}

/**
 * Mirrors KernelSU's practical colour-mode model.  The Monet variants use the
 * Android wallpaper palette and the non-Monet variants retain a neutral app
 * palette while still respecting the selected light/dark behaviour.
 */
enum class AppColorMode(val storageValue: Int, val label: String) {
    SYSTEM(0, "跟随系统"),
    LIGHT(1, "浅色"),
    DARK(2, "深色"),
    MONET_SYSTEM(3, "莫奈取色 · 跟随系统"),
    MONET_LIGHT(4, "莫奈取色 · 浅色"),
    MONET_DARK(5, "莫奈取色 · 深色"),
    ;

    val followsSystem: Boolean
        get() = this == SYSTEM || this == MONET_SYSTEM
    val forcesDark: Boolean
        get() = this == DARK || this == MONET_DARK
    val usesMonet: Boolean
        get() = this == MONET_SYSTEM || this == MONET_LIGHT || this == MONET_DARK

    companion object {
        fun fromStorage(value: Int): AppColorMode = entries.firstOrNull { it.storageValue == value } ?: MONET_SYSTEM
    }
}

/** Box-style separation of the light/dark choice from dynamic-color choice. */
enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** Palette styles directly supported by Miuix's ThemeController. */
enum class AppThemePaletteStyle(val label: String, val miuixValue: ThemePaletteStyle) {
    TONAL_SPOT("Tonal Spot", ThemePaletteStyle.TonalSpot),
    NEUTRAL("Neutral", ThemePaletteStyle.Neutral),
    VIBRANT("Vibrant", ThemePaletteStyle.Vibrant),
    EXPRESSIVE("Expressive", ThemePaletteStyle.Expressive),
    RAINBOW("Rainbow", ThemePaletteStyle.Rainbow),
    FRUIT_SALAD("Fruit Salad", ThemePaletteStyle.FruitSalad),
    MONOCHROME("Monochrome", ThemePaletteStyle.Monochrome),
    FIDELITY("Fidelity", ThemePaletteStyle.Fidelity),
    CONTENT("Content", ThemePaletteStyle.Content),
}

enum class AppThemeColorSpec(val label: String, val miuixValue: ThemeColorSpec) {
    MATERIAL_2021("2021", ThemeColorSpec.Spec2021),
    MATERIAL_2025("2025 Expressive", ThemeColorSpec.Spec2025),
}

/** Box's selectable Material accent family, retained independently of Monet. */
enum class AppAccentColor(val label: String, val argb: Long) {
    RED("红色", 0xFFBA1A1AL),
    PINK("粉色", 0xFF9C405CL),
    PURPLE("紫色", 0xFF7A4EABL),
    DEEP_PURPLE("深紫", 0xFF66558FL),
    INDIGO("靛蓝", 0xFF4F6098L),
    BLUE("蓝色", 0xFF365E9DL),
    LIGHT_BLUE("浅蓝", 0xFF2D6F8EL),
    CYAN("青色", 0xFF1B7186L),
    TEAL("蓝绿", 0xFF276A62L),
    GREEN("绿色", 0xFF386A20L),
    LIGHT_GREEN("浅绿", 0xFF537A1BL),
    LIME("青柠", 0xFF5D7600L),
    YELLOW("黄色", 0xFF745B00L),
    AMBER("琥珀", 0xFF765A00L),
    ORANGE("橙色", 0xFF8B5000L),
    DEEP_ORANGE("深橙", 0xFF9E3D18L),
    BROWN("棕色", 0xFF765548L),
    GREY("灰色", 0xFF5F5E5EL),
    BLUE_GREY("蓝灰", 0xFF54606CL),
    ROSE("玫瑰", 0xFF9D3D65L),
    ;

    val color: Color get() = Color(argb)
}

val AppColorMode.themeMode: AppThemeMode
    get() = when (this) {
        AppColorMode.SYSTEM, AppColorMode.MONET_SYSTEM -> AppThemeMode.SYSTEM
        AppColorMode.LIGHT, AppColorMode.MONET_LIGHT -> AppThemeMode.LIGHT
        AppColorMode.DARK, AppColorMode.MONET_DARK -> AppThemeMode.DARK
    }

fun AppColorMode.withThemeMode(themeMode: AppThemeMode): AppColorMode = when (themeMode) {
    AppThemeMode.SYSTEM -> if (usesMonet) AppColorMode.MONET_SYSTEM else AppColorMode.SYSTEM
    AppThemeMode.LIGHT -> if (usesMonet) AppColorMode.MONET_LIGHT else AppColorMode.LIGHT
    AppThemeMode.DARK -> if (usesMonet) AppColorMode.MONET_DARK else AppColorMode.DARK
}

fun AppColorMode.withDynamicColor(enabled: Boolean): AppColorMode = when (themeMode) {
    AppThemeMode.SYSTEM -> if (enabled) AppColorMode.MONET_SYSTEM else AppColorMode.SYSTEM
    AppThemeMode.LIGHT -> if (enabled) AppColorMode.MONET_LIGHT else AppColorMode.LIGHT
    AppThemeMode.DARK -> if (enabled) AppColorMode.MONET_DARK else AppColorMode.DARK
}

data class UiThemePreferences(
    val uiMode: AppUiMode = AppUiMode.MIUIX,
    val colorMode: AppColorMode = AppColorMode.SYSTEM,
    val pureBlackDarkTheme: Boolean = false,
    // Box keeps the two UI engines' dynamic-colour settings independent. A
    // choice made while previewing Material must not unexpectedly recolour the
    // Miuix implementation (and vice versa) after a later style switch.
    val materialMonetEnabled: Boolean = true,
    val miuixMonetEnabled: Boolean = false,
    val accentColor: AppAccentColor = AppAccentColor.INDIGO,
    val accentUseDefault: Boolean = true,
    val paletteStyle: AppThemePaletteStyle = AppThemePaletteStyle.TONAL_SPOT,
    val colorSpec: AppThemeColorSpec = AppThemeColorSpec.MATERIAL_2021,
    val enableBlur: Boolean = true,
    val floatingBottomBar: Boolean = false,
    val floatingBottomBarBlur: Boolean = false,
    val pageScale: Float = 1f,
) {
    val dynamicColorEnabled: Boolean
        get() = miuixMonetEnabled

    fun normalized(): UiThemePreferences = copy(
        uiMode = AppUiMode.MIUIX,
        // Persist the light/dark behaviour separately from the active
        // renderer; this is the migration path from the old combined enum.
        colorMode = colorMode.withDynamicColor(false),
        pureBlackDarkTheme = pureBlackDarkTheme,
        materialMonetEnabled = materialMonetEnabled,
        miuixMonetEnabled = miuixMonetEnabled,
        accentColor = accentColor,
        accentUseDefault = accentUseDefault,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
        enableBlur = enableBlur,
        floatingBottomBar = floatingBottomBar,
        floatingBottomBarBlur = floatingBottomBarBlur && floatingBottomBar,
        pageScale = pageScale.coerceIn(0.8f, 1.2f),
    )
}

/** Miuix-only theme boundary for all routed UI surfaces. */
@Composable
fun HyperIconTheme(
    preferences: UiThemePreferences,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = preferences.colorMode.forcesDark || (preferences.colorMode.followsSystem && systemDark)
    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val lightColors = lightColorScheme()
    val baseDarkColors = darkColorScheme()
    val darkColors = if (preferences.pureBlackDarkTheme) {
        baseDarkColors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color(0xFF0C0C0C),
            surfaceContainerHigh = Color(0xFF141414),
            surfaceContainerHighest = Color(0xFF1C1C1C),
        )
    } else {
        baseDarkColors
    }
    val baseDensity = LocalDensity.current
    val wallpaperSeed = remember(context, preferences.miuixMonetEnabled) {
        if (preferences.miuixMonetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor
                    ?.toArgb()
                    ?.let(::Color)
            }.getOrNull()
        } else {
            null
        }
    }
    // Box exposes 80%-120% page scaling. Supplying density at the root scales
    // both Compose dp geometry and sp typography without a graphics-layer
    // transform (so touch targets and clipping remain correct).
    val scaledDensity = androidx.compose.ui.unit.Density(
        density = baseDensity.density * preferences.pageScale,
        fontScale = baseDensity.fontScale * preferences.pageScale,
    )
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MiuixTheme(
            controller = ThemeController(
                colorSchemeMode = preferences.colorMode
                    .withDynamicColor(preferences.miuixMonetEnabled)
                    .toMiuixColorSchemeMode(),
                lightColors = lightColors,
                darkColors = darkColors,
                // Supplying a wallpaper seed is required for Miuix to apply
                // paletteStyle and colorSpec. With a null keyColor the
                // controller returns Android's prebuilt dynamic palette and
                // those two settings are intentionally bypassed.
                keyColor = when {
                    !preferences.miuixMonetEnabled -> null
                    !preferences.accentUseDefault -> preferences.accentColor.color
                    else -> wallpaperSeed
                },
                colorSpec = preferences.colorSpec.miuixValue,
                paletteStyle = preferences.paletteStyle.miuixValue,
            ),
            content = content,
        )
    }
}

private fun AppColorMode.toMiuixColorSchemeMode(): ColorSchemeMode = when (this) {
    AppColorMode.SYSTEM -> ColorSchemeMode.System
    AppColorMode.LIGHT -> ColorSchemeMode.Light
    AppColorMode.DARK -> ColorSchemeMode.Dark
    AppColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
    AppColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
    AppColorMode.MONET_DARK -> ColorSchemeMode.MonetDark
}
