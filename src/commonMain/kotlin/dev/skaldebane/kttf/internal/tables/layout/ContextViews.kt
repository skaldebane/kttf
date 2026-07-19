package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class SequenceLookupRecord(
    internal val sequenceIndex: Int,
    internal val lookupListIndex: Int,
)

internal class SequenceLookupRecords private constructor(
    private val data: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): SequenceLookupRecord? {
        if (index !in 0 until size) return null
        return SequenceLookupRecord(
            data.readUInt16(index * 4) ?: return null,
            data.readUInt16(index * 4 + 2) ?: return null,
        )
    }

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            offset: Int,
            count: Int,
        ): SequenceLookupRecords? =
            SequenceLookupRecords(data.slice(offset, count.checkedTimes(4) ?: return null) ?: return null, count)
    }
}

internal data class SequenceRule(
    internal val input: UInt16View,
    internal val lookups: SequenceLookupRecords,
)

internal class SequenceRuleSet private constructor(
    private val rules: Offset16View<SequenceRule>,
) {
    internal val size: Int get() = rules.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): SequenceRule? = rules[index]

    internal companion object {
        internal fun parse(data: ByteSlice): SequenceRuleSet? {
            val count = data.readUInt16(0) ?: return null
            val offsets = UInt16View.parse(data, 2, count) ?: return null
            return SequenceRuleSet(Offset16View(data, offsets, parse = ::parseSequenceRule))
        }
    }
}

internal sealed interface ContextLookup {
    val coverage: Coverage

    data class Format1(
        override val coverage: Coverage,
        internal val sets: Offset16View<SequenceRuleSet>,
    ) : ContextLookup

    data class Format2(
        override val coverage: Coverage,
        internal val classes: ClassDefinition,
        internal val sets: Offset16View<SequenceRuleSet>,
    ) : ContextLookup

    data class Format3(
        override val coverage: Coverage,
        internal val coverages: Offset16View<Coverage>,
        internal val lookups: SequenceLookupRecords,
    ) : ContextLookup

    companion object {
        internal fun parse(data: ByteSlice): ContextLookup? =
            when (data.readUInt16(0)) {
                1 -> {
                    val coverage = data.at16(2, Coverage::parse) ?: return null
                    val count = data.readUInt16(4) ?: return null
                    val offsets = UInt16View.parse(data, 6, count) ?: return null
                    Format1(coverage, Offset16View(data, offsets, parse = SequenceRuleSet::parse))
                }

                2 -> {
                    val coverage = data.at16(2, Coverage::parse) ?: return null
                    val classes = data.at16(4, ClassDefinition::parse) ?: return null
                    val count = data.readUInt16(6) ?: return null
                    val offsets = UInt16View.parse(data, 8, count) ?: return null
                    Format2(coverage, classes, Offset16View(data, offsets, parse = SequenceRuleSet::parse))
                }

                3 -> {
                    val inputCount = data.readUInt16(2) ?: return null
                    if (inputCount == 0) return null
                    val lookupCount = data.readUInt16(4) ?: return null
                    val coverage = data.at16(6, Coverage::parse) ?: return null
                    val offsets = UInt16View.parse(data, 8, inputCount - 1) ?: return null
                    val lookupsOffset = 6 + inputCount * 2
                    Format3(
                        coverage,
                        Offset16View(data, offsets, parse = Coverage::parse),
                        SequenceLookupRecords.parse(data, lookupsOffset, lookupCount) ?: return null,
                    )
                }

                else -> {
                    null
                }
            }
    }
}

internal data class ChainedSequenceRule(
    internal val backtrack: UInt16View,
    internal val input: UInt16View,
    internal val lookahead: UInt16View,
    internal val lookups: SequenceLookupRecords,
)

internal class ChainedSequenceRuleSet private constructor(
    private val rules: Offset16View<ChainedSequenceRule>,
) {
    internal val size: Int get() = rules.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): ChainedSequenceRule? = rules[index]

    internal companion object {
        internal fun parse(data: ByteSlice): ChainedSequenceRuleSet? {
            val count = data.readUInt16(0) ?: return null
            val offsets = UInt16View.parse(data, 2, count) ?: return null
            return ChainedSequenceRuleSet(Offset16View(data, offsets, parse = ::parseChainedSequenceRule))
        }
    }
}

internal sealed interface ChainedContextLookup {
    val coverage: Coverage

    data class Format1(
        override val coverage: Coverage,
        internal val sets: Offset16View<ChainedSequenceRuleSet>,
    ) : ChainedContextLookup

    data class Format2(
        override val coverage: Coverage,
        internal val backtrackClasses: ClassDefinition,
        internal val inputClasses: ClassDefinition,
        internal val lookaheadClasses: ClassDefinition,
        internal val sets: Offset16View<ChainedSequenceRuleSet>,
    ) : ChainedContextLookup

