package io.github.cl0ura.hypericonpack.iconpack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackShapeInferenceTest {
    @Test
    fun `recognises a full circular badge`() {
        val size = 64
        val center = (size - 1) / 2.0
        val radius = 28.0
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if ((x - center) * (x - center) + (y - center) * (y - center) <= radius * radius) {
                OPAQUE
            } else {
                0
            }
        }

        assertTrue(IconPackShapeInference.infer(List(8) { pixels }, size) != null)
    }

    @Test
    fun `recognises a rounded square badge`() {
        val size = 64
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (x in 4..59 && y in 4..59 && !(x < 10 && y < 10) && !(x > 53 && y < 10) &&
                !(x < 10 && y > 53) && !(x > 53 && y > 53)
            ) {
                OPAQUE
            } else {
                0
            }
        }

        assertTrue(IconPackShapeInference.infer(List(8) { pixels }, size) != null)
    }

    @Test
    fun `rejects a small circular glyph without a badge`() {
        val size = 64
        val center = (size - 1) / 2.0
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if ((x - center) * (x - center) + (y - center) * (y - center) <= 12.0 * 12.0) OPAQUE else 0
        }

        assertFalse(IconPackShapeInference.infer(List(8) { pixels }, size) != null)
    }

    @Test
    fun `rejects inconsistent large silhouettes`() {
        val size = 64
        val vertical = alphaRect(size, 20, 4, 43, 59)
        val horizontal = alphaRect(size, 4, 20, 59, 43)

        assertFalse(IconPackShapeInference.infer(List(4) { vertical } + List(4) { horizontal }, size) != null)
    }

    private fun alphaRect(size: Int, left: Int, top: Int, right: Int, bottom: Int): IntArray =
        IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (x in left..right && y in top..bottom) OPAQUE else 0
        }

    private companion object {
        const val OPAQUE = -0x1000000
    }
}
