package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal data class VorgMetric(
    internal val glyphId: Int,
    internal val y: Int,
)

internal class VorgMetrics internal constructor(
    private val records: FixedRecordArray,
) {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): VorgMetric? {
        val record = records[index] ?: return null
        return VorgMetric(
            glyphId = record.readUInt16(0) ?: return null,
            y = record.readInt16(2) ?: return null,
        )
    }

    internal fun yOrigin(glyphId: Int): Int? {
        var remaining = size
        if (remaining == 0) return null
        var base = 0
        while (remaining > 1) {
            val half = remaining / 2
            val middle = base + half
            val current = records[middle]?.readUInt16(0) ?: return null
            if (current <= glyphId) base = middle
            remaining -= half
        }
        val record = records[base] ?: return null
        return record.readInt16(2)?.takeIf { record.readUInt16(0) == glyphId }
    }
}

internal class VorgTable private constructor(
    internal val defaultY: Int,
    internal val metrics: VorgMetrics,
) {
    internal fun glyphYOrigin(glyphId: Int): Int = metrics.yOrigin(glyphId) ?: defaultY

    internal companion object {
        private const val VERSION_1: Long = 0x0001_0000L
        private const val HEADER_SIZE: Int = 8
        private const val METRIC_SIZE: Int = 4

        internal fun parse(data: ByteSlice): VorgTable? {
            if (data.readUInt32(0) != VERSION_1) return null
            val defaultY = data.readInt16(4) ?: return null
            val count = data.readUInt16(6) ?: return null
            val recordsData = data.slice(HEADER_SIZE, count * METRIC_SIZE) ?: return null
            val records = FixedRecordArray.create(recordsData, count, METRIC_SIZE) ?: return null
            return VorgTable(defaultY, VorgMetrics(records))
        }
    }
}
