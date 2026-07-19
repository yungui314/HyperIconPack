package io.github.cl0ura.hypericonpack.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cl0ura.hypericonpack.BuildConfig
import io.github.cl0ura.hypericonpack.config.IconPackConfig
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.systemtheme.HyperOsIconArchiveConverter
import io.github.cl0ura.hypericonpack.systemtheme.RootThemeIconInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class HomeDestination {
    OVERVIEW,
    LOGS,
    ABOUT,
}

@Composable
fun ModuleHomeScreen(rootPadding: PaddingValues) {
    var destination by rememberSaveable { mutableStateOf(HomeDestination.OVERVIEW) }
    BackHandler(enabled = destination != HomeDestination.OVERVIEW) {
        destination = HomeDestination.OVERVIEW
    }

    when (destination) {
        HomeDestination.OVERVIEW -> ModuleHomeOverview(
            rootPadding = rootPadding,
            onLogs = { destination = HomeDestination.LOGS },
            onAbout = { destination = HomeDestination.ABOUT },
        )

        HomeDestination.LOGS -> ModuleLogsPage(
            rootPadding = rootPadding,
            onBack = { destination = HomeDestination.OVERVIEW },
        )

        HomeDestination.ABOUT -> ModuleAboutPage(
            rootPadding = rootPadding,
            onBack = { destination = HomeDestination.OVERVIEW },
        )
    }
}

@Composable
private fun ModuleHomeOverview(
    rootPadding: PaddingValues,
    onLogs: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settingsStore = remember(context) { IconSettingsStore(context) }
    var refreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(ModuleStatusSnapshot.loading()) }

    LaunchedEffect(refreshGeneration) {
        snapshot = withContext(Dispatchers.IO) {
            val config = settingsStore.read()
            val archive = HyperOsIconArchiveConverter.existingArchiveInfo(
                context = context,
                iconPackPackage = config.packageName,
                fallbackScaleMultiplier = config.fallbackScaleMultiplier,
                globalMonetIcons = config.globalMonetIcons,
                monetCustomColors = config.monetCustomColors,
                monetBackgroundColor = config.monetBackgroundColor,
                monetForegroundColor = config.monetForegroundColor,
            )
            val root = RootAccess.check()
            val theme = if (root.success) RootThemeIconInstaller.status() else null
            val sourceName = HyperOsIconArchiveConverter.sourceLabel(
                context,
                archive?.iconPackPackage ?: config.packageName,
            )
            ModuleStatusSnapshot(config, archive, root, theme, sourceName)
        }
    }

    IconAppPage(title = "Hyper Icon Pack", rootPadding = rootPadding) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pagePadding.calculateTopPadding() + 8.dp,
                bottom = pagePadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RuntimeStatusCard(snapshot) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    HomeStatusRow("Root", snapshot.rootLabel, snapshot.rootReady)
                    HomeStatusRow("图标来源", snapshot.sourceLabel, snapshot.archiveReady)
                    HomeStatusRow("系统主题", snapshot.themeLabel, snapshot.themeActive)
                    ArrowPreference(
                        title = "刷新状态",
                        onClick = { refreshGeneration++ },
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "日志",
                        summary = "查看模块与系统主题操作记录",
                        onClick = onLogs,
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "关于",
                        summary = "版本、项目地址与贡献者",
                        onClick = onAbout,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(snapshot: ModuleStatusSnapshot) {
    val active = snapshot.themeActive && snapshot.rootReady
    val containerColor = if (active) {
        MiuixTheme.colorScheme.primaryContainer
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (active) {
        MiuixTheme.colorScheme.onPrimaryContainer
    } else {
        MiuixTheme.colorScheme.onSurfaceContainerHigh
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (active) MiuixIcons.Ok else MiuixIcons.Info,
                    contentDescription = null,
                    tint = if (active) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceContainerHighest,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (active) "模块工作中" else snapshot.primaryStatus,
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = snapshot.primarySummary,
                    style = MiuixTheme.textStyles.body2,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = contentColor.copy(alpha = 0.62f),
                )
            }
        }
    }
}

@Composable
private fun HomeStatusRow(title: String, value: String, ready: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.main,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = if (ready) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class ModuleStatusSnapshot(
    val config: IconPackConfig,
    val archive: HyperOsIconArchiveConverter.ExistingArchiveInfo?,
    val root: RootAccess.Result,
    val theme: RootThemeIconInstaller.Result?,
    val sourceName: String,
) {
    val rootReady: Boolean get() = root.success
    val archiveReady: Boolean get() = archive != null
    val themeActive: Boolean get() = theme?.success == true

    val rootLabel: String get() = if (rootReady) "已授权" else "未授权"

    val sourceLabel: String
        get() = archive?.let {
            buildString {
                append(sourceName)
                if (it.globalMonetIcons) append(" · Monet")
            }
        } ?: "尚未生成"

    val themeLabel: String
        get() = when {
            themeActive -> "已应用"
            theme?.output?.contains("HYPER_ICONPACK_THEME_REPLACED") == true -> "已被替换"
            config.systemThemeActive -> "等待恢复"
            else -> "未应用"
        }

    val primaryStatus: String
        get() = when {
            !rootReady -> "Root 未授权"
            !archiveReady -> "尚未生成图标主题"
            else -> "图标主题未应用"
        }

    val primarySummary: String
        get() = when {
            themeActive && archiveReady -> "${sourceLabel} 已应用到 HyperOS。"
            themeActive -> "系统图标主题已应用。"
            !rootReady -> "授权 Root 后可应用和恢复系统图标。"
            archiveReady -> "前往设置选择已生成的图标存档。"
            else -> "前往设置制作一个图标存档。"
        }

    companion object {
        fun loading() = ModuleStatusSnapshot(
            config = IconPackConfig.disabled(),
            archive = null,
            root = RootAccess.Result(false, "正在检查"),
            theme = null,
            sourceName = "正在读取",
        )
    }
}
