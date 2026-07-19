package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal enum class KernFormat {
    FORMAT_0,
    FORMAT_1,
    FORMAT_2,
    FORMAT_3,
}

internal data class KernPair(
    internal val leftGlyph: Int,
    internal val rightGlyph: Int,
    internal val value: Int,
)

internal class KernPairs internal constructor(
    private val records: FixedRecordArray,
) {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): KernPair? {
        val record = records[index] ?: return null
        return KernPair(
            leftGlyph = record.readUInt16(0) ?: return null,
            rightGlyph = record.readUInt16(2) ?: return null,
            value = record.readInt16(4) ?: return null,
        )
    }

    internal fun kerning(
        left: Int,
        right: Int,
    ): Int? {
        if (left !in 0..0xFFFF || right !in 0..0xFFFF) return null
        val target = (left.toLong() shl 16) or right.toLong()
        var remaining = size
        if (remaining == 0) return null
        var base = 0
        while (remaining > 1) {
            val half = remaining / 2
            val middle = base + half
            val pair = records[middle]?.readUInt32(0) ?: return null
            if (pair <= target) base = middle
            remaining -= half
        }
        val record = records[base] ?: return null
        return record.readInt16(4)?.takeIf { record.readUInt32(0) == target }
    }
}

internal data class KernValueOffset(
    internal val value: Int,
) {
    internal fun next(): KernValueOffset = KernValueOffset((value + 2) and 0xFFFF)
}

internal data class KernStateEntry(
    internal val newState: Int,
    internal val flags: Int,
) {
    internal val hasOffset: Boolean
        get() = flags and 0x3FFF != 0

    internal val valueOffset: KernValueOffset
        get() = KernValueOffset(flags and 0x3FFF)

    internal val hasReset: Boolean
        get() = flags and 0x2000 != 0

    internal val hasAdvance: Boolean
        get() = flags and 0x4000 == 0

    internal val hasPush: Boolean
        get() = flags and 0x8000 != 0
}

internal class KernStateTable private constructor(
    internal val numberOfClasses: Int,
    private val firstGlyph: Int,
    private val classes: ByteSlice,
    private val stateArrayOffset: Int,
    private val stateArray: ByteSlice,
    private val entries: ByteSlice,
    private val actions: ByteSlice,
) {
    internal val numberOfStates: Int
        get() = if (numberOfClasses == 0) 0 else stateArray.size / numberOfClasses

    internal val numberOfEntries: Int
        get() = entries.size / STATE_ENTRY_SIZE

    internal val numberOfActions: Int
        get() = actions.size / 2

    internal fun glyphClass(glyphId: Int): Int? {
        if (glyphId == 0xFFFF) return DELETED_GLYPH_CLASS
        if (glyphId !in 0..0xFFFF) return null
        val index = glyphId - firstGlyph
        if (index < 0) return null
        return classes.readUInt8(index)
    }

    internal fun entry(
        state: Int,
        glyphClass: Int,
    ): KernStateEntry? {
        if (state < 0) return null
        val resolvedClass = if (glyphClass in 0 until numberOfClasses) glyphClass else OUT_OF_BOUNDS_CLASS
        val index = state.toLong() * numberOfClasses + resolvedClass
        if (index !in 0..Int.MAX_VALUE.toLong()) return null
        val entryIndex = stateArray.readUInt8(index.toInt()) ?: return null
        val offset = entryIndex * STATE_ENTRY_SIZE
        return KernStateEntry(
            newState = entries.readUInt16(offset) ?: return null,
            flags = entries.readUInt16(offset + 2) ?: return null,
        )
    }

    internal fun kerning(offset: KernValueOffset): Int? = actions.readInt16(offset.value)

    internal fun newState(state: Int): Int {
        if (numberOfClasses == 0) return 0
        val value = (state - stateArrayOffset) / numberOfClasses
        return value.takeIf { it in 0..0xFFFF } ?: 0
    }

    internal companion object {
        private const val OUT_OF_BOUNDS_CLASS: Int = 1
        private const val DELETED_GLYPH_CLASS: Int = 2
        private const val STATE_ENTRY_SIZE: Int = 4

        internal fun parse(data: ByteSlice): KernStateTable? {
            val numberOfClasses = data.readUInt16(0) ?: return null
            val classTableOffset = data.readUInt16(2) ?: return null
            val stateArrayOffset = data.readUInt16(4) ?: return null
            val entryTableOffset = data.readUInt16(6) ?: return null

            val firstGlyph = data.readUInt16(classTableOffset) ?: return null
            val glyphCount = data.readUInt16(classTableOffset + 2) ?: return null
            val classes = data.slice(classTableOffset + 4, glyphCount) ?: return null
            val stateArray = data.sliceFrom(stateArrayOffset) ?: return null
            val entries = data.sliceFrom(entryTableOffset) ?: return null
            return KernStateTable(
                numberOfClasses,
                firstGlyph,
                classes,
                stateArrayOffset,
                stateArray,
                entries,
                data,
            )
        }
    }
}

