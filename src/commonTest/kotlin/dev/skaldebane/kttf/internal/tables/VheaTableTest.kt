package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VheaTableTest {
    @Test
    fun parsesSignedFieldsAndUnsignedMetricsCount() {
        val data =
            TestByteBuilder()
                .uint32(0x00011000)
                .int16(-32768)
                .int16(32767)
                .int16(-1)
                .zeroes(24)
                .uint16(65535)
                .build()

        val table = VheaTable.parse(ByteSlice.of(data))!!

        assertEquals(-32768, table.ascender)
        assertEquals(32767, table.descender)
        assertEquals(-1, table.lineGap)
        assertEquals(65535, table.numberOfMetrics)
    }

    @Test
    fun acceptsZeroMetricsCount() {
        val table = VheaTable.parse(ByteSlice.of(ByteArray(36)))

        assertNotNull(table)
        assertEquals(0, table.numberOfMetrics)
    }

    @Test
    fun ignoresVersionAndReservedFields() {
        val data = ByteArray(36) { 0x7F }

        val table = VheaTable.parse(ByteSlice.of(data))!!

        assertEquals(0x7F7F, table.ascender)
        assertEquals(0x7F7F, table.descender)
        assertEquals(0x7F7F, table.lineGap)
        assertEquals(0x7F7F, table.numberOfMetrics)
    }

    @Test
    fun acceptsTrailingPadding() {
        val data = ByteArray(40)
        data[34] = 0x12
        data[35] = 0x34

        assertEquals(0x1234, VheaTable.parse(ByteSlice.of(data))?.numberOfMetrics)
    }

    @Test
    fun rejectsEveryTruncatedPrefix() {
        for (length in 0 until 36) {
            assertNull(VheaTable.parse(ByteSlice.of(ByteArray(length))), "length=$length")
        }
    }
}
