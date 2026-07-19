package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SbixTableTest {
    @Test
    fun parsesSinglePngGlyph() {
        val table = assertNotNull(SbixTable.parse(1, ByteSlice.of(singleGlyphSbix())))
        assertEquals(1, table.strikes.size)
        val strike = assertNotNull(table.strikes[0])
        assertEquals(20, strike.pixelsPerEm)
        assertEquals(72, strike.pixelsPerInch)
        assertEquals(1, strike.size)

        val glyph = assertNotNull(strike.get(0))
        assertEquals(1, glyph.x)
        assertEquals(2, glyph.y)
        assertEquals(20, glyph.width)
        assertEquals(30, glyph.height)
        assertEquals(20, glyph.pixelsPerEm)
        assertEquals(RasterImageFormat.PNG, glyph.format)
        assertEquals(24, glyph.data.size)
        assertContentEquals(pngHeader(), glyph.data.values())
    }

    @Test
    fun duplicateUsesReferencedGlyphMetricsAndData() {
        val table = assertNotNull(SbixTable.parse(2, ByteSlice.of(duplicateSbix())))
        val strike = assertNotNull(table.strikes[0])
        val glyph = assertNotNull(strike.get(1))

        assertEquals(1, glyph.x)
        assertEquals(2, glyph.y)
        assertEquals(20, glyph.width)
        assertEquals(30, glyph.height)
        assertContentEquals(pngHeader(), glyph.data.values())
    }

    @Test
    fun recursiveDuplicatesAreBounded() {
        val table = assertNotNull(SbixTable.parse(2, ByteSlice.of(recursiveSbix())))
        val strike = assertNotNull(table.strikes[0])

        assertNull(strike.get(0))
        assertNull(strike.get(1))
    }

    @Test
    fun selectsClosestUpperStrikeThenLargestLowerStrike() {
        val table = assertNotNull(SbixTable.parse(1, ByteSlice.of(twoStrikeSbix())))

        assertEquals(16, table.bestStrike(12)?.pixelsPerEm)
        assertEquals(32, table.bestStrike(40)?.pixelsPerEm)
    }

    @Test
    fun malformedTablesAndGlyphsReturnNull() {
        assertNull(SbixTable.parse(0, ByteSlice.of(singleGlyphSbix())))
        assertNull(SbixTable.parse(0xFFFF, ByteSlice.of(singleGlyphSbix())))
        assertNull(SbixTable.parse(1, ByteSlice.of(ByteArray(0))))
        assertNull(SbixTable.parse(1, ByteSlice.of(TestByteBuilder().uint16(2).zeroes(10).build())))
        assertNull(
            SbixTable.parse(
                1,
                ByteSlice.of(
                    TestByteBuilder()
                        .uint16(1)
                        .uint16(0)
                        .uint32(0)
                        .build(),
                ),
            ),
        )
        for (length in 0 until 12) {
            assertNull(SbixTable.parse(1, ByteSlice.of(singleGlyphSbix().copyOf(length))), "length=$length")
        }

        val reversed =
            sbix(
                numberOfGlyphs = 1,
                glyphOffsets = intArrayOf(20, 12),
                glyphData = ByteArray(8),
            )
        assertNull(
            SbixTable
                .parse(1, ByteSlice.of(reversed))
                ?.strikes
                ?.get(0)
                ?.get(0),
        )

        val unsupported =
            TestByteBuilder()
                .int16(0)
                .int16(0)
                .raw("jpg ".encodeToByteArray())
                .raw(pngHeader())
                .build()
        val unsupportedTable = sbix(1, intArrayOf(12, 12 + unsupported.size), unsupported)
        assertNull(
            SbixTable
                .parse(1, ByteSlice.of(unsupportedTable))
                ?.strikes
                ?.get(0)
                ?.get(0),
        )

        val oversizedPng = pngHeader(width = 0x1_0000)
        val oversizedGlyph =
            TestByteBuilder()
                .zeroes(4)
                .raw("png ".encodeToByteArray())
                .raw(oversizedPng)
                .build()
        val oversizedTable = sbix(1, intArrayOf(12, 12 + oversizedGlyph.size), oversizedGlyph)
        assertNull(
            SbixTable
                .parse(1, ByteSlice.of(oversizedTable))
                ?.strikes
                ?.get(0)
                ?.get(0),
        )
    }

    private fun singleGlyphSbix(): ByteArray {
        val glyph =
            TestByteBuilder()
                .int16(1)
                .int16(2)
                .raw("png ".encodeToByteArray())
                .raw(pngHeader())
                .build()
        return sbix(1, intArrayOf(12, 12 + glyph.size), glyph)
    }

    private fun duplicateSbix(): ByteArray {
        val first =
            TestByteBuilder()
                .int16(1)
                .int16(2)
                .raw("png ".encodeToByteArray())
                .raw(pngHeader())
                .build()
        val second =
            TestByteBuilder()
                .int16(3)
                .int16(4)
                .raw("dupe".encodeToByteArray())
                .uint16(0)
                .build()
        return sbix(2, intArrayOf(16, 16 + first.size, 16 + first.size + second.size), first + second)
    }

    private fun recursiveSbix(): ByteArray {
        val first =
            TestByteBuilder()
                .int16(1)
                .int16(2)
                .raw("dupe".encodeToByteArray())
                .uint16(0)
                .build()
        val second =
            TestByteBuilder()
                .int16(1)
                .int16(2)
                .raw("dupe".encodeToByteArray())
                .uint16(0)
                .build()
        return sbix(2, intArrayOf(16, 16 + first.size, 16 + first.size + second.size), first + second)
    }

    private fun twoStrikeSbix(): ByteArray {
        val firstOffset = 16
        val strikeSize = 12
        val secondOffset = firstOffset + strikeSize
        return TestByteBuilder()
            .uint16(1)
            .uint16(0)
            .uint32(2)
            .uint32(firstOffset.toLong())
            .uint32(secondOffset.toLong())
            .uint16(32)
            .uint16(72)
            .uint32(12)
            .uint32(12)
            .uint16(16)
            .uint16(72)
            .uint32(12)
            .uint32(12)
            .build()
    }

    private fun sbix(
        numberOfGlyphs: Int,
        glyphOffsets: IntArray,
        glyphData: ByteArray,
    ): ByteArray =
        TestByteBuilder()
            .uint16(1)
            .uint16(0)
            .uint32(1)
            .uint32(12)
            .uint16(20)
            .uint16(72)
            .apply {
                assertEquals(numberOfGlyphs + 1, glyphOffsets.size)
                glyphOffsets.forEach { uint32(it.toLong()) }
            }.raw(glyphData)
            .build()

    private fun pngHeader(
        width: Long = 20,
        height: Long = 30,
    ): ByteArray =
        TestByteBuilder()
            .raw(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            .raw(byteArrayOf(0x0D, 0x0A, 0x1A, 0x0A))
            .uint32(13)
            .raw("IHDR".encodeToByteArray())
            .uint32(width)
            .uint32(height)
            .build()

    private fun RasterImageData.values(): ByteArray = ByteArray(size) { assertNotNull(this[it]) }
}
