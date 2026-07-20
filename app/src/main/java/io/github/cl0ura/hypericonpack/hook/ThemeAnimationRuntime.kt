package io.github.cl0ura.hypericonpack.hook

import android.content.SharedPreferences
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import io.github.cl0ura.hypericonpack.config.IconPackConfig
import io.github.cl0ura.hypericonpack.config.IconRemoteConfig
import io.github.cl0ura.hypericonpack.iconpack.ThemeAnimationAdaptiveDrawable
import io.github.cl0ura.hypericonpack.iconpack.ThemeAnimationOutlineDrawable
import java.util.concurrent.atomic.AtomicBoolean

/** Launcher-only animation state backed by libxposed remote preferences. */
internal object ThemeAnimationRuntime {
    private val lock = Any()
    private val bridgeLogged = AtomicBoolean(false)

    @Volatile
    private var config = IconPackConfig.disabled()

    private var preferences: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var logger: ((String, Throwable?) -> Unit)? = null

    fun initialize(
        remotePreferences: SharedPreferences,
        logger: (String, Throwable?) -> Unit,
    ) {
        synchronized(lock) {
            shutdownLocked()
            this.preferences = remotePreferences
            this.logger = logger
            val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
                // The companion writes revision last in one Editor transaction.
                // Reload once per complete configuration instead of once for
                // every individual key callback.
                if (key == IconRemoteConfig.KEY_REVISION) reload(preferences)
            }
            listener = changeListener
            remotePreferences.registerOnSharedPreferenceChangeListener(changeListener)
            reload(remotePreferences)
        }
    }

    fun shutdown() {
        synchronized(lock) { shutdownLocked() }
    }

    fun preferAnimationTargetDrawable(targetDrawable: Drawable?): Drawable? {
        if (!shouldBridgeAnimation() || targetDrawable == null) return null
        if (bridgeLogged.compareAndSet(false, true)) {
            log("Animation bridge is using the native HyperOS themed icon and its alpha outline")
        }
        return when (targetDrawable) {
            is AdaptiveIconDrawable,
            is ThemeAnimationOutlineDrawable,
            -> targetDrawable

            else -> ThemeAnimationAdaptiveDrawable.create(targetDrawable)
                ?: ThemeAnimationOutlineDrawable(targetDrawable)
        }
    }

    private fun reload(preferences: SharedPreferences) {
        config = runCatching { IconRemoteConfig.read(preferences) }
            .getOrElse { throwable ->
                log("Unable to read API 102 remote animation configuration", throwable)
                IconPackConfig.disabled()
            }
        bridgeLogged.set(false)
        log(
            "Remote animation configuration loaded: active=${config.systemThemeActive}, " +
                "bridge=${config.systemThemeAnimationBridge}, pack=${config.packageName ?: "none"}",
        )
    }

    private fun shutdownLocked() {
        val oldPreferences = preferences
        val oldListener = listener
        if (oldPreferences != null && oldListener != null) {
            runCatching { oldPreferences.unregisterOnSharedPreferenceChangeListener(oldListener) }
        }
        preferences = null
        listener = null
        logger = null
        config = IconPackConfig.disabled()
        bridgeLogged.set(false)
    }

    private fun shouldBridgeAnimation(): Boolean =
        config.systemThemeActive && config.systemThemeAnimationBridge && config.packageName != null

    private fun log(message: String, throwable: Throwable? = null) {
        logger?.invoke(message, throwable)
    }
}
