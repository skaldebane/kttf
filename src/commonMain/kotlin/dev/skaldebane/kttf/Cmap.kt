package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.CmapFormatKind
import dev.skaldebane.kttf.internal.tables.CmapPlatform
import dev.skaldebane.kttf.internal.tables.CmapSubtable as InternalCmapSubtable
import dev.skaldebane.kttf.internal.tables.CmapTable as InternalCmapTable
import dev.skaldebane.kttf.internal.tables.CmapVariationResult as InternalVariationResult

/** An OpenType character-to-glyph mapping table. */
public class Cmap internal constructor(
    internal val table: InternalCmapTable,
) {
    /** Binary-backed character mapping subtables in encoding-record order. */
    public val subtables: CmapSubtables = CmapSubtables(table)
}

/** A binary-backed, indexed view of cmap subtables. */
public class CmapSubtables internal constructor(
    private val table: InternalCmapTable,
) : Iterable<CmapSubtable> {
    /** Number of encoding records declared by the cmap table. */
    public val size: Int
        get() = table.size

    /** Returns the parsed subtable at [index], or null when its record is malformed or unsupported. */
    public operator fun get(index: Int): CmapSubtable? = table[index]?.let(::CmapSubtable)

    override fun iterator(): Iterator<CmapSubtable> =
        object : Iterator<CmapSubtable> {
            private var index = 0
            private var next = findNext()

            override fun hasNext(): Boolean = next != null

            override fun next(): CmapSubtable {
                val result = next ?: throw NoSuchElementException()
                next = findNext()
                return result
            }

            private fun findNext(): CmapSubtable? {
                if (index >= size) return null
                return get(index++)
            }
        }
}

/** A platform associated with an OpenType encoding record. */
public enum class CmapPlatformId {
    Unicode,
    Macintosh,
    Iso,
    Windows,
    Custom,
}

/** A supported or recognized cmap subtable format. */
public enum class CmapFormat(
    public val value: Int,
) {
    ByteEncoding(0),
    HighByteMapping(2),
    SegmentMapping(4),
    TrimmedMapping(6),
    MixedCoverage(8),
    TrimmedArray(10),
    SegmentedCoverage(12),
    ManyToOne(13),
    UnicodeVariationSequences(14),
}

/** Result of a low-level format 14 variation mapping. */
public sealed interface CmapVariationResult {
    /** The variation sequence maps directly to [glyphId]. */
    public data class Found(
        public val glyphId: GlyphId,
    ) : CmapVariationResult

    /** The variation sequence uses the ordinary cmap mapping. */
    public data object UseDefault : CmapVariationResult
}

/** A parsed cmap encoding subtable backed by the original font bytes. */
public class CmapSubtable internal constructor(
    private val subtable: InternalCmapSubtable,
) {
    public val platformId: CmapPlatformId
        get() = subtable.platform.toPublic()

    public val encodingId: Int
        get() = subtable.encodingId

    public val format: CmapFormat
        get() = subtable.formatKind.toPublic()

    /** Whether this encoding is considered Unicode-compatible by the high-level face lookup. */
    public val isUnicode: Boolean
        get() = subtable.isUnicode

    /**
     * Maps the raw unsigned 32-bit [codePoint] value without applying Unicode-platform filtering.
     */
    public fun glyphId(codePoint: Long): GlyphId? = subtable.glyphId(codePoint)?.let(::GlyphId)

    /** Resolves a raw format 14 variation mapping. */
    public fun glyphVariation(
        codePoint: Long,
        variationSelector: Long,
    ): CmapVariationResult? =
        when (val result = subtable.glyphVariation(codePoint, variationSelector)) {
            is InternalVariationResult.Found -> CmapVariationResult.Found(GlyphId(result.glyphId))
            InternalVariationResult.UseDefault -> CmapVariationResult.UseDefault
            null -> null
        }

    /**
     * Calls [action] for each raw unsigned 32-bit code-point value defined by this subtable.
     *
     * This does not filter non-Unicode encodings, deduplicate values, or verify that each value
     * maps to a nonzero glyph. The callback is invoked synchronously and is not retained.
     *
     * Returns false when malformed overlapping or oversized ranges would require more than
     * 1,114,112 callbacks; otherwise returns true.
     */
    public fun forEachCodePoint(action: (Long) -> Unit): Boolean = subtable.forEachCodePoint(action)
}

private fun CmapPlatform.toPublic(): CmapPlatformId =
    when (this) {
        CmapPlatform.UNICODE -> CmapPlatformId.Unicode
        CmapPlatform.MACINTOSH -> CmapPlatformId.Macintosh
        CmapPlatform.ISO -> CmapPlatformId.Iso
        CmapPlatform.WINDOWS -> CmapPlatformId.Windows
        CmapPlatform.CUSTOM -> CmapPlatformId.Custom
    }

private fun CmapFormatKind.toPublic(): CmapFormat =
    when (this) {
        CmapFormatKind.BYTE_ENCODING -> CmapFormat.ByteEncoding
        CmapFormatKind.HIGH_BYTE_MAPPING -> CmapFormat.HighByteMapping
        CmapFormatKind.SEGMENT_MAPPING -> CmapFormat.SegmentMapping
        CmapFormatKind.TRIMMED_MAPPING -> CmapFormat.TrimmedMapping
        CmapFormatKind.MIXED_COVERAGE -> CmapFormat.MixedCoverage
        CmapFormatKind.TRIMMED_ARRAY -> CmapFormat.TrimmedArray
        CmapFormatKind.SEGMENTED_COVERAGE -> CmapFormat.SegmentedCoverage
        CmapFormatKind.MANY_TO_ONE -> CmapFormat.ManyToOne
        CmapFormatKind.UNICODE_VARIATION_SEQUENCES -> CmapFormat.UnicodeVariationSequences
    }
