package io.github.cl0ura.hypericonpack.iconpack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconMaskGeometryTest {
    @Test
    fun `normal and inverse masks produce the same normalized path`() {
        val conventional = pixels(inverse = false)
        val inverse = pixels(inverse = true)

        val normalResult = IconMaskGeometry.normalize(SIZE, SIZE, conventional)
        val inverseResult = IconMaskGeometry.normalize(SIZE, SIZE, inverse)

        assertEquals(normalResult.configPath, inverseResult.configPath)
        assertArrayEquals(normalResult.pixels, inverseResult.pixels)
        assertTrue(normalResult.configPath.startsWith("M"))
        assertTrue(normalResult.configPath.endsWith("Z"))
    }

    private fun pixels(inverse: Boolean): IntArray = IntArray(SIZE * SIZE) { index ->
        val x = index % SIZE
        val y = index / SIZE
        val visible = insideRoundedSquare(x, y)
        val alpha = if (visible.xor(inverse)) 255 else 0
        (alpha shl 24) or 0x00FFFFFF
    }

    private fun insideRoundedSquare(x: Int, y: Int): Boolean {
        if (x in INSET until SIZE - INSET && y in INSET + RADIUS until SIZE - INSET - RADIUS) return true
        if (y in INSET until SIZE - INSET && x in INSET + RADIUS until SIZE - INSET - RADIUS) return true
        val centreX = if (x < SIZE / 2) INSET + RADIUS else SIZE - INSET - RADIUS - 1
        val centreY = if (y < SIZE / 2) INSET + RADIUS else SIZE - INSET - RADIUS - 1
        val dx = x - centreX
        val dy = y - centreY
        return dx * dx + dy * dy <= RADIUS * RADIUS
    }

    private companion object {
        const val SIZE = 96
        const val INSET = 4
        const val RADIUS = 18
    }
}
