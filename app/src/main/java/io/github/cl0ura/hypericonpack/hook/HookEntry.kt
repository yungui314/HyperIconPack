package io.github.cl0ura.hypericonpack.hook

import android.content.ComponentName
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.cl0ura.hypericonpack.config.IconRemoteConfig
import io.github.cl0ura.hypericonpack.iconpack.ThemeAnimationOutlineDrawable
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API 102 launcher-only compatibility module.
 *
 * Icon replacement itself is exclusively handled by HyperOS' native
 * /data/system/theme/icons archive. This module does not replace icons in
 * Launcher, Settings, SystemUI, Security Center or the widget picker. The only
 * hooks retained here adapt Xiaomi's launch/return transition to the shape of
 * the already-themed icon shown by Launcher.
 */
class HookEntry : XposedModule() {
    private val installed = AtomicBoolean(false)
    private val animationStates = Collections.synchronizedMap(
        WeakHashMap<Any, AnimationTargetDrawable>(),
    )

    @Volatile
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log(
            Log.INFO,
            TAG,
            "Loaded in ${param.processName}; framework=$frameworkName/$frameworkVersion API $apiVersion",
        )
        if (param.processName != LAUNCHER_PACKAGE) {
            log(Log.INFO, TAG, "Detaching from non-launcher process ${param.processName}")
            detach()
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (processName != LAUNCHER_PACKAGE || param.packageName != LAUNCHER_PACKAGE) return
        installForLauncher(param.classLoader)
    }

