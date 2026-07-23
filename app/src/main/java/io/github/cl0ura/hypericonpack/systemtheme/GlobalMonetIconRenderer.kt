package io.github.cl0ura.hypericonpack.systemtheme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.io.OutputStream
import kotlin.math.roundToInt

/** The one, system-wide tonal family used by every converted icon. */
internal data class GlobalMonetPalette(
    val outerContainer: Int,
    val lightTone: Int,
    val darkTone: Int,
) {
    val cacheFingerprint: String
        get() = listOf(outerContainer, lightTone, darkTone)
            .joinToString(":") { color -> color.toUInt().toString(16) }
}

internal fun currentMonetPaletteFingerprint(
    context: Context,
    globalMonetIcons: Boolean,
    monetCustomColors: Boolean,
    monetBackgroundColor: Int,
    monetForegroundColor: Int,
): String = if (globalMonetIcons) {
    SystemMonetIconPalette.global(
        context = context,
        useCustomColors = monetCustomColors,
        backgroundColor = monetBackgroundColor,
        foregroundColor = monetForegroundColor,
    ).cacheFingerprint
} else {
    ""
}

/** Reads Android's wallpaper-derived Accent 1 palette once for a conversion. */
internal object SystemMonetIconPalette {
    fun global(
        context: Context,
        useCustomColors: Boolean = false,
        backgroundColor: Int = 0xFFE8DEF8.toInt(),
        foregroundColor: Int = 0xFF4A4458.toInt(),
    ): GlobalMonetPalette {
        if (useCustomColors) {
            val background = MonetColorMath.opaque(backgroundColor)
            val foreground = MonetColorMath.ensureContrast(background, MonetColorMath.opaque(foregroundColor))
            return GlobalMonetPalette(
                outerContainer = background,
                lightTone = MonetColorMath.mix(background, foreground, 0.35f),
                darkTone = foreground,
            )
        }
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            GlobalMonetPalette(
                outerContainer = color(context, "system_accent1_700", Color.rgb(54, 74, 110)),
                lightTone = color(context, "system_accent1_600", Color.rgb(83, 107, 150)),
                darkTone = color(context, "system_accent1_100", Color.rgb(218, 226, 255)),
            )
        } else {
            GlobalMonetPalette(
                outerContainer = color(context, "system_accent1_100", Color.rgb(220, 232, 255)),
                lightTone = color(context, "system_accent1_200", Color.rgb(188, 210, 248)),
                darkTone = color(context, "system_accent1_800", Color.rgb(28, 56, 102)),
            )
        }
    }

    private fun color(context: Context, name: String, fallback: Int): Int {
        val resourceId = context.resources.getIdentifier(name, "color", "android")
        return if (resourceId == 0) fallback else {
            runCatching { context.resources.getColor(resourceId, context.theme) }.getOrDefault(fallback)
        }
    }

}

/**
 * Produces a Monet themed PNG while retaining the input drawable's own alpha
 * silhouette.  That last point is deliberately non-negotiable: icon-pack
 * circles, rounded squares and original application icons must not change
 * shape merely because global Monet is toggled on.
 *
 * Earlier versions used only a global luminance remap.  It works for a simple
 * two-colour logo, but it loses the subject of a detailed icon whenever its
 * background, illustration and text overlap in luminance.  This version first
 * detects a stable edge background.  When found, it uses colour-distance
 * segmentation (container -> light Monet tone, foreground -> dark Monet tone)
 * and only falls back to luminance for artwork without a coherent container.
 */
internal object GlobalMonetIconRenderer {
    fun render(
        drawable: Drawable,
        palette: GlobalMonetPalette,
        size: Int,
        output: OutputStream,
    ) = renderClustered(drawable, palette, size, output, maxClusters = 6)

