package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.tables.variations.ItemVariationStore
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates

internal enum class GdefGlyphClass {
    Base,
    Ligature,
    Mark,
    Component,
}

internal class GdefTable private constructor(
    private val glyphClasses: ClassDefinition?,
    private val markAttachmentClasses: ClassDefinition?,
    private val markSetsData: ByteSlice?,
    private val markSetOffsets: UInt32View?,
    private val variationStore: ItemVariationStore?,
) {
    internal val hasGlyphClasses: Boolean get() = glyphClasses != null

    internal fun glyphClass(glyph: GlyphId): GdefGlyphClass? =
        when (glyphClasses?.classOf(glyph)) {
            1 -> GdefGlyphClass.Base
            2 -> GdefGlyphClass.Ligature
            3 -> GdefGlyphClass.Mark
            4 -> GdefGlyphClass.Component
            else -> null
        }

    internal fun markAttachmentClass(glyph: GlyphId): Int = markAttachmentClasses?.classOf(glyph) ?: 0

    internal fun isMarkGlyph(
        glyph: GlyphId,
        setIndex: Int? = null,
    ): Boolean {
        val data = markSetsData ?: return false
        val offsets = markSetOffsets ?: return false
        if (setIndex != null) {
            val coverage =
                Coverage.parse(data.sliceFrom(offsets[setIndex] ?: return false) ?: return false) ?: return false
            return coverage.contains(glyph)
        }
        for (index in 0 until offsets.size) {
            val coverage =
                Coverage.parse(data.sliceFrom(offsets[index] ?: return false) ?: return false) ?: return false
            if (coverage.contains(glyph)) return true
        }
        return false
    }

    internal fun variationDelta(
        outerIndex: Int,
        innerIndex: Int,
        coordinates: VariationCoordinates,
    ): Float? = variationStore?.delta(outerIndex, innerIndex, coordinates)

    internal companion object {
        private const val VERSION_1_0 = 0x0001_0000L
        private const val VERSION_1_2 = 0x0001_0002L
        private const val VERSION_1_3 = 0x0001_0003L

        internal fun parse(data: ByteSlice): GdefTable? {
            val version = data.readUInt32(0) ?: return null
            if (version != VERSION_1_0 && version != VERSION_1_2 && version != VERSION_1_3) return null
            val glyphOffset = data.readUInt16(4) ?: return null
            if (data.readUInt16(6) == null || data.readUInt16(8) == null) return null
            val markAttachmentOffset = data.readUInt16(10) ?: return null
            val markSetsOffset = if (version >= VERSION_1_2) data.readUInt16(12) ?: return null else 0
            val variationOffset = if (version >= VERSION_1_3) data.readUInt32(14) ?: return null else 0

            val rawMarkSetsData = if (markSetsOffset == 0) null else data.sliceFrom(markSetsOffset)
            val markSetOffsets =
                rawMarkSetsData?.let {
                    if (it.readUInt16(0) != 1) return@let null
                    UInt32View.parse(it, 4, it.readUInt16(2) ?: return@let null)
                }
            val markSetsData = rawMarkSetsData.takeIf { markSetOffsets != null }
            val variationData = variationOffset.toIndex()?.let(data::sliceFrom)
            return GdefTable(
                optionalClass(data, glyphOffset),
                optionalClass(data, markAttachmentOffset),
                markSetsData,
                markSetOffsets,
                if (variationOffset == 0L) null else variationData?.let(ItemVariationStore::parse),
            )
        }

        private fun optionalClass(
            data: ByteSlice,
            offset: Int,
        ): ClassDefinition? = if (offset == 0) null else data.sliceFrom(offset)?.let(ClassDefinition::parse)
    }
}
