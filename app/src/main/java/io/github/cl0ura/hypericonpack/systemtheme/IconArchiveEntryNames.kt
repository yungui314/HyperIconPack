package io.github.cl0ura.hypericonpack.systemtheme

import android.content.ComponentName

/**
 * Pure HyperOS theme ZIP entry-name rules shared by full conversion and
 * incremental package add/remove. Kept free of Android Context so ownership
 * matching can be unit-tested without a device.
 *
 * Static resources retain complete package and component coverage. Optional
 * fancy_icons/<package>/ trees provide dynamic calendar resources.
 */
internal object IconArchiveEntryNames {
    const val TARGET_DENSITY_DIRECTORY = "res/drawable-xxhdpi"
    const val DYNAMIC_ROOT_DIRECTORY = "fancy_icons"
    const val GENERIC_MASK_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_mask.png"
    const val GENERIC_BACKGROUND_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_background.png"
    const val GENERIC_PATTERN_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_pattern.png"
    const val GENERIC_BORDER_ENTRY = "$TARGET_DENSITY_DIRECTORY/icon_border.png"
    val GENERIC_THEME_ENTRIES = setOf(
        GENERIC_MASK_ENTRY,
        GENERIC_BACKGROUND_ENTRY,
        GENERIC_PATTERN_ENTRY,
        GENERIC_BORDER_ENTRY,
    )

    // Compatibility names retained for diagnostics from the earlier native
    // fallback exporter. The format-34 writer uses the GENERIC_* names above.
    const val ICON_MASK_ENTRY = GENERIC_MASK_ENTRY
    const val ICON_BACKGROUND_ENTRY = GENERIC_BACKGROUND_ENTRY
    const val ICON_PATTERN_ENTRY = GENERIC_PATTERN_ENTRY
    const val ICON_BORDER_ENTRY = GENERIC_BORDER_ENTRY
    val NATIVE_FALLBACK_ENTRIES: Set<String> = GENERIC_THEME_ENTRIES

    fun isNativeFallbackEntry(entryName: String): Boolean = entryName in GENERIC_THEME_ENTRIES

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
        if (entryName in GENERIC_THEME_ENTRIES) return false
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
