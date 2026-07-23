package io.github.cl0ura.hypericonpack.systemtheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetColorMathTest {
    @Test
    fun `opaque forces full alpha`() {
        val translucent = 0x80FF0000.toInt()
        assertEquals(0xFFFF0000.toInt(), MonetColorMath.opaque(translucent))
    }

    @Test
    fun `mix blends channels linearly`() {
        val mixed = MonetColorMath.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        // 255 * 0.5 rounds half-up to 128 with Kotlin roundToInt.
        assertEquals(128, mixed ushr 16 and 0xFF)
        assertEquals(128, mixed ushr 8 and 0xFF)
        assertEquals(128, mixed and 0xFF)
    }

    @Test
    fun `ensureContrast keeps readable foreground`() {
        val bg = 0xFFFAFAFA.toInt()
        val low = 0xFFF0F0F0.toInt()
        val adjusted = MonetColorMath.ensureContrast(bg, low)
        assertTrue(MonetColorMath.distance(bg, adjusted) >= 96 * 96)
    }

    @Test
    fun `ensureContrast keeps already contrasting colour`() {
        val bg = 0xFFFFFFFF.toInt()
        val fg = 0xFF000000.toInt()
        assertEquals(fg, MonetColorMath.ensureContrast(bg, fg))
    }
}
