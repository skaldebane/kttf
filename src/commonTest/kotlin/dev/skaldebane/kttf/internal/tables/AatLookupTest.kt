package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AatLookupTest {
    @Test
    fun format0UsesExactlyTheDeclaredFontGlyphCount() {
        val data =
            TestByteBuilder()
                .uint16(0)
                .uint16(10)
                .uint16(11)
                .build()
        val lookup = parse(data, glyphCount = 1)

        assertEquals(10, lookup.value(0))
        assertNull(lookup.value(1))
        assertNull(AatLookup.parse(2, ByteSlice.of(data.copyOf(4))))
    }

    @Test
    fun formats2And4ResolveConstantAndPerGlyphSegments() {
        val format2 =
            TestByteBuilder()
                .uint16(2)
                .binarySearchHeader(recordSize = 6, count = 1)
                .uint16(7)
                .uint16(5)
                .uint16(18)
                .build()
        val constant = parse(format2)
        assertNull(constant.value(4))
        assertEquals(18, constant.value(5))
        assertEquals(18, constant.value(7))
        assertNull(constant.value(8))

        val format4 =
            TestByteBuilder()
                .uint16(4)
                .binarySearchHeader(recordSize = 6, count = 1)
                .uint16(7)
                .uint16(5)
                .uint16(18)
                .uint16(10)
                .uint16(11)
                .uint16(12)
                .build()
        val varying = parse(format4)
        assertEquals(10, varying.value(5))
        assertEquals(11, varying.value(6))
        assertEquals(12, varying.value(7))
    }

    @Test
    fun format6SearchesSinglesAndDropsItsTerminationRecord() {
        val data =
            TestByteBuilder()
                .uint16(6)
                .binarySearchHeader(recordSize = 4, count = 3)
                .uint16(0)
                .uint16(10)
                .uint16(5)
                .uint16(20)
                .uint16(0xFFFF)
                .uint16(0xFFFF)
                .build()
        val lookup = parse(data)

        assertEquals(10, lookup.value(0))
        assertEquals(20, lookup.value(5))
        assertNull(lookup.value(1))
        assertNull(lookup.value(0xFFFF))

        val noSegments = TestByteBuilder().uint16(6).binarySearchHeader(4, 0).build()
        assertNull(AatLookup.parse(1, ByteSlice.of(noSegments)))
        val onlyTermination =
            TestByteBuilder()
                .uint16(6)
                .binarySearchHeader(4, 1)
                .uint16(0xFFFF)
                .uint16(0xFFFF)
                .build()
        assertNull(AatLookup.parse(1, ByteSlice.of(onlyTermination)))
    }

    @Test
    fun formats8And10SupportTrimmedValuesAndUpstreamTruncation() {
        val format8 =
            TestByteBuilder()
                .uint16(8)
                .uint16(5)
                .uint16(2)
                .uint16(20)
                .uint16(21)
                .build()
        val lookup8 = parse(format8)
        assertNull(lookup8.value(4))
        assertEquals(20, lookup8.value(5))
        assertEquals(21, lookup8.value(6))
        assertNull(lookup8.value(7))

        val oneByte =
            TestByteBuilder()
                .uint16(10)
                .uint16(1)
                .uint16(0)
                .uint16(1)
                .int8(2)
                .build()
        assertEquals(2, parse(oneByte).value(0))
        val fourByte =
            TestByteBuilder()
                .uint16(10)
                .uint16(4)
                .uint16(0)
                .uint16(1)
                .uint32(0x1_0009)
                .build()
        assertEquals(9, parse(fourByte).value(0))
        val unsupported =
            TestByteBuilder()
                .uint16(10)
                .uint16(8)
                .uint16(0)
                .uint16(1)
                .zeroes(8)
                .build()
        assertNull(parse(unsupported).value(0))
    }

    @Test
    fun extendedStateTableExposesClassesEntriesAndFlagsWithinItsSlice() {
        val bytes =
            TestByteBuilder()
                .uint32(4)
                .uint32(16)
                .uint32(24)
                .uint32(32)
                .uint16(8)
                .uint16(0)
                .uint16(1)
                .uint16(3)
                .uint16(0)
                .uint16(0)
                .uint16(0)
                .uint16(0)
                .uint16(2)
                .uint16(0xC001)
                .uint16(7)
                .build()
        val table =
            AatExtendedStateTable.parse(1, ByteSlice.of(bytes), 2) { extra -> extra.readUInt16(0) }!!

        assertEquals(3, table.glyphClass(0))
        assertEquals(AAT_DELETED_GLYPH_CLASS, table.glyphClass(0xFFFF))
        val entry = table.entry(AAT_START_OF_TEXT_STATE, 3)!!
        assertEquals(2, entry.newState)
        assertEquals(7, entry.extra)
        assertTrue(entry.hasMark)
        assertTrue(entry.hasPush)
        assertFalse(entry.hasAdvance)
        assertTrue(entry.hasOffset)
        assertEquals(1, entry.valueOffset)
        assertEquals(entry, table.entry(0, 99))
    }

    private fun parse(
        data: ByteArray,
        glyphCount: Int = 1,
    ): AatLookup = requireNotNull(AatLookup.parse(glyphCount, ByteSlice.of(data)))
}

private fun TestByteBuilder.binarySearchHeader(
    recordSize: Int,
    count: Int,
): TestByteBuilder = uint16(recordSize).uint16(count).zeroes(6)