    /** Old generation: release every callback and app-object reference. */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "Preparing API 102 hot reload")
        ThemeAnimationRuntime.shutdown()
        restoreAllAnimationTargets()
        installed.set(false)
        return true
    }

    /** New generation: lifecycle callbacks are not replayed, so rebuild explicitly. */
    override fun onHotReloaded(param: HotReloadedParam) {
        processName = param.processName
        param.oldHookHandles.forEach { handle ->
            runCatching { handle.unhook() }
        }
        if (param.processName != LAUNCHER_PACKAGE) {
            detach()
            return
        }
        val classLoader = currentApplicationClassLoader()
            ?: Thread.currentThread().contextClassLoader
        if (classLoader == null) {
            log(Log.ERROR, TAG, "Hot reload completed but Launcher classloader is unavailable")
            return
        }
        installForLauncher(classLoader)
        log(Log.INFO, TAG, "API 102 hot reload completed")
    }

    private fun installForLauncher(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            ThemeAnimationRuntime.initialize(
                getRemotePreferences(IconRemoteConfig.GROUP),
                ::logBridge,
            )
        }.onFailure { throwable ->
            log(Log.ERROR, TAG, "Unable to initialize API 102 remote preferences", throwable)
        }

        val shortcutInstalled = runCatching {
            installShortcutAnimationDrawableHook(classLoader)
            true
        }.getOrElse { throwable ->
            log(Log.ERROR, TAG, "Shortcut animation hook unavailable", throwable)
            false
        }

        val floatingInstalled = installFloatingIconAnimationHooks(classLoader)
        if (!shortcutInstalled && floatingInstalled == 0) {
            installed.set(false)
            ThemeAnimationRuntime.shutdown()
            log(Log.ERROR, TAG, "No compatible HyperOS launcher animation methods were found")
            return
        }
        log(
            Log.INFO,
            TAG,
            "Installed API 102 launcher animation bridge: floating=$floatingInstalled, shortcut=$shortcutInstalled",
        )
    }

    private fun installFloatingIconAnimationHooks(classLoader: ClassLoader): Int {
        val baseLauncher = findClass(BASE_LAUNCHER, classLoader)
        val animationTarget = findClass(ANIMATION_TARGET, classLoader)
        val surfaceControl = findClass(SURFACE_CONTROL_COMPAT, classLoader)
        val iconLayerType = findClass(ICON_LAYER_TYPE, classLoader)
        val layerAdaptiveIcon = findClass(LAYER_ADAPTIVE_ICON, classLoader)
        var installedCount = 0

        listOf(FLOATING_ICON_VIEW_2, FLOATING_ICON_LAYER_2).forEach { className ->
            runCatching {
                val floatingIcon = findClass(className, classLoader)
                val initMethod = floatingIcon.getDeclaredMethod(
                    "init",
                    baseLauncher,
                    animationTarget,
                    Drawable::class.java,
                    surfaceControl,
                    java.lang.Boolean.TYPE,
                    java.lang.Boolean.TYPE,
                    java.lang.Boolean.TYPE,
                    java.lang.Boolean.TYPE,
                    iconLayerType,
                    java.lang.Boolean.TYPE,
                    java.lang.Boolean.TYPE,
                ).apply { isAccessible = true }
                val recycleMethod = floatingIcon.getDeclaredMethod(
                    "recycle",
                    java.lang.Boolean.TYPE,
                ).apply { isAccessible = true }

                hook(initMethod).intercept { chain ->
                    val target = chain.args.getOrNull(1)
                    if (target == null) return@intercept chain.proceed()
                    clearAnimationTargetDrawable(target)
                    val displayedDrawable = invokeNoArgs(target, "getContentDrawable") as? Drawable
                    val animationDrawable = ThemeAnimationRuntime
                        .preferAnimationTargetDrawable(displayedDrawable)
                        ?: return@intercept chain.proceed()

                    val replacement = createLayerAdaptiveDrawable(
                        layerAdaptiveIcon,
                        animationDrawable,
                        animationComponent(target),
                    )
                    val arguments = chain.args.toTypedArray()
                    if (replacement != null) {
                        replaceAnimationTargetDrawable(target, replacement)
                        replacePersistentDrawableForInit(target, replacement)
                        arguments[2] = replacement
                    } else {
                        arguments[2] = if (
                            animationDrawable is AdaptiveIconDrawable && displayedDrawable != null
                        ) {
                            ThemeAnimationOutlineDrawable(displayedDrawable)
                        } else {
                            animationDrawable
                        }
                    }
                    try {
                        chain.proceed(arguments)
                    } finally {
                        restorePersistentDrawableAfterInit(target)
                    }
                }

                hook(recycleMethod).intercept { chain ->
                    chain.thisObject?.let(::animationTargetFromFloatingView)
                        ?.let(::clearAnimationTargetDrawable)
                    chain.proceed()
                }
                installedCount++
            }.onFailure { throwable ->
                log(Log.WARN, TAG, "$className animation methods unavailable", throwable)
            }
        }
        return installedCount
    }

    private fun installShortcutAnimationDrawableHook(classLoader: ClassLoader) {
        val shortcutIcon = findClass(SHORTCUT_ICON, classLoader)
        val method = shortcutIcon.getDeclaredMethod("getLayerAdaptiveIconDrawable")
            .apply { isAccessible = true }
        hook(method).intercept { chain ->
            val original = chain.proceed()
            val target = chain.thisObject ?: return@intercept original
            synchronized(animationStates) {
                animationStates[target]?.temporary ?: original
            }
        }
    }

    private fun createLayerAdaptiveDrawable(
        layerAdaptiveIcon: Class<*>,
        drawable: Drawable,
        componentName: ComponentName?,
    ): Drawable? {
        if (drawable !is AdaptiveIconDrawable || componentName == null) return null
        return runCatching {
            val constructor = layerAdaptiveIcon.declaredConstructors.firstOrNull { candidate ->
                candidate.parameterCount == 3 &&
                    candidate.parameterTypes[0].isAssignableFrom(drawable.javaClass) &&
                    ComponentName::class.java.isAssignableFrom(candidate.parameterTypes[2])
            } ?: return@runCatching null
            constructor.isAccessible = true
            constructor.newInstance(drawable, null, componentName) as? Drawable
        }.getOrNull()
    }

    private fun animationComponent(target: Any): ComponentName? = runCatching {
        val shortcutInfo = invokeNoArgs(target, "getTag") ?: return@runCatching null
        invokeNoArgs(shortcutInfo, "getComponentName") as? ComponentName
    }.getOrNull()

    private fun animationTargetFromFloatingView(floatingView: Any): Any? = runCatching {
        val reference = readField(floatingView, "mAnimTargetRef") as? WeakReference<*>
        reference?.get()
    }.getOrNull()

    private fun replaceAnimationTargetDrawable(target: Any, temporary: Drawable) {
        synchronized(animationStates) {
            animationStates[target] = AnimationTargetDrawable(temporary = temporary)
        }
    }

    private fun replacePersistentDrawableForInit(target: Any, temporary: Drawable) {
        synchronized(animationStates) {
            val state = animationStates[target] ?: return
            runCatching {
                val original = readField(target, SHORTCUT_ICON_DRAWABLE) as? Drawable
                animationStates[target] = state.copy(
                    originalPersistent = original,
                    persistentDrawableWasTemporarilyReplaced = true,
                )
                writeField(target, SHORTCUT_ICON_DRAWABLE, temporary)
            }
        }
    }

    private fun restorePersistentDrawableAfterInit(target: Any) {
        val state = synchronized(animationStates) { animationStates[target] } ?: return
        if (!state.persistentDrawableWasTemporarilyReplaced) return
        runCatching { writeField(target, SHORTCUT_ICON_DRAWABLE, state.originalPersistent) }
    }

    private fun clearAnimationTargetDrawable(target: Any) {
        restorePersistentDrawableAfterInit(target)
        synchronized(animationStates) { animationStates.remove(target) }
    }

    private fun restoreAllAnimationTargets() {
        val targets = synchronized(animationStates) { animationStates.keys.toList() }
        targets.forEach(::clearAnimationTargetDrawable)
        synchronized(animationStates) { animationStates.clear() }
    }

    private fun currentApplicationClassLoader(): ClassLoader? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThread.getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? android.app.Application
        currentApplication?.classLoader
    }.getOrNull()

    private fun invokeNoArgs(target: Any, methodName: String): Any? {
        val method = findMethod(target.javaClass, methodName)
        return method.invoke(target)
    }

    private fun readField(target: Any, fieldName: String): Any? =
        findField(target.javaClass, fieldName).get(target)

    private fun writeField(target: Any, fieldName: String, value: Any?) {
        findField(target.javaClass, fieldName).set(target, value)
    }

    private fun findMethod(type: Class<*>, methodName: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterCount == 0
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${type.name}#$methodName")
    }

    private fun findField(type: Class<*>, fieldName: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(fieldName) }.getOrNull()?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${type.name}#$fieldName")
    }

    private fun findClass(className: String, classLoader: ClassLoader): Class<*> =
        Class.forName(className, false, classLoader)

    private fun logBridge(message: String, throwable: Throwable?) {
        if (throwable == null) {
            log(Log.INFO, TAG, message)
        } else {
            log(Log.ERROR, TAG, message, throwable)
        }
    }

    private data class AnimationTargetDrawable(
        val temporary: Drawable,
        val originalPersistent: Drawable? = null,
        val persistentDrawableWasTemporarilyReplaced: Boolean = false,
    )

    private companion object {
        const val TAG = "HyperIconPack"
        const val LAUNCHER_PACKAGE = "com.miui.home"
        const val FLOATING_ICON_VIEW_2 = "com.miui.home.recents.views.FloatingIconView2"
        const val FLOATING_ICON_LAYER_2 = "com.miui.home.recents.views.FloatingIconLayer2"
        const val SHORTCUT_ICON = "com.miui.home.launcher.ShortcutIcon"
        const val BASE_LAUNCHER = "com.miui.home.launcher.BaseLauncher"
        const val ANIMATION_TARGET = "com.miui.home.common.animate.LaunchAppAndBackHomeAnimTarget"
        const val SURFACE_CONTROL_COMPAT = "com.android.systemui.shared.recents.system.SurfaceControlCompat"
        const val ICON_LAYER_TYPE = "com.miui.home.recents.IconLayerType"
        const val LAYER_ADAPTIVE_ICON = "com.miui.home.common.drawable.LayerAdaptiveIconDrawable"
        const val SHORTCUT_ICON_DRAWABLE = "mIconDrawable"
    }
}
