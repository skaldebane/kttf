package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.OutlineSink
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import dev.skaldebane.kttf.internal.tables.variations.ItemVariationStore
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates
import kotlin.math.abs

internal enum class CffType2Dialect {
    CFF1,
    CFF2,
}

internal class CffType2Vm(
    private val globalSubroutines: CffIndex,
    private var localSubroutines: CffIndex?,
    private val localSubroutinesResolver: (() -> CffIndex?)? = null,
    private val charStrings: CffIndex,
    private val charset: CffCharset?,
    transform: CffTransform?,
    sink: OutlineSink,
    private val widthOnly: Boolean = false,
    private val dialect: CffType2Dialect = CffType2Dialect.CFF1,
    private val variationStore: ItemVariationStore? = null,
    private val coordinates: VariationCoordinates? = null,
) {
    private val stack =
        CffOperandStack(
            if (dialect == CffType2Dialect.CFF2) CffOperandStack.CFF2_CAPACITY else CffOperandStack.CFF1_CAPACITY,
        )
    private val path = CffPath(sink, transform)
    private var x = 0f
    private var y = 0f
    private var hasMoveTo = false
    private var hasOpenContour = false
    private var stemCount = 0
    private var parsedWidth: Float? = null
    private var hasEndChar = false
    private var hasSeac = false
    private var operationsRemaining = MAX_OPERATIONS
    private var scalars = FloatArray(0)
    private var hadVsIndex = false
    private var hadBlend = false

    internal fun outline(charString: ByteSlice): FontBounds? {
        if (dialect == CffType2Dialect.CFF2 && !updateScalars(0)) return null
        if (!execute(charString, 0)) return null
        if (dialect == CffType2Dialect.CFF1 && !hasEndChar) return null
        return path.bounds()
    }

    internal fun width(charString: ByteSlice): CffType2Width? {
        if (!execute(charString, 0)) return null
        return CffType2Width(parsedWidth)
    }

    private fun execute(
        charString: ByteSlice,
        depth: Int,
    ): Boolean {
        val stream = Stream.atStart(charString)
        while (!stream.atEnd) {
            if (operationsRemaining == 0) return false
            operationsRemaining--
            val operator = stream.readUInt8() ?: return false
            val success =
                when (operator) {
                    0, 2, 9, 13, 17 -> false
                    HSTEM, VSTEM, HSTEMHM, VSTEMHM -> parseStems()
                    VMOVETO -> parseMove(MoveKind.VERTICAL)
                    RLINETO -> parseRelativeLines()
                    HLINETO -> parseAlternatingLines(horizontalFirst = true)
                    VLINETO -> parseAlternatingLines(horizontalFirst = false)
                    RRCURVETO -> parseCurves()
                    CALLSUBR -> callLocalSubroutine(stream, depth)
                    RETURN -> if (dialect == CffType2Dialect.CFF1) return true else false
                    ESCAPE -> parseEscaped(stream)
                    ENDCHAR -> if (dialect == CffType2Dialect.CFF1) return parseEndChar(stream, depth) else false
                    VSINDEX -> if (dialect == CffType2Dialect.CFF2) parseVsIndex() else false
                    BLEND -> if (dialect == CffType2Dialect.CFF2) parseBlend() else false
                    HINTMASK, CNTRMASK -> parseMask(stream)
                    RMOVETO -> parseMove(MoveKind.BOTH)
                    HMOVETO -> parseMove(MoveKind.HORIZONTAL)
                    RCURVELINE -> parseCurveLine()
                    RLINECURVE -> parseLineCurve()
                    VVCURVETO -> parseSingleAxisCurves(vertical = true)
                    HHCURVETO -> parseSingleAxisCurves(vertical = false)
                    SHORTINT -> stack.push(stream.readInt16()?.toFloat() ?: return false)
                    CALLGSUBR -> callSubroutine(globalSubroutines, stream, depth)
                    VHCURVETO -> parseAlternatingCurves(verticalFirst = true)
                    HVCURVETO -> parseAlternatingCurves(verticalFirst = false)
                    in 32..254, FIXED -> stack.push(decodeType2Number(operator, stream) ?: return false)
                    else -> false
                }
            if (!success) return false
            if (widthOnly && parsedWidth != null) return true
            if (hasEndChar && !hasSeac) {
                if (!stream.atEnd) return false
                return true
            }
        }
        return true
    }

    private fun parseStems(): Boolean {
        var operandCount = stack.size
        if (dialect == CffType2Dialect.CFF1 && operandCount % 2 != 0 && parsedWidth == null) {
            parsedWidth = stack[0] ?: return false
            operandCount--
        }
        stemCount += operandCount / 2
        stack.clear()
        return true
    }

    private fun parseMask(stream: Stream): Boolean {
        var operandCount = stack.size
        if (dialect == CffType2Dialect.CFF1 && operandCount % 2 != 0) {
            if (parsedWidth == null) parsedWidth = stack[0] ?: return false
            operandCount--
        }
        stemCount += operandCount / 2
        stack.clear()
        val maskLength = (stemCount + 7) / 8
        return stream.advance(maskLength)
    }

    private fun parseMove(kind: MoveKind): Boolean {
        val coordinateCount = if (kind == MoveKind.BOTH) 2 else 1
        val widthOffset = if (dialect == CffType2Dialect.CFF1 && stack.size == coordinateCount + 1) 1 else 0
        if (stack.size != coordinateCount + widthOffset) return false
        if (widthOffset == 1 && parsedWidth == null) parsedWidth = stack[0] ?: return false

        if (hasOpenContour) path.close()
        hasMoveTo = true
        hasOpenContour = true
        when (kind) {
            MoveKind.BOTH -> {
                x += stack[widthOffset] ?: return false
                y += stack[widthOffset + 1] ?: return false
            }

            MoveKind.HORIZONTAL -> {
                x += stack[widthOffset] ?: return false
            }

            MoveKind.VERTICAL -> {
                y += stack[widthOffset] ?: return false
            }
        }
        path.moveTo(x, y)
        stack.clear()
        return true
    }

    private fun parseRelativeLines(): Boolean {
        if (!hasMoveTo || stack.size % 2 != 0) return false
        var index = 0
        while (index < stack.size) {
            x += stack[index] ?: return false
            y += stack[index + 1] ?: return false
            path.lineTo(x, y)
            index += 2
        }
        stack.clear()
        return true
    }

    private fun parseAlternatingLines(horizontalFirst: Boolean): Boolean {
        if (!hasMoveTo || stack.isEmpty) return false
        var horizontal = horizontalFirst
        var index = 0
        while (index < stack.size) {
            if (horizontal) x += stack[index] ?: return false else y += stack[index] ?: return false
            path.lineTo(x, y)
            horizontal = !horizontal
            index++
        }
        stack.clear()
        return true
    }

    private fun parseCurves(): Boolean {
        if (!hasMoveTo || stack.size % 6 != 0) return false
        var index = 0
        while (index < stack.size) {
            if (!curve(index)) return false
            index += 6
        }
        stack.clear()
        return true
    }

    private fun parseCurveLine(): Boolean {
        if (!hasMoveTo || stack.size < 8 || (stack.size - 2) % 6 != 0) return false
        var index = 0
        while (index < stack.size - 2) {
            if (!curve(index)) return false
            index += 6
        }
        x += stack[index] ?: return false
        y += stack[index + 1] ?: return false
        path.lineTo(x, y)
        stack.clear()
        return true
    }

    private fun parseLineCurve(): Boolean {
        if (!hasMoveTo || stack.size < 8 || (stack.size - 6) % 2 != 0) return false
        var index = 0
        while (index < stack.size - 6) {
            x += stack[index] ?: return false
            y += stack[index + 1] ?: return false
            path.lineTo(x, y)
            index += 2
        }
        if (!curve(index)) return false
        stack.clear()
        return true
    }

    private fun parseSingleAxisCurves(vertical: Boolean): Boolean {
        if (!hasMoveTo) return false
        var index = if (stack.size % 2 != 0) 1 else 0
        if ((stack.size - index) % 4 != 0) return false
        if (index == 1) {
            if (vertical) x += stack[0] ?: return false else y += stack[0] ?: return false
        }
        while (index < stack.size) {
            if (vertical) {
                val control1X = x
                val control1Y = y + (stack[index] ?: return false)
                val control2X = control1X + (stack[index + 1] ?: return false)
                val control2Y = control1Y + (stack[index + 2] ?: return false)
                x = control2X
                y = control2Y + (stack[index + 3] ?: return false)
                path.curveTo(control1X, control1Y, control2X, control2Y, x, y)
            } else {
                val control1X = x + (stack[index] ?: return false)
                val control1Y = y
                val control2X = control1X + (stack[index + 1] ?: return false)
                val control2Y = control1Y + (stack[index + 2] ?: return false)
                x = control2X + (stack[index + 3] ?: return false)
                y = control2Y
                path.curveTo(control1X, control1Y, control2X, control2Y, x, y)
            }
            index += 4
        }
        stack.clear()
        return true
    }

    private fun parseAlternatingCurves(verticalFirst: Boolean): Boolean {
        if (!hasMoveTo || stack.size < 4) return false
        var vertical = verticalFirst
        var index = 0
        while (index < stack.size) {
            val remaining = stack.size - index
            if (remaining < 4) return false
            if (vertical) {
                val control1X = x
                val control1Y = y + (stack[index] ?: return false)
                val control2X = control1X + (stack[index + 1] ?: return false)
                val control2Y = control1Y + (stack[index + 2] ?: return false)
                x = control2X + (stack[index + 3] ?: return false)
                y = control2Y
                index += 4
                if (stack.size - index == 1) y += stack[index++] ?: return false
                path.curveTo(control1X, control1Y, control2X, control2Y, x, y)
            } else {
                val control1X = x + (stack[index] ?: return false)
                val control1Y = y
                val control2X = control1X + (stack[index + 1] ?: return false)
                val control2Y = control1Y + (stack[index + 2] ?: return false)
                x = control2X
                y = control2Y + (stack[index + 3] ?: return false)
                index += 4
                if (stack.size - index == 1) x += stack[index++] ?: return false
                path.curveTo(control1X, control1Y, control2X, control2Y, x, y)
            }
            vertical = !vertical
        }
        stack.clear()
        return true
    }

    private fun parseEscaped(stream: Stream): Boolean =
        when (stream.readUInt8() ?: return false) {
            HFLEX -> parseHFlex()
            FLEX -> parseFlex()
            HFLEX1 -> parseHFlex1()
            FLEX1 -> parseFlex1()
            else -> false
        }

    private fun parseVsIndex(): Boolean {
        if (hadBlend || hadVsIndex || stack.size != 1) return false
        val index = stack.pop()?.toUInt16TruncatingOrNull() ?: return false
        if (!updateScalars(index)) return false
        hadVsIndex = true
        stack.clear()
        return true
    }

    private fun parseBlend(): Boolean {
        hadBlend = true
        val valueCount = stack.pop()?.toUInt16TruncatingOrNull() ?: return false
        val blendValueCount = valueCount.toLong() * (scalars.size + 1L)
        if (blendValueCount > stack.size.toLong()) return false

        val start = stack.size - blendValueCount.toInt()
        var valueIndex = valueCount - 1
        while (valueIndex >= 0) {
            var scalarIndex = scalars.lastIndex
            while (scalarIndex >= 0) {
                val delta = stack.pop() ?: return false
                if (!stack.add(start + valueIndex, delta * scalars[scalarIndex])) return false
                scalarIndex--
            }
            valueIndex--
        }
        return true
    }

    private fun updateScalars(index: Int): Boolean {
        val store = variationStore ?: return false
        val currentCoordinates = coordinates ?: return false
        val regionIndices = store.regionIndices(index) ?: return false
        if (regionIndices.size > MAX_BLEND_REGIONS) return false
        scalars = FloatArray(regionIndices.size) { store.regions.evaluate(regionIndices[it], currentCoordinates) }
        return true
    }

    private fun parseHFlex(): Boolean {
        if (!hasMoveTo || stack.size != 7) return false
        val x1 = x + stack.value(0)
        val y1 = y
        val x2 = x1 + stack.value(1)
        val y2 = y1 + stack.value(2)
        val x3 = x2 + stack.value(3)
        val y3 = y2
        val x4 = x3 + stack.value(4)
        val y4 = y2
        val x5 = x4 + stack.value(5)
        val y5 = y
        x = x5 + stack.value(6)
        path.curveTo(x1, y1, x2, y2, x3, y3)
        path.curveTo(x4, y4, x5, y5, x, y)
        stack.clear()
        return true
    }

    private fun parseFlex(): Boolean {
        if (!hasMoveTo || stack.size != 13) return false
        flexPairs()
        return true
    }

    private fun parseHFlex1(): Boolean {
        if (!hasMoveTo || stack.size != 9) return false
        val x1 = x + stack.value(0)
        val y1 = y + stack.value(1)
        val x2 = x1 + stack.value(2)
        val y2 = y1 + stack.value(3)
        val x3 = x2 + stack.value(4)
        val y3 = y2
        val x4 = x3 + stack.value(5)
        val y4 = y2
        val x5 = x4 + stack.value(6)
        val y5 = y4 + stack.value(7)
        x = x5 + stack.value(8)
        path.curveTo(x1, y1, x2, y2, x3, y3)
        path.curveTo(x4, y4, x5, y5, x, y)
        stack.clear()
        return true
    }

    private fun parseFlex1(): Boolean {
        if (!hasMoveTo || stack.size != 11) return false
        val startX = x
        val startY = y
        val x1 = x + stack.value(0)
        val y1 = y + stack.value(1)
        val x2 = x1 + stack.value(2)
        val y2 = y1 + stack.value(3)
        val x3 = x2 + stack.value(4)
        val y3 = y2 + stack.value(5)
        val x4 = x3 + stack.value(6)
        val y4 = y3 + stack.value(7)
        val x5 = x4 + stack.value(8)
        val y5 = y4 + stack.value(9)
        if (abs(x5 - startX) > abs(y5 - startY)) x = x5 + stack.value(10) else y = y5 + stack.value(10)
        path.curveTo(x1, y1, x2, y2, x3, y3)
        path.curveTo(x4, y4, x5, y5, x, y)
        stack.clear()
        return true
    }

    private fun flexPairs() {
        val x1 = x + stack.value(0)
        val y1 = y + stack.value(1)
        val x2 = x1 + stack.value(2)
        val y2 = y1 + stack.value(3)
        val x3 = x2 + stack.value(4)
        val y3 = y2 + stack.value(5)
        val x4 = x3 + stack.value(6)
        val y4 = y3 + stack.value(7)
        val x5 = x4 + stack.value(8)
        val y5 = y4 + stack.value(9)
        x = x5 + stack.value(10)
        y = y5 + stack.value(11)
        path.curveTo(x1, y1, x2, y2, x3, y3)
        path.curveTo(x4, y4, x5, y5, x, y)
        stack.clear()
    }

    private fun curve(index: Int): Boolean {
        val control1X = x + (stack[index] ?: return false)
        val control1Y = y + (stack[index + 1] ?: return false)
        val control2X = control1X + (stack[index + 2] ?: return false)
        val control2Y = control1Y + (stack[index + 3] ?: return false)
        x = control2X + (stack[index + 4] ?: return false)
        y = control2Y + (stack[index + 5] ?: return false)
        path.curveTo(control1X, control1Y, control2X, control2Y, x, y)
        return true
    }

    private fun callSubroutine(
        subroutines: CffIndex?,
        caller: Stream,
        depth: Int,
    ): Boolean {
        if (stack.isEmpty || depth == MAX_SUBROUTINE_DEPTH) return false
        val indexOperand = stack.pop() ?: return false
        if (!indexOperand.isFinite() || indexOperand != indexOperand.toInt().toFloat()) return false
        val index = indexOperand.toInt().toLong() + subroutineBias(subroutines?.size ?: 0)
        if (index !in 0..Int.MAX_VALUE.toLong()) return false
        val charString = subroutines?.get(index.toInt()) ?: return false
        if (!execute(charString, depth + 1)) return false
        return !hasEndChar || hasSeac || caller.atEnd
    }

    private fun callLocalSubroutine(
        caller: Stream,
        depth: Int,
    ): Boolean {
        if (stack.isEmpty || depth == MAX_SUBROUTINE_DEPTH) return false
        if (localSubroutines == null) localSubroutines = localSubroutinesResolver?.invoke()
        return callSubroutine(localSubroutines, caller, depth)
    }

    private fun parseEndChar(
        stream: Stream,
        depth: Int,
    ): Boolean {
        if (stack.size == 4 || (parsedWidth == null && stack.size == 5)) {
            val accentGlyph = seacGlyphId(stack.pop() ?: return false) ?: return false
            val baseGlyph = seacGlyphId(stack.pop() ?: return false) ?: return false
            val dy = stack.pop() ?: return false
            val dx = stack.pop() ?: return false
            if (parsedWidth == null && !stack.isEmpty) parsedWidth = stack.pop() ?: return false
            hasSeac = true
            if (depth == MAX_SUBROUTINE_DEPTH) return false

            val baseCharString = charStrings[baseGlyph] ?: return false
            if (!execute(baseCharString, depth + 1)) return false
            x = dx
            y = dy
            val accentCharString = charStrings[accentGlyph] ?: return false
            if (!execute(accentCharString, depth + 1)) return false
        } else if (stack.size == 1 && parsedWidth == null) {
            parsedWidth = stack.pop() ?: return false
        }
        if (hasOpenContour) {
            path.close()
            hasOpenContour = false
        }
        if (!stream.atEnd) return false
        hasEndChar = true
        return true
    }

    private fun seacGlyphId(value: Float): Int? {
        if (!value.isFinite()) return null
        return charset?.seacCodeToGid(value.toInt().takeIf { it in 0..UByte.MAX_VALUE.toInt() } ?: return null)
    }

    private enum class MoveKind {
        BOTH,
        HORIZONTAL,
        VERTICAL,
    }

    private companion object {
        const val HSTEM = 1
        const val VSTEM = 3
        const val VMOVETO = 4
        const val RLINETO = 5
        const val HLINETO = 6
        const val VLINETO = 7
        const val RRCURVETO = 8
        const val CALLSUBR = 10
        const val RETURN = 11
        const val ESCAPE = 12
        const val ENDCHAR = 14
        const val VSINDEX = 15
        const val BLEND = 16
        const val HSTEMHM = 18
        const val HINTMASK = 19
        const val CNTRMASK = 20
        const val RMOVETO = 21
        const val HMOVETO = 22
        const val VSTEMHM = 23
        const val RCURVELINE = 24
        const val RLINECURVE = 25
        const val VVCURVETO = 26
        const val HHCURVETO = 27
        const val SHORTINT = 28
        const val CALLGSUBR = 29
        const val VHCURVETO = 30
        const val HVCURVETO = 31
        const val FIXED = 255
        const val HFLEX = 34
        const val FLEX = 35
        const val HFLEX1 = 36
        const val FLEX1 = 37
        const val MAX_SUBROUTINE_DEPTH = 10
        const val MAX_OPERATIONS = 100_000
        const val MAX_BLEND_REGIONS = 64

        fun subroutineBias(count: Int): Int =
            when {
                count < 1240 -> 107
                count < 33900 -> 1131
                else -> 32768
            }
    }
}

