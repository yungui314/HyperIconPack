package io.github.cl0ura.hypericonpack.systemtheme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.cl0ura.hypericonpack.config.IconAppearance
import io.github.cl0ura.hypericonpack.iconpack.IconMaskGeometry
import io.github.cl0ura.hypericonpack.iconpack.XiaomiThemeFallback
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal object MtzIconArchiveImporter {
    private const val SOURCE_PREFIX = "mtz:"
    private const val MAX_MTZ_BYTES = 512L * 1024 * 1024
    private const val MAX_ICONS_MODULE_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    fun isImportedSource(source: String?): Boolean = source?.startsWith(SOURCE_PREFIX) == true

    fun sourceLabel(source: String): String = source.removePrefix(SOURCE_PREFIX).substringBeforeLast('#')

    fun import(context: Context, uri: Uri): IconArchiveInfo {
        val outer = File.createTempFile("mtz-import-", ".mtz", context.cacheDir)
        val iconsModule = File.createTempFile("mtz-icons-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outer.outputStream().buffered().use { output -> copyBounded(input, output, MAX_MTZ_BYTES) }
            } ?: throw IllegalStateException("无法读取所选 MTZ 文件")
            val title = ZipFile(outer).use { zip ->
                val icons = zip.getEntry("icons") ?: throw IllegalStateException("MTZ 不包含 icons 图标模块")
                require(!icons.isDirectory && icons.size in 1..MAX_ICONS_MODULE_BYTES) { "MTZ icons 模块大小异常" }
                zip.getInputStream(icons).use { input ->
                    iconsModule.outputStream().buffered().use { output ->
                        copyBounded(input, output, MAX_ICONS_MODULE_BYTES)
                    }
                }
                readTitle(zip).ifBlank { "导入的 MTZ 图标" }
            }
            val sourceId = "$SOURCE_PREFIX${sanitizeTitle(title)}#${IconArchiveCache.sha256(iconsModule).take(12)}"
            val importedFallback = ZipFile(iconsModule).use(::readImportedFallback)
            val variant = IconArchiveVariant(
                iconPackPackage = sourceId,
                fallbackScaleMultiplier = importedFallback?.sourceScale ?: 1f,
                globalMonetIcons = false,
                monetCustomColors = false,
                monetBackgroundColor = 0,
                monetForegroundColor = 0,
                applicationScopeFingerprint = IconArchiveFormat.ALL_APPLICATIONS_SCOPE,
                monetPaletteFingerprint = "",
                iconAppearance = IconAppearance.FOLLOW_SYSTEM,
            )
            val destination = IconArchiveCache.archiveFile(context, variant, createDirectory = true)
            val temporary = File(destination.parentFile, "${destination.name}.mtz-import")
            var iconEntries = 0
            try {
                ZipFile(iconsModule).use { input ->
                    require(input.size() <= MAX_ENTRIES) { "MTZ icons 模块条目过多" }
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                        output.setLevel(Deflater.NO_COMPRESSION)
                        IconArchiveFormat.writeMetadata(output, variant)
                        IconArchiveFormat.writeTransformConfig(
                            output,
                            fallback = importedFallback,
                            useDynamicIcon = false,
                        )
                        IconArchiveFormat.writeThemeFallbackAssets(output, importedFallback)
                        input.entries().asSequence().forEach { entry ->
                            if (entry.isDirectory || !allowedEntry(entry.name)) return@forEach
                            if (entry.name in IconArchiveEntryNames.GENERIC_THEME_ENTRIES) return@forEach
                            require(entry.size in 0..MAX_ICONS_MODULE_BYTES) { "MTZ 图标条目大小异常：${entry.name}" }
                            if (
                                entry.name.startsWith("${IconArchiveEntryNames.TARGET_DENSITY_DIRECTORY}/") &&
                                entry.name.endsWith(".png") &&
                                entry.name !in IconArchiveEntryNames.GENERIC_THEME_ENTRIES
                            ) iconEntries++
                            output.putNextEntry(ZipEntry(entry.name))
                            try {
                                input.getInputStream(entry).use { stream ->
                                    copyBounded(stream, output, MAX_ICONS_MODULE_BYTES)
                                }
                            } finally {
                                output.closeEntry()
                            }
                        }
                    }
                }
                require(iconEntries > 0) { "MTZ icons 模块不包含静态应用图标" }
                IconArchiveFormat.validate(temporary, expectedIconEntries = iconEntries)
                ArchiveFileCommitter.replace(temporary, destination, "无法保存导入的 MTZ 图标存档")
            } catch (throwable: Throwable) {
                temporary.delete()
                throw throwable
            }
            return requireNotNull(IconArchiveFormat.readInfo(destination)) { "无法读取导入存档元数据" }
        } finally {
            outer.delete()
            iconsModule.delete()
        }
    }

    private fun allowedEntry(name: String): Boolean {
        if (name.startsWith('/') || name.contains('\\') || name.split('/').any { it == ".." }) return false
        if (name == IconArchiveFormat.METADATA_ENTRY || name == IconArchiveFormat.TRANSFORM_CONFIG_ENTRY) return false
        return (name.startsWith("${IconArchiveEntryNames.TARGET_DENSITY_DIRECTORY}/") && name.endsWith(".png")) ||
            name.startsWith("${IconArchiveEntryNames.DYNAMIC_ROOT_DIRECTORY}/")
    }

    private fun readTitle(zip: ZipFile): String {
        val entry = zip.getEntry("description.xml") ?: return ""
        return runCatching {
            zip.getInputStream(entry).use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(input.reader(Charsets.UTF_8))
                }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "title") {
                        return@use parser.nextText().trim()
                    }
                    parser.next()
                }
                ""
            }
        }.getOrDefault("")
    }

    private fun readImportedFallback(zip: ZipFile): XiaomiThemeFallback? {
        val maskBytes = zip.readBoundedEntry(IconArchiveEntryNames.GENERIC_MASK_ENTRY)
        val patternBytes = zip.readBoundedEntry(IconArchiveEntryNames.GENERIC_PATTERN_ENTRY)
        val borderBytes = zip.readBoundedEntry(IconArchiveEntryNames.GENERIC_BORDER_ENTRY)
        val shapeBytes = maskBytes ?: patternBytes ?: return null
        val shape = BitmapFactory.decodeByteArray(shapeBytes, 0, shapeBytes.size)
            ?: throw IllegalStateException("MTZ 通用图标轮廓不是有效 PNG")
        val normalized = try {
            val pixels = IntArray(shape.width * shape.height)
            shape.getPixels(pixels, 0, shape.width, 0, 0, shape.width, shape.height)
            IconMaskGeometry.normalize(
                width = shape.width,
                height = shape.height,
                sourcePixels = pixels,
                detectInverse = maskBytes != null,
            )
        } finally {
            shape.recycle()
        }
        val normalizedBitmap = Bitmap.createBitmap(
            normalized.pixels,
            normalized.width,
            normalized.height,
            Bitmap.Config.ARGB_8888,
        )
        val normalizedPng = try {
            ByteArrayOutputStream().use { output ->
                check(normalizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法编码 MTZ 通用图标轮廓"
                }
                output.toByteArray()
            }
        } finally {
            normalizedBitmap.recycle()
        }
        return XiaomiThemeFallback(
            maskPng = normalizedPng,
            patternPng = patternBytes,
            borderPng = borderBytes,
            configIconMask = normalized.configPath,
            sourceScale = readTransformScale(zip),
        )
    }

    private fun readTransformScale(zip: ZipFile): Float {
        val entry = zip.getEntry(IconArchiveFormat.TRANSFORM_CONFIG_ENTRY) ?: return 1f
        return runCatching {
            zip.getInputStream(entry).use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(input.reader(Charsets.UTF_8))
                }
                val fromX = ArrayList<Float>()
                val fromY = ArrayList<Float>()
                val toX = ArrayList<Float>()
                val toY = ArrayList<Float>()
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Point") {
                        parser.getAttributeValue(null, "fromX")?.toFloatOrNull()?.let(fromX::add)
                        parser.getAttributeValue(null, "fromY")?.toFloatOrNull()?.let(fromY::add)
                        parser.getAttributeValue(null, "toX")?.toFloatOrNull()?.let(toX::add)
                        parser.getAttributeValue(null, "toY")?.toFloatOrNull()?.let(toY::add)
                    }
                    parser.next()
                }
                val scaleX = span(toX) / span(fromX)
                val scaleY = span(toY) / span(fromY)
                listOf(scaleX, scaleY)
                    .filter(Float::isFinite)
                    .average()
                    .toFloat()
                    .takeIf { it in MIN_IMPORTED_SCALE..MAX_IMPORTED_SCALE }
                    ?: 1f
            }
        }.getOrDefault(1f)
    }

    private fun span(values: List<Float>): Float = if (values.size < 2) {
        Float.NaN
    } else {
        values.max() - values.min()
    }

    private fun ZipFile.readBoundedEntry(name: String): ByteArray? {
        val entry = getEntry(name) ?: return null
        require(!entry.isDirectory && entry.size in 1..MAX_ICONS_MODULE_BYTES) { "MTZ 图标条目大小异常：$name" }
        return getInputStream(entry).use { input ->
            ByteArrayOutputStream().use { output ->
                copyBounded(input, output, MAX_ICONS_MODULE_BYTES)
                output.toByteArray()
            }
        }
    }

    private fun sanitizeTitle(title: String): String = title
        .replace(Regex("[\\r\\n=#]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "导入的 MTZ 图标" }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= limit) { "导入文件超过允许大小" }
            output.write(buffer, 0, count)
        }
    }

    private const val MIN_IMPORTED_SCALE = 0.5f
    private const val MAX_IMPORTED_SCALE = 1.5f
}
