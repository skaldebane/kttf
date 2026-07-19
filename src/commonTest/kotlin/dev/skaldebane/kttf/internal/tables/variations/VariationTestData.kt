package dev.skaldebane.kttf.internal.tables.variations

internal class VariationBytes {
    private val values = ArrayList<Byte>()

    internal val size: Int
        get() = values.size

    internal fun u8(value: Int): VariationBytes {
        values.add(value.toByte())
        return this
    }

    internal fun u16(value: Int): VariationBytes =
        u8(value ushr 8)
            .u8(value)

    internal fun i16(value: Int): VariationBytes = u16(value and 0xFFFF)

    internal fun u32(value: Long): VariationBytes =
        u8((value ushr 24).toInt())
            .u8((value ushr 16).toInt())
            .u8((value ushr 8).toInt())
            .u8(value.toInt())

    internal fun i32(value: Int): VariationBytes = u32(value.toLong() and 0xFFFF_FFFFL)

    internal fun bytes(value: ByteArray): VariationBytes {
        value.forEach(values::add)
        return this
    }

    internal fun padTo(size: Int): VariationBytes {
        while (values.size < size) values.add(0)
        return this
    }

    internal fun build(): ByteArray = values.toByteArray()
}

internal fun coordinates(vararg values: Int): VariationCoordinates =
    VariationCoordinates.copyOf(values.map(NormalizedCoordinate::fromF2Dot14))

internal fun variationStore(
    rows: List<Pair<Int, Int>> = listOf(10 to 20, -10 to 40),
    longWords: Boolean = false,
): ByteArray {
    val regionList =
        VariationBytes()
            .u16(1)
            .u16(2)
            .i16(-16384)
            .i16(-8192)
            .i16(0)
            .i16(0)
            .i16(16384)
            .i16(16384)
            .build()
    val dataOffset = 12 + regionList.size
    val output =
        VariationBytes()
            .u16(1)
            .u32(12)
            .u16(1)
            .u32(dataOffset.toLong())
            .bytes(regionList)
            .u16(rows.size)
            .u16(if (longWords) 0x8001 else 1)
            .u16(2)
            .u16(0)
            .u16(1)
    rows.forEach { row ->
        if (longWords) {
            output.i32(row.first).i16(row.second)
        } else {
            output.i16(row.first).u8(row.second)
        }
    }
    return output.build()
}

internal fun deltaSetMap(vararg packedEntries: Int): ByteArray {
    val output =
        VariationBytes()
            .u8(0)
            .u8(0x13)
            .u16(packedEntries.size)
    packedEntries.forEach(output::u16)
    return output.build()
}

internal fun putUInt16(
    data: ByteArray,
    offset: Int,
    value: Int,
) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}

internal fun putUInt32(
    data: ByteArray,
    offset: Int,
    value: Long,
) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}
