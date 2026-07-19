package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.AatExtendedStateTable
import dev.skaldebane.kttf.internal.tables.AnkrTable
import dev.skaldebane.kttf.internal.tables.FeatTable
import dev.skaldebane.kttf.internal.tables.KernFormat
import dev.skaldebane.kttf.internal.tables.KernStateEntry
import dev.skaldebane.kttf.internal.tables.KernStateTable
import dev.skaldebane.kttf.internal.tables.KernSubtable
import dev.skaldebane.kttf.internal.tables.KernTable
import dev.skaldebane.kttf.internal.tables.KerxEntryData
import dev.skaldebane.kttf.internal.tables.KerxFormat0
import dev.skaldebane.kttf.internal.tables.KerxFormat1
import dev.skaldebane.kttf.internal.tables.KerxFormat2
import dev.skaldebane.kttf.internal.tables.KerxFormat4
import dev.skaldebane.kttf.internal.tables.KerxSubtable
import dev.skaldebane.kttf.internal.tables.KerxTable
import dev.skaldebane.kttf.internal.tables.MorxChain
import dev.skaldebane.kttf.internal.tables.MorxChains
import dev.skaldebane.kttf.internal.tables.MorxContextualEntryData
import dev.skaldebane.kttf.internal.tables.MorxFeature
import dev.skaldebane.kttf.internal.tables.MorxFeatures
import dev.skaldebane.kttf.internal.tables.MorxInsertionEntryData
import dev.skaldebane.kttf.internal.tables.MorxSubtable
import dev.skaldebane.kttf.internal.tables.MorxSubtableKind
import dev.skaldebane.kttf.internal.tables.MorxSubtables
import dev.skaldebane.kttf.internal.tables.MorxTable
import dev.skaldebane.kttf.internal.tables.StatAxisValue
import dev.skaldebane.kttf.internal.tables.StatTable
import dev.skaldebane.kttf.internal.tables.TrakData
import dev.skaldebane.kttf.internal.tables.TrakTable

public data class FontPoint(
    public val x: Int,
    public val y: Int,
)

public enum class KerningFormat {
    Pair,
    StateMachine,
    Class,
    CompactClass,
    ExtendedClass,
    Anchor,
}

public data class KerningSubtableInfo(
    public val horizontal: Boolean,
    public val variable: Boolean,
    public val crossStream: Boolean,
    public val stateMachine: Boolean,
    public val format: KerningFormat,
)

public data class KerningPair(
    public val leftGlyph: GlyphId,
    public val rightGlyph: GlyphId,
    public val value: Int,
)

public data class KerningAnchor(
    public val markPointIndex: Int,
    public val currentPointIndex: Int,
)

public data class KerningStateEntry(
    public val newState: Int,
    public val flags: Int,
) {
    public val hasOffset: Boolean
        get() = flags and 0x3FFF != 0

    public val actionOffset: Int
        get() = flags and 0x3FFF

    public val nextActionOffset: Int
        get() = (actionOffset + 2) and 0xFFFF

    public val hasReset: Boolean
        get() = flags and 0x2000 != 0

    public val hasAdvance: Boolean
        get() = flags and 0x4000 == 0

    public val hasPush: Boolean
        get() = flags and 0x8000 != 0
}

/** Bounded query view over a classic AAT `kern` state table. */
public class KerningStateTable internal constructor(
    private val table: KernStateTable,
) {
    public val classCount: Int
        get() = table.numberOfClasses

    public val stateCount: Int
        get() = table.numberOfStates

    public val entryCount: Int
        get() = table.numberOfEntries

    public val actionCount: Int
        get() = table.numberOfActions

    public fun glyphClass(glyphId: GlyphId): Int? = table.glyphClass(glyphId.value)

    public fun entry(
        state: Int,
        glyphClass: Int,
    ): KerningStateEntry? = table.entry(state, glyphClass)?.toPublic()

    /** Resolves the state-table action at its byte offset. */
    public fun action(offset: Int): Int? =
        table.kerning(
            dev.skaldebane.kttf.internal.tables
                .KernValueOffset(offset),
        )

    public fun normalizedState(rawState: Int): Int = table.newState(rawState)
}

