package io.github.cl0ura.hypericonpack.systemtheme

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.cl0ura.hypericonpack.root.RootAccess
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal object IconArchiveCache {
    private const val ARCHIVE_DIRECTORY = "hyperos-theme"
    private const val ARCHIVE_CACHE_DIRECTORY = "archives"
    private const val ARCHIVE_FILE_PREFIX = "icons-"
    private const val LEGACY_ARCHIVE_FILE_NAME = "hypericonpack-icons.zip"
    private const val DYNAMIC_ARCHIVE_SUFFIX = "-dynamicicons.zip"
    private const val SCOPE_FINGERPRINT_LENGTH = 20
    private const val GLOBAL_MONET_RENDERING = "global_monet_v18"

    fun existingArchive(
        context: Context,
        iconPackPackage: String?,
        fallbackScaleMultiplier: Float,
        globalMonetIcons: Boolean,
        monetCustomColors: Boolean,
        monetBackgroundColor: Int,
        monetForegroundColor: Int,
    ): File? {
        val packageName = iconPackPackage?.takeIf(String::isNotBlank) ?: return null
        val paletteFingerprint = currentMonetPaletteFingerprint(
            context = context,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
        )
        val variant = IconArchiveVariant(
            iconPackPackage = packageName,
            fallbackScaleMultiplier = fallbackScaleMultiplier,
            globalMonetIcons = globalMonetIcons,
            monetCustomColors = monetCustomColors,
            monetBackgroundColor = monetBackgroundColor,
            monetForegroundColor = monetForegroundColor,
            applicationScopeFingerprint = applicationScopeFingerprint(context),
            monetPaletteFingerprint = paletteFingerprint,
        )
        val cached = archiveFile(context, variant, createDirectory = false).takeIf(File::isFile)
        if (cached != null && IconArchiveFormat.readInfo(cached)?.isCurrentFormat == true) return cached

        val legacy = legacyArchive(context).takeIf(File::isFile) ?: return null
        val info = IconArchiveFormat.readInfo(legacy)
        return legacy.takeIf {
            info?.isCurrentFormat == true &&
                info.iconPackPackage == packageName &&
                info.fallbackScaleMultiplier?.let { scale ->
                    kotlin.math.abs(scale - fallbackScaleMultiplier) < 0.001f
                } == true &&
                info.globalMonetIcons == globalMonetIcons &&
                info.monetCustomColors == (globalMonetIcons && monetCustomColors) &&
                (!globalMonetIcons || !monetCustomColors ||
                    (info.monetBackgroundColor == monetBackgroundColor &&
                        info.monetForegroundColor == monetForegroundColor)) &&
                info.monetPaletteFingerprint == paletteFingerprint &&
                info.applicationScopeFingerprint == variant.applicationScopeFingerprint
        }
    }

    fun cachedArchiveInfos(context: Context): List<IconArchiveInfo> {
        val directory = archiveDirectory(context, createDirectory = false) ?: return legacyArchive(context)
            .takeIf(File::isFile)
            ?.let(IconArchiveFormat::readInfo)
            ?.let(::listOf)
            .orEmpty()
        val cached = directory.listFiles { file ->
            file.isFile &&
                file.name.startsWith(ARCHIVE_FILE_PREFIX) &&
                file.extension == "zip" &&
                !file.name.endsWith(DYNAMIC_ARCHIVE_SUFFIX)
        }.orEmpty().mapNotNull(IconArchiveFormat::readInfo)
        val legacy = legacyArchive(context).takeIf(File::isFile)?.let(IconArchiveFormat::readInfo)
        return (cached + listOfNotNull(legacy))
            .filter(IconArchiveInfo::isCurrentFormat)
            .distinctBy { it.archive.absolutePath }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.iconPackPackage ?: it.archive.name })
    }

    fun latestCachedArchiveForSource(context: Context, iconPackPackage: String?): IconArchiveInfo? {
        if (iconPackPackage.isNullOrBlank()) return null
        return cachedArchiveInfos(context)
            .asSequence()
            .filter { it.iconPackPackage == iconPackPackage }
            .maxByOrNull { it.archive.lastModified() }
    }

    fun deleteCachedArchive(context: Context, archive: File): Boolean {
        val directory = archiveDirectory(context, createDirectory = false) ?: return false
        val expectedParent = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { archive.canonicalFile }.getOrNull() ?: return false
        if (candidate.parentFile != expectedParent) return false
        if (
            !candidate.name.startsWith(ARCHIVE_FILE_PREFIX) ||
            !candidate.name.endsWith(".zip") ||
            candidate.name.endsWith(DYNAMIC_ARCHIVE_SUFFIX)
        ) return false
        val deleted = !candidate.exists() || candidate.delete()
        if (deleted) dynamicArchiveFor(candidate).delete()
        return deleted
    }

    fun archiveFile(context: Context, variant: IconArchiveVariant, createDirectory: Boolean): File {
        val root = context.getExternalFilesDir(ARCHIVE_DIRECTORY)
            ?: throw IllegalStateException("外部应用文件目录不可用，无法创建待应用的主题归档")
        val directory = File(root, ARCHIVE_CACHE_DIRECTORY)
        if (createDirectory) {
            ensureArchiveDirectoryWritable(directory)
        } else if (!directory.exists()) {
            return File(directory, ".missing-cache")
        }
        return File(directory, "$ARCHIVE_FILE_PREFIX${cacheKey(variant)}.zip")
    }

    internal fun cacheKey(variant: IconArchiveVariant): String = archiveIdentity(variant)
        .let { identity ->
            MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
        }
        .take(20)

    private fun archiveIdentity(variant: IconArchiveVariant): String = buildString {
        append("format=").append(ICON_ARCHIVE_FORMAT_VERSION).append('|')
        append(variant.iconPackPackage)
        append('|').append("%.3f".format(Locale.ROOT, variant.fallbackScaleMultiplier))
        if (variant.globalMonetIcons) append("|render=").append(GLOBAL_MONET_RENDERING)
        if (variant.globalMonetIcons) {
            append("|palette_fingerprint=").append(variant.monetPaletteFingerprint)
        }
        if (variant.globalMonetIcons && variant.monetCustomColors) {
            append("|palette=custom")
            append("|background=").append(variant.monetBackgroundColor.toUInt().toString(16))
            append("|foreground=").append(variant.monetForegroundColor.toUInt().toString(16))
        }
        if (variant.applicationScopeFingerprint != IconArchiveFormat.ALL_APPLICATIONS_SCOPE) {
            append("|apps=").append(variant.applicationScopeFingerprint)
        }
    }

    fun applicationScopeFingerprint(context: Context): String {
        @Suppress("DEPRECATION")
        val normalized = context.packageManager
            .getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)
            .asSequence()
            .filter { it.packageName.isNotBlank() }
            .map { packageInfo ->
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                "${packageInfo.packageName}\t$versionCode\t${packageInfo.lastUpdateTime}"
            }
            .distinct()
            .sorted()
            .toList()
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
            .take(SCOPE_FINGERPRINT_LENGTH)
    }

    fun dynamicArchiveFor(iconArchive: File): File = File(
        iconArchive.parentFile,
        "${iconArchive.nameWithoutExtension}$DYNAMIC_ARCHIVE_SUFFIX",
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun archiveDirectory(context: Context, createDirectory: Boolean): File? {
        val root = context.getExternalFilesDir(ARCHIVE_DIRECTORY) ?: return null
        val directory = File(root, ARCHIVE_CACHE_DIRECTORY)
        if (createDirectory) ensureArchiveDirectoryWritable(directory)
        return directory.takeIf { it.exists() || createDirectory }
    }

    private fun legacyArchive(context: Context): File {
        val directory = context.getExternalFilesDir(ARCHIVE_DIRECTORY)
            ?: return File(context.filesDir, ".no-legacy-hypericonpack-icons")
        return File(directory, LEGACY_ARCHIVE_FILE_NAME)
    }

    private fun ensureArchiveDirectoryWritable(directory: File) {
        if (directory.exists() || directory.mkdirs()) {
            val probe = File(directory, ".write-probe")
            val writable = runCatching {
                probe.outputStream().use { it.write(0) }
                probe.delete()
                true
            }.getOrDefault(false)
            if (writable) return
        }

        val path = directory.absolutePath.replace("'", "'\\''")
        val result = RootAccess.runFixed(
            command = """
                set -e
                path='$path'
                app_uid="${'$'}(stat -c %u /data/user/0/io.github.cl0ura.hypericonpack 2>/dev/null || true)"
                [ -n "${'$'}app_uid" ]
                mkdir -p "${'$'}path"
                chown -R "${'$'}app_uid:ext_data_rw" "${'$'}(dirname "${'$'}(dirname "${'$'}path")")" 2>/dev/null || true
                chown -R "${'$'}app_uid:ext_data_rw" "${'$'}path"
                find "${'$'}path" -type d -exec chmod 2770 {} +
                find "${'$'}path" -type f -exec chmod 660 {} +
                chcon -R u:object_r:media_rw_data_file:s0 "${'$'}path" 2>/dev/null || true
                touch "${'$'}path/.write-probe"
                chown "${'$'}app_uid:ext_data_rw" "${'$'}path/.write-probe"
                chmod 660 "${'$'}path/.write-probe"
                rm -f "${'$'}path/.write-probe"
                echo 'HYPER_ICONPACK_ARCHIVE_DIR_OK'
            """.trimIndent(),
            timeoutSeconds = 20L,
        )
        if (!result.success || !result.output.contains("HYPER_ICONPACK_ARCHIVE_DIR_OK")) {
            throw IllegalStateException(
                "主题归档目录不可写：${directory.absolutePath}. ${result.output}".trim(),
            )
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建主题归档目录：${directory.absolutePath}")
        }
        val probe = File(directory, ".write-probe")
        runCatching {
            probe.outputStream().use { it.write(0) }
            probe.delete()
        }.getOrElse {
            throw IllegalStateException("主题归档目录仍不可写：${directory.absolutePath}", it)
        }
    }
}
