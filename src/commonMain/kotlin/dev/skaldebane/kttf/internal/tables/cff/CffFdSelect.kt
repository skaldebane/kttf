package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream

internal sealed interface CffFdSelect {
    fun fontDictionaryIndex(glyphId: Int): Int?
}

internal fun parseCffFdSelect(
    data: ByteSlice,
    offset: Int,
    glyphCount: Int,
): CffFdSelect? {
    val stream = Stream.at(data, offset) ?: return null
    return when (stream.readUInt8() ?: return null) {
        0 -> CffFdSelectFormat0(stream.readBytes(glyphCount) ?: return null)
        3 -> CffFdSelectFormat3(stream.tail() ?: return null)
        else -> null
    }
}

private class CffFdSelectFormat0(
    private val fontDictionaryIndices: ByteSlice,
) : CffFdSelect {
    override fun fontDictionaryIndex(glyphId: Int): Int? = fontDictionaryIndices.readUInt8(glyphId)
}

private class CffFdSelectFormat3(
    private val data: ByteSlice,
) : CffFdSelect {
    override fun fontDictionaryIndex(glyphId: Int): Int? {
        if (glyphId !in 0..UShort.MAX_VALUE.toInt()) return null

        val stream = Stream.atStart(data)
        val rangeCount = stream.readUInt16() ?: return null
        if (rangeCount == 0 || rangeCount == UShort.MAX_VALUE.toInt()) return null

        var previousFirstGlyph = stream.readUInt16() ?: return null
        var previousIndex = stream.readUInt8() ?: return null
        var range = 1
        while (range < rangeCount + 1) {
            val currentFirstGlyph = stream.readUInt16() ?: return null
            if (glyphId in previousFirstGlyph until currentFirstGlyph) return previousIndex

            previousIndex = stream.readUInt8() ?: return null
            previousFirstGlyph = currentFirstGlyph
            range++
        }

        return null
    }
}
