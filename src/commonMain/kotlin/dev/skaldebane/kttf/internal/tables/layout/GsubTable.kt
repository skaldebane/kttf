package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal sealed interface SingleSubstitution {
    val coverage: Coverage

    data class Format1(
        override val coverage: Coverage,
        internal val delta: Int,
    ) : SingleSubstitution {
        internal fun substitute(glyph: GlyphId): GlyphId? {
            if (!coverage.contains(glyph)) return null
            return GlyphId((glyph.value + delta) and 0xFFFF)
        }
    }

    data class Format2(
        override val coverage: Coverage,
        internal val substitutes: UInt16View,
    ) : SingleSubstitution {
        internal fun substitute(glyph: GlyphId): GlyphId? =
            coverage.indexOf(glyph)?.let { substitutes[it] }?.let(::GlyphId)
    }
}

internal data class MultipleSubstitution(
    internal val coverage: Coverage,
    internal val sequences: Offset16View<GlyphSequence>,
)

internal data class GlyphSequence(
    internal val substitutes: UInt16View,
)

internal data class AlternateSubstitution(
    internal val coverage: Coverage,
    internal val alternateSets: Offset16View<AlternateSet>,
)

internal data class AlternateSet(
    internal val alternates: UInt16View,
)

internal data class LigatureSubstitution(
    internal val coverage: Coverage,
    internal val ligatureSets: Offset16View<LigatureSet>,
)

internal class LigatureSet private constructor(
    private val ligatures: Offset16View<Ligature>,
) {
    internal val size: Int get() = ligatures.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): Ligature? = ligatures[index]

    internal companion object {
        internal fun parse(data: ByteSlice): LigatureSet? {
            val count = data.readUInt16(0) ?: return null
            val offsets = UInt16View.parse(data, 2, count) ?: return null
            return LigatureSet(Offset16View(data, offsets, parse = ::parseLigature))
        }
    }
}

internal data class Ligature(
    internal val glyph: GlyphId,
    internal val components: UInt16View,
)

internal data class ReverseChainSingleSubstitution(
    internal val coverage: Coverage,
    internal val backtrackCoverages: Offset16View<Coverage>,
    internal val lookaheadCoverages: Offset16View<Coverage>,
    internal val substitutes: UInt16View,
)

internal sealed interface SubstitutionSubtable {
    val coverage: Coverage
    val isReverse: Boolean get() = this is ReverseChainSingle

    data class Single(
        internal val value: SingleSubstitution,
    ) : SubstitutionSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class Multiple(
        internal val value: MultipleSubstitution,
    ) : SubstitutionSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class Alternate(
        internal val value: AlternateSubstitution,
    ) : SubstitutionSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class LigatureSub(
        internal val value: LigatureSubstitution,
    ) : SubstitutionSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class Context(
        internal val value: ContextLookup,
    ) : SubstitutionSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class ChainContext(
        internal val value: ChainedContextLookup,
    ) : SubstitutionSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class ReverseChainSingle(
        internal val value: ReverseChainSingleSubstitution,
    ) : SubstitutionSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    companion object {
        internal fun parse(
            data: ByteSlice,
            kind: Int,
            extensionDepth: Int,
        ): SubstitutionSubtable? =
            when (kind) {
                1 -> parseSingle(data)?.let(::Single)
                2 -> parseMultiple(data)?.let(::Multiple)
                3 -> parseAlternate(data)?.let(::Alternate)
                4 -> parseLigatureSubstitution(data)?.let(::LigatureSub)
                5 -> ContextLookup.parse(data)?.let(::Context)
                6 -> ChainedContextLookup.parse(data)?.let(::ChainContext)
                7 -> parseExtension(data, extensionDepth)
                8 -> parseReverse(data)?.let(::ReverseChainSingle)
                else -> null
            }

        private fun parseExtension(
            data: ByteSlice,
            depth: Int,
        ): SubstitutionSubtable? {
            if (depth <= 0 || data.readUInt16(0) != 1) return null
            val kind = data.readUInt16(2) ?: return null
            val offset = data.readUInt32(4)?.toIndex() ?: return null
            return parse(data.sliceFrom(offset) ?: return null, kind, depth - 1)
        }
    }
}

