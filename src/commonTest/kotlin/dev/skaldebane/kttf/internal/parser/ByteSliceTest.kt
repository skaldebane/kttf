package dev.skaldebane.kttf.internal.parser

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteSliceTest {
    @Test
    fun readsBigEndianPrimitives() {
        val slice = ByteSlice.of(byteArrayOf(0x80.toByte(), 0x01, 0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte()))

        assertEquals(128, slice.readUInt8(0))
        assertEquals(-128, slice.readInt8(0))
        assertEquals(0x8001, slice.readUInt16(0))
        assertEquals(-32767, slice.readInt16(0))
        assertEquals(0x01FEDC, slice.readUInt24(1))
        assertEquals(0x8001FEDCL, slice.readUInt32(0))
    }

    @Test
    fun rejectsInvalidRangesWithoutOverflow() {
        val slice = ByteSlice.of(ByteArray(8))

        assertFalse(slice.contains(-1, 1))
        assertFalse(slice.contains(0, -1))
        assertFalse(slice.contains(Int.MAX_VALUE, 1))
        assertFalse(slice.contains(1, Int.MAX_VALUE))
        assertNull(slice.slice(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(slice.sliceFrom(Int.MIN_VALUE))
    }

    @Test
    fun slicesWithoutCopying() {
        val source = byteArrayOf(1, 2, 3, 4)
        val child = ByteSlice.of(source).slice(1, 2)!!

        source[2] = 9

        assertEquals(9, child.readUInt8(1))
        assertContentEquals(byteArrayOf(1, 2, 9, 4), source)
    }

    @Test
    fun acceptsEmptyRangeAtEnd() {
        val slice = ByteSlice.of(ByteArray(4))

        assertTrue(slice.contains(4, 0))
        assertEquals(0, slice.slice(4, 0)?.size)
    }
}
