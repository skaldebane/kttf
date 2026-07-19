package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class MorxFeature(
    internal val kind: Int,
    internal val setting: Int,
    internal val enableFlags: Long,
    internal val disableFlags: Long,
)

internal class MorxFeatures internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
) {
    internal operator fun get(index: Int): MorxFeature? {
        if (index !in 0 until size) return null
        val offset = index * 12
        return MorxFeature(
            kind = data.readUInt16(offset) ?: return null,
            setting = data.readUInt16(offset + 2) ?: return null,
            enableFlags = data.readUInt32(offset + 4) ?: return null,
            disableFlags = data.readUInt32(offset + 8) ?: return null,
        )
    }
}

internal data class MorxContextualEntryData(
    internal val markIndex: Int,
    internal val currentIndex: Int,
)

internal class MorxContextualSubtable internal constructor(
    internal val state: AatExtendedStateTable<MorxContextualEntryData>,
    private val offsetsData: ByteSlice,
    private val offsets: AatUInt32View,
    private val numberOfGlyphs: Int,
) {
    internal val lookupCount: Int
        get() = offsets.size

    internal fun lookup(index: Int): AatLookup? {
        val offset = offsets[index]?.toIntOrNull() ?: return null
        return AatLookup.parse(numberOfGlyphs, offsetsData.sliceFrom(offset) ?: return null)
    }
}

internal class MorxLigatureSubtable internal constructor(
    internal val state: AatExtendedStateTable<Int>,
    internal val ligatureActions: AatUInt32View,
    internal val components: AatUInt16View,
    internal val ligatures: AatGlyphView,
)

internal data class MorxInsertionEntryData(
    internal val currentInsertIndex: Int,
    internal val markedInsertIndex: Int,
)

internal class MorxInsertionSubtable internal constructor(
    internal val state: AatExtendedStateTable<MorxInsertionEntryData>,
    internal val glyphs: AatGlyphView,
)

internal sealed interface MorxSubtableKind {
    class Rearrangement(
        internal val state: AatExtendedStateTable<Unit>,
    ) : MorxSubtableKind

    class Contextual(
        internal val table: MorxContextualSubtable,
    ) : MorxSubtableKind

    class Ligature(
        internal val table: MorxLigatureSubtable,
    ) : MorxSubtableKind

    class NonContextual(
        internal val lookup: AatLookup,
    ) : MorxSubtableKind

    class Insertion(
        internal val table: MorxInsertionSubtable,
    ) : MorxSubtableKind
}

internal class MorxCoverage internal constructor(
    private val value: Int,
) {
    internal val isLogical: Boolean
        get() = value and 0x10 != 0

    internal val isAllDirections: Boolean
        get() = value and 0x20 != 0

    internal val isBackwards: Boolean
        get() = value and 0x40 != 0

    internal val isVertical: Boolean
        get() = value and 0x80 != 0
}

internal data class MorxSubtable(
    internal val kind: MorxSubtableKind,
    internal val coverage: MorxCoverage,
    internal val featureFlags: Long,
)

