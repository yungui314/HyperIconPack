package io.github.cl0ura.hypericonpack.iconpack

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/** Standard appfilter layers that can be translated to Xiaomi's native icon fallback. */
internal data class IconPackThemeFallback(
    val mask: Drawable?,
    val pattern: Drawable?,
    val border: Drawable?,
    val scale: Float,
    val declaredPatternCount: Int,
) {
    val hasMask: Boolean
        get() = mask != null

    val hasPattern: Boolean
        get() = pattern != null

    val hasBorder: Boolean
        get() = border != null
}

internal data class IconPackFallbackCapabilities(
    val declaredPatternCount: Int,
    val hasDeclaredMask: Boolean,
    val hasDeclaredBorder: Boolean,
    val declaredScale: Float,
)

/** Shared inverse-alpha detection for launcher preview and Xiaomi theme export. */
internal object IconPackMaskSemantics {
    fun isInverse(bitmap: Bitmap): Boolean = isInverse(
        width = bitmap.width,
        height = bitmap.height,
    ) { x, y -> bitmap.getPixel(x, y) ushr 24 }

    internal fun isInverse(
        width: Int,
        height: Int,
        alphaAt: (x: Int, y: Int) -> Int,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val minimum = minOf(width, height)
        val step = maxOf(1, minimum / 32)
        val band = maxOf(1, minimum / 16)
        var edgeTotal = 0L
        var edgeCount = 0
        val depths = listOf(0, band / 2, band - 1).distinct()
        depths.forEach { depth ->
            for (x in 0 until width step step) {
                edgeTotal += alphaAt(x, depth)
                edgeTotal += alphaAt(x, height - 1 - depth)
                edgeCount += 2
            }
            for (y in 0 until height step step) {
                edgeTotal += alphaAt(depth, y)
                edgeTotal += alphaAt(width - 1 - depth, y)
                edgeCount += 2
            }
        }

        var centreTotal = 0L
        var centreCount = 0
        for (y in height / 3..height * 2 / 3 step step) {
            for (x in width / 3..width * 2 / 3 step step) {
                centreTotal += alphaAt(x, y)
                centreCount++
            }
        }
        val edgeAlpha = edgeTotal.toDouble() / edgeCount.coerceAtLeast(1)
        val centreAlpha = centreTotal.toDouble() / centreCount.coerceAtLeast(1)
        return edgeAlpha - centreAlpha > MASK_DIRECTION_THRESHOLD
    }

    private const val MASK_DIRECTION_THRESHOLD = 32.0
}
