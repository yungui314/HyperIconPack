package io.github.cl0ura.hypericonpack.ui

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

private enum class SettingsDestination {
    OVERVIEW,
    CREATOR,
    ARCHIVES,
    THEME,
}

private data class IconSource(
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
    var archives by remember { mutableStateOf(emptyList<HyperOsIconArchiveConverter.ExistingArchiveInfo>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanSummary by remember { mutableStateOf("尚未扫描") }

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
private fun SettingsOverviewPage(
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
            val result = withContext(Dispatchers.IO) { RootThemeIconInstaller.restore() }
            restoreSummary = if (result.success) {
                settingsStore.writeActiveArchive(null)
                settingsStore.write(
                    settingsStore.read().copy(
                        packageName = null,
                        systemThemeActive = false,
                        systemThemeAnimationBridge = false,
                    ),
                )
                "已恢复系统原本图标"
            } else {
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

@Composable
private fun IconArchiveCreatorPage(
    rootPadding: PaddingValues,
    iconPacks: List<IconPackDescriptor>,
    onBack: () -> Unit,
    onArchiveSaved: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settingsStore = remember(context) { IconSettingsStore(context) }
    val initialConfig = remember { settingsStore.read() }
    val conversionState by IconArchiveConversionController.state.collectAsState()
    val sources = remember(iconPacks) {
        listOf(
            IconSource(
                packageName = HyperOsIconArchiveConverter.ORIGINAL_ICON_PACKAGE,
                label = HyperOsIconArchiveConverter.ORIGINAL_ICON_LABEL,
            ),
        ) + iconPacks.map { IconSource(packageName = it.packageName, label = it.label) }
    }
    var selectedSourcePackage by rememberSaveable {
        mutableStateOf(initialConfig.packageName ?: HyperOsIconArchiveConverter.ORIGINAL_ICON_PACKAGE)
    }
    val selectedSource = sources.firstOrNull { it.packageName == selectedSourcePackage }
    var scale by rememberSaveable { mutableFloatStateOf(initialConfig.fallbackScaleMultiplier) }
    var globalMonet by rememberSaveable { mutableStateOf(initialConfig.globalMonetIcons) }
    var monetCustomColors by rememberSaveable { mutableStateOf(initialConfig.monetCustomColors) }
    var monetBackgroundColor by rememberSaveable { mutableStateOf(initialConfig.monetBackgroundColor) }
    var monetForegroundColor by rememberSaveable { mutableStateOf(initialConfig.monetForegroundColor) }
    var selectorOpen by rememberSaveable { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<IconArchiveConversionRequest?>(null) }
    val runningState = conversionState as? IconArchiveConversionState.Running
    val converting = runningState != null
    val progress = runningState?.progress
    val summary = when (val state = conversionState) {
        IconArchiveConversionState.Idle -> "转换完成后会自动保存为图标存档。"
        is IconArchiveConversionState.Running -> "正在转换 ${state.request.sourceLabel}…"
        is IconArchiveConversionState.Succeeded -> "已保存 · ${state.convertedIcons} 个图标"
        is IconArchiveConversionState.Failed -> "转换失败：${state.message}"
    }

    fun launchConversion(request: IconArchiveConversionRequest) {
        IconArchiveConversionController.start(context, request)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        pendingRequest?.let(::launchConversion)
        pendingRequest = null
    }

    fun startConversion() {
        val source = selectedSource ?: return
        if (converting) return
        val request = IconArchiveConversionRequest(
            iconPackPackage = source.packageName,
            sourceLabel = source.label,
            fallbackScaleMultiplier = scale,
            globalMonetIcons = globalMonet,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
        )
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRequest = request
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchConversion(request)
        }
    }

    LaunchedEffect(conversionState) {
        if (conversionState is IconArchiveConversionState.Succeeded) onArchiveSaved()
    }

    SettingsSecondaryPage(title = "制作图标包", rootPadding = rootPadding, onBack = onBack) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsContentPadding(pagePadding),
        ) {
            item {
                SettingsSection("转换参数") {
                    ArrowPreference(
                        title = "图标来源",
                        summary = selectedSource?.label ?: selectedSourcePackage,
                        onClick = { selectorOpen = true },
                        enabled = !converting,
                    )
                    SliderPreference(
                        title = "图标适配比例",
                        summary = "调整未映射图标的内容大小",
                        valueText = "${(scale * 100).roundToInt()}%",
                        value = scale,
                        onValueChange = { scale = it },
                        valueRange = 0.65f..1.15f,
                        steps = 9,
                        enabled = !converting,
                    )
                    SwitchPreference(
                        title = "全局 Monet（实验性）",
                        summary = "复杂渐变、半透明或动态图标转换后可能出现细节简化、色阶合并或轮廓偏差，效果取决于源图标资源。",
                        checked = globalMonet,
                        onCheckedChange = { globalMonet = it },
                        enabled = !converting,
                    )
                    if (globalMonet) {
                        SwitchPreference(
                            title = "自定义 Monet 配色",
                            summary = if (monetCustomColors) "使用固定背景色和图案色" else "跟随系统壁纸动态色",
                            checked = monetCustomColors,
                            onCheckedChange = { monetCustomColors = it },
                            enabled = !converting,
                        )
                        if (monetCustomColors) {
                            MonetColorPreference(
                                title = "背景色",
                                color = monetBackgroundColor,
                                enabled = !converting,
                                onColorChanged = { monetBackgroundColor = it },
                            )
                            MonetColorPreference(
                                title = "图案色",
                                color = monetForegroundColor,
                                enabled = !converting,
                                onColorChanged = { monetForegroundColor = it },
                            )
                        }
                    }
                }
            }
            item {
                SmallTitle("生成主题存档")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = summary,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (converting) {
                            LinearProgressIndicator(
                                progress = progress?.fraction ?: 0f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                            )
                            Text(
                                text = progress?.let { "${it.phase.creatorLabel()} · ${it.completed}/${it.total}" } ?: "正在准备…",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Button(
                            onClick = ::startConversion,
                            enabled = selectedSource != null && !converting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp),
                        ) {
                            Text(if (converting) "正在转换" else "转换并保存")
                        }
                    }
                }
            }
        }
    }

    IconSourceSelectorDialog(
        show = selectorOpen,
        sources = sources,
        selected = selectedSource,
        onDismiss = { selectorOpen = false },
        onSelected = {
            selectedSourcePackage = it.packageName
            selectorOpen = false
        },
    )
}

@Composable
private fun ConvertedArchivesPage(
    rootPadding: PaddingValues,
    archives: List<HyperOsIconArchiveConverter.ExistingArchiveInfo>,
    onBack: () -> Unit,
    onArchivesChanged: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settingsStore = remember(context) { IconSettingsStore(context) }
    val coroutineScope = rememberCoroutineScope()
    var activeActionPath by remember { mutableStateOf<String?>(null) }
    var actionSummary by remember { mutableStateOf("选择一个存档应用到系统。") }
    var deletingArchive by remember { mutableStateOf<HyperOsIconArchiveConverter.ExistingArchiveInfo?>(null) }

    fun applyArchive(archive: HyperOsIconArchiveConverter.ExistingArchiveInfo) {
        if (activeActionPath != null) return
        val sourcePackage = archive.iconPackPackage ?: run {
            actionSummary = "此存档缺少来源信息，无法应用。"
            return
        }
        coroutineScope.launch {
            activeActionPath = archive.archive.absolutePath
            actionSummary = "正在应用图标主题…"
            val result = withContext(Dispatchers.IO) { RootThemeIconInstaller.install(archive.archive) }
            actionSummary = if (result.success) {
                settingsStore.writeActiveArchive(archive.archive)
                settingsStore.write(
                    settingsStore.read().copy(
                        packageName = sourcePackage,
                        fallbackScaleMultiplier = archive.fallbackScaleMultiplier ?: settingsStore.read().fallbackScaleMultiplier,
                        globalMonetIcons = archive.globalMonetIcons,
                        monetCustomColors = archive.monetCustomColors,
                        monetBackgroundColor = archive.monetBackgroundColor,
                        monetForegroundColor = archive.monetForegroundColor,
                        conversionAllApplications = true,
                        systemThemeActive = true,
                        systemThemeAnimationBridge = true,
                    ),
                )
                if (settingsStore.pendingThemeArchivePackageUpdates().isNotEmpty()) {
                    PackageThemeArchiveUpdateScheduler.schedule(context)
                }
                "已应用 ${HyperOsIconArchiveConverter.sourceLabel(sourcePackage)}"
            } else {
                "应用失败：${result.output.ifBlank { "请检查 Root 授权" }}"
            }
            activeActionPath = null
        }
    }

    fun deleteArchive(archive: HyperOsIconArchiveConverter.ExistingArchiveInfo) {
        if (activeActionPath != null) return
        coroutineScope.launch {
            activeActionPath = archive.archive.absolutePath
            val deleted = withContext(Dispatchers.IO) {
                HyperOsIconArchiveConverter.deleteCachedArchive(context, archive.archive)
            }
            actionSummary = if (deleted) "已删除转换存档" else "删除失败"
            activeActionPath = null
            onArchivesChanged()
        }
    }

    SettingsSecondaryPage(title = "图标存档", rootPadding = rootPadding, onBack = onBack) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsContentPadding(pagePadding),
        ) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = actionSummary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
            if (archives.isEmpty()) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = "暂无转换存档",
                            style = MiuixTheme.textStyles.main,
                            modifier = Modifier.padding(18.dp),
                        )
                    }
                }
            } else {
                items(archives, key = { it.archive.absolutePath }) { archive ->
                    ConvertedArchiveCard(
                        archive = archive,
                        busy = activeActionPath == archive.archive.absolutePath,
                        onApply = { applyArchive(archive) },
                        onDelete = { deletingArchive = archive },
                    )
                }
            }
        }
    }

    deletingArchive?.let { archive ->
        ConfirmDialog(
            show = true,
            title = "删除转换存档？",
            summary = HyperOsIconArchiveConverter.sourceLabel(archive.iconPackPackage),
            confirmText = "删除",
            onDismiss = { deletingArchive = null },
            onConfirm = {
                deletingArchive = null
                deleteArchive(archive)
            },
        )
    }
}