internal class MorxSubtables internal constructor(
    private val count: Long,
    private val data: ByteSlice,
    private val numberOfGlyphs: Int,
) : Iterable<MorxSubtable> {
    override fun iterator(): Iterator<MorxSubtable> =
        object : Iterator<MorxSubtable> {
            private var index = 0L
            private var offset = 0
            private var next: MorxSubtable? = null
            private var prepared = false

            override fun hasNext(): Boolean {
                prepare()
                return next != null
            }

            override fun next(): MorxSubtable {
                prepare()
                val result = next ?: throw NoSuchElementException()
                next = null
                prepared = false
                return result
            }

            private fun prepare() {
                if (prepared) return
                prepared = true
                if (index == count || offset >= data.size) return
                val length = data.readUInt32(offset)?.toIntOrNull() ?: return
                if (length < SUBTABLE_HEADER_SIZE) return
                val coverage = data.readUInt8(offset + 4) ?: return
                val rawKind = data.readUInt8(offset + 7) ?: return
                val featureFlags = data.readUInt32(offset + 8) ?: return
                val body = data.slice(offset + SUBTABLE_HEADER_SIZE, length - SUBTABLE_HEADER_SIZE) ?: return
                val kind = parseKind(rawKind, body) ?: return
                next = MorxSubtable(kind, MorxCoverage(coverage), featureFlags)
                offset += length
                index++
            }

            private fun parseKind(
                rawKind: Int,
                data: ByteSlice,
            ): MorxSubtableKind? =
                when (rawKind) {
                    0 -> {
                        val state = AatExtendedStateTable.parse(numberOfGlyphs, data, 0) { Unit } ?: return null
                        MorxSubtableKind.Rearrangement(state)
                    }

                    1 -> {
                        MorxSubtableKind.Contextual(parseContextual(data) ?: return null)
                    }

                    2 -> {
                        MorxSubtableKind.Ligature(parseLigature(data) ?: return null)
                    }

                    4 -> {
                        val lookup = AatLookup.parse(numberOfGlyphs, data) ?: return null
                        MorxSubtableKind.NonContextual(lookup)
                    }

                    5 -> {
                        MorxSubtableKind.Insertion(parseInsertion(data) ?: return null)
                    }

                    else -> {
                        null
                    }
                }

            private fun parseContextual(data: ByteSlice): MorxContextualSubtable? {
                val state =
                    AatExtendedStateTable.parse(numberOfGlyphs, data, 4) { extra ->
                        MorxContextualEntryData(
                            markIndex = extra.readUInt16(0) ?: return@parse null,
                            currentIndex = extra.readUInt16(2) ?: return@parse null,
                        )
                    } ?: return null
                val offset = data.readUInt32(AatExtendedStateTable.HEADER_SIZE)?.toIntOrNull() ?: return null
                val offsetsData = data.sliceFrom(offset) ?: return null
                return MorxContextualSubtable(state, offsetsData, AatUInt32View(offsetsData), numberOfGlyphs)
            }

            private fun parseLigature(data: ByteSlice): MorxLigatureSubtable? {
                val state =
                    AatExtendedStateTable.parse(numberOfGlyphs, data, 2) { extra ->
                        extra.readUInt16(0)
                    } ?: return null
                val actionsOffset = data.readUInt32(AatExtendedStateTable.HEADER_SIZE)?.toIntOrNull() ?: return null
                val componentsOffset =
                    data
                        .readUInt32(AatExtendedStateTable.HEADER_SIZE + 4)
                        ?.toIntOrNull()
                        ?: return null
                val ligaturesOffset =
                    data
                        .readUInt32(AatExtendedStateTable.HEADER_SIZE + 8)
                        ?.toIntOrNull()
                        ?: return null
                return MorxLigatureSubtable(
                    state = state,
                    ligatureActions = AatUInt32View(data.sliceFrom(actionsOffset) ?: return null),
                    components = AatUInt16View(data.sliceFrom(componentsOffset) ?: return null),
                    ligatures = AatGlyphView(data.sliceFrom(ligaturesOffset) ?: return null),
                )
            }

            private fun parseInsertion(data: ByteSlice): MorxInsertionSubtable? {
                val state =
                    AatExtendedStateTable.parse(numberOfGlyphs, data, 4) { extra ->
                        MorxInsertionEntryData(
                            currentInsertIndex = extra.readUInt16(0) ?: return@parse null,
                            markedInsertIndex = extra.readUInt16(2) ?: return@parse null,
                        )
                    } ?: return null
                val glyphsOffset = data.readUInt32(AatExtendedStateTable.HEADER_SIZE)?.toIntOrNull() ?: return null
                return MorxInsertionSubtable(state, AatGlyphView(data.sliceFrom(glyphsOffset) ?: return null))
            }
        }
}

internal data class MorxChain(
    internal val defaultFlags: Long,
    internal val features: MorxFeatures,
    internal val subtables: MorxSubtables,
)

internal class MorxChains private constructor(
    private val count: Long,
    private val data: ByteSlice,
    private val numberOfGlyphs: Int,
) : Iterable<MorxChain> {
    override fun iterator(): Iterator<MorxChain> =
        object : Iterator<MorxChain> {
            private var index = 0L
            private var offset = 0
            private var next: MorxChain? = null
            private var prepared = false

            override fun hasNext(): Boolean {
                prepare()
                return next != null
            }

            override fun next(): MorxChain {
                prepare()
                val result = next ?: throw NoSuchElementException()
                next = null
                prepared = false
                return result
            }

            private fun prepare() {
                if (prepared) return
                prepared = true
                if (index == count || offset >= data.size) return
                val defaultFlags = data.readUInt32(offset) ?: return
                val length = data.readUInt32(offset + 4)?.toIntOrNull() ?: return
                val featuresCount = data.readUInt32(offset + 8)?.toIntOrNull() ?: return
                val subtablesCount = data.readUInt32(offset + 12) ?: return
                val featuresLength = featuresCount.toLong() * FEATURE_SIZE
                if (featuresLength > Int.MAX_VALUE) return
                val bodyLength = length.toLong() - CHAIN_HEADER_SIZE - featuresLength
                if (bodyLength !in 0..Int.MAX_VALUE.toLong()) return
                val featuresData = data.slice(offset + CHAIN_HEADER_SIZE, featuresLength.toInt()) ?: return
                val subtablesOffset = offset.toLong() + CHAIN_HEADER_SIZE + featuresLength
                if (subtablesOffset > Int.MAX_VALUE) return
                val subtablesData = data.slice(subtablesOffset.toInt(), bodyLength.toInt()) ?: return
                next =
                    MorxChain(
                        defaultFlags = defaultFlags,
                        features = MorxFeatures(featuresData, featuresCount),
                        subtables = MorxSubtables(subtablesCount, subtablesData, numberOfGlyphs),
                    )
                offset += length
                index++
            }
        }

    internal companion object {
        internal fun parse(
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): MorxChains? {
            if (numberOfGlyphs !in 1..0xFFFF) return null
            val count = data.readUInt32(4) ?: return null
            return MorxChains(count, data.sliceFrom(8) ?: return null, numberOfGlyphs)
        }
    }
}

internal class MorxTable private constructor(
    internal val chains: MorxChains,
) {
    internal companion object {
        internal fun parse(
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): MorxTable? = MorxChains.parse(numberOfGlyphs, data)?.let(::MorxTable)
    }
}

private const val FEATURE_SIZE: Int = 12
private const val CHAIN_HEADER_SIZE: Int = 16
private const val SUBTABLE_HEADER_SIZE: Int = 12
