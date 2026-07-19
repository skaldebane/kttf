package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HheaTableTest {
    @Test
    fun acceptsExactLength() {
        val data = ByteArray(36)
        data[4] = 0x03
        data[5] = 0x20
        data[6] = 0xFF.toByte()
        data[7] = 0x38
        data[34] = 0
        data[35] = 2

        val table = HheaTable.parse(ByteSlice.of(data))!!

        assertEquals(800, table.ascender)
        assertEquals(-200, table.descender)
        assertEquals(2, table.numberOfMetrics)
    }

    @Test
    fun rejectsOneByteShort() {
        assertNull(HheaTable.parse(ByteSlice.of(ByteArray(35))))
    }
}
