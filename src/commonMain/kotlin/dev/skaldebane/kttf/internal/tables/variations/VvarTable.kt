package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class VvarTable private constructor(
    private val data: ByteSlice,
    private val variationStore: ItemVariationStore,
    private val advanceHeightMapOffset: Long?,
    private val topSideBearingMapOffset: Long?,
    private val bottomSideBearingMapOffset: Long?,
    private val verticalOriginMapOffset: Long?,
) {
    internal fun advanceHeightDelta(
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? {
        if (glyphId !in 0..0xFFFF) return null
        val index =
            if (advanceHeightMapOffset != null) {
                mappedIndex(advanceHeightMapOffset, glyphId) ?: return null
            } else {
                DeltaSetIndex(0, glyphId)
            }
        return variationStore.delta(index.outerIndex, index.innerIndex, coordinates)
    }

    internal fun topSideBearingDelta(
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? = mappedDelta(topSideBearingMapOffset ?: return null, glyphId, coordinates)

    internal fun bottomSideBearingDelta(
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? = mappedDelta(bottomSideBearingMapOffset ?: return null, glyphId, coordinates)

    internal fun verticalOriginDelta(
        glyphId: Int,
        coordinates: VariationCoordinates,
    ): Float? = mappedDelta(verticalOriginMapOffset ?: return null, glyphId, coordinates)

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
        internal fun parse(data: ByteSlice): VvarTable? {
            if (data.readUInt32(0) != VERSION_1_0) return null
            val storeOffset = data.readUInt32(4)?.checkedIntOffset(data) ?: return null
            val advanceHeightMapOffset = (data.readUInt32(8) ?: return null).takeUnless { it == 0L }
            val topSideBearingMapOffset = (data.readUInt32(12) ?: return null).takeUnless { it == 0L }
            val bottomSideBearingMapOffset = (data.readUInt32(16) ?: return null).takeUnless { it == 0L }
            val verticalOriginMapOffset = (data.readUInt32(20) ?: return null).takeUnless { it == 0L }
            val store = ItemVariationStore.parse(data.sliceFrom(storeOffset) ?: return null) ?: return null
            return VvarTable(
                data,
                store,
                advanceHeightMapOffset,
                topSideBearingMapOffset,
                bottomSideBearingMapOffset,
                verticalOriginMapOffset,
            )
        }

        private const val VERSION_1_0: Long = 0x0001_0000L
    }
}
