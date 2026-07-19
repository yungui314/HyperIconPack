package io.github.cl0ura.hypericonpack.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import de.robv.android.xposed.XposedBridge
import io.github.cl0ura.hypericonpack.config.IconConfigContract

/**
 * Cross-process live fallback used when HyperOS's private icon archive is
 * missing or does not yet contain a newly installed package.
 *
 * The hooked process intentionally does not open the icon-pack APK. Xiaomi's
 * launcher and several system processes cannot reliably see arbitrary icon
 * packs through PackageManager. The companion provider renders/serves the PNG
 * in its own UID and this runtime only decodes the result.
 */
internal object LiveArchiveMissIconRuntime {
    private const val TAG = "HyperIconPack"
    private const val MAX_CACHE_KB = 12 * 1024

    private val lock = Any()
    private val iconCache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun loadMissingPackageIcon(context: Context, packageName: String?): Drawable? {
        val targetPackage = packageName?.takeIf(String::isNotBlank) ?: return null
        val config = IconConfigContract.read(context) { throwable ->
            log("Unable to read live provider configuration", throwable)
        }
        if (!config.systemThemeActive || config.packageName == null) return null
        val key = "${config.revision}|$targetPackage"
        synchronized(lock) { iconCache.get(key) }?.let { bitmap ->
            return BitmapDrawable(context.resources, bitmap)
        }

        val bitmap = runCatching {
            context.contentResolver.openInputStream(
                IconConfigContract.iconUri(targetPackage, config.revision),
            )?.use(BitmapFactory::decodeStream)
        }.getOrElse { throwable ->
            log("Unable to obtain provider icon for $targetPackage", throwable)
            null
        } ?: return null

        synchronized(lock) { iconCache.put(key, bitmap) }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun log(message: String, throwable: Throwable? = null) {
        XposedBridge.log(if (throwable == null) "$TAG: $message" else "$TAG: $message\n$throwable")
    }
}
