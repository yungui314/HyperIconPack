package io.github.cl0ura.hypericonpack.iconpack

import java.util.Locale

/**
 * Normalizes appfilter ComponentInfo strings into package/class lookup keys.
 * Extracted so the pure parsing rules can be unit-tested without Resources.
 */
internal object IconPackComponentNormalizer {
    fun normalize(rawComponent: String): String? {
        var value = rawComponent.trim()
        if (value.startsWith("ComponentInfo{") && value.endsWith("}")) {
            value = value.substring("ComponentInfo{".length, value.length - 1)
        }
        // Pseudo-components such as :CALENDAR are not application activities.
        if (value.startsWith(':')) return null

        val separator = value.indexOf('/')
        if (separator <= 0 || separator == value.lastIndex) return null
        val packageName = value.substring(0, separator)
        val className = value.substring(separator + 1)
        if (packageName.isBlank() || className.isBlank()) return null

        val fullClassName = when {
            className.startsWith('.') -> packageName + className
            '.' !in className -> "$packageName.$className"
            else -> className
        }
        return "${packageName.lowercase(Locale.ROOT)}/$fullClassName"
    }
}
