package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class DeltaSetIndex(
    internal val outerIndex: Int,
    internal val innerIndex: Int,
)

internal class DeltaSetIndexMap private constructor(
    private val data: ByteSlice,
    private val mapCount: Long,
    private val entrySize: Int,
    private val innerBitCount: Int,
    private val headerSize: Int,
) {
    internal fun map(index: Int): DeltaSetIndex? {
        if (index < 0) return null
        if (mapCount == 0L) return null

        val mappedIndex = minOf(index.toLong(), mapCount - 1)
        val entryOffset = headerSize.toLong() + mappedIndex * entrySize
        if (entryOffset > Int.MAX_VALUE) return null
        val entry = data.slice(entryOffset.toInt(), entrySize) ?: return null

        var packed = 0L
        var byteIndex = 0
        while (byteIndex < entrySize) {
            packed = (packed shl 8) or (entry.readUInt8(byteIndex)?.toLong() ?: return null)
            byteIndex++
        }

        val mask = (1L shl innerBitCount) - 1L
        val outerIndex = packed ushr innerBitCount
        val innerIndex = packed and mask
        if (outerIndex > 0xFFFF || innerIndex > 0xFFFF) return null
        return DeltaSetIndex(outerIndex.toInt(), innerIndex.toInt())
    }

    internal companion object {
        internal fun parse(data: ByteSlice): DeltaSetIndexMap? {
            val format = data.readUInt8(0) ?: return null
            val entryFormat = data.readUInt8(1) ?: return null
            val mapCount = if (format == 0) data.readUInt16(2)?.toLong() else data.readUInt32(2)
            mapCount ?: return null
            val entrySize = ((entryFormat ushr 4) and 3) + 1
            val innerBitCount = (entryFormat and 0x0F) + 1
            val headerSize = if (format == 0) 4 else 6
            return DeltaSetIndexMap(data, mapCount, entrySize, innerBitCount, headerSize)
        }
    }
}

internal class ItemVariationStore private constructor(
    private val data: ByteSlice,
    private val dataOffsets: ByteSlice,
    private val dataCount: Int,
    internal val regions: VariationRegionList,
) {
    internal fun regionIndices(outerIndex: Int): IntArray? {
        val subtable = dataSubtable(outerIndex) ?: return null
        val regionCount = subtable.readUInt16(4) ?: return null
        val indices = IntArray(regionCount)
        var index = 0
        while (index < regionCount) {
            indices[index] = subtable.readUInt16(6 + index * 2) ?: return null
            index++
        }
        return indices
    }

    internal fun delta(
        outerIndex: Int,
        innerIndex: Int,
        coordinates: VariationCoordinates,
    ): Float? {
        if (innerIndex < 0) return null
        val subtable = dataSubtable(outerIndex) ?: return null
        val itemCount = subtable.readUInt16(0) ?: return null
        val rawWordDeltaCount = subtable.readUInt16(2) ?: return null
        val regionIndexCount = subtable.readUInt16(4) ?: return null
        if (innerIndex >= itemCount) return null

        val wordDeltaCount = rawWordDeltaCount and 0x7FFF
        if (wordDeltaCount > regionIndexCount) return null
        val longWords = rawWordDeltaCount and 0x8000 != 0
        val rowSize = (regionIndexCount.toLong() + wordDeltaCount) * if (longWords) 2L else 1L
        val rowsOffset = 6L + regionIndexCount * 2L
        val rowOffset = rowsOffset + innerIndex * rowSize
        if (rowOffset > Int.MAX_VALUE || rowSize > Int.MAX_VALUE) return null
        if (!subtable.contains(rowOffset.toInt(), rowSize.toInt())) return null

        var delta = 0.0f
        var cursor = rowOffset.toInt()
        var index = 0
        while (index < regionIndexCount) {
            val regionIndex = subtable.readUInt16(6 + index * 2) ?: return null
            val value =
                if (index < wordDeltaCount) {
                    if (longWords) {
                        val result = subtable.readInt32(cursor)?.toFloat() ?: return null
                        cursor += 4
                        result
                    } else {
                        val result = subtable.readInt16(cursor)?.toFloat() ?: return null
                        cursor += 2
                        result
                    }
                } else if (longWords) {
                    val result = subtable.readInt16(cursor)?.toFloat() ?: return null
                    cursor += 2
                    result
                } else {
                    val result = subtable.readInt8(cursor)?.toFloat() ?: return null
                    cursor += 1
                    result
                }
            delta += value * regions.evaluate(regionIndex, coordinates)
            index++
        }
        return delta
    }

    private fun dataSubtable(index: Int): ByteSlice? {
        if (index !in 0 until dataCount) return null
        val offset = dataOffsets.readUInt32(index * 4) ?: return null
        if (offset > Int.MAX_VALUE) return null
        return data.sliceFrom(offset.toInt())
    }

    internal companion object {
        internal fun parse(data: ByteSlice): ItemVariationStore? {
            if (data.readUInt16(0) != 1) return null
            val regionListOffset = data.readUInt32(2) ?: return null
            if (regionListOffset > Int.MAX_VALUE) return null
            val dataCount = data.readUInt16(6) ?: return null
            val offsets = data.slice(8, dataCount * 4) ?: return null
            val regions = VariationRegionList.parse(data, regionListOffset.toInt()) ?: return null
            return ItemVariationStore(data, offsets, dataCount, regions)
        }
    }
}

