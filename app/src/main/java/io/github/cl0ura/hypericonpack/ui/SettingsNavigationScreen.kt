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
import io.github.cl0ura.hypericonpack.systemtheme.IconArchiveInfo
import io.github.cl0ura.hypericonpack.systemtheme.IconArchiveConversionController
import io.github.cl0ura.hypericonpack.systemtheme.IconArchiveConversionRequest
import io.github.cl0ura.hypericonpack.systemtheme.IconArchiveConversionState
import io.github.cl0ura.hypericonpack.systemtheme.PackageThemeArchiveUpdateScheduler
import io.github.cl0ura.hypericonpack.systemtheme.RootThemeIconInstaller
import io.github.cl0ura.hypericonpack.systemtheme.ThemeArchiveMutationGate
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

internal enum class SettingsDestination {
    OVERVIEW,
    CREATOR,
    ARCHIVES,
    THEME,
}

internal data class IconSource(
    val packageName: String,
    val label: String,
)

@Composable
fun SettingsNavigationScreen(
    rootPadding: PaddingValues,
    themePreferences: UiThemePreferences,
    onThemePreferencesChanged: (UiThemePreferences) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val coroutineScope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.OVERVIEW) }
    var iconPacks by remember { mutableStateOf(emptyList<IconPackDescriptor>()) }
    var archives by remember { mutableStateOf(emptyList<IconArchiveInfo>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanSummary by remember { mutableStateOf("尚未扫描") }
    val sourceLabels = remember(iconPacks) {
        buildMap {
            put(HyperOsIconArchiveConverter.ORIGINAL_ICON_PACKAGE, HyperOsIconArchiveConverter.ORIGINAL_ICON_LABEL)
            iconPacks.forEach { put(it.packageName, it.label) }
        }
    }

    fun refreshInventory() {
        if (scanning) return
        coroutineScope.launch {
            scanning = true
            scanSummary = "正在扫描…"
            val result = withContext(Dispatchers.Default) {
                val packs = IconPackDiscovery.discover(context)
                val cached = withContext(Dispatchers.IO) {
                    HyperOsIconArchiveConverter.cachedArchiveInfos(context)
                }
                packs to cached
            }
            iconPacks = result.first
            archives = result.second
            scanSummary = "${iconPacks.size} 个图标包 · ${archives.size} 个存档"
            scanning = false
        }
    }

    LaunchedEffect(Unit) { refreshInventory() }
    BackHandler(enabled = destination != SettingsDestination.OVERVIEW) {
        destination = SettingsDestination.OVERVIEW
    }

    when (destination) {
        SettingsDestination.OVERVIEW -> SettingsOverviewPage(
            rootPadding = rootPadding,
            scanSummary = scanSummary,
            scanning = scanning,
            archiveCount = archives.size,
            onScan = ::refreshInventory,
            onCreate = { destination = SettingsDestination.CREATOR },
            onArchives = { destination = SettingsDestination.ARCHIVES },
            onTheme = { destination = SettingsDestination.THEME },
        )

        SettingsDestination.CREATOR -> IconArchiveCreatorPage(
            rootPadding = rootPadding,
            iconPacks = iconPacks,
            onBack = { destination = SettingsDestination.OVERVIEW },
            onArchiveSaved = ::refreshInventory,
        )

        SettingsDestination.ARCHIVES -> ConvertedArchivesPage(
            rootPadding = rootPadding,
            archives = archives,
            sourceLabels = sourceLabels,
            onBack = { destination = SettingsDestination.OVERVIEW },
            onArchivesChanged = ::refreshInventory,
        )

        SettingsDestination.THEME -> ThemeSettingsPage(
            rootPadding = rootPadding,
            themePreferences = themePreferences,
            onThemePreferencesChanged = onThemePreferencesChanged,
            onBack = { destination = SettingsDestination.OVERVIEW },
        )

    }
}

@Composable
internal fun SettingsOverviewPage(
    rootPadding: PaddingValues,
    scanSummary: String,
    scanning: Boolean,
    archiveCount: Int,
    onScan: () -> Unit,
    onCreate: () -> Unit,
    onArchives: () -> Unit,
    onTheme: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settingsStore = remember(context) { IconSettingsStore(context) }
    val coroutineScope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var restoreDialogVisible by rememberSaveable { mutableStateOf(false) }
    var restoreSummary by remember { mutableStateOf<String?>(null) }

    fun restoreOriginalIcons() {
        if (restoring) return
        coroutineScope.launch {
            restoring = true
            val restoreAndRefresh = withContext(Dispatchers.IO) {
                ThemeArchiveMutationGate.withLock {
                    val restore = RootThemeIconInstaller.restore()
                    val refresh = if (restore.success) RootAccess.refreshIconSurfaces() else null
                    restore to refresh
                }
            }
            val result = restoreAndRefresh.first
            restoreSummary = if (result.success) {
                settingsStore.writeActiveArchive(null)
                val refresh = restoreAndRefresh.second
                settingsStore.write(
                    settingsStore.read().copy(
                        packageName = null,
                        systemThemeActive = false,
                    ),
                )
                AppLog.info(
                    context,
                    "Restored system icons; surfacesRefreshed=${refresh?.success == true}; " +
                        refresh?.output.orEmpty(),
                )
                if (refresh?.success == true) {
                    "已恢复系统原本图标，桌面与系统界面已刷新"
                } else {
                    "已恢复系统图标；界面刷新未完成，建议重启设备"
                }
            } else {
                AppLog.warning(context, "System icon restore failed: ${result.output}")
                "恢复失败：${result.output.ifBlank { "请检查 Root 授权" }}"
            }
            restoring = false
        }
    }

    IconAppPage(title = "设置", rootPadding = rootPadding) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsContentPadding(pagePadding),
        ) {
            item {
                SettingsSection("图标包管理与转换") {
                    ArrowPreference(
                        title = if (scanning) "正在扫描" else "扫描已安装图标包",
                        summary = scanSummary,
                        onClick = onScan,
                        enabled = !scanning,
                    )
                    ArrowPreference(
                        title = "制作图标包",
                        summary = "选择来源、适配比例和 Monet",
                        onClick = onCreate,
                    )
                }
            }
            item {
                SettingsSection("使用转换的图标包") {
                    ArrowPreference(
                        title = "图标存档",
                        summary = if (archiveCount == 0) "暂无存档" else "$archiveCount 个可用存档",
                        onClick = onArchives,
                    )
                }
            }
            item {
                SettingsSection("恢复系统原本图标") {
                    ArrowPreference(
                        title = if (restoring) "正在恢复" else "恢复系统图标",
                        summary = restoreSummary ?: "撤销模块当前应用的图标主题",
                        onClick = { restoreDialogVisible = true },
                        enabled = !restoring,
                    )
                }
            }
            item {
                SettingsSection("主题设置") {
                    ArrowPreference(
                        title = "界面与配色",
                        summary = "主题模式、Monet、模糊和界面缩放",
                        onClick = onTheme,
                    )
                }
            }
        }
    }

    ConfirmDialog(
        show = restoreDialogVisible,
        title = "恢复系统原本图标？",
        summary = "这会撤销模块当前管理的系统图标主题。",
        confirmText = "恢复",
        onDismiss = { restoreDialogVisible = false },
        onConfirm = {
            restoreDialogVisible = false
            restoreOriginalIcons()
        },
    )
}
