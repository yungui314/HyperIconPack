package io.github.cl0ura.hypericonpack.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.iconpack.IconPackDescriptor
import io.github.cl0ura.hypericonpack.iconpack.IconPackDiagnostics
import io.github.cl0ura.hypericonpack.iconpack.IconPackDiscovery
import io.github.cl0ura.hypericonpack.systemtheme.HyperOsIconArchiveConverter
import io.github.cl0ura.hypericonpack.systemtheme.RootThemeIconInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ConversionSource(
    val packageName: String,
    val label: String,
)

/**
 * System-theme-only settings surface. Its preference primitives adapt to the
 * Material 3 visual system without changing any root action. No switch in
 * this screen enables a live desktop or SystemUI icon hook: the
 * selected appfilter is always converted into HyperOS's private icons archive.
 */
@Composable
fun IconPackSettingsScreen(
    rootPadding: PaddingValues,
    themePreferences: UiThemePreferences,
    onThemePreferencesChanged: (UiThemePreferences) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settingsStore = remember(context) { IconSettingsStore(context) }
    var config by remember { mutableStateOf(settingsStore.read()) }
    var iconPacks by remember { mutableStateOf(emptyList<IconPackDescriptor>()) }
    var cachedArchives by remember { mutableStateOf(emptyList<HyperOsIconArchiveConverter.ExistingArchiveInfo>()) }
    var iconPackConversionPickerOpen by remember { mutableStateOf(false) }
    var currentScopeFingerprint by remember { mutableStateOf<String?>(null) }
    var diagnosticSummary by remember { mutableStateOf("尚未检查") }
    var conversionSummary by remember { mutableStateOf("正在检查已有系统主题归档…") }
    var conversionBusy by remember { mutableStateOf(false) }
    var conversionProgress by remember { mutableStateOf<HyperOsIconArchiveConverter.ConversionProgress?>(null) }
    var conversionRunId by remember { mutableStateOf(0L) }
    var conversionPackPosition by remember { mutableStateOf(0 to 0) }
    var conversionPackLabel by remember { mutableStateOf<String?>(null) }
    var themeSummary by remember { mutableStateOf("尚未检查 Root 与主题目录访问") }
    var themeBusy by remember { mutableStateOf(false) }
    // Do not leave the only Root action with a stale placeholder.  The home
    // page already performs this exact non-mutating probe; settings must do
    // the same whenever it enters composition so its action summary reflects
    // the permission KernelSU/other Root manager has actually granted.
    var rootSummary by remember { mutableStateOf("正在检查 Root 授权…") }
    var rootBusy by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(Unit) {
        iconPacks = IconPackDiscovery.discover(context)
        cachedArchives = withContext(Dispatchers.IO) {
            HyperOsIconArchiveConverter.cachedArchiveInfos(context)
        }
    }

    LaunchedEffect(Unit) {
        val root = withContext(Dispatchers.IO) { RootAccess.check() }
        rootSummary = if (root.success) {
            "Root 已授权（uid=0）"
        } else {
            root.output.ifBlank { "Root 未授权或 Root 命令不可用" }
        }
    }

    // Conversion output lives in the app's external-files directory and
    // survives process death. Re-read it on every fresh screen/configuration
    // change so a completed conversion is never displayed as “尚未生成”.
    LaunchedEffect(config.packageName, config.fallbackScaleMultiplier, config.globalMonetIcons) {
        val archiveState = withContext(Dispatchers.IO) {
            val current = HyperOsIconArchiveConverter.cachedArchiveInfos(context)
            val scopeFingerprint = HyperOsIconArchiveConverter.applicationScopeFingerprint(context)
            val summary = existingArchiveSummary(
                context = context,
                selectedPackage = config.packageName,
                selectedScale = config.fallbackScaleMultiplier,
                globalMonetIcons = config.globalMonetIcons,
            )
            Triple(current, summary, scopeFingerprint)
        }
        cachedArchives = archiveState.first
        conversionSummary = archiveState.second
        currentScopeFingerprint = archiveState.third
    }

    val cachedCurrentScalePackages = cachedArchives.asSequence()
        .filter { archive -> kotlin.math.abs((archive.fallbackScaleMultiplier ?: -1f) - config.fallbackScaleMultiplier) < 0.001f }
        .filter { archive -> archive.globalMonetIcons == config.globalMonetIcons }
        .filter { archive -> archive.applicationScopeFingerprint == currentScopeFingerprint }
        .mapNotNull(HyperOsIconArchiveConverter.ExistingArchiveInfo::iconPackPackage)
        .toSet()
    val originalSource = ConversionSource(
        packageName = HyperOsIconArchiveConverter.ORIGINAL_ICON_PACKAGE,
        label = HyperOsIconArchiveConverter.ORIGINAL_ICON_LABEL,
    )
    val iconPackSources = iconPacks.map { descriptor -> ConversionSource(descriptor.packageName, descriptor.label) }
    val conversionSources = listOf(originalSource) + iconPackSources
    val labels = listOf("未选择图标来源") + conversionSources.map { source ->
        if (source.packageName in cachedCurrentScalePackages) "✓ ${source.label}（已缓存）" else source.label
    }
    val selectedIndex = conversionSources.indexOfFirst { it.packageName == config.packageName }
        .let { if (it < 0) 0 else it + 1 }
    val selectedSource = conversionSources.getOrNull(selectedIndex - 1)
    val selectedPackName = selectedSource?.label
    val selectedOriginalIcons = HyperOsIconArchiveConverter.isOriginalIconSource(config.packageName)
    val fallbackScaleOptions = listOf(0.75f, 0.85f, 0.95f, 1.05f)
    val fallbackScaleLabels = listOf("较小（75%）", "推荐（85%）", "饱满（95%）", "扩展（105%）")
    val selectedFallbackScaleIndex = fallbackScaleOptions.indices.minByOrNull { index ->
        kotlin.math.abs(fallbackScaleOptions[index] - config.fallbackScaleMultiplier)
    } ?: 1

    fun runRootAction(action: () -> RootAccess.Result, successSummary: String) {
        if (rootBusy) return
        coroutineScope.launch {
            rootBusy = true
            val result = withContext(Dispatchers.IO) {
                RootAccess.check().takeIf { !it.success } ?: action()
            }
            rootSummary = if (result.success) {
                successSummary
            } else {
                "操作失败：${result.output.ifBlank { "Root 未授权或受 SELinux 限制" }}"
            }
            rootBusy = false
        }
    }

    fun runThemeAction(action: () -> RootThemeIconInstaller.Result, onSuccess: () -> Unit = {}) {
        if (themeBusy) return
        coroutineScope.launch {
            themeBusy = true
            val result = withContext(Dispatchers.IO) { action() }
            themeSummary = if (result.success) {
                result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "系统主题操作完成"
            } else {
                "操作未执行：${result.output.ifBlank { "Root 未授权或主题目录不可访问" }}"
            }
            if (result.success) onSuccess()
            themeBusy = false
        }
    }

    fun startConversion(
        force: Boolean,
        requested: List<ConversionSource>,
        globalMonetIconsOverride: Boolean? = null,
        selectSingleSource: Boolean = false,
    ) {
        if (conversionBusy) return
        if (requested.isEmpty()) {
            conversionSummary = "请先选择一个需要转换的图标来源"
            return
        }
        conversionSummary = "正在准备完整 appfilter、桌面入口与全部应用包级资源…"
        coroutineScope.launch {
            conversionBusy = true
            conversionProgress = null
            conversionRunId += 1L
            val runId = conversionRunId
            val scale = config.fallbackScaleMultiplier
            val globalMonetIcons = globalMonetIconsOverride ?: config.globalMonetIcons
            val nextConfig = if (selectSingleSource && requested.size == 1) {
                config.copy(
                    packageName = requested.single().packageName,
                    globalMonetIcons = globalMonetIcons,
                    conversionAllApplications = true,
                )
            } else {
                config.copy(conversionAllApplications = true)
            }
            if (nextConfig != config) {
                config = settingsStore.write(nextConfig)
            }
            val output = mutableListOf<String>()
            var converted = 0
            var reused = 0
            var failures = 0

            requested.forEachIndexed { index, source ->
                conversionPackPosition = index + 1 to requested.size
                conversionPackLabel = source.label
                val existing = withContext(Dispatchers.IO) {
                    HyperOsIconArchiveConverter.existingArchive(
                        context = context,
                        iconPackPackage = source.packageName,
                        fallbackScaleMultiplier = scale,
                        globalMonetIcons = globalMonetIcons,
                    )
                }
                if (!force && existing != null) {
                    reused++
                    output += "${source.label} 已有缓存"
                    return@forEachIndexed
                }
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        HyperOsIconArchiveConverter.convert(
                            context = context,
                            iconPackPackage = source.packageName,
                            fallbackScaleMultiplier = scale,
                            globalMonetIcons = globalMonetIcons,
                            includeInstalledAppFallbacks = true,
                            onProgress = { progress ->
                                mainHandler.post {
                                    if (conversionBusy && conversionRunId == runId) {
                                        conversionProgress = progress
                                    }
                                }
                            },
                        )
                    }
                }
                result.onSuccess { archive ->
                    converted++
                    output += buildString {
                        append(source.label)
                        append("：映射 ").append(archive.convertedExplicitMappings)
                        append("，包级图标 ").append(archive.convertedPackageDefaults)
                        if (archive.nativeMonochromeIcons > 0) {
                            append("，原生单色图层 ").append(archive.nativeMonochromeIcons)
                        }
                        if (archive.skippedMappings > 0) append("，跳过 ").append(archive.skippedMappings)
                    }
                }.onFailure { throwable ->
                    failures++
                    output += "${source.label}：${throwable.javaClass.simpleName}"
                }
            }
            cachedArchives = withContext(Dispatchers.IO) {
                HyperOsIconArchiveConverter.cachedArchiveInfos(context)
            }
            currentScopeFingerprint = withContext(Dispatchers.IO) {
                HyperOsIconArchiveConverter.applicationScopeFingerprint(context)
            }
            config = settingsStore.touch()
            conversionSummary = buildString {
                append("缓存完成：新转换 ").append(converted).append(" 个")
                if (reused > 0) append("，复用 ").append(reused).append(" 个")
                if (failures > 0) append("，失败 ").append(failures).append(" 个")
                if (output.isNotEmpty()) append("。 ").append(output.joinToString("；"))
            }
            conversionPackLabel = null
            conversionBusy = false
        }
    }

    if (iconPackConversionPickerOpen) {
        IconPackConversionPickerScreen(
            rootPadding = rootPadding,
            sources = iconPackSources,
            cachedPackages = cachedCurrentScalePackages,
            onConvert = { source ->
                iconPackConversionPickerOpen = false
                startConversion(
                    force = false,
                    requested = listOf(source),
                )
            },
            onClose = { iconPackConversionPickerOpen = false },
        )
        return
    }

    IconAppPage(title = "设置", rootPadding = rootPadding) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = pagePadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                IconSectionTitle("外观与色彩")
                IconCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                    IconDropdownPreference(
                        title = "颜色模式",
                        summary = themePreferences.colorMode.label,
                        items = AppColorMode.entries.map(AppColorMode::label),
                        selectedIndex = AppColorMode.entries.indexOf(themePreferences.colorMode),
                        onSelectedIndexChange = { index ->
                            onThemePreferencesChanged(
                                themePreferences.copy(colorMode = AppColorMode.entries[index]),
                            )
                        },
                    )
                }
            }
            item {
                IconSectionTitle("图标来源与视觉校准")
                IconCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                    IconDropdownPreference(
                        title = "选择图标来源",
                        summary = selectedPackName ?: "未选择",
                        items = labels,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { index ->
                            val source = conversionSources.getOrNull(index - 1)
                            config = settingsStore.write(
                                config.copy(
                                    packageName = source?.packageName,
                                    globalMonetIcons = if (HyperOsIconArchiveConverter.isOriginalIconSource(source?.packageName)) {
                                        true
                                    } else {
                                        config.globalMonetIcons
                                    },
                                    conversionAllApplications = true,
                                ),
                            )
                        },
                    )
                    IconArrowPreference(
                        title = "重新扫描已安装图标包",
                        summary = "安装或更新图标包后点击此项",
                        onClick = {
                            iconPacks = IconPackDiscovery.discover(context)
                            cachedArchives = HyperOsIconArchiveConverter.cachedArchiveInfos(context)
                        },
                    )
                    IconArrowPreference(
                        title = "检查所选图标包",
                        summary = if (selectedOriginalIcons) "本机原始图标模式不需要 appfilter 检查" else diagnosticSummary,
                        onClick = { diagnosticSummary = IconPackDiagnostics.inspect(context, config.packageName) },
                        enabled = config.packageName != null && !selectedOriginalIcons,
                    )
                    IconDropdownPreference(
                        title = "未适配图标内容比例",
                        summary = "用于所有没有有效 appfilter 映射的已安装应用。圆框内留白明显时选 85% 或 95%；修改后须重新转换主题归档。",
                        items = fallbackScaleLabels,
                        selectedIndex = selectedFallbackScaleIndex,
                        onSelectedIndexChange = { index ->
                            config = settingsStore.write(
                                config.copy(fallbackScaleMultiplier = fallbackScaleOptions[index]),
                            )
                        },
                        enabled = config.packageName != null && !selectedOriginalIcons,
                    )
                    IconSwitchPreference(
                        title = "实验性 · 全局 Monet 图标主题",
                        summary = if (selectedOriginalIcons) {
                            "本机原始图标模式固定启用：直接读取系统原始应用图标，统一裁圆并按当前壁纸 Accent 1 色阶做 Monet。"
                        } else if (config.globalMonetIcons) {
                            "已启用：所有 appfilter 图标与未适配应用都会统一裁圆，并按当前壁纸 Accent 1 色阶保留明暗细节。不会进行不可靠的边缘抠图；切换后须重新转换。"
                        } else {
                            "关闭：保留图标包原始彩色资源，未适配图标使用图标包提供的 iconback / iconmask 样式。开启后须重新转换。"
                        },
                        checked = selectedOriginalIcons || config.globalMonetIcons,
                        onCheckedChange = { enabled ->
                            config = settingsStore.write(config.copy(globalMonetIcons = enabled))
                        },
                        enabled = config.packageName != null && !selectedOriginalIcons && !conversionBusy,
                    )
                }
            }

            item {
                IconSectionTitle("图标包缓存")
                IconCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                    IconArrowPreference(
                        title = "转换全部已安装图标包",
                        summary = "按当前手机全部已安装应用依次生成每个图标包的缓存。",
                        onClick = {
                            startConversion(
                                force = false,
                                requested = iconPackSources,
                            )
                        },
                        enabled = !conversionBusy && iconPacks.isNotEmpty(),
                    )
                    IconArrowPreference(
                        title = "选择单个图标包转换",
                        summary = "进入搜索列表，点选某个图标包后立即按全量应用范围生成缓存。",
                        onClick = { iconPackConversionPickerOpen = true },
                        enabled = !conversionBusy && iconPacks.isNotEmpty(),
                    )
                }
            }

            item {
                IconSectionTitle("HyperOS 应用图标主题")
                IconCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                    if (conversionBusy) {
                        LinearProgressIndicator(
                            progress = { conversionProgress?.fraction ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    IconArrowPreference(
                        title = if (conversionBusy) "正在转换 ${conversionPackLabel ?: "图标来源"}" else "转换当前图标来源",
                        summary = if (conversionBusy) {
                            conversionProgress?.let { progress ->
                                "${conversionPackPosition.first}/${conversionPackPosition.second} · ${progress.phase.label()}：${progress.completed}/${progress.total}（${(progress.fraction * 100).toInt()}%）"
                            } ?: "正在读取 appfilter.xml、桌面入口与应用包列表…"
                        } else {
                            "$conversionSummary 相同图标来源、比例和 Monet 状态会直接复用缓存。"
                        },
                        onClick = {
                            startConversion(
                                force = false,
                                requested = listOfNotNull(selectedSource),
                            )
                        },
                        enabled = !conversionBusy && config.packageName != null,
                    )
                    IconArrowPreference(
                        title = "强制重新转换当前图标来源",
                        summary = "图标包更新、壁纸变化后需要更新 Monet 色阶，或想刷新本机原始图标主题时使用。",
                        onClick = {
                            startConversion(
                                force = true,
                                requested = listOfNotNull(selectedSource),
                            )
                        },
                        enabled = !conversionBusy && config.packageName != null,
                    )
                    IconArrowPreference(
                        title = "转换本机原始图标 Monet 主题",
                        summary = "不使用图标包，直接把当前手机全部原始应用图标统一做 Monet 后生成 HyperOS 主题归档。",
                        onClick = {
                            startConversion(
                                force = true,
                                requested = listOf(originalSource),
                                globalMonetIconsOverride = true,
                                selectSingleSource = true,
                            )
                        },
                        enabled = !conversionBusy,
                    )
                    IconArrowPreference(
                        title = "检查系统主题 Root 访问",
                        summary = if (themeBusy) "正在检查 Root 和 SELinux 上下文…" else themeSummary,
                        onClick = { runThemeAction(RootThemeIconInstaller::preflight) },
                    )
                    IconArrowPreference(
                        title = "应用当前图标来源的缓存主题",
                        summary = "将缓存 ZIP 原子安装到 /data/system/theme/icons；模块会让桌面更新 HyperOS 主题修订号并刷新图标缓存，无需手动重启桌面。",
                        onClick = {
                            runThemeAction(
                                action = {
                                    HyperOsIconArchiveConverter.existingArchive(
                                        context = context,
                                        iconPackPackage = config.packageName,
                                        fallbackScaleMultiplier = config.fallbackScaleMultiplier,
                                        globalMonetIcons = config.globalMonetIcons,
                                    )?.let(RootThemeIconInstaller::install)
                                        ?: RootThemeIconInstaller.Result(false, "当前图标来源尚未缓存，请先转换。")
                                },
                                onSuccess = {
                                    config = settingsStore.write(
                                        config.copy(
                                            systemThemeActive = true,
                                            systemThemeAnimationBridge = true,
                                        ),
                                    )
                                },
                            )
                        },
                    )
                    IconArrowPreference(
                        title = "恢复应用前的系统图标主题",
                        summary = "仅恢复本模块受管的原始 icons；哈希保护会阻止覆盖之后由主题商店更换的主题。",
                        onClick = {
                            runThemeAction(
                                action = RootThemeIconInstaller::restore,
                                onSuccess = {
                                    config = settingsStore.write(
                                        config.copy(systemThemeActive = false, systemThemeAnimationBridge = false),
                                    )
                                },
                            )
                        },
                    )
                }
            }

            item {
                IconSectionTitle("Root 快捷操作")
                IconCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                    IconArrowPreference(
                        title = "一键重启设备",
                        summary = if (rootBusy) "正在请求重启…" else "${rootSummary}。立即重启 Android，用于完整刷新系统主题和图标缓存。",
                        onClick = { runRootAction(RootAccess::rebootSystem, "已请求重启设备") },
                    )
                }
            }
        }
    }
}

