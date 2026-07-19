package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class AnkrPoint(
    internal val x: Int,
    internal val y: Int,
)

internal class AnkrPoints internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
) {
    internal operator fun get(index: Int): AnkrPoint? {
        if (index !in 0 until size) return null
        val offset = index * 4
        return AnkrPoint(
            x = data.readInt16(offset) ?: return null,
            y = data.readInt16(offset + 2) ?: return null,
        )
    }
}

internal class AnkrTable private constructor(
    private val lookup: AatLookup,
    private val glyphData: ByteSlice,
) {
    internal fun points(glyphId: Int): AnkrPoints? {
        val offset = lookup.value(glyphId) ?: return null
        val count = glyphData.readUInt32(offset)?.toIntOrNull() ?: return null
        val points = glyphData.slice(offset + 4, count.checkedMultiply(4) ?: return null) ?: return null
        return AnkrPoints(points, count)
    }

    internal companion object {
        internal fun parse(
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): AnkrTable? {
            if (data.readUInt16(0) != 0) return null
            val lookupOffset = data.readUInt32(4)?.toIntOrNull() ?: return null
            val glyphDataOffset = data.readUInt32(8)?.toIntOrNull() ?: return null
            val lookupData = data.sliceFrom(lookupOffset) ?: return null
            return AnkrTable(
                lookup = AatLookup.parse(numberOfGlyphs, lookupData) ?: return null,
                glyphData = data.sliceFrom(glyphDataOffset) ?: return null,
            )
        }
    }
}

private fun Int.checkedMultiply(other: Int): Int? {
    val result = toLong() * other
    return if (result <= Int.MAX_VALUE) result.toInt() else null
}
