package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.layout.GlyphAssembly
import dev.skaldebane.kttf.internal.tables.layout.GlyphConstruction
import dev.skaldebane.kttf.internal.tables.layout.MathTable
import dev.skaldebane.kttf.internal.tables.layout.MathValue

public data class MathValueRecord(
    public val value: Int,
    public val device: LayoutDevice?,
)

/** Piecewise MATH kern values. There is one more kern value than correction height. */
public data class MathKern(
    public val correctionHeights: List<MathValueRecord>,
    public val kernValues: List<MathValueRecord>,
)

public data class MathKernInfo(
    public val topRight: MathKern?,
    public val topLeft: MathKern?,
    public val bottomRight: MathKern?,
    public val bottomLeft: MathKern?,
)

public data class MathGlyphPart(
    public val glyphId: GlyphId,
    public val startConnectorLength: Int,
    public val endConnectorLength: Int,
    public val fullAdvance: Int,
    public val isExtender: Boolean,
)

public data class MathGlyphVariant(
    public val glyphId: GlyphId,
    public val advanceMeasurement: Int,
)

public data class MathGlyphConstruction(
    public val variants: List<MathGlyphVariant>,
    public val assemblyParts: List<MathGlyphPart>,
    public val assemblyItalicCorrection: Int?,
    public val assemblyItalicCorrectionValue: MathValueRecord?,
)

/** High-level MATH constants and glyph construction queries. */
public class MathLayout internal constructor(
    private val table: MathTable,
) {
    public val scriptPercentScaleDown: Int?
        get() = table.constants?.scriptPercentScaleDown

    public val scriptScriptPercentScaleDown: Int?
        get() = table.constants?.scriptScriptPercentScaleDown

    public val delimitedSubFormulaMinHeight: Int?
        get() = table.constants?.delimitedSubFormulaMinHeight

    public val displayOperatorMinHeight: Int?
        get() = table.constants?.displayOperatorMinHeight

    public val minConnectorOverlap: Int?
        get() = table.variants?.minConnectorOverlap

    /** Returns one of the 51 OpenType MathValue constants by specification order. */
    public fun constant(index: Int): Int? =
        table.constants
            ?.takeIf { index in 0..50 }
            ?.value(index)
            ?.value

    /** Returns a constant together with its optional device or VariationIndex. */
    public fun constantValue(index: Int): MathValueRecord? =
        table.constants
            ?.takeIf { index in 0..50 }
            ?.value(index)
            ?.toPublic()

    public fun italicCorrection(glyphId: GlyphId): Int? =
        table.glyphInfo
            ?.italicCorrections
            ?.get(glyphId)
            ?.value

    public fun italicCorrectionValue(glyphId: GlyphId): MathValueRecord? =
        table.glyphInfo
            ?.italicCorrections
            ?.get(glyphId)
            ?.toPublic()

    public fun topAccentAttachment(glyphId: GlyphId): Int? =
        table.glyphInfo
            ?.topAccentAttachments
            ?.get(glyphId)
            ?.value

    public fun topAccentAttachmentValue(glyphId: GlyphId): MathValueRecord? =
        table.glyphInfo
            ?.topAccentAttachments
            ?.get(glyphId)
            ?.toPublic()

    public fun kernInfo(glyphId: GlyphId): MathKernInfo? {
        val info = table.glyphInfo?.kernInfos?.get(glyphId) ?: return null
        return MathKernInfo(
            info.topRight?.toPublic(),
            info.topLeft?.toPublic(),
            info.bottomRight?.toPublic(),
            info.bottomLeft?.toPublic(),
        )
    }

    public fun isExtendedShape(glyphId: GlyphId): Boolean = table.glyphInfo?.extendedShapes?.contains(glyphId) == true

    public fun verticalConstruction(glyphId: GlyphId): MathGlyphConstruction? =
        table.variants
            ?.verticalConstructions
            ?.get(glyphId)
            ?.toPublic()

    public fun horizontalConstruction(glyphId: GlyphId): MathGlyphConstruction? =
        table.variants
            ?.horizontalConstructions
            ?.get(glyphId)
            ?.toPublic()
}

private fun GlyphConstruction.toPublic(): MathGlyphConstruction =
    MathGlyphConstruction(
        variants =
            List(variants.size) { index ->
                val variant = variants[index] ?: throw NoSuchElementException()
                MathGlyphVariant(variant.glyph, variant.advanceMeasurement)
            },
        assemblyParts = assembly?.toPublicParts().orEmpty(),
        assemblyItalicCorrection = assembly?.italicsCorrection?.value,
        assemblyItalicCorrectionValue = assembly?.italicsCorrection?.toPublic(),
    )

private fun GlyphAssembly.toPublicParts(): List<MathGlyphPart> =
    List(parts.size) { index ->
        val part = parts[index] ?: throw NoSuchElementException()
        MathGlyphPart(
            part.glyph,
            part.startConnectorLength,
            part.endConnectorLength,
            part.fullAdvance,
            part.isExtender,
        )
    }

private fun MathValue.toPublic(): MathValueRecord = MathValueRecord(value, device?.let(::LayoutDevice))

private fun dev.skaldebane.kttf.internal.tables.layout.MathKern.toPublic(): MathKern =
    MathKern(
        List(count) { index -> height(index)?.toPublic() ?: return MathKern(emptyList(), emptyList()) },
        List(count + 1) { index -> kern(index)?.toPublic() ?: return MathKern(emptyList(), emptyList()) },
    )
