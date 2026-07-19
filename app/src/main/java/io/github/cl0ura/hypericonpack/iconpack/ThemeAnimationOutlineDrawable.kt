package io.github.cl0ura.hypericonpack.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * Animation-only wrapper for a themed static PNG.
 *
 * ThemeResourcesSystem returns a BitmapDrawable for ordinary icons.  Android's
 * default outline for that class is a rectangle even if the pixels themselves
 * form a circle, which makes HyperOS briefly start a launch animation from a
 * square.  This wrapper preserves every rendered pixel and changes only the
 * outline exposed to FloatingIconView2 when the alpha silhouette is clearly
 * circular.  Unknown shapes deliberately keep their source outline.
 */
internal class ThemeAnimationOutlineDrawable(
    private val source: Drawable,
) : Drawable(), Drawable.Callback {
    private var cachedWidth = -1
    private var cachedHeight = -1
    private var circleDetected = false

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
        detectCircularAlpha(bounds)
        if (circleDetected) {
            outline.setOval(bounds)
            outline.alpha = alpha / 255f
        } else {
            source.getOutline(outline)
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
    override fun getOpacity(): Int = source.opacity

    override fun getIntrinsicWidth(): Int = source.intrinsicWidth

    override fun getIntrinsicHeight(): Int = source.intrinsicHeight

    override fun isStateful(): Boolean = source.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        val changed = source.setState(state)
        if (changed) invalidateSelf()
        return changed
    }

    override fun onLevelChange(level: Int): Boolean {
        val changed = source.setLevel(level)
        if (changed) invalidateSelf()
        return changed
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        return source.setVisible(visible, restart) || changed
    }

    override fun onBoundsChange(bounds: Rect) {
        cachedWidth = -1
        cachedHeight = -1
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) =
        scheduleSelf(what, whenMillis)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    private fun detectCircularAlpha(bounds: Rect) {
        if (cachedWidth == bounds.width() && cachedHeight == bounds.height()) return
        cachedWidth = bounds.width()
        cachedHeight = bounds.height()
        circleDetected = isCircularAlpha(source)
    }

    internal companion object {
        const val OUTLINE_SAMPLE_SIZE = 64
        const val ALPHA_VISIBLE = 128
        const val CIRCLE_EDGE_DENOMINATOR = 4

        /**
         * HyperOS transition code distinguishes actual AdaptiveIconDrawable
         * instances from ordinary Drawables.  Keep this conservative alpha
         * test shared with the adaptive animation wrapper: only a clearly
         * round themed bitmap may opt into that path.
         */
        fun isCircularAlpha(source: Drawable): Boolean {
            val size = OUTLINE_SAMPLE_SIZE
            val sample = try {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                return false
            }
            val canvas = Canvas(sample)
            val oldBounds = Rect(source.bounds)
            return try {
                canvas.drawColor(Color.TRANSPARENT)
                source.setBounds(0, 0, size, size)
                source.draw(canvas)
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
                centre >= ALPHA_VISIBLE &&
                    corners.all { it < ALPHA_VISIBLE } &&
                    horizontalMiddle > 0 && verticalMiddle > 0 &&
                    horizontalTop * CIRCLE_EDGE_DENOMINATOR <= horizontalMiddle &&
                    verticalLeft * CIRCLE_EDGE_DENOMINATOR <= verticalMiddle
            } catch (_: Throwable) {
                // A broken vendor Drawable must not affect the launch animation.
                false
            } finally {
                source.bounds = oldBounds
                sample.recycle()
            }
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
    }
}
