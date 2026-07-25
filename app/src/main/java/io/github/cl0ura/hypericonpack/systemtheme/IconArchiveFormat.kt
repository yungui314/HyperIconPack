package io.github.cl0ura.hypericonpack.systemtheme

import io.github.cl0ura.hypericonpack.config.IconPackConfig
import io.github.cl0ura.hypericonpack.config.IconAppearance
import io.github.cl0ura.hypericonpack.iconpack.XiaomiThemeFallback
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Format 35 records the resource appearance used for both original-colour and
// Monet archives so light/dark variants remain independently identifiable.
internal const val ICON_ARCHIVE_FORMAT_VERSION = 35

internal data class IconArchiveInfo(
    val archive: File,
    val iconPackPackage: String?,
    val fallbackScaleMultiplier: Float?,
    val globalMonetIcons: Boolean,
    val liquidGlassIcons: Boolean = false,
    val monetCustomColors: Boolean,
    val monetBackgroundColor: Int,
    val monetForegroundColor: Int,
    val applicationScopeFingerprint: String,
    val monetPaletteFingerprint: String,
    val formatVersion: Int?,
    val iconAppearance: IconAppearance = IconAppearance.FOLLOW_SYSTEM,
) {
    val isCurrentFormat: Boolean
        get() = formatVersion == ICON_ARCHIVE_FORMAT_VERSION &&
            (!globalMonetIcons || monetPaletteFingerprint.isNotBlank())
}

internal data class IconArchiveVariant(
    val iconPackPackage: String,
    val fallbackScaleMultiplier: Float,
    val globalMonetIcons: Boolean,
    val liquidGlassIcons: Boolean = false,
    val monetCustomColors: Boolean,
    val monetBackgroundColor: Int,
    val monetForegroundColor: Int,
    val applicationScopeFingerprint: String,
    val monetPaletteFingerprint: String,
    val iconAppearance: IconAppearance = IconAppearance.FOLLOW_SYSTEM,
)

internal object IconArchiveFormat {
    const val METADATA_ENTRY = "META-INF/hypericonpack-conversion.properties"
    const val TRANSFORM_CONFIG_ENTRY = "transform_config.xml"
    const val ALL_APPLICATIONS_SCOPE = "all"

