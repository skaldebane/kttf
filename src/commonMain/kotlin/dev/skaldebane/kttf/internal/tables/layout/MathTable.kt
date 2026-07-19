package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class MathValue(
    internal val value: Int,
    internal val device: Device?,
)

internal class MathConstants internal constructor(
    private val data: ByteSlice,
) {
    internal val scriptPercentScaleDown: Int get() = data.readInt16(0) ?: 0
    internal val scriptScriptPercentScaleDown: Int get() = data.readInt16(2) ?: 0
    internal val delimitedSubFormulaMinHeight: Int get() = data.readUInt16(4) ?: 0
    internal val displayOperatorMinHeight: Int get() = data.readUInt16(6) ?: 0

    internal val mathLeading get() = value(0)
    internal val axisHeight get() = value(1)
    internal val accentBaseHeight get() = value(2)
    internal val flattenedAccentBaseHeight get() = value(3)
    internal val subscriptShiftDown get() = value(4)
    internal val subscriptTopMax get() = value(5)
    internal val subscriptBaselineDropMin get() = value(6)
    internal val superscriptShiftUp get() = value(7)
    internal val superscriptShiftUpCramped get() = value(8)
    internal val superscriptBottomMin get() = value(9)
    internal val superscriptBaselineDropMax get() = value(10)
    internal val subSuperscriptGapMin get() = value(11)
    internal val superscriptBottomMaxWithSubscript get() = value(12)
    internal val spaceAfterScript get() = value(13)
    internal val upperLimitGapMin get() = value(14)
    internal val upperLimitBaselineRiseMin get() = value(15)
    internal val lowerLimitGapMin get() = value(16)
    internal val lowerLimitBaselineDropMin get() = value(17)
    internal val stackTopShiftUp get() = value(18)
    internal val stackTopDisplayStyleShiftUp get() = value(19)
    internal val stackBottomShiftDown get() = value(20)
    internal val stackBottomDisplayStyleShiftDown get() = value(21)
    internal val stackGapMin get() = value(22)
    internal val stackDisplayStyleGapMin get() = value(23)
    internal val stretchStackTopShiftUp get() = value(24)
    internal val stretchStackBottomShiftDown get() = value(25)
    internal val stretchStackGapAboveMin get() = value(26)
    internal val stretchStackGapBelowMin get() = value(27)
    internal val fractionNumeratorShiftUp get() = value(28)
    internal val fractionNumeratorDisplayStyleShiftUp get() = value(29)
    internal val fractionDenominatorShiftDown get() = value(30)
    internal val fractionDenominatorDisplayStyleShiftDown get() = value(31)
    internal val fractionNumeratorGapMin get() = value(32)
    internal val fractionNumDisplayStyleGapMin get() = value(33)
    internal val fractionRuleThickness get() = value(34)
    internal val fractionDenominatorGapMin get() = value(35)
    internal val fractionDenomDisplayStyleGapMin get() = value(36)
    internal val skewedFractionHorizontalGap get() = value(37)
    internal val skewedFractionVerticalGap get() = value(38)
    internal val overbarVerticalGap get() = value(39)
    internal val overbarRuleThickness get() = value(40)
    internal val overbarExtraAscender get() = value(41)
    internal val underbarVerticalGap get() = value(42)
    internal val underbarRuleThickness get() = value(43)
    internal val underbarExtraDescender get() = value(44)
    internal val radicalVerticalGap get() = value(45)
    internal val radicalDisplayStyleVerticalGap get() = value(46)
    internal val radicalRuleThickness get() = value(47)
    internal val radicalExtraAscender get() = value(48)
    internal val radicalKernBeforeDegree get() = value(49)
    internal val radicalKernAfterDegree get() = value(50)
    internal val radicalDegreeBottomRaisePercent: Int get() = data.readInt16(212) ?: 0

    internal fun value(index: Int): MathValue {
        if (index !in 0..50) return MathValue(0, null)
        return parseMathValue(data, 8 + index * 4, data) ?: MathValue(0, null)
    }
}

