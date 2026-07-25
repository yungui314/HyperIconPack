package io.github.cl0ura.hypericonpack.iconpack

import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class NormalizedIconMask(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val configPath: String,
)

/** Normalizes either icon-mask alpha convention and samples the matching Xiaomi mask path. */
internal object IconMaskGeometry {
    fun normalize(
        width: Int,
        height: Int,
        sourcePixels: IntArray,
        detectInverse: Boolean = true,
    ): NormalizedIconMask {
        require(width > 0 && height > 0 && sourcePixels.size == width * height) { "Invalid icon mask" }
        val inverse = detectInverse && IconPackMaskSemantics.isInverse(width, height) { x, y ->
            sourcePixels[y * width + x] ushr 24
        }
        val pixels = IntArray(sourcePixels.size) { index ->
            val sourceAlpha = sourcePixels[index] ushr 24
            val alpha = if (inverse) 255 - sourceAlpha else sourceAlpha
            (alpha shl 24) or WHITE_RGB
        }
        return NormalizedIconMask(
            width = width,
            height = height,
            pixels = pixels,
            configPath = createMaskPath(width, height) { x, y -> pixels[y * width + x] ushr 24 },
        )
    }

    private fun createMaskPath(width: Int, height: Int, alphaAt: (Int, Int) -> Int): String {
        val centerX = (width - 1) / 2f
        val centerY = (height - 1) / 2f
        val radii = List(MASK_PATH_SAMPLES) { index ->
            val angle = Math.PI * 2.0 * index / MASK_PATH_SAMPLES
            radialExtent(width, height, centerX, centerY, angle, alphaAt)
        }
        val axis = listOf(radii[0], radii[16], radii[32], radii[48]).average()
        val diagonal = listOf(radii[8], radii[24], radii[40], radii[56]).average()
        if (axis > 0.0 && diagonal / axis < CIRCLE_DIAGONAL_RATIO) {
            val radius = axis * 100.0 / (width - 1)
            val left = formatPathValue(50.0 - radius)
            val radiusValue = formatPathValue(radius)
            return "M50 $left" +
                "A$radiusValue $radiusValue 0 1 1 50 ${formatPathValue(50.0 + radius)}" +
                "A$radiusValue $radiusValue 0 1 1 50 $left" +
                "Z"
        }
        return buildString {
            radii.forEachIndexed { index, radius ->
                val angle = Math.PI * 2.0 * index / MASK_PATH_SAMPLES
                val x = (centerX + cos(angle) * radius) * 100.0 / (width - 1)
                val y = (centerY + sin(angle) * radius) * 100.0 / (height - 1)
                append(if (index == 0) 'M' else 'L')
                append(formatPathValue(x)).append(' ').append(formatPathValue(y))
            }
            append('Z')
        }
    }

    private fun radialExtent(
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        angle: Double,
        alphaAt: (Int, Int) -> Int,
    ): Double {
        val maxRadius = hypot(width.toDouble(), height.toDouble()) / 2.0
        var lastVisible = 0.0
        var radius = 0.0
        while (radius <= maxRadius) {
            val x = (centerX + cos(angle) * radius).toInt()
            val y = (centerY + sin(angle) * radius).toInt()
            if (x !in 0 until width || y !in 0 until height) break
            if (alphaAt(x, y) >= MASK_VISIBLE_ALPHA) lastVisible = radius
            radius += MASK_PATH_STEP
        }
        return lastVisible
    }

    private fun formatPathValue(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')

    private const val WHITE_RGB = 0x00FFFFFF
    private const val MASK_VISIBLE_ALPHA = 128
    private const val MASK_PATH_SAMPLES = 64
    private const val MASK_PATH_STEP = 0.5
    private const val CIRCLE_DIAGONAL_RATIO = 1.08
}
