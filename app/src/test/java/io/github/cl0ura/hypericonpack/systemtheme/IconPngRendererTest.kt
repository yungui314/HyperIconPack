package io.github.cl0ura.hypericonpack.systemtheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class IconPngRendererTest {
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
}
