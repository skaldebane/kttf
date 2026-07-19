package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal data class StatAxisRecord(
    internal val tag: Tag,
    internal val nameId: Int,
    internal val ordering: Int,
)

internal class StatAxes internal constructor(
    private val records: FixedRecordArray,
) {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): StatAxisRecord? {
        val record = records[index] ?: return null
        return StatAxisRecord(
            tag = Tag.fromInt(record.readInt32(0) ?: return null),
            nameId = record.readUInt16(4) ?: return null,
            ordering = record.readUInt16(6) ?: return null,
        )
    }
}

internal data class StatAxisValueFlags(
    internal val value: Int,
) {
    internal val olderSiblingAttribute: Boolean
        get() = value and 0x0001 != 0

    internal val elidable: Boolean
        get() = value and 0x0002 != 0
}

internal data class StatAxisValuePair(
    internal val axisIndex: Int,
    internal val value: Float,
)

internal class StatAxisValuePairs internal constructor(
    private val records: FixedRecordArray,
) {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): StatAxisValuePair? {
        val record = records[index] ?: return null
        return StatAxisValuePair(
            axisIndex = record.readUInt16(0) ?: return null,
            value = record.readFixed(2) ?: return null,
        )
    }
}

internal sealed interface StatAxisValue {
    val flags: StatAxisValueFlags
    val valueNameId: Int
    val value: Float?

    val isElidable: Boolean
        get() = flags.elidable

    val isOlderSibling: Boolean
        get() = flags.olderSiblingAttribute

    fun contains(value: Float): Boolean =
        this.value == value ||
            (this is Format2 && value >= rangeMinValue && value < rangeMaxValue)

    data class Format1(
        internal val axisIndex: Int,
        override val flags: StatAxisValueFlags,
        override val valueNameId: Int,
        override val value: Float,
    ) : StatAxisValue

    data class Format2(
        internal val axisIndex: Int,
        override val flags: StatAxisValueFlags,
        override val valueNameId: Int,
        internal val nominalValue: Float,
        internal val rangeMinValue: Float,
        internal val rangeMaxValue: Float,
    ) : StatAxisValue {
        override val value: Float? = null
    }

    data class Format3(
        internal val axisIndex: Int,
        override val flags: StatAxisValueFlags,
        override val valueNameId: Int,
        override val value: Float,
        internal val linkedValue: Float,
    ) : StatAxisValue

    data class Format4(
        override val flags: StatAxisValueFlags,
        override val valueNameId: Int,
        internal val values: StatAxisValuePairs,
    ) : StatAxisValue {
        override val value: Float? = null
    }
}

internal class StatAxisValueSubtables internal constructor(
    private val data: ByteSlice,
    private val lookupStart: Int,
    private val offsets: FixedRecordArray,
    private val version: Long,
) {
    private var index: Int = 0

    internal val size: Int
        get() = offsets.size

    internal fun next(): StatAxisValue? {
        if (index >= size) return null
        val relativeOffset = offsets[index]?.readUInt16(0) ?: return null
        val absoluteOffset = lookupStart.toLong() + relativeOffset
        if (absoluteOffset !in 0..Int.MAX_VALUE.toLong()) return null
        val subtable = data.sliceFrom(absoluteOffset.toInt()) ?: return null
        index++
        return when (subtable.readUInt16(0)) {
            1 -> {
                StatAxisValue.Format1(
                    axisIndex = subtable.readUInt16(2) ?: return null,
                    flags = StatAxisValueFlags(subtable.readUInt16(4) ?: return null),
                    valueNameId = subtable.readUInt16(6) ?: return null,
                    value = subtable.readFixed(8) ?: return null,
                )
            }

            2 -> {
                StatAxisValue.Format2(
                    axisIndex = subtable.readUInt16(2) ?: return null,
                    flags = StatAxisValueFlags(subtable.readUInt16(4) ?: return null),
                    valueNameId = subtable.readUInt16(6) ?: return null,
                    nominalValue = subtable.readFixed(8) ?: return null,
                    rangeMinValue = subtable.readFixed(12) ?: return null,
                    rangeMaxValue = subtable.readFixed(16) ?: return null,
                )
            }

            3 -> {
                StatAxisValue.Format3(
                    axisIndex = subtable.readUInt16(2) ?: return null,
                    flags = StatAxisValueFlags(subtable.readUInt16(4) ?: return null),
                    valueNameId = subtable.readUInt16(6) ?: return null,
                    value = subtable.readFixed(8) ?: return null,
                    linkedValue = subtable.readFixed(12) ?: return null,
                )
            }

            4 -> {
                if (version < VERSION_1_2) return null
                val count = subtable.readUInt16(2) ?: return null
                val flags = StatAxisValueFlags(subtable.readUInt16(4) ?: return null)
                val valueNameId = subtable.readUInt16(6) ?: return null
                val recordsData = subtable.slice(8, count * AXIS_VALUE_PAIR_SIZE) ?: return null
                val records = FixedRecordArray.create(recordsData, count, AXIS_VALUE_PAIR_SIZE) ?: return null
                StatAxisValue.Format4(flags, valueNameId, StatAxisValuePairs(records))
            }

            else -> {
                null
            }
        }
    }

    private companion object {
        private const val VERSION_1_2: Long = 0x0001_0002L
        private const val AXIS_VALUE_PAIR_SIZE: Int = 6
    }
}

