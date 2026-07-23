package io.github.cl0ura.hypericonpack.systemtheme

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class PackageArchiveMutation(
    val archive: File,
    val changed: Boolean,
    val touchedPackages: Int,
)

internal object PackageArchiveMutator {
    fun updateInstalledPackages(
        context: Context,
        baseArchive: File,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
        monetPaletteFingerprint: String,
        packageNames: Collection<String>,
        preservedEntryNames: Set<String> = emptySet(),
        launchEntryNames: (String) -> List<String>,
        renderPackages: (List<String>) -> Map<String, ByteArray?>,
    ): File {
        require(baseArchive.isFile) { "缺少当前主题归档" }
        val requestedPackages = normalizedPackages(packageNames, "安装包名为空")
        val baseInfo = IconArchiveFormat.readInfo(baseArchive)
            ?: throw IllegalStateException("当前主题归档元数据无法读取")
        validateVariant(
            baseInfo = baseInfo,
            iconPackPackage = iconPackPackage,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            monetPaletteFingerprint = monetPaletteFingerprint,
        )

        val packagePngs = renderPackages(requestedPackages)
        val replacementEntries = linkedMapOf<String, ByteArray>()
        requestedPackages.forEach { packageName ->
            val packagePng = packagePngs[packageName]
                ?: throw IllegalStateException("无法渲染 $packageName 的图标")
            replacementEntries[IconArchiveEntryNames.packageArchiveEntryName(packageName)] = packagePng
            replacementLaunchEntryNames(
                launchEntryNames = launchEntryNames(packageName),
                preservedEntryNames = preservedEntryNames,
            ).forEach { entryName ->
                replacementEntries[entryName] = packagePng
            }
        }

        val variant = IconArchiveVariant(
            iconPackPackage = iconPackPackage,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = globalMonetIcons && monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            applicationScopeFingerprint = IconArchiveCache.applicationScopeFingerprint(context),
            monetPaletteFingerprint = monetPaletteFingerprint,
            nativeFallback = baseInfo.nativeFallback,
            nativeFallbackScale = baseInfo.nativeFallbackScale,
        )
        val destination = IconArchiveCache.archiveFile(context, variant, createDirectory = true)
        val temporary = File(destination.parentFile, "${destination.name}.package-update")
        try {
            rewriteArchive(
                baseArchive = baseArchive,
                temporary = temporary,
                variant = variant,
                skip = { entryName -> entryName in replacementEntries },
                append = { output ->
                    replacementEntries.forEach { (entryName, png) ->
                        writeEntry(output, entryName, png)
                    }
                },
            )
            ZipFile(temporary).use { zip ->
                replacementEntries.keys.forEach { entryName ->
                    require(zip.getEntry(entryName)?.size ?: 0L > 0L) {
                        "增量主题归档校验失败：缺少 $entryName"
                    }
                }
            }
            ArchiveFileCommitter.replace(temporary, destination, "无法提交增量主题归档")
            IconArchiveCache.dynamicArchiveFor(destination).delete()
            return destination
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
    }

    fun removeInstalledPackages(
        context: Context,
        baseArchive: File,
        packageNames: Collection<String>,
        monetPaletteFingerprint: String,
        preservedEntryNames: Set<String> = emptySet(),
        protectedPackageNames: Set<String> = emptySet(),
    ): PackageArchiveMutation {
        require(baseArchive.isFile) { "缺少当前主题归档" }
        val requestedPackages = normalizedPackages(packageNames, "卸载包名为空")
        val baseInfo = IconArchiveFormat.readInfo(baseArchive)
            ?: throw IllegalStateException("当前主题归档元数据无法读取")
        require(baseInfo.isCurrentFormat) { "当前主题归档格式过旧，需要先重新转换一次" }
        val iconPackPackage = baseInfo.iconPackPackage
            ?: throw IllegalStateException("当前主题归档缺少图标来源")
        val fallbackScaleMultiplier = baseInfo.fallbackScaleMultiplier
            ?: throw IllegalStateException("当前主题归档缺少适配比例")
        require(baseInfo.monetPaletteFingerprint == monetPaletteFingerprint) {
            "当前主题 Monet 色板与系统动态色不一致"
        }
        val variant = IconArchiveVariant(
            iconPackPackage = iconPackPackage,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = baseInfo.globalMonetIcons,
            monetCustomColors = baseInfo.monetCustomColors,
            monetBackgroundColor = baseInfo.monetBackgroundColor,
            monetForegroundColor = baseInfo.monetForegroundColor,
            applicationScopeFingerprint = IconArchiveCache.applicationScopeFingerprint(context),
            monetPaletteFingerprint = monetPaletteFingerprint,
            nativeFallback = baseInfo.nativeFallback,
            nativeFallbackScale = baseInfo.nativeFallbackScale,
        )
        val destination = IconArchiveCache.archiveFile(context, variant, createDirectory = true)
        val temporary = File(destination.parentFile, "${destination.name}.package-remove")
        var removedEntries = 0
        try {
            rewriteArchive(
                baseArchive = baseArchive,
                temporary = temporary,
                variant = variant,
                skip = { entryName ->
                    val remove = IconArchiveEntryNames.entryBelongsToAnyPackage(
                        entryName = entryName,
                        packageNames = requestedPackages,
                        preservedEntryNames = preservedEntryNames,
                        protectedPackageNames = protectedPackageNames,
                    )
                    if (remove) removedEntries++
                    remove
                },
            )
            if (removedEntries == 0) {
                temporary.delete()
                return PackageArchiveMutation(
                    archive = baseArchive,
                    changed = false,
                    touchedPackages = requestedPackages.size,
                )
            }
            ArchiveFileCommitter.replace(temporary, destination, "无法提交卸载清理主题归档")
            IconArchiveCache.dynamicArchiveFor(destination).delete()
            return PackageArchiveMutation(
                archive = destination,
                changed = true,
                touchedPackages = requestedPackages.size,
            )
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
    }

    private fun validateVariant(
        baseInfo: IconArchiveInfo,
        iconPackPackage: String,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
        monetPaletteFingerprint: String,
    ) {
        require(baseInfo.isCurrentFormat) { "当前主题归档格式过旧，需要先重新转换一次" }
        require(baseInfo.iconPackPackage == iconPackPackage) { "当前主题来源与活动配置不一致" }
        require(baseInfo.globalMonetIcons == globalMonetIcons) { "当前主题 Monet 配置与活动配置不一致" }
        require(baseInfo.monetCustomColors == (globalMonetIcons && monetCustomColors)) {
            "当前主题 Monet 配色模式与活动配置不一致"
        }
        require(
            !globalMonetIcons || !monetCustomColors ||
                (baseInfo.monetBackgroundColor == monetBackgroundColor &&
                    baseInfo.monetForegroundColor == monetForegroundColor),
        ) { "当前主题 Monet 自定义配色与活动配置不一致" }
        require(baseInfo.monetPaletteFingerprint == monetPaletteFingerprint) {
            "当前主题 Monet 色板与活动配置不一致"
        }
        require(
            baseInfo.fallbackScaleMultiplier?.let {
                kotlin.math.abs(it - fallbackScaleMultiplier) < 0.001f
            } == true,
        ) { "当前主题适配比例与活动配置不一致" }
    }

    private fun rewriteArchive(
        baseArchive: File,
        temporary: File,
        variant: IconArchiveVariant,
        skip: (String) -> Boolean,
        append: (ZipOutputStream) -> Unit = {},
    ) {
        ZipFile(baseArchive).use { input ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                output.setLevel(Deflater.NO_COMPRESSION)
                IconArchiveFormat.writeMetadata(output, variant)
                IconArchiveFormat.writeTransformConfig(
                    zip = output,
                    useDynamicIcon = false,
                    nativeFallbackScale = variant.nativeFallbackScale.takeIf { variant.nativeFallback },
                )
                input.entries().asSequence().forEach { entry ->
                    if (
                        entry.name == IconArchiveFormat.METADATA_ENTRY ||
                        entry.name == IconArchiveFormat.TRANSFORM_CONFIG_ENTRY ||
                        skip(entry.name)
                    ) {
                        return@forEach
                    }
                    output.putNextEntry(ZipEntry(entry.name))
                    try {
                        if (!entry.isDirectory) input.getInputStream(entry).use { it.copyTo(output) }
                    } finally {
                        output.closeEntry()
                    }
                }
                append(output)
            }
        }
    }

    private fun normalizedPackages(packageNames: Collection<String>, emptyMessage: String): List<String> {
        val normalized = packageNames.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
        require(normalized.isNotEmpty()) { emptyMessage }
        return normalized
    }

    internal fun replacementLaunchEntryNames(
        launchEntryNames: List<String>,
        preservedEntryNames: Set<String>,
    ): List<String> = launchEntryNames.distinct().filterNot(preservedEntryNames::contains)

    private fun writeEntry(output: ZipOutputStream, entryName: String, bytes: ByteArray) {
        output.putNextEntry(ZipEntry(entryName))
        try {
            output.write(bytes)
        } finally {
            output.closeEntry()
        }
    }

}