    data class Format3(
        override val coverage: Coverage,
        internal val backtrackCoverages: Offset16View<Coverage>,
        internal val inputCoverages: Offset16View<Coverage>,
        internal val lookaheadCoverages: Offset16View<Coverage>,
        internal val lookups: SequenceLookupRecords,
    ) : ChainedContextLookup

    companion object {
        internal fun parse(data: ByteSlice): ChainedContextLookup? =
            when (data.readUInt16(0)) {
                1 -> parseFormat1(data)
                2 -> parseFormat2(data)
                3 -> parseFormat3(data)
                else -> null
            }

        private fun parseFormat1(data: ByteSlice): ChainedContextLookup? {
            val coverage = data.at16(2, Coverage::parse) ?: return null
            val count = data.readUInt16(4) ?: return null
            val offsets = UInt16View.parse(data, 6, count) ?: return null
            return Format1(coverage, Offset16View(data, offsets, parse = ChainedSequenceRuleSet::parse))
        }

        private fun parseFormat2(data: ByteSlice): ChainedContextLookup? {
            val coverage = data.at16(2, Coverage::parse) ?: return null
            val backtrack = optionalClass(data, 4) ?: return null
            val input = optionalClass(data, 6) ?: return null
            val lookahead = optionalClass(data, 8) ?: return null
            val count = data.readUInt16(10) ?: return null
            val offsets = UInt16View.parse(data, 12, count) ?: return null
            return Format2(
                coverage,
                backtrack,
                input,
                lookahead,
                Offset16View(data, offsets, parse = ChainedSequenceRuleSet::parse),
            )
        }

        private fun parseFormat3(data: ByteSlice): ChainedContextLookup? {
            var cursor = 2
            val backtrackCount = data.readUInt16(cursor) ?: return null
            cursor += 2
            val backtrackOffsets = UInt16View.parse(data, cursor, backtrackCount) ?: return null
            cursor += backtrackCount * 2
            val inputCount = data.readUInt16(cursor) ?: return null
            if (inputCount == 0) return null
            cursor += 2
            val coverage = data.at16(cursor, Coverage::parse) ?: return null
            cursor += 2
            val inputOffsets = UInt16View.parse(data, cursor, inputCount - 1) ?: return null
            cursor += (inputCount - 1) * 2
            val lookaheadCount = data.readUInt16(cursor) ?: return null
            cursor += 2
            val lookaheadOffsets = UInt16View.parse(data, cursor, lookaheadCount) ?: return null
            cursor += lookaheadCount * 2
            val lookupCount = data.readUInt16(cursor) ?: return null
            cursor += 2
            return Format3(
                coverage,
                Offset16View(data, backtrackOffsets, parse = Coverage::parse),
                Offset16View(data, inputOffsets, parse = Coverage::parse),
                Offset16View(data, lookaheadOffsets, parse = Coverage::parse),
                SequenceLookupRecords.parse(data, cursor, lookupCount) ?: return null,
            )
        }

        private fun optionalClass(
            data: ByteSlice,
            fieldOffset: Int,
        ): ClassDefinition? {
            val offset = data.readUInt16(fieldOffset) ?: return null
            return if (offset ==
                0
            ) {
                ClassDefinition.Empty
            } else {
                ClassDefinition.parse(data.sliceFrom(offset) ?: return null)
            }
        }
    }
}

private fun parseSequenceRule(data: ByteSlice): SequenceRule? {
    val inputCount = data.readUInt16(0) ?: return null
    if (inputCount == 0) return null
    val lookupCount = data.readUInt16(2) ?: return null
    val input = UInt16View.parse(data, 4, inputCount - 1) ?: return null
    val lookups = SequenceLookupRecords.parse(data, 4 + (inputCount - 1) * 2, lookupCount) ?: return null
    return SequenceRule(input, lookups)
}

private fun parseChainedSequenceRule(data: ByteSlice): ChainedSequenceRule? {
    var cursor = 0
    val backtrackCount = data.readUInt16(cursor) ?: return null
    cursor += 2
    val backtrack = UInt16View.parse(data, cursor, backtrackCount) ?: return null
    cursor += backtrackCount * 2
    val inputCount = data.readUInt16(cursor) ?: return null
    if (inputCount == 0) return null
    cursor += 2
    val input = UInt16View.parse(data, cursor, inputCount - 1) ?: return null
    cursor += (inputCount - 1) * 2
    val lookaheadCount = data.readUInt16(cursor) ?: return null
    cursor += 2
    val lookahead = UInt16View.parse(data, cursor, lookaheadCount) ?: return null
    cursor += lookaheadCount * 2
    val lookupCount = data.readUInt16(cursor) ?: return null
    cursor += 2
    return ChainedSequenceRule(
        backtrack,
        input,
        lookahead,
        SequenceLookupRecords.parse(data, cursor, lookupCount) ?: return null,
    )
}
