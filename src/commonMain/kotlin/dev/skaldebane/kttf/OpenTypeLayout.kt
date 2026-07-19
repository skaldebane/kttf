package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.layout.Anchor
import dev.skaldebane.kttf.internal.tables.layout.ChainedContextLookup
import dev.skaldebane.kttf.internal.tables.layout.ChainedSequenceRule
import dev.skaldebane.kttf.internal.tables.layout.Condition
import dev.skaldebane.kttf.internal.tables.layout.ContextLookup
import dev.skaldebane.kttf.internal.tables.layout.Coverage
import dev.skaldebane.kttf.internal.tables.layout.Device
import dev.skaldebane.kttf.internal.tables.layout.Feature
import dev.skaldebane.kttf.internal.tables.layout.GdefGlyphClass
import dev.skaldebane.kttf.internal.tables.layout.GdefTable
import dev.skaldebane.kttf.internal.tables.layout.GposTable
import dev.skaldebane.kttf.internal.tables.layout.GsubTable
import dev.skaldebane.kttf.internal.tables.layout.LayoutTable
import dev.skaldebane.kttf.internal.tables.layout.Lookup
import dev.skaldebane.kttf.internal.tables.layout.PairAdjustment
import dev.skaldebane.kttf.internal.tables.layout.PositioningSubtable
import dev.skaldebane.kttf.internal.tables.layout.SequenceLookupRecords
import dev.skaldebane.kttf.internal.tables.layout.SequenceRule
import dev.skaldebane.kttf.internal.tables.layout.SingleAdjustment
import dev.skaldebane.kttf.internal.tables.layout.SingleSubstitution
import dev.skaldebane.kttf.internal.tables.layout.SubstitutionSubtable
import dev.skaldebane.kttf.internal.tables.layout.UInt16View
import dev.skaldebane.kttf.internal.tables.layout.ValueRecord
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates

public enum class GlyphDefinitionClass { Base, Ligature, Mark, Component }

/** High-level queries from the Glyph Definition table. */
public class GlyphDefinitions internal constructor(
    private val table: GdefTable,
    private val coordinates: VariationCoordinates,
) {
    public val hasGlyphClasses: Boolean get() = table.hasGlyphClasses

    public fun glyphClass(glyphId: GlyphId): GlyphDefinitionClass? = table.glyphClass(glyphId)?.toPublic()

    public fun markAttachmentClass(glyphId: GlyphId): Int = table.markAttachmentClass(glyphId)

    public fun isMarkGlyph(
        glyphId: GlyphId,
        setIndex: Int? = null,
    ): Boolean = table.isMarkGlyph(glyphId, setIndex)

    public fun variationDelta(
        outerIndex: Int,
        innerIndex: Int,
    ): Float? = table.variationDelta(outerIndex, innerIndex, coordinates)
}

public data class LayoutLanguage(
    public val tag: Tag,
    public val requiredFeatureIndex: Int?,
    public val featureIndices: List<Int>,
)

public data class LayoutScript(
    public val tag: Tag,
    public val defaultLanguage: LayoutLanguage?,
    public val languages: List<LayoutLanguage>,
)

public data class LayoutFeature(
    public val tag: Tag,
    public val lookupIndices: List<Int>,
)

/** A bounded coverage-table query. */
public class LayoutCoverage internal constructor(
    private val coverage: Coverage,
) {
    public operator fun contains(glyphId: GlyphId): Boolean = coverage.contains(glyphId)

    public fun indexOf(glyphId: GlyphId): Int? = coverage.indexOf(glyphId)
}

/** Device table or VariationIndex attached to a positioning or MATH value. */
public class LayoutDevice internal constructor(
    private val device: Device,
) {
    public val startSize: Int? get() = (device as? Device.Hinting)?.startSize
    public val endSize: Int? get() = (device as? Device.Hinting)?.endSize
    public val outerVariationIndex: Int? get() = (device as? Device.Variation)?.outerIndex
    public val innerVariationIndex: Int? get() = (device as? Device.Variation)?.innerIndex
    public val isVariationIndex: Boolean get() = device is Device.Variation

    /** Returns the design-unit hinting delta at [pixelsPerEm], or null for a VariationIndex. */
    public fun delta(
        pixelsPerEm: Int,
        unitsPerEm: Int,
    ): Int? = (device as? Device.Hinting)?.delta(pixelsPerEm, unitsPerEm)
}