/** Parsed `kern` and `kerx` subtables in that order. */
public class KerningSubtables internal constructor(
    kern: KernTable?,
    kerx: KerxTable?,
) : Iterable<KerningSubtable> {
    private val values: List<KerningSubtable> =
        buildList {
            if (kern != null) {
                val iterator = kern.subtables.iterator()
                while (true) add(KerningSubtable(iterator.next() ?: break))
            }
            if (kerx != null) kerx.subtables.forEach { add(KerningSubtable(it)) }
        }

    public val size: Int
        get() = values.size

    public operator fun get(index: Int): KerningSubtable? = values.getOrNull(index)

    override fun iterator(): Iterator<KerningSubtable> = values.iterator()
}

public class KerningSubtable private constructor(
    private val kern: KernSubtable?,
    private val kerx: KerxSubtable?,
) {
    internal constructor(value: KernSubtable) : this(value, null)

    internal constructor(value: KerxSubtable) : this(null, value)

    public val info: KerningSubtableInfo
        get() =
            kern?.let {
                KerningSubtableInfo(
                    it.horizontal,
                    it.variable,
                    it.hasCrossStream,
                    it.hasStateMachine,
                    it.format.toPublic(),
                )
            } ?: kerx!!.let {
                KerningSubtableInfo(
                    it.horizontal,
                    it.variable,
                    it.hasCrossStream,
                    it.hasStateMachine,
                    when (it.format) {
                        is KerxFormat0 -> KerningFormat.Pair
                        is KerxFormat1 -> KerningFormat.StateMachine
                        is KerxFormat2 -> KerningFormat.Class
                        is KerxFormat4 -> KerningFormat.Anchor
                        else -> KerningFormat.ExtendedClass
                    },
                )
            }

    /** Kerx variation tuple count, or zero for classic `kern`. */
    public val tupleCount: Long
        get() = kerx?.tupleCount ?: 0L

    public val pairCount: Int
        get() = kern?.pairs?.size ?: (kerx?.format as? KerxFormat0)?.pairs?.size ?: 0

    public fun pair(index: Int): KerningPair? {
        kern?.pairs?.get(index)?.let { return KerningPair(GlyphId(it.leftGlyph), GlyphId(it.rightGlyph), it.value) }
        val pair = (kerx?.format as? KerxFormat0)?.pairs?.get(index) ?: return null
        return KerningPair(GlyphId(pair.leftGlyph), GlyphId(pair.rightGlyph), pair.value)
    }

    public val stateTable: KerningStateTable?
        get() = kern?.stateTable?.let(::KerningStateTable)

    public val extendedStateTable: AatStateTable?
        get() =
            when (val format = kerx?.format) {
                is KerxFormat1 -> AatStateTable.kerx(format.stateTable)
                is KerxFormat4 -> AatStateTable.kerx(format.stateTable)
                else -> null
            }

    public val actionCount: Int
        get() = (kerx?.format as? KerxFormat1)?.actionCount ?: 0

    public fun action(index: Int): Int? = (kerx?.format as? KerxFormat1)?.actionKerning(index)

    public val anchorCount: Int
        get() = (kerx?.format as? KerxFormat4)?.anchorPoints?.size ?: 0

    public fun anchor(index: Int): KerningAnchor? =
        (kerx?.format as? KerxFormat4)
            ?.anchorPoints
            ?.get(index)
            ?.let { KerningAnchor(it.first, it.second) }

    public fun kerning(
        leftGlyph: GlyphId,
        rightGlyph: GlyphId,
    ): Int? =
        kern?.glyphsKerning(leftGlyph.value, rightGlyph.value) ?: kerx?.glyphsKerning(leftGlyph.value, rightGlyph.value)
}

