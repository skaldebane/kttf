package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CffHeaderTest {
    @Test
    fun parsesVersionAndLeavesStreamAtDeclaredHeaderEnd() {
        val stream = stream(2, 7, 7, 0x12, 0x34, 0xAA, 0xBB, 99)

        val header = assertNotNull(parseCffHeader(stream, minimumSize = 5))

        assertEquals(2, header.majorVersion)
        assertEquals(7, header.minorVersion)
        assertEquals(7, header.size)
        assertEquals(0x1234, header.remainder.readUInt16(0))
        assertEquals(0xAA, header.remainder.readUInt8(2))
        assertEquals(7, stream.offset)
        assertEquals(99, stream.readUInt8())
    }

    @Test
    fun exposesCff1OffsetSizeFromTheHeaderRemainder() {
        val stream = stream(1, 0, 4, 3, 88)

        val header = assertNotNull(parseCffHeader(stream, minimumSize = 4))

        assertEquals(3, header.remainder.readUInt8(0))
        assertEquals(4, stream.offset)
        assertEquals(88, stream.readUInt8())
    }

    @Test
    fun rejectsUndersizedAndTruncatedHeaders() {
        assertNull(parseCffHeader(stream(1, 0, 3, 9), minimumSize = 4))
        assertNull(parseCffHeader(stream(1, 0, 6, 0), minimumSize = 4))
        assertNull(parseCffHeader(stream(1, 0, 3), minimumSize = 2))
        assertNull(parseCffHeader(stream(1, 0), minimumSize = 3))
    }

    private fun stream(vararg values: Int): Stream =
        Stream.atStart(ByteSlice.of(ByteArray(values.size) { values[it].toByte() }))
}
