package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class ValueRecord(
    internal val xPlacement: Int = 0,
    internal val yPlacement: Int = 0,
    internal val xAdvance: Int = 0,
    internal val yAdvance: Int = 0,
    internal val xPlacementDevice: Device? = null,
    internal val yPlacementDevice: Device? = null,
    internal val xAdvanceDevice: Device? = null,
    internal val yAdvanceDevice: Device? = null,
)

internal data class ValueFormat(
    val bits: Int,
) {
    val size: Int get() = (bits and 0xFF).countOneBits() * 2
}

internal class ValueRecordsView internal constructor(
    private val table: ByteSlice,
    private val records: ByteSlice,
    private val format: ValueFormat,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): ValueRecord? {
        if (index !in 0 until size) return null
        return parseValue(table, records, index * format.size, format)
    }
}

internal sealed interface SingleAdjustment {
    val coverage: Coverage

    data class Format1(
        override val coverage: Coverage,
        internal val value: ValueRecord,
    ) : SingleAdjustment

    data class Format2(
        override val coverage: Coverage,
        internal val values: ValueRecordsView,
    ) : SingleAdjustment
}

internal class PairSet internal constructor(
    private val table: ByteSlice,
    private val records: ByteSlice,
    private val firstFormat: ValueFormat,
    private val secondFormat: ValueFormat,
    private val count: Int,
) {
    private val recordSize = 2 + firstFormat.size + secondFormat.size

    internal fun get(secondGlyph: GlyphId): Pair<ValueRecord, ValueRecord>? {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val offset = middle * recordSize
            val glyph = records.readUInt16(offset) ?: return null
            when {
                glyph < secondGlyph.value -> {
                    low = middle + 1
                }

                glyph > secondGlyph.value -> {
                    high = middle - 1
                }

                else -> {
                    val first = parseValue(table, records, offset + 2, firstFormat) ?: return null
                    val second = parseValue(table, records, offset + 2 + firstFormat.size, secondFormat) ?: return null
                    return first to second
                }
            }
        }
        return null
    }
}

internal class PairSets internal constructor(
    private val table: ByteSlice,
    private val offsets: UInt16View,
    private val firstFormat: ValueFormat,
    private val secondFormat: ValueFormat,
) {
    internal val size: Int get() = offsets.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): PairSet? {
        val offset = offsets[index] ?: return null
        if (offset == 0) return null
        val set = table.sliceFrom(offset) ?: return null
        val count = set.readUInt16(0) ?: return null
        val recordSize = 2 + firstFormat.size + secondFormat.size
        val records = set.slice(2, count.checkedTimes(recordSize) ?: return null) ?: return null
        return PairSet(table, records, firstFormat, secondFormat, count)
    }
}

internal class ClassMatrix internal constructor(
    private val table: ByteSlice,
    private val records: ByteSlice,
    private val class1Count: Int,
    private val class2Count: Int,
    private val firstFormat: ValueFormat,
    private val secondFormat: ValueFormat,
) {
    internal fun get(
        class1: Int,
        class2: Int,
    ): Pair<ValueRecord, ValueRecord>? {
        if (class1 !in 0 until class1Count || class2 !in 0 until class2Count) return null
        val recordSize = firstFormat.size + secondFormat.size
        val offset = ((class1.toLong() * class2Count + class2) * recordSize).toIndex() ?: return null
        val first = parseValue(table, records, offset, firstFormat) ?: return null
        val second = parseValue(table, records, offset + firstFormat.size, secondFormat) ?: return null
        return first to second
    }
}

internal sealed interface PairAdjustment {
    val coverage: Coverage

    data class Format1(
        override val coverage: Coverage,
        internal val sets: PairSets,
    ) : PairAdjustment

    data class Format2(
        override val coverage: Coverage,
        internal val firstClasses: ClassDefinition,
        internal val secondClasses: ClassDefinition,
        internal val matrix: ClassMatrix,
    ) : PairAdjustment
}

