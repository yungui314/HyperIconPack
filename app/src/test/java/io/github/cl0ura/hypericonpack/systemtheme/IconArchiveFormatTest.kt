package io.github.cl0ura.hypericonpack.systemtheme

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
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
            ZipFile(archive).use { zip ->
                val config = zip.getInputStream(zip.getEntry(IconArchiveFormat.TRANSFORM_CONFIG_ENTRY))
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                assertTrue(config.contains("name=\"SupportLayerIcon\" value=\"false\""))
                assertTrue(config.contains("name=\"UseDynamicIcon\" value=\"false\""))
                assertTrue(config.contains("<PointsMapping>"))
                assertTrue(config.contains("name=\"ConfigIconMask\""))
                assertTrue(!config.contains("value=\"M0 0H100V100H0Z\""))
            }
            IconArchiveFormat.validate(archive, expectedIconEntries = 0)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `generic Xiaomi resources are not counted as application icons`() {
        val archive = File.createTempFile("hyper-iconpack-generic", ".zip")
        val variant = IconArchiveVariant(
            iconPackPackage = "com.example.icons",
            fallbackScaleMultiplier = 1f,
            globalMonetIcons = false,
            monetCustomColors = false,
            monetBackgroundColor = 0,
            monetForegroundColor = 0,
            applicationScopeFingerprint = "apps",
            monetPaletteFingerprint = "",
        )
        try {
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                IconArchiveFormat.writeMetadata(output, variant)
                IconArchiveFormat.writeTransformConfig(output)
                output.putNextEntry(java.util.zip.ZipEntry(IconArchiveEntryNames.GENERIC_MASK_ENTRY))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
                output.putNextEntry(java.util.zip.ZipEntry("res/drawable-xxhdpi/com.example.app.png"))
                output.write(byteArrayOf(4, 5, 6))
                output.closeEntry()
            }
            IconArchiveFormat.validate(archive, expectedIconEntries = 1)
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
