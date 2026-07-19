package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.OutlineSink
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import dev.skaldebane.kttf.internal.tables.variations.ItemVariationStore
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates

/** A parsed CFF2 table with variation-aware Type 2 outlines. */
internal class Cff2Table private constructor(
    private val charStrings: CffIndex,
    private val globalSubroutines: CffIndex,
    private val localSubroutines: CffIndex?,
    private val variationStore: ItemVariationStore?,
) {
    internal val glyphCount: Int
        get() = charStrings.size

    internal fun outline(
        glyphId: Int,
        coordinates: VariationCoordinates,
        sink: OutlineSink,
    ): FontBounds? {
        val charString = charStrings[glyphId] ?: return null
        return CffType2Vm(
            globalSubroutines = globalSubroutines,
            localSubroutines = localSubroutines,
            charStrings = charStrings,
            charset = null,
            transform = null,
            sink = sink,
            dialect = CffType2Dialect.CFF2,
            variationStore = variationStore,
            coordinates = coordinates,
        ).outline(charString)
    }

    internal companion object {
        internal fun parse(data: ByteSlice): Cff2Table? {
            val stream = Stream.atStart(data)
            val header = parseCffHeader(stream, CFF2_HEADER_SIZE) ?: return null
            if (header.majorVersion != CFF2_MAJOR_VERSION) return null
            val topDictionaryLength = header.remainder.readUInt16(0) ?: return null

            val topDictionaryData = stream.readBytes(topDictionaryLength) ?: return null
            val topDictionary = parseTopDictionary(topDictionaryData) ?: return null
            val globalSubroutines = CffIndex.parseCff2(stream) ?: return null
            val charStrings =
                CffIndex.parseCff2(Stream.at(data, topDictionary.charStringsOffset) ?: return null)
                    ?: return null
            val variationStore =
                topDictionary.variationStoreOffset?.let { offset ->
                    val variationStream = Stream.at(data, offset) ?: return null
                    if (!variationStream.advance(VARIATION_STORE_LENGTH_SIZE)) return null
                    ItemVariationStore.parse(variationStream.tail() ?: return null) ?: return null
                }
            val localSubroutines =
                if (topDictionary.fontDictionaryIndexOffset != null) {
                    val result = parseLocalSubroutines(data, topDictionary.fontDictionaryIndexOffset) ?: return null
                    result.index
                } else {
                    null
                }

            return Cff2Table(charStrings, globalSubroutines, localSubroutines, variationStore)
        }

        private fun parseTopDictionary(data: ByteSlice): Cff2TopDictionary? {
            val parser = CffDictionaryParser(data, DoubleArray(CffOperandStack.CFF2_CAPACITY))
            var charStringsOffset = 0
            var fontDictionaryIndexOffset: Int? = null
            var variationStoreOffset: Int? = null
            while (true) {
                val result = parser.parseNextResult()
                if (result.status == CffDictionaryParseStatus.END) break
                if (result.status == CffDictionaryParseStatus.MALFORMED) return null
                val operator = result.operator ?: return null
                when (operator.value) {
                    CHAR_STRINGS_OPERATOR -> charStringsOffset = parser.parseOffset() ?: return null
                    VARIATION_STORE_OPERATOR -> variationStoreOffset = parser.parseOffset() ?: return null
                    FD_ARRAY_OPERATOR -> fontDictionaryIndexOffset = parser.parseOffset() ?: return null
                    else -> if (!parser.parseOperands()) return null
                }
            }
            if (charStringsOffset == 0) return null
            return Cff2TopDictionary(charStringsOffset, fontDictionaryIndexOffset, variationStoreOffset)
        }

        private fun parseLocalSubroutines(
            data: ByteSlice,
            fontDictionaryIndexOffset: Int,
        ): Cff2LocalSubroutines? {
            val fontDictionaries =
                CffIndex.parseCff2(Stream.at(data, fontDictionaryIndexOffset) ?: return null) ?: return null
            var index = 0
            while (index < fontDictionaries.size) {
                val fontDictionary = fontDictionaries[index] ?: return null
                val privateRange = parseFontDictionary(fontDictionary) ?: return null
                if (privateRange is Cff2PrivateRange.Found) {
                    val range = privateRange.value
                    val privateData = data.slice(range.first, range.length()) ?: return null
                    val relativeOffset = parsePrivateDictionary(privateData) ?: return null
                    if (relativeOffset is Cff2SubroutinesOffset.Found) {
                        if (relativeOffset.value > Int.MAX_VALUE - range.first) return null
                        val subroutineStream = Stream.at(data, range.first + relativeOffset.value) ?: return null
                        return Cff2LocalSubroutines(CffIndex.parseCff2(subroutineStream) ?: return null)
                    }
                }
                index++
            }
            return Cff2LocalSubroutines(null)
        }

        private fun parseFontDictionary(data: ByteSlice): Cff2PrivateRange? {
            val parser = CffDictionaryParser(data, DoubleArray(CffOperandStack.CFF2_CAPACITY))
            var privateRange: IntRange? = null
            while (true) {
                val result = parser.parseNextResult()
                if (result.status == CffDictionaryParseStatus.END) {
                    return privateRange?.let(Cff2PrivateRange::Found) ?: Cff2PrivateRange.Absent
                }
                if (result.status == CffDictionaryParseStatus.MALFORMED) return null
                val operator = result.operator ?: return null
                if (operator.value == PRIVATE_DICTIONARY_OPERATOR) {
                    privateRange = parser.parseRange() ?: return null
                } else if (!parser.parseOperands()) {
                    return null
                }
            }
        }

        private fun parsePrivateDictionary(data: ByteSlice): Cff2SubroutinesOffset? {
            val parser = CffDictionaryParser(data, DoubleArray(CffOperandStack.CFF2_CAPACITY))
            var subroutinesOffset: Int? = null
            while (true) {
                val result = parser.parseNextResult()
                if (result.status == CffDictionaryParseStatus.END) {
                    return subroutinesOffset?.let(Cff2SubroutinesOffset::Found)
                        ?: Cff2SubroutinesOffset.Absent
                }
                if (result.status == CffDictionaryParseStatus.MALFORMED) return null
                val operator = result.operator ?: return null
                if (operator.value == LOCAL_SUBROUTINES_OPERATOR) {
                    subroutinesOffset = parser.parseOffset() ?: return null
                } else if (!parser.parseOperands()) {
                    return null
                }
            }
        }

        private const val CFF2_MAJOR_VERSION: Int = 2
        private const val CFF2_HEADER_SIZE: Int = 5
        private const val VARIATION_STORE_LENGTH_SIZE: Int = 2
        private const val CHAR_STRINGS_OPERATOR: Int = 17
        private const val PRIVATE_DICTIONARY_OPERATOR: Int = 18
        private const val LOCAL_SUBROUTINES_OPERATOR: Int = 19
        private const val VARIATION_STORE_OPERATOR: Int = 24
        private const val FD_ARRAY_OPERATOR: Int = 1236
    }
}

private data class Cff2TopDictionary(
    val charStringsOffset: Int,
    val fontDictionaryIndexOffset: Int?,
    val variationStoreOffset: Int?,
)

private data class Cff2LocalSubroutines(
    val index: CffIndex?,
)

private sealed interface Cff2PrivateRange {
    data class Found(
        val value: IntRange,
    ) : Cff2PrivateRange

    data object Absent : Cff2PrivateRange
}

private sealed interface Cff2SubroutinesOffset {
    data class Found(
        val value: Int,
    ) : Cff2SubroutinesOffset

    data object Absent : Cff2SubroutinesOffset
}

private fun IntRange.length(): Int = if (isEmpty()) 0 else last - first + 1
