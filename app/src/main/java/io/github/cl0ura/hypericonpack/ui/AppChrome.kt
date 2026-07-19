package io.github.cl0ura.hypericonpack.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared HyperOS-style page shell for every routed screen. */
@Composable
fun IconAppPage(
    title: String,
    rootPadding: PaddingValues,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = rootPadding.calculateTopPadding()),
        topBar = { TopAppBar(title = title) },
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
fun IconSectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun IconCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier, content = content)
}

@Composable
fun IconTonalCard(
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = containerColor),
        content = content,
    )
}

@Composable
fun IconArrowPreference(
    title: String,
    summary: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
fun IconDropdownPreference(
    title: String,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    OverlayDropdownPreference(
        title = title,
        summary = summary,
        items = items,
        selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        enabled = enabled,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
fun IconCheckboxPreference(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    CheckboxPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

@Composable
fun IconSwitchPreference(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

@Composable
fun IconBottomBar(
    selectedPage: Int,
    onPageSelected: (Int) -> Unit,
    floating: Boolean,
    glass: Boolean,
    backdrop: LayerBackdrop? = null,
) {
    val destinations: @Composable RowScope.() -> Unit = {
        NavigationBarItem(
            selected = selectedPage == 0,
            onClick = { onPageSelected(0) },
            icon = MiuixIcons.Home,
            label = "主页",
        )
        NavigationBarItem(
            selected = selectedPage == 1,
            onClick = { onPageSelected(1) },
            icon = MiuixIcons.Settings,
            label = "设置",
        )
    }

    if (!floating) {
        NavigationBar(
            color = MiuixTheme.colorScheme.surface,
            content = destinations,
        )
    } else {
        val blurActive = glass && backdrop != null
        val barShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            FloatingNavigationBar(
                modifier = if (blurActive) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = barShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
                                ),
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
                color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            ) {
                FloatingNavigationBarItem(
                    selected = selectedPage == 0,
                    onClick = { onPageSelected(0) },
                    icon = MiuixIcons.Home,
                    label = "主页",
                )
                FloatingNavigationBarItem(
                    selected = selectedPage == 1,
                    onClick = { onPageSelected(1) },
                    icon = MiuixIcons.Settings,
                    label = "设置",
                )
            }
        }
    }
}
