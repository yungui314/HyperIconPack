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
internal fun IconArchiveCreatorPage(
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
                            onClick = {
                                if (converting) {
                                    IconArchiveConversionController.cancel(context)
                                } else {
                                    startConversion()
                                }
                            },
                            enabled = selectedSource != null || converting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp),
                        ) {
                            Text(if (converting) "取消转换" else "转换并保存")
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


internal fun HyperOsIconArchiveConverter.ConversionPhase.creatorLabel(): String = when (this) {
    HyperOsIconArchiveConverter.ConversionPhase.PARSING -> "解析图标包"
    HyperOsIconArchiveConverter.ConversionPhase.EXPLICIT_MAPPINGS -> "转换图标映射"
    HyperOsIconArchiveConverter.ConversionPhase.FALLBACK_ACTIVITIES -> "补全未适配图标"
    HyperOsIconArchiveConverter.ConversionPhase.VALIDATING -> "校验主题存档"
    HyperOsIconArchiveConverter.ConversionPhase.COMPLETED -> "转换完成"
}
