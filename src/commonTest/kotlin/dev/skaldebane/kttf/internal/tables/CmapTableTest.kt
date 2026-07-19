package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CmapTableTest {
    @Test
    fun format0MapsOnlyNonzeroGlyphs() {
        val glyphs = ByteArray(256)
        glyphs[0x40] = 100
        val subtable =
            parseSubtable(
                TestByteBuilder()
                    .uint16(0)
                    .uint16(262)
                    .uint16(0)
                    .raw(glyphs)
                    .build(),
            )

        assertNull(subtable.glyphId(0))
        assertEquals(100, subtable.glyphId(0x40))
        assertNull(subtable.glyphId(100))
        assertEquals(listOf(0x40L), subtable.codePoints())
    }

    @Test
    fun format2CollectsSingleAndDoubleByteCodePoints() {
        val keys = ByteArray(512)
        keys[0x28 * 2 + 1] = 8
        val data =
            TestByteBuilder()
                .uint16(2)
                .uint16(534)
                .uint16(0)
                .raw(keys)
                .uint16(254)
                .uint16(2)
                .uint16(0)
                .uint16(0)
                .uint16(16)
                .uint16(3)
                .uint16(0)
                .uint16(0)
                .build()

        assertEquals(listOf(10256L, 10257L, 10258L, 254L, 255L), parseSubtable(data).codePoints())
    }

    @Test
    fun format2DoesNotIncludeExclusiveRangeEnd() {
        val data =
            TestByteBuilder()
                .uint16(2)
                .uint16(532)
                .uint16(0)
                .zeroes(512)
                .uint16(40)
                .uint16(2)
                .uint16(0)
                .uint16(2)
                .uint16(100)
                .uint16(1000)
                .uint16(10000)
                .build()
        val subtable = parseSubtable(data)

        assertNull(subtable.glyphId(39))
        assertEquals(100, subtable.glyphId(40))
        assertEquals(1000, subtable.glyphId(41))
        assertNull(subtable.glyphId(42))
    }

    @Test
    fun format4MapsDirectContinuousAndDisjointRanges() {
        val subtable =
            parseSubtable(
                format4(
                    starts = intArrayOf(65, 67, 71, 65535),
                    ends = intArrayOf(65, 69, 73, 65535),
                    deltas = intArrayOf(-64, -65, -66, 1),
                    offsets = intArrayOf(0, 0, 0, 0),
                ),
            )

        assertEquals(1, subtable.glyphId(0x41))
        assertNull(subtable.glyphId(0x42))
        assertEquals(2, subtable.glyphId(0x43))
        assertEquals(4, subtable.glyphId(0x45))
        assertNull(subtable.glyphId(0x46))
        assertEquals(7, subtable.glyphId(0x49))
        assertNull(subtable.glyphId(0x4A))
    }

    @Test
    fun format4ReadsGlyphArrayInStoredOrder() {
        val subtable =
            parseSubtable(
                format4(
                    starts = intArrayOf(65, 65535),
                    ends = intArrayOf(69, 65535),
                    deltas = intArrayOf(0, 1),
                    offsets = intArrayOf(4, 0),
                    glyphs = intArrayOf(1, 10, 100, 1000, 10000),
                ),
            )

        assertEquals(listOf(1, 10, 100, 1000, 10000), (65L..69L).map(subtable::glyphId))
    }

    @Test
    fun format4SupportsUnorderedGlyphIdsAcrossSingletonSegments() {
        val subtable =
            parseSubtable(
                format4(
                    starts = intArrayOf(80, 256, 336, 512, 592, 65535),
                    ends = intArrayOf(80, 256, 336, 512, 592, 65535),
                    deltas = intArrayOf(-79, -246, -236, 488, 9408, 1),
                    offsets = IntArray(6),
                ),
            )

        assertEquals(listOf(1, 10, 100, 1000, 10000), listOf(80L, 256L, 336L, 512L, 592L).map(subtable::glyphId))
    }

    @Test
    fun format4RejectsTruncatedArraysAndInvalidSegmentCount() {
        val valid = format4(intArrayOf(65, 65535), intArrayOf(65, 65535), intArrayOf(-64, 1), intArrayOf(0, 0))
        assertNull(parseSubtableOrNull(valid.copyOf(valid.size - 2)))

        val invalidCount = valid.copyOf()
        invalidCount[6] = 0
        invalidCount[7] = 1
        assertNull(parseSubtableOrNull(invalidCount))
    }

    @Test
    fun format4IgnoresDeclaredLengthAndHandlesSentinelOnly() {
        val invalidLength = format4(intArrayOf(65, 65535), intArrayOf(65, 65535), intArrayOf(-64, 1), intArrayOf(0, 0))
        invalidLength[2] = 0
        invalidLength[3] = 16
        assertEquals(1, parseSubtable(invalidLength).glyphId(65))

        val sentinel = format4(intArrayOf(65535), intArrayOf(65535), intArrayOf(1), intArrayOf(0))
        assertNull(parseSubtable(sentinel).glyphId(65))
    }

    @Test
    fun format4RejectsOutOfRangeZeroAndInvalidOffset() {
        val zero =
            parseSubtable(
                format4(
                    intArrayOf(65, 65535),
                    intArrayOf(65, 65535),
                    intArrayOf(0, 1),
                    intArrayOf(4, 0),
                    intArrayOf(0),
                ),
            )
        assertNull(zero.glyphId(65))
        assertNull(zero.glyphId(0x1FFFF))

        val invalidOffset =
            parseSubtable(
                format4(
                    intArrayOf(65, 65535),
                    intArrayOf(69, 65535),
                    intArrayOf(0, 1),
                    intArrayOf(4, 65535),
                    intArrayOf(1),
                ),
            )
        assertNull(invalidOffset.glyphId(65535))
    }

    @Test
    fun format4CollectsStoredRangesAndStopsAtSingletonSentinel() {
        val subtable =
            parseSubtable(
                format4(
                    intArrayOf(27, 65533),
                    intArrayOf(34, 65535),
                    intArrayOf(0, 1),
                    intArrayOf(4, 0),
                    intArrayOf(0, 10),
                ),
            )

        assertEquals((27L..34L).toList() + listOf(65533L, 65534L, 65535L), subtable.codePoints())
    }

    @Test
    fun formats6And10MapTrimmedArrays() {
        val format6 =
            parseSubtable(
                TestByteBuilder()
                    .uint16(6)
                    .uint16(16)
                    .uint16(0)
                    .uint16(100)
                    .uint16(3)
                    .uint16(4)
                    .uint16(0)
                    .uint16(9)
                    .build(),
            )
        assertEquals(4, format6.glyphId(100))
        assertEquals(0, format6.glyphId(101))
        assertNull(format6.glyphId(103))
        assertEquals(listOf(100L, 101L, 102L), format6.codePoints())

        val format10 =
            parseSubtable(
                TestByteBuilder()
                    .uint16(10)
                    .uint16(0)
                    .uint32(26)
                    .uint32(0)
                    .uint32(0x10000)
                    .uint32(3)
                    .uint16(10)
                    .uint16(11)
                    .uint16(12)
                    .build(),
            )
        assertEquals(11, format10.glyphId(0x10001))
        assertEquals(listOf(0x10000L, 0x10001L, 0x10002L), format10.codePoints())

        val boundary =
            parseSubtable(
                TestByteBuilder()
                    .uint16(10)
                    .uint16(0)
                    .uint32(24)
                    .uint32(0)
                    .uint32(0xFFFF_FFFFL)
                    .uint32(2)
                    .uint16(1)
                    .uint16(2)
                    .build(),
            )
        assertEquals(1, boundary.glyphId(0xFFFF_FFFFL))
        assertNull(boundary.glyphId(0x1_0000_0000L))
    }

    @Test
    fun formats12And13MapGroups() {
        val format12 =
            parseSubtable(
                groupedFormat(12, arrayOf(longArrayOf(0x10000, 0x10002, 20), longArrayOf(0x20000, 0x20000, 30))),
            )
        assertEquals(22, format12.glyphId(0x10002))
        assertEquals(30, format12.glyphId(0x20000))
        assertNull(format12.glyphId(0x10003))
        assertEquals(listOf(0x10000L, 0x10001L, 0x10002L, 0x20000L), format12.codePoints())

        val format13 = parseSubtable(groupedFormat(13, arrayOf(longArrayOf(100, 102, 7))))
        assertEquals(7, format13.glyphId(100))
        assertEquals(7, format13.glyphId(102))
        assertNull(format13.glyphId(103))

        val overflowing = parseSubtable(groupedFormat(12, arrayOf(longArrayOf(0xFFFF_FFFFL, 0xFFFF_FFFFL, 1))))
        assertNull(overflowing.glyphId(0xFFFF_FFFFL))

        val overlapping =
            parseSubtable(
                groupedFormat(
                    12,
                    arrayOf(longArrayOf(100, 102, 1), longArrayOf(100, 102, 10)),
                ),
            )
        assertEquals(10, overlapping.glyphId(100))
    }

    @Test
    fun format14ResolvesDefaultAndNonDefaultMappings() {
        val subtable = parseSubtable(format14())

        assertEquals(CmapVariationResult.UseDefault, subtable.glyphVariation(0x41, 0xFE0F))
        assertEquals(77, assertIs<CmapVariationResult.Found>(subtable.glyphVariation(0x42, 0xFE0F)).glyphId)
        assertNull(subtable.glyphVariation(0x43, 0xFE0F))
        assertNull(subtable.glyphVariation(0x41, 0xFE0E))
        assertTrue(subtable.codePoints().isEmpty())
    }

    @Test
    fun parsesEncodingRecordsAndUnicodePolicy() {
        val format4 = format4(intArrayOf(65, 65535), intArrayOf(65, 65535), intArrayOf(-64, 1), intArrayOf(0, 0))
        val format10 =
            TestByteBuilder()
                .uint16(10)
                .uint16(0)
                .uint32(20)
                .uint32(0)
                .uint32(65)
                .uint32(0)
                .build()
        val table = parseTable(listOf(Triple(3, 1, format4), Triple(3, 10, format10), Triple(0, 99, format4)))

        assertEquals(3, table.size)
        assertTrue(table[0]!!.isUnicode)
        assertFalse(table[1]!!.isUnicode)
        assertTrue(table[2]!!.isUnicode)
        assertEquals(CmapFormatKind.SEGMENT_MAPPING, table[0]!!.formatKind)
        assertEquals(1, table.glyphId(65))
    }

    private fun parseSubtable(data: ByteArray): CmapSubtable = requireNotNull(parseSubtableOrNull(data))

    private fun parseSubtableOrNull(data: ByteArray): CmapSubtable? = parseTable(listOf(Triple(0, 3, data)))[0]

    private fun parseTable(records: List<Triple<Int, Int, ByteArray>>): CmapTable {
        val headerSize = 4 + records.size * 8
        var offset = headerSize
        val builder = TestByteBuilder().uint16(0).uint16(records.size)
        for ((platform, encoding, data) in records) {
            builder.uint16(platform).uint16(encoding).uint32(offset.toLong())
            offset += data.size
        }
        records.forEach { builder.raw(it.third) }
        return requireNotNull(CmapTable.parse(ByteSlice.of(builder.build())))
    }

    private fun CmapSubtable.codePoints(): List<Long> = buildList { forEachCodePoint(::add) }

    private fun format4(
        starts: IntArray,
        ends: IntArray,
        deltas: IntArray,
        offsets: IntArray,
        glyphs: IntArray = intArrayOf(),
    ): ByteArray {
        require(starts.size == ends.size && starts.size == deltas.size && starts.size == offsets.size)
        val size = 16 + starts.size * 8 + glyphs.size * 2
        return TestByteBuilder()
            .uint16(4)
            .uint16(size)
            .uint16(0)
            .uint16(starts.size * 2)
            .uint16(0)
            .uint16(0)
            .uint16(0)
            .apply { ends.forEach(::uint16) }
            .uint16(0)
            .apply { starts.forEach(::uint16) }
            .apply { deltas.forEach(::int16) }
            .apply { offsets.forEach(::uint16) }
            .apply { glyphs.forEach(::uint16) }
            .build()
    }

    private fun groupedFormat(
        format: Int,
        groups: Array<LongArray>,
    ): ByteArray =
        TestByteBuilder()
            .uint16(format)
            .uint16(0)
            .uint32((16 + groups.size * 12).toLong())
            .uint32(0)
            .uint32(groups.size.toLong())
            .apply { groups.forEach { group -> group.forEach(::uint32) } }
            .build()

    private fun format14(): ByteArray {
        val defaultOffset = 21
        val nonDefaultOffset = 29
        return TestByteBuilder()
            .uint16(14)
            .uint32(38)
            .uint32(1)
            .int8(0)
            .int8(0xFE)
            .int8(0x0F)
            .uint32(defaultOffset.toLong())
            .uint32(nonDefaultOffset.toLong())
            .uint32(1)
            .int8(0)
            .int8(0)
            .int8(0x41)
            .int8(0)
            .uint32(1)
            .int8(0)
            .int8(0)
            .int8(0x42)
            .uint16(77)
            .build()
    }
}
