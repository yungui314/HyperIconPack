package io.github.cl0ura.hypericonpack.hook

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.cl0ura.hypericonpack.config.IconConfigContract
import io.github.cl0ura.hypericonpack.config.IconPackConfig

/**
 * Completes a private HyperOS icon-archive update through the same two signals
 * emitted by Theme Manager after it applies a theme:
 *
 * 1. MiuiConfiguration#sendThemeConfigurationChangeMsg increments the
 *    framework theme revision, which clears AppIconsHelper/IconCustomizer
 *    caches in the already-running launcher.
 * 2. ACTION_THEME_CHANGED informs listeners after that revision changed.
 *
 * A broadcast on its own is insufficient: it is only the notification half
 * of Theme Manager's transaction.  The framework revision is the part that
 * makes HyperOS reload the `icons` archive without force-stopping Launcher.
 */
internal object HyperOsThemeRefreshRuntime {
    private const val TAG = "HyperIconPack"
    private const val MIUI_CONFIGURATION = "android.content.res.MiuiConfiguration"
    private const val THEME_CHANGED_ACTION = "miui.intent.action.ACTION_THEME_CHANGED"

    // This is the exact mask Theme Manager forwards to
    // MiuiConfiguration#sendThemeConfigurationChangeMsg for a completed
    // theme transaction on the inspected HyperOS 3 build. It includes the
    // app-icon theme segment and causes the launcher Configuration diff to
    // carry the theme-changed bit it already handles natively.
    private const val THEME_CONFIGURATION_MASK = 0x10007899L

    private val lock = Any()
    private var observerRegistered = false
    private var lastConfig = IconPackConfig.disabled()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (observerRegistered) return
            observerRegistered = true
            lastConfig = readConfig(context)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = reloadAndRefresh(context)

                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = reloadAndRefresh(context)
            }
            runCatching {
                context.contentResolver.registerContentObserver(
                    IconConfigContract.CONFIG_URI,
                    false,
                    observer,
                )
            }.onFailure { throwable ->
                log("Unable to observe HyperOS theme-refresh configuration", throwable)
            }
        }
    }

    private fun reloadAndRefresh(context: Context) {
        val previous = synchronized(lock) { lastConfig }
        val current = readConfig(context)
        synchronized(lock) { lastConfig = current }
        val revisionChanged = current.revision != previous.revision
        if (!revisionChanged || (!previous.systemThemeActive && !current.systemThemeActive)) return

        runCatching {
            val configurationClass = XposedHelpers.findClass(MIUI_CONFIGURATION, context.classLoader)
            XposedHelpers.callStaticMethod(
                configurationClass,
                "sendThemeConfigurationChangeMsg",
                THEME_CONFIGURATION_MASK,
            )
            // Theme Manager sends this after the framework configuration
            // update. Keep the order; dispatching the broadcast first merely
            // tells Launcher to inspect its still-stale configuration.
            context.sendBroadcast(Intent(THEME_CHANGED_ACTION))
            log("Requested native HyperOS icon-theme cache refresh (revision=${current.revision})")
        }.onFailure { throwable ->
            log("Native HyperOS icon-theme cache refresh is unavailable", throwable)
        }
    }

    private fun readConfig(context: Context): IconPackConfig =
        IconConfigContract.read(context) { throwable ->
            log("Unable to read HyperOS theme-refresh configuration", throwable)
        }

    private fun log(message: String, throwable: Throwable? = null) {
        XposedBridge.log(if (throwable == null) "$TAG: $message" else "$TAG: $message\n$throwable")
    }
}