internal typealias GsubTable = LayoutTable<SubstitutionSubtable>

internal fun parseGsub(data: ByteSlice): GsubTable? = LayoutTable.parse(data, SubstitutionSubtable::parse)

private fun parseSingle(data: ByteSlice): SingleSubstitution? =
    when (data.readUInt16(0)) {
        1 -> {
            SingleSubstitution.Format1(
                data.at16(2, Coverage::parse) ?: return null,
                data.readInt16(4) ?: return null,
            )
        }

        2 -> {
            val coverage = data.at16(2, Coverage::parse) ?: return null
            val count = data.readUInt16(4) ?: return null
            SingleSubstitution.Format2(coverage, UInt16View.parse(data, 6, count) ?: return null)
        }

        else -> {
            null
        }
    }

private fun parseMultiple(data: ByteSlice): MultipleSubstitution? {
    if (data.readUInt16(0) != 1) return null
    val coverage = data.at16(2, Coverage::parse) ?: return null
    val count = data.readUInt16(4) ?: return null
    val offsets = UInt16View.parse(data, 6, count) ?: return null
    return MultipleSubstitution(coverage, Offset16View(data, offsets, parse = ::parseGlyphSequence))
}

private fun parseGlyphSequence(data: ByteSlice): GlyphSequence? {
    val count = data.readUInt16(0) ?: return null
    return GlyphSequence(UInt16View.parse(data, 2, count) ?: return null)
}

private fun parseAlternate(data: ByteSlice): AlternateSubstitution? {
    if (data.readUInt16(0) != 1) return null
    val coverage = data.at16(2, Coverage::parse) ?: return null
    val count = data.readUInt16(4) ?: return null
    val offsets = UInt16View.parse(data, 6, count) ?: return null
    return AlternateSubstitution(coverage, Offset16View(data, offsets, parse = ::parseAlternateSet))
}

private fun parseAlternateSet(data: ByteSlice): AlternateSet? {
    val count = data.readUInt16(0) ?: return null
    return AlternateSet(UInt16View.parse(data, 2, count) ?: return null)
}

private fun parseLigatureSubstitution(data: ByteSlice): LigatureSubstitution? {
    if (data.readUInt16(0) != 1) return null
    val coverage = data.at16(2, Coverage::parse) ?: return null
    val count = data.readUInt16(4) ?: return null
    val offsets = UInt16View.parse(data, 6, count) ?: return null
    return LigatureSubstitution(coverage, Offset16View(data, offsets, parse = LigatureSet::parse))
}

private fun parseLigature(data: ByteSlice): Ligature? {
    val glyph = GlyphId(data.readUInt16(0) ?: return null)
    val componentCount = data.readUInt16(2) ?: return null
    if (componentCount == 0) return null
    return Ligature(glyph, UInt16View.parse(data, 4, componentCount - 1) ?: return null)
}

private fun parseReverse(data: ByteSlice): ReverseChainSingleSubstitution? {
    if (data.readUInt16(0) != 1) return null
    val coverage = data.at16(2, Coverage::parse) ?: return null
    var cursor = 4
    val backtrackCount = data.readUInt16(cursor) ?: return null
    cursor += 2
    val backtrack = UInt16View.parse(data, cursor, backtrackCount) ?: return null
    cursor += backtrackCount * 2
    val lookaheadCount = data.readUInt16(cursor) ?: return null
    cursor += 2
    val lookahead = UInt16View.parse(data, cursor, lookaheadCount) ?: return null
    cursor += lookaheadCount * 2
    val substituteCount = data.readUInt16(cursor) ?: return null
    cursor += 2
    return ReverseChainSingleSubstitution(
        coverage,
        Offset16View(data, backtrack, parse = Coverage::parse),
        Offset16View(data, lookahead, parse = Coverage::parse),
        UInt16View.parse(data, cursor, substituteCount) ?: return null,
    )
}
