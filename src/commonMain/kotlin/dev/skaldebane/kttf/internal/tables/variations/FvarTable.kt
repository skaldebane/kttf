package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class VariationAxis internal constructor(
    internal val tag: Tag,
    internal val minValue: Float,
    internal val defaultValue: Float,
    internal val maxValue: Float,
    internal val nameId: Int,
    internal val hidden: Boolean,
) {
    /** Applies the pinned upstream default normalization, including truncation to F2DOT14. */
    internal fun normalize(value: Float): NormalizedCoordinate {
        var normalized =
            when {
                value > maxValue -> maxValue
                value < minValue -> minValue
                else -> value
            }

        normalized =
            when {
                normalized == defaultValue -> {
                    0.0f
                }

                normalized < defaultValue -> {
                    (normalized - defaultValue) / (defaultValue - minValue)
                }

                else -> {
                    (normalized - defaultValue) / (maxValue - defaultValue)
                }
            }

        return NormalizedCoordinate.fromFloat(normalized)
    }
}

internal class VariationAxes internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
    private val recordSize: Int,
) : Iterable<VariationAxis> {
    internal operator fun get(index: Int): VariationAxis? {
        if (index !in 0 until size) return null
        val record = data.slice(index * recordSize, AXIS_RECORD_SIZE) ?: return null
        val rawMinimum = record.readFixed16Dot16(4) ?: return null
        val defaultValue = record.readFixed16Dot16(8) ?: return null
        val rawMaximum = record.readFixed16Dot16(12) ?: return null
        val flags = record.readUInt16(16) ?: return null

        return VariationAxis(
            tag = Tag.fromInt(record.readInt32(0) ?: return null),
            minValue = minOf(defaultValue, rawMinimum),
            defaultValue = defaultValue,
            maxValue = maxOf(defaultValue, rawMaximum),
            nameId = record.readUInt16(18) ?: return null,
            hidden = flags and HIDDEN_AXIS_FLAG != 0,
        )
    }

    override fun iterator(): Iterator<VariationAxis> =
        object : Iterator<VariationAxis> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): VariationAxis = get(index++) ?: throw NoSuchElementException()
        }

    private companion object {
        private const val AXIS_RECORD_SIZE: Int = 20
        private const val HIDDEN_AXIS_FLAG: Int = 0x0008
    }
}

internal class FvarInstanceCoordinates internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
) : Iterable<Float> {
    internal operator fun get(index: Int): Float? {
        if (index !in 0 until size) return null
        return data.readFixed16Dot16(index * COORDINATE_SIZE)
    }

    override fun iterator(): Iterator<Float> =
        object : Iterator<Float> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): Float = get(index++) ?: throw NoSuchElementException()
        }

    private companion object {
        private const val COORDINATE_SIZE: Int = 4
    }
}

internal class FvarNamedInstance internal constructor(
    internal val subfamilyNameId: Int,
    internal val flags: Int,
    internal val coordinates: FvarInstanceCoordinates,
    internal val postScriptNameId: Int?,
)

internal class FvarNamedInstances internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
    private val recordSize: Int,
    private val axisCount: Int,
) : Iterable<FvarNamedInstance> {
    internal operator fun get(index: Int): FvarNamedInstance? {
        if (index !in 0 until size) return null
        val record = data.slice(index * recordSize, recordSize) ?: return null
        val coordinatesSize = axisCount * COORDINATE_SIZE
        val coordinates = record.slice(INSTANCE_HEADER_SIZE, coordinatesSize) ?: return null
        val postScriptNameOffset = INSTANCE_HEADER_SIZE + coordinatesSize
        val postScriptNameId =
            if (recordSize >= postScriptNameOffset + 2) {
                record.readUInt16(postScriptNameOffset)?.takeUnless { it == MISSING_NAME_ID }
            } else {
                null
            }

        return FvarNamedInstance(
            subfamilyNameId = record.readUInt16(0) ?: return null,
            flags = record.readUInt16(2) ?: return null,
            coordinates = FvarInstanceCoordinates(coordinates, axisCount),
            postScriptNameId = postScriptNameId,
        )
    }

    override fun iterator(): Iterator<FvarNamedInstance> =
        object : Iterator<FvarNamedInstance> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): FvarNamedInstance = get(index++) ?: throw NoSuchElementException()
        }

    private companion object {
        private const val INSTANCE_HEADER_SIZE: Int = 4
        private const val COORDINATE_SIZE: Int = 4
        private const val MISSING_NAME_ID: Int = 0xFFFF
    }
}

internal class FvarTable private constructor(
    internal val axes: VariationAxes,
    internal val namedInstances: FvarNamedInstances,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): FvarTable? {
            if (data.readUInt32(0) != VERSION_1_0) return null

            val axesOffset = data.readUInt16(4) ?: return null
            val axisCount = data.readUInt16(8) ?: return null
            if (axisCount == 0) return null

            val axisSize = data.readUInt16(10) ?: return null
            val instanceCount = data.readUInt16(12) ?: return null
            val instanceSize = data.readUInt16(14) ?: return null
            if (axesOffset < HEADER_SIZE || axisSize < AXIS_RECORD_SIZE) return null

            val axesData = data.checkedRecords(axesOffset, axisCount, axisSize) ?: return null
            val instancesOffset = axesOffset + axesData.size
            val minimumInstanceSize = INSTANCE_HEADER_SIZE + axisCount * COORDINATE_SIZE
            if (instanceCount != 0 && instanceSize < minimumInstanceSize) return null
            val instancesData = data.checkedRecords(instancesOffset, instanceCount, instanceSize) ?: return null

            return FvarTable(
                axes = VariationAxes(axesData, axisCount, axisSize),
                namedInstances = FvarNamedInstances(instancesData, instanceCount, instanceSize, axisCount),
            )
        }

        private const val VERSION_1_0: Long = 0x0001_0000L
        private const val HEADER_SIZE: Int = 16
        private const val AXIS_RECORD_SIZE: Int = 20
        private const val INSTANCE_HEADER_SIZE: Int = 4
        private const val COORDINATE_SIZE: Int = 4
    }
}

private fun ByteSlice.checkedRecords(
    offset: Int,
    count: Int,
    recordSize: Int,
): ByteSlice? {
    if (offset < 0 || offset > size || count < 0 || recordSize < 0) return null
    if (count == 0) return slice(offset, 0)
    if (recordSize == 0 || count > (size - offset) / recordSize) return null
    return slice(offset, count * recordSize)
}

private fun ByteSlice.readFixed16Dot16(offset: Int): Float? = readInt32(offset)?.toFloat()?.div(FIXED_16_16_SCALE)

private const val FIXED_16_16_SCALE: Float = 65536.0f
