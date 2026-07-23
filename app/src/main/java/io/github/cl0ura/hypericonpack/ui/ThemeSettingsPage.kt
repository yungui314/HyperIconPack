package io.github.cl0ura.hypericonpack.ui

import io.github.cl0ura.hypericonpack.root.RootAccess
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.cl0ura.hypericonpack.config.IconPackConfig
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.logging.AppLog
import io.github.cl0ura.hypericonpack.iconpack.IconPackDescriptor
import io.github.cl0ura.hypericonpack.iconpack.IconPackDiscovery
import io.github.cl0ura.hypericonpack.systemtheme.HyperOsIconArchiveConverter
import io.github.cl0ura.hypericonpack.systemtheme.IconArchiveConversionController
import io.github.cl0ura.hypericonpack.systemtheme.IconArchiveConversionRequest
import io.github.cl0ura.hypericonpack.systemtheme.IconArchiveConversionState
import io.github.cl0ura.hypericonpack.systemtheme.PackageThemeArchiveUpdateScheduler
import io.github.cl0ura.hypericonpack.systemtheme.RootThemeIconInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
internal fun ThemeSettingsPage(
    rootPadding: PaddingValues,
    themePreferences: UiThemePreferences,
    onThemePreferencesChanged: (UiThemePreferences) -> Unit,
    onBack: () -> Unit,
) {
    val themeModes = AppThemeMode.entries

    SettingsSecondaryPage(title = "主题设置", rootPadding = rootPadding, onBack = onBack) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsContentPadding(pagePadding),
        ) {
            item {
                SettingsSection("外观") {
                    OverlayDropdownPreference(
                        title = "主题模式",
                        items = themeModes.map { it.label },
                        selectedIndex = themeModes.indexOf(themePreferences.colorMode.themeMode),
                        onSelectedIndexChange = { index ->
                            onThemePreferencesChanged(
                                themePreferences.copy(
                                    colorMode = themePreferences.colorMode.withThemeMode(themeModes[index]),
                                ),
                            )
                        },
                    )
                    SwitchPreference(
                        title = "深色使用纯黑背景",
                        checked = themePreferences.pureBlackDarkTheme,
                        onCheckedChange = { enabled ->
                            onThemePreferencesChanged(themePreferences.copy(pureBlackDarkTheme = enabled))
                        },
                    )
                }
            }
            item {
                SettingsSection("动态色彩") {
                    SwitchPreference(
                        title = "Monet 取色",
                        summary = "使用系统壁纸动态配色",
                        checked = themePreferences.miuixMonetEnabled,
                        onCheckedChange = { enabled ->
                            onThemePreferencesChanged(themePreferences.copy(miuixMonetEnabled = enabled))
                        },
                    )
                    OverlayDropdownPreference(
                        title = "色彩风格",
                        items = AppThemePaletteStyle.entries.map { it.label },
                        selectedIndex = AppThemePaletteStyle.entries.indexOf(themePreferences.paletteStyle),
                        enabled = themePreferences.miuixMonetEnabled,
                        onSelectedIndexChange = { index ->
                            onThemePreferencesChanged(
                                themePreferences.copy(paletteStyle = AppThemePaletteStyle.entries[index]),
                            )
                        },
                    )
                    OverlayDropdownPreference(
                        title = "色彩标准",
                        items = AppThemeColorSpec.entries.map { it.label },
                        selectedIndex = AppThemeColorSpec.entries.indexOf(themePreferences.colorSpec),
                        enabled = themePreferences.miuixMonetEnabled,
                        onSelectedIndexChange = { index ->
                            onThemePreferencesChanged(
                                themePreferences.copy(colorSpec = AppThemeColorSpec.entries[index]),
                            )
                        },
                    )
                }
            }
            item {
                SettingsSection("显示与交互") {
                    SwitchPreference(
                        title = "模糊效果",
                        checked = themePreferences.enableBlur,
                        onCheckedChange = { enabled ->
                            onThemePreferencesChanged(themePreferences.copy(enableBlur = enabled))
                        },
                    )
                    SwitchPreference(
                        title = "悬浮底栏",
                        checked = themePreferences.floatingBottomBar,
                        onCheckedChange = { enabled ->
                            onThemePreferencesChanged(themePreferences.copy(floatingBottomBar = enabled))
                        },
                    )
                    SwitchPreference(
                        title = "底栏半透明效果",
                        checked = themePreferences.floatingBottomBarBlur,
                        onCheckedChange = { enabled ->
                            onThemePreferencesChanged(themePreferences.copy(floatingBottomBarBlur = enabled))
                        },
                        enabled = themePreferences.floatingBottomBar && themePreferences.enableBlur,
                    )
                    SliderPreference(
                        title = "界面缩放",
                        valueText = "${(themePreferences.pageScale * 100).roundToInt()}%",
                        value = themePreferences.pageScale,
                        onValueChange = { scale ->
                            onThemePreferencesChanged(themePreferences.copy(pageScale = scale))
                        },
                        valueRange = 0.8f..1.2f,
                        steps = 7,
                    )
                }
            }
        }
    }
}
