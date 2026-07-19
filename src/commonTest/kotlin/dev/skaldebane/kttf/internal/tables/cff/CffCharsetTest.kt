package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CffCharsetTest {
    @Test
    fun parsesDefaultAndIsoAdobeWithExactUpstreamMappings() {
        for (charsetOffset in arrayOf<Int?>(null, 0)) {
            val charset = assertNotNull(parseCffCharset(ByteSlice.of(byteArrayOf()), charsetOffset, 1))

            for (sid in 0..UShort.MAX_VALUE.toInt()) {
                assertEquals(if (sid == 0) 0 else null, charset.sidToGid(sid), "offset=$charsetOffset sid=$sid")
            }
            for (gid in 0..UShort.MAX_VALUE.toInt()) {
                assertEquals(gid.takeIf { it <= 228 }, charset.gidToSid(gid), "offset=$charsetOffset gid=$gid")
            }
            assertNull(charset.sidToGid(-1))
            assertNull(charset.sidToGid(0x1_0000))
            assertNull(charset.gidToSid(-1))
            assertNull(charset.gidToSid(0x1_0000))
        }
    }

    @Test
    fun parsesEveryExpertAndExpertSubsetEntry() {
        for ((charsetId, expected) in listOf(1 to EXPECTED_EXPERT_SIDS, 2 to EXPECTED_EXPERT_SUBSET_SIDS)) {
            val charset = assertNotNull(parseCffCharset(ByteSlice.of(byteArrayOf()), charsetId, 1))

            expected.forEachIndexed { gid, sid ->
                assertEquals(sid, charset.gidToSid(gid), "charset=$charsetId gid=$gid")
            }
            for (gid in expected.size..UShort.MAX_VALUE.toInt()) {
                assertNull(charset.gidToSid(gid), "charset=$charsetId gid=$gid")
            }
            for (sid in 0..UShort.MAX_VALUE.toInt()) {
                assertEquals(if (sid == 0) 0 else null, charset.sidToGid(sid), "charset=$charsetId sid=$sid")
            }
            assertNull(charset.gidToSid(-1))
            assertNull(charset.gidToSid(0x1_0000))
            assertNull(charset.sidToGid(-1))
            assertNull(charset.sidToGid(0x1_0000))
        }
    }

    @Test
    fun parsesFormat0InBothDirectionsAndUsesFirstDuplicate() {
        val charset = assertNotNull(parseCustom(4, format0(7, 0xFFFF, 7)))
        val expectedSids = intArrayOf(0, 7, 0xFFFF, 7)

        expectedSids.forEachIndexed { gid, sid -> assertEquals(sid, charset.gidToSid(gid), "gid=$gid") }
        assertEquals(0, charset.sidToGid(0))
        assertEquals(1, charset.sidToGid(7))
        assertEquals(2, charset.sidToGid(0xFFFF))
        assertNull(charset.sidToGid(8))
        assertNull(charset.gidToSid(4))
        assertOutOfBoundsQueriesFail(charset)
    }

    @Test
    fun parsesFormat1RangesInBothDirectionsAndUsesFirstOverlap() {
        val charset = assertNotNull(parseCustom(7, format1(10 to 2, 20 to 0, 0xFFFE to 1)))
        val expectedSids = intArrayOf(0, 10, 11, 12, 20, 0xFFFE, 0xFFFF)

        expectedSids.forEachIndexed { gid, sid -> assertEquals(sid, charset.gidToSid(gid), "gid=$gid") }
        expectedSids.distinct().forEachIndexed { gid, sid -> assertEquals(gid, charset.sidToGid(sid), "sid=$sid") }
        assertNull(charset.sidToGid(13))
        assertNull(charset.gidToSid(expectedSids.size))

        val overlapping = assertNotNull(parseCustom(6, format1(10 to 2, 11 to 1)))
        assertEquals(2, overlapping.sidToGid(11))
        assertEquals(11, overlapping.gidToSid(4))
        assertOutOfBoundsQueriesFail(charset)
    }

    @Test
    fun parsesFormat2RangesIncludingTheMaximumValidGlyphCoverage() {
        val charset = assertNotNull(parseCustom(7, format2(1000 to 2, 0xFFFD to 2)))
        val expectedSids = intArrayOf(0, 1000, 1001, 1002, 0xFFFD, 0xFFFE, 0xFFFF)

        expectedSids.forEachIndexed { gid, sid ->
            assertEquals(sid, charset.gidToSid(gid), "gid=$gid")
            assertEquals(gid, charset.sidToGid(sid), "sid=$sid")
        }
        assertNull(charset.sidToGid(999))
        assertNull(charset.gidToSid(expectedSids.size))
        assertOutOfBoundsQueriesFail(charset)

        val maximum = assertNotNull(parseCustom(UShort.MAX_VALUE.toInt(), format2(0 to 0xFFFD)))
        assertEquals(0, maximum.gidToSid(1))
        assertEquals(0xFFFD, maximum.gidToSid(0xFFFE))
        assertNull(maximum.gidToSid(0xFFFF))
        assertEquals(0, maximum.sidToGid(0))
        assertEquals(0xFFFE, maximum.sidToGid(0xFFFD))
    }

    @Test
    fun supportsEmptyCustomCharsetsForNotdefOnlyFonts() {
        for (format in 0..2) {
            val charset = assertNotNull(parseCustom(1, byteArrayOf(format.toByte())))

            assertEquals(0, charset.sidToGid(0))
            assertEquals(0, charset.gidToSid(0))
            assertNull(charset.sidToGid(1))
            assertNull(charset.gidToSid(1))
        }
    }

    @Test
    fun rejectsEveryTruncatedCustomFormatPrefix() {
        val cases =
            listOf(
                4 to format0(7, 8, 9),
                6 to format1(10 to 1, 20 to 2),
                6 to format2(10 to 1, 20 to 2),
            )

        for ((glyphCount, bytes) in cases) {
            for (size in bytes.indices) {
                assertNull(parseCustom(glyphCount, bytes.copyOf(size)), "format=${bytes[0]} size=$size")
            }
            assertNotNull(parseCustom(glyphCount, bytes))
        }
    }

    @Test
    fun requiresRangesToCoverExactlyTheDeclaredGlyphCount() {
        val format1 = format1(10 to 1, 20 to 2)
        val format2 = format2(10 to 1, 20 to 2)

        for (bytes in listOf(format1, format2)) {
            assertNotNull(parseCustom(6, bytes))
            assertNull(parseCustom(5, bytes), "last range exceeds remaining glyphs")
            assertNull(parseCustom(7, bytes), "another range is required")
        }
    }

    @Test
    fun ignoresBytesAfterTheRequiredRangeCountLikeUpstream() {
        val extraRange = byteArrayOf(0, 99, 0)
        val format1 = format1(10 to 1) + extraRange
        val format2 = format2(10 to 1) + byteArrayOf(0, 99, 0, 0)

        val parsed1 = assertNotNull(parseCustom(3, format1))
        val parsed2 = assertNotNull(parseCustom(3, format2))
        assertEquals(11, parsed1.gidToSid(2))
        assertEquals(11, parsed2.gidToSid(2))
        assertNull(parsed1.gidToSid(3))
        assertNull(parsed2.gidToSid(3))
    }

    @Test
    fun rejectsFormat2LeftPlusOneOverflowAndOverlongRanges() {
        assertNull(parseCustom(UShort.MAX_VALUE.toInt(), format2(0 to 0xFFFF)))
        assertNull(parseCustom(2, format1(0 to 1)))
        assertNull(parseCustom(2, format2(0 to 1)))
    }

    @Test
    fun defersSidRangeOverflowToGidToSidQueriesLikeUpstream() {
        for (bytes in listOf(format1(0xFFFA to 10), format2(0xFFFA to 10))) {
            val charset = assertNotNull(parseCustom(12, bytes))

            assertEquals(0xFFFF, charset.gidToSid(6))
            assertNull(charset.gidToSid(7))
            assertEquals(6, charset.sidToGid(0xFFFF))
        }
    }

    @Test
    fun rejectsInvalidCountsOffsetsAndFormats() {
        val empty = ByteSlice.of(byteArrayOf())
        assertNull(parseCffCharset(empty, null, 0))
        assertNull(parseCffCharset(empty, 0, 0x1_0000))
        assertNull(parseCffCharset(empty, -1, 1))
        assertNull(parseCffCharset(empty, 3, 1))
        assertNull(parseCffCharset(empty, Int.MAX_VALUE, 1))
        assertNull(parseCustom(1, byteArrayOf(3)))
        assertNull(parseCustom(1, byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun parsingAndQueriesRemainInsideTheProvidedSlice() {
        val backing = byteArrayOf(90, 91, 92, 93, 0, 0, 7)
        val bounded = ByteSlice.of(backing).slice(1, 5)!!

        assertNull(parseCffCharset(bounded, CUSTOM_OFFSET, 2))
    }

    @Test
    fun customCharsetsRetainAZeroCopyViewOfTheSource() {
        val bytes = wrapped(format0(7))
        val charset = assertNotNull(parseCffCharset(ByteSlice.of(bytes), CUSTOM_OFFSET, 2))

        assertEquals(7, charset.gidToSid(1))
        bytes[CUSTOM_OFFSET + 2] = 9
        assertEquals(9, charset.gidToSid(1))
        assertNull(charset.sidToGid(7))
        assertEquals(1, charset.sidToGid(9))
    }

    private fun assertOutOfBoundsQueriesFail(charset: CffCharset) {
        assertNull(charset.sidToGid(-1))
        assertNull(charset.sidToGid(0x1_0000))
        assertNull(charset.gidToSid(-1))
        assertNull(charset.gidToSid(0x1_0000))
    }

    private fun parseCustom(
        glyphCount: Int,
        charset: ByteArray,
    ): CffCharset? = parseCffCharset(ByteSlice.of(wrapped(charset)), CUSTOM_OFFSET, glyphCount)

    private fun wrapped(charset: ByteArray): ByteArray = byteArrayOf(90, 91, 92) + charset

    private fun format0(vararg sids: Int): ByteArray = byteArrayOf(0) + unsigned16s(*sids)

    private fun format1(vararg ranges: Pair<Int, Int>): ByteArray {
        val bytes = mutableListOf(1.toByte())
        for ((first, left) in ranges) {
            appendUInt16(bytes, first)
            bytes += left.toByte()
        }
        return ByteArray(bytes.size) { bytes[it] }
    }

    private fun format2(vararg ranges: Pair<Int, Int>): ByteArray {
        val bytes = mutableListOf(2.toByte())
        for ((first, left) in ranges) {
            appendUInt16(bytes, first)
            appendUInt16(bytes, left)
        }
        return ByteArray(bytes.size) { bytes[it] }
    }

    private fun unsigned16s(vararg values: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        values.forEach { appendUInt16(bytes, it) }
        return ByteArray(bytes.size) { bytes[it] }
    }

    private fun appendUInt16(
        bytes: MutableList<Byte>,
        value: Int,
    ) {
        bytes += (value ushr 8).toByte()
        bytes += value.toByte()
    }
}

private const val CUSTOM_OFFSET: Int = 3

@Suppress("ktlint:standard:argument-list-wrapping")
private val EXPECTED_EXPERT_SIDS: IntArray =
    intArrayOf(
        0, 1, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 13, 14, 15, 99,
        239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 27, 28, 249, 250, 251, 252,
        253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 109, 110,
        267, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282,
        283, 284, 285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298,
        299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314,
        315, 316, 317, 318, 158, 155, 163, 319, 320, 321, 322, 323, 324, 325, 326, 150,
        164, 169, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340,
        341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356,
        357, 358, 359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372,
        373, 374, 375, 376, 377, 378,
    )

@Suppress("ktlint:standard:argument-list-wrapping")
private val EXPECTED_EXPERT_SUBSET_SIDS: IntArray =
    intArrayOf(
        0, 1, 231, 232, 235, 236, 237, 238, 13, 14, 15, 99, 239, 240, 241, 242,
        243, 244, 245, 246, 247, 248, 27, 28, 249, 250, 251, 253, 254, 255, 256, 257,
        258, 259, 260, 261, 262, 263, 264, 265, 266, 109, 110, 267, 268, 269, 270, 272,
        300, 301, 302, 305, 314, 315, 158, 155, 163, 320, 321, 322, 323, 324, 325, 326,
        150, 164, 169, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339,
        340, 341, 342, 343, 344, 345, 346,
    )
