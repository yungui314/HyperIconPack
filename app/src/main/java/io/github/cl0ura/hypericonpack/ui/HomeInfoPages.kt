package io.github.cl0ura.hypericonpack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cl0ura.hypericonpack.BuildConfig
import io.github.cl0ura.hypericonpack.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModuleLogsPage(
    rootPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var refreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var logs by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<ModuleLogEntry>()) }
    var status by remember { mutableStateOf("正在读取日志…") }

    LaunchedEffect(refreshGeneration) {
        loading = true
        val result = withContext(Dispatchers.IO) { RootAccess.readModuleLogs() }
        logs = result.output
        entries = if (result.success) parseModuleLogs(result.output) else emptyList()
        status = when {
            !result.success -> "读取失败：${result.output.ifBlank { "请检查 Root 授权" }}"
            entries.isEmpty() -> "暂时没有本模块的近期日志。"
            else -> "已读取最近 ${entries.size} 条相关记录。"
        }
        loading = false
    }

    SettingsSecondaryPage(title = "日志", rootPadding = rootPadding, onBack = onBack) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = homeDetailPadding(pagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = status,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = "Android 使用固定大小的环形日志缓冲区，旧记录会自动覆盖，不会持续占用存储空间。",
                            style = MiuixTheme.textStyles.footnote1,
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
                            TextButton(
                                text = "复制全部",
                                enabled = logs.isNotBlank() && !loading,
                                onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Hyper Icon Pack 日志", logs))
                                    status = "日志已复制到剪贴板。"
                                },
                            )
                            Button(
                                onClick = { refreshGeneration++ },
                                enabled = !loading,
                                modifier = Modifier.padding(start = 8.dp),
                            ) {
                                Text(if (loading) "读取中" else "刷新")
                            }
                        }
                    }
                }
            }
            items(entries, key = { it.id }) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    LogEntryContent(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryContent(entry: ModuleLogEntry) {
    val levelColor = when (entry.level) {
        "F", "E" -> Color(0xFFD93025)
        "W" -> Color(0xFFE08A00)
        "I" -> Color(0xFF2E7D55)
        else -> MiuixTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(levelColor.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.level,
                style = MiuixTheme.textStyles.footnote1,
                color = levelColor,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.tag,
                    style = MiuixTheme.textStyles.main,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entry.time,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = "PID ${entry.processId} · TID ${entry.threadId}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SelectionContainer {
                Text(
                    text = entry.message,
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = if (entry.message.contains('\n')) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
    }
}

private data class ModuleLogEntry(
    val id: Int,
    val time: String,
    val processId: String,
    val threadId: String,
    val level: String,
    val tag: String,
    val message: String,
)

private fun parseModuleLogs(raw: String): List<ModuleLogEntry> {
    val entries = mutableListOf<ModuleLogEntry>()
    raw.lineSequence().forEach { line ->
        val match = THREADTIME_LOG_PATTERN.matchEntire(line)
        if (match != null) {
            entries += ModuleLogEntry(
                id = entries.size,
                time = "${match.groupValues[1]} ${match.groupValues[2]}",
                processId = match.groupValues[3],
                threadId = match.groupValues[4],
                level = match.groupValues[5],
                tag = match.groupValues[6].trim(),
                message = match.groupValues[7].trim(),
            )
        } else if (line.isNotBlank() && entries.isNotEmpty()) {
            val lastIndex = entries.lastIndex
            entries[lastIndex] = entries[lastIndex].copy(
                message = entries[lastIndex].message + "\n" + line.trimEnd(),
            )
        }
    }
    return entries.asReversed().mapIndexed { index, entry -> entry.copy(id = index) }
}

private val THREADTIME_LOG_PATTERN = Regex(
    "^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" +
        "(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)$",
)

@Composable
internal fun ModuleAboutPage(
    rootPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    SettingsSecondaryPage(title = "关于", rootPadding = rootPadding, onBack = onBack) { pagePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = homeDetailPadding(pagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .background(
                                color = colorResource(R.color.launcher_icon_background),
                                shape = RoundedCornerShape(28.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = "Hyper Icon Pack 图标",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text = "Hyper Icon Pack",
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "项目地址",
                        summary = PROJECT_URL.removePrefix("https://"),
                        onClick = { openUrl(PROJECT_URL) },
                    )
                    ArrowPreference(
                        title = "贡献者",
                        summary = "@$CONTRIBUTOR",
                        onClick = { openUrl(CONTRIBUTOR_URL) },
                    )
                }
            }
        }
    }
}

private fun homeDetailPadding(pagePadding: PaddingValues): PaddingValues = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = pagePadding.calculateTopPadding() + 8.dp,
    bottom = pagePadding.calculateBottomPadding() + 16.dp,
)

private const val PROJECT_URL = "https://github.com/yungui314/HyperIconPack"
private const val CONTRIBUTOR = "yungui314"
private const val CONTRIBUTOR_URL = "https://github.com/yungui314"
