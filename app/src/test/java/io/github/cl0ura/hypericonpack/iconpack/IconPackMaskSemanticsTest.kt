package io.github.cl0ura.hypericonpack.iconpack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackMaskSemanticsTest {
    @Test
    fun `opaque corners and transparent centre are inverse`() {
        assertTrue(
            IconPackMaskSemantics.isInverse(SIZE, SIZE) { x, y ->
                if (insideCircle(x, y)) 0 else 255
            },
        )
    }

    @Test
    fun `transparent corners and opaque centre are conventional`() {
        assertFalse(
            IconPackMaskSemantics.isInverse(SIZE, SIZE) { x, y ->
                if (insideCircle(x, y)) 255 else 0
            },
        )
    }

    @Test
    fun `uniform alpha is not treated as inverse`() {
        assertFalse(IconPackMaskSemantics.isInverse(SIZE, SIZE) { _, _ -> 255 })
    }

    private fun insideCircle(x: Int, y: Int): Boolean {
        val offsetX = x - SIZE / 2
        val offsetY = y - SIZE / 2
        return offsetX * offsetX + offsetY * offsetY <= RADIUS * RADIUS
    }

    private companion object {
        const val SIZE = 96
        const val RADIUS = 38
    }
}
