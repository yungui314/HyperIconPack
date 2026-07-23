package io.github.cl0ura.hypericonpack.systemtheme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import io.github.cl0ura.hypericonpack.iconpack.IconPackMaskSemantics
import io.github.cl0ura.hypericonpack.iconpack.IconPackThemeFallback
import java.io.ByteArrayOutputStream

internal enum class NativeThemeMaskSource {
    MASK,
    INVERTED_MASK,
    PATTERN_ALPHA,
    OPAQUE,
}

internal data class NativeThemeFallbackAssets(
    val entries: Map<String, ByteArray>,
    val scale: Float,
    val maskSource: NativeThemeMaskSource,
    val hasPattern: Boolean,
    val hasBorder: Boolean,
)

/** Renders appfilter fallback declarations into Xiaomi's reserved icon resources. */
internal object NativeThemeFallbackWriter {
    fun render(fallback: IconPackThemeFallback): NativeThemeFallbackAssets {
        val maskBitmap = fallback.mask?.let(::renderDrawable)
        val patternBitmap = fallback.pattern?.let(::renderDrawable)
        val borderBitmap = fallback.border?.let(::renderDrawable)
        val maskResult = createMask(maskBitmap, patternBitmap)
        val transparent = transparentPng()
        return try {
            NativeThemeFallbackAssets(
                entries = linkedMapOf(
                    IconArchiveEntryNames.ICON_MASK_ENTRY to encodePng(maskResult.bitmap),
                    IconArchiveEntryNames.ICON_BACKGROUND_ENTRY to transparent,
                    IconArchiveEntryNames.ICON_PATTERN_ENTRY to (patternBitmap?.let(::encodePng) ?: transparent),
                    IconArchiveEntryNames.ICON_BORDER_ENTRY to (borderBitmap?.let(::encodePng) ?: transparent),
                ),
                scale = fallback.scale,
                maskSource = maskResult.source,
                hasPattern = patternBitmap != null,
                hasBorder = borderBitmap != null,
            )
        } finally {
            maskResult.bitmap.recycle()
            maskBitmap?.recycle()
            patternBitmap?.recycle()
            borderBitmap?.recycle()
        }
    }

    private fun createMask(mask: Bitmap?, pattern: Bitmap?): MaskResult {
        val explicitMaskHasAlpha = mask?.let(::hasVisibleAlpha) == true
        val source = when {
            explicitMaskHasAlpha -> mask
            pattern?.let(::hasVisibleAlpha) == true -> pattern
            else -> null
        }
        val inverse = source === mask && IconPackMaskSemantics.isInverse(requireNotNull(mask))
        val pixels = IntArray(RENDER_SIZE_PX * RENDER_SIZE_PX)
        if (source == null) {
            pixels.fill(Color.WHITE)
        } else {
            source.getPixels(pixels, 0, RENDER_SIZE_PX, 0, 0, RENDER_SIZE_PX, RENDER_SIZE_PX)
            pixels.indices.forEach { index ->
                val sourceAlpha = pixels[index] ushr 24
                val alpha = if (inverse) OPAQUE - sourceAlpha else sourceAlpha
                pixels[index] = Color.argb(alpha, OPAQUE, OPAQUE, OPAQUE)
            }
        }
        return MaskResult(
            bitmap = Bitmap.createBitmap(
                pixels,
                RENDER_SIZE_PX,
                RENDER_SIZE_PX,
                Bitmap.Config.ARGB_8888,
            ),
            source = when {
                source == null -> NativeThemeMaskSource.OPAQUE
                source === pattern -> NativeThemeMaskSource.PATTERN_ALPHA
                inverse -> NativeThemeMaskSource.INVERTED_MASK
                else -> NativeThemeMaskSource.MASK
            },
        )
    }

    private fun renderDrawable(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(RENDER_SIZE_PX, RENDER_SIZE_PX, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(drawable.bounds)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawable.setBounds(0, 0, RENDER_SIZE_PX, RENDER_SIZE_PX)
            drawable.draw(canvas)
            bitmap
        } catch (throwable: Throwable) {
            bitmap.recycle()
            throw throwable
        } finally {
            drawable.bounds = previousBounds
        }
    }

    private fun hasVisibleAlpha(bitmap: Bitmap): Boolean {
        val pixels = IntArray(RENDER_SIZE_PX * RENDER_SIZE_PX)
        bitmap.getPixels(pixels, 0, RENDER_SIZE_PX, 0, 0, RENDER_SIZE_PX, RENDER_SIZE_PX)
        return pixels.any { pixel -> pixel ushr 24 > 0 }
    }

    private fun transparentPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(RENDER_SIZE_PX, RENDER_SIZE_PX, Bitmap.Config.ARGB_8888)
        return try {
            encodePng(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "原生 fallback PNG 编码失败" }
        output.toByteArray()
    }

    private data class MaskResult(
        val bitmap: Bitmap,
        val source: NativeThemeMaskSource,
    )

    private const val RENDER_SIZE_PX = IconPngRenderer.RENDER_SIZE_PX
    private const val OPAQUE = 255
}
