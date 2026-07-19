package io.github.cl0ura.hypericonpack.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Animation-only wrapper for a themed static PNG.
 *
 * HyperOS asks the transition Drawable for an Outline. BitmapDrawable returns
 * a rectangle regardless of its transparent pixels, which briefly reveals
 * square or rounded-square corners during launch and return. Sample the real
 * alpha silhouette and publish a convex path for circles, squircles, rounded
 * rectangles, diamonds and irregular glyph icons alike.
 */
internal class ThemeAnimationOutlineDrawable(
    private val source: Drawable,
) : Drawable(), Drawable.Callback {
    private var cachedWidth = -1
    private var cachedHeight = -1
    private var circleDetected = false
    private var cachedOutlinePath: Path? = null

    init {
        source.callback = this
    }

    override fun draw(canvas: Canvas) {
        source.bounds = bounds
        source.draw(canvas)
    }

    override fun getOutline(outline: Outline) {
        val bounds = bounds
        if (bounds.isEmpty) {
            source.getOutline(outline)
            return
        }
        detectAlphaOutline(bounds)
        when {
            circleDetected -> outline.setOval(bounds)
            cachedOutlinePath != null -> setOutlinePath(outline, cachedOutlinePath!!)
            else -> {
                source.getOutline(outline)
                return
            }
        }
        outline.alpha = alpha / 255f
    }

    @Suppress("DEPRECATION")
    private fun setOutlinePath(outline: Outline, path: Path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(path)
        } else {
            outline.setConvexPath(path)
        }
    }

    override fun setAlpha(alpha: Int) {
        source.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = source.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        source.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = source.intrinsicWidth

    override fun getIntrinsicHeight(): Int = source.intrinsicHeight

    override fun isStateful(): Boolean = source.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        val changed = source.setState(state)
        if (changed) {
            invalidateCachedOutline()
            invalidateSelf()
        }
        return changed
    }

    override fun onLevelChange(level: Int): Boolean {
        val changed = source.setLevel(level)
        if (changed) {
            invalidateCachedOutline()
            invalidateSelf()
        }
        return changed
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        return source.setVisible(visible, restart) || changed
    }

    override fun onBoundsChange(bounds: Rect) = invalidateCachedOutline()

    override fun invalidateDrawable(who: Drawable) {
        invalidateCachedOutline()
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) =
        scheduleSelf(what, whenMillis)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    private fun invalidateCachedOutline() {
        cachedWidth = -1
        cachedHeight = -1
        circleDetected = false
        cachedOutlinePath = null
    }

    private fun detectAlphaOutline(bounds: Rect) {
        if (cachedWidth == bounds.width() && cachedHeight == bounds.height()) return
        cachedWidth = bounds.width()
        cachedHeight = bounds.height()
        circleDetected = false
        cachedOutlinePath = null

        val sample = renderAlphaSample(source) ?: return
        try {
            circleDetected = isCircularSample(sample)
            if (!circleDetected) cachedOutlinePath = convexAlphaPath(sample, bounds)
        } finally {
            sample.recycle()
        }
    }

    internal companion object {
        const val OUTLINE_SAMPLE_SIZE = 64
        const val ALPHA_VISIBLE = 128
        private const val ALPHA_PATH_VISIBLE = 24
        const val CIRCLE_EDGE_DENOMINATOR = 4

        /** Used by the vendor adaptive bridge only for genuinely round icons. */
        fun isCircularAlpha(source: Drawable): Boolean {
            val sample = renderAlphaSample(source) ?: return false
            return try {
                isCircularSample(sample)
            } finally {
                sample.recycle()
            }
        }

        private fun renderAlphaSample(source: Drawable): Bitmap? {
            val size = OUTLINE_SAMPLE_SIZE
            val sample = try {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                return null
            }
            val canvas = Canvas(sample)
            val oldBounds = Rect(source.bounds)
            return try {
                canvas.drawColor(Color.TRANSPARENT)
                source.setBounds(0, 0, size, size)
                source.draw(canvas)
                sample
            } catch (_: Throwable) {
                sample.recycle()
                null
            } finally {
                source.bounds = oldBounds
            }
        }

        private fun isCircularSample(sample: Bitmap): Boolean {
            val size = sample.width
            val centre = alphaAt(sample, size / 2, size / 2)
            val corners = listOf(
                alphaAt(sample, 0, 0),
                alphaAt(sample, size - 1, 0),
                alphaAt(sample, 0, size - 1),
                alphaAt(sample, size - 1, size - 1),
            )
            val horizontalMiddle = visiblePixels(sample, row = size / 2)
            val verticalMiddle = visiblePixels(sample, column = size / 2)
            val horizontalTop = visiblePixels(sample, row = 0)
            val verticalLeft = visiblePixels(sample, column = 0)
            val visibleArea = visibleArea(sample)
            val boundingArea = horizontalMiddle * verticalMiddle
            val areaPercent = if (boundingArea == 0) 0 else visibleArea * 100 / boundingArea
            return centre >= ALPHA_VISIBLE &&
                corners.all { it < ALPHA_VISIBLE } &&
                horizontalMiddle > 0 && verticalMiddle > 0 &&
                horizontalTop * CIRCLE_EDGE_DENOMINATOR <= horizontalMiddle &&
                verticalLeft * CIRCLE_EDGE_DENOMINATOR <= verticalMiddle &&
                areaPercent in CIRCLE_MIN_AREA_PERCENT..CIRCLE_MAX_AREA_PERCENT
        }

        private fun convexAlphaPath(sample: Bitmap, bounds: Rect): Path? {
            val points = ArrayList<AlphaPoint>(sample.height * 4)
            for (y in 0 until sample.height) {
                var first = -1
                var last = -1
                for (x in 0 until sample.width) {
                    if (alphaAt(sample, x, y) >= ALPHA_PATH_VISIBLE) {
                        if (first < 0) first = x
                        last = x
                    }
                }
                if (first >= 0) {
                    points += AlphaPoint(first, y)
                    if (last != first) points += AlphaPoint(last, y)
                }
            }
            for (x in 0 until sample.width) {
                var first = -1
                var last = -1
                for (y in 0 until sample.height) {
                    if (alphaAt(sample, x, y) >= ALPHA_PATH_VISIBLE) {
                        if (first < 0) first = y
                        last = y
                    }
                }
                if (first >= 0) {
                    points += AlphaPoint(x, first)
                    if (last != first) points += AlphaPoint(x, last)
                }
            }

            val hull = convexHull(points)
            if (hull.size < 3) return null
            val path = Path()
            hull.forEachIndexed { index, point ->
                val x = bounds.left + point.x.toFloat() * bounds.width() / (sample.width - 1)
                val y = bounds.top + point.y.toFloat() * bounds.height() / (sample.height - 1)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }

        private fun convexHull(points: List<AlphaPoint>): List<AlphaPoint> {
            val sorted = points.distinct().sortedWith(compareBy<AlphaPoint>({ it.x }, { it.y }))
            if (sorted.size <= 2) return sorted

            fun cross(origin: AlphaPoint, first: AlphaPoint, second: AlphaPoint): Long =
                (first.x - origin.x).toLong() * (second.y - origin.y) -
                    (first.y - origin.y).toLong() * (second.x - origin.x)

            val lower = ArrayList<AlphaPoint>()
            sorted.forEach { point ->
                while (lower.size >= 2 && cross(lower[lower.lastIndex - 1], lower.last(), point) <= 0L) {
                    lower.removeAt(lower.lastIndex)
                }
                lower += point
            }
            val upper = ArrayList<AlphaPoint>()
            sorted.asReversed().forEach { point ->
                while (upper.size >= 2 && cross(upper[upper.lastIndex - 1], upper.last(), point) <= 0L) {
                    upper.removeAt(upper.lastIndex)
                }
                upper += point
            }
            lower.removeAt(lower.lastIndex)
            upper.removeAt(upper.lastIndex)
            return lower + upper
        }

        private fun alphaAt(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(x, y) ushr 24

        private fun visiblePixels(bitmap: Bitmap, row: Int? = null, column: Int? = null): Int {
            var count = 0
            if (row != null) {
                for (x in 0 until bitmap.width) if (alphaAt(bitmap, x, row) >= ALPHA_VISIBLE) count++
            } else if (column != null) {
                for (y in 0 until bitmap.height) if (alphaAt(bitmap, column, y) >= ALPHA_VISIBLE) count++
            }
            return count
        }

        private fun visibleArea(bitmap: Bitmap): Int {
            var count = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (alphaAt(bitmap, x, y) >= ALPHA_VISIBLE) count++
                }
            }
            return count
        }

        private data class AlphaPoint(val x: Int, val y: Int)

        private const val CIRCLE_MIN_AREA_PERCENT = 68
        private const val CIRCLE_MAX_AREA_PERCENT = 86
    }
}
