package io.github.cl0ura.hypericonpack.hook

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.cl0ura.hypericonpack.iconpack.ThemeAnimationOutlineDrawable
import java.lang.ref.WeakReference

/**
 * Xposed entry point for the static HyperOS-theme architecture.
 *
 * The launcher still obtains ordinary icons from /data/system/theme/icons.
 * The hooks below are deliberately narrow presentation bridges for the few
 * system surfaces that bypass IconCustomizer: launcher transitions,
 * Settings' package-manager path, and notification small-icon rendering.
 * No appfilter resource is loaded in any injected process.
 */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            LAUNCHER_PACKAGE -> if (lpparam.processName == LAUNCHER_PACKAGE) {
                installApplicationAttachHook { context ->
                    ThemeAnimationRuntime.initialize(context)
                    HyperOsThemeRefreshRuntime.initialize(context)
                    // This remains a static-theme architecture. The reader is
                    // only an emergency presentation bridge for the rare OEM
                    // cache miss where IconCustomizer returns null even though
                    // /data/system/theme/icons is still valid on disk.
                    SystemThemeArchiveRuntime.initialize(context)
                }
                try {
                    XposedBridge.log("$TAG: loading launcher transition bridge")
                    installStaticArchiveFallbackBridge(lpparam)
                    installShortcutAnimationDrawableHook(lpparam)
                    installFloatingIconAnimationHook(lpparam)
                } catch (throwable: Throwable) {
                    // A theme archive remains usable even when a vendor
                    // launcher update changes an animation-only signature.
                    XposedBridge.log("$TAG: launcher transition bridge unavailable\n$throwable")
                }
            }

            SYSTEM_UI_PACKAGE -> {
                installApplicationAttachHook { context -> SystemThemeArchiveRuntime.initialize(context) }
                installSystemUiIconBridge(lpparam)
            }

            SETTINGS_PACKAGE,
            SECURITY_CENTER_PACKAGE,
            MI_SETTINGS_PACKAGE,
            -> {
                installApplicationAttachHook { context -> SystemThemeArchiveRuntime.initialize(context) }
                installPackageManagerIconBridge(lpparam)
            }
        }
    }

    private fun installApplicationAttachHook(onAttached: (Context) -> Unit) {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.args[0] as? Context)?.let(onAttached)
                }
            },
        )
    }

    private fun installSystemUiIconBridge(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val iconView = XposedHelpers.findClass(STATUS_BAR_ICON_VIEW, lpparam.classLoader)
            val statusBarIcon = XposedHelpers.findClass(STATUS_BAR_ICON, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                iconView,
                "getIcon",
                statusBarIcon,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val packageName = themeableNotificationPackage(view) ?: return
                        SystemThemeArchiveRuntime.loadPackageIcon(view.context, packageName)?.let { themed ->
                            // StatusBarIconView continues to apply its own
                            // system tint/contrast behaviour to this Drawable.
                            // Wi-Fi, signal and battery slots have no
                            // StatusBarNotification, so they remain untouched.
                            param.result = themed
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: installed static-theme SystemUI notification icon bridge")
        }.onFailure { throwable ->
            XposedBridge.log("$TAG: SystemUI notification icon bridge unavailable\n$throwable")
        }
    }

    /**
     * HyperOS normally reads the generated ZIP through IconCustomizer.  After
     * a framework theme-cache eviction some builds transiently return null
     * from this private helper although the archive itself remains intact,
     * making the whole desktop appear to revert to original icons.  Supply
     * the already-baked package resource only for that null result.  A normal
     * ThemeResourcesSystem result is never touched, so this is not a return
     * to the former live appfilter/Drawable replacement design.
     */
    private fun installStaticArchiveFallbackBridge(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val iconProvider = XposedHelpers.findClass(ICON_PROVIDER, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                iconProvider,
                "getCustomizedIcon",
                Context::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE,
                ApplicationInfo::class.java,
                java.lang.Boolean.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.getOrNull(0) as? Context ?: return
                        val packageName = param.args.getOrNull(1) as? String ?: return
                        if (param.result == null) {
                            // The native static archive should remain the
                            // normal source of truth. This path only covers a
                            // genuine null return after a framework cache miss.
                            SystemThemeArchiveRuntime.loadPackageIcon(context, packageName)?.let { themed ->
                                param.result = themed
                            }
                        } else {
                            // A newly installed app is commonly returned as a
                            // non-null *original* icon, even though its package
                            // has no entry in /data/system/theme/icons. Detect
                            // precisely that ZIP miss and bridge just that one
                            // icon until the incremental archive worker writes
                            // it durably. Existing static entries are never
                            // replaced here.
                            SystemThemeArchiveRuntime.loadArchiveMissPackageIcon(context, packageName)?.let { themed ->
                                param.result = themed
                            }
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: installed static-theme launcher cache-miss bridge")

            // HyperOS normally reaches getCustomizedIcon while populating
            // IconCache, but an existing launcher database entry and some
            // package-added paths call getActivityIcon without re-entering
            // that private helper. Hook the stable public vendor method too.
            // The runtime checks the ZIP index first, so this only replaces a
            // result when the complete archive is absent or the newly
            // installed package has no static entry yet.
            XposedHelpers.findAndHookMethod(
                iconProvider,
                "getActivityIcon",
                LauncherActivityInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.args.firstOrNull() as? LauncherActivityInfo ?: return
                        val packageName = info.componentName?.packageName ?: return
                        val context = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        }.getOrNull() ?: return
                        SystemThemeArchiveRuntime.loadArchiveMissPackageIcon(context, packageName)?.let { themed ->
                            param.result = themed
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: installed provider-backed launcher activity icon bridge")
        }.onFailure { throwable ->
            // The archive is still handled natively if a launcher update
            // changes this private method's signature.
            XposedBridge.log("$TAG: launcher cache-miss bridge unavailable\n$throwable")
        }
    }

    /**
     * Notification small icons communicate an operation, not an app's launcher
     * identity. Replacing a long-running system/foreground notification with
     * an arbitrary full-colour package icon makes it collapse into a tinted
     * white blob. LSPosed's persistent “loaded” notification is posted by
     * package `android`, so this is the exact path that produced the reported
     * white dot. Keep those semantic icons intact and only bridge ordinary
     * application notifications.
     */
    private fun themeableNotificationPackage(view: View): String? = runCatching {
        val isNotification = XposedHelpers.callMethod(view, "isNotification") as? Boolean ?: false
        if (!isNotification) return@runCatching null
        val notification = XposedHelpers.callMethod(view, "getNotification") as? StatusBarNotification
        val source = notification ?: return@runCatching null
        val flags = source.notification.flags
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return@runCatching null
        source.packageName
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it in STATUS_ICON_SYSTEM_PACKAGES }
    }.getOrNull()

    private fun installPackageManagerIconBridge(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val packageManager = XposedHelpers.findClass(APPLICATION_PACKAGE_MANAGER, lpparam.classLoader)
            val drawableHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val packageName = when (val argument = param.args.firstOrNull()) {
                        is String -> argument
                        is ApplicationInfo -> argument.packageName
                        is ComponentName -> argument.packageName
                        else -> null
                    } ?: return
                    val context = runCatching {
                        XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                    }.getOrNull() ?: return
                    SystemThemeArchiveRuntime.loadPackageIcon(context, packageName)?.let { themed ->
                        param.result = themed
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                packageManager,
                "getApplicationIcon",
                String::class.java,
                drawableHook,
            )
            XposedHelpers.findAndHookMethod(
                packageManager,
                "getApplicationIcon",
                ApplicationInfo::class.java,
                drawableHook,
            )
            XposedHelpers.findAndHookMethod(
                packageManager,
                "getActivityIcon",
                ComponentName::class.java,
                drawableHook,
            )
            XposedBridge.log("$TAG: installed static-theme package icon bridge for ${lpparam.packageName}")
        }.onFailure { throwable ->
            XposedBridge.log("$TAG: package icon bridge unavailable for ${lpparam.packageName}\n$throwable")
        }
    }

    private fun installFloatingIconAnimationHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val baseLauncher = XposedHelpers.findClass(BASE_LAUNCHER, lpparam.classLoader)
        val animationTarget = XposedHelpers.findClass(ANIMATION_TARGET, lpparam.classLoader)
        val surfaceControl = XposedHelpers.findClass(SURFACE_CONTROL_COMPAT, lpparam.classLoader)
        val iconLayerType = XposedHelpers.findClass(ICON_LAYER_TYPE, lpparam.classLoader)
        val layerAdaptiveIcon = XposedHelpers.findClass(LAYER_ADAPTIVE_ICON, lpparam.classLoader)
        val bridgeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val target = param.args.getOrNull(1) ?: return
                // A previous transition can be cancelled by a gesture. Never
                // keep a stale animation-only object for the next transition.
                // This also restores mIconDrawable if an OEM exception aborted
                // the previous init call before its after-hook could run.
                clearAnimationTargetDrawable(target)
                val displayedDrawable = runCatching {
                    XposedHelpers.callMethod(target, "getContentDrawable") as? Drawable
                }.getOrNull()
                ThemeAnimationRuntime.preferAnimationTargetDrawable(displayedDrawable)?.let { drawable ->
                    // FloatingIconView2's adaptive branch explicitly requires
                    // Xiaomi's LayerAdaptiveIconDrawable, not a platform
                    // AdaptiveIconDrawable.
                    val layeredDrawable = if (drawable is AdaptiveIconDrawable) {
                        animationComponent(target)?.let { component ->
                            runCatching {
                                XposedHelpers.newInstance(
                                    layerAdaptiveIcon,
                                    drawable,
                                    null,
                                    component,
                                ) as? Drawable
                            }.getOrNull()
                        }
                    } else {
                        null
                    }
                    if (layeredDrawable != null) {
                        // HyperOS reads both init()'s Drawable argument and
                        // ShortcutIcon.mIconDrawable while it decides whether
                        // to construct the native adaptive transition. Passing
                        // only the argument is insufficient on HyperOS 3 and
                        // makes the icon briefly use the legacy rectangular
                        // animation.  The field is therefore replaced only
                        // during this synchronous init invocation.  Its old
                        // themed PNG is restored in afterHookedMethod below;
                        // keeping the Layer object there after init is exactly
                        // what caused the old persistent black square bug.
                        replaceAnimationTargetDrawable(target, layeredDrawable)
                        replacePersistentDrawableForInit(target, layeredDrawable)
                        // Folder previews do not expose ShortcutIcon's
                        // mIconDrawable field, but can still use the correct
                        // LayerAdaptiveIconDrawable for their opening path.
                        param.args[2] = layeredDrawable
                    } else {
                        param.args[2] = if (drawable is AdaptiveIconDrawable && displayedDrawable != null) {
                            ThemeAnimationOutlineDrawable(displayedDrawable)
                        } else {
                            drawable
                        }
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                // FloatingIconView2 has copied the Drawable and calculated
                // mIsAdaptiveIcon by this point. Restore the desktop field
                // before launcher rendering resumes, while the temporary
                // Layer remains available through getLayerAdaptiveIconDrawable
                // for the subsequent return animation.
                param.args.getOrNull(1)?.let(::restorePersistentDrawableAfterInit)
            }
        }
        var installed = 0
        listOf(FLOATING_ICON_VIEW_2, FLOATING_ICON_LAYER_2).forEach { className ->
            try {
                val floatingIcon = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    floatingIcon,
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
                    bridgeHook,
                )
                // recycle() marks the end of both an opening-only animation
                // and a full launch/return animation. Restoring here keeps
                // the desktop's persistent Drawable as the original PNG.
                XposedHelpers.findAndHookMethod(
                    floatingIcon,
                    "recycle",
                    java.lang.Boolean.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            animationTargetFromFloatingView(param.thisObject)?.let(::clearAnimationTargetDrawable)
                        }
                    },
                )
                installed++
            } catch (throwable: Throwable) {
                XposedBridge.log("$TAG: $className animation bridge unavailable\n$throwable")
            }
        }
        check(installed > 0) { "No compatible HyperOS floating-icon class was found" }
        XposedBridge.log("$TAG: installed launcher transition bridge for $installed floating-icon class(es)")
    }

    /**
     * FloatingIconView2's return path calls ShortcutIcon#getLayerAdaptiveIconDrawable.
     * Supplying the object through this method keeps the animation adaptive
     * without ever changing ShortcutIcon.mIconDrawable, which is the drawable
     * that stays on the desktop after the animation has completed.
     */
    private fun installShortcutAnimationDrawableHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val shortcutIcon = XposedHelpers.findClass(SHORTCUT_ICON, lpparam.classLoader)
        XposedHelpers.findAndHookMethod(
            shortcutIcon,
            "getLayerAdaptiveIconDrawable",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val state = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject,
                        TEMPORARY_ANIMATION_DRAWABLE,
                    ) as? AnimationTargetDrawable ?: return
                    param.result = state.temporary
                }
            },
        )
    }

    private fun animationComponent(target: Any): ComponentName? = runCatching {
        val shortcutInfo = XposedHelpers.callMethod(target, "getTag") ?: return@runCatching null
        XposedHelpers.callMethod(shortcutInfo, "getComponentName") as? ComponentName
    }.getOrNull()

    private fun animationTargetFromFloatingView(floatingView: Any): Any? = runCatching {
        val reference = XposedHelpers.getObjectField(floatingView, "mAnimTargetRef") as? WeakReference<*>
        reference?.get()
    }.getOrNull()

    private fun replaceAnimationTargetDrawable(target: Any, temporary: Drawable): Boolean = runCatching {
        XposedHelpers.setAdditionalInstanceField(
            target,
            TEMPORARY_ANIMATION_DRAWABLE,
            AnimationTargetDrawable(temporary = temporary),
        )
        true
    }.getOrDefault(false)

    /**
     * `LaunchAppAndBackHomeAnimTarget` is normally a ShortcutIcon.  Folder and
     * widget targets are not, so probing the private field must remain best
     * effort.  A nullable old value is meaningful and is therefore retained.
     */
    private fun replacePersistentDrawableForInit(target: Any, temporary: Drawable) {
        val state = XposedHelpers.getAdditionalInstanceField(
            target,
            TEMPORARY_ANIMATION_DRAWABLE,
        ) as? AnimationTargetDrawable ?: return
        runCatching {
            val original = XposedHelpers.getObjectField(target, SHORTCUT_ICON_DRAWABLE) as? Drawable
            XposedHelpers.setAdditionalInstanceField(
                target,
                TEMPORARY_ANIMATION_DRAWABLE,
                state.copy(
                    originalPersistent = original,
                    persistentDrawableWasTemporarilyReplaced = true,
                ),
            )
            XposedHelpers.setObjectField(target, SHORTCUT_ICON_DRAWABLE, temporary)
        }
    }

    private fun restorePersistentDrawableAfterInit(target: Any) {
        val state = XposedHelpers.getAdditionalInstanceField(
            target,
            TEMPORARY_ANIMATION_DRAWABLE,
        ) as? AnimationTargetDrawable ?: return
        if (!state.persistentDrawableWasTemporarilyReplaced) return
        runCatching {
            XposedHelpers.setObjectField(target, SHORTCUT_ICON_DRAWABLE, state.originalPersistent)
        }
    }

    private fun clearAnimationTargetDrawable(target: Any) {
        // A cancellation can skip FloatingIconView2.init's after hook.  Always
        // restore before dropping state so the launcher never retains a Layer
        // as the icon it draws on the desktop.
        restorePersistentDrawableAfterInit(target)
        XposedHelpers.removeAdditionalInstanceField(target, TEMPORARY_ANIMATION_DRAWABLE)
    }

    private data class AnimationTargetDrawable(
        val temporary: Drawable,
        val originalPersistent: Drawable? = null,
        val persistentDrawableWasTemporarilyReplaced: Boolean = false,
    )

    private companion object {
        const val TAG = "HyperIconPack"
        const val LAUNCHER_PACKAGE = "com.miui.home"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
        const val MI_SETTINGS_PACKAGE = "com.xiaomi.misettings"

        const val STATUS_BAR_ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"
        const val STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon"
        const val APPLICATION_PACKAGE_MANAGER = "android.app.ApplicationPackageManager"
        const val ICON_PROVIDER = "com.miui.home.icon.IconProvider"

        const val FLOATING_ICON_VIEW_2 = "com.miui.home.recents.views.FloatingIconView2"
        const val FLOATING_ICON_LAYER_2 = "com.miui.home.recents.views.FloatingIconLayer2"
        const val SHORTCUT_ICON = "com.miui.home.launcher.ShortcutIcon"
        const val BASE_LAUNCHER = "com.miui.home.launcher.BaseLauncher"
        const val ANIMATION_TARGET = "com.miui.home.common.animate.LaunchAppAndBackHomeAnimTarget"
        const val SURFACE_CONTROL_COMPAT = "com.android.systemui.shared.recents.system.SurfaceControlCompat"
        const val ICON_LAYER_TYPE = "com.miui.home.recents.IconLayerType"
        const val LAYER_ADAPTIVE_ICON = "com.miui.home.common.drawable.LayerAdaptiveIconDrawable"
        const val TEMPORARY_ANIMATION_DRAWABLE = "hyper_icon_pack.temporary_animation_drawable"
        const val SHORTCUT_ICON_DRAWABLE = "mIconDrawable"

        val STATUS_ICON_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
        )
    }
}
