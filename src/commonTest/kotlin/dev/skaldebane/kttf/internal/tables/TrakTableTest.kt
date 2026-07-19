package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrakTableTest {
    @Test
    fun parsesEmptyUpstreamFixture() {
        val table =
            parse(
                TestByteBuilder()
                    .fixed(1f)
                    .uint16(0)
                    .zeroes(6)
                    .build(),
            )

        assertEquals(0, table.horizontal.tracks.size)
        assertEquals(0, table.horizontal.sizes.size)
        assertEquals(0, table.vertical.tracks.size)
        assertEquals(0, table.vertical.sizes.size)
    }

    @Test
    fun readsUpstreamHorizontalTrackingFixture() {
        val data =
            TestByteBuilder()
                .fixed(1f)
                .uint16(0)
                .uint16(12)
                .uint16(0)
                .uint16(0)
                .uint16(3)
                .uint16(2)
                .uint32(44)
                .track(-1f, 256, 52)
                .track(0f, 258, 60)
                .track(1f, 257, 56)
                .fixed(12f)
                .fixed(24f)
                .int16(-15)
                .int16(-7)
                .int16(50)
                .int16(20)
                .int16(0)
                .int16(0)
                .build()
        val horizontal = parse(data).horizontal

        assertEquals(listOf(-1f, 0f, 1f), horizontal.tracks.map { it.value })
        assertEquals(listOf(256, 258, 257), horizontal.tracks.map { it.nameIndex })
        assertEquals(-15, horizontal.tracks[0]?.values?.get(0))
        assertEquals(-7, horizontal.tracks[0]?.values?.get(1))
        assertEquals(0, horizontal.tracks[1]?.values?.get(0))
        assertEquals(50, horizontal.tracks[2]?.values?.get(0))
        assertEquals(20, horizontal.tracks[2]?.values?.get(1))
        assertEquals(12f, horizontal.sizes[0])
        assertEquals(24f, horizontal.sizes[1])
    }

    @Test
    fun rejectsInvalidHeadersAndBoundsDeferredTrackValues() {
        assertNull(TrakTable.parse(ByteSlice.of(ByteArray(11))))
        assertNull(TrakTable.parse(ByteSlice.of(TestByteBuilder().uint32(0).zeroes(8).build())))
        assertNull(
            TrakTable.parse(
                ByteSlice.of(
                    TestByteBuilder()
                        .fixed(1f)
                        .uint16(1)
                        .zeroes(6)
                        .build(),
                ),
            ),
        )

        val data =
            TestByteBuilder()
                .fixed(1f)
                .uint16(0)
                .uint16(12)
                .uint16(0)
                .uint16(0)
                .uint16(1)
                .uint16(1)
                .uint32(28)
                .track(0f, 256, 100)
                .fixed(12f)
                .build()
        val table = parse(data)
        assertNull(table.horizontal.tracks[0])
        assertEquals(0, table.horizontal.tracks.count())
    }

    private fun parse(data: ByteArray): TrakTable = requireNotNull(TrakTable.parse(ByteSlice.of(data)))
}

private fun TestByteBuilder.fixed(value: Float): TestByteBuilder =
    uint32(
        (value * 65536f).toInt().toLong() and 0xFFFF_FFFFL,
    )

private fun TestByteBuilder.track(
    value: Float,
    nameIndex: Int,
    offset: Int,
): TestByteBuilder = fixed(value).uint16(nameIndex).uint16(offset)
