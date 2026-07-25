package io.github.cl0ura.hypericonpack.systemtheme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import io.github.cl0ura.hypericonpack.iconpack.InferredIconMask

/**
 * Bakes the shape normally supplied by Android's adaptive-icon pipeline into
 * a static theme resource. HyperOS opens converted icons as ordinary PNGs,
 * so legacy source icons otherwise skip the launcher's final rounded clip and
 * become hard-cornered squares.
 *
 * Some HyperOS adaptive masks are implemented by framework drawables that
 * cannot be replayed faithfully into an off-screen BitmapCanvas.  Asking such
 * a Drawable for `iconMask` caused the entire Monet render to throw and fall
 * through to a solid-colour emergency PNG.  Use the same stable rounded
 * legacy envelope that HyperOS applies to ordinary icons instead: it gives
 * raw square resources visible R corners, while a circular/transparent source
 * keeps its own shape naturally inside that envelope.
 */
internal class SystemIconShapeDrawable(
    private val source: Drawable,
    private val inferredMask: InferredIconMask? = null,
) : Drawable(), Drawable.Callback {
    private val fallbackPath = Path()
    private val fallbackBounds = RectF()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val maskBitmap: Bitmap? = inferredMask?.let { mask ->
        Bitmap.createBitmap(mask.pixels, mask.size, mask.size, Bitmap.Config.ARGB_8888)
    }

    init {
        source.callback = this
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val save = if (maskBitmap != null) {
            canvas.saveLayer(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                null,
            )
        } else {
            canvas.save()
        }
        try {
            // Do not let a vendor's square adaptive mask turn a static theme
            // PNG into a hard-cornered tile. The clip is intentionally a
            // simple path we own rather than AdaptiveIconDrawable.iconMask:
            // it must be safe to draw for every third-party resource during
            // archive conversion.
            val minimum = minOf(bounds.width(), bounds.height()).toFloat()
            val edgeInset = minimum * EDGE_INSET_FRACTION
            fallbackBounds.set(bounds)
            fallbackBounds.inset(edgeInset, edgeInset)
            val radius = minOf(fallbackBounds.width(), fallbackBounds.height()) * MINIMUM_CORNER_RADIUS_FRACTION
            fallbackPath.reset()
            if (maskBitmap == null) {
                fallbackPath.addRoundRect(fallbackBounds, radius, radius, Path.Direction.CW)
                canvas.clipPath(fallbackPath)
            }
            val previousSourceBounds = Rect(source.bounds)
            try {
                source.bounds = bounds
                source.draw(canvas)
            } finally {
                source.bounds = previousSourceBounds
            }
            maskBitmap?.let { mask -> canvas.drawBitmap(mask, null, bounds, maskPaint) }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {
        source.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        source.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        val bounds = bounds
        if (bounds.isEmpty) {
            super.getOutline(outline)
            return
        }
        val radius = minOf(bounds.width(), bounds.height()) * MINIMUM_CORNER_RADIUS_FRACTION
        outline.setRoundRect(bounds, radius)
        outline.alpha = source.alpha / 255f
    }

    override fun isStateful(): Boolean = source.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        val sourceChanged = source.setState(state)
        return sourceChanged.also { if (it) invalidateSelf() }
    }

    override fun onLevelChange(level: Int): Boolean {
        val sourceChanged = source.setLevel(level)
        return sourceChanged.also { if (it) invalidateSelf() }
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) =
        scheduleSelf(what, whenMillis)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    private companion object {
        // HyperOS' normal legacy-icon treatment is a rounded square, not a
        // fully circular badge. Keep this deliberately below 0.25 so a source
        // square gains visible R corners without being visually transformed
        // into a different icon shape.
        const val MINIMUM_CORNER_RADIUS_FRACTION = 0.18f
        const val EDGE_INSET_FRACTION = 1f / 256f
    }
}
