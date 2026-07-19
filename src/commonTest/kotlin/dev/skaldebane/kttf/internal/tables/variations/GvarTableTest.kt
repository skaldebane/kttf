package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GvarTableTest {
    @Test
    fun appliesEmbeddedTupleScalarsToPointsComponentsAndPhantoms() {
        val serialized = packedDeltas(10, 20, 0, 0, 30, 40, 1, 2, 0, 0, 3, 4)
        val tuple =
            glyphVariationData(
                rawTupleCount = 1,
                header =
                    VariationBytes()
                        .u16(serialized.size)
                        .u16(0x8000)
                        .i16(16384)
                        .build(),
                serialized = serialized,
            )
        val table = GvarTable.parse(ByteSlice.of(gvar(tuple)))!!

        val deltas =
            assertNotNull(
                table.glyphDeltas(
                    glyphId = 0,
                    coordinates = coordinates(8192),
                    xCoordinates = intArrayOf(0, 100),
                    yCoordinates = intArrayOf(0, 100),
                    contourEnds = intArrayOf(1),
                ),
            )
        assertEquals(GlyphPointDelta(5.0f, 0.5f), deltas[0])
        assertEquals(GlyphPointDelta(10.0f, 1.0f), deltas[1])
        assertEquals(GlyphPointDelta(15.0f, 1.5f), deltas.phantom(2))
        assertEquals(GlyphPointDelta(20.0f, 2.0f), deltas.phantom(3))
        assertNull(deltas[-1])
        assertNull(deltas[2])
        assertNull(deltas.phantom(4))

        val components = table.componentDeltas(0, coordinates(16384), 2)!!
        assertEquals(GlyphPointDelta(10.0f, 1.0f), components[0])
        assertEquals(GlyphPointDelta(20.0f, 2.0f), components[1])
    }

    @Test
    fun decodesSharedPointsAndPerformsContourLocalIup() {
        val sharedPoints = packedPoints(0, 2)
        val serialized = packedDeltas(0, 20, 10, 30)
        val tuple =
            glyphVariationData(
                rawTupleCount = 0x8001,
                header = VariationBytes().u16(serialized.size).u16(0).build(),
                sharedPoints = sharedPoints,
                serialized = serialized,
            )
        val table = GvarTable.parse(ByteSlice.of(gvar(tuple, sharedPeak = 16384)))!!
        val deltas =
            table.glyphDeltas(
                0,
                coordinates(16384),
                intArrayOf(0, 50, 100),
                intArrayOf(0, 50, 100),
                intArrayOf(2),
            )!!

        assertEquals(GlyphPointDelta(0.0f, 10.0f), deltas[0])
        assertEquals(GlyphPointDelta(10.0f, 20.0f), deltas[1])
        assertEquals(GlyphPointDelta(20.0f, 30.0f), deltas[2])
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas.phantom(0))
    }

    @Test
    fun decodesPrivatePointsAndPropagatesSingleTouchedPointWithinOnlyItsContour() {
        val privatePoints = packedPoints(1)
        val serialized = privatePoints + packedDeltas(12, -8)
        val tuple =
            glyphVariationData(
                rawTupleCount = 1,
                header =
                    VariationBytes()
                        .u16(serialized.size)
                        .u16(0xA000)
                        .i16(16384)
                        .build(),
                serialized = serialized,
            )
        val table = GvarTable.parse(ByteSlice.of(gvar(tuple)))!!
        val deltas =
            table.glyphDeltas(
                0,
                coordinates(16384),
                intArrayOf(0, 10, 20, 100, 110),
                intArrayOf(0, 10, 20, 100, 110),
                intArrayOf(2, 4),
            )!!

        assertEquals(GlyphPointDelta(12.0f, -8.0f), deltas[0])
        assertEquals(GlyphPointDelta(12.0f, -8.0f), deltas[1])
        assertEquals(GlyphPointDelta(12.0f, -8.0f), deltas[2])
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas[3])
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas[4])
    }

    @Test
    fun appliesIntermediateRegionScalarAndPackedRunsAcrossAxisBoundary() {
        val serialized = packedDeltas(8, 0, 0, 0, 0, 4, 0, 0, 0, 0)
        val header =
            VariationBytes()
                .u16(serialized.size)
                .u16(0xC000)
                .i16(8192)
                .i16(0)
                .i16(16384)
                .build()
        val table =
            GvarTable.parse(
                ByteSlice.of(gvar(glyphVariationData(1, header, serialized = serialized))),
            )!!

        val deltas = table.glyphDeltas(0, coordinates(4096), intArrayOf(0), intArrayOf(0), intArrayOf(0))!!
        assertEquals(GlyphPointDelta(4.0f, 2.0f), deltas[0])
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas.phantom(3))
    }

    @Test
    fun decodesWordAndZeroDeltaRunsAcrossTheAxisBoundary() {
        val serialized =
            VariationBytes()
                .u8(0x41)
                .i16(300)
                .i16(-300)
                .u8(0x83)
                .u8(0)
                .u8(5)
                .u8(0x84)
                .build()
        val header =
            VariationBytes()
                .u16(serialized.size)
                .u16(0x8000)
                .i16(16384)
                .build()
        val table = GvarTable.parse(ByteSlice.of(gvar(glyphVariationData(1, header, serialized = serialized))))!!

        val deltas = table.glyphDeltas(0, coordinates(16384), intArrayOf(0, 10), intArrayOf(0, 10), intArrayOf(1))!!
        assertEquals(GlyphPointDelta(300.0f, 5.0f), deltas[0])
        assertEquals(GlyphPointDelta(-300.0f, 0.0f), deltas[1])
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas.phantom(3))
    }

    @Test
    fun skipsOutOfRangeTupleDataWithoutParsingItLikePinnedUpstream() {
        val glyphData =
            glyphVariationData(
                1,
                VariationBytes()
                    .u16(100)
                    .u16(0x8000)
                    .i16(-16384)
                    .build(),
                serialized = byteArrayOf(),
            )
        val table = GvarTable.parse(ByteSlice.of(gvar(glyphData)))!!

        val deltas = table.glyphDeltas(0, coordinates(16384), intArrayOf(0), intArrayOf(0), intArrayOf(0))!!
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas[0])
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas.phantom(0))
    }

    @Test
    fun appliesPrivatePhantomPointsWithoutRunningIupAcrossThem() {
        val privatePoints = packedPoints(1)
        val serialized = privatePoints + packedDeltas(8, -9)
        val header =
            VariationBytes()
                .u16(serialized.size)
                .u16(0xA000)
                .i16(16384)
                .build()
        val table = GvarTable.parse(ByteSlice.of(gvar(glyphVariationData(1, header, serialized = serialized))))!!

        val deltas = table.glyphDeltas(0, coordinates(16384), intArrayOf(25), intArrayOf(30), intArrayOf(0))!!
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas[0])
        assertEquals(GlyphPointDelta(8.0f, -9.0f), deltas.phantom(0))
        assertEquals(GlyphPointDelta(0.0f, 0.0f), deltas.phantom(1))
    }

    @Test
    fun followsPinnedRepeatedPointAndIupEqualCoordinateBehavior() {
        val repeatedPoints = packedPoints(0, 0)
        val repeatedSerialized = repeatedPoints + packedDeltas(5, 7, 0, 0)
        val repeatedHeader =
            VariationBytes()
                .u16(repeatedSerialized.size)
                .u16(0xA000)
                .i16(16384)
                .build()
        val repeatedTable =
            GvarTable.parse(
                ByteSlice.of(gvar(glyphVariationData(1, repeatedHeader, serialized = repeatedSerialized))),
            )!!
        val repeated =
            repeatedTable.glyphDeltas(
                0,
                coordinates(16384),
                intArrayOf(0, 10),
                intArrayOf(0, 10),
                intArrayOf(1),
            )!!
        assertEquals(GlyphPointDelta(5.0f, 0.0f), repeated[0])
        assertEquals(GlyphPointDelta(7.0f, 0.0f), repeated[1])

        val touchedPoints = packedPoints(0, 2)
        val iupSerialized = touchedPoints + packedDeltas(10, 20, 0, 0)
        val iupHeader =
            VariationBytes()
                .u16(iupSerialized.size)
                .u16(0xA000)
                .i16(16384)
                .build()
        val iupTable =
            GvarTable.parse(
                ByteSlice.of(gvar(glyphVariationData(1, iupHeader, serialized = iupSerialized))),
            )!!
        val inferred =
            iupTable.glyphDeltas(
                0,
                coordinates(16384),
                intArrayOf(0, 0, 0),
                intArrayOf(0, 5, 10),
                intArrayOf(2),
            )!!
        assertEquals(GlyphPointDelta(0.0f, 0.0f), inferred[1])
    }

    @Test
    fun validatesTableRangesGeometryCoordinateShapeAndPackedData() {
        val serialized = packedDeltas(1, 0, 0, 0, 0, 1, 0, 0, 0, 0)
        val tuple =
            glyphVariationData(
                1,
                VariationBytes()
                    .u16(serialized.size)
                    .u16(0x8000)
                    .i16(16384)
                    .build(),
                serialized = serialized,
            )
        val valid = gvar(tuple)
        val table = GvarTable.parse(ByteSlice.of(valid))!!

        assertNull(table.glyphDeltas(1, coordinates(1), intArrayOf(), intArrayOf(), intArrayOf()))
        assertNull(table.glyphDeltas(0, coordinates(1, 2), intArrayOf(), intArrayOf(), intArrayOf()))
        assertNull(table.glyphDeltas(0, coordinates(1), intArrayOf(0), intArrayOf(), intArrayOf()))
        assertNull(table.glyphDeltas(0, coordinates(1), intArrayOf(0), intArrayOf(0), intArrayOf(1)))
        assertNull(table.glyphDeltas(0, coordinates(1), intArrayOf(40000), intArrayOf(0), intArrayOf(0)))

        assertNull(GvarTable.parse(ByteSlice.of(valid.copyOf(19))))
        assertNull(GvarTable.parse(ByteSlice.of(valid.copyOf().also { putUInt16(it, 4, 0) })))
        val reversedOffsets = valid.copyOf().also { putUInt16(it, 20, 10) }
        assertNull(
            GvarTable
                .parse(ByteSlice.of(reversedOffsets))
                ?.glyphDeltas(0, coordinates(1), intArrayOf(), intArrayOf(), intArrayOf()),
        )

        val malformedPoints = byteArrayOf(1, 1, 0, 0, 0)
        val malformedTuple =
            glyphVariationData(
                1,
                VariationBytes()
                    .u16(malformedPoints.size)
                    .u16(0xA000)
                    .i16(16384)
                    .build(),
                serialized = malformedPoints,
            )
        assertNull(
            GvarTable
                .parse(ByteSlice.of(gvar(malformedTuple)))
                ?.glyphDeltas(0, coordinates(16384), intArrayOf(), intArrayOf(), intArrayOf()),
        )
    }

    private fun gvar(
        glyphData: ByteArray,
        sharedPeak: Int? = null,
    ): ByteArray {
        val paddedGlyphData =
            if (glyphData.size % 2 == 0) {
                glyphData
            } else {
                glyphData + byteArrayOf(0)
            }
        val sharedTuple = sharedPeak?.let { VariationBytes().i16(it).build() } ?: byteArrayOf()
        val glyphDataOffset = 24 + sharedTuple.size
        return VariationBytes()
            .u32(0x0001_0000)
            .u16(1)
            .u16(if (sharedPeak == null) 0 else 1)
            .u32(24)
            .u16(1)
            .u16(0)
            .u32(glyphDataOffset.toLong())
            .u16(0)
            .u16(paddedGlyphData.size / 2)
            .bytes(sharedTuple)
            .bytes(paddedGlyphData)
            .build()
    }

    private fun glyphVariationData(
        rawTupleCount: Int,
        header: ByteArray,
        sharedPoints: ByteArray = byteArrayOf(),
        serialized: ByteArray,
    ): ByteArray =
        VariationBytes()
            .u16(rawTupleCount)
            .u16(4 + header.size)
            .bytes(header)
            .bytes(sharedPoints)
            .bytes(serialized)
            .build()

    private fun packedPoints(vararg absolutePoints: Int): ByteArray {
        val output = VariationBytes().u8(absolutePoints.size)
        if (absolutePoints.isEmpty()) return output.build()
        output.u8(absolutePoints.size - 1)
        var previous = 0
        absolutePoints.forEach { point ->
            output.u8(point - previous)
            previous = point
        }
        return output.build()
    }

    private fun packedDeltas(vararg values: Int): ByteArray {
        require(values.isNotEmpty() && values.size <= 64)
        val output = VariationBytes().u8(values.size - 1)
        values.forEach(output::u8)
        return output.build()
    }
}
