package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KernTableTest {
    @Test
    fun parsesOpenTypeFormatZeroAsALazyOrderedPairView() {
        val bytes =
            openTypeKern(
                openTypeSubtable(
                    format = 0,
                    coverage = 0x05,
                    declaredLength = 6,
                    body =
                        TestByteBuilder()
                            .uint16(2)
                            .zeroes(6)
                            .uint16(1)
                            .uint16(2)
                            .int16(-20)
                            .uint16(3)
                            .uint16(4)
                            .int16(30)
                            .build(),
                ),
            )
        val table = parse(bytes)
        val subtable = table.subtables.iterator().next()!!

        assertFalse(table.subtables.isAat)
        assertEquals(1, table.subtables.size)
        assertEquals(KernFormat.FORMAT_0, subtable.format)
        assertTrue(subtable.horizontal)
        assertTrue(subtable.hasCrossStream)
        assertFalse(subtable.variable)
        assertFalse(subtable.hasStateMachine)
        assertEquals(KernPair(1, 2, -20), subtable.pairs!![0])
        assertEquals(-20, subtable.glyphsKerning(1, 2))
        assertEquals(30, subtable.glyphsKerning(3, 4))
        assertNull(subtable.glyphsKerning(2, 1))

        bytes[bytes.lastIndex] = 77
        assertEquals(77, subtable.glyphsKerning(3, 4))
    }

    @Test
    fun parsesOpenTypeFormatTwoClassKerning() {
        val body =
            TestByteBuilder()
                .uint16(2)
                .uint16(14)
                .uint16(20)
                .uint16(26)
                .uint16(5)
                .uint16(1)
                .uint16(26)
                .uint16(7)
                .uint16(1)
                .uint16(2)
                .int16(0)
                .int16(-40)
                .build()
        val subtable = parse(openTypeKern(openTypeSubtable(2, 1, body = body))).subtables.iterator().next()!!

        assertEquals(KernFormat.FORMAT_2, subtable.format)
        assertEquals(-40, subtable.glyphsKerning(5, 7))
        assertNull(subtable.glyphsKerning(4, 7))
        assertEquals(0, subtable.glyphsKerning(5, 8))
    }

    @Test
    fun parsesAatFormatThreeAndCoverage() {
        val body =
            TestByteBuilder()
                .uint16(3)
                .int8(2)
                .int8(2)
                .int8(2)
                .int8(0)
                .int16(0)
                .int16(-50)
                .raw(byteArrayOf(0, 1, 0))
                .raw(byteArrayOf(0, 0, 1))
                .raw(byteArrayOf(0, 1, 1, 0))
                .build()
        val table = parse(aatKern(aatSubtable(3, 0xE0, body)))
        val subtable = table.subtables.iterator().next()!!

        assertTrue(table.subtables.isAat)
        assertEquals(KernFormat.FORMAT_3, subtable.format)
        assertFalse(subtable.horizontal)
        assertTrue(subtable.variable)
        assertTrue(subtable.hasCrossStream)
        assertEquals(-50, subtable.glyphsKerning(1, 0))
        assertNull(subtable.glyphsKerning(3, 0))
    }

    @Test
    fun exposesAatFormatOneStateMachineViews() {
        val body =
            TestByteBuilder()
                .uint16(4)
                .uint16(8)
                .uint16(14)
                .uint16(18)
                .uint16(10)
                .uint16(2)
                .raw(byteArrayOf(3, 2))
                .zeroes(4)
                .uint16(14)
                .uint16(0x8018)
                .zeroes(2)
                .int16(-30)
                .build()
        val subtable = parse(aatKern(aatSubtable(1, 0, body))).subtables.iterator().next()!!
        val stateTable = subtable.stateTable!!
        val entry = stateTable.entry(0, stateTable.glyphClass(10)!!)!!

        assertEquals(KernFormat.FORMAT_1, subtable.format)
        assertTrue(subtable.hasStateMachine)
        assertNull(subtable.glyphsKerning(10, 11))
        assertEquals(3, stateTable.glyphClass(10))
        assertEquals(2, stateTable.glyphClass(0xFFFF))
        assertNull(stateTable.glyphClass(-1))
        assertEquals(14, entry.newState)
        assertTrue(entry.hasOffset)
        assertTrue(entry.hasPush)
        assertTrue(entry.hasAdvance)
        assertFalse(entry.hasReset)
        assertEquals(26, entry.valueOffset.next().value)
        assertEquals(-30, stateTable.kerning(entry.valueOffset))
        assertEquals(0, stateTable.newState(entry.newState))
    }

    @Test
    fun iteratesMultipleSubtablesAndHonorsDeclaredLengths() {
        val first = openTypeSubtable(0, 1, format0Body(1, 2, -10))
        val second = openTypeSubtable(0, 0, format0Body(3, 4, -20))
        val iterator = parse(openTypeKern(first, second)).subtables.iterator()

        assertEquals(-10, iterator.next()!!.glyphsKerning(1, 2))
        assertEquals(-20, iterator.next()!!.glyphsKerning(3, 4))
        assertNull(iterator.next())
    }

    @Test
    fun malformedHeadersLengthsFormatsAndBodiesStopSafely() {
        assertNull(KernTable.parse(ByteSlice.of(ByteArray(3))))
        assertNull(KernTable.parse(ByteSlice.of(byteArrayOf(0, 1, 0, 0, 0, 0, 0))))

        val shortBody = openTypeKern(openTypeSubtable(0, 1, TestByteBuilder().uint16(1).zeroes(6).build()))
        assertNull(parse(shortBody).subtables.iterator().next())

        val unknownOpenType = openTypeKern(openTypeSubtable(1, 0, ByteArray(8)))
        assertNull(parse(unknownOpenType).subtables.iterator().next())

        val invalidAatLength = aatKern(aatSubtable(0, 0, format0Body(1, 2, 3), declaredLength = 7))
        assertNull(parse(invalidAatLength).subtables.iterator().next())

        val bounded = openTypeKern(openTypeSubtable(0, 1, format0Body(1, 2, 3)))
        val slice = ByteSlice.of(bounded + ByteArray(20)).slice(0, bounded.size - 1)!!
        assertNull(
            KernTable
                .parse(slice)!!
                .subtables
                .iterator()
                .next(),
        )
    }

    private fun parse(data: ByteArray): KernTable = requireNotNull(KernTable.parse(ByteSlice.of(data)))

    private fun openTypeKern(vararg subtables: ByteArray): ByteArray =
        TestByteBuilder()
            .uint16(0)
            .uint16(subtables.size)
            .apply { subtables.forEach(::raw) }
            .build()

    private fun openTypeSubtable(
        format: Int,
        coverage: Int,
        body: ByteArray,
        declaredLength: Int = body.size + 6,
    ): ByteArray =
        TestByteBuilder()
            .uint16(0)
            .uint16(declaredLength)
            .int8(format)
            .int8(coverage)
            .raw(body)
            .build()

    private fun aatKern(vararg subtables: ByteArray): ByteArray =
        TestByteBuilder()
            .uint32(0x0001_0000)
            .uint32(subtables.size.toLong())
            .apply { subtables.forEach(::raw) }
            .build()

    private fun aatSubtable(
        format: Int,
        coverage: Int,
        body: ByteArray,
        declaredLength: Long = body.size.toLong() + 8,
    ): ByteArray =
        TestByteBuilder()
            .uint32(declaredLength)
            .int8(coverage)
            .int8(format)
            .uint16(0)
            .raw(body)
            .build()

    private fun format0Body(
        left: Int,
        right: Int,
        value: Int,
    ): ByteArray =
        TestByteBuilder()
            .uint16(1)
            .zeroes(6)
            .uint16(left)
            .uint16(right)
            .int16(value)
            .build()
}
