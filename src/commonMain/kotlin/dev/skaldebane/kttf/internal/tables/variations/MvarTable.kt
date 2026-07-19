package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class MvarTable private constructor(
    private val records: ByteSlice,
    private val recordCount: Int,
    private val variationStore: ItemVariationStore,
) {
    internal fun metricDelta(
        tag: Tag,
        coordinates: VariationCoordinates,
    ): Float? {
        var low = 0
        var high = recordCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val offset = middle * RECORD_SIZE
            val recordTag = records.readInt32(offset) ?: return null
            val comparison = (tag.value xor Int.MIN_VALUE).compareTo(recordTag xor Int.MIN_VALUE)
            when {
                comparison < 0 -> {
                    high = middle - 1
                }

                comparison > 0 -> {
                    low = middle + 1
                }

                else -> {
                    val outerIndex = records.readUInt16(offset + 4) ?: return null
                    val innerIndex = records.readUInt16(offset + 6) ?: return null
                    return variationStore.delta(outerIndex, innerIndex, coordinates)
                }
            }
        }
        return null
    }

    internal companion object {
        private const val RECORD_SIZE: Int = 8
        private const val VERSION_1_0: Long = 0x0001_0000L

        internal fun parse(data: ByteSlice): MvarTable? {
            if (data.readUInt32(0) != VERSION_1_0) return null
            if (data.readUInt16(6) != RECORD_SIZE) return null
            val recordCount = data.readUInt16(8) ?: return null
            if (recordCount == 0) return null
            val storeOffset = data.readUInt16(10) ?: return null
            if (storeOffset == 0) return null
            val records = data.slice(12, recordCount * RECORD_SIZE) ?: return null
            val store = ItemVariationStore.parse(data.sliceFrom(storeOffset) ?: return null) ?: return null
            return MvarTable(records, recordCount, store)
        }
    }
}
