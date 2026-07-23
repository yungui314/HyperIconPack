package io.github.cl0ura.hypericonpack.systemtheme

import io.github.cl0ura.hypericonpack.config.IconPackConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal const val ICON_ARCHIVE_FORMAT_VERSION = 30

internal data class IconArchiveInfo(
    val archive: File,
    val iconPackPackage: String?,
    val fallbackScaleMultiplier: Float?,
    val globalMonetIcons: Boolean,
    val monetCustomColors: Boolean,
    val monetBackgroundColor: Int,
    val monetForegroundColor: Int,
    val applicationScopeFingerprint: String,
    val monetPaletteFingerprint: String,
    val formatVersion: Int?,
) {
    val isCurrentFormat: Boolean
        get() = formatVersion == ICON_ARCHIVE_FORMAT_VERSION &&
            (!globalMonetIcons || monetPaletteFingerprint.isNotBlank())
}

internal data class IconArchiveVariant(
    val iconPackPackage: String,
    val fallbackScaleMultiplier: Float,
    val globalMonetIcons: Boolean,
    val monetCustomColors: Boolean,
    val monetBackgroundColor: Int,
    val monetForegroundColor: Int,
    val applicationScopeFingerprint: String,
    val monetPaletteFingerprint: String,
)

internal object IconArchiveFormat {
    const val METADATA_ENTRY = "META-INF/hypericonpack-conversion.properties"
    const val TRANSFORM_CONFIG_ENTRY = "transform_config.xml"
    const val ALL_APPLICATIONS_SCOPE = "all"

    private const val DEFAULT_RENDERING = "original"
    private const val GLOBAL_MONET_RENDERING = "global_monet_v18"
    private const val GLOBAL_MONET_RENDERING_PREFIX = "global_monet_v"

