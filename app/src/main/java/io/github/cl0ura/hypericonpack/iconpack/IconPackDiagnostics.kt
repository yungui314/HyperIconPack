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
            val fallbackSummary = if (pack.supportsFallbackStyle) {
                "检测到可用的 iconmask，可统一未适配应用外观。"
            } else {
                "未检测到可用的 iconmask，未适配应用会保留原图。"
            }
            "检测成功：读取到 ${pack.mappingCount} 条 Activity 图标映射。$fallbackSummary 若桌面仍无变化，请查看 LSPosed 的 HyperIconPack 日志。"
        } else {
            "检测失败：${failure?.javaClass?.simpleName ?: "未知错误"}。图标包可能未提供标准 appfilter.xml。"
        }
    }
}
