package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CffIndexTest {
    @Test
    fun parsesOffsetSize() {
        assertNull(parseCff1(byteArrayOf(0, 1, 0)))
        assertNotNull(parseCff1(byteArrayOf(0, 1, 1, 1, 1)))
        assertNull(parseCff1(byteArrayOf(0, 1, 5)))
    }

    @Test
    fun parsesCff1ObjectsWithEveryOffsetSize() {
        for (offsetSize in 1..4) {
            val bytes = indexBytes(2, 2, offsetSize, longArrayOf(1, 3, 6), byteArrayOf(10, 11, 20, 21, 22, 99))
            val stream = Stream.atStart(ByteSlice.of(bytes))
            val index = assertNotNull(CffIndex.parseCff1(stream), "offsetSize=$offsetSize")

            assertEquals(2, index.size)
            assertEquals(listOf(10, 11), index[0]?.values())
            assertEquals(listOf(20, 21, 22), index[1]?.values())
            assertNull(index[-1])
            assertNull(index[2])
            assertEquals(3 + 3 * offsetSize + 5, stream.offset)
            assertEquals(99, stream.readUInt8())
        }
    }

    @Test
    fun parsesCff2UInt32Count() {
        val bytes = indexBytes(2, 4, 1, longArrayOf(1, 2, 4), byteArrayOf(7, 8, 9))
        val stream = Stream.atStart(ByteSlice.of(bytes))
        val index = assertNotNull(CffIndex.parseCff2(stream))

        assertEquals(2, index.size)
        assertEquals(listOf(7), index[0]?.values())
        assertEquals(listOf(8, 9), index[1]?.values())
        assertEquals(bytes.size, stream.offset)
    }

    @Test
    fun emptyAndUInt32MaxCountsDoNotReadAnOffsetSize() {
        val cff1 = Stream.atStart(ByteSlice.of(byteArrayOf(0, 0, 77)))
        val cff2Zero = Stream.atStart(ByteSlice.of(byteArrayOf(0, 0, 0, 0, 78)))
        val cff2Max =
            Stream.atStart(
                ByteSlice.of(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 79)),
            )

        assertEquals(0, assertNotNull(CffIndex.parseCff1(cff1)).size)
        assertEquals(77, cff1.readUInt8())
        assertEquals(0, assertNotNull(CffIndex.parseCff2(cff2Zero)).size)
        assertEquals(78, cff2Zero.readUInt8())
        assertEquals(0, assertNotNull(CffIndex.parseCff2(cff2Max)).size)
        assertEquals(79, cff2Max.readUInt8())
    }

    @Test
    fun rejectsUInt32CountArithmeticAndStorageOverflow() {
        val multiplicationOverflow =
            Stream.atStart(
                ByteSlice.of(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(), 2)),
            )
        val intStorageOverflow =
            Stream.atStart(
                ByteSlice.of(byteArrayOf(0x80.toByte(), 0, 0, 0, 1)),
            )

        assertNull(CffIndex.parseCff2(multiplicationOverflow))
        assertEquals(5, multiplicationOverflow.offset)
        assertNull(CffIndex.parseCff2(intStorageOverflow))
        assertEquals(5, intStorageOverflow.offset)
    }

    @Test
    fun rejectsTruncatedHeadersOffsetsAndData() {
        assertNull(parseCff1(byteArrayOf(0)))
        assertNull(parseCff1(byteArrayOf(0, 1)))
        assertNull(parseCff1(byteArrayOf(0, 1, 1, 1)))
        assertNull(parseCff1(byteArrayOf(0, 1, 1, 1, 4, 10, 11)))
        assertNull(CffIndex.parseCff2(Stream.atStart(ByteSlice.of(byteArrayOf(0, 0, 0)))))
    }

    @Test
    fun appliesOneBasedOffsetsAndDefersOrderingValidationToAccess() {
        val descending =
            parseCff1(
                indexBytes(3, 2, 1, longArrayOf(1, 4, 3, 6), byteArrayOf(10, 11, 12, 13, 14)),
            )!!
        val zeroOffset =
            parseCff1(
                indexBytes(2, 2, 1, longArrayOf(0, 2, 3), byteArrayOf(20, 21)),
            )!!
        val equalOffsets =
            parseCff1(
                indexBytes(1, 2, 1, longArrayOf(1, 1), byteArrayOf()),
            )!!

        assertEquals(listOf(10, 11, 12), descending[0]?.values())
        assertNull(descending[1])
        assertEquals(listOf(12, 13, 14), descending[2]?.values())
        assertNull(zeroOffset[0])
        assertEquals(listOf(21), zeroOffset[1]?.values())
        assertEquals(0, equalOffsets[0]?.size)
    }

    @Test
    fun zeroTerminalOffsetProducesTheUpstreamEmptyDefault() {
        val stream = Stream.atStart(ByteSlice.of(indexBytes(1, 2, 1, longArrayOf(1, 0), byteArrayOf(55))))
        val index = assertNotNull(CffIndex.parseCff1(stream))

        assertEquals(0, index.size)
        assertEquals(5, stream.offset)
        assertEquals(55, stream.readUInt8())
    }

    @Test
    fun objectAccessIsALazyBoundedView() {
        val bytes = indexBytes(1, 2, 1, longArrayOf(1, 3), byteArrayOf(30, 31, 99))
        val stream = Stream.atStart(ByteSlice.of(bytes))
        val index = assertNotNull(CffIndex.parseCff1(stream))
        val objectStart = 2 + 1 + 2

        bytes[objectStart + 1] = 42

        assertEquals(listOf(30, 42), index[0]?.values())
        assertEquals(99, stream.readUInt8())
    }

    @Test
    fun skipsCff1AndCff2IndexesAndRejectsTruncatedData() {
        val cff1 =
            Stream.atStart(
                ByteSlice.of(indexBytes(1, 2, 1, longArrayOf(1, 3), byteArrayOf(10, 11, 90))),
            )
        val cff2 =
            Stream.atStart(
                ByteSlice.of(indexBytes(1, 4, 2, longArrayOf(1, 2), byteArrayOf(20, 91))),
            )
        val truncated = Stream.atStart(ByteSlice.of(indexBytes(1, 2, 1, longArrayOf(1, 4), byteArrayOf(30))))

        assertEquals(true, CffIndex.skipCff1(cff1))
        assertEquals(90, cff1.readUInt8())
        assertEquals(true, CffIndex.skipCff2(cff2))
        assertEquals(91, cff2.readUInt8())
        assertEquals(false, CffIndex.skipCff1(truncated))
        assertEquals(5, truncated.offset)
    }

    private fun parseCff1(bytes: ByteArray): CffIndex? = CffIndex.parseCff1(Stream.atStart(ByteSlice.of(bytes)))

    private fun indexBytes(
        count: Long,
        countSize: Int,
        offsetSize: Int,
        offsets: LongArray,
        data: ByteArray,
    ): ByteArray {
        val bytes = mutableListOf<Byte>()
        appendUnsigned(bytes, count, countSize)
        bytes += offsetSize.toByte()
        offsets.forEach { appendUnsigned(bytes, it, offsetSize) }
        data.forEach(bytes::add)
        return ByteArray(bytes.size) { bytes[it] }
    }

    private fun appendUnsigned(
        destination: MutableList<Byte>,
        value: Long,
        width: Int,
    ) {
        for (shift in (width - 1) * 8 downTo 0 step 8) {
            destination += (value ushr shift).toByte()
        }
    }

    private fun ByteSlice.values(): List<Int> = List(size) { readUInt8(it)!! }
}