public data class StyleAxis(
    public val tag: Tag,
    public val nameId: Int,
    public val ordering: Int,
)

public data class StyleAxisValue(
    public val valueNameId: Int,
    public val axisValues: List<Pair<Int, Float>>,
    public val nominalValue: Float?,
    public val rangeMinimum: Float?,
    public val rangeMaximum: Float?,
    public val linkedValue: Float?,
    public val isElidable: Boolean,
    public val isOlderSibling: Boolean,
)

/** Parsed STAT design-axis and axis-value metadata. */
public class StyleAttributes internal constructor(
    private val table: StatTable,
) {
    public val fallbackNameId: Int?
        get() = table.fallbackNameId

    public val axisCount: Int
        get() = table.axes.size

    public fun axis(index: Int): StyleAxis? = table.axes[index]?.let { StyleAxis(it.tag, it.nameId, it.ordering) }

    public val valueCount: Int
        get() = table.subtables().size

    public fun value(index: Int): StyleAxisValue? {
        val iterator = table.subtables()
        repeat(index) { iterator.next() ?: return null }
        return iterator.next()?.toPublic()
    }
}

public data class AatFeatureSetting(
    public val value: Int,
    public val nameId: Int,
)

public data class AatFeature(
    public val feature: Int,
    public val nameId: Int,
    public val isExclusive: Boolean,
    public val defaultSettingIndex: Int,
    public val settings: List<AatFeatureSetting>,
)

public data class AatTrack(
    public val value: Float,
    public val nameId: Int,
    public val perSizeValues: List<Int>,
)

/** Raw extended AAT state-table entry plus the indices used by its subtable kind. */
public data class AatStateEntry(
    public val newState: Int,
    public val flags: Int,
    public val actionIndex: Int? = null,
    public val markIndex: Int? = null,
    public val currentIndex: Int? = null,
    public val currentInsertIndex: Int? = null,
    public val markedInsertIndex: Int? = null,
) {
    public val hasOffset: Boolean
        get() = flags and 0x3FFF != 0

    public val valueOffset: Int
        get() = flags and 0x3FFF

    public val hasReset: Boolean
        get() = flags and 0x2000 != 0

    public val hasAdvance: Boolean
        get() = flags and 0x4000 == 0

    public val hasPush: Boolean
        get() = flags and 0x8000 != 0

    public val hasMark: Boolean
        get() = hasPush
}

/** Bounded class, state, and entry queries shared by kerx and morx state tables. */
public class AatStateTable private constructor(
    public val classCount: Int,
    public val stateCount: Int,
    public val entryCount: Int,
    private val classLookup: (Int) -> Int?,
    private val entryLookup: (Int, Int) -> AatStateEntry?,
) {
    public fun glyphClass(glyphId: GlyphId): Int? = classLookup(glyphId.value)

    public fun entry(
        state: Int,
        glyphClass: Int,
    ): AatStateEntry? = entryLookup(state, glyphClass)

    internal companion object {
        internal fun kerx(table: AatExtendedStateTable<KerxEntryData>): AatStateTable =
            create(table) { entry -> entry.toPublic(actionIndex = entry.extra.actionIndex) }

        internal fun rearrangement(table: AatExtendedStateTable<Unit>): AatStateTable =
            create(table) { entry -> entry.toPublic() }

        internal fun contextual(table: AatExtendedStateTable<MorxContextualEntryData>): AatStateTable =
            create(table) { entry ->
                entry.toPublic(markIndex = entry.extra.markIndex, currentIndex = entry.extra.currentIndex)
            }

        internal fun ligature(table: AatExtendedStateTable<Int>): AatStateTable =
            create(table) { entry -> entry.toPublic(actionIndex = entry.extra) }

        internal fun insertion(table: AatExtendedStateTable<MorxInsertionEntryData>): AatStateTable =
            create(table) { entry ->
                entry.toPublic(
                    currentInsertIndex = entry.extra.currentInsertIndex,
                    markedInsertIndex = entry.extra.markedInsertIndex,
                )
            }

        private fun <T> create(
            table: AatExtendedStateTable<T>,
            convert: (dev.skaldebane.kttf.internal.tables.AatStateEntry<T>) -> AatStateEntry,
        ): AatStateTable =
            AatStateTable(
                table.numberOfClasses,
                table.numberOfStates,
                table.numberOfEntries,
                table::glyphClass,
            ) { state, glyphClass -> table.entry(state, glyphClass)?.let(convert) }
    }
}