internal class KernSubtable private constructor(
    internal val horizontal: Boolean,
    internal val variable: Boolean,
    internal val hasCrossStream: Boolean,
    internal val hasStateMachine: Boolean,
    internal val format: KernFormat,
    internal val pairs: KernPairs?,
    internal val stateTable: KernStateTable?,
    private val format2: KernFormat2?,
    private val format3: KernFormat3?,
) {
    internal fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int? =
        when (format) {
            KernFormat.FORMAT_0 -> pairs?.kerning(leftGlyph, rightGlyph)
            KernFormat.FORMAT_2 -> format2?.glyphsKerning(leftGlyph, rightGlyph)
            KernFormat.FORMAT_3 -> format3?.glyphsKerning(leftGlyph, rightGlyph)
            KernFormat.FORMAT_1 -> null
        }

    internal companion object {
        internal fun create(
            isAat: Boolean,
            coverage: Int,
            formatId: Int,
            headerSize: Int,
            data: ByteSlice,
        ): KernSubtable? {
            var pairs: KernPairs? = null
            var stateTable: KernStateTable? = null
            var format2: KernFormat2? = null
            var format3: KernFormat3? = null
            val format =
                when (formatId) {
                    0 -> {
                        val count = data.readUInt16(0) ?: return null
                        val recordsData = data.slice(8, count * KERN_PAIR_SIZE) ?: return null
                        pairs = KernPairs(FixedRecordArray.create(recordsData, count, KERN_PAIR_SIZE) ?: return null)
                        KernFormat.FORMAT_0
                    }

                    1 -> {
                        stateTable = KernStateTable.parse(data) ?: return null
                        KernFormat.FORMAT_1
                    }

                    2 -> {
                        format2 = KernFormat2(headerSize, data)
                        KernFormat.FORMAT_2
                    }

                    3 -> {
                        format3 = KernFormat3(data)
                        KernFormat.FORMAT_3
                    }

                    else -> {
                        return null
                    }
                }

            return KernSubtable(
                horizontal = if (isAat) coverage and 0x80 == 0 else coverage and 0x01 != 0,
                variable = isAat && coverage and 0x20 != 0,
                hasCrossStream = if (isAat) coverage and 0x40 != 0 else coverage and 0x04 != 0,
                hasStateMachine = formatId == 1,
                format = format,
                pairs = pairs,
                stateTable = stateTable,
                format2 = format2,
                format3 = format3,
            )
        }

        private const val KERN_PAIR_SIZE: Int = 6
    }
}

internal class KernSubtables internal constructor(
    internal val isAat: Boolean,
    internal val size: Long,
    private val data: ByteSlice,
) {
    internal val isEmpty: Boolean
        get() = size == 0L

    internal fun iterator(): KernSubtableIterator = KernSubtableIterator(isAat, size, data)
}