internal data class Anchor(
    internal val x: Int,
    internal val y: Int,
    internal val contourPoint: Int?,
    internal val xDevice: Device?,
    internal val yDevice: Device?,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): Anchor? {
            val format = data.readUInt16(0) ?: return null
            if (format !in 1..3) return null
            val x = data.readInt16(2) ?: return null
            val y = data.readInt16(4) ?: return null
            return when (format) {
                1 -> {
                    Anchor(x, y, null, null, null)
                }

                2 -> {
                    Anchor(x, y, data.readUInt16(6) ?: return null, null, null)
                }

                else -> {
                    Anchor(
                        x,
                        y,
                        null,
                        optionalDevice(data, data.readUInt16(6) ?: return null),
                        optionalDevice(data, data.readUInt16(8) ?: return null),
                    )
                }
            }
        }
    }
}

internal class CursiveAnchorSet internal constructor(
    private val data: ByteSlice,
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal fun entry(index: Int): Anchor? = anchorAt(index, 0)

    internal fun exit(index: Int): Anchor? = anchorAt(index, 2)

    private fun anchorAt(
        index: Int,
        field: Int,
    ): Anchor? {
        if (index !in 0 until size) return null
        val offset = records.readUInt16(index * 4 + field) ?: return null
        if (offset == 0) return null
        return Anchor.parse(data.sliceFrom(offset) ?: return null)
    }
}

internal data class CursiveAdjustment(
    internal val coverage: Coverage,
    internal val anchors: CursiveAnchorSet,
)

internal class MarkArray private constructor(
    private val data: ByteSlice,
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): Pair<Int, Anchor>? {
        if (index !in 0 until size) return null
        val glyphClass = records.readUInt16(index * 4) ?: return null
        val offset = records.readUInt16(index * 4 + 2) ?: return null
        return glyphClass to (Anchor.parse(data.sliceFrom(offset) ?: return null) ?: return null)
    }

    internal companion object {
        internal fun parse(data: ByteSlice): MarkArray? {
            val count = data.readUInt16(0) ?: return null
            return MarkArray(data, data.slice(2, count.checkedTimes(4) ?: return null) ?: return null, count)
        }
    }
}

internal class AnchorMatrix internal constructor(
    private val data: ByteSlice,
    internal val rows: Int,
    internal val columns: Int,
    private val offsets: UInt16View,
) {
    internal operator fun get(
        row: Int,
        column: Int,
    ): Anchor? {
        if (row !in 0 until rows || column !in 0 until columns) return null
        val offset = offsets[row * columns + column] ?: return null
        if (offset == 0) return null
        return Anchor.parse(data.sliceFrom(offset) ?: return null)
    }

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            columns: Int,
        ): AnchorMatrix? {
            val rows = data.readUInt16(0) ?: return null
            val count = rows.checkedTimes(columns) ?: return null
            return AnchorMatrix(data, rows, columns, UInt16View.parse(data, 2, count) ?: return null)
        }
    }
}

internal data class MarkToBaseAdjustment(
    internal val markCoverage: Coverage,
    internal val baseCoverage: Coverage,
    internal val marks: MarkArray,
    internal val anchors: AnchorMatrix,
)

internal class LigatureArray private constructor(
    private val data: ByteSlice,
    private val classCount: Int,
    private val offsets: UInt16View,
) {
    internal val size: Int get() = offsets.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): AnchorMatrix? {
        val offset = offsets[index] ?: return null
        return AnchorMatrix.parse(data.sliceFrom(offset) ?: return null, classCount)
    }

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            classCount: Int,
        ): LigatureArray? {
            val count = data.readUInt16(0) ?: return null
            return LigatureArray(data, classCount, UInt16View.parse(data, 2, count) ?: return null)
        }
    }
}

internal data class MarkToLigatureAdjustment(
    internal val markCoverage: Coverage,
    internal val ligatureCoverage: Coverage,
    internal val marks: MarkArray,
    internal val ligatures: LigatureArray,
)