public enum class AatMorxKind {
    Rearrangement,
    Contextual,
    Ligature,
    NonContextual,
    Insertion,
}

public data class AatMorxFeature(
    public val kind: Int,
    public val setting: Int,
    public val enableFlags: Long,
    public val disableFlags: Long,
)

public class AatMorxFeatures internal constructor(
    private val values: MorxFeatures,
) : Iterable<AatMorxFeature> {
    public val size: Int
        get() = values.size

    public operator fun get(index: Int): AatMorxFeature? = values[index]?.toPublic()

    override fun iterator(): Iterator<AatMorxFeature> =
        object : Iterator<AatMorxFeature> {
            private var index: Int = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): AatMorxFeature = get(index++) ?: throw NoSuchElementException()
        }
}

public sealed interface AatMorxSubtableData

public class AatMorxRearrangement internal constructor(
    public val stateTable: AatStateTable,
) : AatMorxSubtableData

public class AatMorxContextual internal constructor(
    public val stateTable: AatStateTable,
    private val table: dev.skaldebane.kttf.internal.tables.MorxContextualSubtable,
) : AatMorxSubtableData {
    public val lookupCount: Int
        get() = table.lookupCount

    public fun substitution(
        lookupIndex: Int,
        glyphId: GlyphId,
    ): GlyphId? = table.lookup(lookupIndex)?.value(glyphId.value)?.let(::GlyphId)
}

public class AatMorxLigature internal constructor(
    public val stateTable: AatStateTable,
    private val table: dev.skaldebane.kttf.internal.tables.MorxLigatureSubtable,
) : AatMorxSubtableData {
    public val actionCount: Int
        get() = table.ligatureActions.size

    public fun action(index: Int): Long? = table.ligatureActions[index]

    public val componentCount: Int
        get() = table.components.size

    public fun component(index: Int): Int? = table.components[index]

    public val ligatureCount: Int
        get() = table.ligatures.size

    public fun ligature(index: Int): GlyphId? = table.ligatures[index]?.let(::GlyphId)
}

public class AatMorxNonContextual internal constructor(
    private val lookup: dev.skaldebane.kttf.internal.tables.AatLookup,
) : AatMorxSubtableData {
    public fun substitution(glyphId: GlyphId): GlyphId? = lookup.value(glyphId.value)?.let(::GlyphId)
}

public class AatMorxInsertion internal constructor(
    public val stateTable: AatStateTable,
    private val table: dev.skaldebane.kttf.internal.tables.MorxInsertionSubtable,
) : AatMorxSubtableData {
    public val glyphCount: Int
        get() = table.glyphs.size

    public fun glyph(index: Int): GlyphId? = table.glyphs[index]?.let(::GlyphId)
}

public data class AatMorxSubtable(
    public val kind: AatMorxKind,
    public val featureFlags: Long,
    public val isLogical: Boolean,
    public val isAllDirections: Boolean,
    public val isBackwards: Boolean,
    public val isVertical: Boolean,
    public val data: AatMorxSubtableData,
)

public class AatMorxSubtables internal constructor(
    subtables: MorxSubtables,
) : Iterable<AatMorxSubtable> {
    private val values: List<MorxSubtable> = subtables.toList()

    public val size: Int
        get() = values.size

    public operator fun get(index: Int): AatMorxSubtable? = values.getOrNull(index)?.toPublic()

    override fun iterator(): Iterator<AatMorxSubtable> =
        object : Iterator<AatMorxSubtable> {
            private var index: Int = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): AatMorxSubtable = get(index++) ?: throw NoSuchElementException()
        }
}

