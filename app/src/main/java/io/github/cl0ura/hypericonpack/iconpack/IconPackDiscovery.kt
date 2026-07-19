package io.github.cl0ura.hypericonpack.iconpack

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class IconPackDescriptor(
    val packageName: String,
    val label: String,
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
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun discoveryIntents() = listOf(
        Intent("org.adw.launcher.THEMES").addCategory(Intent.CATEGORY_DEFAULT),
        Intent("com.novalauncher.THEME"),
        Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
        Intent("com.gau.go.launcherex.theme"),
        Intent("ch.deletescape.lawnchair.ICONPACK"),
        Intent("com.lge.launcher2.THEME"),
    )
}
