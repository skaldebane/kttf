package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvarTableTest {
    @Test
    fun parsesSegmentMapsWithBoundedIndexedAndIterableAccess() {
        val table =
            assertNotNull(
                AvarTable.parse(
                    ByteSlice.of(
                        avar(
                            listOf(
                                listOf(-16384 to -16384, 0 to 0, 16384 to 8192),
                                emptyList(),
                            ),
                        ),
                    ),
                ),
            )

        assertEquals(2, table.segmentMaps.size)
        val first = assertNotNull(table.segmentMaps[0])
        assertEquals(3, first.size)
        assertEquals(-16384, first[0]?.fromCoordinate)
        assertEquals(8192, first[2]?.toCoordinate)
        assertEquals(0, table.segmentMaps[1]?.size)
        assertNull(first[-1])
        assertNull(first[3])
        assertNull(table.segmentMaps[-1])
        assertNull(table.segmentMaps[2])
        assertEquals(listOf(3, 0), table.segmentMaps.map(AvarSegmentMap::size))
    }

    @Test
    fun mapsCoordinatesWithInterpolationAndIdentityMaps() {
        val table =
            AvarTable.parse(
                ByteSlice.of(
                    avar(
                        listOf(
                            listOf(-16384 to -16384, 0 to 0, 16384 to 8192),
                            emptyList(),
                        ),
                    ),
                ),
            )!!
        val coordinates =
            VariationCoordinates.copyOf(
                listOf(
                    NormalizedCoordinate.fromF2Dot14(8192),
                    NormalizedCoordinate.fromF2Dot14(-500),
                ),
            )

        assertTrue(table.mapAll(coordinates))
        assertEquals(4096, coordinates[0]?.rawValue)
        assertEquals(-500, coordinates[1]?.rawValue)
    }

    @Test
    fun matchesUpstreamRoundingAndEndpointExtrapolation() {
        val rounded = segmentMap(listOf(0 to 0, 3 to 2, 16384 to 16384))
        val below = segmentMap(listOf(-100 to -200, 100 to 200))
        val single = segmentMap(listOf(100 to 200))

        assertEquals(1, rounded.map(NormalizedCoordinate.fromF2Dot14(1))?.rawValue)
        assertEquals(-250, below.map(NormalizedCoordinate.fromF2Dot14(-150))?.rawValue)
        assertEquals(250, below.map(NormalizedCoordinate.fromF2Dot14(150))?.rawValue)
        assertEquals(150, single.map(NormalizedCoordinate.fromF2Dot14(50))?.rawValue)
        assertEquals(16384, segmentMap(listOf(0 to 20000)).map(NormalizedCoordinate.ZERO)?.rawValue)
    }

    @Test
    fun coordinateMappingValidatesShapeAndKeepsUpstreamOutOfRangeNoOp() {
        val table = AvarTable.parse(ByteSlice.of(avar(listOf(emptyList()))))!!
        val one = VariationCoordinates.zeros(1)!!
        val two = VariationCoordinates.zeros(2)!!

        assertFalse(table.mapCoordinate(two, 0))
        assertTrue(table.mapCoordinate(one, -1))
        assertTrue(table.mapCoordinate(one, 1))
        assertEquals(0, one[0]?.rawValue)
    }

    @Test
    fun segmentMapsRemainZeroCopyAfterValidation() {
        val bytes = avar(listOf(listOf(-16384 to -16384, 0 to 0, 16384 to 8192)))
        val table = AvarTable.parse(ByteSlice.of(bytes))!!

        putInt16(bytes, 8 + 2 + 2 * 4 + 2, 4096)

        assertEquals(4096, table.segmentMaps[0]?.get(2)?.toCoordinate)
        assertEquals(2048, table.segmentMaps[0]?.map(NormalizedCoordinate.fromF2Dot14(8192))?.rawValue)
    }

    @Test
    fun validatesVersionHeaderCountsAndCompleteMapRanges() {
        val valid = avar(listOf(listOf(-16384 to -16384, 0 to 0, 16384 to 16384)))
        assertNotNull(AvarTable.parse(ByteSlice.of(valid)))
        assertNotNull(AvarTable.parse(ByteSlice.of(valid + byteArrayOf(99))))
        assertNull(AvarTable.parse(ByteSlice.of(valid.copyOf(7))))

        val wrongVersion = valid.copyOf().also { putUInt32(it, 0, 0x0002_0000) }
        val missingMapCount = valid.copyOf(8).also { putUInt16(it, 6, 1) }
        val missingMapRecord = valid.copyOf(valid.size - 1)
        val extraAxis = valid.copyOf().also { putUInt16(it, 6, 2) }

        assertNull(AvarTable.parse(ByteSlice.of(wrongVersion)))
        assertNull(AvarTable.parse(ByteSlice.of(missingMapCount)))
        assertNull(AvarTable.parse(ByteSlice.of(missingMapRecord)))
        assertNull(AvarTable.parse(ByteSlice.of(extraAxis)))
    }

    private fun segmentMap(values: List<Pair<Int, Int>>): AvarSegmentMap {
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            putInt16(bytes, index * 4, value.first)
            putInt16(bytes, index * 4 + 2, value.second)
        }
        return AvarSegmentMap(ByteSlice.of(bytes), values.size)
    }

    private fun avar(maps: List<List<Pair<Int, Int>>>): ByteArray {
        val size = 8 + maps.sumOf { 2 + it.size * 4 }
        val bytes = ByteArray(size)
        putUInt32(bytes, 0, 0x0001_0000)
        putUInt16(bytes, 6, maps.size)
        var offset = 8
        maps.forEach { map ->
            putUInt16(bytes, offset, map.size)
            offset += 2
            map.forEach { value ->
                putInt16(bytes, offset, value.first)
                putInt16(bytes, offset + 2, value.second)
                offset += 4
            }
        }
        return bytes
    }

    private fun putInt16(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) = putUInt16(data, offset, value)

    private fun putUInt16(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun putUInt32(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
