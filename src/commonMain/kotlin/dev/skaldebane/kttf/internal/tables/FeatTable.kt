package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal data class FeatSettingName(
    internal val setting: Int,
    internal val nameIndex: Int,
)

internal class FeatSettingNames internal constructor(
    private val records: FixedRecordArray,
) {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): FeatSettingName? {
        val record = records[index] ?: return null
        return FeatSettingName(
            setting = record.readUInt16(0) ?: return null,
            nameIndex = record.readUInt16(2) ?: return null,
        )
    }
}

internal data class FeatFeatureName(
    internal val feature: Int,
    internal val settingNames: FeatSettingNames,
    internal val defaultSettingIndex: Int,
    internal val exclusive: Boolean,
    internal val nameIndex: Int,
)

internal class FeatFeatureNames internal constructor(
    private val data: ByteSlice,
    private val records: FixedRecordArray,
) : Iterable<FeatFeatureName> {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): FeatFeatureName? {
        val record = records[index] ?: return null
        val count = record.readUInt16(2) ?: return null
        val offset = record.readUInt32(4)?.toIntOrNull() ?: return null
        val settingData = data.sliceFrom(offset) ?: return null
        val settings = FixedRecordArray.create(settingData, count, 4) ?: return null
        val flags = record.readUInt8(8) ?: return null
        return FeatFeatureName(
            feature = record.readUInt16(0) ?: return null,
            settingNames = FeatSettingNames(settings),
            defaultSettingIndex = if (flags and 0x40 != 0) record.readUInt8(9) ?: return null else 0,
            exclusive = flags and 0x80 != 0,
            nameIndex = record.readUInt16(10) ?: return null,
        )
    }

    internal fun find(feature: Int): FeatFeatureName? {
        if (feature !in 0..0xFFFF) return null
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val value = records[middle]?.readUInt16(0) ?: return null
            when {
                feature < value -> high = middle - 1
                feature > value -> low = middle + 1
                else -> return get(middle)
            }
        }
        return null
    }

    override fun iterator(): Iterator<FeatFeatureName> =
        object : Iterator<FeatFeatureName> {
            private var index = 0
            private var next = findNext()

            override fun hasNext(): Boolean = next != null

            override fun next(): FeatFeatureName {
                val result = next ?: throw NoSuchElementException()
                next = findNext()
                return result
            }

            private fun findNext(): FeatFeatureName? {
                if (index >= size) return null
                return get(index++)
            }
        }
}

internal class FeatTable private constructor(
    internal val names: FeatFeatureNames,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): FeatTable? {
            if (data.readUInt32(0) != 0x0001_0000L) return null
            val count = data.readUInt16(4) ?: return null
            val recordsData = data.sliceFrom(12) ?: return null
            val records = FixedRecordArray.create(recordsData, count, 12) ?: return null
            return FeatTable(FeatFeatureNames(data, records))
        }
    }
}
