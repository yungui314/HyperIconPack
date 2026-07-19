package io.github.cl0ura.hypericonpack.iconpack

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * Produces an AdaptiveIconDrawable only for a clearly round, themed bitmap.
 *
 * HyperOS's FloatingIconView2 has a separate transition path selected with
 * `drawable is AdaptiveIconDrawable`.  Supplying merely an oval Outline is
 * not enough for that path, so an otherwise correct round PNG could still
 * briefly allocate a rectangular animation layer.  This wrapper is used only
 * for the launch/return animation argument; the desktop and folder retain
 * the ThemeResourcesSystem Drawable unchanged.
 */
internal object ThemeAnimationAdaptiveDrawable {
    fun create(source: Drawable): Drawable? {
        if (!ThemeAnimationOutlineDrawable.isCircularAlpha(source)) return null

        // Do not steal the target view's Drawable callback.  BitmapDrawables
        // returned by ThemeResourcesSystem have a ConstantState; if a vendor
        // Drawable does not, leave it on the conservative outline-only path.
        val copy = source.constantState?.newDrawable()?.mutate() ?: return null
        return AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT),
            AdaptiveForegroundDrawable(copy),
        )
    }
}

/**
 * Android gives AdaptiveIcon foreground layers a 150% viewport so launchers
 * can pan them without exposing an edge.  A static themed PNG already has its
 * final 100% composition, therefore it is drawn in the central 2/3 safe zone
 * to preserve the exact apparent size seen on the desktop.
 */
private class AdaptiveForegroundDrawable(
    private val source: Drawable,
) : Drawable(), Drawable.Callback {
    private var drawableAlpha = 255

    init {
        source.callback = this
    }

    override fun draw(canvas: Canvas) {
        val targetBounds = bounds
        if (targetBounds.isEmpty) return
        val insetX = (targetBounds.width() * FOREGROUND_SAFE_INSET).roundToInt()
        val insetY = (targetBounds.height() * FOREGROUND_SAFE_INSET).roundToInt()
        val oldBounds = Rect(source.bounds)
        try {
            source.setBounds(
                targetBounds.left + insetX,
                targetBounds.top + insetY,
                targetBounds.right - insetX,
                targetBounds.bottom - insetY,
            )
            source.draw(canvas)
        } finally {
            source.bounds = oldBounds
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        source.alpha = drawableAlpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

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
        if (changed) invalidateSelf()
        return changed
    }

    override fun onLevelChange(level: Int): Boolean {
        val changed = source.setLevel(level)
        if (changed) invalidateSelf()
        return changed
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) =
        scheduleSelf(what, whenMillis)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    private companion object {
        // 150% foreground viewport: (150 - 100) / 2 / 150 = 1 / 6.
        const val FOREGROUND_SAFE_INSET = 1f / 6f
    }
}
