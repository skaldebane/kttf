package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class LocaTable private constructor(
    private val data: ByteSlice,
    private val longOffsets: Boolean,
    internal val size: Int,
) {
    internal fun glyphRange(glyphId: Int): LongRange? {
        if (glyphId !in 0 until 0xFFFF || glyphId + 1 >= size) return null
        val start = offset(glyphId) ?: return null
        val end = offset(glyphId + 1) ?: return null
        if (start >= end) return null
        return start until end
    }

    private fun offset(index: Int): Long? =
        if (longOffsets) {
            data.readUInt32(index * 4)
        } else {
            data.readUInt16(index * 2)?.toLong()?.times(2)
        }

    internal companion object {
        internal fun parse(
            glyphCount: Int,
            longOffsets: Boolean,
            data: ByteSlice,
        ): LocaTable? {
            if (glyphCount !in 1..0xFFFF) return null
            val entrySize = if (longOffsets) 4 else 2
            val available = data.size / entrySize
            if (available > 0xFFFF) return null
            val desired = if (glyphCount == 0xFFFF) glyphCount else glyphCount + 1
            val count = minOf(desired, available)
            val content = data.slice(0, count * entrySize) ?: return null
            return LocaTable(content, longOffsets, count)
        }
    }
}