    fun readInfo(archive: File): IconArchiveInfo? = runCatching {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(METADATA_ENTRY) ?: return@use legacyInfo(archive)
            val values = linkedMapOf<String, String>()
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val divider = line.indexOf('=')
                    if (divider > 0) {
                        values[line.substring(0, divider)] = line.substring(divider + 1)
                    }
                }
            }
            IconArchiveInfo(
                archive = archive,
                iconPackPackage = values["icon_pack_package"]?.takeIf(String::isNotBlank),
                fallbackScaleMultiplier = values["fallback_scale"]?.toFloatOrNull(),
                globalMonetIcons = values["icon_rendering"]
                    ?.startsWith(GLOBAL_MONET_RENDERING_PREFIX) == true,
                monetCustomColors = values["monet_palette"] == "custom",
                monetBackgroundColor = values["monet_background"]
                    ?.toLongOrNull(16)
                    ?.toInt()
                    ?: IconPackConfig.DEFAULT_MONET_BACKGROUND_COLOR,
                monetForegroundColor = values["monet_foreground"]
                    ?.toLongOrNull(16)
                    ?.toInt()
                    ?: IconPackConfig.DEFAULT_MONET_FOREGROUND_COLOR,
                applicationScopeFingerprint = values["application_scope"] ?: ALL_APPLICATIONS_SCOPE,
                monetPaletteFingerprint = values["monet_palette_fingerprint"].orEmpty(),
                formatVersion = values["format"]?.toIntOrNull(),
            )
        }
    }.getOrElse {
        // The archive may already be installed. Preserve it as a legacy item
        // instead of reporting a malformed metadata entry as no conversion.
        legacyInfo(archive)
    }

    fun writeMetadata(zip: ZipOutputStream, variant: IconArchiveVariant) {
        require(!variant.globalMonetIcons || variant.monetPaletteFingerprint.isNotBlank()) {
            "Monet 主题归档缺少色板指纹"
        }
        val metadata = buildString {
            append("format=").append(ICON_ARCHIVE_FORMAT_VERSION).append('\n')
            append("icon_pack_package=").append(variant.iconPackPackage).append('\n')
            append("fallback_scale=").append(variant.fallbackScaleMultiplier).append('\n')
            append("icon_rendering=")
                .append(if (variant.globalMonetIcons) GLOBAL_MONET_RENDERING else DEFAULT_RENDERING)
                .append('\n')
            append("monet_palette=")
                .append(if (variant.globalMonetIcons && variant.monetCustomColors) "custom" else "system")
                .append('\n')
            append("monet_background=")
                .append(variant.monetBackgroundColor.toUInt().toString(16))
                .append('\n')
            append("monet_foreground=")
                .append(variant.monetForegroundColor.toUInt().toString(16))
                .append('\n')
            append("monet_palette_fingerprint=").append(variant.monetPaletteFingerprint).append('\n')
            append("application_scope=").append(variant.applicationScopeFingerprint).append('\n')
        }
        zip.putNextEntry(ZipEntry(METADATA_ENTRY))
        try {
            zip.write(metadata.toByteArray(Charsets.UTF_8))
        } finally {
            zip.closeEntry()
        }
    }

    fun writeTransformConfig(zip: ZipOutputStream, useDynamicIcon: Boolean) {
        val config = """
            <?xml version="1.0" encoding="UTF-8"?>
            <IconTransform>
                <Config name="SupportLayerIcon" value="false" />
                <Config name="UseDynamicIcon" value="$useDynamicIcon" />
                <Config name="ConfigIconMask" value="M0 0H100V100H0Z" />
            </IconTransform>
        """.trimIndent()
        zip.putNextEntry(ZipEntry(TRANSFORM_CONFIG_ENTRY))
        try {
            zip.write(config.toByteArray(Charsets.UTF_8))
        } finally {
            zip.closeEntry()
        }
    }

    fun ensureTransformConfig(iconArchive: File, enableDynamicIcons: Boolean): File {
        ZipFile(iconArchive).use { zip ->
            val existing = zip.getEntry(TRANSFORM_CONFIG_ENTRY)
            if (existing != null) {
                val content = zip.getInputStream(existing).use { it.readBytes().toString(Charsets.UTF_8) }
                val expected = enableDynamicIcons.toString()
                if (
                    content.contains("name=\"UseDynamicIcon\" value=\"$expected\"") &&
                    content.contains("name=\"SupportLayerIcon\" value=\"false\"")
                ) {
                    return iconArchive
                }
            }
        }

        val temporary = File(iconArchive.parentFile, "${iconArchive.name}.dynamic-config.new")
        try {
            ZipFile(iconArchive).use { input ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                    output.setLevel(Deflater.NO_COMPRESSION)
                    writeTransformConfig(output, useDynamicIcon = enableDynamicIcons)
                    input.entries().asSequence().forEach { entry ->
                        if (entry.name == TRANSFORM_CONFIG_ENTRY) return@forEach
                        output.putNextEntry(ZipEntry(entry.name))
                        try {
                            if (!entry.isDirectory) {
                                input.getInputStream(entry).use { stream -> stream.copyTo(output) }
                            }
                        } finally {
                            output.closeEntry()
                        }
                    }
                }
            }
            ArchiveFileCommitter.replace(
                temporary = temporary,
                destination = iconArchive,
                errorMessage = "无法提交主题归档的动态图标配置",
            )
            return iconArchive
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
    }

    fun validate(file: File, expectedIconEntries: Int) {
        ZipFile(file).use { zip ->
            val iconEntries = zip.entries().asSequence().filter { entry ->
                !entry.isDirectory &&
                    entry.name.startsWith("${IconArchiveEntryNames.TARGET_DENSITY_DIRECTORY}/") &&
                    entry.name.endsWith(".png")
            }.toList()
            require(iconEntries.size == expectedIconEntries) {
                "主题归档校验失败：期望 $expectedIconEntries 个图标，实际 ${iconEntries.size} 个"
            }
            require(iconEntries.all { it.size > 0L }) {
                "主题归档校验失败：包含空 PNG 资源"
            }
            require(zip.getEntry(TRANSFORM_CONFIG_ENTRY) != null) {
                "主题归档校验失败：缺少 $TRANSFORM_CONFIG_ENTRY"
            }
            require(zip.getEntry(METADATA_ENTRY)?.size ?: 0L > 0L) {
                "主题归档校验失败：缺少 $METADATA_ENTRY"
            }
        }
        val info = readInfo(file)
        require(
            info?.isCurrentFormat == true &&
                !info.iconPackPackage.isNullOrBlank() &&
                info.fallbackScaleMultiplier != null,
        ) { "主题归档校验失败：转换元数据无效" }
    }

    private fun legacyInfo(archive: File) = IconArchiveInfo(
        archive = archive,
        iconPackPackage = null,
        fallbackScaleMultiplier = null,
        globalMonetIcons = false,
        monetCustomColors = false,
        monetBackgroundColor = IconPackConfig.DEFAULT_MONET_BACKGROUND_COLOR,
        monetForegroundColor = IconPackConfig.DEFAULT_MONET_FOREGROUND_COLOR,
        applicationScopeFingerprint = ALL_APPLICATIONS_SCOPE,
        monetPaletteFingerprint = "",
        formatVersion = null,
    )
}