    private const val DEFAULT_RENDERING = "original"
    private const val GLOBAL_MONET_RENDERING = "global_monet_v20"
    private const val GLOBAL_MONET_RENDERING_PREFIX = "global_monet_v"
    private const val LIQUID_GLASS_RENDERING = "liquid_glass_v3"

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
                liquidGlassIcons = values["icon_rendering"] == LIQUID_GLASS_RENDERING,
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
                iconAppearance = IconAppearance.fromStorage(values["icon_appearance"]),
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
                .append(
                    when {
                        variant.liquidGlassIcons -> LIQUID_GLASS_RENDERING
                        variant.globalMonetIcons -> GLOBAL_MONET_RENDERING
                        else -> DEFAULT_RENDERING
                    },
                )
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
            append("icon_appearance=").append(variant.iconAppearance.storageValue).append('\n')
            append("application_scope=").append(variant.applicationScopeFingerprint).append('\n')
        }
        zip.putNextEntry(ZipEntry(METADATA_ENTRY))
        try {
            zip.write(metadata.toByteArray(Charsets.UTF_8))
        } finally {
            zip.closeEntry()
        }
    }

    fun writeTransformConfig(
        zip: ZipOutputStream,
        fallback: XiaomiThemeFallback? = null,
        useDynamicIcon: Boolean = false,
    ) {
        val config = transformConfig(fallback)
        zip.putNextEntry(ZipEntry(TRANSFORM_CONFIG_ENTRY))
        try {
            zip.write(config.toByteArray(Charsets.UTF_8))
        } finally {
            zip.closeEntry()
        }
    }

    fun writeThemeFallbackAssets(zip: ZipOutputStream, fallback: XiaomiThemeFallback?) {
        if (fallback == null) return
        writeEntry(zip, IconArchiveEntryNames.GENERIC_MASK_ENTRY, fallback.maskPng)
        fallback.patternPng?.let { writeEntry(zip, IconArchiveEntryNames.GENERIC_PATTERN_ENTRY, it) }
        fallback.borderPng?.let { writeEntry(zip, IconArchiveEntryNames.GENERIC_BORDER_ENTRY, it) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun ensureTransformConfig(iconArchive: File, enableDynamicIcons: Boolean = false): File {
        ZipFile(iconArchive).use { zip ->
            val existing = zip.getEntry(TRANSFORM_CONFIG_ENTRY)
            if (existing != null) {
                val content = zip.getInputStream(existing).use { it.readBytes().toString(Charsets.UTF_8) }
                if (isOfficialTransformConfig(content)) {
                    return iconArchive
                }
            }
        }

        val temporary = File(iconArchive.parentFile, "${iconArchive.name}.dynamic-config.new")
        try {
            ZipFile(iconArchive).use { input ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                    output.setLevel(Deflater.NO_COMPRESSION)
                    writeTransformConfig(output, useDynamicIcon = false)
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

    fun isOfficialTransformConfig(content: String): Boolean {
        val normalized = content.replace("\r\n", "\n").trim()
        return normalized.contains("name=\"SupportLayerIcon\" value=\"false\"") &&
            normalized.contains("name=\"UseDynamicIcon\" value=\"false\"") &&
            normalized.contains("name=\"ConfigIconMask\"") &&
            !normalized.contains("value=\"M0 0H100V100H0Z\"") &&
            !normalized.contains("name=\"SupportLayerIcon\" value=\"true\"") &&
            !normalized.contains("name=\"UseDynamicIcon\" value=\"true\"")
    }

    private fun transformConfig(fallback: XiaomiThemeFallback?): String {
        val mask = fallback?.configIconMask ?: DEFAULT_CONFIG_ICON_MASK
        val scale = fallback?.sourceScale ?: 1f
        val fromMin = 0f
        val fromMax = 90f
        val toMin = 45f * (1f - scale)
        val toMax = 45f * (1f + scale)
        return """<?xml version="1.0" encoding="UTF-8"?>
<IconTransform>
    <PointsMapping>
        <Point fromX="$fromMin" fromY="$fromMin" toX="$toMin" toY="$toMin" />
        <Point fromX="$fromMin" fromY="$fromMax" toX="$toMin" toY="$toMax" />
        <Point fromX="$fromMax" fromY="$fromMax" toX="$toMax" toY="$toMax" />
        <Point fromX="$fromMax" fromY="$fromMin" toX="$toMax" toY="$toMin" />
    </PointsMapping>
    <Config name="SupportLayerIcon" value="false" />
    <Config name="UseDynamicIcon" value="false" />
    <Config name="ConfigIconMask" value="$mask" />
</IconTransform>
"""
    }

    private const val DEFAULT_CONFIG_ICON_MASK =
        "M0 50C0 13.0208 13.0209 0 50 0C86.9792 0 100 13.0209 100 50C100 86.9791 86.9792 100 50.0001 100C13.021 100 0 86.9792 0 50Z"

    fun validate(file: File, expectedIconEntries: Int) {
        ZipFile(file).use { zip ->
            val iconEntries = zip.entries().asSequence().filter { entry ->
                !entry.isDirectory &&
                    entry.name.startsWith("${IconArchiveEntryNames.TARGET_DENSITY_DIRECTORY}/") &&
                    entry.name.endsWith(".png") &&
                    entry.name !in IconArchiveEntryNames.GENERIC_THEME_ENTRIES
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
            val config = zip.getInputStream(zip.getEntry(TRANSFORM_CONFIG_ENTRY))
                .use { it.readBytes().toString(Charsets.UTF_8) }
            require(isOfficialTransformConfig(config)) {
                "主题归档校验失败：图标轮廓仍为 FullMask 或配置无效"
            }
            if (IconArchiveEntryNames.GENERIC_THEME_ENTRIES.any { zip.getEntry(it) != null }) {
                require(zip.getEntry(IconArchiveEntryNames.GENERIC_MASK_ENTRY)?.size ?: 0L > 0L) {
                    "主题归档校验失败：通用图标素材缺少 icon_mask.png"
                }
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
        iconAppearance = IconAppearance.FOLLOW_SYSTEM,
    )

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        try {
            zip.write(bytes)
        } finally {
            zip.closeEntry()
        }
    }
}
