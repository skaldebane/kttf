package dev.skaldebane.kttf.internal.tables.variations

import kotlin.jvm.JvmInline

/** A normalized variation coordinate stored in OpenType F2DOT14 units. */
@JvmInline
internal value class NormalizedCoordinate private constructor(
    internal val rawValue: Int,
) {
    internal fun toFloat(): Float = rawValue.toFloat() / SCALE

    internal companion object {
        internal val ZERO: NormalizedCoordinate = NormalizedCoordinate(0)

        internal fun fromF2Dot14(value: Int): NormalizedCoordinate =
            NormalizedCoordinate(value.coerceIn(MIN_VALUE, MAX_VALUE))

        internal fun fromFloat(value: Float): NormalizedCoordinate {
            val bounded =
                when {
                    value > 1.0f -> 1.0f
                    value < -1.0f -> -1.0f
                    else -> value
                }
            return fromF2Dot14((bounded * SCALE).toInt())
        }

        private const val MIN_VALUE: Int = -16384
        private const val MAX_VALUE: Int = 16384
        private const val SCALE: Float = 16384.0f
    }
}

/** Mutable normalized coordinates shared by variation table evaluators. */
internal class VariationCoordinates private constructor(
    private val values: ShortArray,
) {
    internal val size: Int
        get() = values.size

    internal operator fun get(index: Int): NormalizedCoordinate? =
        values.getOrNull(index)?.let { NormalizedCoordinate.fromF2Dot14(it.toInt()) }

    internal operator fun set(
        index: Int,
        value: NormalizedCoordinate,
    ): Boolean {
        if (index !in values.indices) return false
        values[index] = value.rawValue.toShort()
        return true
    }

    internal fun hasNonDefaultValues(): Boolean = values.any { it.toInt() != 0 }

    internal companion object {
        internal fun zeros(size: Int): VariationCoordinates? {
            if (size < 0) return null
            return VariationCoordinates(ShortArray(size))
        }

        internal fun copyOf(values: List<NormalizedCoordinate>): VariationCoordinates =
            VariationCoordinates(ShortArray(values.size) { values[it].rawValue.toShort() })
    }
}
