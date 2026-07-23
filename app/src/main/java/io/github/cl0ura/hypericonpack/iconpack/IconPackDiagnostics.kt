package io.github.cl0ura.hypericonpack.iconpack

import android.content.Context

/**
 * User-triggered validation that runs in the module app process. It verifies
 * the same package-context and appfilter parser used by the injected process,
 * without requiring a connected computer or logcat access.
 */
internal object IconPackDiagnostics {
    fun inspect(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "请先选择一个图标包"

        var failure: Throwable? = null
        val pack = ParsedIconPack.load(context, packageName) { throwable ->
            failure = throwable
        }
        return if (pack != null) {
            val capabilities = pack.fallbackCapabilities
            val nativeFallback = pack.loadNativeThemeFallback(scaleMultiplier = 1f)
            val fallbackSummary = when {
                nativeFallback != null && capabilities != null -> buildString {
                    append("标准回退层可转换：")
                    append("iconback ").append(capabilities.declaredPatternCount).append(" 个")
                    append("、iconmask ").append(if (nativeFallback.hasMask) "可用" else "缺失")
                    append("、iconupon ").append(if (nativeFallback.hasBorder) "可用" else "缺失")
                    append("、声明缩放 ").append(capabilities.declaredScale).append("。")
                    if (capabilities.declaredPatternCount > 1) {
                        append("Xiaomi 原生格式只会使用首个可加载 iconback。")
                    }
                }

                capabilities != null -> "检测到标准回退声明，但对应 Drawable 无法加载；未知应用将保留系统默认处理。"
                else -> "未检测到 iconback/iconmask/iconupon，未知应用将保留系统默认处理。"
            }
            "检测成功：${pack.mappingCount} 条 Activity 映射、${pack.calendarMappings().size} 条日历映射。$fallbackSummary"
        } else {
            "检测失败：${failure?.javaClass?.simpleName ?: "未知错误"}。图标包可能未提供标准 appfilter.xml。"
        }
    }
}
