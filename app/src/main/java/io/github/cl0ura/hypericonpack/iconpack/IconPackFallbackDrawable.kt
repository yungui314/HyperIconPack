package io.github.cl0ura.hypericonpack.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * Draws the conventional icon-pack fallback stack:
 *
 *   iconback -> original application icon (scaled) -> iconupon -> iconmask
 *
 * The mask is rasterised to alpha once per bounds/state change, then applied
 * with the correct DST blend mode over the complete isolated composition. The traditional
 * icon-mask convention used by Pure is inverse alpha (opaque outside the
 * shape), so it needs DST_OUT; normal alpha masks need DST_IN.  Both forms
 * occur in icon packs, therefore the mode is detected from the rendered mask.
 */
internal class IconPackFallbackDrawable(
    private val source: Drawable,
    private val background: Drawable?,
    private val mask: Drawable?,
    private val foreground: Drawable?,
    scale: Float,
) : Drawable(), Drawable.Callback {
    private val sourceScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    private val sourceBounds = RectF()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private var drawableAlpha = OPAQUE
    private var cachedMask: Bitmap? = null
    private var maskDirty = true
    private var cachedMaskIsCircular = false

    init {
        listOfNotNull(source, background, mask, foreground).forEach { drawable ->
            drawable.callback = this
        }
        applyAlphaToVisibleLayers()
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return

        val savedLayer = canvas.saveLayer(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            null,
        )
        background?.let { drawable ->
            drawable.bounds = bounds
            drawable.draw(canvas)
        }
        source.bounds = scaledBounds(bounds)
        source.draw(canvas)
        foreground?.let { drawable ->
            drawable.bounds = bounds
            drawable.draw(canvas)
        }
        // Static HyperOS theme PNGs bypass a launcher's final icon clip. Apply
        // the mask to iconback, source and iconupon together so a pack's
        // rounded-square/circle corners survive conversion.
        alphaMask(bounds.width(), bounds.height())?.let { alphaBitmap ->
            canvas.drawBitmap(alphaBitmap, bounds.left.toFloat(), bounds.top.toFloat(), maskPaint)
        }
        canvas.restoreToCount(savedLayer)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, OPAQUE)
        applyAlphaToVisibleLayers()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // The mask contributes alpha only; applying a colour filter to it can
        // change that alpha on some drawable implementations.
        source.colorFilter = colorFilter
        background?.colorFilter = colorFilter
        foreground?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = intrinsicDimension { it.intrinsicWidth }

    override fun getIntrinsicHeight(): Int = intrinsicDimension { it.intrinsicHeight }

    /**
     * Launcher transition code queries a Drawable outline before it starts its
     * floating-icon animation.  The platform fallback for a plain Drawable is
     * a rectangle, which is why a masked round icon could briefly become a
     * square at the beginning/end of an app transition.  PNG masks do not
     * expose an Outline themselves, so infer the common circular convention
     * from their alpha raster and publish it here.  Non-circular masks retain
     * the framework default rather than pretending every icon pack is round.
     */
    override fun getOutline(outline: Outline) {
        val bounds = bounds
        if (bounds.isEmpty) {
            super.getOutline(outline)
            return
        }
        // Transition code can ask for an outline before the first draw pass.
        alphaMask(bounds.width(), bounds.height())
        if (!cachedMaskIsCircular) {
            super.getOutline(outline)
            return
        }
        outline.setOval(bounds)
        outline.alpha = drawableAlpha / OPAQUE.toFloat()
    }

    override fun isStateful(): Boolean = listOfNotNull(source, background, mask, foreground)
        .any(Drawable::isStateful)

    override fun onStateChange(state: IntArray): Boolean {
        var changed = false
        listOfNotNull(source, background, mask, foreground).forEach { drawable ->
            changed = drawable.setState(state) || changed
        }
        if (changed) {
            maskDirty = true
            invalidateSelf()
        }
        return changed
    }

    override fun onLevelChange(level: Int): Boolean {
        var changed = false
        listOfNotNull(source, background, mask, foreground).forEach { drawable ->
            changed = drawable.setLevel(level) || changed
        }
        if (changed) {
            maskDirty = true
            invalidateSelf()
        }
        return changed
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        listOfNotNull(source, background, mask, foreground).forEach { drawable ->
            drawable.setVisible(visible, restart)
        }
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        maskDirty = true
    }

    override fun invalidateDrawable(who: Drawable) {
        if (who === mask || who === background) maskDirty = true
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) {
        scheduleSelf(what, whenMillis)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
    }

    private fun alphaMask(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val existing = cachedMask
        if (!maskDirty && existing != null && existing.width == width && existing.height == height) {
            return existing
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(bitmap)
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val shapeLayer = mask ?: background ?: return null
        shapeLayer.bounds = Rect(0, 0, width, height)
        shapeLayer.draw(maskCanvas)
        // iconback alpha describes visible content directly; only standard
        // iconmask resources can use the inverse-alpha convention.
        val inverseMask = mask != null && isInverseMask(bitmap)
        maskPaint.xfermode = PorterDuffXfermode(
            if (inverseMask) PorterDuff.Mode.DST_OUT else PorterDuff.Mode.DST_IN,
        )
        cachedMaskIsCircular = isCircularMask(bitmap, inverseMask)
        cachedMask?.recycle()
        cachedMask = bitmap
        maskDirty = false
        return bitmap
    }

    private fun scaledBounds(bounds: Rect): Rect {
        val halfWidth = bounds.width() * sourceScale / 2f
        val halfHeight = bounds.height() * sourceScale / 2f
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        sourceBounds.set(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight,
        )
        return Rect(
            sourceBounds.left.roundToInt(),
            sourceBounds.top.roundToInt(),
            sourceBounds.right.roundToInt(),
            sourceBounds.bottom.roundToInt(),
        )
    }

    /**
     * Standard appfilter masks normally have alpha in the area to remove
     * (opaque corners, transparent centre).  Detect that layout instead of
     * hard-coding it so conventional non-inverted circle masks still work.
     */
    private fun isInverseMask(bitmap: Bitmap): Boolean {
        val centreAlpha = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) ushr 24
        val edgeAlpha = listOf(
            bitmap.getPixel(0, 0) ushr 24,
            bitmap.getPixel(bitmap.width - 1, 0) ushr 24,
            bitmap.getPixel(0, bitmap.height - 1) ushr 24,
            bitmap.getPixel(bitmap.width - 1, bitmap.height - 1) ushr 24,
        ).average()
        return edgeAlpha - centreAlpha > MASK_DIRECTION_THRESHOLD
    }

    private fun isCircularMask(bitmap: Bitmap, inverseMask: Boolean): Boolean {
        if (bitmap.width < 8 || bitmap.height < 8) return false
        val middleY = bitmap.height / 2
        val middleX = bitmap.width / 2
        val topWidth = visiblePixelCount(bitmap, row = 0, inverseMask = inverseMask)
        val middleWidth = visiblePixelCount(bitmap, row = middleY, inverseMask = inverseMask)
        val leftHeight = visiblePixelCount(bitmap, column = 0, inverseMask = inverseMask)
        val middleHeight = visiblePixelCount(bitmap, column = middleX, inverseMask = inverseMask)

        // A circle reaches the top/left boundary at only a tiny central arc,
        // while a rounded square retains a noticeably long straight segment.
        // Keep the test deliberately conservative: unknown pack masks use the
        // normal Drawable outline instead of an incorrect oval.
        return middleWidth > 0 && middleHeight > 0 &&
            topWidth * CIRCLE_EDGE_RATIO_DENOMINATOR <= middleWidth &&
            leftHeight * CIRCLE_EDGE_RATIO_DENOMINATOR <= middleHeight
    }

    private fun visiblePixelCount(bitmap: Bitmap, row: Int? = null, column: Int? = null, inverseMask: Boolean): Int {
        var count = 0
        if (row != null) {
            for (x in 0 until bitmap.width) {
                if (isVisibleMaskPixel(bitmap.getPixel(x, row) ushr 24, inverseMask)) count++
            }
        } else if (column != null) {
            for (y in 0 until bitmap.height) {
                if (isVisibleMaskPixel(bitmap.getPixel(column, y) ushr 24, inverseMask)) count++
            }
        }
        return count
    }

    private fun isVisibleMaskPixel(alpha: Int, inverseMask: Boolean): Boolean =
        if (inverseMask) alpha < MASK_VISIBLE_ALPHA_THRESHOLD else alpha >= MASK_VISIBLE_ALPHA_THRESHOLD

    private fun intrinsicDimension(selector: (Drawable) -> Int): Int {
        return listOfNotNull(background, mask, source, foreground)
            .asSequence()
            .map(selector)
            .firstOrNull { it > 0 }
            ?: -1
    }

    private fun applyAlphaToVisibleLayers() {
        source.alpha = drawableAlpha
        background?.alpha = drawableAlpha
        foreground?.alpha = drawableAlpha
        // Keep mask alpha at 255.  The source is already alpha-adjusted, and
        // applying the same value to the DST_IN mask would square the alpha.
        mask?.alpha = OPAQUE
    }

    private companion object {
        const val OPAQUE = 255
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
        const val MASK_DIRECTION_THRESHOLD = 32.0
        const val MASK_VISIBLE_ALPHA_THRESHOLD = 128
        const val CIRCLE_EDGE_RATIO_DENOMINATOR = 4
    }
}