public data class GlyphPositionAdjustment(
    public val xPlacement: Int = 0,
    public val yPlacement: Int = 0,
    public val xAdvance: Int = 0,
    public val yAdvance: Int = 0,
    public val xPlacementDevice: LayoutDevice? = null,
    public val yPlacementDevice: LayoutDevice? = null,
    public val xAdvanceDevice: LayoutDevice? = null,
    public val yAdvanceDevice: LayoutDevice? = null,
)

public data class LayoutAnchor(
    public val x: Int,
    public val y: Int,
    public val contourPoint: Int?,
    public val xDevice: LayoutDevice?,
    public val yDevice: LayoutDevice?,
)

public data class LayoutLookupFlags(
    public val rightToLeft: Boolean,
    public val ignoreBaseGlyphs: Boolean,
    public val ignoreLigatures: Boolean,
    public val ignoreMarks: Boolean,
    public val markAttachmentType: Int,
    public val markFilteringSet: Int?,
)

public data class LayoutLookup(
    public val subtableCount: Int,
    public val flags: LayoutLookupFlags,
)

public data class LayoutSequenceLookup(
    public val sequenceIndex: Int,
    public val lookupIndex: Int,
)

/** A format 1/2 contextual rule. Input values omit the first glyph/class selected by coverage. */
public data class LayoutContextRule(
    public val input: List<Int>,
    public val lookups: List<LayoutSequenceLookup>,
)

public data class LayoutChainedContextRule(
    public val backtrack: List<Int>,
    public val input: List<Int>,
    public val lookahead: List<Int>,
    public val lookups: List<LayoutSequenceLookup>,
)

public enum class LayoutContextFormat { Glyphs, Classes, Coverages }

/** Bounded view of a contextual lookup shared by GSUB and GPOS. */
public class LayoutContext internal constructor(
    private val value: ContextLookup,
) {
    public val format: LayoutContextFormat
        get() =
            when (value) {
                is ContextLookup.Format1 -> LayoutContextFormat.Glyphs
                is ContextLookup.Format2 -> LayoutContextFormat.Classes
                is ContextLookup.Format3 -> LayoutContextFormat.Coverages
            }
    public val coverage: LayoutCoverage get() = LayoutCoverage(value.coverage)
    public val ruleSetCount: Int
        get() =
            when (value) {
                is ContextLookup.Format1 -> value.sets.size
                is ContextLookup.Format2 -> value.sets.size
                is ContextLookup.Format3 -> 0
            }
    public val inputCoverageCount: Int get() = if (value is ContextLookup.Format3) value.coverages.size + 1 else 0

    public fun inputClass(glyphId: GlyphId): Int? = (value as? ContextLookup.Format2)?.classes?.classOf(glyphId)

    public fun rule(
        setIndex: Int,
        ruleIndex: Int,
    ): LayoutContextRule? {
        val rule =
            when (val context = value) {
                is ContextLookup.Format1 -> context.sets[setIndex]?.get(ruleIndex)
                is ContextLookup.Format2 -> context.sets[setIndex]?.get(ruleIndex)
                is ContextLookup.Format3 -> null
            } ?: return null
        return rule.toPublic()
    }

    public fun inputCoverage(index: Int): LayoutCoverage? {
        val context = value as? ContextLookup.Format3 ?: return null
        if (index == 0) return coverage
        return context.coverages[index - 1]?.let(::LayoutCoverage)
    }

    public fun coverageLookups(): List<LayoutSequenceLookup>? = (value as? ContextLookup.Format3)?.lookups?.toPublic()
}