internal class KernSubtableIterator internal constructor(
    private val isAat: Boolean,
    private val size: Long,
    private val data: ByteSlice,
) {
    private var index: Long = 0
    private var offset: Int = 0

    internal fun next(): KernSubtable? {
        if (index == size || offset >= data.size) return null

        val headerSize = if (isAat) AAT_HEADER_SIZE else OPENTYPE_HEADER_SIZE
        val length: Long
        val coverage: Int
        val formatId: Int
        if (isAat) {
            length = data.readUInt32(offset) ?: return null
            coverage = data.readUInt8(offset + 4) ?: return null
            formatId = data.readUInt8(offset + 5) ?: return null
            if (data.readUInt16(offset + 6) == null || formatId > 3) return null
        } else {
            if (data.readUInt16(offset) == null) return null
            length = data.readUInt16(offset + 2)?.toLong() ?: return null
            formatId = data.readUInt8(offset + 4) ?: return null
            coverage = data.readUInt8(offset + 5) ?: return null
            if (formatId != 0 && formatId != 2) return null
        }

        val bodyLength =
            if (!isAat && size == 1L) {
                data.size - offset - headerSize
            } else {
                val value = length - headerSize
                if (value !in 0..Int.MAX_VALUE.toLong()) return null
                value.toInt()
            }
        val body = data.slice(offset + headerSize, bodyLength) ?: return null
        offset += headerSize + bodyLength
        index++
        return KernSubtable.create(isAat, coverage, formatId, headerSize, body)
    }

    private companion object {
        private const val OPENTYPE_HEADER_SIZE: Int = 6
        private const val AAT_HEADER_SIZE: Int = 8
    }
}

internal class KernTable private constructor(
    internal val subtables: KernSubtables,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): KernTable? {
            val version = data.readUInt16(0) ?: return null
            val isAat = version != 0
            val count =
                if (isAat) {
                    if (data.readUInt16(2) == null) return null
                    data.readUInt32(4) ?: return null
                } else {
                    data.readUInt16(2)?.toLong() ?: return null
                }
            val headerSize = if (isAat) 8 else 4
            val subtablesData = data.sliceFrom(headerSize) ?: return null
            return KernTable(KernSubtables(isAat, count, subtablesData))
        }
    }
}

private class KernFormat2(
    private val headerSize: Int,
    private val data: ByteSlice,
) {
    internal fun glyphsKerning(
        left: Int,
        right: Int,
    ): Int? {
        if (left !in 0..0xFFFF || right !in 0..0xFFFF) return null
        if (data.readUInt16(0) == null) return null
        val leftTableOffset = (data.readUInt16(2) ?: return null) - headerSize
        val rightTableOffset = (data.readUInt16(4) ?: return null) - headerSize
        val arrayOffset = (data.readUInt16(6) ?: return null) - headerSize
        if (leftTableOffset < 0 || rightTableOffset < 0 || arrayOffset < 0) return null

        val leftClass = format2Class(left, leftTableOffset) ?: 0
        val rightClass = format2Class(right, rightTableOffset) ?: 0
        if (leftClass < arrayOffset) return null
        val valueOffset = leftClass + rightClass - headerSize
        return data.readInt16(valueOffset)
    }

    private fun format2Class(
        glyphId: Int,
        offset: Int,
    ): Int? {
        val firstGlyph = data.readUInt16(offset) ?: return null
        val index = glyphId - firstGlyph
        if (index < 0) return null
        val count = data.readUInt16(offset + 2) ?: return null
        if (index >= count) return null
        return data.readUInt16(offset + 4 + index * 2)
    }
}

private class KernFormat3(
    private val data: ByteSlice,
) {
    internal fun glyphsKerning(
        left: Int,
        right: Int,
    ): Int? {
        if (left !in 0..0xFFFF || right !in 0..0xFFFF) return null
        val glyphCount = data.readUInt16(0) ?: return null
        val valueCount = data.readUInt8(2) ?: return null
        val leftClassCount = data.readUInt8(3) ?: return null
        val rightClassCount = data.readUInt8(4) ?: return null
        if (data.readUInt8(5) == null) return null

        var offset = 6
        val values = data.slice(offset, valueCount * 2) ?: return null
        offset += valueCount * 2
        val leftClasses = data.slice(offset, glyphCount) ?: return null
        offset += glyphCount
        val rightClasses = data.slice(offset, glyphCount) ?: return null
        offset += glyphCount
        val indexCount = leftClassCount * rightClassCount
        val indices = data.slice(offset, indexCount) ?: return null

        val leftClass = leftClasses.readUInt8(left) ?: return null
        val rightClass = rightClasses.readUInt8(right) ?: return null
        if (leftClass > leftClassCount || rightClass > rightClassCount) return null
        val index = leftClass * rightClassCount + rightClass
        val valueIndex = indices.readUInt8(index) ?: return null
        return values.readInt16(valueIndex * 2)
    }
}
