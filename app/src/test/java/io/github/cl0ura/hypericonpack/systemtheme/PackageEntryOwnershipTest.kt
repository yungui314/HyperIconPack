package io.github.cl0ura.hypericonpack.systemtheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageEntryOwnershipTest {
    @Test
    fun `package default png matches`() {
        assertTrue(
            IconArchiveEntryNames.entryBelongsToPackage(
                "res/drawable-xxhdpi/com.example.app.png",
                "com.example.app",
            ),
        )
    }

    @Test
    fun `activity and external component png match`() {
        assertTrue(
            IconArchiveEntryNames.entryBelongsToPackage(
                "res/drawable-xxhdpi/com.example.app.MainActivity.png",
                "com.example.app",
            ),
        )
        assertTrue(
            IconArchiveEntryNames.entryBelongsToPackage(
                "res/drawable-xxhdpi/com.example.app#com.other.Alias.png",
                "com.example.app",
            ),
        )
    }

    @Test
    fun `dynamic calendar tree matches package root`() {
        assertTrue(
            IconArchiveEntryNames.entryBelongsToPackage(
                "animating_icons/com.example.app/fancy/manifest.xml",
                "com.example.app",
            ),
        )
        assertTrue(
            IconArchiveEntryNames.entryBelongsToPackage(
                "animating_icons/com.example.app.MainActivity/quiet/quietImage.png",
                "com.example.app",
            ),
        )
    }

    @Test
    fun `neighbor packages do not match prefix collisions`() {
        assertFalse(
            IconArchiveEntryNames.entryBelongsToPackage(
                "res/drawable-xxhdpi/com.example.app2.png",
                "com.example.app",
            ),
        )
        assertFalse(
            IconArchiveEntryNames.entryBelongsToPackage(
                "animating_icons/com.example.appx/fancy/manifest.xml",
                "com.example.app",
            ),
        )
        assertFalse(
            IconArchiveEntryNames.entryBelongsToPackage(
                "META-INF/hypericonpack-conversion.properties",
                "com.example.app",
            ),
        )
        IconArchiveEntryNames.NATIVE_FALLBACK_ENTRIES.forEach { entryName ->
            assertFalse(IconArchiveEntryNames.entryBelongsToPackage(entryName, "icon_mask"))
        }
    }

    @Test
    fun `converter delegates ownership matching`() {
        assertTrue(
            HyperOsIconArchiveConverter.entryBelongsToPackage(
                "res/drawable-xxhdpi/com.example.app.png",
                "com.example.app",
            ),
        )
    }

    @Test
    fun `explicit mapping can be preserved during package cleanup`() {
        val explicitEntry = "res/drawable-xxhdpi/com.example.app.SpecialActivity.png"
        assertFalse(
            IconArchiveEntryNames.entryBelongsToAnyPackage(
                entryName = explicitEntry,
                packageNames = listOf("com.example.app"),
                preservedEntryNames = setOf(explicitEntry),
            ),
        )
        assertTrue(
            IconArchiveEntryNames.entryBelongsToAnyPackage(
                entryName = "res/drawable-xxhdpi/com.example.app.png",
                packageNames = listOf("com.example.app"),
                preservedEntryNames = setOf(explicitEntry),
            ),
        )
    }

    @Test
    fun `installed dotted child package is protected from parent cleanup`() {
        assertFalse(
            IconArchiveEntryNames.entryBelongsToAnyPackage(
                entryName = "res/drawable-xxhdpi/com.example.app.feature.png",
                packageNames = listOf("com.example.app"),
                protectedPackageNames = setOf("com.example.app.feature"),
            ),
        )
        assertFalse(
            IconArchiveEntryNames.entryBelongsToAnyPackage(
                entryName = "res/drawable-xxhdpi/com.example.app.feature.MainActivity.png",
                packageNames = listOf("com.example.app"),
                protectedPackageNames = setOf("com.example.app.feature"),
            ),
        )
        assertTrue(
            IconArchiveEntryNames.entryBelongsToAnyPackage(
                entryName = "res/drawable-xxhdpi/com.example.app.MainActivity.png",
                packageNames = listOf("com.example.app"),
                protectedPackageNames = setOf("com.example.app.feature"),
            ),
        )
    }

    @Test
    fun `incremental replacement leaves explicit launcher entries unchanged`() {
        val explicit = "res/drawable-xxhdpi/com.example.app.MainActivity.png"
        val generated = "res/drawable-xxhdpi/com.example.app.SecondaryActivity.png"

        assertEquals(
            listOf(generated),
            PackageArchiveMutator.replacementLaunchEntryNames(
                launchEntryNames = listOf(explicit, generated, generated),
                preservedEntryNames = setOf(explicit),
            ),
        )
    }
}
