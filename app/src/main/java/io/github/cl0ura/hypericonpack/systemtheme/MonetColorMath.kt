package io.github.cl0ura.hypericonpack.systemtheme

import kotlin.math.roundToInt

/** Pure colour helpers used by custom Monet palettes. */
internal object MonetColorMath {
    private const val MIN_CUSTOM_CONTRAST_SQUARED = 96 * 96

    fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    fun mix(start: Int, end: Int, amount: Float): Int {
        fun channel(from: Int, to: Int): Int = (from + (to - from) * amount).roundToInt()
        return opaque(
            (channel(red(start), red(end)) shl 16) or
                (channel(green(start), green(end)) shl 8) or
                channel(blue(start), blue(end)),
        )
    }

    fun ensureContrast(background: Int, foreground: Int): Int {
        if (distance(background, foreground) >= MIN_CUSTOM_CONTRAST_SQUARED) return foreground
        val black = argb(24, 24, 24)
        val white = argb(244, 244, 244)
        return if (distance(background, black) >= distance(background, white)) black else white
    }

    fun distance(first: Int, second: Int): Int {
        val redDifference = red(first) - red(second)
        val greenDifference = green(first) - green(second)
        val blueDifference = blue(first) - blue(second)
        return redDifference * redDifference +
            greenDifference * greenDifference +
            blueDifference * blueDifference
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun red(color: Int): Int = color ushr 16 and 0xFF

    private fun green(color: Int): Int = color ushr 8 and 0xFF

    private fun blue(color: Int): Int = color and 0xFF
}
