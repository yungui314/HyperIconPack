package io.github.cl0ura.hypericonpack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveArchiveMatcherTest {
    @Test
    fun `preferred archive wins when it matches active hash`() {
        val matched = findMatchingActiveArchive(
            activeSha256 = "active",
            preferred = "saved",
            candidates = listOf("other"),
            sha256 = mapOf("saved" to "active", "other" to "active")::get,
        )

        assertEquals("saved", matched)
    }

    @Test
    fun `cached archive repairs a stale preferred path`() {
        val matched = findMatchingActiveArchive(
            activeSha256 = "active",
            preferred = "stale",
            candidates = listOf("old", "installed"),
            sha256 = mapOf(
                "stale" to "previous",
                "old" to "older",
                "installed" to "active",
            )::get,
        )

        assertEquals("installed", matched)
    }

    @Test
    fun `no local hash match remains unknown`() {
        val matched = findMatchingActiveArchive(
            activeSha256 = "active",
            preferred = "stale",
            candidates = listOf("old"),
            sha256 = mapOf("stale" to "previous", "old" to "older")::get,
        )

        assertNull(matched)
    }

    @Test
    fun `missing active hash cannot select an archive`() {
        val matched = findMatchingActiveArchive(
            activeSha256 = null,
            preferred = "saved",
            candidates = listOf("other"),
            sha256 = { "unused" },
        )

        assertNull(matched)
    }
}
