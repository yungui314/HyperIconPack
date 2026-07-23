package io.github.cl0ura.hypericonpack.xposed

import android.content.Context
import io.github.cl0ura.hypericonpack.config.IconPackConfig
import io.github.cl0ura.hypericonpack.config.IconRemoteConfig
import io.github.cl0ura.hypericonpack.logging.AppLog
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Companion-app connection to the modern libxposed service. */
object XposedServiceBridge : XposedServiceHelper.OnServiceListener {
    data class State(
        val connected: Boolean = false,
        val frameworkName: String? = null,
        val frameworkVersion: String? = null,
        val apiVersion: Int = 0,
        val scope: Set<String> = emptySet(),
        val targets: List<HookedTarget> = emptyList(),
        val error: String? = null,
    ) {
        val api102Ready: Boolean get() = connected && apiVersion >= XposedService.API_102

        val label: String
            get() = when {
                api102Ready -> "${frameworkName ?: "Xposed"} · API $apiVersion"
                connected -> "API $apiVersion 不兼容"
                error != null -> "连接失败"
                else -> "未连接"
            }

        /** Whether LSPosed has granted the synthetic system_server scope. */
        val systemScopeGranted: Boolean
            get() = scope.any { it == "system" || it == "android" }

        /** Whether the module is currently loaded into system_server. */
        val systemTargetRunning: Boolean
            get() = targets.any { target ->
                val process = target.processName
                process == "system" || process == "system_server" || process == "android"
            }

        /**
         * DRM bypass only works when the module is live inside system_server.
         * Scope alone is not enough before the first reboot / after a failed
         * hot reload.
         */
        val drmProtectionReady: Boolean
            get() = api102Ready && systemScopeGranted && systemTargetRunning

        val drmProtectionLabel: String
            get() = when {
                !connected -> "未连接"
                !api102Ready -> "API 不兼容"
                !systemScopeGranted -> "缺少 system 作用域"
                !systemTargetRunning -> "需重启生效"
                else -> "已保护"
            }
    }

    private val initialized = AtomicBoolean(false)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var latestConfig: IconPackConfig = IconPackConfig.disabled()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context, config: IconPackConfig) {
        appContext = context.applicationContext
        latestConfig = config
        if (initialized.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(this)
        }
        service?.let { publishToService(it, config) }
    }

    fun publishConfig(config: IconPackConfig) {
        latestConfig = config
        service?.let { publishToService(it, config) }
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        var current = readState(service)
        val missingScope = STATIC_SCOPE - current.scope
        if (missingScope.isNotEmpty()) {
            runCatching { service.requestScope(missingScope.sorted(), SCOPE_LISTENER) }
                .onSuccess {
                    appContext?.let { context ->
                        AppLog.info(context, "Requested Xposed scope: ${missingScope.sorted()}; reboot required")
                    }
                }
                .onFailure { throwable ->
                    appContext?.let { context ->
                        AppLog.warning(
                            context,
                            "Unable to request Xposed scope ${missingScope.sorted()}: " +
                                (throwable.message ?: throwable.javaClass.simpleName),
                        )
                    }
                }
        }
        val obsoleteScope = current.scope - STATIC_SCOPE
        if (obsoleteScope.isNotEmpty()) {
            runCatching { service.removeScope(obsoleteScope.sorted()) }
                .onSuccess {
                    appContext?.let { context ->
                        AppLog.info(context, "Removed legacy Xposed scope: ${obsoleteScope.sorted()}")
                    }
                    current = readState(service)
                }
                .onFailure { throwable ->
                    appContext?.let { context ->
                        AppLog.warning(
                            context,
                            "Unable to remove legacy Xposed scope: " +
                                (throwable.message ?: throwable.javaClass.simpleName),
                        )
                    }
                }
        }
        _state.value = current
        appContext?.let { context ->
            AppLog.info(
                context,
                "Xposed service connected: ${current.frameworkName ?: "unknown"} " +
                    "${current.frameworkVersion ?: ""}; API ${current.apiVersion}; " +
                    "scope=${current.scope.sorted()}; targets=${current.targets.map { it.processName }}",
            )
        }
        publishToService(service, latestConfig)
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) {
            this.service = null
            _state.value = State(error = "Xposed service disconnected")
            appContext?.let { AppLog.warning(it, "Xposed service disconnected") }
        }
    }

    private fun readState(service: XposedService): State = runCatching {
        val api = service.apiVersion
        State(
            connected = true,
            frameworkName = service.frameworkName,
            frameworkVersion = service.frameworkVersion,
            apiVersion = api,
            scope = service.scope.toSet(),
            targets = if (api >= XposedService.API_102) service.runningTargets else emptyList(),
        )
    }.getOrElse { throwable ->
        State(error = throwable.message ?: throwable.javaClass.simpleName)
    }

    private fun publishToService(service: XposedService, config: IconPackConfig) {
        runCatching {
            val preferences = service.getRemotePreferences(IconRemoteConfig.GROUP)
            preferences.edit()
                .apply {
                    config.packageName?.let { putString(IconRemoteConfig.KEY_PACKAGE_NAME, it) }
                        ?: remove(IconRemoteConfig.KEY_PACKAGE_NAME)
                    putFloat(IconRemoteConfig.KEY_FALLBACK_SCALE, config.fallbackScaleMultiplier)
                    putBoolean(IconRemoteConfig.KEY_GLOBAL_MONET_ICONS, config.globalMonetIcons)
                    putBoolean(IconRemoteConfig.KEY_MONET_CUSTOM_COLORS, config.monetCustomColors)
                    putInt(IconRemoteConfig.KEY_MONET_BACKGROUND_COLOR, config.monetBackgroundColor)
                    putInt(IconRemoteConfig.KEY_MONET_FOREGROUND_COLOR, config.monetForegroundColor)
                    putBoolean(IconRemoteConfig.KEY_SYSTEM_THEME_ACTIVE, config.systemThemeActive)
                    putBoolean(
                        IconRemoteConfig.KEY_SYSTEM_THEME_ANIMATION_BRIDGE,
                        false,
                    )
                    putLong(IconRemoteConfig.KEY_REVISION, config.revision)
                }
                .apply()
        }.onFailure { throwable ->
            _state.value = readState(service).copy(
                error = throwable.message ?: throwable.javaClass.simpleName,
            )
            appContext?.let {
                AppLog.warning(
                    it,
                    "Unable to publish Xposed API 102 remote configuration: " +
                        (throwable.message ?: throwable.javaClass.simpleName),
                )
            }
        }
    }

    private val STATIC_SCOPE = setOf(
        "com.miui.home",
        // LSPosed maps system_server to the synthetic "system" package, not "android".
        "system",
    )

    private val SCOPE_LISTENER = object : XposedService.OnScopeEventListener {
        override fun onScopeRequestApproved(granted: MutableList<String>) {
            appContext?.let { context ->
                AppLog.info(context, "Scope request approved: ${granted.sorted()}; reboot required")
            }
        }

        override fun onScopeRequestFailed(reason: String) {
            appContext?.let { context ->
                AppLog.warning(context, "Scope request failed: $reason")
            }
        }
    }
}