/** Bounded view of a chained contextual lookup shared by GSUB and GPOS. */
public class LayoutChainedContext internal constructor(
    private val value: ChainedContextLookup,
) {
    public val format: LayoutContextFormat
        get() =
            when (value) {
                is ChainedContextLookup.Format1 -> LayoutContextFormat.Glyphs
                is ChainedContextLookup.Format2 -> LayoutContextFormat.Classes
                is ChainedContextLookup.Format3 -> LayoutContextFormat.Coverages
            }
    public val coverage: LayoutCoverage get() = LayoutCoverage(value.coverage)
    public val ruleSetCount: Int
        get() =
            when (value) {
                is ChainedContextLookup.Format1 -> value.sets.size
                is ChainedContextLookup.Format2 -> value.sets.size
                is ChainedContextLookup.Format3 -> 0
            }
    public val backtrackCoverageCount: Int
        get() = (value as? ChainedContextLookup.Format3)?.backtrackCoverages?.size ?: 0
    public val inputCoverageCount: Int
        get() = (value as? ChainedContextLookup.Format3)?.inputCoverages?.size?.plus(1) ?: 0
    public val lookaheadCoverageCount: Int
        get() = (value as? ChainedContextLookup.Format3)?.lookaheadCoverages?.size ?: 0

    public fun backtrackClass(glyphId: GlyphId): Int? =
        (value as? ChainedContextLookup.Format2)?.backtrackClasses?.classOf(glyphId)

    public fun inputClass(glyphId: GlyphId): Int? =
        (value as? ChainedContextLookup.Format2)?.inputClasses?.classOf(glyphId)

    public fun lookaheadClass(glyphId: GlyphId): Int? =
        (value as? ChainedContextLookup.Format2)?.lookaheadClasses?.classOf(glyphId)

    public fun rule(
        setIndex: Int,
        ruleIndex: Int,
    ): LayoutChainedContextRule? {
        val rule =
            when (val context = value) {
                is ChainedContextLookup.Format1 -> context.sets[setIndex]?.get(ruleIndex)
                is ChainedContextLookup.Format2 -> context.sets[setIndex]?.get(ruleIndex)
                is ChainedContextLookup.Format3 -> null
            } ?: return null
        return rule.toPublic()
    }

    public fun backtrackCoverage(index: Int): LayoutCoverage? =
        (value as? ChainedContextLookup.Format3)?.backtrackCoverages?.get(index)?.let(::LayoutCoverage)

    public fun inputCoverage(index: Int): LayoutCoverage? {
        val context = value as? ChainedContextLookup.Format3 ?: return null
        if (index == 0) return coverage
        return context.inputCoverages[index - 1]?.let(::LayoutCoverage)
    }

    public fun lookaheadCoverage(index: Int): LayoutCoverage? =
        (value as? ChainedContextLookup.Format3)?.lookaheadCoverages?.get(index)?.let(::LayoutCoverage)

    public fun coverageLookups(): List<LayoutSequenceLookup>? =
        (value as? ChainedContextLookup.Format3)?.lookups?.toPublic()
}

public data class FeatureVariationCondition(
    public val axisIndex: Int,
    public val minimum: Float,
    public val maximum: Float,
)

/** Shared script, language, feature, lookup, and FeatureVariations queries. */
public sealed class OpenTypeLayout protected constructor() {
    public abstract val scriptCount: Int
    public abstract val featureCount: Int
    public abstract val lookupCount: Int
    public abstract val featureVariationCount: Int

    public abstract fun script(index: Int): LayoutScript?

    public abstract fun script(tag: Tag): LayoutScript?

    public abstract fun feature(index: Int): LayoutFeature?

    public abstract fun feature(tag: Tag): LayoutFeature?

    public abstract fun lookup(index: Int): LayoutLookup?

    public abstract fun matchingFeatureVariationIndex(): Int?

    public abstract fun featureVariationConditionCount(variationIndex: Int): Int?

    public abstract fun featureVariationCondition(
        variationIndex: Int,
        conditionIndex: Int,
    ): FeatureVariationCondition?

    public abstract fun featureSubstitution(
        variationIndex: Int,
        featureIndex: Int,
    ): LayoutFeature?
}

public enum class GlyphSubstitutionKind {
    Single,
    Multiple,
    Alternate,
    Ligature,
    Context,
    ChainedContext,
    ReverseChainedSingle,
}

public data class LigatureSubstitution(
    public val glyphId: GlyphId,
    public val components: List<GlyphId>,
)

