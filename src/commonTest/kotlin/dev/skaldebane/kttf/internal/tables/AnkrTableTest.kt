package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnkrTableTest {
    @Test
    fun readsUpstreamTwoGlyphFixture() {
        val data =
            TestByteBuilder()
                .uint16(0)
                .uint16(0)
                .uint32(12)
                .uint32(32)
                .uint16(6)
                .uint16(4)
                .uint16(2)
                .zeroes(6)
                .uint16(0)
                .uint16(0)
                .uint16(1)
                .uint16(8)
                .uint32(1)
                .int16(-5)
                .int16(11)
                .uint32(1)
                .int16(40)
                .int16(10)
                .build()
        val table = parse(data, 2)

        assertEquals(AnkrPoint(-5, 11), table.points(0)?.get(0))
        assertEquals(AnkrPoint(40, 10), table.points(1)?.get(0))
        assertNull(table.points(2))
    }

    @Test
    fun pointViewsAreBoundedAndRetainTheSource() {
        val data =
            TestByteBuilder()
                .uint16(0)
                .uint16(0)
                .uint32(12)
                .uint32(28)
                .uint16(6)
                .uint16(4)
                .uint16(1)
                .zeroes(6)
                .uint16(0)
                .uint16(0)
                .uint32(1)
                .int16(3)
                .int16(4)
                .build()
        val source = ByteArray(data.size + 4)
        data.copyInto(source, 2)
        val table = AnkrTable.parse(1, ByteSlice.of(source).slice(2, data.size)!!)!!
        val points = table.points(0)!!
        source[2 + 28 + 7] = 9

        assertEquals(AnkrPoint(3, 9), points[0])
        assertNull(points[-1])
        assertNull(points[1])
        assertNull(AnkrTable.parse(1, ByteSlice.of(data).slice(0, data.size - 1)!!)?.points(0))
    }

    private fun parse(
        data: ByteArray,
        glyphCount: Int,
    ): AnkrTable = requireNotNull(AnkrTable.parse(glyphCount, ByteSlice.of(data)))
}
