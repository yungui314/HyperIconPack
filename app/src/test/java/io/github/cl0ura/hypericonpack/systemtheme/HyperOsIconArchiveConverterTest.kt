package io.github.cl0ura.hypericonpack.systemtheme

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsIconArchiveConverterTest {
    @Test
    fun `legacy layered transform config is normalized to static icon lookup`() {
        val archive = createArchiveWithTransformConfig(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <IconTransform>
                <Config name="SupportLayerIcon" value="true" />
                <Config name="UseDynamicIcon" value="false" />
                <Config name="ConfigIconMask" value="M0 0H100V100H0Z" />
            </IconTransform>
            """.trimIndent(),
        )
        try {
            HyperOsIconArchiveConverter.ensureUseDynamicIconTransformConfig(
                iconArchive = archive,
                enableDynamicIcons = false,
            )

            val config = readTransformConfig(archive)
            assertTrue(config.contains("name=\"UseDynamicIcon\" value=\"false\""))
            assertTrue(config.contains("name=\"SupportLayerIcon\" value=\"false\""))
            assertFalse(config.contains("name=\"SupportLayerIcon\" value=\"true\""))
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `old dynamic transform config is rewritten to UseDynamicIcon false when disabled`() {
        val archive = createArchiveWithTransformConfig(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <IconTransform>
                <Config name="SupportLayerIcon" value="true" />
                <Config name="UseDynamicIcon" value="true" />
                <Config name="ConfigIconMask" value="M0 0H100V100H0Z" />
            </IconTransform>
            """.trimIndent(),
        )
        try {
            HyperOsIconArchiveConverter.ensureUseDynamicIconTransformConfig(
                iconArchive = archive,
                enableDynamicIcons = false,
            )

            val config = readTransformConfig(archive)
            assertTrue(config.contains("name=\"UseDynamicIcon\" value=\"false\""))
            assertTrue(config.contains("name=\"SupportLayerIcon\" value=\"false\""))
            assertFalse(config.contains("name=\"SupportLayerIcon\" value=\"true\""))
            assertFalse(config.contains("name=\"UseDynamicIcon\" value=\"true\""))
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `matching static transform config is left unchanged`() {
        val original = """
            <?xml version="1.0" encoding="UTF-8"?>
            <IconTransform>
                <Config name="SupportLayerIcon" value="false" />
                <Config name="UseDynamicIcon" value="false" />
                <Config name="ConfigIconMask" value="M0 0H100V100H0Z" />
            </IconTransform>
        """.trimIndent()
        val archive = createArchiveWithTransformConfig(original)
        try {
            val before = archive.lastModified()
            Thread.sleep(5L)
            HyperOsIconArchiveConverter.ensureUseDynamicIconTransformConfig(
                iconArchive = archive,
                enableDynamicIcons = false,
            )
            val config = readTransformConfig(archive)
            assertTrue(config.contains("name=\"SupportLayerIcon\" value=\"false\""))
            assertTrue(config.contains("name=\"UseDynamicIcon\" value=\"false\""))
            // Unchanged path should keep the same archive file.
            assertTrue(archive.isFile)
            assertTrue(archive.lastModified() >= before)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `native fallback transform keeps scale and omits full mask`() {
        val archive = File.createTempFile("hyper-iconpack-native-transform", ".zip")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                val variant = IconArchiveVariant(
                    iconPackPackage = "com.example.icons",
                    fallbackScaleMultiplier = 0.85f,
                    globalMonetIcons = false,
                    monetCustomColors = false,
                    monetBackgroundColor = 0,
                    monetForegroundColor = 0,
                    applicationScopeFingerprint = "apps",
                    monetPaletteFingerprint = "",
                    nativeFallback = true,
                    nativeFallbackScale = 0.9775f,
                )
                IconArchiveFormat.writeMetadata(output, variant)
                IconArchiveFormat.writeTransformConfig(
                    zip = output,
                    useDynamicIcon = false,
                    nativeFallbackScale = variant.nativeFallbackScale,
                )
            }

            HyperOsIconArchiveConverter.ensureUseDynamicIconTransformConfig(
                iconArchive = archive,
                enableDynamicIcons = true,
            )

            val config = readTransformConfig(archive)
            assertTrue(config.contains("name=\"UseDynamicIcon\" value=\"true\""))
            assertTrue(config.contains("<ScaleX value=\"0.9775\" />"))
            assertTrue(config.contains("<ScaleY value=\"0.9775\" />"))
            assertFalse(config.contains("ConfigIconMask"))
        } finally {
            archive.delete()
        }
    }

    private fun createArchiveWithTransformConfig(configXml: String): File {
        val archive = File.createTempFile("hyper-iconpack-transform", ".zip")
        ZipOutputStream(FileOutputStream(archive)).use { output ->
            output.putNextEntry(ZipEntry("transform_config.xml"))
            output.write(configXml.toByteArray(Charsets.UTF_8))
            output.closeEntry()
            output.putNextEntry(ZipEntry("res/drawable-xxhdpi/example.png"))
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
        }
        return archive
    }

    private fun readTransformConfig(archive: File): String {
        return ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry("transform_config.xml"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