public class AatMorxChain internal constructor(
    chain: MorxChain,
) {
    public val defaultFlags: Long = chain.defaultFlags
    public val features: AatMorxFeatures = AatMorxFeatures(chain.features)
    public val subtables: AatMorxSubtables = AatMorxSubtables(chain.subtables)
}

public class AatMorxChains internal constructor(
    chains: MorxChains?,
) : Iterable<AatMorxChain> {
    private val values: List<MorxChain> = chains?.toList().orEmpty()

    public val size: Int
        get() = values.size

    public operator fun get(index: Int): AatMorxChain? = values.getOrNull(index)?.let(::AatMorxChain)

    override fun iterator(): Iterator<AatMorxChain> =
        object : Iterator<AatMorxChain> {
            private var index: Int = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): AatMorxChain = get(index++) ?: throw NoSuchElementException()
        }
}

/** Query facade for implemented Apple Advanced Typography tables. */
public class AppleLayout internal constructor(
    private val ankr: AnkrTable?,
    private val feat: FeatTable?,
    private val trak: TrakTable?,
    private val morx: MorxTable?,
) {
    /** Parsed morx chains with bounded feature and subtable payload views. */
    public val morxChains: AatMorxChains = AatMorxChains(morx?.chains)

    public fun anchorPoints(glyphId: GlyphId): List<FontPoint>? {
        val points = ankr?.points(glyphId.value) ?: return null
        return List(points.size) { index -> points[index]?.let { FontPoint(it.x, it.y) } ?: return null }
    }

    public val featureCount: Int
        get() = feat?.names?.size ?: 0

    public fun feature(index: Int): AatFeature? {
        val value = feat?.names?.get(index) ?: return null
        return AatFeature(
            value.feature,
            value.nameIndex,
            value.exclusive,
            value.defaultSettingIndex,
            List(value.settingNames.size) { settingIndex ->
                val setting = value.settingNames[settingIndex] ?: return null
                AatFeatureSetting(setting.setting, setting.nameIndex)
            },
        )
    }

    public fun featureByKind(feature: Int): AatFeature? {
        val value = feat?.names?.find(feature) ?: return null
        return AatFeature(
            value.feature,
            value.nameIndex,
            value.exclusive,
            value.defaultSettingIndex,
            List(value.settingNames.size) { settingIndex ->
                val setting = value.settingNames[settingIndex] ?: return null
                AatFeatureSetting(setting.setting, setting.nameIndex)
            },
        )
    }

    public fun horizontalTrackSizes(): List<Float> =
        trak
            ?.horizontal
            ?.sizes
            ?.toPublic()
            .orEmpty()

    public fun verticalTrackSizes(): List<Float> =
        trak
            ?.vertical
            ?.sizes
            ?.toPublic()
            .orEmpty()

    public fun horizontalTracks(): List<AatTrack> = trak?.horizontal?.toPublic().orEmpty()

    public fun verticalTracks(): List<AatTrack> = trak?.vertical?.toPublic().orEmpty()

    public fun morxSubtables(): List<AatMorxSubtable> =
        morx
            ?.chains
            ?.flatMap { chain ->
                chain.subtables.map(MorxSubtable::toPublic)
            }.orEmpty()

    /** Applies the first matching non-contextual morx substitution. */
    public fun morxNonContextualSubstitution(glyphId: GlyphId): GlyphId? {
        val table = morx ?: return null
        for (chain in table.chains) {
            for (subtable in chain.subtables) {
                val kind = subtable.kind as? MorxSubtableKind.NonContextual ?: continue
                kind.lookup.value(glyphId.value)?.let { return GlyphId(it) }
            }
        }
        return null
    }
}

