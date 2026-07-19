package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadTableTest {
    @Test
    fun acceptsUnitsPerEmBoundariesAndLocationFormats() {
        val shortOffsets = head(unitsPerEm = 16, locationFormat = 0)
        val longOffsets = head(unitsPerEm = 16384, locationFormat = 1)

        assertEquals(16, HeadTable.parse(shortOffsets)?.unitsPerEm)
        assertFalse(HeadTable.parse(shortOffsets)!!.longLocationOffsets)
        assertEquals(16384, HeadTable.parse(longOffsets)?.unitsPerEm)
        assertTrue(HeadTable.parse(longOffsets)!!.longLocationOffsets)
    }

    @Test
    fun rejectsInvalidUnitsPerEmAndLocationFormat() {
        assertNull(HeadTable.parse(head(unitsPerEm = 15, locationFormat = 0)))
        assertNull(HeadTable.parse(head(unitsPerEm = 16385, locationFormat = 0)))
        assertNull(HeadTable.parse(head(unitsPerEm = 1000, locationFormat = 2)))
    }

    @Test
    fun rejectsShortTable() {
        assertNull(HeadTable.parse(ByteSlice.of(ByteArray(53))))
    }

    private fun head(
        unitsPerEm: Int,
        locationFormat: Int,
    ): ByteSlice {
        val data = ByteArray(54)
        data[18] = (unitsPerEm ushr 8).toByte()
        data[19] = unitsPerEm.toByte()
        data[50] = (locationFormat ushr 8).toByte()
        data[51] = locationFormat.toByte()
        return ByteSlice.of(data)
    }
}
