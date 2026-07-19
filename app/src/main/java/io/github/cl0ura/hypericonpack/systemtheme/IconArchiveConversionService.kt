package io.github.cl0ura.hypericonpack.systemtheme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.cl0ura.hypericonpack.R
import io.github.cl0ura.hypericonpack.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class IconArchiveConversionRequest(
    val iconPackPackage: String,
    val sourceLabel: String,
    val fallbackScaleMultiplier: Float,
    val globalMonetIcons: Boolean,
    val monetCustomColors: Boolean,
    val monetBackgroundColor: Int,
    val monetForegroundColor: Int,
)

internal sealed interface IconArchiveConversionState {
    data object Idle : IconArchiveConversionState

    data class Running(
        val request: IconArchiveConversionRequest,
        val progress: HyperOsIconArchiveConverter.ConversionProgress?,
    ) : IconArchiveConversionState

    data class Succeeded(
        val request: IconArchiveConversionRequest,
        val archivePath: String,
        val convertedIcons: Int,
    ) : IconArchiveConversionState

    data class Failed(
        val request: IconArchiveConversionRequest,
        val message: String,
    ) : IconArchiveConversionState
}

internal object IconArchiveConversionController {
    private val mutableState = MutableStateFlow<IconArchiveConversionState>(IconArchiveConversionState.Idle)
    val state: StateFlow<IconArchiveConversionState> = mutableState.asStateFlow()

    fun start(context: Context, request: IconArchiveConversionRequest): Boolean {
        if (mutableState.value is IconArchiveConversionState.Running) return false
        mutableState.value = IconArchiveConversionState.Running(request, null)
        return runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                IconArchiveConversionService.intent(context, request),
            )
            true
        }.getOrElse { throwable ->
            val message = throwable.message ?: throwable.javaClass.simpleName
            mutableState.value = IconArchiveConversionState.Failed(request, message)
            Log.e("HyperIconPack", "Unable to start conversion service", throwable)
            false
        }
    }

    internal fun publish(state: IconArchiveConversionState) {
        mutableState.value = state
    }
}

class IconArchiveConversionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var conversionJob: Job? = null
    private var conversionFinished = false
    private var lastNotifiedPercent = -1
    private var lastNotifiedPhase: HyperOsIconArchiveConverter.ConversionPhase? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.toRequest()
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (conversionJob?.isActive == true) return START_NOT_STICKY

        val initial = IconArchiveConversionState.Running(request, null)
        IconArchiveConversionController.publish(initial)
        startForeground(NOTIFICATION_ID, buildNotification(initial))
        Log.i(TAG, "Conversion started: ${request.sourceLabel}")

        conversionJob = serviceScope.launch {
            val result = runCatching {
                HyperOsIconArchiveConverter.convert(
                    context = applicationContext,
                    iconPackPackage = request.iconPackPackage,
                    fallbackScaleMultiplier = request.fallbackScaleMultiplier,
                    globalMonetIcons = request.globalMonetIcons,
                    monetCustomColors = request.monetCustomColors,
                    monetBackgroundColor = request.monetBackgroundColor,
                    monetForegroundColor = request.monetForegroundColor,
                    includeInstalledAppFallbacks = true,
                    onProgress = { progress ->
                        val state = IconArchiveConversionState.Running(request, progress)
                        IconArchiveConversionController.publish(state)
                        val percent = (progress.fraction * 100).toInt().coerceIn(0, 100)
                        if (percent != lastNotifiedPercent || progress.phase != lastNotifiedPhase) {
                            lastNotifiedPercent = percent
                            lastNotifiedPhase = progress.phase
                            notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                        }
                    },
                )
            }

            result.fold(
                onSuccess = { archive ->
                    val state = IconArchiveConversionState.Succeeded(
                        request = request,
                        archivePath = archive.archive.absolutePath,
                        convertedIcons = archive.convertedExplicitMappings + archive.convertedPackageDefaults,
                    )
                    IconArchiveConversionController.publish(state)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                    Log.i(TAG, "Conversion completed: ${archive.archive.name}, icons=${state.convertedIcons}")
                },
                onFailure = { throwable ->
                    val message = throwable.message ?: throwable.javaClass.simpleName
                    val state = IconArchiveConversionState.Failed(request, message)
                    IconArchiveConversionController.publish(state)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                    Log.e(TAG, "Conversion failed: ${request.sourceLabel}", throwable)
                },
            )
            conversionFinished = true
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!conversionFinished) {
            val running = IconArchiveConversionController.state.value as? IconArchiveConversionState.Running
            if (running != null) {
                IconArchiveConversionController.publish(
                    IconArchiveConversionState.Failed(running.request, "转换任务被系统中断"),
                )
                Log.w(TAG, "Conversion service stopped before completion")
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: IconArchiveConversionState): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title: String
        val content: String
        val progress: Int
        val indeterminate: Boolean
        val ongoing: Boolean
        when (state) {
            IconArchiveConversionState.Idle -> {
                title = "制作图标包"
                content = "正在准备"
                progress = 0
                indeterminate = true
                ongoing = true
            }

            is IconArchiveConversionState.Running -> {
                title = "正在制作 ${state.request.sourceLabel}"
                content = state.progress?.let {
                    "${it.phase.notificationLabel()} · ${it.completed}/${it.total}"
                } ?: "正在准备转换资源"
                progress = ((state.progress?.fraction ?: 0f) * 100).toInt().coerceIn(0, 100)
                indeterminate = state.progress == null || state.progress.total <= 0
                ongoing = true
            }

            is IconArchiveConversionState.Succeeded -> {
                title = "图标包制作完成"
                content = "已保存 ${state.convertedIcons} 个图标"
                progress = 100
                indeterminate = false
                ongoing = false
            }

            is IconArchiveConversionState.Failed -> {
                title = "图标包制作失败"
                content = state.message
                progress = 0
                indeterminate = false
                ongoing = false
            }
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (state is IconArchiveConversionState.Running) {
            builder.setProgress(100, progress, indeterminate)
        }

        val notification = builder.build()
        attachXiaomiIslandPayload(notification, state, progress, ongoing)
        return notification
    }

    private fun attachXiaomiIslandPayload(
        notification: Notification,
        state: IconArchiveConversionState,
        progress: Int,
        ongoing: Boolean,
    ) {
        val iconKey = "miui.focus.pic_progress"
        // A stable four-character percentage prevents the compact island from
        // resizing at 9/10/100%, while the regular notification can still
        // show exact phase and item counts.
        val percentText = progress.toString().padStart(3, '0') + "%"
        val islandStatus = when {
            ongoing -> "转换中"
            state is IconArchiveConversionState.Succeeded -> "已完成"
            else -> "已停止"
        }
        val pictureBundle = Bundle().apply {
            putParcelable(iconKey, Icon.createWithResource(this@IconArchiveConversionService, R.drawable.ic_notification_small))
        }
        notification.extras.putBundle("miui.focus.pics", pictureBundle)

        val smallIsland = JSONObject()
            .put("picInfo", JSONObject().put("type", 1).put("pic", iconKey))
            .put("textInfo", JSONObject().put("title", percentText))
        val bigIsland = JSONObject()
            .put(
                "imageTextInfoLeft",
                JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", iconKey))
                    .put(
                        "textInfo",
                        JSONObject()
                            .put("frontTitle", islandStatus)
                            .put("title", percentText)
                            .put("content", "图标包进度")
                            .put("useHighLight", true),
                    ),
            )
        val island = JSONObject()
            .put("islandProperty", 1)
            // Reordering on every update makes SystemUI replay the compact
            // island placement animation and looks like its width is jumping.
            .put("islandOrder", false)
            .put("islandTimeout", if (ongoing) 21_600 else 30)
            .put("dismissIsland", !ongoing)
            .put("highlightColor", "#5B78A6")
            .put("bigIslandArea", bigIsland)
            .put("smallIslandArea", smallIsland)
        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("business", "icon_pack_conversion")
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("updatable", ongoing)
            .put("timeout", 360)
            .put("filterWhenNoPermission", false)
            .put("ticker", percentText)
            .put("tickerPic", iconKey)
            .put("param_island", island)
            .put(
                "baseInfo",
                JSONObject()
                    .put("title", "制作图标包")
                    .put("content", percentText)
                    .put("colorTitle", "#5B78A6")
                    .put("type", 2),
            )
        notification.extras.putString("miui.focus.param", JSONObject().put("param_v2", paramV2).toString())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "图标包转换",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "在后台制作图标主题存档并显示转换进度"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        Log.i(
            TAG,
            "Island support=${supportsIsland()}, protocol=${focusProtocolVersion()}, focusPermission=${hasFocusPermission()}",
        )
    }

    private fun supportsIsland(): Boolean = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val method = systemProperties.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        method.invoke(null, "persist.sys.feature.island", false) as Boolean
    }.getOrDefault(false)

    private fun focusProtocolVersion(): Int = runCatching {
        Settings.System.getInt(contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    private fun hasFocusPermission(): Boolean = runCatching {
        val extras = Bundle().apply { putString("package", packageName) }
        contentResolver.call(
            android.net.Uri.parse("content://miui.statusbar.notification.public"),
            "canShowFocus",
            null,
            extras,
        )?.getBoolean("canShowFocus", false) == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "HyperIconPack"
        private const val CHANNEL_ID = "icon_archive_conversion"
        private const val NOTIFICATION_ID = 3917

        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_SCALE = "scale"
        private const val EXTRA_MONET = "monet"
        private const val EXTRA_CUSTOM_COLORS = "custom_colors"
        private const val EXTRA_BACKGROUND = "background"
        private const val EXTRA_FOREGROUND = "foreground"

        internal fun intent(context: Context, request: IconArchiveConversionRequest): Intent =
            Intent(context, IconArchiveConversionService::class.java).apply {
                putExtra(EXTRA_PACKAGE, request.iconPackPackage)
                putExtra(EXTRA_LABEL, request.sourceLabel)
                putExtra(EXTRA_SCALE, request.fallbackScaleMultiplier)
                putExtra(EXTRA_MONET, request.globalMonetIcons)
                putExtra(EXTRA_CUSTOM_COLORS, request.monetCustomColors)
                putExtra(EXTRA_BACKGROUND, request.monetBackgroundColor)
                putExtra(EXTRA_FOREGROUND, request.monetForegroundColor)
            }

        private fun Intent.toRequest(): IconArchiveConversionRequest? {
            val iconPackPackage = getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() } ?: return null
            return IconArchiveConversionRequest(
                iconPackPackage = iconPackPackage,
                sourceLabel = getStringExtra(EXTRA_LABEL).orEmpty().ifBlank {
                    HyperOsIconArchiveConverter.sourceLabel(iconPackPackage)
                },
                fallbackScaleMultiplier = getFloatExtra(EXTRA_SCALE, 0.85f),
                globalMonetIcons = getBooleanExtra(EXTRA_MONET, false),
                monetCustomColors = getBooleanExtra(EXTRA_CUSTOM_COLORS, false),
                monetBackgroundColor = getIntExtra(EXTRA_BACKGROUND, 0),
                monetForegroundColor = getIntExtra(EXTRA_FOREGROUND, 0),
            )
        }
    }
}

private fun HyperOsIconArchiveConverter.ConversionPhase.notificationLabel(): String = when (this) {
    HyperOsIconArchiveConverter.ConversionPhase.PARSING -> "解析图标包"
    HyperOsIconArchiveConverter.ConversionPhase.EXPLICIT_MAPPINGS -> "转换图标映射"
    HyperOsIconArchiveConverter.ConversionPhase.FALLBACK_ACTIVITIES -> "补全应用图标"
    HyperOsIconArchiveConverter.ConversionPhase.VALIDATING -> "校验主题存档"
    HyperOsIconArchiveConverter.ConversionPhase.COMPLETED -> "转换完成"
}
