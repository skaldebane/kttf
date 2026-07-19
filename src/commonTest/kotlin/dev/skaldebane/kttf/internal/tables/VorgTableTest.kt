package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VorgTableTest {
    @Test
    fun parsesDefaultAndSortedMetricView() {
        val table = parse(vorg(-500, 2 to 700, 8 to -200))!!

        assertEquals(-500, table.defaultY)
        assertEquals(2, table.metrics.size)
        assertEquals(VorgMetric(2, 700), table.metrics[0])
        assertEquals(VorgMetric(8, -200), table.metrics[1])
        assertNull(table.metrics[-1])
        assertNull(table.metrics[2])
        assertEquals(700, table.glyphYOrigin(2))
        assertEquals(-200, table.glyphYOrigin(8))
        assertEquals(-500, table.glyphYOrigin(4))
    }

    @Test
    fun metricsRemainBackedByTheBoundedSource() {
        val bytes = vorg(100, 3 to 200)
        val source = ByteArray(bytes.size + 4)
        bytes.copyInto(source, 2)
        val table = VorgTable.parse(ByteSlice.of(source).slice(2, bytes.size)!!)!!

        source[2 + 10] = 0xFE.toByte()
        source[2 + 11] = 0xD4.toByte()

        assertEquals(-300, table.metrics[0]!!.y)
        assertEquals(-300, table.glyphYOrigin(3))
    }

    @Test
    fun rejectsUnknownVersionAndTruncatedMetrics() {
        assertNull(parse(ByteArray(7)))
        assertNull(parse(vorg(0).also { it[3] = 1 }))

        val truncated =
            TestByteBuilder()
                .uint32(0x0001_0000)
                .int16(0)
                .uint16(2)
                .uint16(1)
                .int16(2)
                .build()
        assertNull(parse(truncated))

        val complete = vorg(0, 1 to 2)
        val bounded = ByteSlice.of(complete + byteArrayOf(0, 3)).slice(0, complete.size - 1)!!
        assertNull(VorgTable.parse(bounded))
    }

    @Test
    fun acceptsEmptyMetricsAndIgnoresTrailingData() {
        val table = parse(vorg(123) + ByteArray(7))!!

        assertEquals(0, table.metrics.size)
        assertEquals(123, table.glyphYOrigin(42))
    }

    private fun parse(data: ByteArray): VorgTable? = VorgTable.parse(ByteSlice.of(data))

    private fun vorg(
        defaultY: Int,
        vararg metrics: Pair<Int, Int>,
    ): ByteArray =
        TestByteBuilder()
            .uint32(0x0001_0000)
            .int16(defaultY)
            .uint16(metrics.size)
            .apply {
                metrics.forEach { (glyphId, y) ->
                    uint16(glyphId)
                    int16(y)
                }
            }.build()
}