internal class VariationRegionList private constructor(
    private val records: ByteSlice,
    internal val axisCount: Int,
    internal val size: Int,
) {
    internal fun evaluate(
        regionIndex: Int,
        coordinates: VariationCoordinates,
    ): Float {
        if (regionIndex !in 0 until size) return 0.0f
        var scalar = 1.0f
        var axisIndex = 0
        while (axisIndex < coordinates.size) {
            if (axisIndex >= axisCount) return 0.0f
            val offset = (regionIndex * axisCount + axisIndex) * REGION_AXIS_SIZE
            val start = records.readInt16(offset) ?: return 0.0f
            val peak = records.readInt16(offset + 2) ?: return 0.0f
            val end = records.readInt16(offset + 4) ?: return 0.0f
            val coordinate = coordinates[axisIndex]?.rawValue ?: return 0.0f
            val factor = evaluateAxis(start, peak, end, coordinate)
            if (factor == 0.0f) return 0.0f
            scalar *= factor
            axisIndex++
        }
        return scalar
    }

    internal companion object {
        private const val REGION_AXIS_SIZE: Int = 6

        internal fun parse(
            storeData: ByteSlice,
            offset: Int,
        ): VariationRegionList? {
            val axisCount = storeData.readUInt16(offset) ?: return null
            val regionCount = storeData.readUInt16(offset + 2) ?: return null
            val recordCount = axisCount.toLong() * regionCount
            if (recordCount > 0xFFFFL) return null
            val recordsSize = recordCount * REGION_AXIS_SIZE
            if (recordsSize > Int.MAX_VALUE) return null
            val records = storeData.slice(offset + 4, recordsSize.toInt()) ?: return null
            return VariationRegionList(records, axisCount, regionCount)
        }

        private fun evaluateAxis(
            start: Int,
            peak: Int,
            end: Int,
            coordinate: Int,
        ): Float {
            if (start > peak || peak > end) return 1.0f
            if (start < 0 && end > 0 && peak != 0) return 1.0f
            if (peak == 0 || coordinate == peak) return 1.0f
            if (coordinate <= start || end <= coordinate) return 0.0f
            return if (coordinate < peak) {
                (coordinate - start).toFloat() / (peak - start)
            } else {
                (end - coordinate).toFloat() / (end - peak)
            }
        }
    }
}
