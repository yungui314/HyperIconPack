package io.github.cl0ura.hypericonpack.systemtheme

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DynamicCalendarArchiveTest {
    @Test
    fun `lookup name keeps package fallback`() {
        assertEquals(
            "com.example.calendar",
            DynamicCalendarArchive.lookupName("com.example.calendar", null),
        )
        assertEquals(
            "com.example.calendar",
            DynamicCalendarArchive.lookupName("com.example.calendar", ""),
        )
    }

    @Test
    fun `lookup name keeps same-package activity`() {
        assertEquals(
            "com.example.calendar.MainActivity",
            DynamicCalendarArchive.lookupName(
                "com.example.calendar",
                "com.example.calendar.MainActivity",
            ),
        )
    }

    @Test
    fun `lookup name prefixes external activity`() {
        assertEquals(
            "com.example.calendar#com.vendor.CalendarAlias",
            DynamicCalendarArchive.lookupName(
                "com.example.calendar",
                "com.vendor.CalendarAlias",
            ),
        )
    }

    @Test
    fun `embedded calendar replacement drops stale trees`() {
        val icons = File.createTempFile("hyper-iconpack-icons", ".zip")
        val dynamic = File.createTempFile("hyper-iconpack-dynamic", ".zip")
        try {
            writeArchive(
                icons,
                mapOf(
                    IconArchiveFormat.TRANSFORM_CONFIG_ENTRY to "old-config".toByteArray(),
                    "res/drawable-xxhdpi/com.example.calendar.png" to byteArrayOf(1),
                    "animating_icons/com.example.calendar/fancy/manifest.xml" to "old".toByteArray(),
                    "animating_icons/com.example.stale/fancy/manifest.xml" to "stale".toByteArray(),
                ),
            )
            writeArchive(
                dynamic,
                mapOf(
                    "animating_icons/com.example.calendar/fancy/manifest.xml" to "new".toByteArray(),
                    "animating_icons/com.example.calendar/fancy/day_1.png" to byteArrayOf(2),
                ),
            )

            DynamicCalendarArchive.replaceEmbeddedEntries(icons, dynamic)

            ZipFile(icons).use { zip ->
                assertArrayEquals(
                    "new".toByteArray(),
                    zip.getInputStream(zip.getEntry("animating_icons/com.example.calendar/fancy/manifest.xml"))
                        .use { it.readBytes() },
                )
                assertNull(zip.getEntry("animating_icons/com.example.stale/fancy/manifest.xml"))
                assertArrayEquals(
                    byteArrayOf(1),
                    zip.getInputStream(zip.getEntry("res/drawable-xxhdpi/com.example.calendar.png"))
                        .use { it.readBytes() },
                )
            }

            DynamicCalendarArchive.replaceEmbeddedEntries(icons, dynamicArchive = null)

            ZipFile(icons).use { zip ->
                assertFalse(
                    zip.entries().asSequence().any {
                        it.name.startsWith("${IconArchiveEntryNames.DYNAMIC_ROOT_DIRECTORY}/")
                    },
                )
            }
        } finally {
            icons.delete()
            dynamic.delete()
        }
    }

    private fun writeArchive(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name))
                output.write(value)
                output.closeEntry()
            }
        }
    }
}
