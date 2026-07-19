package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HmtxTableTest {
    @Test
    fun parsesSimpleMetrics() {
        val table = parse(numberOfMetrics = 1, numberOfGlyphs = 1, 1, 2)!!

        assertEquals(1, table.advance(0))
        assertEquals(2, table.sideBearing(0))
    }

    @Test
    fun rejectsEmptyData() {
        assertNull(HmtxTable.parse(1, 1, ByteSlice.of(ByteArray(0))))
    }

    @Test
    fun rejectsZeroMetrics() {
        assertNull(parse(numberOfMetrics = 0, numberOfGlyphs = 1, 1, 2))
    }

    @Test
    fun reusesLastAdvanceAndReadsTrailingBearing() {
        val table =
            HmtxTable.parse(
                numberOfMetrics = 1,
                numberOfGlyphs = 2,
                data =
                    ByteSlice.of(
                        TestByteBuilder()
                            .uint16(1)
                            .int16(2)
                            .int16(3)
                            .build(),
                    ),
            )!!

        assertEquals(1, table.advance(0))
        assertEquals(2, table.sideBearing(0))
        assertEquals(1, table.advance(1))
        assertEquals(3, table.sideBearing(1))
    }

    @Test
    fun missingTrailingBearingsDoNotRejectTable() {
        val table = parse(numberOfMetrics = 1, numberOfGlyphs = 2, 1, 2)!!

        assertEquals(1, table.advance(1))
        assertNull(table.sideBearing(1))
    }

    @Test
    fun metricsCountMayExceedGlyphCount() {
        val data =
            TestByteBuilder()
                .uint16(1)
                .int16(2)
                .uint16(3)
                .int16(4)
                .int16(5)
                .build()
        val table = HmtxTable.parse(2, 1, ByteSlice.of(data))!!

        assertEquals(2, table.sideBearing(0))
        assertEquals(4, table.sideBearing(1))
        assertNull(table.sideBearing(2))
    }

    @Test
    fun rejectsOutOfBoundsGlyphs() {
        val table = parse(numberOfMetrics = 1, numberOfGlyphs = 1, 1, 2)!!

        assertNull(table.advance(1))
        assertNull(table.sideBearing(1))
        assertNull(table.advance(-1))
    }

    @Test
    fun rejectsGlyphAfterTrailingBearings() {
        val data =
            TestByteBuilder()
                .uint16(1)
                .int16(2)
                .int16(3)
                .build()
        val table = HmtxTable.parse(1, 2, ByteSlice.of(data))!!

        assertEquals(1, table.advance(1))
        assertEquals(3, table.sideBearing(1))
        assertNull(table.advance(2))
        assertNull(table.sideBearing(2))
    }

    private fun parse(
        numberOfMetrics: Int,
        numberOfGlyphs: Int,
        advance: Int,
        bearing: Int,
    ): HmtxTable? =
        HmtxTable.parse(
            numberOfMetrics,
            numberOfGlyphs,
            ByteSlice.of(TestByteBuilder().uint16(advance).int16(bearing).build()),
        )
}
