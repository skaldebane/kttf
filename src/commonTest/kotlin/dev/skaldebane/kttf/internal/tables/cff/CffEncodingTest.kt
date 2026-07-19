package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CffEncodingTest {
    @Test
    fun standardHelperAndBothPredefinedEncodingsMatchEveryUpstreamEntry() {
        val data = ByteSlice.of(byteArrayOf())
        val charset = sequentialCharset(149)
        val standard = assertNotNull(parseCffEncoding(data, null))
        val explicitStandard = assertNotNull(parseCffEncoding(data, 0))
        val expert = assertNotNull(parseCffEncoding(data, 1))

        EXPECTED_STANDARD_ENCODING.forEachIndexed { code, sid ->
            assertEquals(sid, cffStandardEncodingCodeToSid(code), "helper code=$code")
            assertEquals(sid, standard.codeToGid(charset, code), "default code=$code")
            assertEquals(sid, explicitStandard.codeToGid(charset, code), "standard code=$code")
            assertEquals(sid, expert.codeToGid(charset, code), "expert code=$code")
        }
        for (code in listOf(Int.MIN_VALUE, -1, 256, Int.MAX_VALUE)) {
            assertNull(cffStandardEncodingCodeToSid(code), "helper code=$code")
            assertNull(standard.codeToGid(charset, code), "standard code=$code")
            assertNull(expert.codeToGid(charset, code), "expert code=$code")
        }
    }

    @Test
    fun format0MapsCodesDirectlyToTheFirstMatchingGid() {
        val codes = intArrayOf(7, 255, 7, 0, 128)
        val encoding = assertNotNull(parseCustom(format0(*codes)))
        val charset = sequentialCharset(1)

        for (code in 0..255) {
            val first = codes.indexOf(code)
            assertEquals(first.takeIf { it >= 0 }?.plus(1), encoding.codeToGid(charset, code), "code=$code")
        }
        assertNull(encoding.codeToGid(charset, -1))
        assertNull(encoding.codeToGid(charset, 256))
    }

    @Test
    fun format1UsesFirstRangeAccumulationAndSaturatingEnds() {
        val ranges = arrayOf(10 to 2, 11 to 1, 250 to 10)
        val encoding = assertNotNull(parseCustom(format1(*ranges)))
        val charset = sequentialCharset(1)

        for (code in 0..255) {
            var gid = 1
            var expected: Int? = null
            for ((first, left) in ranges) {
                if (code in first..(first + left).coerceAtMost(255)) {
                    expected = gid + code - first
                    break
                }
                gid += left + 1
            }
            assertEquals(expected, encoding.codeToGid(charset, code), "code=$code")
        }
        assertEquals(2, encoding.codeToGid(charset, 11), "the first overlapping range wins")
        assertEquals(11, encoding.codeToGid(charset, 255), "the last range saturates at 255")
        assertNull(encoding.codeToGid(charset, -1))
        assertNull(encoding.codeToGid(charset, 256))
    }

    @Test
    fun supplementsReturnTheFirstMatchingCharsetLookupImmediately() {
        val charset = charsetWithSids(9)
        val unresolvedFirst =
            assertNotNull(
                parseCustom(format0(7, supplements = listOf(7 to 8, 7 to 9))),
            )
        val resolvedFirst =
            assertNotNull(
                parseCustom(format0(7, supplements = listOf(7 to 9, 7 to 8))),
            )
        val primaryFallback =
            assertNotNull(
                parseCustom(format0(7, supplements = listOf(8 to 9))),
            )

        assertNull(unresolvedFirst.codeToGid(charset, 7), "no later supplement or primary fallback is attempted")
        assertEquals(1, resolvedFirst.codeToGid(charset, 7))
        assertEquals(1, primaryFallback.codeToGid(charset, 7))
        assertEquals(1, primaryFallback.codeToGid(charset, 8))
    }

    @Test
    fun acceptsMaximumCountsAndPreservesFirstMatchBehavior() {
        val format0Codes = IntArray(255) { it + 1 }
        val format0 = assertNotNull(parseCustom(format0(*format0Codes)))
        val ranges = Array(255) { if (it == 254) 255 to 255 else 0 to 254 }
        val format1 = assertNotNull(parseCustom(format1(*ranges)))
        val supplements = List(255) { it to 9 }
        val supplemented = assertNotNull(parseCustom(format0(supplements = supplements)))
        val charset = charsetWithSids(9)

        assertNull(format0.codeToGid(charset, 0))
        for (code in 1..255) assertEquals(code, format0.codeToGid(charset, code), "format0 code=$code")
        assertEquals(64_771, format1.codeToGid(charset, 255))
        for (code in 0..254) assertEquals(1, supplemented.codeToGid(charset, code), "supplement code=$code")
        assertNull(supplemented.codeToGid(charset, 255))
    }

    @Test
    fun rejectsInvalidOffsetsFormatsAndEveryTruncatedPrefix() {
        val empty = ByteSlice.of(byteArrayOf())
        assertNull(parseCffEncoding(empty, -1))
        assertNull(parseCffEncoding(empty, 2))
        assertNull(parseCffEncoding(empty, Int.MAX_VALUE))

        for (format in listOf(2, 0x7F, 0x82, 0xFF)) {
            assertNull(parseCustom(byteArrayOf(format.toByte(), 0)), "format=$format")
        }

        val complete =
            listOf(
                format0(7, 8, 9),
                format1(10 to 1, 20 to 2),
                format0(7, 8, supplements = listOf(7 to 9, 8 to 10)),
                format1(10 to 1, supplements = listOf(10 to 9, 11 to 10)),
            )
        for (bytes in complete) {
            for (size in bytes.indices) {
                assertNull(parseCustom(bytes.copyOf(size)), "format=${bytes[0].toInt() and 0xFF} size=$size")
            }
            assertNotNull(parseCustom(bytes))
        }
    }

    @Test
    fun parsingAndQueriesStayWithinTheProvidedSlice() {
        val backing = byteArrayOf(90, 91, 0, 1, 7)
        val bounded = ByteSlice.of(backing).slice(0, 4)!!

        assertNull(parseCffEncoding(bounded, CUSTOM_OFFSET))
    }

    @Test
    fun customRecordsAndSupplementsAreZeroCopyViews() {
        val primaryBytes = wrapped(format0(7))
        val primary = assertNotNull(parseCffEncoding(ByteSlice.of(primaryBytes), CUSTOM_OFFSET))
        val charset = charsetWithSids(9)

        assertEquals(1, primary.codeToGid(charset, 7))
        primaryBytes[CUSTOM_OFFSET + 2] = 8
        assertNull(primary.codeToGid(charset, 7))
        assertEquals(1, primary.codeToGid(charset, 8))

        val supplementBytes = wrapped(format0(7, supplements = listOf(7 to 8)))
        val supplemented = assertNotNull(parseCffEncoding(ByteSlice.of(supplementBytes), CUSTOM_OFFSET))
        assertNull(supplemented.codeToGid(charset, 7))
        supplementBytes[CUSTOM_OFFSET + 6] = 9
        assertEquals(1, supplemented.codeToGid(charset, 7))
    }

    private fun parseCustom(bytes: ByteArray): CffEncoding? =
        parseCffEncoding(ByteSlice.of(wrapped(bytes)), CUSTOM_OFFSET)

    private fun wrapped(bytes: ByteArray): ByteArray = byteArrayOf(90, 91) + bytes

    private fun format0(
        vararg codes: Int,
        supplements: List<Pair<Int, Int>>? = null,
    ): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes += (if (supplements == null) 0 else 0x80).toByte()
        bytes += codes.size.toByte()
        codes.forEach { bytes += it.toByte() }
        appendSupplements(bytes, supplements)
        return ByteArray(bytes.size) { bytes[it] }
    }

    private fun format1(
        vararg ranges: Pair<Int, Int>,
        supplements: List<Pair<Int, Int>>? = null,
    ): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes += (if (supplements == null) 1 else 0x81).toByte()
        bytes += ranges.size.toByte()
        for ((first, left) in ranges) {
            bytes += first.toByte()
            bytes += left.toByte()
        }
        appendSupplements(bytes, supplements)
        return ByteArray(bytes.size) { bytes[it] }
    }

    private fun appendSupplements(
        bytes: MutableList<Byte>,
        supplements: List<Pair<Int, Int>>?,
    ) {
        if (supplements == null) return
        bytes += supplements.size.toByte()
        for ((code, sid) in supplements) {
            bytes += code.toByte()
            bytes += (sid ushr 8).toByte()
            bytes += sid.toByte()
        }
    }

    private fun sequentialCharset(lastSid: Int): CffCharset {
        val bytes = byteArrayOf(90, 91, 92, 1, 0, 1, (lastSid - 1).toByte())
        return assertNotNull(parseCffCharset(ByteSlice.of(bytes), CUSTOM_CHARSET_OFFSET, lastSid + 1))
    }

    private fun charsetWithSids(vararg sids: Int): CffCharset {
        val bytes = mutableListOf<Byte>(90, 91, 92, 0)
        for (sid in sids) {
            bytes += (sid ushr 8).toByte()
            bytes += sid.toByte()
        }
        return assertNotNull(
            parseCffCharset(ByteSlice.of(ByteArray(bytes.size) { bytes[it] }), CUSTOM_CHARSET_OFFSET, sids.size + 1),
        )
    }
}

private const val CUSTOM_OFFSET: Int = 2
private const val CUSTOM_CHARSET_OFFSET: Int = 3

@Suppress("ktlint:standard:argument-list-wrapping")
private val EXPECTED_STANDARD_ENCODING: IntArray =
    intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48,
        49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64,
        65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
        81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
        0, 111, 112, 113, 114, 0, 115, 116, 117, 118, 119, 120, 121, 122, 0, 123,
        0, 124, 125, 126, 127, 128, 129, 130, 131, 0, 132, 133, 0, 134, 135, 136,
        137, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 138, 0, 139, 0, 0, 0, 0, 140, 141, 142, 143, 0, 0, 0, 0,
        0, 144, 0, 0, 0, 145, 0, 0, 146, 147, 148, 149, 0, 0, 0, 0,
    )
