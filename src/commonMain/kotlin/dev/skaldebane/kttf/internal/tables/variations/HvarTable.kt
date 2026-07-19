package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class HvarTable private constructor(
    private val data: ByteSlice,
    private val variationStore: ItemVariationStore,
    private val advanceWidthMapOffset: Long?,
    private val leftSideBearingMapOffset: Long?,
    private val rightSideBearingMapOffset: Long?,
) {
    internal fun advanceWidthDelta(
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? {
        if (glyphId !in 0..0xFFFF) return null
        val index =
            if (advanceWidthMapOffset != null) {
                mappedIndex(advanceWidthMapOffset, glyphId) ?: return null
            } else {
                DeltaSetIndex(0, glyphId)
            }
        return variationStore.delta(index.outerIndex, index.innerIndex, coordinates)
    }

    internal fun leftSideBearingDelta(
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? = mappedDelta(leftSideBearingMapOffset ?: return null, glyphId, coordinates)

    internal fun rightSideBearingDelta(
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? = mappedDelta(rightSideBearingMapOffset ?: return null, glyphId, coordinates)

    private fun mappedDelta(
        offset: Long,
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? {
        if (glyphId !in 0..0xFFFF) return null
        val index = mappedIndex(offset, glyphId) ?: return null
        return variationStore.delta(index.outerIndex, index.innerIndex, coordinates)
    }

    private fun mappedIndex(
        offset: Long,
        glyphId: Int,
    ): DeltaSetIndex? {
        if (offset > Int.MAX_VALUE) return null
        return DeltaSetIndexMap.parse(data.sliceFrom(offset.toInt()) ?: return null)?.map(glyphId)
    }

    internal companion object {
        internal fun parse(data: ByteSlice): HvarTable? {
            if (data.readUInt32(0) != VERSION_1_0) return null
            val storeOffset = data.readUInt32(4)?.checkedIntOffset(data) ?: return null
            val advanceWidthMapOffset = (data.readUInt32(8) ?: return null).takeUnless { it == 0L }
            val leftSideBearingMapOffset = (data.readUInt32(12) ?: return null).takeUnless { it == 0L }
            val rightSideBearingMapOffset = (data.readUInt32(16) ?: return null).takeUnless { it == 0L }
            val store = ItemVariationStore.parse(data.sliceFrom(storeOffset) ?: return null) ?: return null
            return HvarTable(
                data,
                store,
                advanceWidthMapOffset,
                leftSideBearingMapOffset,
                rightSideBearingMapOffset,
            )
        }

        private const val VERSION_1_0: Long = 0x0001_0000L
    }
}

internal fun Long.checkedIntOffset(data: ByteSlice): Int? {
    if (this > Int.MAX_VALUE) return null
    return toInt().takeIf { data.contains(it, 0) }
}
