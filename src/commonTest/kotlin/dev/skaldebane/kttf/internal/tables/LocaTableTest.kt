package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocaTableTest {
    @Test
    fun parsesShortAndLongOffsets() {
        val short =
            LocaTable.parse(
                2,
                false,
                ByteSlice.of(
                    TestByteBuilder()
                        .uint16(0)
                        .uint16(10)
                        .uint16(15)
                        .build(),
                ),
            )!!
        val long =
            LocaTable.parse(
                2,
                true,
                ByteSlice.of(
                    TestByteBuilder()
                        .uint32(0)
                        .uint32(20)
                        .uint32(30)
                        .build(),
                ),
            )!!

        assertEquals(0L until 20L, short.glyphRange(0))
        assertEquals(20L until 30L, short.glyphRange(1))
        assertEquals(0L until 20L, long.glyphRange(0))
        assertEquals(20L until 30L, long.glyphRange(1))
    }

    @Test
    fun acceptsPartialLocaAndIgnoresExtraOffsets() {
        val partial = LocaTable.parse(3, false, ByteSlice.of(TestByteBuilder().uint16(0).uint16(4).build()))!!
        val extra =
            LocaTable.parse(
                1,
                false,
                ByteSlice.of(
                    TestByteBuilder()
                        .uint16(0)
                        .uint16(4)
                        .uint16(8)
                        .build(),
                ),
            )!!

        assertEquals(2, partial.size)
        assertEquals(0L until 8L, partial.glyphRange(0))
        assertNull(partial.glyphRange(1))
        assertEquals(2, extra.size)
        assertEquals(0L until 8L, extra.glyphRange(0))
    }

    @Test
    fun rejectsEmptyDescendingAndTerminalGlyphRanges() {
        val table =
            LocaTable.parse(
                3,
                true,
                ByteSlice.of(
                    TestByteBuilder()
                        .uint32(10)
                        .uint32(10)
                        .uint32(5)
                        .uint32(20)
                        .build(),
                ),
            )!!

        assertNull(table.glyphRange(0))
        assertNull(table.glyphRange(1))
        assertEquals(5L until 20L, table.glyphRange(2))
        assertNull(table.glyphRange(0xFFFF))
    }

    @Test
    fun parsesEmptyTableAsUnavailableRanges() {
        val table = assertNotNull(LocaTable.parse(1, false, ByteSlice.of(ByteArray(0))))

        assertEquals(0, table.size)
        assertNull(table.glyphRange(0))
    }
}
