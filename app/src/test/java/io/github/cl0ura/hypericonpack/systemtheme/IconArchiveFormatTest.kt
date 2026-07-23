package io.github.cl0ura.hypericonpack.systemtheme

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class IconArchiveFormatTest {
    @Test
    fun `metadata round trips through archive`() {
        val archive = File.createTempFile("hyper-iconpack-format", ".zip")
        val variant = IconArchiveVariant(
            iconPackPackage = "com.example.icons",
            fallbackScaleMultiplier = 1.125f,
            globalMonetIcons = true,
            monetCustomColors = true,
            monetBackgroundColor = 0xFF123456.toInt(),
            monetForegroundColor = 0xFFABCDEF.toInt(),
            applicationScopeFingerprint = "0123456789abcdefghij",
            monetPaletteFingerprint = "ff123456:ff789abc:ffabcdef",
        )
        try {
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                IconArchiveFormat.writeMetadata(output, variant)
                IconArchiveFormat.writeTransformConfig(output, useDynamicIcon = false)
            }

            val info = requireNotNull(IconArchiveFormat.readInfo(archive))
            assertEquals(variant.iconPackPackage, info.iconPackPackage)
            assertEquals(
                variant.fallbackScaleMultiplier,
                requireNotNull(info.fallbackScaleMultiplier),
                0f,
            )
            assertEquals(variant.globalMonetIcons, info.globalMonetIcons)
            assertEquals(variant.monetCustomColors, info.monetCustomColors)
            assertEquals(variant.monetBackgroundColor, info.monetBackgroundColor)
            assertEquals(variant.monetForegroundColor, info.monetForegroundColor)
            assertEquals(variant.applicationScopeFingerprint, info.applicationScopeFingerprint)
            assertEquals(variant.monetPaletteFingerprint, info.monetPaletteFingerprint)
            assertTrue(info.isCurrentFormat)
            IconArchiveFormat.validate(archive, expectedIconEntries = 0)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `Monet metadata rejects a missing palette fingerprint`() {
        val archive = File.createTempFile("hyper-iconpack-format", ".zip")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                assertThrows(IllegalArgumentException::class.java) {
                    IconArchiveFormat.writeMetadata(
                        output,
                        IconArchiveVariant(
                            iconPackPackage = "com.example.icons",
                            fallbackScaleMultiplier = 1f,
                            globalMonetIcons = true,
                            monetCustomColors = false,
                            monetBackgroundColor = 0,
                            monetForegroundColor = 0,
                            applicationScopeFingerprint = "apps",
                            monetPaletteFingerprint = "",
                        ),
                    )
                }
            }
        } finally {
            archive.delete()
        }
    }
}
