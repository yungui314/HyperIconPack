package io.github.cl0ura.hypericonpack.hook

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmProtectionPolicyTest {
    @Test
    fun `protects only a managed icons archive`() {
        assertTrue(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/icons",
                managedArchive = true,
            ),
        )
        assertFalse(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/dynamicicons",
                managedArchive = true,
            ),
        )
    }

    @Test
    fun `does not protect theme components when this app is not managing icons`() {
        assertFalse(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/icons",
                managedArchive = false,
            ),
        )
        assertFalse(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/lockscreen",
                managedArchive = true,
            ),
        )
        assertFalse(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/rights/icons_default.mra",
                managedArchive = true,
            ),
        )
    }

    @Test
    fun `normalizes trailing slashes and accepts absolute managed paths`() {
        assertTrue(
            DrmProtectionPolicy.shouldBypass(
                contentPath = "/data/system/theme/icons/",
                managedArchive = true,
            ),
        )
    }

    @Test
    fun `recognizes conversion metadata only in a marked zip`() {
        val themeDirectory = File.createTempFile("hyper-iconpack-theme", "")
            .also { file -> file.delete() }
        val dataSystemTheme = File(themeDirectory, "data/system/theme").also(File::mkdirs)
        val icons = File(dataSystemTheme, "icons")
        val unmarked = File(dataSystemTheme, "unmarked")
        try {
            writeZip(icons, "META-INF/hypericonpack-conversion.properties", "format=32")
            writeZip(unmarked, "transform_config.xml", "<IconTransform/>")

            assertTrue(DrmProtectionPolicy.hasConversionMetadata(icons))
            assertFalse(DrmProtectionPolicy.hasConversionMetadata(unmarked))
            // A marked temporary ZIP is still rejected because only Xiaomi's
            // canonical active icons path can be protected in production.
            assertFalse(DrmProtectionPolicy.isManagedIconArchive(icons))
        } finally {
            icons.delete()
            unmarked.delete()
            dataSystemTheme.deleteRecursively()
            themeDirectory.deleteRecursively()
        }
    }

    private fun writeZip(file: File, entryName: String, content: String) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
