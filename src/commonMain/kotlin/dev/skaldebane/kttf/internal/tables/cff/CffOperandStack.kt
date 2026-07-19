package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.Stream

internal class CffOperandStack internal constructor(
    internal val capacity: Int,
) {
    init {
        require(capacity >= 0) { "capacity must not be negative" }
    }

    private val values: FloatArray = FloatArray(capacity)

    internal var size: Int = 0
        private set

    internal val isEmpty: Boolean
        get() = size == 0

    internal fun push(value: Float): Boolean {
        if (size == capacity) return false
        values[size] = value
        size += 1
        return true
    }

    internal fun pop(): Float? {
        if (isEmpty) return null
        size -= 1
        return values[size]
    }

    internal fun clear() {
        size = 0
    }

    internal operator fun get(index: Int): Float? {
        if (index !in 0 until size) return null
        return values[index]
    }

    internal fun add(
        index: Int,
        value: Float,
    ): Boolean {
        if (index !in 0 until size) return false
        values[index] += value
        return true
    }

    internal companion object {
        internal const val CFF1_CAPACITY: Int = 48
        internal const val CFF2_CAPACITY: Int = 513
    }
}

internal fun decodeType2Number(
    firstByte: Int,
    stream: Stream,
): Float? =
    when (firstByte) {
        in 32..246 -> {
            (firstByte - 139).toFloat()
        }

        in 247..250 -> {
            val secondByte = stream.readUInt8() ?: return null
            ((firstByte - 247) * 256 + secondByte + 108).toFloat()
        }

        in 251..254 -> {
            val secondByte = stream.readUInt8() ?: return null
            (-(firstByte - 251) * 256 - secondByte - 108).toFloat()
        }

        28 -> {
            stream.readInt16()?.toFloat()
        }

        255 -> {
            stream.readInt32()?.toFloat()?.div(65536.0f)
        }

        else -> {
            null
        }
    }
