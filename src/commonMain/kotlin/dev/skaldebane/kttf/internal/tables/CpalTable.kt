package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal data class ColorRgba(
    internal val red: Int,
    internal val green: Int,
    internal val blue: Int,
    internal val alpha: Int,
) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255)
    }

    internal fun withAlpha(multiplier: Float): ColorRgba {
        val resolved = (((alpha.toFloat() / 255f) * multiplier) * 255f).toInt().coerceIn(0, 255)
        return copy(alpha = resolved)
    }
}

internal class CpalTable private constructor(
    private val paletteIndices: FixedRecordArray,
    private val colors: FixedRecordArray,
) {
    internal val paletteCount: Int
        get() = paletteIndices.size

    internal fun color(
        paletteIndex: Int,
        paletteEntry: Int,
    ): ColorRgba? {
        if (paletteEntry !in 0..0xFFFF) return null
        val first = paletteIndices[paletteIndex]?.readUInt16(0) ?: return null
        if (first > 0xFFFF - paletteEntry) return null
        val color = colors[first + paletteEntry] ?: return null
        return ColorRgba(
            red = color.readUInt8(2) ?: return null,
            green = color.readUInt8(1) ?: return null,
            blue = color.readUInt8(0) ?: return null,
            alpha = color.readUInt8(3) ?: return null,
        )
    }

    internal companion object {
        internal fun parse(data: ByteSlice): CpalTable? {
            val version = data.readUInt16(0) ?: return null
            if (version > 1) return null
            val paletteCount = data.readUInt16(4) ?: return null
            if (paletteCount == 0) return null
            val colorCount = data.readUInt16(6) ?: return null
            val colorsOffset = data.readUInt32(8)?.toIndexOrNull() ?: return null
            val indicesData = data.checkedRecords(12, paletteCount, 2) ?: return null
            val colorsData = data.checkedRecords(colorsOffset, colorCount, 4) ?: return null
            return CpalTable(
                FixedRecordArray.create(indicesData, paletteCount, 2) ?: return null,
                FixedRecordArray.create(colorsData, colorCount, 4) ?: return null,
            )
        }
    }
}

private fun ByteSlice.checkedRecords(
    offset: Int,
    count: Int,
    size: Int,
): ByteSlice? {
    if (count < 0 || size <= 0 || count > Int.MAX_VALUE / size) return null
    return slice(offset, count * size)
}

private fun Long.toIndexOrNull(): Int? = if (this <= Int.MAX_VALUE) toInt() else null
