package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KerxTableTest {
    @Test
    fun format0ExposesPairsCoverageAndDirectKerning() {
        val body =
            TestByteBuilder()
                .uint32(1)
                .zeroes(12)
                .uint32(0x0001_0002)
                .int16(-20)
                .build()
        val table = parse(kerx(subtable(format = 0, coverage = 0x60, tupleCount = 2, body = body)))
        val subtable = table.subtables.single()
        val format = assertIs<KerxFormat0>(subtable.format)

        assertTrue(subtable.horizontal)
        assertTrue(subtable.variable)
        assertTrue(subtable.hasCrossStream)
        assertFalse(subtable.hasStateMachine)
        assertEquals(2, subtable.tupleCount)
        assertEquals(KerxPair(1, 2, -20), format.pairs[0])
        assertEquals(-20, subtable.glyphsKerning(1, 2))
        assertNull(subtable.glyphsKerning(2, 1))
    }

    @Test
    fun formats2And6ResolveClassAndIndexArrays() {
        val format2Body =
            TestByteBuilder()
                .uint32(2)
                .uint32(28)
                .uint32(34)
                .uint32(44)
                .uint16(1)
                .uint16(1)
                .uint16(44)
                .uint16(2)
                .uint16(1)
                .uint16(0)
                .zeroes(4)
                .int16(-7)
                .build()
        val format2 = parse(kerx(subtable(2, body = format2Body))).subtables.single()
        assertEquals(-7, format2.glyphsKerning(1, 2))
        assertNull(format2.glyphsKerning(0, 2))

        val format6Body =
            TestByteBuilder()
                .uint32(0)
                .uint16(1)
                .uint16(1)
                .uint32(36)
                .uint32(44)
                .uint32(52)
                .uint32(54)
                .trimmedLookup(1, 0)
                .trimmedLookup(2, 0)
                .uint16(0)
                .int16(-8)
                .build()
        val format6 = parse(kerx(subtable(6, body = format6Body))).subtables.single()
        assertEquals(-8, format6.glyphsKerning(1, 2))
    }

    @Test
    fun format1ProvidesTypedStateEntriesAndActionValues() {
        val body =
            TestByteBuilder()
                .uint32(4)
                .uint32(20)
                .uint32(28)
                .uint32(36)
                .uint32(42)
                .trimmedLookup(0, 3)
                .zeroes(8)
                .uint16(2)
                .uint16(0x8000)
                .uint16(1)
                .int16(-5)
                .int16(-9)
                .build()
        val subtable = parse(kerx(subtable(1, body = body))).subtables.single()
        val format = assertIs<KerxFormat1>(subtable.format)

        assertTrue(subtable.hasStateMachine)
        assertNull(subtable.glyphsKerning(0, 0))
        assertEquals(3, format.stateTable.glyphClass(0))
        val entry = format.stateTable.entry(0, 3)!!
        assertEquals(2, entry.newState)
        assertEquals(1, entry.extra.actionIndex)
        assertTrue(entry.hasPush)
        assertEquals(-9, format.actionKerning(entry.extra.actionIndex))
    }

    @Test
    fun iteratorStopsSafelyAtUnknownOrTruncatedSubtables() {
        val valid = subtable(0, body = TestByteBuilder().uint32(0).zeroes(12).build())
        val unknown = subtable(3, body = byteArrayOf())
        assertEquals(1, parse(kerx(valid, unknown)).subtables.count())

        val truncated = kerx(valid).copyOf(kerx(valid).size - 1)
        assertEquals(0, parse(truncated).subtables.count())
        assertNull(KerxTable.parse(0, ByteSlice.of(kerx(valid))))
    }

    private fun parse(data: ByteArray): KerxTable = requireNotNull(KerxTable.parse(3, ByteSlice.of(data)))
}

private fun kerx(vararg subtables: ByteArray): ByteArray =
    TestByteBuilder()
        .uint16(2)
        .uint16(0)
        .uint32(subtables.size.toLong())
        .apply { subtables.forEach(::raw) }
        .build()

private fun subtable(
    format: Int,
    coverage: Int = 0,
    tupleCount: Int = 0,
    body: ByteArray,
): ByteArray =
    TestByteBuilder()
        .uint32((12 + body.size).toLong())
        .int8(coverage)
        .uint16(0)
        .int8(format)
        .uint32(tupleCount.toLong())
        .raw(body)
        .build()

private fun TestByteBuilder.trimmedLookup(
    firstGlyph: Int,
    value: Int,
): TestByteBuilder = uint16(8).uint16(firstGlyph).uint16(1).uint16(value)