@Composable
private fun ConvertedArchiveCard(
    archive: HyperOsIconArchiveConverter.ExistingArchiveInfo,
    busy: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val source = HyperOsIconArchiveConverter.sourceLabel(archive.iconPackPackage)
    val size = "${archive.archive.length() / 1024 / 1024} MB"
    val created = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(archive.archive.lastModified()))
    val details = buildString {
        append(size).append(" · ").append(created)
        archive.fallbackScaleMultiplier?.let { append(" · ").append((it * 100).roundToInt()).append('%') }
        append(if (archive.globalMonetIcons) " · Monet" else " · 原始色彩")
        if (archive.globalMonetIcons && archive.monetCustomColors) append(" · 自定义配色")
        if (!archive.isCurrentFormat) append(" · 需要重新转换")
    }

    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = source,
                style = MiuixTheme.textStyles.main,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = details,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "删除", onClick = onDelete, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApply, enabled = !busy && archive.iconPackPackage != null) {
                    Text(if (busy) "处理中" else "使用")
                }
            }
        }
    }
}

@Composable
private fun ThemeSettingsPage(
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

@Composable
private fun IconSourceSelectorDialog(
    show: Boolean,
    sources: List<IconSource>,
    selected: IconSource?,
    onDismiss: () -> Unit,
    onSelected: (IconSource) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(sources, query) {
        if (query.isBlank()) sources else sources.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    OverlayDialog(
        show = show,
        title = "选择图标来源",
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = "搜索名称或包名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .padding(top = 10.dp),
            ) {
                items(filtered, key = IconSource::packageName) { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(source) }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source.packageName == selected?.packageName,
                            onClick = { onSelected(source) },
                        )
                        Column(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f),
                        ) {
                            Text(source.label, style = MiuixTheme.textStyles.main)
                            Text(
                                text = source.packageName,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MonetColorPreference(
    title: String,
    color: Int,
    enabled: Boolean,
    onColorChanged: (Int) -> Unit,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { dialogOpen = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.main,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = color.toHexColor(),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(ComposeColor(color), CircleShape)
                .border(1.dp, MiuixTheme.colorScheme.outline, CircleShape),
        )
    }

    MonetColorDialog(
        show = dialogOpen,
        title = title,
        initialColor = color,
        onDismiss = { dialogOpen = false },
        onConfirm = {
            dialogOpen = false
            onColorChanged(it)
        },
    )
}

@Composable
private fun MonetColorDialog(
    show: Boolean,
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selectedColor by remember(initialColor, show) { mutableStateOf(initialColor) }
    var hexValue by remember(initialColor, show) { mutableStateOf(initialColor.toHexColor()) }
    val presets = remember {
        listOf(
            IconPackConfig.DEFAULT_MONET_BACKGROUND_COLOR,
            IconPackConfig.DEFAULT_MONET_FOREGROUND_COLOR,
            0xFFD7E3FF.toInt(),
            0xFF284777.toInt(),
            0xFFD9E7CB.toInt(),
            0xFF314F2A.toInt(),
            0xFFFFDAD6.toInt(),
            0xFF73342F.toInt(),
        )
    }

    OverlayDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            presets.chunked(4).forEach { rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    rowColors.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(ComposeColor(preset), CircleShape)
                                .border(
                                    width = if (preset == selectedColor) 3.dp else 1.dp,
                                    color = if (preset == selectedColor) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable {
                                    selectedColor = preset
                                    hexValue = preset.toHexColor()
                                },
                        )
                    }
                }
            }
            TextField(
                value = hexValue,
                onValueChange = { value ->
                    hexValue = value.take(7)
                    parseOpaqueColor(hexValue)?.let { selectedColor = it }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                label = "十六进制颜色，例如 #E8DEF8",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(selectedColor) },
                    enabled = parseOpaqueColor(hexValue) != null,
                ) {
                    Text("确定")
                }
            }
        }
    }
}

