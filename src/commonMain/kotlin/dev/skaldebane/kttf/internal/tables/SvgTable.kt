package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal class SvgDocument internal constructor(
    private val bytes: ByteSlice,
    internal val startGlyphId: Int,
    internal val endGlyphId: Int,
) {
    internal val size: Int
        get() = bytes.size

    internal operator fun get(index: Int): Byte? = bytes.readByte(index)

    internal fun copyInto(
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): Boolean {
        if (destinationOffset < 0 || bytes.size > destination.size - destinationOffset) return false
        bytes.data.copyInto(destination, destinationOffset, bytes.start, bytes.end)
        return true
    }
}

internal class SvgTable private constructor(
    private val documentList: ByteSlice,
    private val records: FixedRecordArray,
) {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): SvgDocument? {
        val record = records[index] ?: return null
        val offset = record.readUInt32(4)?.toIndexOrNull() ?: return null
        val length = record.readUInt32(8)?.toIndexOrNull() ?: return null
        val bytes = documentList.slice(offset, length) ?: return null
        return SvgDocument(
            bytes,
            startGlyphId = record.readUInt16(0) ?: return null,
            endGlyphId = record.readUInt16(2) ?: return null,
        )
    }

    internal fun document(glyphId: Int): SvgDocument? {
        if (glyphId !in 0..0xFFFF) return null
        var index = 0
        while (index < records.size) {
            val record = records[index] ?: return null
            val start = record.readUInt16(0) ?: return null
            val end = record.readUInt16(2) ?: return null
            if (glyphId in start..end) return get(index)
            index++
        }
        return null
    }

    internal companion object {
        private const val RECORD_SIZE: Int = 12

        internal fun parse(data: ByteSlice): SvgTable? {
            data.readUInt16(0) ?: return null
            val listOffset = data.readUInt32(2)?.takeUnless { it == 0L }?.toIndexOrNull() ?: return null
            val list = data.sliceFrom(listOffset) ?: return null
            val count = list.readUInt16(0) ?: return null
            if (count > (list.size - 2).coerceAtLeast(0) / RECORD_SIZE) return null
            val recordBytes = list.slice(2, count * RECORD_SIZE) ?: return null
            val records = FixedRecordArray.create(recordBytes, count, RECORD_SIZE) ?: return null
            return SvgTable(list, records)
        }
    }
}

private fun Long.toIndexOrNull(): Int? = if (this <= Int.MAX_VALUE) toInt() else null
