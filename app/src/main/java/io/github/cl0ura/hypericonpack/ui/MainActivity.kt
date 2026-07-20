package io.github.cl0ura.hypericonpack.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.cl0ura.hypericonpack.BuildConfig
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.logging.AppLog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Two-page shell. The information page and settings navigator stay alive in
 * a pager, while the bottom navigation switches top-level destinations.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info(applicationContext, "Application opened, version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val context = applicationContext
            val settingsStore = remember(context) { IconSettingsStore(context) }
            var themePreferences by remember { mutableStateOf(settingsStore.readUiThemePreferences()) }

            HyperIconTheme(preferences = themePreferences) {
                HyperIconRoot(
                    themePreferences = themePreferences,
                    onThemePreferencesChanged = { updated ->
                        themePreferences = settingsStore.writeUiThemePreferences(updated)
                    },
                )
            }
        }
    }
}

@Composable
private fun HyperIconRoot(
    themePreferences: UiThemePreferences,
    onThemePreferencesChanged: (UiThemePreferences) -> Unit,
) {
    var selectedPage by rememberSaveable { mutableIntStateOf(HOME_PAGE) }
    val pageStateHolder = rememberSaveableStateHolder()
    val blurActive = themePreferences.enableBlur &&
        themePreferences.floatingBottomBar &&
        themePreferences.floatingBottomBarBlur &&
        isRuntimeShaderSupported()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = if (blurActive) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
    val pages: @Composable (PaddingValues) -> Unit = { rootPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            pageStateHolder.SaveableStateProvider(selectedPage) {
                when (selectedPage) {
                    HOME_PAGE -> ModuleHomeScreen(rootPadding = rootPadding)
                    SETTINGS_PAGE -> SettingsNavigationScreen(
                        rootPadding = rootPadding,
                        themePreferences = themePreferences,
                        onThemePreferencesChanged = onThemePreferencesChanged,
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            IconBottomBar(
                selectedPage = selectedPage,
                onPageSelected = { selectedPage = it },
                floating = themePreferences.floatingBottomBar,
                glass = themePreferences.enableBlur && themePreferences.floatingBottomBarBlur,
                backdrop = backdrop,
            )
        },
        content = pages,
    )
}

private const val HOME_PAGE = 0
private const val SETTINGS_PAGE = 1
