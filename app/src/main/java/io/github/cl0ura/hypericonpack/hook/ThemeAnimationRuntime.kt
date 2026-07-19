package io.github.cl0ura.hypericonpack.hook

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedBridge
import io.github.cl0ura.hypericonpack.config.IconConfigContract
import io.github.cl0ura.hypericonpack.config.IconPackConfig
import io.github.cl0ura.hypericonpack.iconpack.ThemeAnimationAdaptiveDrawable
import io.github.cl0ura.hypericonpack.iconpack.ThemeAnimationOutlineDrawable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only remaining launcher-process runtime behaviour.
 *
 * App icons themselves are resolved entirely by HyperOS from the generated
 * /data/system/theme/icons archive.  This class never loads an icon pack or
 * replaces a desktop/folder Drawable; it only supplies the displayed themed
 * Drawable to the vendor launch/return transition and publishes an alpha-
 * derived outline for every static PNG shape.
 */
internal object ThemeAnimationRuntime {
    private const val TAG = "HyperIconPack"

    private val lock = Any()
    private val bridgeLogged = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var config = IconPackConfig.disabled()

    private var observerRegistered = false
    private var retryAttempt = 0

    fun initialize(context: Context) {
        synchronized(lock) {
            if (observerRegistered) return
            observerRegistered = true
            reload(context)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = reload(context)

                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = reload(context)
            }
            runCatching {
                context.contentResolver.registerContentObserver(
                    IconConfigContract.CONFIG_URI,
                    false,
                    observer,
                )
            }.onFailure { throwable ->
                log("Unable to observe system-theme animation configuration", throwable)
            }
        }
    }

    fun preferAnimationTargetDrawable(targetDrawable: Drawable?): Drawable? {
        if (!shouldBridgeAnimation() || targetDrawable == null) return null
        if (bridgeLogged.compareAndSet(false, true)) {
            log("Theme animation bridge is using the displayed HyperOS icon with a shape-aware alpha outline")
        }
        return when (targetDrawable) {
            is AdaptiveIconDrawable,
            is ThemeAnimationOutlineDrawable,
            -> targetDrawable

            else -> ThemeAnimationAdaptiveDrawable.create(targetDrawable)
                ?: ThemeAnimationOutlineDrawable(targetDrawable)
        }
    }

    private fun reload(context: Context) {
        config = IconConfigContract.read(context) { throwable ->
            log("Unable to read system-theme animation configuration", throwable)
        }
        if (config.systemThemeActive && config.systemThemeAnimationBridge) {
            retryAttempt = 0
        }
        log(
            "System-theme animation configuration loaded: active=${config.systemThemeActive}, " +
                "bridge=${config.systemThemeAnimationBridge}, pack=${config.packageName ?: "none"}",
        )
        scheduleRetryIfNeeded(context)
    }

    /**
     * During an early boot, Launcher can be created before the companion
     * application's exported provider is ready.  A one-shot query would then
     * permanently disable the bridge until the user changed a setting.  Keep
     * a short bounded retry window; normal ContentObserver updates still win
     * immediately once the provider comes online.
     */
    private fun scheduleRetryIfNeeded(context: Context) {
        if (shouldBridgeAnimation() || retryAttempt >= RETRY_DELAYS_MILLIS.size) return
        val delay = RETRY_DELAYS_MILLIS[retryAttempt++]
        mainHandler.postDelayed({ reload(context) }, delay)
    }

    private fun shouldBridgeAnimation(): Boolean =
        config.systemThemeActive && config.systemThemeAnimationBridge && config.packageName != null

    private fun log(message: String, throwable: Throwable? = null) {
        XposedBridge.log(if (throwable == null) "$TAG: $message" else "$TAG: $message\n$throwable")
    }

    private val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L)
}
