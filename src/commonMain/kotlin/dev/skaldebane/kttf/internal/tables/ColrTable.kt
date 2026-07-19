package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray
import dev.skaldebane.kttf.internal.tables.variations.DeltaSetIndexMap
import dev.skaldebane.kttf.internal.tables.variations.ItemVariationStore
import dev.skaldebane.kttf.internal.tables.variations.NormalizedCoordinate
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

internal data class ColrClipBox(
    internal val xMin: Float,
    internal val yMin: Float,
    internal val xMax: Float,
    internal val yMax: Float,
)

internal data class ColrTransform(
    internal val a: Float,
    internal val b: Float,
    internal val c: Float,
    internal val d: Float,
    internal val e: Float,
    internal val f: Float,
) {
    internal companion object {
        internal fun translate(
            x: Float,
            y: Float,
        ): ColrTransform = ColrTransform(1f, 0f, 0f, 1f, x, y)

        internal fun scale(
            x: Float,
            y: Float,
        ): ColrTransform = ColrTransform(x, 0f, 0f, y, 0f, 0f)

        internal fun rotate(angle: Float): ColrTransform {
            val radians = angle * PI.toFloat()
            val cosine = cos(radians)
            val sine = sin(radians)
            return ColrTransform(cosine, sine, -sine, cosine, 0f, 0f)
        }

        internal fun skew(
            x: Float,
            y: Float,
        ): ColrTransform = ColrTransform(1f, tan(y * PI.toFloat()), -tan(x * PI.toFloat()), 1f, 0f, 0f)
    }
}

internal enum class ColrGradientExtend {
    PAD,
    REPEAT,
    REFLECT,
}

internal data class ColrGradientStop(
    internal val offset: Float,
    internal val color: ColorRgba,
)

internal sealed interface ColrPaint {
    data class Solid(
        internal val color: ColorRgba,
    ) : ColrPaint

    data class LinearGradient(
        internal val x0: Float,
        internal val y0: Float,
        internal val x1: Float,
        internal val y1: Float,
        internal val x2: Float,
        internal val y2: Float,
        internal val extend: ColrGradientExtend,
        internal val stops: List<ColrGradientStop>,
    ) : ColrPaint

    data class RadialGradient(
        internal val x0: Float,
        internal val y0: Float,
        internal val r0: Float,
        internal val x1: Float,
        internal val y1: Float,
        internal val r1: Float,
        internal val extend: ColrGradientExtend,
        internal val stops: List<ColrGradientStop>,
    ) : ColrPaint

    data class SweepGradient(
        internal val centerX: Float,
        internal val centerY: Float,
        internal val startAngle: Float,
        internal val endAngle: Float,
        internal val extend: ColrGradientExtend,
        internal val stops: List<ColrGradientStop>,
    ) : ColrPaint
}

internal enum class ColrCompositeMode {
    CLEAR,
    SOURCE,
    DESTINATION,
    SOURCE_OVER,
    DESTINATION_OVER,
    SOURCE_IN,
    DESTINATION_IN,
    SOURCE_OUT,
    DESTINATION_OUT,
    SOURCE_ATOP,
    DESTINATION_ATOP,
    XOR,
    PLUS,
    SCREEN,
    OVERLAY,
    DARKEN,
    LIGHTEN,
    COLOR_DODGE,
    COLOR_BURN,
    HARD_LIGHT,
    SOFT_LIGHT,
    DIFFERENCE,
    EXCLUSION,
    MULTIPLY,
    HUE,
    SATURATION,
    COLOR,
    LUMINOSITY,
}

/** The stable, graph-independent command boundary used by the internal COLR executor. */
internal interface ColrPainter {
    fun outlineGlyph(glyphId: Int)

    fun paint(paint: ColrPaint)

    fun pushClip()

    fun pushClipBox(clipBox: ColrClipBox)

    fun popClip()

    fun pushLayer(mode: ColrCompositeMode)

    fun popLayer()

