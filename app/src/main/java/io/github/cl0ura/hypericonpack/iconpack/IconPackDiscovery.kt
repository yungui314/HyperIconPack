package io.github.cl0ura.hypericonpack.iconpack

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.cl0ura.hypericonpack.config.IconAppearance
import java.util.concurrent.ConcurrentHashMap

data class IconPackDescriptor(
    val packageName: String,
    val label: String,
    val supportsDarkIcons: Boolean,
)

/** Finds packs through their public launcher-theme intent contracts. */
object IconPackDiscovery {
    fun discover(context: Context): List<IconPackDescriptor> {
        val packageManager = context.packageManager
        val packages = linkedSetOf<String>()
        for (intent in discoveryIntents()) {
            @Suppress("DEPRECATION")
            val activities = packageManager.queryIntentActivities(intent, 0)
            activities.forEach { resolveInfo ->
                resolveInfo.activityInfo?.packageName?.let(packages::add)
            }
        }
        return packages.mapNotNull { packageName ->
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                IconPackDescriptor(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(appInfo).toString(),
                    supportsDarkIcons = supportsDarkIcons(
                        context = context,
                        packageName = packageName,
                        packageIdentity = packageIdentity(packageManager, packageName),
                    ),
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun supportsDarkIcons(
        context: Context,
        packageName: String,
        packageIdentity: String,
    ): Boolean = appearanceSupportCache.getOrPut(packageIdentity) {
        runCatching {
            val light = ParsedIconPack.load(context, packageName, IconAppearance.LIGHT)
                ?: return@runCatching false
            val dark = ParsedIconPack.load(context, packageName, IconAppearance.DARK)
                ?: return@runCatching false
            light.hasVisualDifferenceFrom(dark)
        }.getOrDefault(false)
    }

    private fun packageIdentity(packageManager: PackageManager, packageName: String): String {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return "$packageName:$versionCode:${info.lastUpdateTime}"
    }

    private fun discoveryIntents() = listOf(
        Intent("org.adw.launcher.THEMES").addCategory(Intent.CATEGORY_DEFAULT),
        Intent("com.novalauncher.THEME"),
        Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
        Intent("com.gau.go.launcherex.theme"),
        Intent("ch.deletescape.lawnchair.ICONPACK"),
        Intent("com.lge.launcher2.THEME"),
    )

    private val appearanceSupportCache = ConcurrentHashMap<String, Boolean>()
}
