package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class AvarAxisValueMap internal constructor(
    internal val fromCoordinate: Int,
    internal val toCoordinate: Int,
)

internal class AvarSegmentMap internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
) : Iterable<AvarAxisValueMap> {
    internal operator fun get(index: Int): AvarAxisValueMap? {
        if (index !in 0 until size) return null
        val offset = index * VALUE_MAP_SIZE
        return AvarAxisValueMap(
            fromCoordinate = data.readInt16(offset) ?: return null,
            toCoordinate = data.readInt16(offset + 2) ?: return null,
        )
    }

    /** Applies the pinned upstream HarfBuzz-style map, including endpoint extrapolation. */
    internal fun map(value: NormalizedCoordinate): NormalizedCoordinate? {
        if (size == 0) return value
        if (size == 1) {
            val record = get(0) ?: return null
            return extrapolate(value.rawValue, record)
        }

        val first = get(0) ?: return null
        if (value.rawValue <= first.fromCoordinate) return extrapolate(value.rawValue, first)

        var index = 1
        while (index < size && value.rawValue > (get(index) ?: return null).fromCoordinate) index++
        if (index == size) index--

        val current = get(index) ?: return null
        if (value.rawValue >= current.fromCoordinate) return extrapolate(value.rawValue, current)

        val previous = get(index - 1) ?: return null
        if (previous.fromCoordinate == current.fromCoordinate) {
            return NormalizedCoordinate.fromF2Dot14(previous.toCoordinate)
        }

        val denominator = current.fromCoordinate.toLong() - previous.fromCoordinate
        val numerator =
            (current.toCoordinate.toLong() - previous.toCoordinate) *
                (value.rawValue.toLong() - previous.fromCoordinate) +
                denominator / 2
        val mapped = previous.toCoordinate.toLong() + numerator / denominator
        if (mapped !in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()) return null
        return NormalizedCoordinate.fromF2Dot14(mapped.toInt())
    }

    override fun iterator(): Iterator<AvarAxisValueMap> =
        object : Iterator<AvarAxisValueMap> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): AvarAxisValueMap = get(index++) ?: throw NoSuchElementException()
        }

    private fun extrapolate(
        value: Int,
        record: AvarAxisValueMap,
    ): NormalizedCoordinate? {
        val mapped = value.toLong() - record.fromCoordinate + record.toCoordinate
        if (mapped !in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()) return null
        return NormalizedCoordinate.fromF2Dot14(mapped.toInt())
    }

    private companion object {
        private const val VALUE_MAP_SIZE: Int = 4
    }
}

internal class AvarSegmentMaps internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
) : Iterable<AvarSegmentMap> {
    internal operator fun get(index: Int): AvarSegmentMap? {
        if (index !in 0 until size) return null
        var offset = 0
        var currentIndex = 0
        while (currentIndex < index) {
            val count = data.readUInt16(offset) ?: return null
            offset += COUNT_SIZE + count * VALUE_MAP_SIZE
            currentIndex++
        }

        val count = data.readUInt16(offset) ?: return null
        val records = data.slice(offset + COUNT_SIZE, count * VALUE_MAP_SIZE) ?: return null
        return AvarSegmentMap(records, count)
    }

    override fun iterator(): Iterator<AvarSegmentMap> =
        object : Iterator<AvarSegmentMap> {
            private var offset = 0
            private var remaining = size

            override fun hasNext(): Boolean = remaining > 0

            override fun next(): AvarSegmentMap {
                if (!hasNext()) throw NoSuchElementException()
                val count = data.readUInt16(offset) ?: throw NoSuchElementException()
                val records =
                    data.slice(offset + COUNT_SIZE, count * VALUE_MAP_SIZE)
                        ?: throw NoSuchElementException()
                offset += COUNT_SIZE + records.size
                remaining--
                return AvarSegmentMap(records, count)
            }
        }

    private companion object {
        private const val COUNT_SIZE: Int = 2
        private const val VALUE_MAP_SIZE: Int = 4
    }
}

internal class AvarTable private constructor(
    internal val segmentMaps: AvarSegmentMaps,
) {
    internal fun mapCoordinate(
        coordinates: VariationCoordinates,
        coordinateIndex: Int,
    ): Boolean {
        if (segmentMaps.size != coordinates.size) return false
        if (coordinateIndex !in 0 until coordinates.size) return true

        val coordinate = coordinates[coordinateIndex] ?: return false
        val mapped = segmentMaps[coordinateIndex]?.map(coordinate) ?: return false
        return coordinates.set(coordinateIndex, mapped)
    }

    internal fun mapAll(coordinates: VariationCoordinates): Boolean {
        if (segmentMaps.size != coordinates.size) return false
        var index = 0
        while (index < coordinates.size) {
            if (!mapCoordinate(coordinates, index)) return false
            index++
        }
        return true
    }

    internal companion object {
        internal fun parse(data: ByteSlice): AvarTable? {
            if (data.readUInt32(0) != VERSION_1_0) return null
            val axisCount = data.readUInt16(6) ?: return null

            var offset = HEADER_SIZE
            var index = 0
            while (index < axisCount) {
                val mapCount = data.readUInt16(offset) ?: return null
                offset += COUNT_SIZE
                if (mapCount > (data.size - offset) / VALUE_MAP_SIZE) return null
                offset += mapCount * VALUE_MAP_SIZE
                index++
            }

            val mapsData = data.slice(HEADER_SIZE, offset - HEADER_SIZE) ?: return null
            return AvarTable(AvarSegmentMaps(mapsData, axisCount))
        }

        private const val VERSION_1_0: Long = 0x0001_0000L
        private const val HEADER_SIZE: Int = 8
        private const val COUNT_SIZE: Int = 2
        private const val VALUE_MAP_SIZE: Int = 4
    }
}