private fun KernFormat.toPublic(): KerningFormat =
    when (this) {
        KernFormat.FORMAT_0 -> KerningFormat.Pair
        KernFormat.FORMAT_1 -> KerningFormat.StateMachine
        KernFormat.FORMAT_2 -> KerningFormat.Class
        KernFormat.FORMAT_3 -> KerningFormat.CompactClass
    }

private fun KernStateEntry.toPublic(): KerningStateEntry = KerningStateEntry(newState, flags)

private fun <T> dev.skaldebane.kttf.internal.tables.AatStateEntry<T>.toPublic(
    actionIndex: Int? = null,
    markIndex: Int? = null,
    currentIndex: Int? = null,
    currentInsertIndex: Int? = null,
    markedInsertIndex: Int? = null,
): AatStateEntry =
    AatStateEntry(
        newState,
        flags,
        actionIndex,
        markIndex,
        currentIndex,
        currentInsertIndex,
        markedInsertIndex,
    )

private fun MorxFeature.toPublic(): AatMorxFeature = AatMorxFeature(kind, setting, enableFlags, disableFlags)

private fun MorxSubtable.toPublic(): AatMorxSubtable =
    AatMorxSubtable(
        kind.toPublic(),
        featureFlags,
        coverage.isLogical,
        coverage.isAllDirections,
        coverage.isBackwards,
        coverage.isVertical,
        when (val value = kind) {
            is MorxSubtableKind.Rearrangement -> {
                AatMorxRearrangement(AatStateTable.rearrangement(value.state))
            }

            is MorxSubtableKind.Contextual -> {
                AatMorxContextual(AatStateTable.contextual(value.table.state), value.table)
            }

            is MorxSubtableKind.Ligature -> {
                AatMorxLigature(AatStateTable.ligature(value.table.state), value.table)
            }

            is MorxSubtableKind.NonContextual -> {
                AatMorxNonContextual(value.lookup)
            }

            is MorxSubtableKind.Insertion -> {
                AatMorxInsertion(AatStateTable.insertion(value.table.state), value.table)
            }
        },
    )

private fun StatAxisValue.toPublic(): StyleAxisValue =
    when (this) {
        is StatAxisValue.Format1 -> {
            StyleAxisValue(valueNameId, listOf(axisIndex to value), value, null, null, null, isElidable, isOlderSibling)
        }

        is StatAxisValue.Format2 -> {
            StyleAxisValue(
                valueNameId,
                listOf(axisIndex to nominalValue),
                nominalValue,
                rangeMinValue,
                rangeMaxValue,
                null,
                isElidable,
                isOlderSibling,
            )
        }

        is StatAxisValue.Format3 -> {
            StyleAxisValue(
                valueNameId,
                listOf(axisIndex to value),
                value,
                null,
                null,
                linkedValue,
                isElidable,
                isOlderSibling,
            )
        }

        is StatAxisValue.Format4 -> {
            StyleAxisValue(
                valueNameId,
                List(values.size) {
                    values[it]?.let { pair -> pair.axisIndex to pair.value }
                        ?: throw NoSuchElementException()
                },
                null,
                null,
                null,
                null,
                isElidable,
                isOlderSibling,
            )
        }
    }

private fun TrakData.toPublic(): List<AatTrack> =
    List(tracks.size) { index ->
        val track = tracks[index] ?: throw NoSuchElementException()
        AatTrack(track.value, track.nameIndex, List(track.values.size) { track.values[it] ?: 0 })
    }

private fun dev.skaldebane.kttf.internal.tables.TrakSizes.toPublic(): List<Float> = List(size) { get(it) ?: 0f }

private fun MorxSubtableKind.toPublic(): AatMorxKind =
    when (this) {
        is MorxSubtableKind.Rearrangement -> AatMorxKind.Rearrangement
        is MorxSubtableKind.Contextual -> AatMorxKind.Contextual
        is MorxSubtableKind.Ligature -> AatMorxKind.Ligature
        is MorxSubtableKind.NonContextual -> AatMorxKind.NonContextual
        is MorxSubtableKind.Insertion -> AatMorxKind.Insertion
    }