internal class MathValues private constructor(
    private val data: ByteSlice,
    private val coverage: Coverage,
    private val records: ByteSlice,
    private val count: Int,
) {
    internal fun get(glyph: GlyphId): MathValue? {
        val index = coverage.indexOf(glyph) ?: return null
        if (index !in 0 until count) return null
        return parseMathValue(records, index * 4, data)
    }

    internal companion object {
        internal fun parse(data: ByteSlice): MathValues? {
            val coverage = data.at16(0, Coverage::parse) ?: return null
            val count = data.readUInt16(2) ?: return null
            val records = data.slice(4, count.checkedTimes(4) ?: return null) ?: return null
            return MathValues(data, coverage, records, count)
        }
    }
}

internal class MathKern private constructor(
    private val data: ByteSlice,
    private val heights: ByteSlice,
    private val kerns: ByteSlice,
    internal val count: Int,
) {
    internal fun height(index: Int): MathValue? =
        if (index in 0 until count) parseMathValue(heights, index * 4, data) else null

    internal fun kern(index: Int): MathValue? = if (index in 0..count) parseMathValue(kerns, index * 4, data) else null

    internal companion object {
        internal fun parse(data: ByteSlice): MathKern? {
            val count = data.readUInt16(0) ?: return null
            val heights = data.slice(2, count.checkedTimes(4) ?: return null) ?: return null
            val kerns = data.slice(2 + count * 4, (count + 1).checkedTimes(4) ?: return null) ?: return null
            return MathKern(data, heights, kerns, count)
        }
    }
}

internal data class MathKernInfo(
    internal val topRight: MathKern?,
    internal val topLeft: MathKern?,
    internal val bottomRight: MathKern?,
    internal val bottomLeft: MathKern?,
)

internal class MathKernInfos private constructor(
    private val data: ByteSlice,
    private val coverage: Coverage,
    private val records: ByteSlice,
    private val count: Int,
) {
    internal fun get(glyph: GlyphId): MathKernInfo? {
        val index = coverage.indexOf(glyph) ?: return null
        if (index !in 0 until count) return null
        val offset = index * 8
        return MathKernInfo(
            kernAt(records.readUInt16(offset) ?: return null),
            kernAt(records.readUInt16(offset + 2) ?: return null),
            kernAt(records.readUInt16(offset + 4) ?: return null),
            kernAt(records.readUInt16(offset + 6) ?: return null),
        )
    }

    private fun kernAt(offset: Int): MathKern? = if (offset == 0) null else data.sliceFrom(offset)?.let(MathKern::parse)

    internal companion object {
        internal fun parse(data: ByteSlice): MathKernInfos? {
            val coverage = data.at16(0, Coverage::parse) ?: return null
            val count = data.readUInt16(2) ?: return null
            val records = data.slice(4, count.checkedTimes(8) ?: return null) ?: return null
            return MathKernInfos(data, coverage, records, count)
        }
    }
}

internal data class MathGlyphInfo(
    internal val italicCorrections: MathValues?,
    internal val topAccentAttachments: MathValues?,
    internal val extendedShapes: Coverage?,
    internal val kernInfos: MathKernInfos?,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): MathGlyphInfo? {
            if (!data.contains(0, 8)) return null
            return MathGlyphInfo(
                optionalAt16(data, 0, MathValues::parse),
                optionalAt16(data, 2, MathValues::parse),
                optionalAt16(data, 4, Coverage::parse),
                optionalAt16(data, 6, MathKernInfos::parse),
            )
        }
    }
}

internal data class GlyphPart(
    internal val glyph: GlyphId,
    internal val startConnectorLength: Int,
    internal val endConnectorLength: Int,
    internal val fullAdvance: Int,
    internal val flags: Int,
) {
    internal val isExtender: Boolean get() = flags and 1 != 0
}

internal class GlyphParts private constructor(
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): GlyphPart? {
        if (index !in 0 until size) return null
        val offset = index * 10
        return GlyphPart(
            GlyphId(records.readUInt16(offset) ?: return null),
            records.readUInt16(offset + 2) ?: return null,
            records.readUInt16(offset + 4) ?: return null,
            records.readUInt16(offset + 6) ?: return null,
            records.readUInt16(offset + 8) ?: return null,
        )
    }

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            offset: Int,
            count: Int,
        ): GlyphParts? = GlyphParts(data.slice(offset, count.checkedTimes(10) ?: return null) ?: return null, count)
    }
}

