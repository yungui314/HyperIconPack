package io.github.cl0ura.hypericonpack.systemtheme

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Immutable package snapshot shared by full, incremental and calendar conversion. */
internal data class InstalledAppCatalog(
    val applications: List<ApplicationInfo>,
    val launchableActivities: List<ActivityInfo>,
) {
    val packageNames: Set<String> = applications.mapTo(LinkedHashSet(), ApplicationInfo::packageName)
    val applicationsByPackage: Map<String, ApplicationInfo> =
        applications.associateBy(ApplicationInfo::packageName)
    val launchableActivitiesByComponent: Map<ComponentName, ActivityInfo> = launchableActivities
        .mapNotNull { activity -> componentName(activity)?.let { component -> component to activity } }
        .toMap()
    val launchableComponents: List<ComponentName> = launchableActivities.mapNotNull(::componentName)

    companion object {
        fun load(context: Context): InstalledAppCatalog {
            val packageManager = context.packageManager
            val launchableActivities = queryLaunchableActivities(packageManager)
            val applicationsByPackage = LinkedHashMap<String, ApplicationInfo>()

            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                .forEach { application -> applicationsByPackage.putIfAbsent(application.packageName, application) }

            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)
                .mapNotNull { packageInfo -> packageInfo.applicationInfo }
                .forEach { application -> applicationsByPackage.putIfAbsent(application.packageName, application) }

            // Package visibility implementations can return an incomplete bulk
            // application list while still exposing every MAIN/LAUNCHER result.
            // Always merge those ActivityInfo records so desktop coverage never
            // silently collapses to the converter's own package.
            launchableActivities.forEach { activity ->
                applicationsByPackage.putIfAbsent(activity.packageName, activity.applicationInfo)
            }

            val applications = applicationsByPackage.values
                .filter { application -> application.packageName.isNotBlank() }
                .sortedBy(ApplicationInfo::packageName)
            val launchablePackageCount = launchableActivities
                .mapTo(HashSet(), ActivityInfo::packageName)
                .size
            require(applications.size >= launchablePackageCount) {
                "已安装应用枚举不完整：应用=${applications.size}，桌面包=$launchablePackageCount"
            }
            return InstalledAppCatalog(
                applications = applications,
                launchableActivities = launchableActivities,
            )
        }

        fun componentName(activityInfo: ActivityInfo): ComponentName? {
            val packageName = activityInfo.packageName.takeIf(String::isNotBlank) ?: return null
            val rawClassName = activityInfo.name.takeIf(String::isNotBlank) ?: return null
            val className = if (rawClassName.startsWith('.')) packageName + rawClassName else rawClassName
            return ComponentName(packageName, className)
        }

        private fun queryLaunchableActivities(packageManager: PackageManager): List<ActivityInfo> {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            return packageManager.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { resolveInfo -> resolveInfo.activityInfo }
                .distinctBy { activity -> "${activity.packageName}/${activity.name}" }
                .sortedWith(compareBy(ActivityInfo::packageName, ActivityInfo::name))
        }
    }
}
