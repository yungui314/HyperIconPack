package io.github.cl0ura.hypericonpack.systemtheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class IconPngRendererTest {
    @Test
    fun `native monochrome quality accepts a centered contained glyph`() {
        val size = 64
        val shape = alphaRect(size, 8, 8, 55, 55)
        val glyph = alphaRect(size, 22, 20, 41, 43)

        assertTrue(NativeMonochromeQuality.isAcceptable(shape, glyph, size))
    }

    @Test
    fun `native monochrome quality rejects a glyph filling the silhouette`() {
        val size = 64
        val shape = alphaRect(size, 8, 8, 55, 55)

        assertFalse(NativeMonochromeQuality.isAcceptable(shape, shape.copyOf(), size))
    }

    @Test
    fun `native monochrome quality rejects an off-centre edge glyph`() {
        val size = 64
        val shape = alphaRect(size, 8, 8, 55, 55)
        val glyph = alphaRect(size, 8, 12, 25, 48)

        assertFalse(NativeMonochromeQuality.isAcceptable(shape, glyph, size))
    }

    @Test
    fun `parallelism stays within renderer bound`() {
        assertEquals(0, IconPngRenderer.boundedParallelism(requestCount = 0, requestedParallelism = 3))
        assertEquals(1, IconPngRenderer.boundedParallelism(requestCount = 1, requestedParallelism = 3))
        assertEquals(2, IconPngRenderer.boundedParallelism(requestCount = 10, requestedParallelism = 2))
        assertEquals(3, IconPngRenderer.boundedParallelism(requestCount = 10, requestedParallelism = 20))
    }


    @Test
    fun `task source is resolved by render worker`() {
        val callerThread = Thread.currentThread().name
        val resolverThreads = ConcurrentHashMap.newKeySet<String>()
        val tasks = (1..6).map { index ->
            PngRenderTask(key = index.toString()) {
                resolverThreads += Thread.currentThread().name
                null
            }
        }

        val results = IconPngRenderer.renderParallelTasks(
            tasks = tasks,
            palette = null,
            isCancelled = { false },
            parallelism = 3,
        )

        assertEquals(tasks.size, results.size)
        assertTrue(results.values.all { result -> result.fallback == PngRenderFallback.FAILED })
        assertTrue(resolverThreads.isNotEmpty())
        assertTrue(resolverThreads.none { thread -> thread == callerThread })
        assertTrue(resolverThreads.all { thread -> thread.startsWith("HyperIconPack-render-") })
    }

    private fun alphaRect(
        size: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): IntArray = IntArray(size * size).also { pixels ->
        for (y in top..bottom) {
            for (x in left..right) pixels[y * size + x] = 0xFF000000.toInt()
        }
    }
}