private fun existingArchiveSummary(
    context: android.content.Context,
    selectedPackage: String?,
    selectedScale: Float,
    globalMonetIcons: Boolean,
): String {
    if (selectedPackage == null) return "请选择图标来源"
    val archive = HyperOsIconArchiveConverter.existingArchiveInfo(
        context = context,
        iconPackPackage = selectedPackage,
        fallbackScaleMultiplier = selectedScale,
        globalMonetIcons = globalMonetIcons,
    ) ?: return "当前图标来源尚未缓存主题归档"
    val convertedPackage = archive.iconPackPackage
    val convertedScale = archive.fallbackScaleMultiplier
    if (convertedPackage == null || convertedScale == null) {
        return "已检测到旧格式主题归档（${archive.archive.length() / 1024 / 1024} MB）；建议重新转换一次以记录状态。"
    }
    val scaleText = "${(convertedScale * 100).toInt()}%"
    val sourceText = HyperOsIconArchiveConverter.sourceLabel(convertedPackage)
    return if (convertedPackage == selectedPackage && kotlin.math.abs(convertedScale - selectedScale) < 0.001f) {
        "已缓存当前图标来源主题归档（$sourceText · $scaleText${if (globalMonetIcons) " · 全局 Monet" else ""}）。"
    } else {
        "已找到 $sourceText（$scaleText）的主题归档。"
    }
}

