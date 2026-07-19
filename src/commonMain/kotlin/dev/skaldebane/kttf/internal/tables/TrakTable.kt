package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal data class TrakTrack(
    internal val value: Float,
    internal val nameIndex: Int,
    internal val values: AatInt16View,
)

internal class TrakTracks internal constructor(
    private val tableData: ByteSlice,
    private val records: FixedRecordArray,
    private val sizesCount: Int,
) : Iterable<TrakTrack> {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): TrakTrack? {
        val record = records[index] ?: return null
        val valuesOffset = record.readUInt16(6) ?: return null
        val valuesData = tableData.sliceFrom(valuesOffset) ?: return null
        return TrakTrack(
            value = (record.readInt32(0) ?: return null).toFloat() / 65536f,
            nameIndex = record.readUInt16(4) ?: return null,
            values = AatInt16View.sized(valuesData, sizesCount) ?: return null,
        )
    }

    override fun iterator(): Iterator<TrakTrack> =
        object : Iterator<TrakTrack> {
            private var index = 0
            private var next = findNext()

            override fun hasNext(): Boolean = next != null

            override fun next(): TrakTrack {
                val result = next ?: throw NoSuchElementException()
                next = findNext()
                return result
            }

            private fun findNext(): TrakTrack? {
                if (index >= size) return null
                return get(index++)
            }
        }

    internal companion object {
        internal fun empty(): TrakTracks =
            TrakTracks(
                ByteSlice.of(ByteArray(0)),
                FixedRecordArray.create(ByteSlice.of(ByteArray(0)), 0, 8)!!,
                0,
            )
    }
}

internal data class TrakData(
    internal val tracks: TrakTracks,
    internal val sizes: TrakSizes,
) {
    internal companion object {
        internal val EMPTY: TrakData = TrakData(TrakTracks.empty(), TrakSizes(ByteSlice.of(ByteArray(0)), 0))
    }
}

internal class TrakSizes internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
) {
    internal operator fun get(index: Int): Float? {
        if (index !in 0 until size) return null
        return data.readInt32(index * 4)?.toFloat()?.div(65536f)
    }
}

internal class TrakTable private constructor(
    internal val horizontal: TrakData,
    internal val vertical: TrakData,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): TrakTable? {
            if (data.readUInt32(0) != 0x0001_0000L || data.readUInt16(4) != 0) return null
            val horizontalOffset = data.readUInt16(6) ?: return null
            val verticalOffset = data.readUInt16(8) ?: return null
            val horizontal =
                if (horizontalOffset == 0) {
                    TrakData.EMPTY
                } else {
                    parseData(data, horizontalOffset) ?: return null
                }
            val vertical = if (verticalOffset == 0) TrakData.EMPTY else parseData(data, verticalOffset) ?: return null
            return TrakTable(horizontal, vertical)
        }

        private fun parseData(
            data: ByteSlice,
            offset: Int,
        ): TrakData? {
            val tracksCount = data.readUInt16(offset) ?: return null
            val sizesCount = data.readUInt16(offset + 2) ?: return null
            val sizesOffset = data.readUInt32(offset + 4)?.toIntOrNull() ?: return null
            val recordsData = data.sliceFrom(offset + 8) ?: return null
            val records = FixedRecordArray.create(recordsData, tracksCount, 8) ?: return null
            val sizesData = data.slice(sizesOffset, sizesCount.checkedMultiply(4) ?: return null) ?: return null
            return TrakData(
                tracks = TrakTracks(data, records, sizesCount),
                sizes = TrakSizes(sizesData, sizesCount),
            )
        }
    }
}

private fun Int.checkedMultiply(other: Int): Int? {
    val result = toLong() * other
    return if (result <= Int.MAX_VALUE) result.toInt() else null
}
