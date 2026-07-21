package io.github.cl0ura.hypericonpack.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import io.github.cl0ura.hypericonpack.systemtheme.SystemIconShapeDrawable
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Parser for the de-facto standard used by Nova, ADW, Lawnchair, and most
 * commercial icon packs.
 *
 * In addition to explicit activity mappings, the standard appfilter format
 * can provide iconback/iconmask/iconupon/scale resources.  Those resources
 * are retained separately and are used only when the user explicitly enables
 * fallback styling for an otherwise unmapped launcher icon.
 */
internal class ParsedIconPack private constructor(
    private val packageContext: Context,
    private val componentToDrawable: Map<String, String>,
    private val componentToCalendarPrefix: Map<String, String>,
    private val fallbackStyle: IconPackFallbackStyle?,
) {
    val mappingCount: Int
        get() = componentToDrawable.size

    val supportsFallbackStyle: Boolean
        get() = fallbackStyle?.hasRenderableLayers == true

    private val resources: Resources = packageContext.resources
    private val constantStates = ConcurrentHashMap<String, Drawable.ConstantState>()
    /**
     * Explicit appfilter mappings exposed for the HyperOS-theme converter.
     *
     * The converter deliberately uses only these mappings.  Legacy appfilter
     * fallback layers (iconback/iconmask/iconupon) describe how a launcher
     * should synthesize an icon for an application it knows about; they are
     * not per-application HyperOS theme resources and must not be baked into
     * every mapping here.
     */
    fun explicitMappings(): List<IconPackMapping> = componentToDrawable.entries.mapNotNull { entry ->
        val separator = entry.key.indexOf('/')
        if (separator <= 0 || separator == entry.key.lastIndex) {
            null
        } else {
            IconPackMapping(
                component = ComponentName(
                    entry.key.substring(0, separator),
                    entry.key.substring(separator + 1),
                ),
                drawableName = entry.value,
            )
        }
    }

    fun calendarMappings(): List<IconPackCalendarMapping> = componentToCalendarPrefix.entries.mapNotNull { entry ->
        val separator = entry.key.indexOf('/')
        if (separator <= 0 || separator == entry.key.lastIndex) {
            null
        } else {
            IconPackCalendarMapping(
                component = ComponentName(
                    entry.key.substring(0, separator),
                    entry.key.substring(separator + 1),
                ),
                drawablePrefix = entry.value,
            )
        }
    }

    /**
     * Calendar packs use two incompatible conventions: some ship a complete
     * dated icon, while others ship only the date glyph. Complete artwork must
     * follow the same path as a static mapping; adding iconback/iconupon again
     * changes its shape and can add an unwanted border. Glyph-only assets are
     * composed through the pack fallback stack.
     */
    fun loadCalendarDrawable(
        drawablePrefix: String,
        dayOfMonth: Int,
        scaleMultiplier: Float = 1f,
    ): Drawable? {
        require(dayOfMonth in 1..31) { "日历日期必须在 1 到 31 之间" }
        val day = loadDrawable("$drawablePrefix$dayOfMonth") ?: return null
        if (looksLikeFinishedCalendarIcon(day)) {
            return applyDeclaredShape(day)
        }
        val style = fallbackStyle ?: return applyDeclaredShape(day)
        val mask = style.maskName?.let(::loadDrawable)
        val background = selectBackground(
            names = style.backgroundNames,
            component = ComponentName("calendar", "day$dayOfMonth"),
        )
        val foreground = style.foregroundName?.let(::loadDrawable)
        if (mask == null && background == null && foreground == null) {
            return applyDeclaredShape(day)
        }
        return IconPackFallbackDrawable(
            source = day,
            background = background,
            mask = mask,
            foreground = foreground,
            scale = style.scale * scaleMultiplier,
        )
    }

    private fun looksLikeFinishedCalendarIcon(drawable: Drawable): Boolean = runCatching {
        val size = CALENDAR_PROBE_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            var visible = 0
            var left = size
            var top = size
            var right = -1
            var bottom = -1
            pixels.forEachIndexed { index, pixel ->
                if ((pixel ushr 24) < CALENDAR_VISIBLE_ALPHA) return@forEachIndexed
                visible++
                val x = index % size
                val y = index / size
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            if (visible == 0) return@runCatching false
            val coverage = visible.toFloat() / pixels.size
            val widthRatio = (right - left + 1).toFloat() / size
            val heightRatio = (bottom - top + 1).toFloat() / size
            coverage >= CALENDAR_FINISHED_COVERAGE ||
                (widthRatio >= CALENDAR_FINISHED_SPAN && heightRatio >= CALENDAR_FINISHED_SPAN)
        } finally {
            drawable.bounds = previousBounds
            bitmap.recycle()
        }
    }.getOrDefault(false)

    /**
     * Loads a mapped drawable with the icon pack's own Resources and theme.
     *
     * Normal launchers perform the pack's final iconmask pass when drawing
     * every icon. HyperOS consumes the pre-rendered PNG from this module
     * directly, so reproduce that pass here: opaque square source PNGs keep
     * the pack's intended circle or rounded-square footprint.
     */
    fun loadMappedDrawable(drawableName: String): Drawable? =
        loadDrawable(drawableName)?.let(::applyDeclaredShape)

    /**
     * Reuse the exact drawable proven by the original-colour conversion.
     * Explicit mappings are already finished artwork; blindly adding
     * iconback/iconupon a second time erased white and line-based glyphs in
     * packs such as Pure. Fallback layers remain reserved for genuinely
     * unmapped or stale entries through [createFallback].
     */
    fun loadMappedDrawableForMonet(
        drawableName: String,
        @Suppress("UNUSED_PARAMETER") component: ComponentName,
    ): Drawable? = loadMappedDrawable(drawableName)

    /**
     * Returns the icon-pack resources explicitly associated with an app
     * package.  A few applications change their launcher Activity between
     * releases (or expose an Activity-alias) while an icon pack still names
     * the old entry.  The system-theme converter uses this only as a
     * same-package fallback after the exact component failed to render; it
     * never borrows an icon from a different package.
     */
    fun mappedDrawableNamesForPackage(packageName: String): List<String> {
        val normalizedPackage = packageName.lowercase(Locale.ROOT)
        return componentToDrawable.asSequence()
            .filter { (component, _) -> component.substringBefore('/') == normalizedPackage }
            .map { (_, drawableName) -> drawableName }
            .distinct()
            .toList()
    }

    /**
     * Creates the standard icon-pack fallback composition for an unmapped app.
     * When a pack omits iconmask, the alpha of its iconback is used as the
     * shape source. This is common in packs that provide only a rounded icon
     * background rather than a separate mask image.
     */
    fun createFallback(
        original: Drawable,
        component: ComponentName,
        scaleMultiplier: Float,
    ): Drawable? {
        val style = fallbackStyle ?: return null
        val mask = style.maskName?.let(::loadDrawable)
        val background = selectBackground(style.backgroundNames, component)
        val foreground = style.foregroundName?.let(::loadDrawable)
        if (mask == null && background == null && foreground == null) return null
        // This ConstantState belongs to System Launcher/the target application,
        // not the icon-pack Resources. Let it recreate itself with its own
        // resource context before drawing it inside the icon-pack layers.
        val source = original.constantState
            ?.newDrawable()
            ?.mutate()
            ?: original

        return IconPackFallbackDrawable(
            source = source,
            background = background,
            mask = mask,
            foreground = foreground,
            // appfilter's scale is part of the cross-launcher icon-pack
            // format. Keep it, then apply only the user's final HyperOS
            // calibration to generated fallback icons.
            scale = style.scale * scaleMultiplier,
        )
    }

    /** Applies the icon pack's declared static outline to an explicit mapping. */
    private fun applyDeclaredShape(drawable: Drawable): Drawable {
        val mask = fallbackStyle?.maskName?.let(::loadDrawable) ?: return bakeAdaptiveShape(drawable)
        return IconPackFallbackDrawable(
            source = drawable,
            background = null,
            mask = mask,
            foreground = null,
            scale = 1f,
        )
    }

    /**
     * Adaptive icon resources rely on the launcher to apply their final mask.
     * HyperOS reads our generated file as a legacy PNG, so bake a conservative
     * rounded adaptive outline when the icon pack did not declare iconmask.
     */
    private fun bakeAdaptiveShape(drawable: Drawable): Drawable =
        if (drawable is AdaptiveIconDrawable) {
            SystemIconShapeDrawable(source = drawable, systemMask = drawable)
        } else {
            drawable
        }

    private fun selectBackground(names: List<String>, component: ComponentName): Drawable? {
        if (names.isEmpty()) return null
        val start = (componentKey(component).hashCode() and Int.MAX_VALUE) % names.size
        repeat(names.size) { offset ->
            val drawableName = names[(start + offset) % names.size]
            loadDrawable(drawableName)?.let { return it }
        }
        return null
    }

    private fun loadDrawable(rawName: String): Drawable? {
        constantStates[rawName]?.let { state ->
            return state.newDrawable(resources, packageContext.theme)
        }

        val resourceId = resourceId(rawName)
        if (resourceId == 0) return null
        val drawable = try {
            resources.getDrawable(resourceId, packageContext.theme)
        } catch (_: Throwable) {
            null
        } ?: return null

        drawable.constantState?.let { constantStates.putIfAbsent(rawName, it) }
        return drawable
    }

    private fun resourceId(rawName: String): Int {
        val name = rawName
            .removePrefix("@drawable/")
            .removePrefix("@mipmap/")
            .trim()
        return resources.getIdentifier(name, "drawable", packageContext.packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(name, "mipmap", packageContext.packageName)
    }

    private fun componentKey(component: ComponentName): String =
        "${component.packageName.lowercase(Locale.ROOT)}/${component.className}"

    companion object {
        fun load(
            hostContext: Context,
            iconPackPackage: String,
            onError: (Throwable) -> Unit = {},
        ): ParsedIconPack? {
            return try {
                val packageContext = hostContext.createPackageContext(
                    iconPackPackage,
                    Context.CONTEXT_IGNORE_SECURITY,
                )
                val appFilter = packageContext.openAssetAppFilter()?.use(::parseAppFilter)
                    ?: packageContext.parseXmlAppFilter()
                    ?: throw IllegalStateException("No assets/appfilter.xml or res/xml/appfilter in $iconPackPackage")
                if (appFilter.componentToDrawable.isEmpty()) {
                    throw IllegalStateException("appfilter.xml in $iconPackPackage contains no ComponentInfo mappings")
                }
                ParsedIconPack(
                    packageContext = packageContext,
                    componentToDrawable = appFilter.componentToDrawable,
                    componentToCalendarPrefix = appFilter.componentToCalendarPrefix,
                    fallbackStyle = appFilter.fallbackStyle,
                )
            } catch (throwable: Throwable) {
                onError(throwable)
                null
            }
        }

        private fun Context.openAssetAppFilter(): InputStream? {
            try {
                return assets.open("appfilter.xml")
            } catch (_: Throwable) {
                return null
            }
        }

        /**
         * XML files under res/xml are compiled binary XML; Resources#getXml
         * supplies the correct parser, whereas openRawResource would not.
         */
        private fun Context.parseXmlAppFilter(): ParsedAppFilter? {
            val resourceId = resources.getIdentifier("appfilter", "xml", packageName)
            if (resourceId == 0) return null
            val parser = try {
                resources.getXml(resourceId)
            } catch (_: Throwable) {
                return null
            }
            return try {
                parseAppFilter(parser)
            } finally {
                parser.close()
            }
        }

        private fun parseAppFilter(input: InputStream): ParsedAppFilter {
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            return parseAppFilter(parser)
        }

        private fun parseAppFilter(parser: XmlPullParser): ParsedAppFilter {
            val mappings = HashMap<String, String>()
            val calendars = HashMap<String, String>()
            val backgrounds = LinkedHashSet<String>()
            var maskName: String? = null
            var foregroundName: String? = null
            var scale = 1f

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name.lowercase(Locale.ROOT)) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component") ?: continue
                        val drawable = parser.getAttributeValue(null, "drawable") ?: continue
                        val normalized = normalizeComponent(component) ?: continue
                        mappings[normalized] = drawable
                    }

                    "calendar" -> {
                        val component = parser.getAttributeValue(null, "component") ?: continue
                        val prefix = parser.getAttributeValue(null, "prefix")
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: continue
                        val normalized = normalizeComponent(component) ?: continue
                        calendars[normalized] = prefix
                    }

                    "iconback" -> backgrounds.addAll(imageAttributeValues(parser))
                    "iconmask" -> if (maskName == null) {
                        maskName = imageAttributeValues(parser).firstOrNull()
                    }

                    "iconupon" -> if (foregroundName == null) {
                        foregroundName = imageAttributeValues(parser).firstOrNull()
                    }

                    "scale" -> {
                        val parsed = parser.getAttributeValue(null, "factor")
                            ?.trim()
                            ?.toFloatOrNull()
                        if (parsed != null && parsed.isFinite()) {
                            scale = parsed.coerceIn(MIN_FALLBACK_SCALE, MAX_FALLBACK_SCALE)
                        }
                    }
                }
            }

            return ParsedAppFilter(
                componentToDrawable = mappings,
                componentToCalendarPrefix = calendars,
                fallbackStyle = IconPackFallbackStyle(
                    backgroundNames = backgrounds.toList(),
                    maskName = maskName,
                    foregroundName = foregroundName,
                    scale = scale,
                ),
            )
        }

        private fun imageAttributeValues(parser: XmlPullParser): List<String> {
            val values = ArrayList<String>()
            for (index in 0 until parser.attributeCount) {
                val attributeName = parser.getAttributeName(index).lowercase(Locale.ROOT)
                if (attributeName == "img" || attributeName.matches(IMAGE_ATTRIBUTE)) {
                    parser.getAttributeValue(index)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(values::add)
                }
            }
            return values
        }

        private fun normalizeComponent(rawComponent: String): String? {
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

        private val IMAGE_ATTRIBUTE = Regex("img\\d+")
        private const val CALENDAR_PROBE_SIZE = 96
        private const val CALENDAR_VISIBLE_ALPHA = 24
        private const val CALENDAR_FINISHED_COVERAGE = 0.25f
        private const val CALENDAR_FINISHED_SPAN = 0.70f
        private const val MIN_FALLBACK_SCALE = 0.5f
        private const val MAX_FALLBACK_SCALE = 2f
    }
}

/** A single standard appfilter ComponentInfo -> drawable association. */
internal data class IconPackMapping(
    val component: ComponentName,
    val drawableName: String,
)

internal data class IconPackCalendarMapping(
    val component: ComponentName,
    val drawablePrefix: String,
)

private data class ParsedAppFilter(
    val componentToDrawable: Map<String, String>,
    val componentToCalendarPrefix: Map<String, String>,
    val fallbackStyle: IconPackFallbackStyle,
)

private data class IconPackFallbackStyle(
    val backgroundNames: List<String>,
    val maskName: String?,
    val foregroundName: String?,
    val scale: Float,
) {
    val hasRenderableLayers: Boolean
        get() = backgroundNames.isNotEmpty() || maskName != null || foregroundName != null
}
