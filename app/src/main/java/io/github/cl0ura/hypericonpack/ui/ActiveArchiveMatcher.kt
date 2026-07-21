package io.github.cl0ura.hypericonpack.ui

/** Selects the local archive whose bytes match the active Root marker. */
internal fun <T> findMatchingActiveArchive(
    activeSha256: String?,
    preferred: T?,
    candidates: Iterable<T>,
    sha256: (T) -> String?,
): T? {
    val expected = activeSha256?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (preferred != null && sha256(preferred).equals(expected, ignoreCase = true)) {
        return preferred
    }
    return candidates.firstOrNull { candidate ->
        candidate != preferred && sha256(candidate).equals(expected, ignoreCase = true)
    }
}
