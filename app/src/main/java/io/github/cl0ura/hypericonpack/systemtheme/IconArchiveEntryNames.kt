package io.github.cl0ura.hypericonpack.systemtheme

import android.content.ComponentName

/**
 * Pure HyperOS theme ZIP entry-name rules shared by full conversion and
 * incremental package add/remove. Kept free of Android Context so ownership
 * matching can be unit-tested without a device.
 */
internal object IconArchiveEntryNames {
    const val TARGET_DENSITY_DIRECTORY = "res/drawable-xxhdpi"
    const val DYNAMIC_ROOT_DIRECTORY = "animating_icons"
    const val ICON_MASK_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_mask.png"
    const val ICON_BACKGROUND_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_background.png"
    const val ICON_PATTERN_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_pattern.png"
    const val ICON_BORDER_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_border.png"

    val NATIVE_FALLBACK_ENTRIES: Set<String> = setOf(
        ICON_MASK_ENTRY,
        ICON_BACKGROUND_ENTRY,
        ICON_PATTERN_ENTRY,
        ICON_BORDER_ENTRY,
    )

    fun isNativeFallbackEntry(entryName: String): Boolean = entryName in NATIVE_FALLBACK_ENTRIES

    fun archiveEntryName(component: ComponentName): String {
        // ZIP entry names are case-sensitive. HyperOS passes the installed
        // package string through to IconCustomizer, and packages such as
        // com.MobileTicket / com.miHoYo.* retain capital letters.
        val packageName = component.packageName
        val className = component.className
        val iconName = if (className.startsWith(packageName)) {
            className
        } else {
            "$packageName#$className"
        }
        return "$TARGET_DENSITY_DIRECTORY/$iconName.png"
    }

    fun packageArchiveEntryName(packageName: String): String =
        "$TARGET_DENSITY_DIRECTORY/$packageName.png"

    fun entryBelongsToPackage(entryName: String, packageName: String): Boolean {
        if (isNativeFallbackEntry(entryName)) return false
        val iconPrefix = "$TARGET_DENSITY_DIRECTORY/"
        if (entryName.startsWith(iconPrefix)) {
            val leaf = entryName.removePrefix(iconPrefix)
            val base = if (leaf.endsWith(".png")) leaf.removeSuffix(".png") else leaf
            return base == packageName ||
                base.startsWith("$packageName.") ||
                base.startsWith("$packageName#")
        }
        val dynamicPrefix = "$DYNAMIC_ROOT_DIRECTORY/"
        if (entryName.startsWith(dynamicPrefix)) {
            val root = entryName.removePrefix(dynamicPrefix).substringBefore('/')
            return root == packageName ||
                root.startsWith("$packageName.") ||
                root.startsWith("$packageName#")
        }
        return false
    }

    fun entryBelongsToAnyPackage(
        entryName: String,
        packageNames: Collection<String>,
        preservedEntryNames: Set<String> = emptySet(),
        protectedPackageNames: Set<String> = emptySet(),
    ): Boolean {
        if (entryName in preservedEntryNames) return false
        val requested = packageNames.toSet()
        if (requested.none { packageName -> entryBelongsToPackage(entryName, packageName) }) return false
        return protectedPackageNames
            .asSequence()
            .filterNot(requested::contains)
            .none { packageName -> entryBelongsToPackage(entryName, packageName) }
    }
}
