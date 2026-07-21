package io.github.cl0ura.hypericonpack.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File

/**
 * API 102 module lifecycle entry.
 *
 * Icon replacement uses HyperOS' native theme archives.  The single runtime
 * hook here prevents system_server's periodic DRM validator from deleting
 * hand-installed icon archives that lack a Xiaomi-signed rights file.
 */
class HookEntry : XposedModule() {
    @Volatile
    private var systemServerClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "Loaded in ${param.processName}; native HyperOS theme mode; " +
                "framework=$frameworkName/$frameworkVersion API $apiVersion",
        )
        // system_server can also appear as process "system".  Installing here
        // covers both the first boot path and post-hot-reload module instances
        // that never see onSystemServerStarting again.
        if (param.processName == "system" || param.processName == "android") {
            val classLoader = resolveDrmClassLoader(preferred = null)
            if (classLoader != null) {
                systemServerClassLoader = classLoader
                installDrmBypass(classLoader)
            } else {
                log(
                    Log.WARN,
                    TAG,
                    "Loaded in ${param.processName} but DrmManager is unavailable; " +
                        "DRM bypass not installed yet",
                )
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        systemServerClassLoader = param.classLoader
        log(Log.INFO, TAG, "System server starting — installing DRM bypass")
        installDrmBypass(param.classLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "Preparing API 102 hot reload; releasing DRM bypass hooks")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        param.oldHookHandles.forEach { handle ->
            runCatching { handle.unhook() }
        }
        val classLoader = resolveDrmClassLoader(preferred = systemServerClassLoader)
        if (classLoader != null) {
            systemServerClassLoader = classLoader
            log(Log.INFO, TAG, "Re-installing DRM bypass after hot reload")
            installDrmBypass(classLoader)
        } else {
            log(
                Log.WARN,
                TAG,
                "No system server classloader for DRM bypass re-install; " +
                    "if icons disappear, reboot the device",
            )
        }
    }

    /**
     * HyperOS periodically validates every file under /data/system/theme/
     * against a DRM rights file.  Hand-installed archives lack a valid rights
     * file, so the validator would delete them.  Short-circuit only when this
     * app still owns the active icons module.
     */
    private fun installDrmBypass(classLoader: ClassLoader) {
        try {
            val drmManager = classLoader.loadClass("miui.drm.DrmManager")
            val drmResult = classLoader.loadClass("miui.drm.DrmManager\$DrmResult")
            val drmSuccess = drmResult.getField("DRM_SUCCESS").get(null)

            val targets = drmManager.declaredMethods
                .filter { method -> method.name == "isLegal" }
                .filter { method -> method.returnType == drmResult }

            if (targets.isEmpty()) {
                log(
                    Log.WARN,
                    TAG,
                    "No isLegal overloads found in DrmManager; DRM bypass not installed",
                )
                return
            }

            var hooksInstalled = 0
            for (method in targets) {
                hook(method).intercept { chain ->
                    val candidate = resolveCandidatePath(chain)
                    val managed = isManagedThemeMarkerPresent()
                    if (
                        DrmProtectionPolicy.shouldBypass(
                            contentPath = candidate,
                            managedThemeMarkerPresent = managed,
                        )
                    ) {
                        return@intercept drmSuccess
                    }
                    if (!managed && DrmProtectionPolicy.isProtectedThemePath(candidate)) {
                        log(
                            Log.WARN,
                            TAG,
                            "DRM checked $candidate but managed marker is unreadable; " +
                                "icons may be deleted. Check .runtime permissions/SELinux.",
                        )
                    }
                    chain.proceed()
                }
                hooksInstalled++
            }

            log(
                Log.INFO,
                TAG,
                "DRM bypass installed: $hooksInstalled isLegal overload(s) protected in system_server",
            )
        } catch (throwable: Throwable) {
            log(
                Log.ERROR,
                TAG,
                "Failed to install DRM bypass; icons may revert after periodic validation",
                throwable,
            )
        }
    }

    /**
     * Best-effort content path from [miui.drm.DrmManager.isLegal].
     *
     * ThemeReceiver always starts with (Context, File, rightsDir).  Hash-based
     * overloads only receive the SHA-1 and cannot be mapped back to a path, so
     * they fall through to the original implementation.
     */
    private fun resolveCandidatePath(chain: io.github.libxposed.api.XposedInterface.Chain): String {
        return when (val second = chain.args.getOrNull(1)) {
            is File -> runCatching { second.canonicalPath }.getOrElse { second.absolutePath }
            else -> ""
        }
    }

    private fun isManagedThemeMarkerPresent(): Boolean {
        return MANAGED_MARKER_CANDIDATES.any { path -> File(path).isFile }
    }

    private fun resolveDrmClassLoader(preferred: ClassLoader?): ClassLoader? {
        preferred?.let { classLoader ->
            if (runCatching { classLoader.loadClass("miui.drm.DrmManager") }.isSuccess) {
                return classLoader
            }
        }
        runCatching { Class.forName("miui.drm.DrmManager").classLoader }
            .getOrNull()
            ?.let { return it }
        val contextLoader = Thread.currentThread().contextClassLoader
        if (
            contextLoader != null &&
            runCatching { contextLoader.loadClass("miui.drm.DrmManager") }.isSuccess
        ) {
            return contextLoader
        }
        val ownLoader = javaClass.classLoader
        if (
            ownLoader != null &&
            runCatching { ownLoader.loadClass("miui.drm.DrmManager") }.isSuccess
        ) {
            return ownLoader
        }
        return null
    }

    private companion object {
        const val TAG = "HyperIconPack"

        // Prefer the DRM-whitelist .runtime location.  Keep the legacy marker
        // path as a one-release fallback so already-installed themes stay
        // protected until the next apply migrates the marker.
        private val MANAGED_MARKER_CANDIDATES = listOf(
            "/data/system/theme/.runtime/hypericonpack/icons.sha256",
            "/data/system/theme/.hypericonpack-icons.sha256",
        )
    }
}
