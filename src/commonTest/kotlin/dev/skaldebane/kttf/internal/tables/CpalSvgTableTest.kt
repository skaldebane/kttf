package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CpalSvgTableTest {
    @Test
    fun cpalMatchesPaletteAndBgraBounds() {
        val data =
            TestByteBuilder()
                .uint16(1)
                .uint16(2)
                .uint16(2)
                .uint16(4)
                .uint32(16)
                .uint16(0)
                .uint16(2)
                .raw(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 1, 2, 3, 4))
                .build()
        val table = requireNotNull(CpalTable.parse(ByteSlice.of(data)))

        assertEquals(2, table.paletteCount)
        assertEquals(ColorRgba(30, 20, 10, 40), table.color(0, 0))
        assertEquals(ColorRgba(110, 100, 90, 120), table.color(1, 0))
        assertNull(table.color(0, 4))
        assertNull(table.color(2, 0))
        assertNull(table.color(0, -1))
    }

    @Test
    fun cpalRejectsUnsupportedEmptyTruncatedAndOverflowingPalettes() {
        assertNull(CpalTable.parse(ByteSlice.of(cpal(version = 2))))
        assertNull(CpalTable.parse(ByteSlice.of(cpal(palettes = 0))))
        assertNull(CpalTable.parse(ByteSlice.of(cpal().copyOf(13))))

        val overflow = cpal(colors = 1, firstColor = 0xFFFF)
        val table = requireNotNull(CpalTable.parse(ByteSlice.of(overflow)))
        assertNull(table.color(0, 1))
    }

    @Test
    fun svgUsesDocumentListRelativeOffsetsAndFirstMatchingRange() {
        val first = "<svg id='first'/>".encodeToByteArray()
        val second = byteArrayOf(0x1F, 0x8B.toByte(), 1, 2)
        val listSize = 2 + 2 * 12
        val data =
            TestByteBuilder()
                .uint16(99)
                .uint32(10)
                .uint32(0)
                .uint16(2)
                .uint16(2)
                .uint16(5)
                .uint32(listSize.toLong())
                .uint32(first.size.toLong())
                .uint16(4)
                .uint16(8)
                .uint32((listSize + first.size).toLong())
                .uint32(second.size.toLong())
                .raw(first)
                .raw(second)
                .build()
        val table = requireNotNull(SvgTable.parse(ByteSlice.of(data)))

        assertEquals(2, table.size)
        assertEquals(2, table.document(4)?.startGlyphId)
        val copied = ByteArray(first.size)
        assertEquals(true, table[0]?.copyInto(copied))
        assertContentEquals(first, copied)
        assertEquals(0x1F, table[1]?.get(0)?.toInt())
        assertNull(table.document(9))
    }

    @Test
    fun svgRejectsZeroListOffsetAndBoundsEachDocumentLazily() {
        assertNull(SvgTable.parse(ByteSlice.of(ByteArray(6))))

        val malformed =
            TestByteBuilder()
                .uint16(0)
                .uint32(6)
                .uint16(1)
                .uint16(1)
                .uint16(1)
                .uint32(14)
                .uint32(100)
                .build()
        val table = requireNotNull(SvgTable.parse(ByteSlice.of(malformed)))
        assertNull(table[0])
        assertNull(table.document(1))

        val destination = ByteArray(0)
        val valid = svgSingle(byteArrayOf(1))
        assertFalse(requireNotNull(valid[0]).copyInto(destination))
    }

    private fun cpal(
        version: Int = 0,
        palettes: Int = 1,
        colors: Int = 1,
        firstColor: Int = 0,
    ): ByteArray =
        TestByteBuilder()
            .uint16(version)
            .uint16(1)
            .uint16(palettes)
            .uint16(colors)
            .uint32(14)
            .apply { repeat(palettes) { uint16(firstColor) } }
            .raw(ByteArray(colors * 4))
            .build()

    private fun svgSingle(document: ByteArray): SvgTable {
        val data =
            TestByteBuilder()
                .uint16(0)
                .uint32(6)
                .uint16(1)
                .uint16(1)
                .uint16(1)
                .uint32(14)
                .uint32(document.size.toLong())
                .raw(document)
                .build()
        return requireNotNull(SvgTable.parse(ByteSlice.of(data)))
    }
}
