package io.github.cl0ura.hypericonpack.hook

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import de.robv.android.xposed.XposedBridge
import io.github.cl0ura.hypericonpack.config.IconConfigContract
import io.github.cl0ura.hypericonpack.config.IconPackConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Shared, static-theme reader used only by the small SystemUI/Settings
 * bridges.  It never opens an icon pack and never generates a Drawable from
 * appfilter at runtime: every bitmap comes from the already-installed
 * HyperOS [ARCHIVE_PATH] archive.
 *
 * Some HyperOS surfaces (notably notification small-icon rendering and parts
 * of Settings) bypass IconCustomizer even though the launcher uses it.  They
 * can use this reader as a final presentation bridge while retaining one
 * source of truth: the generated system theme archive.
 */
internal object SystemThemeArchiveRuntime {
    private const val TAG = "HyperIconPack"
    private const val ARCHIVE_PATH = "/data/system/theme/icons"
    private const val ENTRY_DIRECTORY = "res/drawable-xxhdpi/"
    private const val MAX_CACHE_KB = 12 * 1024

    private val lock = Any()
    private val unavailableLogged = AtomicBoolean(false)
    private val liveArchiveMissingLogged = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconCache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    @Volatile
    private var config = IconPackConfig.disabled()

    @Volatile
    private var initialized = false

    private var observerRegistered = false
    private var archiveLength = -1L
    private var archiveModified = -1L
    // IconProvider returns a non-null *raw* drawable when the theme archive
    // has no resource for a new package.  Keep a lightweight package-entry
    // index so the launcher hook can recognise that case without opening the
    // ZIP once per desktop icon.
    private var packageEntryIndex: Set<String>? = null
    private var retryAttempt = 0

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            reload(context, reason = "initial")
            if (!observerRegistered) {
                observerRegistered = true
                val observer = object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) = reload(context, reason = "changed")

                    override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = reload(context, reason = "changed")
                }
                runCatching {
                    context.contentResolver.registerContentObserver(
                        IconConfigContract.CONFIG_URI,
                        false,
                        observer,
                    )
                }.onFailure { throwable ->
                    log("Unable to observe static-theme configuration", throwable)
                }
            }
        }
    }

    /** Returns a new Drawable backed by a cached, decoded package-level PNG. */
    fun loadPackageIcon(context: Context, packageName: String?): Drawable? {
        if (!isEnabled() || packageName.isNullOrBlank()) return null
        val archive = File(ARCHIVE_PATH)
        if (!archive.isFile || archive.length() <= 0L) {
            if (unavailableLogged.compareAndSet(false, true)) {
                log("Static theme archive is unavailable to ${context.packageName}; preserving original surface icon")
            }
            return LiveArchiveMissIconRuntime.loadMissingPackageIcon(context, packageName)
        }
        unavailableLogged.set(false)

        val cacheKey = synchronized(lock) {
            refreshArchiveSignatureLocked(archive)
            "${archiveModified}:${archiveLength}:$packageName"
        }
        synchronized(lock) {
            iconCache.get(cacheKey)
        }?.let { bitmap ->
            return BitmapDrawable(context.resources, bitmap)
        }

        val entryName = "$ENTRY_DIRECTORY$packageName.png"
        val decoded = runCatching {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@use null
                zip.getInputStream(entry).use(BitmapFactory::decodeStream)
            }
        }.getOrNull() ?: return LiveArchiveMissIconRuntime.loadMissingPackageIcon(context, packageName)

        synchronized(lock) {
            // An install may have atomically replaced icons while this PNG was
            // decoding.  Re-check before caching so a stale bitmap cannot
            // survive the next lookup.
            refreshArchiveSignatureLocked(archive)
            iconCache.put("${archiveModified}:${archiveLength}:$packageName", decoded)
        }
        return BitmapDrawable(context.resources, decoded)
    }

    /**
     * Returns an immediate generated icon only when the active static archive
     * has no package-level resource for [packageName].  This is deliberately
     * separate from [loadPackageIcon]: normal, already-converted archive
     * entries must remain on HyperOS's native static-theme route.
     *
     * HyperOS's IconProvider often returns a non-null original application
     * icon for a ZIP miss, so checking `param.result == null` in the hook is
     * insufficient for newly installed applications.
     */
    fun loadArchiveMissPackageIcon(context: Context, packageName: String?): Drawable? {
        if (!isEnabled() || packageName.isNullOrBlank()) return null
        val archive = File(ARCHIVE_PATH)
        if (!archive.isFile || archive.length() <= 0L) {
            if (liveArchiveMissingLogged.compareAndSet(false, true)) {
                log("Static archive is missing; switching launcher requests to the companion icon provider")
            }
            return LiveArchiveMissIconRuntime.loadMissingPackageIcon(context, packageName)
        }
        liveArchiveMissingLogged.set(false)
        val missing = synchronized(lock) {
            refreshArchiveSignatureLocked(archive)
            !hasPackageEntryLocked(archive, packageName)
        }
        return if (missing) {
            LiveArchiveMissIconRuntime.loadMissingPackageIcon(context, packageName)
        } else {
            null
        }
    }

    private fun reload(context: Context, reason: String) {
        config = IconConfigContract.read(context) { throwable ->
            log("Unable to read static-theme configuration", throwable)
        }
        if (config.systemThemeActive) retryAttempt = 0
        log("Static-theme configuration loaded ($reason): active=${config.systemThemeActive}")
        scheduleRetryIfNeeded(context)
    }

    private fun scheduleRetryIfNeeded(context: Context) {
        if (config.systemThemeActive || retryAttempt >= RETRY_DELAYS_MILLIS.size) return
        val delay = RETRY_DELAYS_MILLIS[retryAttempt++]
        mainHandler.postDelayed(
            { reload(context, reason = "retry-$retryAttempt") },
            delay,
        )
    }

    private fun refreshArchiveSignatureLocked(archive: File) {
        val length = archive.length()
        val modified = archive.lastModified()
        if (length != archiveLength || modified != archiveModified) {
            archiveLength = length
            archiveModified = modified
            iconCache.evictAll()
            packageEntryIndex = null
        }
    }

    /** Must be called while [lock] is held and after [refreshArchiveSignatureLocked]. */
    private fun hasPackageEntryLocked(archive: File, packageName: String): Boolean {
        val entries = packageEntryIndex ?: runCatching {
            ZipFile(archive).use { zip ->
                buildSet {
                    zip.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith(ENTRY_DIRECTORY) &&
                                entry.name.endsWith(".png")
                        }
                        .forEach { entry -> add(entry.name.removePrefix(ENTRY_DIRECTORY).removeSuffix(".png")) }
                }
            }
        }.getOrElse { emptySet() }.also { packageEntryIndex = it }
        return packageName in entries
    }

    private fun isEnabled(): Boolean = config.systemThemeActive && config.packageName != null

    private fun log(message: String, throwable: Throwable? = null) {
        XposedBridge.log(if (throwable == null) "$TAG: $message" else "$TAG: $message\n$throwable")
    }

    private val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L)
}
