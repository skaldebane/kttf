package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class GlyphPointDelta(
    internal val x: Float,
    internal val y: Float,
)

/** Deltas for caller-provided points and the four trailing TrueType phantom points. */
internal class GlyphVariationDeltas internal constructor(
    private val xDeltas: FloatArray,
    private val yDeltas: FloatArray,
    internal val pointCount: Int,
) {
    internal operator fun get(index: Int): GlyphPointDelta? {
        if (index !in 0 until pointCount) return null
        return GlyphPointDelta(xDeltas[index], yDeltas[index])
    }

    internal fun phantom(index: Int): GlyphPointDelta? {
        if (index !in 0 until PHANTOM_POINT_COUNT) return null
        val pointIndex = pointCount + index
        return GlyphPointDelta(xDeltas[pointIndex], yDeltas[pointIndex])
    }

    private companion object {
        private const val PHANTOM_POINT_COUNT: Int = 4
    }
}

internal class GvarTable private constructor(
    private val axisCount: Int,
    private val glyphCount: Int,
    private val sharedTuples: ByteSlice,
    private val offsets: LongArray,
    private val glyphVariationData: ByteSlice,
) {
    /**
     * Evaluates deltas for simple-glyph points. [contourEnds] contains inclusive point indices.
     * The result also retains the four phantom-point deltas used by later metric wiring.
     */
    internal fun glyphDeltas(
        glyphId: Int,
        coordinates: VariationCoordinates,
        xCoordinates: IntArray,
        yCoordinates: IntArray,
        contourEnds: IntArray,
    ): GlyphVariationDeltas? {
        if (xCoordinates.size != yCoordinates.size || xCoordinates.size > MAX_OUTLINE_POINTS) return null
        if (!validContours(contourEnds, xCoordinates.size)) return null
        if (xCoordinates.any { it !in Short.MIN_VALUE..Short.MAX_VALUE }) return null
        if (yCoordinates.any { it !in Short.MIN_VALUE..Short.MAX_VALUE }) return null
        if (coordinates.size != axisCount || glyphId !in 0 until glyphCount) return null

        val totalPointCount = xCoordinates.size + PHANTOM_POINT_COUNT
        val xDeltas = FloatArray(totalPointCount)
        val yDeltas = FloatArray(totalPointCount)
        val range = glyphDataRange(glyphId) ?: return null
        if (range.first == range.second) return GlyphVariationDeltas(xDeltas, yDeltas, xCoordinates.size)
        val glyphData = glyphVariationData.slice(range.first, range.second - range.first) ?: return null
        if (!applyGlyphData(glyphData, coordinates, xCoordinates, yCoordinates, contourEnds, xDeltas, yDeltas)) {
            return null
        }
        return GlyphVariationDeltas(xDeltas, yDeltas, xCoordinates.size)
    }

    /** Evaluates component translation deltas for a composite glyph. */
    internal fun componentDeltas(
        glyphId: Int,
        coordinates: VariationCoordinates,
        componentCount: Int,
    ): GlyphVariationDeltas? {
        if (componentCount !in 0..MAX_OUTLINE_POINTS) return null
        return glyphDeltas(
            glyphId,
            coordinates,
            IntArray(componentCount),
            IntArray(componentCount),
            IntArray(0),
        )
    }

    private fun glyphDataRange(glyphId: Int): Pair<Int, Int>? {
        val start = offsets[glyphId]
        val end = offsets[glyphId + 1]
        if (start > end || end > glyphVariationData.size || start > Int.MAX_VALUE) return null
        return start.toInt() to end.toInt()
    }

    private fun applyGlyphData(
        data: ByteSlice,
        coordinates: VariationCoordinates,
        xCoordinates: IntArray,
        yCoordinates: IntArray,
        contourEnds: IntArray,
        xResult: FloatArray,
        yResult: FloatArray,
    ): Boolean {
        val rawTupleCount = data.readUInt16(0) ?: return false
        val tupleCount = rawTupleCount and TUPLE_COUNT_MASK
        if (tupleCount == 0) return false
        val dataOffset = data.readUInt16(2) ?: return false
        if (!data.contains(dataOffset, 0)) return false

        var serializedOffset = dataOffset
        val sharedPoints =
            if (rawTupleCount and SHARED_POINT_NUMBERS_FLAG != 0) {
                val decoded = decodePackedPoints(data, serializedOffset) ?: return false
                serializedOffset = decoded.nextOffset
                decoded.points
            } else {
                null
            }

        var headerOffset = 4
        var tupleIndex = 0
        while (tupleIndex < tupleCount) {
            val header = parseTupleHeader(data, headerOffset, coordinates) ?: return false
            headerOffset = header.nextOffset
            if (header.scalar > 0.0f) {
                val chunk = data.slice(serializedOffset, header.serializedDataSize) ?: return false
                var chunkOffset = 0
                val points =
                    if (header.privatePoints) {
                        val decoded = decodePackedPoints(chunk, 0) ?: return false
                        chunkOffset = decoded.nextOffset
                        decoded.points
                    } else {
                        sharedPoints
                    }
                val deltaCount = points?.size ?: xResult.size
                if (deltaCount > 0xFFFF) return false
                val deltaData = chunk.sliceFrom(chunkOffset) ?: return false
                val deltas = decodePackedDeltas(deltaData, deltaCount) ?: return false
                applyTuple(
                    points,
                    deltas,
                    xCoordinates,
                    yCoordinates,
                    contourEnds,
                    xResult,
                    yResult,
                    header.scalar,
                )
            }
            serializedOffset += header.serializedDataSize
            tupleIndex++
        }
        return true
    }

    private fun parseTupleHeader(
        data: ByteSlice,
        startOffset: Int,
        coordinates: VariationCoordinates,
    ): TupleHeader? {
        val serializedDataSize = data.readUInt16(startOffset) ?: return null
        val rawTupleIndex = data.readUInt16(startOffset + 2) ?: return null
        var offset = startOffset + 4
        val peak = IntArray(axisCount)
        if (rawTupleIndex and EMBEDDED_PEAK_TUPLE_FLAG != 0) {
            var axis = 0
            while (axis < axisCount) {
                peak[axis] = data.readInt16(offset) ?: return null
                offset += 2
                axis++
            }
        } else {
            val sharedIndex = rawTupleIndex and TUPLE_INDEX_MASK
            val sharedStart = sharedIndex.toLong() * axisCount * 2L
            if (sharedStart > Int.MAX_VALUE) return null
            var axis = 0
            while (axis < axisCount) {
                peak[axis] = sharedTuples.readInt16(sharedStart.toInt() + axis * 2) ?: return null
                axis++
            }
        }

        val intermediate = rawTupleIndex and INTERMEDIATE_REGION_FLAG != 0
        val regionStart = if (intermediate) IntArray(axisCount) else null
        val regionEnd = if (intermediate) IntArray(axisCount) else null
        if (intermediate) {
            var axis = 0
            while (axis < axisCount) {
                regionStart!![axis] = data.readInt16(offset) ?: return null
                offset += 2
                axis++
            }
            axis = 0
            while (axis < axisCount) {
                regionEnd!![axis] = data.readInt16(offset) ?: return null
                offset += 2
                axis++
            }
        }

        var scalar = 1.0f
        var axis = 0
        while (axis < axisCount) {
            val coordinate = coordinates[axis]?.rawValue ?: return null
            val peakValue = peak[axis]
            if (peakValue != 0 && coordinate != peakValue) {
                if (intermediate) {
                    val start = regionStart!![axis]
                    val end = regionEnd!![axis]
                    if (!(start > peakValue || peakValue > end || (start < 0 && end > 0 && peakValue != 0))) {
                        if (coordinate < start || coordinate > end) {
                            scalar = 0.0f
                            break
                        }
                        if (coordinate < peakValue && peakValue != start) {
                            scalar *= (coordinate - start).toFloat() / (peakValue - start)
                        } else if (coordinate >= peakValue && peakValue != end) {
                            scalar *= (end - coordinate).toFloat() / (end - peakValue)
                        }
                    }
                } else if (
                    coordinate == 0 ||
                    coordinate < minOf(0, peakValue) ||
                    coordinate > maxOf(0, peakValue)
                ) {
                    scalar = 0.0f
                    break
                } else {
                    scalar *= coordinate.toFloat() / peakValue
                }
            }
            axis++
        }

        return TupleHeader(
            scalar,
            rawTupleIndex and PRIVATE_POINT_NUMBERS_FLAG != 0,
            serializedDataSize,
            offset,
        )
    }

    internal companion object {
        private const val VERSION_1_0: Long = 0x0001_0000L
        private const val HEADER_SIZE: Int = 20
        private const val PHANTOM_POINT_COUNT: Int = 4
        private const val MAX_OUTLINE_POINTS: Int = 0xFFFF - PHANTOM_POINT_COUNT

        private const val SHARED_POINT_NUMBERS_FLAG: Int = 0x8000
        private const val TUPLE_COUNT_MASK: Int = 0x0FFF
        private const val EMBEDDED_PEAK_TUPLE_FLAG: Int = 0x8000
        private const val INTERMEDIATE_REGION_FLAG: Int = 0x4000
        private const val PRIVATE_POINT_NUMBERS_FLAG: Int = 0x2000
        private const val TUPLE_INDEX_MASK: Int = 0x0FFF

        internal fun parse(data: ByteSlice): GvarTable? {
            if (data.readUInt32(0) != VERSION_1_0) return null
            val axisCount = data.readUInt16(4) ?: return null
            if (axisCount == 0) return null
            val sharedTupleCount = data.readUInt16(6) ?: return null
            val sharedTupleOffset = data.readUInt32(8)?.checkedIntOffset(data) ?: return null
            val glyphCount = data.readUInt16(12) ?: return null
            if (glyphCount == 0xFFFF) return null
            val longOffsets = (data.readUInt16(14) ?: return null) and 1 != 0
            val glyphDataOffset = data.readUInt32(16)?.checkedIntOffset(data) ?: return null

            val sharedSize = sharedTupleCount.toLong() * axisCount * 2L
            if (sharedTupleCount.toLong() * axisCount > 0xFFFFL) return null
            if (sharedSize > Int.MAX_VALUE) return null
            val sharedTuples = data.slice(sharedTupleOffset, sharedSize.toInt()) ?: return null
            val offsetCount = glyphCount + 1
            val offsetSize = if (longOffsets) 4 else 2
            val offsetBytes = offsetCount.toLong() * offsetSize
            if (offsetBytes > Int.MAX_VALUE || !data.contains(HEADER_SIZE, offsetBytes.toInt())) return null
            val offsets = LongArray(offsetCount)
            var index = 0
            while (index < offsetCount) {
                offsets[index] =
                    if (longOffsets) {
                        data.readUInt32(HEADER_SIZE + index * 4) ?: return null
                    } else {
                        (data.readUInt16(HEADER_SIZE + index * 2) ?: return null) * 2L
                    }
                index++
            }
            val glyphVariationData = data.sliceFrom(glyphDataOffset) ?: return null
            return GvarTable(axisCount, glyphCount, sharedTuples, offsets, glyphVariationData)
        }

        private fun validContours(
            contourEnds: IntArray,
            pointCount: Int,
        ): Boolean {
            var previous = -1
            for (end in contourEnds) {
                if (end <= previous || end !in 0 until pointCount) return false
                previous = end
            }
            return true
        }

        private fun decodePackedPoints(
            data: ByteSlice,
            offset: Int,
        ): PackedPoints? {
            val first = data.readUInt8(offset) ?: return null
            var cursor = offset + 1
            val count =
                if (first and 0x80 != 0) {
                    val second = data.readUInt8(cursor) ?: return null
                    cursor++
                    ((first and 0x7F) shl 8) or second
                } else {
                    first
                }
            if (count == 0) return PackedPoints(null, cursor)

            val points = IntArray(count)
            var pointIndex = 0
            var absolutePoint = 0
            while (pointIndex < count) {
                val control = data.readUInt8(cursor++) ?: return null
                val runCount = (control and 0x7F) + 1
                if (runCount > count - pointIndex) return null
                val words = control and 0x80 != 0
                var runIndex = 0
                while (runIndex < runCount) {
                    val increment =
                        if (words) {
                            val value = data.readUInt16(cursor) ?: return null
                            cursor += 2
                            value
                        } else {
                            data.readUInt8(cursor++) ?: return null
                        }
                    if (increment == 0 && pointIndex != 0) {
                        // Pinned upstream treats repeated point numbers as the following point.
                        absolutePoint++
                    } else {
                        absolutePoint += increment
                    }
                    points[pointIndex++] = absolutePoint
                    runIndex++
                }
            }
            return PackedPoints(points, cursor)
        }

        private fun decodePackedDeltas(
            data: ByteSlice,
            count: Int,
        ): PackedDeltas? {
            val decoder = PackedDeltaDecoder(data)
            val x = FloatArray(count)
            val y = FloatArray(count)
            var index = 0
            while (index < count) {
                x[index] = decoder.next() ?: return null
                index++
            }
            index = 0
            while (index < count) {
                y[index] = decoder.next() ?: return null
                index++
            }
            return PackedDeltas(x, y)
        }

        private fun applyTuple(
            points: IntArray?,
            deltas: PackedDeltas,
            xCoordinates: IntArray,
            yCoordinates: IntArray,
            contourEnds: IntArray,
            xResult: FloatArray,
            yResult: FloatArray,
            scalar: Float,
        ) {
            if (points == null) {
                var index = 0
                while (index < xResult.size) {
                    xResult[index] += deltas.x[index] * scalar
                    yResult[index] += deltas.y[index] * scalar
                    index++
                }
                return
            }

            val touched = BooleanArray(xResult.size)
            val tupleX = FloatArray(xResult.size)
            val tupleY = FloatArray(yResult.size)
            var index = 0
            while (index < points.size) {
                val point = points[index]
                if (point in touched.indices) {
                    touched[point] = true
                    tupleX[point] += deltas.x[index] * scalar
                    tupleY[point] += deltas.y[index] * scalar
                }
                index++
            }

            var contourStart = 0
            for (contourEnd in contourEnds) {
                interpolateContour(touched, tupleX, xCoordinates, contourStart, contourEnd)
                interpolateContour(touched, tupleY, yCoordinates, contourStart, contourEnd)
                contourStart = contourEnd + 1
            }
            index = 0
            while (index < xResult.size) {
                xResult[index] += tupleX[index]
                yResult[index] += tupleY[index]
                index++
            }
        }

        private fun interpolateContour(
            touched: BooleanArray,
            deltas: FloatArray,
            coordinates: IntArray,
            start: Int,
            end: Int,
        ) {
            val touchedPoints = ArrayList<Int>()
            var index = start
            while (index <= end) {
                if (touched[index]) touchedPoints.add(index)
                index++
            }
            if (touchedPoints.isEmpty()) return
            if (touchedPoints.size == 1) {
                val delta = deltas[touchedPoints[0]]
                index = start
                while (index <= end) {
                    deltas[index] = delta
                    index++
                }
                return
            }

            var touchedIndex = 0
            while (touchedIndex < touchedPoints.size) {
                val previous = touchedPoints[touchedIndex]
                val next = touchedPoints[(touchedIndex + 1) % touchedPoints.size]
                index = if (previous == end) start else previous + 1
                while (index != next) {
                    deltas[index] =
                        inferDelta(
                            coordinates[previous],
                            coordinates[index],
                            coordinates[next],
                            deltas[previous],
                            deltas[next],
                        )
                    index = if (index == end) start else index + 1
                }
                touchedIndex++
            }
        }

        private fun inferDelta(
            previousPoint: Int,
            targetPoint: Int,
            nextPoint: Int,
            previousDelta: Float,
            nextDelta: Float,
        ): Float =
            when {
                previousPoint == nextPoint -> {
                    if (previousDelta == nextDelta) previousDelta else 0.0f
                }

                targetPoint <= minOf(previousPoint, nextPoint) -> {
                    if (previousPoint < nextPoint) previousDelta else nextDelta
                }

                targetPoint >= maxOf(previousPoint, nextPoint) -> {
                    if (previousPoint > nextPoint) previousDelta else nextDelta
                }

                else -> {
                    val targetDifference = targetPoint - previousPoint
                    val nextDifference = nextPoint - previousPoint
                    if (targetDifference !in Short.MIN_VALUE..Short.MAX_VALUE ||
                        nextDifference !in Short.MIN_VALUE..Short.MAX_VALUE
                    ) {
                        0.0f
                    } else {
                        val ratio = targetDifference.toFloat() / nextDifference
                        (1.0f - ratio) * previousDelta + ratio * nextDelta
                    }
                }
            }
    }
}

private data class TupleHeader(
    val scalar: Float,
    val privatePoints: Boolean,
    val serializedDataSize: Int,
    val nextOffset: Int,
)

private data class PackedPoints(
    val points: IntArray?,
    val nextOffset: Int,
)

private data class PackedDeltas(
    val x: FloatArray,
    val y: FloatArray,
)

private class PackedDeltaDecoder(
    private val data: ByteSlice,
) {
    private var offset = 0
    private var runLeft = 0
    private var runType = 0

    fun next(): Float? {
        if (runLeft == 0) {
            val control = data.readUInt8(offset++) ?: return null
            runLeft = (control and 0x3F) + 1
            runType =
                when {
                    control and 0x80 != 0 -> 0
                    control and 0x40 != 0 -> 2
                    else -> 1
                }
        }
        runLeft--
        return when (runType) {
            0 -> {
                0.0f
            }

            1 -> {
                data.readInt8(offset++)?.toFloat()
            }

            else -> {
                val value = data.readInt16(offset)?.toFloat() ?: return null
                offset += 2
                value
            }
        }
    }
}