@Composable
private fun IconPackConversionPickerScreen(
    rootPadding: PaddingValues,
    sources: List<ConversionSource>,
    cachedPackages: Set<String>,
    onConvert: (ConversionSource) -> Unit,
    onClose: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredPacks = remember(sources, searchQuery) {
        if (searchQuery.isBlank()) sources else sources.filter { source ->
            source.label.contains(searchQuery, ignoreCase = true) ||
                source.packageName.contains(searchQuery, ignoreCase = true)
        }
    }
    BackHandler(onBack = onClose)
    IconAppPage(title = "选择图标包", rootPadding = rootPadding) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = pagePadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                IconCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("搜索图标包名称或包名") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    IconArrowPreference(
                        title = "返回设置",
                        summary = "已安装图标包 ${sources.size} 个，当前结果 ${filteredPacks.size} 个。",
                        onClick = onClose,
                    )
                }
                IconSectionTitle("图标包列表")
            }
            items(filteredPacks, key = ConversionSource::packageName) { source ->
                IconCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    IconArrowPreference(
                        title = source.label,
                        summary = if (source.packageName in cachedPackages) {
                            "${source.packageName} · 全量缓存已存在，点此复用"
                        } else {
                            "${source.packageName} · 点此按全量应用转换"
                        },
                        onClick = { onConvert(source) },
                    )
                }
            }
        }
    }
}

private fun HyperOsIconArchiveConverter.ConversionPhase.label(): String = when (this) {
    HyperOsIconArchiveConverter.ConversionPhase.PARSING -> "解析图标包"
    HyperOsIconArchiveConverter.ConversionPhase.EXPLICIT_MAPPINGS -> "转换明确映射"
    HyperOsIconArchiveConverter.ConversionPhase.FALLBACK_ACTIVITIES -> "生成桌面入口与全部应用包级资源"
    HyperOsIconArchiveConverter.ConversionPhase.VALIDATING -> "校验主题归档"
    HyperOsIconArchiveConverter.ConversionPhase.COMPLETED -> "转换完成"
}
