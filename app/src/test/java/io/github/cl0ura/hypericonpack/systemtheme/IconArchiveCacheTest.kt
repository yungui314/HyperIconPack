package io.github.cl0ura.hypericonpack.systemtheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IconArchiveCacheTest {
    @Test
    fun `cache key separates rendering and installed app scope`() {
        val ordinary = IconArchiveVariant(
            iconPackPackage = "com.example.icons",
            fallbackScaleMultiplier = 0.85f,
            globalMonetIcons = false,
            monetCustomColors = false,
            monetBackgroundColor = 0xFFE8DEF8.toInt(),
            monetForegroundColor = 0xFF4A4458.toInt(),
            applicationScopeFingerprint = "apps-a",
            monetPaletteFingerprint = "palette-a",
        )
        assertNotEquals(
            IconArchiveCache.cacheKey(ordinary),
            IconArchiveCache.cacheKey(ordinary.copy(globalMonetIcons = true)),
        )
        assertNotEquals(
            IconArchiveCache.cacheKey(ordinary),
            IconArchiveCache.cacheKey(ordinary.copy(applicationScopeFingerprint = "apps-b")),
        )
        val systemMonet = ordinary.copy(globalMonetIcons = true)
        assertNotEquals(
            IconArchiveCache.cacheKey(systemMonet),
            IconArchiveCache.cacheKey(systemMonet.copy(monetPaletteFingerprint = "palette-b")),
        )
        assertEquals(
            IconArchiveCache.cacheKey(ordinary),
            IconArchiveCache.cacheKey(ordinary.copy(monetCustomColors = true)),
        )
        assertEquals(
            IconArchiveCache.cacheKey(ordinary),
            IconArchiveCache.cacheKey(ordinary.copy(monetPaletteFingerprint = "palette-b")),
        )
    }
}