internal data class GlyphAssembly(
    internal val italicsCorrection: MathValue,
    internal val parts: GlyphParts,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): GlyphAssembly? {
            val correction = parseMathValue(data, 0, data) ?: return null
            val count = data.readUInt16(4) ?: return null
            return GlyphAssembly(correction, GlyphParts.parse(data, 6, count) ?: return null)
        }
    }
}

internal data class GlyphVariant(
    internal val glyph: GlyphId,
    internal val advanceMeasurement: Int,
)

internal class GlyphVariants private constructor(
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): GlyphVariant? {
        if (index !in 0 until size) return null
        return GlyphVariant(
            GlyphId(records.readUInt16(index * 4) ?: return null),
            records.readUInt16(index * 4 + 2) ?: return null,
        )
    }

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            offset: Int,
            count: Int,
        ): GlyphVariants? =
            GlyphVariants(data.slice(offset, count.checkedTimes(4) ?: return null) ?: return null, count)
    }
}

internal data class GlyphConstruction(
    internal val assembly: GlyphAssembly?,
    internal val variants: GlyphVariants,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): GlyphConstruction? {
            val assemblyOffset = data.readUInt16(0) ?: return null
            val count = data.readUInt16(2) ?: return null
            return GlyphConstruction(
                if (assemblyOffset == 0) null else data.sliceFrom(assemblyOffset)?.let(GlyphAssembly::parse),
                GlyphVariants.parse(data, 4, count) ?: return null,
            )
        }
    }
}

internal class GlyphConstructions internal constructor(
    private val data: ByteSlice,
    private val coverage: Coverage?,
    private val offsets: UInt16View,
) {
    internal fun get(glyph: GlyphId): GlyphConstruction? {
        val index = coverage?.indexOf(glyph) ?: return null
        val offset = offsets[index] ?: return null
        if (offset == 0) return null
        return GlyphConstruction.parse(data.sliceFrom(offset) ?: return null)
    }
}

internal data class MathVariants(
    internal val minConnectorOverlap: Int,
    internal val verticalConstructions: GlyphConstructions,
    internal val horizontalConstructions: GlyphConstructions,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): MathVariants? {
            val overlap = data.readUInt16(0) ?: return null
            val verticalCoverage = optionalAt16(data, 2, Coverage::parse)
            val horizontalCoverage = optionalAt16(data, 4, Coverage::parse)
            val verticalCount = data.readUInt16(6) ?: return null
            val horizontalCount = data.readUInt16(8) ?: return null
            val verticalOffsets = UInt16View.parse(data, 10, verticalCount) ?: return null
            val horizontalOffsets = UInt16View.parse(data, 10 + verticalCount * 2, horizontalCount) ?: return null
            return MathVariants(
                overlap,
                GlyphConstructions(data, verticalCoverage, verticalOffsets),
                GlyphConstructions(data, horizontalCoverage, horizontalOffsets),
            )
        }
    }
}

internal data class MathTable(
    internal val constants: MathConstants?,
    internal val glyphInfo: MathGlyphInfo?,
    internal val variants: MathVariants?,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): MathTable? {
            if (data.readUInt16(0) != 1 || data.readUInt16(2) == null) return null
            if (!data.contains(0, 10)) return null
            return MathTable(
                optionalAt16(data, 4, ::MathConstants),
                optionalAt16(data, 6, MathGlyphInfo::parse),
                optionalAt16(data, 8, MathVariants::parse),
            )
        }
    }
}

private fun parseMathValue(
    recordData: ByteSlice,
    offset: Int,
    parent: ByteSlice,
): MathValue? {
    val value = recordData.readInt16(offset) ?: return null
    val deviceOffset = recordData.readUInt16(offset + 2) ?: return null
    val device = if (deviceOffset == 0) null else parent.sliceFrom(deviceOffset)?.let(Device::parse)
    return MathValue(value, device)
}

private inline fun <T> optionalAt16(
    data: ByteSlice,
    fieldOffset: Int,
    parse: (ByteSlice) -> T?,
): T? {
    val offset = data.readUInt16(fieldOffset) ?: return null
    if (offset == 0) return null
    return parse(data.sliceFrom(offset) ?: return null)
}