/** One parsed GSUB subtable. Methods not applicable to [kind] return null. */
public class GlyphSubstitutionSubtable internal constructor(
    private val value: SubstitutionSubtable,
) {
    public val kind: GlyphSubstitutionKind
        get() =
            when (value) {
                is SubstitutionSubtable.Single -> GlyphSubstitutionKind.Single
                is SubstitutionSubtable.Multiple -> GlyphSubstitutionKind.Multiple
                is SubstitutionSubtable.Alternate -> GlyphSubstitutionKind.Alternate
                is SubstitutionSubtable.LigatureSub -> GlyphSubstitutionKind.Ligature
                is SubstitutionSubtable.Context -> GlyphSubstitutionKind.Context
                is SubstitutionSubtable.ChainContext -> GlyphSubstitutionKind.ChainedContext
                is SubstitutionSubtable.ReverseChainSingle -> GlyphSubstitutionKind.ReverseChainedSingle
            }
    public val coverage: LayoutCoverage get() = LayoutCoverage(value.coverage)
    public val reverseBacktrackCoverageCount: Int
        get() = (value as? SubstitutionSubtable.ReverseChainSingle)?.value?.backtrackCoverages?.size ?: 0
    public val reverseLookaheadCoverageCount: Int
        get() = (value as? SubstitutionSubtable.ReverseChainSingle)?.value?.lookaheadCoverages?.size ?: 0

    public fun substitute(glyphId: GlyphId): GlyphId? =
        when (val single = (value as? SubstitutionSubtable.Single)?.value) {
            is SingleSubstitution.Format1 -> single.substitute(glyphId)
            is SingleSubstitution.Format2 -> single.substitute(glyphId)
            null -> null
        }

    public fun multipleSubstitution(glyphId: GlyphId): List<GlyphId>? {
        val multiple = (value as? SubstitutionSubtable.Multiple)?.value ?: return null
        val sequence = multiple.sequences[multiple.coverage.indexOf(glyphId) ?: return null] ?: return null
        return sequence.substitutes.toGlyphIds()
    }

    public fun alternates(glyphId: GlyphId): List<GlyphId>? {
        val alternate = (value as? SubstitutionSubtable.Alternate)?.value ?: return null
        return alternate.alternateSets[alternate.coverage.indexOf(glyphId) ?: return null]?.alternates?.toGlyphIds()
    }

    public fun ligatures(glyphId: GlyphId): List<LigatureSubstitution>? {
        val ligature = (value as? SubstitutionSubtable.LigatureSub)?.value ?: return null
        val set = ligature.ligatureSets[ligature.coverage.indexOf(glyphId) ?: return null] ?: return null
        return List(set.size) { index ->
            val item = set[index] ?: return null
            LigatureSubstitution(item.glyph, item.components.toGlyphIds() ?: return null)
        }
    }

    public fun context(): LayoutContext? =
        (value as? SubstitutionSubtable.Context)
            ?.value
            ?.let(::LayoutContext)

    public fun chainedContext(): LayoutChainedContext? =
        (value as? SubstitutionSubtable.ChainContext)?.value?.let(::LayoutChainedContext)

    public fun reverseSubstitution(glyphId: GlyphId): GlyphId? {
        val reverse = (value as? SubstitutionSubtable.ReverseChainSingle)?.value ?: return null
        return reverse.substitutes[reverse.coverage.indexOf(glyphId) ?: return null]?.let(::GlyphId)
    }

    public fun reverseBacktrackCoverage(index: Int): LayoutCoverage? =
        (value as? SubstitutionSubtable.ReverseChainSingle)
            ?.value
            ?.backtrackCoverages
            ?.get(
                index,
            )?.let(::LayoutCoverage)

    public fun reverseLookaheadCoverage(index: Int): LayoutCoverage? =
        (value as? SubstitutionSubtable.ReverseChainSingle)
            ?.value
            ?.lookaheadCoverages
            ?.get(
                index,
            )?.let(::LayoutCoverage)
}

