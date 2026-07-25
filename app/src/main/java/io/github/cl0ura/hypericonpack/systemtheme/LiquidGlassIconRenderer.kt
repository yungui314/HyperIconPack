package io.github.cl0ura.hypericonpack.systemtheme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Offline glass material that preserves the source icon's alpha silhouette. */
internal object LiquidGlassIconRenderer {
    fun render(drawable: Drawable, size: Int, output: OutputStream) {
        require(size > 0)
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        var softened: Bitmap? = null
        var reduced: Bitmap? = null
        try {
            val oldBounds = Rect(drawable.bounds)
            try {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(source))
            } finally {
                drawable.bounds = oldBounds
            }

            val downsampledSize = max(8, size / 12)
            reduced = Bitmap.createScaledBitmap(source, downsampledSize, downsampledSize, true)
            softened = Bitmap.createScaledBitmap(reduced, size, size, true)
            val sourcePixels = IntArray(size * size)
            val softPixels = IntArray(size * size)
            source.getPixels(sourcePixels, 0, size, 0, 0, size, size)
            softened.getPixels(softPixels, 0, size, 0, 0, size, size)
            val distance = distanceFromBoundary(sourcePixels, size)
            val target = IntArray(sourcePixels.size)
            val centre = (size - 1) / 2f

            sourcePixels.indices.forEach { index ->
                val sourcePixel = sourcePixels[index]
                val sourceAlpha = Color.alpha(sourcePixel)
                if (sourceAlpha == 0) return@forEach
                val x = index % size
                val y = index / size
                val nx = (x - centre) / centre
                val ny = (y - centre) / centre

                // A shallow convex lens bends the source artwork towards the centre.
                val radial = (nx * nx + ny * ny).coerceIn(0f, 1.6f)
                val bend = 0.055f * (1f - radial.coerceAtMost(1f)).pow(1.4f)
                val sampleX = (x - nx * size * bend).toInt().coerceIn(0, size - 1)
                val sampleY = (y - ny * size * bend).toInt().coerceIn(0, size - 1)
                val refracted = sourcePixels[sampleY * size + sampleX]
                val haze = softPixels[index]

                var red = mix(Color.red(refracted), Color.red(haze), 0.16f)
                var green = mix(Color.green(refracted), Color.green(haze), 0.16f)
                var blue = mix(Color.blue(refracted), Color.blue(haze), 0.16f)
                val luminance = red * 0.299f + green * 0.587f + blue * 0.114f
                red = mix(luminance.toInt(), red, 0.90f)
                green = mix(luminance.toInt(), green, 0.90f)
                blue = mix(luminance.toInt(), blue, 0.90f)

                // Translucent cool material body. Keep enough source colour to identify the app.
                red = mix(red, 242, 0.06f)
                green = mix(green, 248, 0.07f)
                blue = mix(blue, 255, 0.09f)

                val edge = (1f - distance[index] / (size * 0.075f)).coerceIn(0f, 1f)
                val alphaLeft = alphaAt(sourcePixels, x - 2, y, size)
                val alphaRight = alphaAt(sourcePixels, x + 2, y, size)
                val alphaTop = alphaAt(sourcePixels, x, y - 2, size)
                val alphaBottom = alphaAt(sourcePixels, x, y + 2, size)
                val lightFacing = ((alphaRight - alphaLeft) + (alphaBottom - alphaTop)) / 510f
                val bevelLight = edge * lightFacing.coerceIn(0f, 1f) * 0.78f
                val bevelShade = edge * (-lightFacing).coerceIn(0f, 1f) * 0.34f

                // Broad top-left reflection plus a narrow curved caustic line.
                val highlightDistance = hypot(nx + 0.42f, ny + 0.48f)
                val broadHighlight = (1f - highlightDistance / 0.72f).coerceIn(0f, 1f).pow(2.2f) * 0.48f
                val ellipse = hypot((nx + 0.08f) / 0.92f, (ny + 0.16f) / 0.68f)
                val caustic = (1f - abs(ellipse - 0.78f) / 0.055f).coerceIn(0f, 1f) *
                    ((-nx - ny + 0.35f) / 1.6f).coerceIn(0f, 1f) * 0.30f
                val gloss = (broadHighlight + caustic + bevelLight).coerceIn(0f, 0.68f)
                red = mix(red, 255, gloss)
                green = mix(green, 255, gloss)
                blue = mix(blue, 255, gloss)

                val bottomShade = ((ny + 0.15f) / 1.15f).coerceIn(0f, 1f) * 0.07f + bevelShade
                red = (red * (1f - bottomShade)).toInt().coerceIn(0, 255)
                green = (green * (1f - bottomShade)).toInt().coerceIn(0, 255)
                blue = (blue * (1f - bottomShade * 0.72f)).toInt().coerceIn(0, 255)

                val materialAlpha = (0.92f + edge * 0.06f + broadHighlight * 0.02f).coerceAtMost(0.995f)
                target[index] = Color.argb(
                    (sourceAlpha * materialAlpha).toInt().coerceIn(0, 255),
                    red,
                    green,
                    blue,
                )
            }
            result.setPixels(target, 0, size, 0, 0, size, size)
            check(result.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Liquid Glass PNG 编码失败" }
        } finally {
            reduced?.recycle()
            softened?.recycle()
            source.recycle()
            result.recycle()
        }
    }

    private fun distanceFromBoundary(pixels: IntArray, size: Int): IntArray {
        val far = size * 2
        val distance = IntArray(pixels.size) { index ->
            val x = index % size
            val y = index / size
            if (Color.alpha(pixels[index]) < 24 || x == 0 || y == 0 || x == size - 1 || y == size - 1) 0 else far
        }
        for (y in 0 until size) for (x in 0 until size) {
            val index = y * size + x
            if (x > 0) distance[index] = min(distance[index], distance[index - 1] + 1)
            if (y > 0) distance[index] = min(distance[index], distance[index - size] + 1)
        }
        for (y in size - 1 downTo 0) for (x in size - 1 downTo 0) {
            val index = y * size + x
            if (x + 1 < size) distance[index] = min(distance[index], distance[index + 1] + 1)
            if (y + 1 < size) distance[index] = min(distance[index], distance[index + size] + 1)
        }
        return distance
    }

    private fun alphaAt(pixels: IntArray, x: Int, y: Int, size: Int): Int =
        if (x in 0 until size && y in 0 until size) Color.alpha(pixels[y * size + x]) else 0

    private fun mix(start: Int, end: Int, amount: Float): Int =
        (start + (end - start) * amount.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
}