private fun Int.toHexColor(): String = "#%06X".format(this and 0xFFFFFF)

private fun parseOpaqueColor(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it !in "0123456789abcdefABCDEF" }) return null
    return (0xFF000000L or normalized.toLong(16)).toInt()
}

@Composable
private fun ConfirmDialog(
    show: Boolean,
    title: String,
    summary: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            ) {
                Text(confirmText)
            }
        }
    }
}

@Composable
internal fun SettingsSecondaryPage(
    title: String,
    rootPadding: PaddingValues,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = rootPadding.calculateTopPadding()),
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { pagePadding ->
        content(
            PaddingValues(
                top = pagePadding.calculateTopPadding(),
                bottom = rootPadding.calculateBottomPadding(),
            ),
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    SmallTitle(text = title)
    Card(
        modifier = Modifier.padding(horizontal = 12.dp),
        content = content,
    )
}

private fun settingsContentPadding(pagePadding: PaddingValues): PaddingValues = PaddingValues(
    top = pagePadding.calculateTopPadding() + 4.dp,
    bottom = pagePadding.calculateBottomPadding() + 16.dp,
)

private fun HyperOsIconArchiveConverter.ConversionPhase.creatorLabel(): String = when (this) {
    HyperOsIconArchiveConverter.ConversionPhase.PARSING -> "解析图标包"
    HyperOsIconArchiveConverter.ConversionPhase.EXPLICIT_MAPPINGS -> "转换图标映射"
    HyperOsIconArchiveConverter.ConversionPhase.FALLBACK_ACTIVITIES -> "补全未适配图标"
    HyperOsIconArchiveConverter.ConversionPhase.VALIDATING -> "校验主题存档"
    HyperOsIconArchiveConverter.ConversionPhase.COMPLETED -> "转换完成"
}
