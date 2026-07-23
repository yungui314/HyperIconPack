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

@Composable
internal fun ConvertedArchivesPage(
    rootPadding: PaddingValues,
    archives: List<IconArchiveInfo>,
    sourceLabels: Map<String, String>,
    onBack: () -> Unit,
    onArchivesChanged: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settingsStore = remember(context) { IconSettingsStore(context) }
    val coroutineScope = rememberCoroutineScope()
    var activeActionPath by remember { mutableStateOf<String?>(null) }
    var actionSummary by remember { mutableStateOf("选择一个存档应用到系统。") }
    var deletingArchive by remember { mutableStateOf<IconArchiveInfo?>(null) }

    fun applyArchive(archive: IconArchiveInfo) {
        if (activeActionPath != null) return
        if (!archive.isCurrentFormat) {
            actionSummary = "该存档使用旧版主题资源格式，请在“制作图标包”中重新转换。"
            return
        }
        val sourcePackage = archive.iconPackPackage ?: run {
            actionSummary = "此存档缺少来源信息，无法应用。"
            return
        }
        coroutineScope.launch {
            activeActionPath = archive.archive.absolutePath
            actionSummary = "正在应用图标主题…"
            val installAndRefresh = withContext(Dispatchers.IO) {
                ThemeArchiveMutationGate.withLock {
                    val install = RootThemeIconInstaller.install(archive.archive)
                    val refresh = if (install.success) RootAccess.refreshIconSurfaces(context) else null
                    install to refresh
                }
            }
            val result = installAndRefresh.first
            actionSummary = if (result.success) {
                val refresh = installAndRefresh.second
                val sourceName = sourceLabels[sourcePackage]
                    ?: HyperOsIconArchiveConverter.sourceLabel(sourcePackage)
                AppLog.info(
                    context,
                    "Applied archive ${archive.archive.name} from $sourceName; " +
                        "surfacesRefreshed=${refresh?.success == true}; ${refresh?.output.orEmpty()}",
                )
                if (refresh?.success == true) {
                    val current = settingsStore.read()
                    settingsStore.writeActiveArchive(archive.archive)
                    settingsStore.write(
                        current.copy(
                            packageName = sourcePackage,
                            fallbackScaleMultiplier = archive.fallbackScaleMultiplier
                                ?: current.fallbackScaleMultiplier,
                            globalMonetIcons = archive.globalMonetIcons,
                            monetCustomColors = archive.monetCustomColors,
                            monetBackgroundColor = archive.monetBackgroundColor,
                            monetForegroundColor = archive.monetForegroundColor,
                            conversionAllApplications = true,
                            systemThemeActive = true,
                        ),
                    )
                    if (settingsStore.pendingThemeArchivePackageUpdates().isNotEmpty()) {
                        PackageThemeArchiveUpdateScheduler.schedule(context)
                    }
                    "已应用 $sourceName，桌面与系统界面已刷新"
                } else {
                    "主题文件已写入，但系统界面刷新失败；请再次点击此存档重试"
                }
            } else {
                AppLog.warning(context, "Archive apply failed: ${archive.archive.name}; ${result.output}")
                "应用失败：${result.output.ifBlank { "请检查 Root 授权" }}"
            }
            activeActionPath = null
        }
    }

    fun deleteArchive(archive: IconArchiveInfo) {
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
                        sourceLabel = archive.iconPackPackage?.let(sourceLabels::get)
                            ?: HyperOsIconArchiveConverter.sourceLabel(archive.iconPackPackage),
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
            summary = archive.iconPackPackage?.let(sourceLabels::get)
                ?: HyperOsIconArchiveConverter.sourceLabel(archive.iconPackPackage),
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
internal fun ConvertedArchiveCard(
    archive: IconArchiveInfo,
    sourceLabel: String,
    busy: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val source = sourceLabel
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
                Button(
                    onClick = onApply,
                    enabled = !busy && archive.iconPackPackage != null && archive.isCurrentFormat,
                ) {
                    Text(
                        when {
                            !archive.isCurrentFormat -> "请重新转换"
                            busy -> "处理中"
                            else -> "使用"
                        },
                    )
                }
            }
        }
    }
}