/** Observable GSUB metadata and direct, non-shaping subtable queries. */
public class GlyphSubstitutions internal constructor(
    private val table: GsubTable,
    private val coordinates: VariationCoordinates,
) : OpenTypeLayout() {
    override val scriptCount: Int get() = table.scripts.size
    override val featureCount: Int get() = table.features.size
    override val lookupCount: Int get() = table.lookups.size
    override val featureVariationCount: Int get() = table.variations?.size ?: 0

    override fun script(index: Int): LayoutScript? = table.scriptPublic(index)

    override fun script(tag: Tag): LayoutScript? = table.scripts.index(tag)?.let(table::scriptPublic)

    override fun feature(index: Int): LayoutFeature? = table.featurePublic(index)

    override fun feature(tag: Tag): LayoutFeature? = table.features.index(tag)?.let(table::featurePublic)

    override fun lookup(index: Int): LayoutLookup? = table.lookups[index]?.toPublic()

    override fun matchingFeatureVariationIndex(): Int? = table.variations?.findIndex(coordinates)

    override fun featureVariationConditionCount(variationIndex: Int): Int? =
        table.variations?.conditionCount(variationIndex)

    override fun featureVariationCondition(
        variationIndex: Int,
        conditionIndex: Int,
    ): FeatureVariationCondition? = table.variations?.condition(variationIndex, conditionIndex)?.toPublic()

    override fun featureSubstitution(
        variationIndex: Int,
        featureIndex: Int,
    ): LayoutFeature? =
        table.variations?.findSubstitute(featureIndex, variationIndex)?.toPublic(table.features[featureIndex]?.tag)

    public fun subtable(
        lookupIndex: Int,
        subtableIndex: Int,
    ): GlyphSubstitutionSubtable? = table.lookups[lookupIndex]?.get(subtableIndex)?.let(::GlyphSubstitutionSubtable)

    public fun substitute(
        lookupIndex: Int,
        glyphId: GlyphId,
    ): GlyphId? = firstResult(lookupIndex) { it.substitute(glyphId) }

    public fun multipleSubstitution(
        lookupIndex: Int,
        glyphId: GlyphId,
    ): List<GlyphId>? = firstResult(lookupIndex) { it.multipleSubstitution(glyphId) }

    public fun alternates(
        lookupIndex: Int,
        glyphId: GlyphId,
    ): List<GlyphId>? = firstResult(lookupIndex) { it.alternates(glyphId) }

    private fun <R> firstResult(
        lookupIndex: Int,
        query: (GlyphSubstitutionSubtable) -> R?,
    ): R? {
        val lookup = table.lookups[lookupIndex] ?: return null
        for (index in 0 until lookup.size) {
            query(
                GlyphSubstitutionSubtable(lookup[index] ?: continue),
            )?.let { return it }
        }
        return null
    }
}

public enum class GlyphPositioningKind {
    Single,
    Pair,
    Cursive,
    MarkToBase,
    MarkToLigature,
    MarkToMark,
    Context,
    ChainedContext,
}

public data class MarkAnchor(
    public val markClass: Int,
    public val anchor: LayoutAnchor,
)