    fun pushTransform(transform: ColrTransform)

    fun popTransform()
}

/** Overrides embedded COLR variation deltas when it returns a value. */
internal fun interface ColrDeltaProvider {
    fun delta(
        varIndex: Long,
        coordinates: FloatArray,
    ): Float?
}

internal class ColrTable private constructor(
    private val palettes: CpalTable,
    private val data: ByteSlice,
    private val version: Int,
    private val baseGlyphs: FixedRecordArray,
    private val layers: FixedRecordArray,
    private val baseGlyphPaintsOffset: Int,
    private val baseGlyphPaints: FixedRecordArray?,
    private val layerListOffset: Int?,
    private val layerPaintOffsets: FixedRecordArray?,
    private val clipList: ClipList?,
    private val varIndexMap: DeltaSetIndexMap?,
    private val variationStore: ItemVariationStore?,
) {
    internal val isSimple: Boolean
        get() = version == 0

    internal fun contains(glyphId: Int): Boolean = findV1(glyphId) != null || findV0(glyphId) != null

    internal fun clipBox(
        glyphId: Int,
        coordinates: FloatArray = FloatArray(0),
        deltas: ColrDeltaProvider? = null,
    ): ColrClipBox? = clipList?.find(glyphId, coordinates, deltaProvider(coordinates, deltas))

    internal fun paint(
        glyphId: Int,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray = FloatArray(0),
        deltas: ColrDeltaProvider? = null,
    ): Boolean {
        if (palette !in 0..0xFFFF || glyphId !in 0..0xFFFF) return false
        val context = PaintContext()
        return paintGlyph(
            glyphId,
            palette,
            foreground,
            painter,
            coordinates,
            deltaProvider(coordinates, deltas),
            context,
        )
    }

    private fun paintGlyph(
        glyphId: Int,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray,
        deltas: ColrDeltaProvider?,
        context: PaintContext,
    ): Boolean {
        val v1 = findV1(glyphId)
        if (v1 != null) {
            val clip = clipList?.find(glyphId, coordinates, deltas)
            if (clip != null) painter.pushClipBox(clip)
            relative(baseGlyphPaintsOffset, v1.second)?.let {
                parsePaint(it, palette, foreground, painter, coordinates, deltas, context)
            }
            if (clip != null) painter.popClip()
            return true
        }

        val v0 = findV0(glyphId) ?: return false
        val end = v0.first.toLong() + v0.second
        if (end > 0xFFFF || end > layers.size) return false
        var index = v0.first
        while (index < end.toInt()) {
            if (!context.consumeOperation()) break
            val layer = layers[index] ?: return false
            val layerGlyph = layer.readUInt16(0) ?: return false
            val paletteEntry = layer.readUInt16(2) ?: return false
            val color =
                if (paletteEntry ==
                    0xFFFF
                ) {
                    foreground
                } else {
                    palettes.color(palette, paletteEntry) ?: return false
                }
            painter.outlineGlyph(layerGlyph)
            painter.pushClip()
            painter.paint(ColrPaint.Solid(color))
            painter.popClip()
            index++
        }
        return true
    }

    private fun parsePaint(
        offset: Int,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray,
        deltas: ColrDeltaProvider?,
        context: PaintContext,
    ): Boolean {
        val format = data.readUInt8(offset) ?: return false
        if (!context.enter(offset)) return false
        val cursor = ColrCursor(data, offset + 1)
        val result =
            parsePaintFormat(offset, format, cursor, palette, foreground, painter, coordinates, deltas, context)
        context.leave()
        return result
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun parsePaintFormat(
        offset: Int,
        format: Int,
        cursor: ColrCursor,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray,
        provider: ColrDeltaProvider?,
        context: PaintContext,
    ): Boolean {
        when (format) {
            1 -> {
                val count = cursor.uint8() ?: return false
                val first = cursor.uint32() ?: return false
                val paintOffsets = layerPaintOffsets ?: return false
                val listOffset = layerListOffset ?: return false
                var i = 0
                while (i < count) {
                    if (context.exhausted) break
                    val index = first + i
                    if (index > Int.MAX_VALUE || index >= paintOffsets.size) return false
                    val paintOffset = paintOffsets[index.toInt()]?.readUInt32(0) ?: return false
                    relative(listOffset, paintOffset)?.let {
                        parsePaint(it, palette, foreground, painter, coordinates, provider, context)
                    }
                    i++
                }
            }

            2, 3 -> {
                val paletteEntry = cursor.uint16() ?: return false
                val alpha = cursor.f2Dot14() ?: return false
                val adjustedAlpha =
                    if (format ==
                        3
                    ) {
                        alpha + delta(cursor.uint32() ?: return false, 0, coordinates, provider) / 16384f
                    } else {
                        alpha
                    }
                val color = resolveColor(palette, paletteEntry, foreground)?.withAlpha(adjustedAlpha) ?: return false
                painter.paint(ColrPaint.Solid(color))
            }

            4, 5 -> {
                val lineOffset = cursor.uint24() ?: return false
                val values = FloatArray(6) { cursor.int16()?.toFloat() ?: return false }
                val varBase = if (format == 5) cursor.uint32() ?: return false else null
                applyDeltas(values, varBase, coordinates, provider, 1f)
                val line =
                    parseColorLine(
                        relative(offset, lineOffset) ?: return false,
                        format == 5,
                        palette,
                        foreground,
                        coordinates,
                        provider,
                        context,
                    )
                        ?: return false
                painter.paint(
                    ColrPaint.LinearGradient(
                        values[0],
                        values[1],
                        values[2],
                        values[3],
                        values[4],
                        values[5],
                        line.extend,
                        line.stops,
                    ),
                )
            }

            6, 7 -> {
                val lineOffset = cursor.uint24() ?: return false
                val values =
                    floatArrayOf(
                        cursor.int16()?.toFloat() ?: return false,
                        cursor.int16()?.toFloat() ?: return false,
                        cursor.uint16()?.toFloat() ?: return false,
                        cursor.int16()?.toFloat() ?: return false,
                        cursor.int16()?.toFloat() ?: return false,
                        cursor.uint16()?.toFloat() ?: return false,
                    )
                val varBase = if (format == 7) cursor.uint32() ?: return false else null
                applyDeltas(values, varBase, coordinates, provider, 1f)
                val line =
                    parseColorLine(
                        relative(offset, lineOffset) ?: return false,
                        format == 7,
                        palette,
                        foreground,
                        coordinates,
                        provider,
                        context,
                    )
                        ?: return false
                painter.paint(
                    ColrPaint.RadialGradient(
                        values[0],
                        values[1],
                        values[2],
                        values[3],
                        values[4],
                        values[5],
                        line.extend,
                        line.stops,
                    ),
                )
            }

            8, 9 -> {
                val lineOffset = cursor.uint24() ?: return false
                val centerX = cursor.int16()?.toFloat() ?: return false
                val centerY = cursor.int16()?.toFloat() ?: return false
                val startAngle = cursor.f2Dot14() ?: return false
                val endAngle = cursor.f2Dot14() ?: return false
                val varBase = if (format == 9) cursor.uint32() ?: return false else null
                val values = floatArrayOf(centerX, centerY, startAngle, endAngle)
                if (varBase != null) {
                    values[0] += delta(varBase, 0, coordinates, provider)
                    values[1] += delta(varBase, 1, coordinates, provider)
                    values[2] += delta(varBase, 2, coordinates, provider) / 16384f
                    values[3] += delta(varBase, 3, coordinates, provider) / 16384f
                }
                val line =
                    parseColorLine(
                        relative(offset, lineOffset) ?: return false,
                        format == 9,
                        palette,
                        foreground,
                        coordinates,
                        provider,
                        context,
                    )
                        ?: return false
                painter.paint(
                    ColrPaint.SweepGradient(values[0], values[1], values[2], values[3], line.extend, line.stops),
                )
            }

            10 -> {
                val paintOffset = cursor.uint24() ?: return false
                val glyphId = cursor.uint16() ?: return false
                painter.outlineGlyph(glyphId)
                painter.pushClip()
                relative(
                    offset,
                    paintOffset,
                )?.let { parsePaint(it, palette, foreground, painter, coordinates, provider, context) }
                painter.popClip()
            }

            11 -> {
                val glyphId = cursor.uint16() ?: return false
                paintGlyph(glyphId, palette, foreground, painter, coordinates, provider, context)
            }

            12, 13 -> {
                val paintOffset = cursor.uint24() ?: return false
                val transformOffset = cursor.uint24() ?: return false
                val transformCursor = ColrCursor(data, relative(offset, transformOffset) ?: return false)
                val values = FloatArray(6) { transformCursor.fixed() ?: return false }
                if (format == 13) {
                    val varBase = transformCursor.uint32() ?: return false
                    applyDeltas(values, varBase, coordinates, provider, 1f / 65536f)
                }
                transformChild(
                    ColrTransform(values[0], values[1], values[2], values[3], values[4], values[5]),
                    relative(offset, paintOffset),
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            14, 15 -> {
                val paintOffset = cursor.uint24() ?: return false
                val values =
                    floatArrayOf(
                        cursor.int16()?.toFloat() ?: return false,
                        cursor.int16()?.toFloat() ?: return false,
                    )
                if (format == 15) applyDeltas(values, cursor.uint32() ?: return false, coordinates, provider, 1f)
                transformChild(
                    ColrTransform.translate(values[0], values[1]),
                    relative(offset, paintOffset),
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            16, 17 -> {
                val paintOffset = cursor.uint24() ?: return false
                val values = floatArrayOf(cursor.f2Dot14() ?: return false, cursor.f2Dot14() ?: return false)
                if (format ==
                    17
                ) {
                    applyDeltas(values, cursor.uint32() ?: return false, coordinates, provider, 1f / 16384f)
                }
                transformChild(
                    ColrTransform.scale(values[0], values[1]),
                    relative(offset, paintOffset),
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            18, 19 -> {
                parseScaleAroundCenter(
                    offset,
                    format == 19,
                    false,
                    cursor,
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            20, 21 -> {
                val paintOffset = cursor.uint24() ?: return false
                var scale = cursor.f2Dot14() ?: return false
                if (format == 21) scale += delta(cursor.uint32() ?: return false, 0, coordinates, provider) / 16384f
                transformChild(
                    ColrTransform.scale(scale, scale),
                    relative(offset, paintOffset),
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            22, 23 -> {
                parseScaleAroundCenter(
                    offset,
                    format == 23,
                    true,
                    cursor,
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            24, 25 -> {
                val paintOffset = cursor.uint24() ?: return false
                var angle = cursor.f2Dot14() ?: return false
                if (format == 25) angle += delta(cursor.uint32() ?: return false, 0, coordinates, provider) / 16384f
                transformChild(
                    ColrTransform.rotate(angle),
                    relative(offset, paintOffset),
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            26, 27 -> {
                parseAngleAroundCenter(
                    offset,
                    format == 27,
                    true,
                    cursor,
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            28, 29 -> {
                val paintOffset = cursor.uint24() ?: return false
                val values = floatArrayOf(cursor.f2Dot14() ?: return false, cursor.f2Dot14() ?: return false)
                if (format ==
                    29
                ) {
                    applyDeltas(values, cursor.uint32() ?: return false, coordinates, provider, 1f / 16384f)
                }
                transformChild(
                    ColrTransform.skew(values[0], values[1]),
                    relative(offset, paintOffset),
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            30, 31 -> {
                parseAngleAroundCenter(
                    offset,
                    format == 31,
                    false,
                    cursor,
                    palette,
                    foreground,
                    painter,
                    coordinates,
                    provider,
                    context,
                )
            }

            32 -> {
                val sourceOffset = cursor.uint24() ?: return false
                val mode = cursor.uint8()?.toCompositeMode() ?: return false
                val backdropOffset = cursor.uint24() ?: return false
                painter.pushLayer(ColrCompositeMode.SOURCE_OVER)
                relative(
                    offset,
                    backdropOffset,
                )?.let { parsePaint(it, palette, foreground, painter, coordinates, provider, context) }
                painter.pushLayer(mode)
                relative(
                    offset,
                    sourceOffset,
                )?.let { parsePaint(it, palette, foreground, painter, coordinates, provider, context) }
                painter.popLayer()
                painter.popLayer()
            }

            else -> {
                return true
            }
        }
        return true
    }

    private fun parseScaleAroundCenter(
        offset: Int,
        variable: Boolean,
        uniform: Boolean,
        cursor: ColrCursor,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray,
        provider: ColrDeltaProvider?,
        context: PaintContext,
    ): Boolean {
        val paintOffset = cursor.uint24() ?: return false
        val scaleX = cursor.f2Dot14() ?: return false
        val scaleY = if (uniform) scaleX else cursor.f2Dot14() ?: return false
        val centerX = cursor.int16()?.toFloat() ?: return false
        val centerY = cursor.int16()?.toFloat() ?: return false
        val values = floatArrayOf(scaleX, scaleY, centerX, centerY)
        if (variable) {
            val base = cursor.uint32() ?: return false
            values[0] += delta(base, 0, coordinates, provider) / 16384f
            if (uniform) {
                values[1] = values[0]
                values[2] += delta(base, 1, coordinates, provider)
                values[3] += delta(base, 2, coordinates, provider)
            } else {
                values[1] += delta(base, 1, coordinates, provider) / 16384f
                values[2] += delta(base, 2, coordinates, provider)
                values[3] += delta(base, 3, coordinates, provider)
            }
        }
        aroundCenter(
            values[2],
            values[3],
            ColrTransform.scale(values[0], values[1]),
            relative(offset, paintOffset),
            palette,
            foreground,
            painter,
            coordinates,
            provider,
            context,
        )
        return true
    }

    private fun parseAngleAroundCenter(
        offset: Int,
        variable: Boolean,
        rotate: Boolean,
        cursor: ColrCursor,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray,
        provider: ColrDeltaProvider?,
        context: PaintContext,
    ): Boolean {
        val paintOffset = cursor.uint24() ?: return false
        val valueX = cursor.f2Dot14() ?: return false
        val valueY = if (rotate) 0f else cursor.f2Dot14() ?: return false
        val centerX = cursor.int16()?.toFloat() ?: return false
        val centerY = cursor.int16()?.toFloat() ?: return false
        val values = floatArrayOf(valueX, valueY, centerX, centerY)
        if (variable) {
            val base = cursor.uint32() ?: return false
            values[0] += delta(base, 0, coordinates, provider) / 16384f
            if (rotate) {
                values[2] += delta(base, 1, coordinates, provider)
                values[3] += delta(base, 2, coordinates, provider)
            } else {
                values[1] += delta(base, 1, coordinates, provider) / 16384f
                values[2] += delta(base, 2, coordinates, provider)
                values[3] += delta(base, 3, coordinates, provider)
            }
        }
        val transform = if (rotate) ColrTransform.rotate(values[0]) else ColrTransform.skew(values[0], values[1])
        aroundCenter(
            values[2],
            values[3],
            transform,
            relative(offset, paintOffset),
            palette,
            foreground,
            painter,
            coordinates,
            provider,
            context,
        )
        return true
    }

    private fun aroundCenter(
        x: Float,
        y: Float,
        transform: ColrTransform,
        childOffset: Int?,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray,
        provider: ColrDeltaProvider?,
        context: PaintContext,
    ) {
        painter.pushTransform(ColrTransform.translate(x, y))
        painter.pushTransform(transform)
        painter.pushTransform(ColrTransform.translate(-x, -y))
        childOffset?.let { parsePaint(it, palette, foreground, painter, coordinates, provider, context) }
        painter.popTransform()
        painter.popTransform()
        painter.popTransform()
    }

    private fun transformChild(
        transform: ColrTransform,
        childOffset: Int?,
        palette: Int,
        foreground: ColorRgba,
        painter: ColrPainter,
        coordinates: FloatArray,
        provider: ColrDeltaProvider?,
        context: PaintContext,
    ) {
        painter.pushTransform(transform)
        childOffset?.let { parsePaint(it, palette, foreground, painter, coordinates, provider, context) }
        painter.popTransform()
    }

    private fun parseColorLine(
        offset: Int,
        variable: Boolean,
        palette: Int,
        foreground: ColorRgba,
        coordinates: FloatArray,
        provider: ColrDeltaProvider?,
        context: PaintContext,
    ): ResolvedColorLine? {
        val cursor = ColrCursor(data, offset)
        val extend = cursor.uint8()?.toGradientExtend() ?: return null
        val count = cursor.uint16() ?: return null
        val recordSize = if (variable) 10 else 6
        if (!data.contains(cursor.offset, count * recordSize)) return null
        val stops = ArrayList<ColrGradientStop>(count)
        repeat(count) {
            if (!context.consumeOperation()) return null
            var stopOffset = cursor.f2Dot14() ?: return null
            val paletteEntry = cursor.uint16() ?: return null
            var alpha = cursor.f2Dot14() ?: return null
            if (variable) {
                val base = cursor.uint32() ?: return null
                stopOffset += delta(base, 0, coordinates, provider) / 16384f
                alpha += delta(base, 1, coordinates, provider) / 16384f
            }
            val color = resolveColor(palette, paletteEntry, foreground)?.withAlpha(alpha) ?: return null
            stops += ColrGradientStop(stopOffset, color)
        }
        return ResolvedColorLine(extend, stops)
    }

    private fun resolveColor(
        palette: Int,
        entry: Int,
        foreground: ColorRgba,
    ): ColorRgba? = if (entry == 0xFFFF) foreground else palettes.color(palette, entry)

    private fun findV0(glyphId: Int): Pair<Int, Int>? {
        val record = baseGlyphs.binarySearch(glyphId, 0) ?: return null
        return Pair(record.readUInt16(2) ?: return null, record.readUInt16(4) ?: return null)
    }

    private fun findV1(glyphId: Int): Pair<Int, Long>? {
        val record = baseGlyphPaints?.binarySearch(glyphId, 0) ?: return null
        return Pair(record.readUInt16(0) ?: return null, record.readUInt32(2) ?: return null)
    }

    private fun deltaProvider(
        coordinates: FloatArray,
        override: ColrDeltaProvider?,
    ): ColrDeltaProvider? {
        val map = varIndexMap ?: return override
        val store = variationStore ?: return override
        val normalized =
            VariationCoordinates.copyOf(
                coordinates.map(NormalizedCoordinate::fromFloat),
            )
        return ColrDeltaProvider { varIndex, sourceCoordinates ->
            override?.delta(varIndex, sourceCoordinates)
                ?: map
                    .map(varIndex.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    ?.let { store.delta(it.outerIndex, it.innerIndex, normalized) }
        }
    }

    internal companion object {
        private const val BASE_GLYPH_RECORD_SIZE: Int = 6
        private const val LAYER_RECORD_SIZE: Int = 4

        internal fun parse(
            palettes: CpalTable,
            data: ByteSlice,
        ): ColrTable? {
            val version = data.readUInt16(0) ?: return null
            if (version > 1) return null
            val baseCount = data.readUInt16(2) ?: return null
            val baseOffset = data.readUInt32(4)?.toIndexOrNull() ?: return null
            val layersOffset = data.readUInt32(8)?.toIndexOrNull() ?: return null
            val layerCount = data.readUInt16(12) ?: return null
            val baseGlyphs = data.records(baseOffset, baseCount, BASE_GLYPH_RECORD_SIZE) ?: return null
            val layers = data.records(layersOffset, layerCount, LAYER_RECORD_SIZE) ?: return null
            if (version == 0) {
                return ColrTable(palettes, data, version, baseGlyphs, layers, 0, null, null, null, null, null, null)
            }

            val basePaintOffset = data.readUInt32(14)?.toIndexOrNull() ?: return null
            val layerListRaw = data.readUInt32(18) ?: return null
            val layerListOffset = if (layerListRaw == 0L) null else layerListRaw.toIndexOrNull() ?: return null
            val clipListRaw = data.readUInt32(22) ?: return null
            val clipListOffset = if (clipListRaw == 0L) null else clipListRaw.toIndexOrNull() ?: return null
            val varIndexMapRaw = data.readUInt32(26) ?: return null
            val varIndexMapOffset = if (varIndexMapRaw == 0L) null else varIndexMapRaw.toIndexOrNull() ?: return null
            val variationStoreRaw = data.readUInt32(30) ?: return null
            val variationStoreOffset =
                if (variationStoreRaw == 0L) null else variationStoreRaw.toIndexOrNull() ?: return null
            val basePaintCount = data.readUInt32(basePaintOffset)?.toIndexOrNull() ?: return null
            val basePaintRecordsOffset = relative(basePaintOffset, 4) ?: return null
            val basePaints = data.records(basePaintRecordsOffset, basePaintCount, 6) ?: return null
            val layerPaints =
                layerListOffset?.let {
                    val count = data.readUInt32(it)?.toIndexOrNull() ?: return null
                    data.records(relative(it, 4) ?: return null, count, 4) ?: return null
                }
            val clipList = clipListOffset?.let { ClipList.parse(data, it) ?: return null }
            val variationStore =
                variationStoreOffset?.let {
                    ItemVariationStore.parse(data.sliceFrom(it) ?: return null) ?: return null
                }
            val varIndexMap =
                varIndexMapOffset?.let {
                    DeltaSetIndexMap.parse(data.sliceFrom(it) ?: return null)
                }
            return ColrTable(
                palettes,
                data,
                version,
                baseGlyphs,
                layers,
                basePaintOffset,
                basePaints,
                layerListOffset,
                layerPaints,
                clipList,
                varIndexMap,
                variationStore,
            )
        }
    }

    private class ClipList(
        private val data: ByteSlice,
        private val listOffset: Int,
        private val records: FixedRecordArray,
    ) {
        fun find(
            glyphId: Int,
            coordinates: FloatArray,
            provider: ColrDeltaProvider?,
        ): ColrClipBox? {
            var index = 0
            while (index < records.size) {
                val record = records[index] ?: return null
                val start = record.readUInt16(0) ?: return null
                val end = record.readUInt16(2) ?: return null
                if (glyphId in start..end) {
                    val boxOffset = record.readUInt24(4)?.toLong() ?: return null
                    val cursor = ColrCursor(data, relative(listOffset, boxOffset) ?: return null)
                    val format = cursor.uint8() ?: return null
                    val values = FloatArray(4) { cursor.int16()?.toFloat() ?: return null }
                    if (format == 2) applyDeltas(values, cursor.uint32() ?: return null, coordinates, provider, 1f)
                    return ColrClipBox(values[0], values[1], values[2], values[3])
                }
                index++
            }
            return null
        }

        companion object {
            fun parse(
                data: ByteSlice,
                offset: Int,
            ): ClipList? {
                data.readUInt8(offset) ?: return null
                val count = data.readUInt32(relative(offset, 1) ?: return null)?.toIndexOrNull() ?: return null
                val records = data.records(relative(offset, 5) ?: return null, count, 7) ?: return null
                return ClipList(data, offset, records)
            }
        }
    }
}

private data class ResolvedColorLine(
    val extend: ColrGradientExtend,
    val stops: List<ColrGradientStop>,
)

private class PaintContext {
    private val offsets = IntArray(MAX_RECURSION_DEPTH)
    private var depth = 0
    private var operations = 0

    val exhausted: Boolean
        get() = operations >= MAX_PAINT_OPERATIONS

    fun enter(offset: Int): Boolean {
        if (!consumeOperation() || depth >= offsets.size) return false
        var index = 0
        while (index < depth) {
            if (offsets[index] == offset) return false
            index++
        }
        offsets[depth++] = offset
        return true
    }

    fun consumeOperation(): Boolean {
        if (operations >= MAX_PAINT_OPERATIONS) return false
        operations++
        return true
    }

    fun leave() {
        depth--
    }

    private companion object {
        private const val MAX_RECURSION_DEPTH: Int = 64
        private const val MAX_PAINT_OPERATIONS: Int = 100_000
    }
}

private class ColrCursor(
    private val data: ByteSlice,
    internal var offset: Int,
) {
    fun uint8(): Int? = data.readUInt8(offset)?.also { offset++ }

    fun uint16(): Int? = data.readUInt16(offset)?.also { offset += 2 }

    fun int16(): Int? = data.readInt16(offset)?.also { offset += 2 }

    fun uint24(): Long? = data.readUInt24(offset)?.toLong()?.also { offset += 3 }

    fun uint32(): Long? = data.readUInt32(offset)?.also { offset += 4 }

    fun f2Dot14(): Float? = int16()?.toFloat()?.div(16384f)

    fun fixed(): Float? =
        data
            .readInt32(offset)
            ?.toFloat()
            ?.div(65536f)
            ?.also { offset += 4 }
}

private fun applyDeltas(
    values: FloatArray,
    varIndexBase: Long?,
    coordinates: FloatArray,
    provider: ColrDeltaProvider?,
    scale: Float,
) {
    if (varIndexBase == null) return
    values.indices.forEach { values[it] += delta(varIndexBase, it, coordinates, provider) * scale }
}

private fun delta(
    varIndexBase: Long,
    index: Int,
    coordinates: FloatArray,
    provider: ColrDeltaProvider?,
): Float {
    if (provider == null || coordinates.isEmpty() || varIndexBase == 0xFFFF_FFFFL) return 0f
    val varIndex = varIndexBase + index
    if (varIndex > 0xFFFF_FFFFL) return 0f
    return provider.delta(varIndex, coordinates) ?: 0f
}

private fun ByteSlice.records(
    offset: Int,
    count: Int,
    recordSize: Int,
): FixedRecordArray? {
    if (offset < 0 || count < 0 || recordSize <= 0 || count > Int.MAX_VALUE / recordSize) return null
    val bytes = slice(offset, count * recordSize) ?: return null
    return FixedRecordArray.create(bytes, count, recordSize)
}

private fun FixedRecordArray.binarySearch(
    target: Int,
    keyOffset: Int,
): ByteSlice? {
    var start = 0
    var end = size
    while (start < end) {
        val middle = (start + end) ushr 1
        val record = get(middle) ?: return null
        val key = record.readUInt16(keyOffset) ?: return null
        when {
            key < target -> start = middle + 1
            key > target -> end = middle
            else -> return record
        }
    }
    return null
}

private fun Int.toGradientExtend(): ColrGradientExtend? = ColrGradientExtend.entries.getOrNull(this)

private fun Int.toCompositeMode(): ColrCompositeMode? = ColrCompositeMode.entries.getOrNull(this)

private fun Long.toIndexOrNull(): Int? = if (this <= Int.MAX_VALUE) toInt() else null

private fun relative(
    base: Int,
    offset: Long,
): Int? {
    val result = base.toLong() + offset
    return if (result in 0..Int.MAX_VALUE.toLong()) result.toInt() else null
}
