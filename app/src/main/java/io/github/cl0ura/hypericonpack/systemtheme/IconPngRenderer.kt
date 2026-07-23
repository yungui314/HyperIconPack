package io.github.cl0ura.hypericonpack.systemtheme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class NativeMonochromeSource(
    val glyph: Drawable,
    val silhouette: Drawable,
    val recoveryDrawable: Drawable,
)

internal enum class PngRenderFallback {
    NONE,
    SIMPLIFIED,
    GUARANTEED,
    FAILED,
}

internal data class PngRenderResult(
    val png: ByteArray?,
    val fallback: PngRenderFallback,
    val failure: Throwable? = null,
    val usedNativeMonochrome: Boolean = false,
)

internal data class PngRenderSource(
    val drawable: Drawable,
    val nativeMonochrome: NativeMonochromeSource? = null,
)

internal data class PngRenderRequest(
    val key: String,
    val drawable: Drawable,
    val nativeMonochrome: NativeMonochromeSource? = null,
)

internal data class PngRenderTask(
    val key: String,
    val resolveSource: () -> PngRenderSource?,
)

internal object IconPngRenderer {
    const val RENDER_SIZE_PX = 256

    fun render(
        drawable: Drawable,
        palette: GlobalMonetPalette?,
        nativeMonochrome: NativeMonochromeSource? = null,
    ): PngRenderResult {
        return try {
            ByteArrayOutputStream().use { output ->
                if (palette == null) {
                    renderDrawableAsPng(drawable, output)
                    return@use PngRenderResult(output.toByteArray(), PngRenderFallback.NONE)
                }

                val highFidelity = renderAttempt {
                    if (nativeMonochrome != null) {
                        GlobalMonetIconRenderer.renderNativeMonochrome(
                            glyph = nativeMonochrome.glyph,
                            silhouette = nativeMonochrome.silhouette,
                            palette = palette,
                            size = RENDER_SIZE_PX,
                            output = output,
                        )
                    } else {
                        GlobalMonetIconRenderer.render(
                            drawable = drawable,
                            palette = palette,
                            size = RENDER_SIZE_PX,
                            output = output,
                        )
                    }
                }
                if (highFidelity) {
                    return@use PngRenderResult(output.toByteArray(), PngRenderFallback.NONE)
                }

                output.reset()
                val simplified = renderAttempt {
                    GlobalMonetIconRenderer.renderSimplified(
                        drawable = nativeMonochrome?.recoveryDrawable ?: drawable,
                        palette = palette,
                        size = RENDER_SIZE_PX,
                        output = output,
                    )
                }
                if (simplified) {
                    return@use PngRenderResult(output.toByteArray(), PngRenderFallback.SIMPLIFIED)
                }

                output.reset()
                val guaranteed = renderAttempt {
                    GlobalMonetIconRenderer.renderGuaranteed(
                        drawable = nativeMonochrome?.recoveryDrawable ?: drawable,
                        palette = palette,
                        size = RENDER_SIZE_PX,
                        output = output,
                    )
                }
                if (guaranteed) {
                    PngRenderResult(output.toByteArray(), PngRenderFallback.GUARANTEED)
                } else {
                    PngRenderResult(null, PngRenderFallback.FAILED)
                }
            }
        } catch (cancelled: ConversionCancelledException) {
            throw cancelled
        } catch (throwable: Exception) {
            PngRenderResult(null, PngRenderFallback.FAILED, throwable)
        }
    }

    fun renderParallel(
        requests: List<PngRenderRequest>,
        palette: GlobalMonetPalette?,
        isCancelled: () -> Boolean,
        parallelism: Int = recommendedParallelism(),
    ): Map<String, PngRenderResult> = renderParallelTasks(
        tasks = requests.map { request ->
            PngRenderTask(key = request.key) {
                PngRenderSource(
                    drawable = request.drawable,
                    nativeMonochrome = request.nativeMonochrome,
                )
            }
        },
        palette = palette,
        isCancelled = isCancelled,
        parallelism = parallelism,
    )

    /** Resolves each Drawable on the same worker that immediately draws it. */
    fun renderParallelTasks(
        tasks: List<PngRenderTask>,
        palette: GlobalMonetPalette?,
        isCancelled: () -> Boolean,
        parallelism: Int = recommendedParallelism(),
    ): Map<String, PngRenderResult> {
        if (tasks.isEmpty()) return emptyMap()
        require(tasks.mapTo(HashSet(tasks.size), PngRenderTask::key).size == tasks.size) {
            "PNG 渲染请求键重复"
        }
        val workerCount = boundedParallelism(tasks.size, parallelism)
        if (workerCount == 1) {
            return tasks.associate { task ->
                checkCancelled(isCancelled)
                task.key to renderTask(task, palette)
            }
        }

        val threadId = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "HyperIconPack-render-${threadId.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        return try {
            val futures = tasks.map { task ->
                task.key to executor.submit(Callable {
                    checkCancelled(isCancelled)
                    renderTask(task, palette)
                })
            }
            buildMap(tasks.size) {
                futures.forEach { (key, future) ->
                    checkCancelled(isCancelled)
                    put(key, await(future, isCancelled))
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun await(
        future: Future<PngRenderResult>,
        isCancelled: () -> Boolean,
    ): PngRenderResult {
        while (true) {
            checkCancelled(isCancelled)
            try {
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                // Poll cancellation while a costly drawable/Monet render runs.
            } catch (execution: java.util.concurrent.ExecutionException) {
                val cause = execution.cause
                if (cause is ConversionCancelledException) throw cause
                throw cause ?: execution
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }

    private fun renderDrawableAsPng(
        drawable: Drawable,
        output: OutputStream,
        size: Int = RENDER_SIZE_PX,
    ) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalStateException("PNG 编码失败")
            }
        } finally {
            drawable.bounds = oldBounds
            bitmap.recycle()
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw ConversionCancelledException()
    }

    private inline fun renderAttempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: ConversionCancelledException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun renderTask(
        task: PngRenderTask,
        palette: GlobalMonetPalette?,
    ): PngRenderResult {
        val source = try {
            task.resolveSource()
        } catch (cancelled: ConversionCancelledException) {
            throw cancelled
        } catch (throwable: Exception) {
            return PngRenderResult(null, PngRenderFallback.FAILED, throwable)
        } ?: return PngRenderResult(null, PngRenderFallback.FAILED)
        val native = source.nativeMonochrome
        val result = if (native == null) {
            render(source.drawable, palette)
        } else {
            // Cached native monochrome layers can be shared by aliases. Their
            // Drawable bounds are mutable, so serialize only requests sharing
            // that glyph while unrelated icons continue in parallel.
            synchronized(native.glyph) {
                render(source.drawable, palette, native)
            }
        }
        return if (native == null) result else result.copy(usedNativeMonochrome = true)
    }

    private fun recommendedParallelism(): Int {
        // Bitmaps and drawable resources are memory-heavy. Two or three
        // workers saturate mid/high-end phones without multiplying peak memory.
        return Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_PARALLELISM)
    }

    internal fun boundedParallelism(requestCount: Int, requestedParallelism: Int): Int {
        require(requestCount >= 0) { "PNG 渲染请求数量不能为负数" }
        if (requestCount == 0) return 0
        return requestedParallelism.coerceIn(1, MAX_PARALLELISM).coerceAtMost(requestCount)
    }

    private const val MAX_PARALLELISM = 3
}
