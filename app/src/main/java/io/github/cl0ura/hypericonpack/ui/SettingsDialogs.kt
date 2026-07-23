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
internal fun IconSourceSelectorDialog(
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
internal fun MonetColorPreference(
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
internal fun MonetColorDialog(
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

internal fun Int.toHexColor(): String = "#%06X".format(this and 0xFFFFFF)

internal fun parseOpaqueColor(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it !in "0123456789abcdefABCDEF" }) return null
    return (0xFF000000L or normalized.toLong(16)).toInt()
}

@Composable
internal fun ConfirmDialog(
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
