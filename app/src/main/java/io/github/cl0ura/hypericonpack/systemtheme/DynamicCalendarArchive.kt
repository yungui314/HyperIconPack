package io.github.cl0ura.hypericonpack.systemtheme

import android.content.ComponentName
import io.github.cl0ura.hypericonpack.iconpack.ParsedIconPack
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object DynamicCalendarArchive {
    private const val DYNAMIC_ICON_SIZE_PX = 256

    fun generate(
        pack: ParsedIconPack?,
        installedPackageNames: Set<String>,
        launchableComponents: List<ComponentName>,
        iconArchive: File,
        palette: GlobalMonetPalette?,
        fallbackScaleMultiplier: Float,
        isCancelled: () -> Boolean,
    ): File? {
        checkCancelled(isCancelled)
        val destination = IconArchiveCache.dynamicArchiveFor(iconArchive)
        val resolvedPack = pack ?: run {
            deleteSidecar(destination)
            return null
        }
        val installedMappings = resolvedPack.calendarMappings()
            .filter { it.component.packageName in installedPackageNames }
        if (installedMappings.isEmpty()) {
            deleteSidecar(destination)
            return null
        }
        val launchableByPackage = launchableComponents.groupBy({ it.packageName }, { it })
        val temporary = File(destination.parentFile, "${destination.name}.new")
        var convertedTrees = 0
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                val renderedByPrefix = HashMap<String, List<ByteArray>>()
                val writtenRoots = HashSet<String>()
                installedMappings
                    .groupBy { it.component.packageName to it.drawablePrefix }
                    .forEach { (packageAndPrefix, mappings) ->
                        checkCancelled(isCancelled)
                        val (packageName, drawablePrefix) = packageAndPrefix
                        val renderedDays = renderedByPrefix.getOrPut(drawablePrefix) {
                            val tasks = (1..31).map { day ->
                                PngRenderTask(key = day.toString()) {
                                    checkCancelled(isCancelled)
                                    resolvedPack.loadCalendarDrawable(
                                        drawablePrefix = drawablePrefix,
                                        dayOfMonth = day,
                                        scaleMultiplier = fallbackScaleMultiplier,
                                    )?.let { drawable ->
                                        PngRenderSource(drawable = drawable)
                                    }
                                }
                            }
                            val rendered = IconPngRenderer.renderParallelTasks(
                                tasks = tasks,
                                palette = palette,
                                isCancelled = isCancelled,
                            )
                            (1..31).mapNotNull { day -> rendered[day.toString()]?.png }
                                .takeIf { it.size == 31 }
                                .orEmpty()
                        }
                        if (renderedDays.size != 31) return@forEach
                        val relativeNames = LinkedHashSet<String>()
                        relativeNames += packageName
                        mappings.forEach { mapping ->
                            relativeNames += lookupName(
                                packageName = mapping.component.packageName,
                                className = mapping.component.className,
                            )
                        }
                        launchableByPackage[packageName].orEmpty().forEach { component ->
                            relativeNames += lookupName(component.packageName, component.className)
                        }
                        val currentDay = Calendar.getInstance()
                            .get(Calendar.DAY_OF_MONTH)
                            .coerceIn(1, 31)
                        val quiet = renderedDays[currentDay - 1]
                        relativeNames.forEach { relativeName ->
                            checkCancelled(isCancelled)
                            if (!writtenRoots.add(relativeName)) return@forEach
                            val packageRoot = "${IconArchiveEntryNames.DYNAMIC_ROOT_DIRECTORY}/$relativeName"
                            appendText(zip, "$packageRoot/fancy/manifest.xml", manifest())
                            renderedDays.forEachIndexed { index, bytes ->
                                checkCancelled(isCancelled)
                                appendBytes(zip, "$packageRoot/fancy/day_${index + 1}.png", bytes)
                            }
                            appendBytes(zip, "$packageRoot/quiet/quietImage.png", quiet)
                            convertedTrees++
                        }
                    }
            }
            if (convertedTrees == 0) {
                temporary.delete()
                deleteSidecar(destination)
                return null
            }
            ZipFile(temporary).use { zip ->
                require(zip.entries().asSequence().any { it.name.endsWith("/fancy/manifest.xml") }) {
                    "HyperOS 动态日历归档校验失败"
                }
            }
            ArchiveFileCommitter.replace(temporary, destination, "无法提交动态日历归档")
            return destination
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
    }

    fun replaceEmbeddedEntries(
        iconArchive: File,
        dynamicArchive: File?,
        isCancelled: () -> Boolean = { false },
    ) {
        require(iconArchive.isFile) { "缺少待合并的主题归档" }
        require(dynamicArchive == null || dynamicArchive.isFile) { "缺少动态日历归档" }
        val hasEmbeddedEntries = ZipFile(iconArchive).use { icons ->
            icons.entries().asSequence().any { entry -> isDynamicEntry(entry.name) }
        }
        if (dynamicArchive == null && !hasEmbeddedEntries) return

        val temporary = File(iconArchive.parentFile, "${iconArchive.name}.embed-dynamic.new")
        try {
            ZipFile(iconArchive).use { icons ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                    output.setLevel(Deflater.NO_COMPRESSION)
                    IconArchiveFormat.writeTransformConfig(output, useDynamicIcon = false)
                    val written = HashSet<String>()
                    icons.entries().asSequence().forEach { entry ->
                        checkCancelled(isCancelled)
                        if (
                            entry.name == IconArchiveFormat.TRANSFORM_CONFIG_ENTRY ||
                            isDynamicEntry(entry.name)
                        ) {
                            return@forEach
                        }
                        copyEntry(icons, entry, output, written)
                    }
                    if (dynamicArchive != null) {
                        ZipFile(dynamicArchive).use { dynamic ->
                            dynamic.entries().asSequence()
                                .filter { isDynamicEntry(it.name) }
                                .forEach { entry ->
                                    checkCancelled(isCancelled)
                                    copyEntry(dynamic, entry, output, written)
                                }
                        }
                    }
                }
            }
            ArchiveFileCommitter.replace(temporary, iconArchive, "无法提交合并后的主题归档")
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
    }

    internal fun lookupName(packageName: String, className: String?): String {
        val resolvedClass = className?.takeIf(String::isNotBlank) ?: return packageName
        return if (resolvedClass.startsWith(packageName)) resolvedClass else "$packageName#$resolvedClass"
    }

    private fun copyEntry(
        source: ZipFile,
        entry: ZipEntry,
        output: ZipOutputStream,
        written: MutableSet<String>,
    ) {
        if (!written.add(entry.name)) return
        output.putNextEntry(ZipEntry(entry.name))
        try {
            if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(output) }
        } finally {
            output.closeEntry()
        }
    }

    private fun appendText(zip: ZipOutputStream, name: String, value: String) =
        appendBytes(zip, name, value.toByteArray(Charsets.UTF_8))

    private fun appendBytes(zip: ZipOutputStream, name: String, value: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        try {
            zip.write(value)
        } finally {
            zip.closeEntry()
        }
    }

    private fun isDynamicEntry(entryName: String): Boolean {
        val root = IconArchiveEntryNames.DYNAMIC_ROOT_DIRECTORY
        return entryName == root || entryName.startsWith("$root/")
    }

    private fun deleteSidecar(file: File) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("无法清理旧的动态日历归档")
        }
    }

    private fun manifest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <Icon version="1" frameRate="0" width="$DYNAMIC_ICON_SIZE_PX" height="$DYNAMIC_ICON_SIZE_PX" screenWidth="1080" useVariableUpdater="DateTime.Day">
            <ExternalCommands>
                <Trigger action="resume">
                    <FrameRateCommand rate="5" />
                    <FrameRateCommand rate="0" delay="10" />
                </Trigger>
                <Trigger action="back_home_start"><FrameRateCommand rate="60" /></Trigger>
                <Trigger action="back_home_finish"><FrameRateCommand rate="0" delay="1000" /></Trigger>
            </ExternalCommands>
            <Image x="$DYNAMIC_ICON_SIZE_PX/2" y="$DYNAMIC_ICON_SIZE_PX/2" align="center" alignV="center" src="day.png" srcid="#date" />
        </Icon>
    """.trimIndent()

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw ConversionCancelledException()
    }
}
