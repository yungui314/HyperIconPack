package io.github.cl0ura.hypericonpack.iconpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPackComponentNormalizerTest {
    @Test
    fun `component info wrapper is stripped`() {
        assertEquals(
            "com.example.app/com.example.app.MainActivity",
            IconPackComponentNormalizer.normalize("ComponentInfo{com.example.app/com.example.app.MainActivity}"),
        )
    }

    @Test
    fun `relative class name is expanded`() {
        assertEquals(
            "com.example.app/com.example.app.MainActivity",
            IconPackComponentNormalizer.normalize("com.example.app/.MainActivity"),
        )
    }

    @Test
    fun `short class name is expanded`() {
        assertEquals(
            "com.example.app/com.example.app.MainActivity",
            IconPackComponentNormalizer.normalize("com.example.app/MainActivity"),
        )
    }

    @Test
    fun `package name is lowercased while class keeps case`() {
        assertEquals(
            "com.example.app/com.example.app.MainActivity",
            IconPackComponentNormalizer.normalize("Com.Example.App/com.example.app.MainActivity"),
        )
    }

    @Test
    fun `calendar pseudo components are ignored`() {
        assertNull(IconPackComponentNormalizer.normalize(":CALENDAR"))
    }

    @Test
    fun `malformed values are ignored`() {
        assertNull(IconPackComponentNormalizer.normalize("com.example.app"))
        assertNull(IconPackComponentNormalizer.normalize("/MainActivity"))
        assertNull(IconPackComponentNormalizer.normalize("com.example.app/"))
    }
}