internal data class MarkToMarkAdjustment(
    internal val mark1Coverage: Coverage,
    internal val mark2Coverage: Coverage,
    internal val marks: MarkArray,
    internal val mark2Anchors: AnchorMatrix,
)

internal sealed interface PositioningSubtable {
    val coverage: Coverage

    data class Single(
        internal val value: SingleAdjustment,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class PairAdjustmentSubtable(
        internal val value: PairAdjustment,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class Cursive(
        internal val value: CursiveAdjustment,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class MarkToBase(
        internal val value: MarkToBaseAdjustment,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.markCoverage
    }

    data class MarkToLigature(
        internal val value: MarkToLigatureAdjustment,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.markCoverage
    }

    data class MarkToMark(
        internal val value: MarkToMarkAdjustment,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.mark1Coverage
    }

    data class Context(
        internal val value: ContextLookup,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    data class ChainContext(
        internal val value: ChainedContextLookup,
    ) : PositioningSubtable {
        override val coverage: Coverage get() = value.coverage
    }

    companion object {
        internal fun parse(
            data: ByteSlice,
            kind: Int,
            extensionDepth: Int,
        ): PositioningSubtable? =
            when (kind) {
                1 -> parseSingleAdjustment(data)?.let(::Single)
                2 -> parsePairAdjustment(data)?.let(::PairAdjustmentSubtable)
                3 -> parseCursive(data)?.let(::Cursive)
                4 -> parseMarkToBase(data)?.let(::MarkToBase)
                5 -> parseMarkToLigature(data)?.let(::MarkToLigature)
                6 -> parseMarkToMark(data)?.let(::MarkToMark)
                7 -> ContextLookup.parse(data)?.let(::Context)
                8 -> ChainedContextLookup.parse(data)?.let(::ChainContext)
                9 -> parseExtension(data, extensionDepth)
                else -> null
            }

        private fun parseExtension(
            data: ByteSlice,
            depth: Int,
        ): PositioningSubtable? {
            if (depth <= 0 || data.readUInt16(0) != 1) return null
            val kind = data.readUInt16(2) ?: return null
            val offset = data.readUInt32(4)?.toIndex() ?: return null
            return parse(data.sliceFrom(offset) ?: return null, kind, depth - 1)
        }
    }
}

internal typealias GposTable = LayoutTable<PositioningSubtable>

internal fun parseGpos(data: ByteSlice): GposTable? = LayoutTable.parse(data, PositioningSubtable::parse)

private fun parseSingleAdjustment(data: ByteSlice): SingleAdjustment? {
    val coverage = data.at16(2, Coverage::parse) ?: return null
    val format = ValueFormat(data.readUInt16(4) ?: return null)
    return when (data.readUInt16(0)) {
        1 -> {
            SingleAdjustment.Format1(coverage, parseValue(data, data, 6, format) ?: return null)
        }

        2 -> {
            val count = data.readUInt16(6) ?: return null
            val records = data.slice(8, count.checkedTimes(format.size) ?: return null) ?: return null
            SingleAdjustment.Format2(coverage, ValueRecordsView(data, records, format, count))
        }

        else -> {
            null
        }
    }
}

private fun parsePairAdjustment(data: ByteSlice): PairAdjustment? {
    val coverage = data.at16(2, Coverage::parse) ?: return null
    val first = ValueFormat(data.readUInt16(4) ?: return null)
    val second = ValueFormat(data.readUInt16(6) ?: return null)
    return when (data.readUInt16(0)) {
        1 -> {
            val count = data.readUInt16(8) ?: return null
            PairAdjustment.Format1(
                coverage,
                PairSets(data, UInt16View.parse(data, 10, count) ?: return null, first, second),
            )
        }

        2 -> {
            val firstClasses = data.at16(8, ClassDefinition::parse) ?: return null
            val secondClasses = data.at16(10, ClassDefinition::parse) ?: return null
            val class1Count = data.readUInt16(12) ?: return null
            val class2Count = data.readUInt16(14) ?: return null
            val recordSize = first.size + second.size
            val recordsSize = (class1Count.toLong() * class2Count * recordSize).toIndex() ?: return null
            val records = data.slice(16, recordsSize) ?: return null
            PairAdjustment.Format2(
                coverage,
                firstClasses,
                secondClasses,
                ClassMatrix(data, records, class1Count, class2Count, first, second),
            )
        }

        else -> {
            null
        }
    }
}

private fun parseCursive(data: ByteSlice): CursiveAdjustment? {
    if (data.readUInt16(0) != 1) return null
    val coverage = data.at16(2, Coverage::parse) ?: return null
    val count = data.readUInt16(4) ?: return null
    val records = data.slice(6, count.checkedTimes(4) ?: return null) ?: return null
    return CursiveAdjustment(coverage, CursiveAnchorSet(data, records, count))
}

private fun parseMarkToBase(data: ByteSlice): MarkToBaseAdjustment? {
    if (data.readUInt16(0) != 1) return null
    val markCoverage = data.at16(2, Coverage::parse) ?: return null
    val baseCoverage = data.at16(4, Coverage::parse) ?: return null
    val classCount = data.readUInt16(6) ?: return null
    val marks = data.at16(8, MarkArray::parse) ?: return null
    val anchors = data.at16(10) { AnchorMatrix.parse(it, classCount) } ?: return null
    return MarkToBaseAdjustment(markCoverage, baseCoverage, marks, anchors)
}

private fun parseMarkToLigature(data: ByteSlice): MarkToLigatureAdjustment? {
    if (data.readUInt16(0) != 1) return null
    val markCoverage = data.at16(2, Coverage::parse) ?: return null
    val ligatureCoverage = data.at16(4, Coverage::parse) ?: return null
    val classCount = data.readUInt16(6) ?: return null
    val marks = data.at16(8, MarkArray::parse) ?: return null
    val ligatures = data.at16(10) { LigatureArray.parse(it, classCount) } ?: return null
    return MarkToLigatureAdjustment(markCoverage, ligatureCoverage, marks, ligatures)
}

private fun parseMarkToMark(data: ByteSlice): MarkToMarkAdjustment? {
    if (data.readUInt16(0) != 1) return null
    val mark1Coverage = data.at16(2, Coverage::parse) ?: return null
    val mark2Coverage = data.at16(4, Coverage::parse) ?: return null
    val classCount = data.readUInt16(6) ?: return null
    val marks = data.at16(8, MarkArray::parse) ?: return null
    val anchors = data.at16(10) { AnchorMatrix.parse(it, classCount) } ?: return null
    return MarkToMarkAdjustment(mark1Coverage, mark2Coverage, marks, anchors)
}

private fun parseValue(
    table: ByteSlice,
    records: ByteSlice,
    start: Int,
    format: ValueFormat,
): ValueRecord? {
    var cursor = start

    fun signed(bit: Int): Int {
        if (format.bits and bit == 0) return 0
        val value = records.readInt16(cursor) ?: throw InvalidValueRecord
        cursor += 2
        return value
    }

    fun device(bit: Int): Device? {
        if (format.bits and bit == 0) return null
        val offset = records.readUInt16(cursor) ?: throw InvalidValueRecord
        cursor += 2
        return optionalDevice(table, offset)
    }

    return try {
        ValueRecord(
            signed(0x0001),
            signed(0x0002),
            signed(0x0004),
            signed(0x0008),
            device(0x0010),
            device(0x0020),
            device(0x0040),
            device(0x0080),
        )
    } catch (_: InvalidValueRecord) {
        null
    }
}

private fun optionalDevice(
    data: ByteSlice,
    offset: Int,
): Device? = if (offset == 0) null else data.sliceFrom(offset)?.let(Device::parse)

private data object InvalidValueRecord : Throwable()
