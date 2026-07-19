package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.OutlineSink
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray
import dev.skaldebane.kttf.internal.tables.variations.GlyphVariationDeltas
import dev.skaldebane.kttf.internal.tables.variations.GvarTable
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates

internal class GlyfTable private constructor(
    private val loca: LocaTable,
    private val data: ByteSlice,
) {
    internal fun outline(
        glyphId: Int,
        sink: OutlineSink,
        variations: GvarTable? = null,
        coordinates: VariationCoordinates? = null,
    ): FontBounds? {
        val glyph = glyphData(glyphId) ?: return null
        val builder = OutlineBuilder(sink)
        if (!outlineGlyph(glyphId, glyph, Transform.IDENTITY, 0, builder, variations, coordinates)) return null
        return builder.bounds()
    }

    internal fun storedBounds(glyphId: Int): FontBounds? {
        val glyph = glyphData(glyphId) ?: return null
        return FontBounds(
            xMin = glyph.readInt16(2) ?: return null,
            yMin = glyph.readInt16(4) ?: return null,
            xMax = glyph.readInt16(6) ?: return null,
            yMax = glyph.readInt16(8) ?: return null,
        )
    }

    internal fun phantomDeltas(
        glyphId: Int,
        variations: GvarTable,
        coordinates: VariationCoordinates,
    ): GlyphVariationDeltas? {
        val glyph = glyphData(glyphId) ?: return null
        val contourCount = glyph.readInt16(0) ?: return null
        val body = glyph.sliceFrom(GLYPH_HEADER_SIZE) ?: return null
        val pointCount =
            when {
                contourCount > 0 -> {
                    body.readUInt16((contourCount - 1) * 2)?.plus(1) ?: return null
                }

                contourCount < 0 -> {
                    compositeComponentCount(body) ?: return null
                }

                else -> {
                    0
                }
            }
        return variations.glyphDeltas(
            glyphId,
            coordinates,
            IntArray(pointCount),
            IntArray(pointCount),
            IntArray(0),
        )
    }

    private fun glyphData(glyphId: Int): ByteSlice? {
        val range = loca.glyphRange(glyphId) ?: return null
        if (range.first > Int.MAX_VALUE || range.last >= Int.MAX_VALUE) return null
        val start = range.first.toInt()
        val length = (range.last - range.first + 1).toInt()
        return data.slice(start, length)
    }

    private fun outlineGlyph(
        glyphId: Int,
        glyph: ByteSlice,
        transform: Transform,
        depth: Int,
        builder: OutlineBuilder,
        variations: GvarTable?,
        coordinates: VariationCoordinates?,
    ): Boolean {
        if (depth >= MAX_COMPONENT_DEPTH) return false
        val contourCount = glyph.readInt16(0) ?: return false
        if (glyph.size < GLYPH_HEADER_SIZE) return false
        return when {
            contourCount > 0 -> {
                outlineSimple(
                    glyph.sliceFrom(GLYPH_HEADER_SIZE) ?: return false,
                    glyphId,
                    contourCount,
                    transform,
                    builder,
                    variations,
                    coordinates,
                )
            }

            contourCount < 0 -> {
                outlineComposite(
                    glyph.sliceFrom(GLYPH_HEADER_SIZE) ?: return false,
                    glyphId,
                    transform,
                    depth,
                    builder,
                    variations,
                    coordinates,
                )
            }

            else -> {
                true
            }
        }
    }

    private fun outlineSimple(
        body: ByteSlice,
        glyphId: Int,
        contourCount: Int,
        transform: Transform,
        builder: OutlineBuilder,
        variations: GvarTable?,
        coordinates: VariationCoordinates?,
    ): Boolean {
        val endpointsBytes = body.slice(0, contourCount * 2) ?: return false
        val endpoints = FixedRecordArray.create(endpointsBytes, contourCount, 2) ?: return false
        val lastEndpoint = endpoints[contourCount - 1]?.readUInt16(0) ?: return false
        if (lastEndpoint == 0xFFFF) return false
        val pointCount = lastEndpoint + 1
        if (pointCount == 1) return true

        var offset = contourCount * 2
        val instructionLength = body.readUInt16(offset) ?: return false
        offset += 2
        if (!body.contains(offset, instructionLength)) return false
        offset += instructionLength
        val flagsStart = offset
        var flagsLeft = pointCount
        var xLength = 0
        var yLength = 0
        while (flagsLeft > 0) {
            val flags = body.readUInt8(offset++) ?: return false
            val repetitions =
                if (flags and REPEAT_FLAG != 0) {
                    val repeat = body.readUInt8(offset++) ?: return false
                    repeat + 1
                } else {
                    1
                }
            if (repetitions > flagsLeft) return false
            xLength += coordinateWidth(flags, X_SHORT, X_SAME) * repetitions
            yLength += coordinateWidth(flags, Y_SHORT, Y_SAME) * repetitions
            flagsLeft -= repetitions
        }

        val flags = body.slice(flagsStart, offset - flagsStart) ?: return false
        val xCoordinates = body.slice(offset, xLength) ?: return false
        val yCoordinates = body.slice(offset + xLength, yLength) ?: return false
        val points = PointCursor(endpoints, flags, xCoordinates, yCoordinates, pointCount)
        val pointX = IntArray(pointCount)
        val pointY = IntArray(pointCount)
        val pointOnCurve = BooleanArray(pointCount)
        val pointLast = BooleanArray(pointCount)
        var pointIndex = 0
        while (points.next()) {
            pointX[pointIndex] = points.x
            pointY[pointIndex] = points.y
            pointOnCurve[pointIndex] = points.onCurve
            pointLast[pointIndex] = points.lastPoint
            pointIndex++
        }
        if (!points.complete) return false
        val contourEnds = IntArray(contourCount) { endpoints[it]?.readUInt16(0) ?: return false }
        val deltas =
            if (variations != null && coordinates != null) {
                variations.glyphDeltas(glyphId, coordinates, pointX, pointY, contourEnds) ?: return false
            } else {
                null
            }
        pointIndex = 0
        while (pointIndex < pointCount) {
            val delta = deltas?.get(pointIndex)
            builder.pushPoint(
                x = pointX[pointIndex].toFloat() + (delta?.x ?: 0f),
                y = pointY[pointIndex].toFloat() + (delta?.y ?: 0f),
                onCurve = pointOnCurve[pointIndex],
                lastPoint = pointLast[pointIndex],
                transform = transform,
            )
            pointIndex++
        }
        return true
    }

    private fun outlineComposite(
        body: ByteSlice,
        glyphId: Int,
        parentTransform: Transform,
        depth: Int,
        builder: OutlineBuilder,
        variations: GvarTable?,
        coordinates: VariationCoordinates?,
    ): Boolean {
        val componentDeltas =
            if (variations != null && coordinates != null) {
                variations.componentDeltas(glyphId, coordinates, compositeComponentCount(body) ?: return false)
                    ?: return false
            } else {
                null
            }
        var offset = 0
        var more = true
        var componentIndex = 0
        while (more) {
            val flags = body.readUInt16(offset) ?: return true
            val glyphId = body.readUInt16(offset + 2) ?: return true
            offset += 4

            val words = flags and ARGUMENTS_ARE_WORDS != 0
            val xyValues = flags and ARGUMENTS_ARE_XY_VALUES != 0
            var translateX = 0f
            var translateY = 0f
            if (words) {
                val first = if (xyValues) body.readInt16(offset) else body.readUInt16(offset)
                val second = if (xyValues) body.readInt16(offset + 2) else body.readUInt16(offset + 2)
                if (first == null || second == null) return true
                if (xyValues) {
                    translateX = first.toFloat()
                    translateY = second.toFloat()
                }
                offset += 4
            } else {
                val first = if (xyValues) body.readInt8(offset) else body.readUInt8(offset)
                val second = if (xyValues) body.readInt8(offset + 1) else body.readUInt8(offset + 1)
                if (first == null || second == null) return true
                if (xyValues) {
                    translateX = first.toFloat()
                    translateY = second.toFloat()
                }
                offset += 2
            }

            var component = Transform(e = translateX, f = translateY)
            val componentDelta = componentDeltas?.get(componentIndex)
            if (xyValues && componentDelta != null) {
                component = component.copy(e = component.e + componentDelta.x, f = component.f + componentDelta.y)
            }
            component =
                when {
                    flags and TWO_BY_TWO != 0 -> {
                        val a = body.readF2Dot14(offset) ?: return true
                        val b = body.readF2Dot14(offset + 2) ?: return true
                        val c = body.readF2Dot14(offset + 4) ?: return true
                        val d = body.readF2Dot14(offset + 6) ?: return true
                        offset += 8
                        component.copy(a = a, b = b, c = c, d = d)
                    }

                    flags and X_AND_Y_SCALE != 0 -> {
                        val a = body.readF2Dot14(offset) ?: return true
                        val d = body.readF2Dot14(offset + 2) ?: return true
                        offset += 4
                        component.copy(a = a, d = d)
                    }

                    flags and UNIFORM_SCALE != 0 -> {
                        val scale = body.readF2Dot14(offset) ?: return true
                        offset += 2
                        component.copy(a = scale, d = scale)
                    }

                    else -> {
                        component
                    }
                }

            val child = glyphData(glyphId)
            if (child != null &&
                !outlineGlyph(
                    glyphId,
                    child,
                    parentTransform.combine(component),
                    depth + 1,
                    builder,
                    variations,
                    coordinates,
                )
            ) {
                return false
            }
            more = flags and MORE_COMPONENTS != 0
            componentIndex++
        }
        return true
    }

    private fun compositeComponentCount(body: ByteSlice): Int? {
        var offset = 0
        var count = 0
        var more = true
        while (more) {
            val flags = body.readUInt16(offset) ?: return null
            if (body.readUInt16(offset + 2) == null) return null
            offset += 4
            offset += if (flags and ARGUMENTS_ARE_WORDS != 0) 4 else 2
            offset +=
                when {
                    flags and TWO_BY_TWO != 0 -> 8
                    flags and X_AND_Y_SCALE != 0 -> 4
                    flags and UNIFORM_SCALE != 0 -> 2
                    else -> 0
                }
            if (!body.contains(offset, 0)) return null
            count++
            more = flags and MORE_COMPONENTS != 0
        }
        return count
    }

    internal companion object {
        private const val GLYPH_HEADER_SIZE: Int = 10
        private const val MAX_COMPONENT_DEPTH: Int = 32

        private const val ON_CURVE: Int = 0x01
        private const val X_SHORT: Int = 0x02
        private const val Y_SHORT: Int = 0x04
        private const val REPEAT_FLAG: Int = 0x08
        private const val X_SAME: Int = 0x10
        private const val Y_SAME: Int = 0x20

        private const val ARGUMENTS_ARE_WORDS: Int = 0x0001
        private const val ARGUMENTS_ARE_XY_VALUES: Int = 0x0002
        private const val UNIFORM_SCALE: Int = 0x0008
        private const val MORE_COMPONENTS: Int = 0x0020
        private const val X_AND_Y_SCALE: Int = 0x0040
        private const val TWO_BY_TWO: Int = 0x0080

        internal fun parse(
            loca: LocaTable,
            data: ByteSlice,
        ): GlyfTable = GlyfTable(loca, data)

        private fun coordinateWidth(
            flags: Int,
            shortFlag: Int,
            sameFlag: Int,
        ): Int =
            when {
                flags and shortFlag != 0 -> 1
                flags and sameFlag == 0 -> 2
                else -> 0
            }
    }

    private class PointCursor(
        private val endpoints: FixedRecordArray,
        private val flags: ByteSlice,
        private val xCoordinates: ByteSlice,
        private val yCoordinates: ByteSlice,
        pointCount: Int,
    ) {
        private var pointsLeft = pointCount
        private var pointIndex = 0
        private var endpointIndex = 1
        private var endpointLeft = endpoints[0]?.readUInt16(0) ?: 0
        private var flagsOffset = 0
        private var repeatLeft = 0
        private var currentFlags = 0
        private var xOffset = 0
        private var yOffset = 0

        var x: Int = 0
            private set
        var y: Int = 0
            private set
        var onCurve: Boolean = false
            private set
        var lastPoint: Boolean = false
            private set
        var complete: Boolean = false
            private set

        fun next(): Boolean {
            if (pointsLeft <= 0) {
                complete = true
                return false
            }
            val flag = nextFlag() ?: return false
            x = (x + coordinateDelta(flag, true)).toShort().toInt()
            y = (y + coordinateDelta(flag, false)).toShort().toInt()
            onCurve = flag and ON_CURVE != 0
            lastPoint = nextEndpoint()
            pointsLeft--
            pointIndex++
            return true
        }

        private fun nextFlag(): Int? {
            if (repeatLeft == 0) {
                currentFlags = flags.readUInt8(flagsOffset++) ?: return null
                if (currentFlags and REPEAT_FLAG != 0) {
                    repeatLeft = flags.readUInt8(flagsOffset++) ?: return null
                }
            } else {
                repeatLeft--
            }
            return currentFlags
        }

        private fun coordinateDelta(
            flags: Int,
            horizontal: Boolean,
        ): Int {
            val shortFlag = if (horizontal) X_SHORT else Y_SHORT
            val sameFlag = if (horizontal) X_SAME else Y_SAME
            val coordinates = if (horizontal) xCoordinates else yCoordinates
            var offset = if (horizontal) xOffset else yOffset
            val delta =
                if (flags and shortFlag != 0) {
                    val value = coordinates.readUInt8(offset) ?: 0
                    offset++
                    if (flags and sameFlag != 0) value else -value
                } else if (flags and sameFlag == 0) {
                    val value = coordinates.readInt16(offset) ?: 0
                    offset += 2
                    value
                } else {
                    0
                }
            if (horizontal) xOffset = offset else yOffset = offset
            return delta
        }

        private fun nextEndpoint(): Boolean {
            if (endpointLeft == 0) {
                val end = endpoints[endpointIndex]?.readUInt16(0)
                if (end != null) {
                    val previous = endpoints[endpointIndex - 1]?.readUInt16(0) ?: 0
                    endpointLeft = (end - previous).coerceAtLeast(0)
                    endpointLeft = (endpointLeft - 1).coerceAtLeast(0)
                }
                endpointIndex++
                return true
            }
            endpointLeft--
            return false
        }
    }

    private data class Transform(
        val a: Float = 1f,
        val b: Float = 0f,
        val c: Float = 0f,
        val d: Float = 1f,
        val e: Float = 0f,
        val f: Float = 0f,
    ) {
        fun combine(child: Transform): Transform =
            Transform(
                a = a * child.a + c * child.b,
                b = b * child.a + d * child.b,
                c = a * child.c + c * child.d,
                d = b * child.c + d * child.d,
                e = a * child.e + c * child.f + e,
                f = b * child.e + d * child.f + f,
            )

        companion object {
            val IDENTITY: Transform = Transform()
        }
    }

    private class OutlineBuilder(
        private val sink: OutlineSink,
    ) {
        private var hasBounds = false
        private var xMin = Float.MAX_VALUE
        private var yMin = Float.MAX_VALUE
        private var xMax = -Float.MAX_VALUE
        private var yMax = -Float.MAX_VALUE

        private var hasFirstOn = false
        private var firstOnX = 0f
        private var firstOnY = 0f
        private var hasFirstOff = false
        private var firstOffX = 0f
        private var firstOffY = 0f
        private var hasLastOff = false
        private var lastOffX = 0f
        private var lastOffY = 0f

        fun pushPoint(
            x: Float,
            y: Float,
            onCurve: Boolean,
            lastPoint: Boolean,
            transform: Transform,
        ) {
            if (!hasFirstOn) {
                if (onCurve) {
                    hasFirstOn = true
                    firstOnX = x
                    firstOnY = y
                    moveTo(x, y, transform)
                } else if (hasFirstOff) {
                    val midX = (firstOffX + x) * 0.5f
                    val midY = (firstOffY + y) * 0.5f
                    hasFirstOn = true
                    firstOnX = midX
                    firstOnY = midY
                    hasLastOff = true
                    lastOffX = x
                    lastOffY = y
                    moveTo(midX, midY, transform)
                } else {
                    hasFirstOff = true
                    firstOffX = x
                    firstOffY = y
                }
            } else if (hasLastOff) {
                if (onCurve) {
                    quadTo(lastOffX, lastOffY, x, y, transform)
                    hasLastOff = false
                } else {
                    val midX = (lastOffX + x) * 0.5f
                    val midY = (lastOffY + y) * 0.5f
                    quadTo(lastOffX, lastOffY, midX, midY, transform)
                    lastOffX = x
                    lastOffY = y
                }
            } else if (onCurve) {
                lineTo(x, y, transform)
            } else {
                hasLastOff = true
                lastOffX = x
                lastOffY = y
            }
            if (lastPoint) finishContour(transform)
        }

        fun bounds(): FontBounds? {
            if (!hasBounds) return null
            val xMin = xMin.toShortIntOrNull() ?: return null
            val yMin = yMin.toShortIntOrNull() ?: return null
            val xMax = xMax.toShortIntOrNull() ?: return null
            val yMax = yMax.toShortIntOrNull() ?: return null
            return FontBounds(xMin, yMin, xMax, yMax)
        }

        private fun finishContour(transform: Transform) {
            if (hasFirstOff && hasLastOff) {
                val midX = (lastOffX + firstOffX) * 0.5f
                val midY = (lastOffY + firstOffY) * 0.5f
                quadTo(lastOffX, lastOffY, midX, midY, transform)
                hasLastOff = false
            }
            when {
                hasFirstOn && hasFirstOff -> quadTo(firstOffX, firstOffY, firstOnX, firstOnY, transform)
                hasFirstOn && hasLastOff -> quadTo(lastOffX, lastOffY, firstOnX, firstOnY, transform)
                hasFirstOn -> lineTo(firstOnX, firstOnY, transform)
            }
            hasFirstOn = false
            hasFirstOff = false
            hasLastOff = false
            sink.close()
        }

        private fun moveTo(
            x: Float,
            y: Float,
            transform: Transform,
        ) {
            val tx = transform.a * x + transform.c * y + transform.e
            val ty = transform.b * x + transform.d * y + transform.f
            extend(tx, ty)
            sink.moveTo(tx, ty)
        }

        private fun lineTo(
            x: Float,
            y: Float,
            transform: Transform,
        ) {
            val tx = transform.a * x + transform.c * y + transform.e
            val ty = transform.b * x + transform.d * y + transform.f
            extend(tx, ty)
            sink.lineTo(tx, ty)
        }

        private fun quadTo(
            controlX: Float,
            controlY: Float,
            x: Float,
            y: Float,
            transform: Transform,
        ) {
            val tcx = transform.a * controlX + transform.c * controlY + transform.e
            val tcy = transform.b * controlX + transform.d * controlY + transform.f
            val tx = transform.a * x + transform.c * y + transform.e
            val ty = transform.b * x + transform.d * y + transform.f
            extend(tcx, tcy)
            extend(tx, ty)
            sink.quadTo(tcx, tcy, tx, ty)
        }

        private fun extend(
            x: Float,
            y: Float,
        ) {
            hasBounds = true
            xMin = minOf(xMin, x)
            yMin = minOf(yMin, y)
            xMax = maxOf(xMax, x)
            yMax = maxOf(yMax, y)
        }
    }
}

private fun ByteSlice.readF2Dot14(offset: Int): Float? = readInt16(offset)?.div(16384f)

private fun Float.toShortIntOrNull(): Int? {
    if (!isFinite()) return null
    val value = toInt()
    return value.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
}
