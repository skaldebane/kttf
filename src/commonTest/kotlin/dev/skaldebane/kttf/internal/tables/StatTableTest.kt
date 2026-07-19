package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatTableTest {
    @Test
    fun parsesAxesFallbackAndEveryAxisValueFormat() {
        val bytes =
            stat(
                version = VERSION_1_2,
                axes = arrayOf(Axis("wght", 256, 2), Axis("wdth", 257, 1)),
                fallbackNameId = 300,
                values =
                    arrayOf(
                        format1(0, 0x0003, 301, 400f),
                        format2(1, 0, 302, 100f, 75f, 125f),
                        format3(0, 0, 303, 700f, 400f),
                        format4(0x0002, 304, arrayOf(0 to 500f, 1 to 90f)),
                    ),
            )
        val table = parse(bytes)!!

        assertEquals(VERSION_1_2, table.version)
        assertEquals(300, table.fallbackNameId)
        assertEquals(2, table.axes.size)
        assertEquals(StatAxisRecord(Tag.parse("wght"), 256, 2), table.axes[0])
        assertEquals(StatAxisRecord(Tag.parse("wdth"), 257, 1), table.axes[1])

        val subtables = table.subtables()
        assertEquals(4, subtables.size)
        val first = assertIs<StatAxisValue.Format1>(subtables.next())
        assertEquals(400f, first.value)
        assertTrue(first.contains(400f))
        assertTrue(first.isElidable)
        assertTrue(first.isOlderSibling)

        val second = assertIs<StatAxisValue.Format2>(subtables.next())
        assertEquals(100f, second.nominalValue)
        assertNull(second.value)
        assertTrue(second.contains(75f))
        assertTrue(second.contains(124.5f))
        assertFalse(second.contains(125f))

        val third = assertIs<StatAxisValue.Format3>(subtables.next())
        assertEquals(700f, third.value)
        assertEquals(400f, third.linkedValue)

        val fourth = assertIs<StatAxisValue.Format4>(subtables.next())
        assertEquals(304, fourth.valueNameId)
        assertTrue(fourth.isElidable)
        assertEquals(2, fourth.values.size)
        assertEquals(StatAxisValuePair(0, 500f), fourth.values[0])
        assertEquals(StatAxisValuePair(1, 90f), fourth.values[1])
        assertFalse(fourth.contains(500f))
        assertNull(subtables.next())
    }

    @Test
    fun supportsAllPinnedVersionsAndRestrictsFormatFourToVersionOnePointTwo() {
        val version10 = parse(stat(VERSION_1_0, emptyArray(), null, emptyArray()))!!
        val version11 = parse(stat(VERSION_1_1, emptyArray(), 7, arrayOf(format4(0, 8, emptyArray()))))!!
        val version12 = parse(stat(VERSION_1_2, emptyArray(), 9, arrayOf(format4(0, 10, emptyArray()))))!!

        assertNull(version10.fallbackNameId)
        assertEquals(7, version11.fallbackNameId)
        assertNull(version11.subtables().next())
        assertIs<StatAxisValue.Format4>(version12.subtables().next())
    }

    @Test
    fun axisAndFormatFourPairViewsStayBinaryBacked() {
        val bytes =
            stat(
                VERSION_1_2,
                arrayOf(Axis("wght", 1, 0)),
                2,
                arrayOf(format4(0, 3, arrayOf(0 to 1f))),
            )
        val table = parse(bytes)!!
        bytes[20] = 'i'.code.toByte()
        val format4 = assertIs<StatAxisValue.Format4>(table.subtables().next())
        val pairValueOffset = bytes.size - 4
        writeFixed(bytes, pairValueOffset, 2.5f)

        assertEquals(Tag.parse("ight"), table.axes[0]!!.tag)
        assertEquals(2.5f, format4.values[0]!!.value)
    }

    @Test
    fun subtableLookupMatchesPinnedUpstreamSelectionRules() {
        val table =
            parse(
                stat(
                    VERSION_1_2,
                    arrayOf(Axis("wght", 1, 0), Axis("wdth", 2, 0)),
                    3,
                    arrayOf(
                        format1(0, 0, 4, 400f),
                        format2(1, 0, 5, 100f, 75f, 125f),
                        format3(0, 0, 6, 700f, 400f),
                    ),
                ),
            )!!

        assertEquals(4, table.subtableForAxis(Tag.parse("wght"), 400f)!!.valueNameId)
        assertEquals(6, table.subtableForAxis(Tag.parse("wght"), 700f)!!.valueNameId)
        assertNull(table.subtableForAxis(Tag.parse("wdth"), 100f))
        assertEquals(5, table.subtableForAxis(Tag.parse("wght"), 100f)!!.valueNameId)
    }

    @Test
    fun rejectsMalformedHeadersArraysOffsetsAndSubtablesWithinSlice() {
        assertNull(parse(ByteArray(17)))
        assertNull(parse(stat(0x0002_0000, emptyArray(), 0, emptyArray())))

        val valid = stat(VERSION_1_2, arrayOf(Axis("wght", 1, 0)), 2, arrayOf(format1(0, 0, 3, 1f)))
        assertNull(parse(valid.copyOf(19)))

        val invalidAxisOffset = valid.copyOf().also { writeUInt32(it, 8, 0xFFFF_FFFFL) }
        assertNull(parse(invalidAxisOffset))

        val invalidLookupOffset = valid.copyOf().also { writeUInt32(it, 14, 0xFFFF_FFFFL) }
        assertNull(parse(invalidLookupOffset))

        val truncatedSubtable = valid.copyOf(valid.size - 1)
        val table = parse(truncatedSubtable)!!
        assertNull(table.subtables().next())

        val bounded = ByteSlice.of(valid + ByteArray(20)).slice(0, valid.size - 1)!!
        assertNull(StatTable.parse(bounded)!!.subtables().next())
    }

    private fun parse(data: ByteArray): StatTable? = StatTable.parse(ByteSlice.of(data))

    private fun stat(
        version: Long,
        axes: Array<Axis>,
        fallbackNameId: Int?,
        values: Array<ByteArray>,
    ): ByteArray {
        val headerSize = if (version >= VERSION_1_1) 20 else 18
        val axisOffset = headerSize
        val lookupOffset = axisOffset + axes.size * 8
        var subtableOffset = lookupOffset + values.size * 2
        return TestByteBuilder()
            .uint32(version)
            .uint16(8)
            .uint16(axes.size)
            .uint32(axisOffset.toLong())
            .uint16(values.size)
            .uint32(lookupOffset.toLong())
            .apply { if (version >= VERSION_1_1) uint16(requireNotNull(fallbackNameId)) }
            .apply {
                axes.forEach { axis ->
                    raw(axis.tag.encodeToByteArray())
                    uint16(axis.nameId)
                    uint16(axis.ordering)
                }
            }.apply {
                values.forEach { value ->
                    uint16(subtableOffset - lookupOffset)
                    subtableOffset += value.size
                }
            }.apply { values.forEach(::raw) }
            .build()
    }

    private fun format1(
        axisIndex: Int,
        flags: Int,
        nameId: Int,
        value: Float,
    ): ByteArray =
        TestByteBuilder()
            .uint16(1)
            .uint16(axisIndex)
            .uint16(flags)
            .uint16(nameId)
            .fixed(value)
            .build()

    private fun format2(
        axisIndex: Int,
        flags: Int,
        nameId: Int,
        nominal: Float,
        minimum: Float,
        maximum: Float,
    ): ByteArray =
        TestByteBuilder()
            .uint16(2)
            .uint16(axisIndex)
            .uint16(flags)
            .uint16(nameId)
            .fixed(nominal)
            .fixed(minimum)
            .fixed(maximum)
            .build()

    private fun format3(
        axisIndex: Int,
        flags: Int,
        nameId: Int,
        value: Float,
        linkedValue: Float,
    ): ByteArray =
        TestByteBuilder()
            .uint16(3)
            .uint16(axisIndex)
            .uint16(flags)
            .uint16(nameId)
            .fixed(value)
            .fixed(linkedValue)
            .build()

    private fun format4(
        flags: Int,
        nameId: Int,
        values: Array<Pair<Int, Float>>,
    ): ByteArray =
        TestByteBuilder()
            .uint16(4)
            .uint16(values.size)
            .uint16(flags)
            .uint16(nameId)
            .apply {
                values.forEach { (axisIndex, value) ->
                    uint16(axisIndex)
                    fixed(value)
                }
            }.build()

    private fun TestByteBuilder.fixed(value: Float): TestByteBuilder =
        uint32((value * 65536f).toLong() and 0xFFFF_FFFFL)

    private fun writeFixed(
        bytes: ByteArray,
        offset: Int,
        value: Float,
    ) = writeUInt32(bytes, offset, (value * 65536f).toLong())

    private fun writeUInt32(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private data class Axis(
        val tag: String,
        val nameId: Int,
        val ordering: Int,
    )

    private companion object {
        private const val VERSION_1_0: Long = 0x0001_0000L
        private const val VERSION_1_1: Long = 0x0001_0001L
        private const val VERSION_1_2: Long = 0x0001_0002L
    }
}