/** One parsed GPOS subtable. Methods not applicable to [kind] return null. */
public class GlyphPositioningSubtable internal constructor(
    private val value: PositioningSubtable,
) {
    public val kind: GlyphPositioningKind
        get() =
            when (value) {
                is PositioningSubtable.Single -> GlyphPositioningKind.Single
                is PositioningSubtable.PairAdjustmentSubtable -> GlyphPositioningKind.Pair
                is PositioningSubtable.Cursive -> GlyphPositioningKind.Cursive
                is PositioningSubtable.MarkToBase -> GlyphPositioningKind.MarkToBase
                is PositioningSubtable.MarkToLigature -> GlyphPositioningKind.MarkToLigature
                is PositioningSubtable.MarkToMark -> GlyphPositioningKind.MarkToMark
                is PositioningSubtable.Context -> GlyphPositioningKind.Context
                is PositioningSubtable.ChainContext -> GlyphPositioningKind.ChainedContext
            }
    public val coverage: LayoutCoverage get() = LayoutCoverage(value.coverage)

    /** Secondary base, ligature, or mark2 coverage for attachment subtables. */
    public val attachmentCoverage: LayoutCoverage?
        get() =
            when (val subtable = value) {
                is PositioningSubtable.MarkToBase -> subtable.value.baseCoverage
                is PositioningSubtable.MarkToLigature -> subtable.value.ligatureCoverage
                is PositioningSubtable.MarkToMark -> subtable.value.mark2Coverage
                else -> null
            }?.let(::LayoutCoverage)

    public fun adjustment(glyphId: GlyphId): GlyphPositionAdjustment? {
        val single = (value as? PositioningSubtable.Single)?.value ?: return null
        return when (single) {
            is SingleAdjustment.Format1 -> single.value.takeIf { single.coverage.contains(glyphId) }
            is SingleAdjustment.Format2 -> single.coverage.indexOf(glyphId)?.let { single.values[it] }
        }?.toPublic()
    }

    public fun pairAdjustment(
        firstGlyph: GlyphId,
        secondGlyph: GlyphId,
    ): Pair<GlyphPositionAdjustment, GlyphPositionAdjustment>? {
        val pair = (value as? PositioningSubtable.PairAdjustmentSubtable)?.value ?: return null
        val coverageIndex = pair.coverage.indexOf(firstGlyph) ?: return null
        val result =
            when (pair) {
                is PairAdjustment.Format1 -> {
                    pair.sets[coverageIndex]?.get(secondGlyph)
                }

                is PairAdjustment.Format2 -> {
                    pair.matrix.get(
                        pair.firstClasses.classOf(firstGlyph),
                        pair.secondClasses.classOf(secondGlyph),
                    )
                }
            } ?: return null
        return result.first.toPublic() to result.second.toPublic()
    }

    public fun entryAnchor(glyphId: GlyphId): LayoutAnchor? {
        val cursive = (value as? PositioningSubtable.Cursive)?.value ?: return null
        return cursive.anchors.entry(cursive.coverage.indexOf(glyphId) ?: return null)?.toPublic()
    }

    public fun exitAnchor(glyphId: GlyphId): LayoutAnchor? {
        val cursive = (value as? PositioningSubtable.Cursive)?.value ?: return null
        return cursive.anchors.exit(cursive.coverage.indexOf(glyphId) ?: return null)?.toPublic()
    }

    public fun markAnchor(glyphId: GlyphId): MarkAnchor? {
        val pair =
            when (val subtable = value) {
                is PositioningSubtable.MarkToBase -> {
                    subtable.value.marks[
                        subtable.value.markCoverage.indexOf(glyphId)
                            ?: return null,
                    ]
                }

                is PositioningSubtable.MarkToLigature -> {
                    subtable.value.marks[
                        subtable.value.markCoverage.indexOf(glyphId)
                            ?: return null,
                    ]
                }

                is PositioningSubtable.MarkToMark -> {
                    subtable.value.marks[
                        subtable.value.mark1Coverage.indexOf(glyphId)
                            ?: return null,
                    ]
                }

                else -> {
                    null
                }
            } ?: return null
        return MarkAnchor(pair.first, pair.second.toPublic())
    }

    public fun baseAnchor(
        glyphId: GlyphId,
        markClass: Int,
    ): LayoutAnchor? {
        val adjustment = (value as? PositioningSubtable.MarkToBase)?.value ?: return null
        return adjustment.anchors[adjustment.baseCoverage.indexOf(glyphId) ?: return null, markClass]?.toPublic()
    }

    public fun ligatureAnchor(
        glyphId: GlyphId,
        componentIndex: Int,
        markClass: Int,
    ): LayoutAnchor? {
        val adjustment = (value as? PositioningSubtable.MarkToLigature)?.value ?: return null
        val ligature = adjustment.ligatures[adjustment.ligatureCoverage.indexOf(glyphId) ?: return null] ?: return null
        return ligature[componentIndex, markClass]?.toPublic()
    }

    public fun mark2Anchor(
        glyphId: GlyphId,
        markClass: Int,
    ): LayoutAnchor? {
        val adjustment = (value as? PositioningSubtable.MarkToMark)?.value ?: return null
        return adjustment.mark2Anchors[adjustment.mark2Coverage.indexOf(glyphId) ?: return null, markClass]?.toPublic()
    }

    public fun context(): LayoutContext? = (value as? PositioningSubtable.Context)?.value?.let(::LayoutContext)

    public fun chainedContext(): LayoutChainedContext? =
        (value as? PositioningSubtable.ChainContext)?.value?.let(::LayoutChainedContext)
}

