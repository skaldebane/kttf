package dev.skaldebane.kttf.internal.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamTest {
    @Test
    fun failedReadDoesNotAdvance() {
        val stream = Stream.atStart(ByteSlice.of(byteArrayOf(1, 2, 3)))

        assertNull(stream.readUInt32())
        assertEquals(0, stream.offset)
        assertEquals(0x0102, stream.readUInt16())
        assertEquals(2, stream.offset)
    }

    @Test
    fun checkedAdvanceRejectsInvalidLengths() {
        val stream = Stream.atStart(ByteSlice.of(ByteArray(2)))

        assertFalse(stream.advance(Int.MAX_VALUE))
        assertEquals(0, stream.offset)
        assertTrue(stream.advance(2))
        assertTrue(stream.atEnd)
        assertFalse(stream.advance(1))
    }

    @Test
    fun streamCanStartAtEnd() {
        val stream = Stream.at(ByteSlice.of(ByteArray(2)), 2)!!

        assertTrue(stream.atEnd)
        assertEquals(0, stream.tail()?.size)
    }
}