    /**
     * Renders Android 13+'s semantic monochrome foreground over a real
     * adaptive silhouette.  The foreground is explicitly intersected with the
     * silhouette alpha: some OEM monochrome resources paint outside their
     * adaptive mask, which was the source of individual square icons in an
     * otherwise circular icon pack.
     */
    fun renderNativeMonochrome(
        glyph: Drawable,
        silhouette: Drawable,
        palette: GlobalMonetPalette,
        size: Int,
        output: OutputStream,
    ) {
        require(size > 0) { "图标渲染尺寸必须大于零" }
        val shapeBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val glyphBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawDrawable(silhouette, shapeBitmap, size)
            drawDrawable(glyph, glyphBitmap, size)
            val shapePixels = IntArray(size * size)
            val glyphPixels = IntArray(size * size)
            shapeBitmap.getPixels(shapePixels, 0, size, 0, 0, size, size)
            glyphBitmap.getPixels(glyphPixels, 0, size, 0, 0, size, size)
            val visibleShape = shapePixels.sumOf { Color.alpha(it).toLong() }
            require(visibleShape > 0L) { "原生图标轮廓没有可见像素" }
            val clippedGlyphTotal = glyphPixels.indices.sumOf { index ->
                Color.alpha(glyphPixels[index]).toLong() * Color.alpha(shapePixels[index]) / OPAQUE
            }
            // OEM and third-party adaptive icons occasionally expose a
            // technically non-null monochrome layer that is almost empty or
            // fills the complete mask. Both cases produce a blank/dark tile,
            // so reject them and let the caller recolour the full source art.
            require(clippedGlyphTotal >= maxOf(MIN_NATIVE_GLYPH_ALPHA, visibleShape / 100)) {
                "原生单色图层没有足够的可见像素"
            }
            require(clippedGlyphTotal * 100 <= visibleShape * MAX_NATIVE_GLYPH_PERCENT) {
                "原生单色图层异常覆盖整个图标"
            }

            val targetPixels = IntArray(shapePixels.size)
            targetPixels.indices.forEach { index ->
                val shapeAlpha = Color.alpha(shapePixels[index])
                val glyphAlpha = Color.alpha(glyphPixels[index])
                // Adaptive masks are alpha, not a boolean. Multiplication
                // preserves antialiased edges and guarantees a glyph cannot
                // reintroduce opaque square pixels outside its silhouette.
                val clippedGlyphAlpha = glyphAlpha * shapeAlpha / OPAQUE
                val underAlpha = shapeAlpha * (OPAQUE - clippedGlyphAlpha) / OPAQUE
                val combinedAlpha = clippedGlyphAlpha + underAlpha
                if (combinedAlpha == 0) return@forEach

                fun compositeChannel(container: Int, foreground: Int): Int =
                    (container * underAlpha + foreground * clippedGlyphAlpha) / combinedAlpha
                targetPixels[index] = Color.argb(
                    combinedAlpha,
                    compositeChannel(Color.red(palette.outerContainer), Color.red(palette.darkTone)),
                    compositeChannel(Color.green(palette.outerContainer), Color.green(palette.darkTone)),
                    compositeChannel(Color.blue(palette.outerContainer), Color.blue(palette.darkTone)),
                )
            }
            result.setPixels(targetPixels, 0, size, 0, 0, size, size)
            compress(result, output, "原生单色 Monet 图标 PNG 编码失败")
        } finally {
            shapeBitmap.recycle()
            glyphBitmap.recycle()
            result.recycle()
        }
    }

    /** Conservative entry point used after a resource-specific render error. */
    fun renderSimplified(
        drawable: Drawable,
        palette: GlobalMonetPalette,
        size: Int,
        output: OutputStream,
    ) = renderClustered(drawable, palette, size, output, maxClusters = 4)

    /**
     * Guaranteed low-complexity Monet fallback. It intentionally avoids edge
     * clustering, native monochrome and percentile analysis; if a Drawable can
     * be drawn at all this produces a non-blank two-tone icon that retains its
     * alpha shape. It is used only after both higher-fidelity paths reject an
     * unusual OEM/third-party resource, so a global Monet archive never
     * silently degrades to the original coloured bitmap for ordinary apps.
     */
    fun renderGuaranteed(
        drawable: Drawable,
        palette: GlobalMonetPalette,
        size: Int,
        output: OutputStream,
    ) = renderClustered(drawable, palette, size, output, maxClusters = 3)

    /**
     * Region-aware Monet rendering. A small weighted colour model preserves
     * distinct artwork regions even when their source colours have similar
     * luminance. The detected outer container receives one uniform tone;
     * every remaining cluster receives a separate, high-contrast tone from
     * the same Monet family.
     */
    private fun renderClustered(
        drawable: Drawable,
        palette: GlobalMonetPalette,
        size: Int,
        output: OutputStream,
        maxClusters: Int,
    ) {
        require(size > 0) { "图标渲染尺寸必须大于零" }
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawDrawable(drawable, source, size)
            val sourcePixels = IntArray(size * size)
            source.getPixels(sourcePixels, 0, size, 0, 0, size, size)
            repairOuterBoundaryColours(sourcePixels, size)
            val model = buildClusterModel(sourcePixels, size, palette, maxClusters)
            val pixelClusters = IntArray(sourcePixels.size) { index ->
                if (Color.alpha(sourcePixels[index]) == 0) -1 else model.clusterFor(sourcePixels[index])
            }
            val targetPixels = IntArray(sourcePixels.size)
            sourcePixels.forEachIndexed { index, pixel ->
                val alpha = Color.alpha(pixel)
                if (alpha == 0) return@forEachIndexed
                val cluster = pixelClusters[index]
                val tone = model.tones[cluster]
                targetPixels[index] = Color.argb(
                    alpha,
                    Color.red(tone),
                    Color.green(tone),
                    Color.blue(tone),
                )
            }
            result.setPixels(targetPixels, 0, size, 0, 0, size, size)
            validateRecognisableOutput(sourcePixels, targetPixels)
            compress(result, output, "Monet 聚类图标 PNG 编码失败")
        } finally {
            source.recycle()
            result.recycle()
        }
    }

    private fun buildClusterModel(
        pixels: IntArray,
        size: Int,
        palette: GlobalMonetPalette,
        maxClusters: Int,
    ): ClusterModel {
        val bucketWeights = LongArray(COLOUR_BUCKET_COUNT)
        val bucketRed = LongArray(COLOUR_BUCKET_COUNT)
        val bucketGreen = LongArray(COLOUR_BUCKET_COUNT)
        val bucketBlue = LongArray(COLOUR_BUCKET_COUNT)
        val modelAlphaThreshold = if (pixels.any { Color.alpha(it) >= CLUSTER_MODEL_MIN_ALPHA }) {
            CLUSTER_MODEL_MIN_ALPHA
        } else {
            MIN_VISIBLE_ALPHA
        }
        var alphaTotal = 0L
        pixels.forEach { pixel ->
            val alpha = Color.alpha(pixel)
            // Low-alpha antialiasing pixels describe a boundary, not a real
            // artwork colour. Let them inherit their nearest solid region at
            // render time instead of spending one of the limited clusters on
            // a pale fringe.
            if (alpha < modelAlphaThreshold) return@forEach
            val bucket = colourBucket(pixel)
            val weight = alpha.toLong()
            bucketWeights[bucket] += weight
            bucketRed[bucket] += Color.red(pixel) * weight
            bucketGreen[bucket] += Color.green(pixel) * weight
            bucketBlue[bucket] += Color.blue(pixel) * weight
            alphaTotal += weight
        }
        require(alphaTotal > 0L) { "图标没有可见像素" }

        val occupied = bucketWeights.indices.filter { bucketWeights[it] > 0L }
        val significantSupport = maxOf(CLUSTER_MIN_WEIGHT, alphaTotal / CLUSTER_SUPPORT_DIVISOR)
        val significantCount = occupied.count { bucketWeights[it] >= significantSupport }
        val clusterCount = minOf(maxClusters, maxOf(1, significantCount, occupied.size.coerceAtMost(2)))

        fun bucketColour(bucket: Int): Int {
            val weight = bucketWeights[bucket].coerceAtLeast(1L)
            return Color.rgb(
                (bucketRed[bucket] / weight).toInt(),
                (bucketGreen[bucket] / weight).toInt(),
                (bucketBlue[bucket] / weight).toInt(),
            )
        }

        val selected = ArrayList<Int>(clusterCount)
        selected += occupied.maxByOrNull { bucketWeights[it] } ?: throw IllegalStateException("图标颜色为空")
        while (selected.size < clusterCount) {
            val candidate = occupied.asSequence()
                .filterNot(selected::contains)
                .maxByOrNull { bucket ->
                    val colour = bucketColour(bucket)
                    val nearestDistance = selected.minOf { selectedBucket ->
                        colourDistance(colour, bucketColour(selectedBucket))
                    }
                    nearestDistance.toLong() * minOf(
                        bucketWeights[bucket],
                        maxOf(CLUSTER_MIN_WEIGHT, alphaTotal / CLUSTER_INITIAL_WEIGHT_DIVISOR),
                    )
                }
                ?: break
            selected += candidate
        }

        val centroids = selected.map { bucket -> bucketColour(bucket) }.toMutableList()
        val bucketToCluster = IntArray(COLOUR_BUCKET_COUNT) { -1 }
        repeat(CLUSTER_ITERATIONS) {
            occupied.forEach { bucket ->
                val colour = bucketColour(bucket)
                bucketToCluster[bucket] = centroids.indices.minByOrNull { index ->
                    colourDistance(colour, centroids[index])
                } ?: 0
            }
            val weights = LongArray(centroids.size)
            val red = LongArray(centroids.size)
            val green = LongArray(centroids.size)
            val blue = LongArray(centroids.size)
            occupied.forEach { bucket ->
                val cluster = bucketToCluster[bucket]
                val weight = bucketWeights[bucket]
                weights[cluster] += weight
                red[cluster] += bucketRed[bucket]
                green[cluster] += bucketGreen[bucket]
                blue[cluster] += bucketBlue[bucket]
            }
            centroids.indices.forEach { index ->
                val weight = weights[index]
                if (weight > 0L) {
                    centroids[index] = Color.rgb(
                        (red[index] / weight).toInt(),
                        (green[index] / weight).toInt(),
                        (blue[index] / weight).toInt(),
                    )
                }
            }
        }

        // Reclassify after the final centroid update and collect real cluster weights.
        val clusterWeights = LongArray(centroids.size)
        occupied.forEach { bucket ->
            val colour = bucketColour(bucket)
            val cluster = centroids.indices.minByOrNull { index ->
                colourDistance(colour, centroids[index])
            } ?: 0
            bucketToCluster[bucket] = cluster
            clusterWeights[cluster] += bucketWeights[bucket]
        }

        val coverage = alphaTotal.toDouble() / (size.toLong() * size * OPAQUE).toDouble()
        val backgroundCluster = if (coverage < GLYPH_COVERAGE_THRESHOLD) {
            -1
        } else {
            findBackgroundCluster(
                pixels = pixels,
                size = size,
                bucketToCluster = bucketToCluster,
                centroids = centroids,
                clusterWeights = clusterWeights,
                alphaTotal = alphaTotal,
            )
        }

        val tones = IntArray(centroids.size)
        val orderedForeground = centroids.indices
            .filter { it != backgroundCluster }
            .sortedWith(compareBy<Int>({ luminance(centroids[it]) }, { colourOrder(centroids[it]) }))
        if (backgroundCluster >= 0) {
            // Flat icons commonly contain several fully opaque colours only
            // because their antialiased glyph was precomposited onto an opaque
            // background. Ranking those blends by luminance created a dark
            // one-pixel outline around an otherwise lighter glyph. Map every
            // region monotonically by its actual distance from the detected
            // background instead: background -> container, farthest subject
            // -> dark tone, intermediate samples -> smooth antialiasing.
            val background = centroids[backgroundCluster]
            val maxDistance = orderedForeground.maxOfOrNull { colourDistance(centroids[it], background) }
                ?.coerceAtLeast(1)
                ?: 1
            orderedForeground.forEach { cluster ->
                val distance = colourDistance(centroids[cluster], background)
                val amount = (kotlin.math.sqrt(distance.toDouble() / maxDistance) * OPAQUE)
                    .roundToInt()
                    .coerceIn(0, OPAQUE)
                tones[cluster] = blend(palette.outerContainer, palette.darkTone, amount)
            }
            tones[backgroundCluster] = palette.outerContainer
        } else {
            orderedForeground.forEachIndexed { position, cluster ->
                val amount = if (orderedForeground.size <= 1) {
                    0
                } else {
                    position * CLUSTER_FULL_MAX_AMOUNT / (orderedForeground.size - 1)
                }
                tones[cluster] = blend(palette.darkTone, palette.outerContainer, amount)
            }
        }

        return ClusterModel(
            bucketToCluster = bucketToCluster,
            centroids = centroids.toIntArray(),
            tones = tones,
            backgroundCluster = backgroundCluster,
        )
    }

    private fun findBackgroundCluster(
        pixels: IntArray,
        size: Int,
        bucketToCluster: IntArray,
        centroids: List<Int>,
        clusterWeights: LongArray,
        alphaTotal: Long,
    ): Int {
        val edgeVotes = LongArray(centroids.size)
        var edgeTotal = 0L
        for (row in 0 until size) {
            for (column in 0 until size) {
                if (row >= EDGE_SAMPLE_WIDTH && row < size - EDGE_SAMPLE_WIDTH &&
                    column >= EDGE_SAMPLE_WIDTH && column < size - EDGE_SAMPLE_WIDTH
                ) continue
                val pixel = pixels[row * size + column]
                val alpha = Color.alpha(pixel)
                if (alpha < MIN_VISIBLE_ALPHA) continue
                val cluster = bucketToCluster[colourBucket(pixel)].takeIf { it >= 0 }
                    ?: centroids.indices.minByOrNull { colourDistance(pixel, centroids[it]) }
                    ?: continue
                edgeVotes[cluster] += alpha.toLong()
                edgeTotal += alpha.toLong()
            }
        }
        if (edgeTotal > 0L) {
            val edgeCluster = edgeVotes.indices.maxByOrNull { edgeVotes[it] } ?: -1
            if (edgeCluster >= 0 &&
                edgeVotes[edgeCluster] * 100 >= edgeTotal * CLUSTER_EDGE_BACKGROUND_PERCENT &&
                clusterWeights[edgeCluster] * 100 >= alphaTotal * CLUSTER_MIN_BACKGROUND_PERCENT
            ) {
                return edgeCluster
            }
        }
        val dominant = clusterWeights.indices.maxByOrNull { clusterWeights[it] } ?: return -1
        return dominant.takeIf {
            clusterWeights[it] * 100 >= alphaTotal * CLUSTER_DOMINANT_BACKGROUND_PERCENT
        } ?: -1
    }

    private fun colourOrder(colour: Int): Int =
        (Color.red(colour) shl 16) or (Color.green(colour) shl 8) or Color.blue(colour)

    private data class ClusterModel(
        val bucketToCluster: IntArray,
        val centroids: IntArray,
        val tones: IntArray,
        val backgroundCluster: Int,
    ) {
        fun clusterFor(pixel: Int): Int = bucketToCluster[colourBucket(pixel)]
            .takeIf { it >= 0 }
            ?: nearestCluster(pixel)

        fun nearestCluster(pixel: Int): Int = centroids.indices.minByOrNull { index ->
            colourDistance(pixel, centroids[index])
        } ?: 0
    }

    /**
     * Bitmap and adaptive layers sampled exactly at 0/size can acquire a
     * one-pixel premultiplied fringe on all four sides. Inherit the colour of
     * the first interior pixel while preserving the edge alpha. This is the
     * standard texture-extrusion treatment and does not change icon shape.
     */
    private fun repairOuterBoundaryColours(pixels: IntArray, size: Int) {
        if (size < 3) return

        fun inherit(edgeIndex: Int, interiorIndex: Int) {
            val edge = pixels[edgeIndex]
            val alpha = Color.alpha(edge)
            if (alpha == 0) return
            val interior = pixels[interiorIndex]
            if (Color.alpha(interior) == 0) return
            pixels[edgeIndex] = Color.argb(
                alpha,
                Color.red(interior),
                Color.green(interior),
                Color.blue(interior),
            )
        }

        for (position in 0 until size) {
            inherit(position, size + position)
            inherit((size - 1) * size + position, (size - 2) * size + position)
            inherit(position * size, position * size + 1)
            inherit(position * size + size - 1, position * size + size - 2)
        }
    }

    private fun renderBitmap(
        drawable: Drawable,
        palette: GlobalMonetPalette,
        size: Int,
        output: OutputStream,
        simplified: Boolean,
    ) {
        require(size > 0) { "图标渲染尺寸必须大于零" }
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawDrawable(drawable, source, size)
            val sourcePixels = IntArray(size * size)
            source.getPixels(sourcePixels, 0, size, 0, 0, size, size)
            val analysis = analyse(sourcePixels, size)
            val targetPixels = IntArray(sourcePixels.size)
            sourcePixels.forEachIndexed { index, pixel ->
                val alpha = Color.alpha(pixel)
                if (alpha == 0) return@forEachIndexed
                val localEdgeStrength = if (simplified) 0 else edgeStrength(sourcePixels, index, size)
                val colour = when {
                    analysis.glyphLike -> palette.darkTone
                    analysis.edgeBackground != null -> segmentColour(
                        pixel = pixel,
                        edgeBackground = analysis.edgeBackground,
                        analysis = analysis,
                        localEdgeStrength = localEdgeStrength,
                        palette = palette,
                        simplified = simplified,
                    )
                    else -> detailColour(
                        pixel = pixel,
                        analysis = analysis,
                        localEdgeStrength = localEdgeStrength,
                        palette = palette,
                        simplified = simplified,
                    )
                }
                targetPixels[index] = Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))
            }
            result.setPixels(targetPixels, 0, size, 0, 0, size, size)
            validateRecognisableOutput(sourcePixels, targetPixels)
            compress(result, output, "Monet 图标 PNG 编码失败")
        } finally {
            source.recycle()
            result.recycle()
        }
    }

    private fun segmentColour(
        pixel: Int,
        edgeBackground: Int,
        analysis: Analysis,
        localEdgeStrength: Int,
        palette: GlobalMonetPalette,
        simplified: Boolean,
    ): Int {
        val distance = colourDistance(pixel, edgeBackground)
        if (distance <= BACKGROUND_DISTANCE_SQUARED) return palette.outerContainer
        if (simplified) {
            return if (distance >= SIMPLIFIED_DISTANCE_SQUARED) palette.darkTone else palette.outerContainer
        }
        // Keep the container uniform, but never collapse every foreground
        // colour to one dark value. Google Play, Photos and many weather/game
        // icons encode their internal geometry with several colours of
        // different luminance. Mapping that complete foreground continuously
        // into one Monet family retains those boundaries and fine details.
        val normalized = (((luminance(pixel) - analysis.low) * OPAQUE / analysis.range) -
            edgeDarkening(localEdgeStrength))
            .coerceIn(0, OPAQUE)
        val foregroundAmount = normalized * MAX_FOREGROUND_TONE_AMOUNT / OPAQUE
        val foregroundTone = blend(palette.darkTone, palette.outerContainer, foregroundAmount)
        if (distance >= FOREGROUND_DISTANCE_SQUARED) return foregroundTone

        // Pixels close to the detected background are normally antialiasing.
        // Blend them smoothly instead of drawing a hard halo around glyphs.
        val amount = ((distance - BACKGROUND_DISTANCE_SQUARED).toLong() * OPAQUE /
            (FOREGROUND_DISTANCE_SQUARED - BACKGROUND_DISTANCE_SQUARED)).toInt().coerceIn(0, OPAQUE)
        return blend(palette.outerContainer, foregroundTone, amount)
    }

    /**
     * Detailed artwork without a coherent flat background keeps its tonal
     * structure inside one Monet family. Local edge detection previously
     * reduced complex illustrations to broken outlines and removed interiors.
     */
    private fun detailColour(
        pixel: Int,
        analysis: Analysis,
        localEdgeStrength: Int,
        palette: GlobalMonetPalette,
        simplified: Boolean,
    ): Int {
        val normalized = (((luminance(pixel) - analysis.low) * OPAQUE / analysis.range) -
            edgeDarkening(localEdgeStrength))
            .coerceIn(0, OPAQUE)
        return if (simplified) {
            if (normalized < GUARANTEED_LIGHT_THRESHOLD) palette.darkTone else palette.outerContainer
        } else {
            blend(palette.darkTone, palette.outerContainer, normalized)
        }
    }

    private fun analyse(pixels: IntArray, size: Int): Analysis {
        val histogram = IntArray(OPAQUE + 1)
        var alphaTotal = 0L
        pixels.forEach { pixel ->
            val alpha = Color.alpha(pixel)
            if (alpha == 0) return@forEach
            histogram[luminance(pixel)] += alpha
            alphaTotal += alpha.toLong()
        }
        require(alphaTotal > 0L) { "图标没有可见像素" }
        val coverage = alphaTotal.toDouble() / (size.toLong() * size * OPAQUE).toDouble()
        if (coverage < GLYPH_COVERAGE_THRESHOLD) {
            return Analysis(glyphLike = true, edgeBackground = null, low = 0, range = OPAQUE)
        }

        // A whole-icon dominant-colour fallback is unsafe for multicolour
        // artwork. Chrome, Photos and game icons often have one large colour
        // region that is not a background; treating it as one erased every
        // neighbouring region. Only use a background when the outer edge is
        // demonstrably stable, otherwise preserve the complete tonal image.
        val edgeBackground = findStableEdgeBackground(pixels, size)
        val low = histogramPercentile(histogram, alphaTotal, LOW_PERCENTILE)
        val high = histogramPercentile(histogram, alphaTotal, HIGH_PERCENTILE)
        return Analysis(
            glyphLike = false,
            edgeBackground = edgeBackground,
            low = if (high - low < MIN_LUMINANCE_RANGE) 0 else low,
            range = if (high - low < MIN_LUMINANCE_RANGE) OPAQUE else high - low,
        )
    }

    /**
     * Uses a quantised colour mode from the visible edge, then validates its
     * support with actual (unquantised) pixel distance.  Circular icons still
     * work because the midpoints of their four edges are visible; transparent
     * corners simply do not participate in the vote.
     */
    private fun findStableEdgeBackground(pixels: IntArray, size: Int): Int? {
        if (size <= EDGE_SAMPLE_WIDTH * 2) return null
        val edgePixels = ArrayList<Int>(size * EDGE_SAMPLE_WIDTH * 4)
        for (y in pixels.indices step size) {
            val row = y / size
            for (column in 0 until size) {
                if (row >= EDGE_SAMPLE_WIDTH && row < size - EDGE_SAMPLE_WIDTH &&
                    column >= EDGE_SAMPLE_WIDTH && column < size - EDGE_SAMPLE_WIDTH
                ) continue
                val pixel = pixels[y + column]
                if (Color.alpha(pixel) >= MIN_VISIBLE_ALPHA) edgePixels += pixel
            }
        }
        if (edgePixels.size < MIN_EDGE_PIXELS) return null
        val buckets = IntArray(COLOUR_BUCKET_COUNT)
        edgePixels.forEach { buckets[colourBucket(it)]++ }
        val selectedBucket = buckets.indices.maxByOrNull(buckets::get) ?: return null
        val bucketCount = buckets[selectedBucket]
        if (bucketCount * 100 < edgePixels.size * MIN_EDGE_BUCKET_PERCENT) return null

        // Average all samples near the mode instead of reconstructing the
        // bucket centre. It behaves better for anti-aliased pack masks and
        // subtle background gradients.
        val bucketColour = colourFromBucket(selectedBucket)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        edgePixels.forEach { pixel ->
            if (colourDistance(pixel, bucketColour) <= EDGE_MODE_DISTANCE_SQUARED) {
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return null
        val candidate = Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        val support = edgePixels.count { colourDistance(it, candidate) <= EDGE_SUPPORT_DISTANCE_SQUARED }
        return candidate.takeIf { support * 100 >= edgePixels.size * MIN_EDGE_SUPPORT_PERCENT }
    }

    private fun drawDrawable(drawable: Drawable, target: Bitmap, size: Int) {
        val oldBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(target))
        } finally {
            drawable.bounds = oldBounds
        }
    }

    /** Highlights boundaries between source colours with similar luminance. */
    private fun edgeStrength(pixels: IntArray, index: Int, size: Int): Int {
        val pixel = pixels[index]
        val x = index % size
        val y = index / size
        var strongest = 0
        fun compare(otherIndex: Int) {
            val other = pixels[otherIndex]
            if (Color.alpha(other) < MIN_VISIBLE_ALPHA) return
            strongest = maxOf(strongest, colourDistance(pixel, other))
        }
        if (x > 0) compare(index - 1)
        if (x + 1 < size) compare(index + 1)
        if (y > 0) compare(index - size)
        if (y + 1 < size) compare(index + size)
        return strongest
    }

    private fun edgeDarkening(strength: Int): Int {
        if (strength <= DETAIL_EDGE_START_SQUARED) return 0
        return ((strength - DETAIL_EDGE_START_SQUARED).toLong() * DETAIL_EDGE_MAX_DARKEN /
            (DETAIL_EDGE_FULL_SQUARED - DETAIL_EDGE_START_SQUARED))
            .toInt()
            .coerceIn(0, DETAIL_EDGE_MAX_DARKEN)
    }

    private fun compress(bitmap: Bitmap, output: OutputStream, error: String) {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)) throw IllegalStateException(error)
    }

    /**
     * Never accept a flat palette tile when the source contained real
     * internal artwork. The caller can then try another renderer or omit the
     * entry so HyperOS falls back to a recognisable application icon.
     */
    private fun validateRecognisableOutput(source: IntArray, target: IntArray) {
        val sourceColours = significantColourCount(source)
        if (sourceColours < MIN_SOURCE_DETAIL_COLOURS) return
        require(significantColourCount(target) >= MIN_TARGET_DETAIL_COLOURS) {
            "Monet 输出丢失了图标内部细节"
        }
    }

    private fun significantColourCount(pixels: IntArray): Int {
        val buckets = IntArray(DETAIL_BUCKET_COUNT)
        var visible = 0
        pixels.forEach { pixel ->
            if (Color.alpha(pixel) < DETAIL_MIN_ALPHA) return@forEach
            val bucket = ((Color.red(pixel) shr DETAIL_QUANTISATION_SHIFT) shl (DETAIL_QUANTISATION_BITS * 2)) or
                ((Color.green(pixel) shr DETAIL_QUANTISATION_SHIFT) shl DETAIL_QUANTISATION_BITS) or
                (Color.blue(pixel) shr DETAIL_QUANTISATION_SHIFT)
            buckets[bucket]++
            visible++
        }
        if (visible == 0) return 0
        val minimumSupport = maxOf(DETAIL_MIN_PIXELS, visible / DETAIL_SUPPORT_DIVISOR)
        return buckets.count { it >= minimumSupport }
    }

    private fun colourBucket(colour: Int): Int =
        ((Color.red(colour) shr COLOUR_QUANTISATION_SHIFT) shl (COLOUR_QUANTISATION_BITS * 2)) or
            ((Color.green(colour) shr COLOUR_QUANTISATION_SHIFT) shl COLOUR_QUANTISATION_BITS) or
            (Color.blue(colour) shr COLOUR_QUANTISATION_SHIFT)

    private fun colourFromBucket(bucket: Int): Int {
        val mask = (1 shl COLOUR_QUANTISATION_BITS) - 1
        fun expand(value: Int): Int = (value shl COLOUR_QUANTISATION_SHIFT) + (1 shl (COLOUR_QUANTISATION_SHIFT - 1))
        return Color.rgb(
            expand((bucket shr (COLOUR_QUANTISATION_BITS * 2)) and mask),
            expand((bucket shr COLOUR_QUANTISATION_BITS) and mask),
            expand(bucket and mask),
        )
    }

    private fun colourDistance(first: Int, second: Int): Int {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        return red * red + green * green + blue * blue
    }

    private fun histogramPercentile(histogram: IntArray, total: Long, percentile: Double): Int {
        val target = (total * percentile).roundToInt().toLong()
        var accumulated = 0L
        histogram.forEachIndexed { value, weight ->
            accumulated += weight.toLong()
            if (accumulated >= target) return value
        }
        return OPAQUE
    }

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 54 + Color.green(pixel) * 183 + Color.blue(pixel) * 19) / OPAQUE

    private fun blend(start: Int, end: Int, amount: Int): Int {
        fun channel(from: Int, to: Int): Int = from + (to - from) * amount / OPAQUE
        return Color.rgb(
            channel(Color.red(start), Color.red(end)),
            channel(Color.green(start), Color.green(end)),
            channel(Color.blue(start), Color.blue(end)),
        )
    }

    private data class Analysis(
        val glyphLike: Boolean,
        val edgeBackground: Int?,
        val low: Int,
        val range: Int,
    )

    private const val OPAQUE = 255
    private const val PNG_QUALITY = 100
    private const val GLYPH_COVERAGE_THRESHOLD = 0.42
    private const val MIN_NATIVE_GLYPH_ALPHA = 255L * 8
    private const val MAX_NATIVE_GLYPH_PERCENT = 88
    private const val LOW_PERCENTILE = 0.03
    private const val HIGH_PERCENTILE = 0.97
    private const val MIN_LUMINANCE_RANGE = 22
    private const val MIN_VISIBLE_ALPHA = 32
    private const val CLUSTER_MODEL_MIN_ALPHA = 112
    private const val EDGE_SAMPLE_WIDTH = 6
    private const val MIN_EDGE_PIXELS = 64
    private const val MIN_EDGE_BUCKET_PERCENT = 18
    private const val MIN_EDGE_SUPPORT_PERCENT = 46
    // Four bits per channel are sufficient for a six-region model and keep
    // conversion allocations bounded when an icon pack contains thousands
    // of mappings.
    private const val COLOUR_QUANTISATION_BITS = 4
    private const val COLOUR_QUANTISATION_SHIFT = 8 - COLOUR_QUANTISATION_BITS
    private const val COLOUR_BUCKET_COUNT = 1 shl (COLOUR_QUANTISATION_BITS * 3)
    private const val EDGE_MODE_DISTANCE_SQUARED = 34 * 34
    private const val EDGE_SUPPORT_DISTANCE_SQUARED = 40 * 40
    private const val BACKGROUND_DISTANCE_SQUARED = 12 * 12
    private const val FOREGROUND_DISTANCE_SQUARED = 44 * 44
    private const val SIMPLIFIED_DISTANCE_SQUARED = 28 * 28
    private const val GUARANTEED_LIGHT_THRESHOLD = 156
    private const val DETAIL_MIN_ALPHA = 64
    private const val DETAIL_QUANTISATION_BITS = 4
    private const val DETAIL_QUANTISATION_SHIFT = 8 - DETAIL_QUANTISATION_BITS
    private const val DETAIL_BUCKET_COUNT = 1 shl (DETAIL_QUANTISATION_BITS * 3)
    private const val DETAIL_MIN_PIXELS = 12
    private const val DETAIL_SUPPORT_DIVISOR = 300
    private const val MIN_SOURCE_DETAIL_COLOURS = 2
    private const val MIN_TARGET_DETAIL_COLOURS = 2
    private const val MAX_FOREGROUND_TONE_AMOUNT = 150
    private const val DETAIL_EDGE_START_SQUARED = 24 * 24
    private const val DETAIL_EDGE_FULL_SQUARED = 96 * 96
    private const val DETAIL_EDGE_MAX_DARKEN = 72
    private const val CLUSTER_MIN_WEIGHT = 255L * 8
    private const val CLUSTER_SUPPORT_DIVISOR = 650
    private const val CLUSTER_INITIAL_WEIGHT_DIVISOR = 16
    private const val CLUSTER_ITERATIONS = 6
    private const val CLUSTER_EDGE_BACKGROUND_PERCENT = 42
    private const val CLUSTER_MIN_BACKGROUND_PERCENT = 8
    private const val CLUSTER_DOMINANT_BACKGROUND_PERCENT = 60
    // Keep every subject region decisively separated from the container. The
    // old 165/220 limits allowed the lightest clusters to approach the
    // background and made small launcher icons look washed out.
    private const val CLUSTER_FULL_MAX_AMOUNT = 148
}