/** Observable GPOS metadata and direct, non-shaping subtable queries. */
public class GlyphPositioning internal constructor(
    private val table: GposTable,
    private val coordinates: VariationCoordinates,
) : OpenTypeLayout() {
    override val scriptCount: Int get() = table.scripts.size
    override val featureCount: Int get() = table.features.size
    override val lookupCount: Int get() = table.lookups.size
    override val featureVariationCount: Int get() = table.variations?.size ?: 0

    override fun script(index: Int): LayoutScript? = table.scriptPublic(index)

    override fun script(tag: Tag): LayoutScript? = table.scripts.index(tag)?.let(table::scriptPublic)

    override fun feature(index: Int): LayoutFeature? = table.featurePublic(index)

    override fun feature(tag: Tag): LayoutFeature? = table.features.index(tag)?.let(table::featurePublic)

    override fun lookup(index: Int): LayoutLookup? = table.lookups[index]?.toPublic()

    override fun matchingFeatureVariationIndex(): Int? = table.variations?.findIndex(coordinates)

    override fun featureVariationConditionCount(variationIndex: Int): Int? =
        table.variations?.conditionCount(variationIndex)

    override fun featureVariationCondition(
        variationIndex: Int,
        conditionIndex: Int,
    ): FeatureVariationCondition? = table.variations?.condition(variationIndex, conditionIndex)?.toPublic()

    override fun featureSubstitution(
        variationIndex: Int,
        featureIndex: Int,
    ): LayoutFeature? =
        table.variations?.findSubstitute(featureIndex, variationIndex)?.toPublic(table.features[featureIndex]?.tag)

    public fun subtable(
        lookupIndex: Int,
        subtableIndex: Int,
    ): GlyphPositioningSubtable? = table.lookups[lookupIndex]?.get(subtableIndex)?.let(::GlyphPositioningSubtable)

    public fun adjustment(
        lookupIndex: Int,
        glyphId: GlyphId,
    ): GlyphPositionAdjustment? = firstResult(lookupIndex) { it.adjustment(glyphId) }

    public fun pairAdjustment(
        lookupIndex: Int,
        firstGlyph: GlyphId,
        secondGlyph: GlyphId,
    ): Pair<GlyphPositionAdjustment, GlyphPositionAdjustment>? =
        firstResult(lookupIndex) { it.pairAdjustment(firstGlyph, secondGlyph) }

    private fun <R> firstResult(
        lookupIndex: Int,
        query: (GlyphPositioningSubtable) -> R?,
    ): R? {
        val lookup = table.lookups[lookupIndex] ?: return null
        for (index in 0 until lookup.size) query(GlyphPositioningSubtable(lookup[index] ?: continue))?.let { return it }
        return null
    }
}

private fun GdefGlyphClass.toPublic(): GlyphDefinitionClass = GlyphDefinitionClass.entries[ordinal]

private fun Device?.toPublic(): LayoutDevice? = this?.let(::LayoutDevice)

private fun Anchor.toPublic(): LayoutAnchor = LayoutAnchor(x, y, contourPoint, xDevice.toPublic(), yDevice.toPublic())

private fun ValueRecord.toPublic(): GlyphPositionAdjustment =
    GlyphPositionAdjustment(
        xPlacement,
        yPlacement,
        xAdvance,
        yAdvance,
        xPlacementDevice.toPublic(),
        yPlacementDevice.toPublic(),
        xAdvanceDevice.toPublic(),
        yAdvanceDevice.toPublic(),
    )

private fun <T> Lookup<T>.toPublic(): LayoutLookup =
    LayoutLookup(
        size,
        LayoutLookupFlags(
            flags.rightToLeft,
            flags.ignoreBaseGlyphs,
            flags.ignoreLigatures,
            flags.ignoreMarks,
            flags.markAttachmentType,
            markFilteringSet,
        ),
    )

private fun Condition.toPublic(): FeatureVariationCondition =
    FeatureVariationCondition(axisIndex, minimum / 16384f, maximum / 16384f)

private fun Feature.toPublic(tag: Tag?): LayoutFeature = LayoutFeature(tag ?: this.tag, lookupIndices.toList())

private fun SequenceRule.toPublic(): LayoutContextRule = LayoutContextRule(input.toList(), lookups.toPublic())

private fun ChainedSequenceRule.toPublic(): LayoutChainedContextRule =
    LayoutChainedContextRule(backtrack.toList(), input.toList(), lookahead.toList(), lookups.toPublic())

private fun SequenceLookupRecords.toPublic(): List<LayoutSequenceLookup> =
    List(size) { index ->
        val record = get(index) ?: return emptyList()
        LayoutSequenceLookup(record.sequenceIndex, record.lookupListIndex)
    }

private fun <T> LayoutTable<T>.scriptPublic(index: Int): LayoutScript? {
    val script = scripts[index] ?: return null
    return LayoutScript(
        script.tag,
        script.defaultLanguage?.let { LayoutLanguage(it.tag, it.requiredFeature, it.featureIndices.toList()) },
        List(script.languages.size) { languageIndex ->
            val language = script.languages[languageIndex] ?: return null
            LayoutLanguage(language.tag, language.requiredFeature, language.featureIndices.toList())
        },
    )
}

private fun <T> LayoutTable<T>.featurePublic(index: Int): LayoutFeature? {
    val feature = features[index] ?: return null
    return feature.toPublic(feature.tag)
}

private fun UInt16View.toList(): List<Int> = List(size) { get(it) ?: 0 }

private fun UInt16View.toGlyphIds(): List<GlyphId>? = List(size) { GlyphId(get(it) ?: return null) }
