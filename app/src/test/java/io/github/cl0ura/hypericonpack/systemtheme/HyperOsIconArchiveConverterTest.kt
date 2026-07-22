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
        val archive = File.createTempFile("hyper-iconpack-transform", ".zip")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                output.putNextEntry(ZipEntry("transform_config.xml"))
                output.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <IconTransform>
                        <Config name="SupportLayerIcon" value="true" />
                        <Config name="UseDynamicIcon" value="false" />
                        <Config name="ConfigIconMask" value="M0 0H100V100H0Z" />
                    </IconTransform>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                output.closeEntry()
                output.putNextEntry(ZipEntry("res/drawable-xxhdpi/example.png"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }

            HyperOsIconArchiveConverter.ensureUseDynamicIconTransformConfig(
                iconArchive = archive,
                enableDynamicIcons = false,
            )

            ZipFile(archive).use { zip ->
                val config = zip.getInputStream(zip.getEntry("transform_config.xml"))
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                assertTrue(config.contains("name=\"UseDynamicIcon\" value=\"false\""))
                assertTrue(config.contains("name=\"SupportLayerIcon\" value=\"false\""))
                assertFalse(config.contains("name=\"SupportLayerIcon\" value=\"true\""))
            }
        } finally {
            archive.delete()
        }
    }
}