internal class StatTable private constructor(
    internal val version: Long,
    internal val axes: StatAxes,
    internal val fallbackNameId: Int?,
    private val data: ByteSlice,
    private val valueLookupStart: Int,
    private val valueOffsets: FixedRecordArray,
) {
    internal fun subtables(): StatAxisValueSubtables =
        StatAxisValueSubtables(data, valueLookupStart, valueOffsets, version)

    internal fun subtableForAxis(
        axis: Tag,
        matchValue: Float?,
    ): StatAxisValue? {
        val subtables = subtables()
        while (true) {
            val subtable = subtables.next() ?: return null
            when (subtable) {
                is StatAxisValue.Format1 -> {
                    val subtableAxis = axes[subtable.axisIndex]?.tag ?: return null
                    if (subtableAxis != axis) continue
                    if (matchValue == null || matchValue == subtable.value) return subtable
                }

                is StatAxisValue.Format2 -> {
                    val subtableAxis = axes[subtable.axisIndex]?.tag ?: return null
                    if (subtableAxis == axis) continue
                    if (matchValue == null || subtable.contains(matchValue)) return subtable
                }

                is StatAxisValue.Format3 -> {
                    val subtableAxis = axes[subtable.axisIndex]?.tag ?: return null
                    if (subtableAxis != axis) continue
                    if (matchValue == null || matchValue == subtable.value) return subtable
                }

                is StatAxisValue.Format4 -> {
                    continue
                }
            }
        }
    }

    internal companion object {
        private const val AXIS_RECORD_SIZE: Int = 8
        private const val VALUE_OFFSET_SIZE: Int = 2
        private const val VERSION_1_0: Long = 0x0001_0000L
        private const val VERSION_1_1: Long = 0x0001_0001L
        private const val VERSION_1_2: Long = 0x0001_0002L

        internal fun parse(data: ByteSlice): StatTable? {
            val version = data.readUInt32(0) ?: return null
            if (version != VERSION_1_0 && version != VERSION_1_1 && version != VERSION_1_2) return null
            if (data.readUInt16(4) == null) return null
            val axisCount = data.readUInt16(6) ?: return null
            val axisOffset = data.readUInt32(8)?.toIndexOrNull() ?: return null
            val valueCount = data.readUInt16(12) ?: return null
            val valueLookupStart = data.readUInt32(14)?.toIndexOrNull() ?: return null
            val fallbackNameId = if (version >= VERSION_1_1) data.readUInt16(18) ?: return null else null

            val axesData = data.slice(axisOffset, axisCount * AXIS_RECORD_SIZE) ?: return null
            val axes = FixedRecordArray.create(axesData, axisCount, AXIS_RECORD_SIZE) ?: return null
            val offsetsData = data.slice(valueLookupStart, valueCount * VALUE_OFFSET_SIZE) ?: return null
            val offsets = FixedRecordArray.create(offsetsData, valueCount, VALUE_OFFSET_SIZE) ?: return null
            return StatTable(version, StatAxes(axes), fallbackNameId, data, valueLookupStart, offsets)
        }
    }
}

private fun ByteSlice.readFixed(offset: Int): Float? = readInt32(offset)?.toFloat()?.div(65536f)

private fun Long.toIndexOrNull(): Int? = if (this <= Int.MAX_VALUE) toInt() else null
