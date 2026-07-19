package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MaxpTableTest {
    @Test
    fun parsesVersion05() {
        val data = TestByteBuilder().uint32(0x00005000).uint16(1).build()

        assertEquals(1, MaxpTable.parse(ByteSlice.of(data))?.glyphCount)
    }

    @Test
    fun parsesFullVersion10() {
        val builder = TestByteBuilder().uint32(0x00010000).uint16(1)
        repeat(13) { builder.uint16(0) }

        assertEquals(1, MaxpTable.parse(ByteSlice.of(builder.build()))?.glyphCount)
    }

    @Test
    fun parsesTrimmedVersion10() {
        val data = TestByteBuilder().uint32(0x00010000).uint16(1).build()

        assertEquals(1, MaxpTable.parse(ByteSlice.of(data))?.glyphCount)
    }

    @Test
    fun rejectsUnknownVersion() {
        val data = TestByteBuilder().uint32(0).uint16(1).build()

        assertNull(MaxpTable.parse(ByteSlice.of(data)))
    }

    @Test
    fun rejectsZeroGlyphs() {
        val data = TestByteBuilder().uint32(0x00005000).uint16(0).build()

        assertNull(MaxpTable.parse(ByteSlice.of(data)))
    }
}
