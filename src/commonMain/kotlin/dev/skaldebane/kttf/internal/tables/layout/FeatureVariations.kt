package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates

internal class FeatureVariations private constructor(
    private val data: ByteSlice,
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal fun conditionCount(variationIndex: Int): Int? = conditionSet(variationIndex)?.size

    internal fun condition(
        variationIndex: Int,
        conditionIndex: Int,
    ): Condition? = conditionSet(variationIndex)?.get(conditionIndex)

    internal fun findIndex(coordinates: VariationCoordinates): Int? {
        for (index in 0 until size) {
            val offset = records.readUInt32(index * RECORD_SIZE)?.toIndex() ?: return null
            val conditions = data.sliceFrom(offset)?.let(ConditionSet::parse) ?: return null
            if (conditions.evaluate(coordinates)) return index
        }
        return null
    }

    internal fun findSubstitute(
        featureIndex: Int,
        variationIndex: Int,
    ): Feature? {
        if (featureIndex !in 0..0xFFFF || variationIndex !in 0 until size) return null
        val offset = records.readUInt32(variationIndex * RECORD_SIZE + 4)?.toIndex() ?: return null
        return data.sliceFrom(offset)?.let { FeatureTableSubstitution.parse(it, featureIndex) }
    }

    private fun conditionSet(variationIndex: Int): ConditionSet? {
        if (variationIndex !in 0 until size) return null
        val offset = records.readUInt32(variationIndex * RECORD_SIZE)?.toIndex() ?: return null
        return data.sliceFrom(offset)?.let(ConditionSet::parse)
    }

    internal companion object {
        private const val RECORD_SIZE = 8

        internal fun parse(data: ByteSlice): FeatureVariations? {
            if (data.readUInt16(0) != 1 || data.readUInt16(2) == null) return null
            val count = data.readUInt32(4) ?: return null
            val recordsSize = count * RECORD_SIZE
            if (count > Int.MAX_VALUE || recordsSize > Int.MAX_VALUE) return null
            return FeatureVariations(data, data.slice(8, recordsSize.toInt()) ?: return null, count.toInt())
        }
    }
}

internal class ConditionSet(
    private val data: ByteSlice,
    private val offsets: ByteSlice,
    internal val size: Int,
) {
    internal operator fun get(index: Int): Condition? {
        if (index !in 0 until size) return null
        val offset = offsets.readUInt32(index * 4)?.toIndex() ?: return null
        return data.sliceFrom(offset)?.let(Condition::parse)
    }

    fun evaluate(coordinates: VariationCoordinates): Boolean {
        for (index in 0 until size) {
            val offset = offsets.readUInt32(index * 4)?.toIndex() ?: return false
            val condition = data.sliceFrom(offset)?.let(Condition::parse) ?: return false
            if (!condition.evaluate(coordinates)) return false
        }
        return true
    }

    companion object {
        fun parse(data: ByteSlice): ConditionSet? {
            val count = data.readUInt16(0) ?: return null
            return ConditionSet(data, data.slice(2, count * 4) ?: return null, count)
        }
    }
}

internal data class Condition(
    internal val axisIndex: Int,
    internal val minimum: Int,
    internal val maximum: Int,
) {
    fun evaluate(coordinates: VariationCoordinates): Boolean {
        val coordinate = coordinates[axisIndex]?.rawValue ?: 0
        return coordinate in minimum..maximum
    }

    companion object {
        fun parse(data: ByteSlice): Condition? {
            if (data.readUInt16(0) != 1) return null
            return Condition(
                data.readUInt16(2) ?: return null,
                data.readInt16(4) ?: return null,
                data.readInt16(6) ?: return null,
            )
        }
    }
}

private object FeatureTableSubstitution {
    fun parse(
        data: ByteSlice,
        featureIndex: Int,
    ): Feature? {
        if (data.readUInt16(0) != 1 || data.readUInt16(2) == null) return null
        val count = data.readUInt16(4) ?: return null
        val records = data.slice(6, count * 6) ?: return null
        for (index in 0 until count) {
            val recordOffset = index * 6
            if (records.readUInt16(recordOffset) == featureIndex) {
                val offset = records.readUInt32(recordOffset + 2)?.toIndex() ?: return null
                return parseFeature(data.sliceFrom(offset) ?: return null)
            }
        }
        return null
    }

    private fun parseFeature(data: ByteSlice): Feature? {
        if (data.readUInt16(0) == null) return null
        val count = data.readUInt16(2) ?: return null
        return Feature(Tag.parse("DFLT"), UInt16View.parse(data, 4, count) ?: return null)
    }
}