private fun Float.toUInt16TruncatingOrNull(): Int? {
    if (!isFinite() || this < -1f || this >= 65536f) return null
    return toInt().takeIf { it in 0..UShort.MAX_VALUE.toInt() }
}

internal data class CffType2Width(
    internal val value: Float?,
)

private fun CffOperandStack.value(index: Int): Float = get(index)!!

private class CffPath(
    private val sink: OutlineSink,
    private val transform: CffTransform?,
) {
    private var hasBounds = false
    private var xMin = Float.MAX_VALUE
    private var yMin = Float.MAX_VALUE
    private var xMax = -Float.MAX_VALUE
    private var yMax = -Float.MAX_VALUE

    fun moveTo(
        x: Float,
        y: Float,
    ) {
        val transformedX = transform?.transformX(x, y) ?: x
        val transformedY = transform?.transformY(x, y) ?: y
        extend(transformedX, transformedY)
        sink.moveTo(transformedX, transformedY)
    }

    fun lineTo(
        x: Float,
        y: Float,
    ) {
        val transformedX = transform?.transformX(x, y) ?: x
        val transformedY = transform?.transformY(x, y) ?: y
        extend(transformedX, transformedY)
        sink.lineTo(transformedX, transformedY)
    }

    fun curveTo(
        control1X: Float,
        control1Y: Float,
        control2X: Float,
        control2Y: Float,
        x: Float,
        y: Float,
    ) {
        val transformedControl1X = transform?.transformX(control1X, control1Y) ?: control1X
        val transformedControl1Y = transform?.transformY(control1X, control1Y) ?: control1Y
        val transformedControl2X = transform?.transformX(control2X, control2Y) ?: control2X
        val transformedControl2Y = transform?.transformY(control2X, control2Y) ?: control2Y
        val transformedX = transform?.transformX(x, y) ?: x
        val transformedY = transform?.transformY(x, y) ?: y
        extend(transformedControl1X, transformedControl1Y)
        extend(transformedControl2X, transformedControl2Y)
        extend(transformedX, transformedY)
        sink.curveTo(
            transformedControl1X,
            transformedControl1Y,
            transformedControl2X,
            transformedControl2Y,
            transformedX,
            transformedY,
        )
    }

    fun close() = sink.close()

    fun bounds(): FontBounds? {
        if (!hasBounds) return null
        return FontBounds(
            xMin = xMin.toShortIntOrNull() ?: return null,
            yMin = yMin.toShortIntOrNull() ?: return null,
            xMax = xMax.toShortIntOrNull() ?: return null,
            yMax = yMax.toShortIntOrNull() ?: return null,
        )
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

private fun Float.toShortIntOrNull(): Int? {
    if (!isFinite()) return null
    return toInt().takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
}
