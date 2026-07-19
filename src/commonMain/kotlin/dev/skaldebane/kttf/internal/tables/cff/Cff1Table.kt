package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.OutlineSink
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import dev.skaldebane.kttf.internal.parser.decodeUtf8

internal data class CffMatrix(
    internal val scaleX: Float = 0.001f,
    internal val skewY: Float = 0f,
    internal val skewX: Float = 0f,
    internal val scaleY: Float = 0.001f,
    internal val translateX: Float = 0f,
    internal val translateY: Float = 0f,
)

/** A parsed CFF1 table with Type 2 outlines. */
internal class Cff1Table private constructor(
    private val tableData: ByteSlice,
    private val charStrings: CffIndex,
    private val strings: CffIndex,
    private val charset: CffCharset,
    private val globalSubroutines: CffIndex,
    private val fontKind: Cff1FontKind,
    private val unitsPerEm: Int?,
    internal val matrix: CffMatrix,
) {
    internal val glyphCount: Int
        get() = charStrings.size

    internal fun outline(
        glyphId: Int,
        sink: OutlineSink,
    ): FontBounds? {
        val charString = charStrings[glyphId] ?: return null
        val localSubroutines = localSubroutines(glyphId)
        return CffType2Vm(
            globalSubroutines = globalSubroutines,
            localSubroutines = localSubroutines.first,
            localSubroutinesResolver = localSubroutines.second,
            charStrings = charStrings,
            charset = charset,
            transform = CffTransform.create(unitsPerEm, matrix),
            sink = sink,
        ).outline(charString)
    }

    internal fun glyphWidth(glyphId: Int): Int? {
        val sid = fontKind as? Cff1FontKind.Sid ?: return null
        val charString = charStrings[glyphId] ?: return null
        val result =
            CffType2Vm(
                globalSubroutines = globalSubroutines,
                localSubroutines = sid.localSubroutines,
                charStrings = charStrings,
                charset = charset,
                transform = null,
                sink = NO_OP_OUTLINE_SINK,
                widthOnly = true,
            ).width(charString) ?: return null
        return result.value?.let { (sid.nominalWidth + it).toUnsignedIntOrNull() }
            ?: sid.defaultWidth.toUnsignedIntOrNull()
    }

    internal fun glyphName(glyphId: Int): String? {
        if (fontKind !is Cff1FontKind.Sid) return null
        val sid = charset.gidToSid(glyphId) ?: return null
        return CffStandardNames.name(sid)
            ?: strings[sid - CffStandardNames.count]?.decodeUtf8()
    }

    internal fun glyphIdByName(name: String): Int? {
        if (fontKind !is Cff1FontKind.Sid) return null
        val standardSid = CffStandardNames.sid(name)
        if (standardSid != null) return charset.sidToGid(standardSid)

        var index = 0
        while (index < strings.size) {
            val string = strings[index] ?: return null
            if (string.decodeUtf8() == name) {
                val sid = (CffStandardNames.count + index) and 0xFFFF
                return charset.sidToGid(sid)
            }
            index++
        }
        return null
    }

    internal fun glyphIdForCode(code: Int): Int? {
        val sid = fontKind as? Cff1FontKind.Sid ?: return null
        return sid.encoding.codeToGid(charset, code)
            ?: cffStandardEncodingCodeToSid(code)?.let(charset::sidToGid)
    }

    internal fun glyphCid(glyphId: Int): Int? = if (fontKind is Cff1FontKind.Cid) charset.gidToSid(glyphId) else null

    private fun localSubroutines(glyphId: Int): Pair<CffIndex?, (() -> CffIndex?)?> =
        when (val kind = fontKind) {
            is Cff1FontKind.Sid -> Pair(kind.localSubroutines, null)
            is Cff1FontKind.Cid -> Pair(null) { parseCidLocalSubroutines(tableData, glyphId, kind) }
        }

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            unitsPerEm: Int? = null,
        ): Cff1Table? {
            if (unitsPerEm != null && unitsPerEm <= 0) return null

            val stream = Stream.atStart(data)
            val header = parseCffHeader(stream, CFF_HEADER_SIZE) ?: return null
            if (header.majorVersion != CFF1_MAJOR_VERSION) return null

            if (!CffIndex.skipCff1(stream)) return null
            val topDictIndex = CffIndex.parseCff1(stream) ?: return null
            val topDictData = topDictIndex[0] ?: return null
            val topDict = parseTopDictionary(topDictData) ?: return null
            if (topDict.charStringsOffset == 0) return null

            val strings = CffIndex.parseCff1(stream) ?: return null
            val globalSubroutines = CffIndex.parseCff1(stream) ?: return null
            val charStringStream = Stream.at(data, topDict.charStringsOffset) ?: return null
            val charStrings = CffIndex.parseCff1(charStringStream) ?: return null
            if (charStrings.size == 0 || charStrings.size > UShort.MAX_VALUE.toInt()) return null
            val charset = parseCffCharset(data, topDict.charsetOffset, charStrings.size) ?: return null
            val fontKind =
                if (topDict.hasRos) {
                    parseCidMetadata(data, topDict, charStrings.size) ?: return null
                } else {
                    parseSidMetadata(data, topDict) ?: return null
                }

            return Cff1Table(
                tableData = data,
                charStrings = charStrings,
                strings = strings,
                charset = charset,
                globalSubroutines = globalSubroutines,
                fontKind = fontKind,
                unitsPerEm = unitsPerEm,
                matrix = topDict.matrix,
            )
        }

        private fun parseTopDictionary(data: ByteSlice): TopDictionary? {
            val parser = CffDictionaryParser(data, DoubleArray(CffOperandStack.CFF1_CAPACITY))
            var charStringsOffset = 0
            var charsetOffset: Int? = null
            var encodingOffset: Int? = null
            var privateRange: IntRange? = null
            var matrix = CffMatrix()
            var hasRos = false
            var fdArrayOffset: Int? = null
            var fdSelectOffset: Int? = null

            while (true) {
                val result = parser.parseNextResult()
                if (result.status == CffDictionaryParseStatus.END) break
                if (result.status == CffDictionaryParseStatus.MALFORMED) return null
                val operator = result.operator ?: return null
                when (operator.value) {
                    CHAR_STRINGS_OPERATOR -> {
                        charStringsOffset = parser.parseOffset() ?: return null
                    }

                    CHARSET_OPERATOR -> {
                        charsetOffset = parser.parseOffset() ?: return null
                    }

                    ENCODING_OPERATOR -> {
                        encodingOffset = parser.parseOffset() ?: return null
                    }

                    PRIVATE_DICTIONARY_OPERATOR -> {
                        if (!parser.parseOperands(2)) return null
                        privateRange = parser.parseRange()
                    }

                    FONT_MATRIX_OPERATOR -> {
                        if (!parser.parseOperands(6)) return null
                        matrix =
                            CffMatrix(
                                scaleX = parser.operand(0)?.toFiniteFloatOrNull() ?: return null,
                                skewY = parser.operand(1)?.toFiniteFloatOrNull() ?: return null,
                                skewX = parser.operand(2)?.toFiniteFloatOrNull() ?: return null,
                                scaleY = parser.operand(3)?.toFiniteFloatOrNull() ?: return null,
                                translateX = parser.operand(4)?.toFiniteFloatOrNull() ?: return null,
                                translateY = parser.operand(5)?.toFiniteFloatOrNull() ?: return null,
                            )
                    }

                    ROS_OPERATOR -> {
                        if (!parser.parseOperands(3)) return null
                        hasRos = true
                    }

                    FD_ARRAY_OPERATOR -> {
                        fdArrayOffset = parser.parseOffset() ?: return null
                    }

                    FD_SELECT_OPERATOR -> {
                        fdSelectOffset = parser.parseOffset() ?: return null
                    }

                    else -> {
                        if (!parser.parseOperands()) return null
                    }
                }
            }

            return TopDictionary(
                charStringsOffset,
                charsetOffset,
                encodingOffset,
                privateRange,
                matrix,
                hasRos,
                fdArrayOffset,
                fdSelectOffset,
            )
        }

        private fun parseSidMetadata(
            data: ByteSlice,
            topDictionary: TopDictionary,
        ): Cff1FontKind.Sid? {
            val encoding = parseCffEncoding(data, topDictionary.encodingOffset) ?: return null
            val privateDictionary = parsePrivateDictionary(data, topDictionary.privateRange) ?: return null
            val localSubroutines =
                privateDictionary.localSubroutinesOffset?.let { relativeOffset ->
                    val privateStart = topDictionary.privateRange?.first ?: return null
                    if (relativeOffset > Int.MAX_VALUE - privateStart) return null
                    CffIndex.parseCff1(Stream.at(data, privateStart + relativeOffset) ?: return null) ?: return null
                }
            return Cff1FontKind.Sid(
                encoding,
                localSubroutines,
                privateDictionary.defaultWidth,
                privateDictionary.nominalWidth,
            )
        }

        private fun parseCidMetadata(
            data: ByteSlice,
            topDictionary: TopDictionary,
            glyphCount: Int,
        ): Cff1FontKind.Cid? {
            val charsetOffset = topDictionary.charsetOffset ?: return null
            if (charsetOffset <= EXPERT_SUBSET_CHARSET_ID) return null
            val fdArrayOffset = topDictionary.fdArrayOffset ?: return null
            val fdSelectOffset = topDictionary.fdSelectOffset ?: return null
            val fdArray = CffIndex.parseCff1(Stream.at(data, fdArrayOffset) ?: return null) ?: return null
            val fdSelect = parseCffFdSelect(data, fdSelectOffset, glyphCount) ?: return null
            return Cff1FontKind.Cid(fdArray, fdSelect)
        }

        private fun parseCidLocalSubroutines(
            data: ByteSlice,
            glyphId: Int,
            metadata: Cff1FontKind.Cid,
        ): CffIndex? {
            val fontDictionaryIndex = metadata.fdSelect.fontDictionaryIndex(glyphId) ?: return null
            val fontDictionary = metadata.fdArray[fontDictionaryIndex] ?: return null
            val privateRange = parseFontDictionary(fontDictionary) ?: return null
            val privateDictionary = parsePrivateDictionary(data, privateRange) ?: return null
            val relativeOffset = privateDictionary.localSubroutinesOffset ?: return null
            val privateStart = privateRange.first
            if (relativeOffset > Int.MAX_VALUE - privateStart) return null
            return CffIndex.parseCff1(Stream.at(data, privateStart + relativeOffset) ?: return null)
        }

        private fun parseFontDictionary(data: ByteSlice): IntRange? {
            val parser = CffDictionaryParser(data, DoubleArray(CffOperandStack.CFF1_CAPACITY))
            var privateRange: IntRange? = null
            while (true) {
                val result = parser.parseNextResult()
                if (result.status == CffDictionaryParseStatus.END) return privateRange
                if (result.status == CffDictionaryParseStatus.MALFORMED) return null
                val operator = result.operator ?: return null
                if (operator.value == PRIVATE_DICTIONARY_OPERATOR) {
                    privateRange = parser.parseRange() ?: return null
                } else if (!parser.parseOperands()) {
                    return null
                }
            }
        }

        private fun parsePrivateDictionary(
            tableData: ByteSlice,
            range: IntRange?,
        ): PrivateDictionary? {
            if (range == null) return PrivateDictionary()
            val length = if (range.isEmpty()) 0 else range.last - range.first + 1
            val data = tableData.slice(range.first, length) ?: return null
            val parser = CffDictionaryParser(data, DoubleArray(CffOperandStack.CFF1_CAPACITY))
            var localSubroutinesOffset: Int? = null
            var defaultWidth = 0f
            var nominalWidth = 0f

            while (true) {
                val result = parser.parseNextResult()
                if (result.status == CffDictionaryParseStatus.END) break
                if (result.status == CffDictionaryParseStatus.MALFORMED) return null
                val operator = result.operator ?: return null
                when (operator.value) {
                    LOCAL_SUBROUTINES_OPERATOR -> {
                        localSubroutinesOffset = parser.parseOffset() ?: return null
                    }

                    DEFAULT_WIDTH_OPERATOR -> {
                        defaultWidth = parser.parseNumber()?.toFiniteFloatOrNull() ?: return null
                    }

                    NOMINAL_WIDTH_OPERATOR -> {
                        nominalWidth = parser.parseNumber()?.toFiniteFloatOrNull() ?: return null
                    }

                    else -> {
                        if (!parser.parseOperands()) return null
                    }
                }
            }

            return PrivateDictionary(localSubroutinesOffset, defaultWidth, nominalWidth)
        }

        private const val CFF1_MAJOR_VERSION: Int = 1
        private const val CFF_HEADER_SIZE: Int = 4
        private const val CHARSET_OPERATOR: Int = 15
        private const val ENCODING_OPERATOR: Int = 16
        private const val CHAR_STRINGS_OPERATOR: Int = 17
        private const val PRIVATE_DICTIONARY_OPERATOR: Int = 18
        private const val FONT_MATRIX_OPERATOR: Int = 1207
        private const val ROS_OPERATOR: Int = 1230
        private const val FD_ARRAY_OPERATOR: Int = 1236
        private const val FD_SELECT_OPERATOR: Int = 1237
        private const val LOCAL_SUBROUTINES_OPERATOR: Int = 19
        private const val DEFAULT_WIDTH_OPERATOR: Int = 20
        private const val NOMINAL_WIDTH_OPERATOR: Int = 21
        private const val EXPERT_SUBSET_CHARSET_ID: Int = 2
    }
}

private data class TopDictionary(
    val charStringsOffset: Int,
    val charsetOffset: Int?,
    val encodingOffset: Int?,
    val privateRange: IntRange?,
    val matrix: CffMatrix,
    val hasRos: Boolean,
    val fdArrayOffset: Int?,
    val fdSelectOffset: Int?,
)

private sealed interface Cff1FontKind {
    data class Sid(
        val encoding: CffEncoding,
        val localSubroutines: CffIndex?,
        val defaultWidth: Float,
        val nominalWidth: Float,
    ) : Cff1FontKind

    data class Cid(
        val fdArray: CffIndex,
        val fdSelect: CffFdSelect,
    ) : Cff1FontKind
}

private data class PrivateDictionary(
    val localSubroutinesOffset: Int? = null,
    val defaultWidth: Float = 0f,
    val nominalWidth: Float = 0f,
)

internal data class CffTransform(
    val unitsPerEm: Float,
    val matrix: CffMatrix,
) {
    internal fun transformX(
        x: Float,
        y: Float,
    ): Float = (x * matrix.scaleX + y * matrix.skewX + matrix.translateX) * unitsPerEm

    internal fun transformY(
        x: Float,
        y: Float,
    ): Float = (x * matrix.skewY + y * matrix.scaleY + matrix.translateY) * unitsPerEm

    internal companion object {
        internal fun create(
            unitsPerEm: Int?,
            matrix: CffMatrix,
        ): CffTransform? {
            if (unitsPerEm == null) return null
            if (unitsPerEm == 1000 && matrix == CffMatrix()) return null
            return CffTransform(unitsPerEm.toFloat(), matrix)
        }
    }
}

private fun Float.toUnsignedIntOrNull(): Int? {
    if (!isFinite()) return null
    return toInt().takeIf { it in 0..UShort.MAX_VALUE.toInt() }
}

private val NO_OP_OUTLINE_SINK: OutlineSink =
    object : OutlineSink {
        override fun moveTo(
            x: Float,
            y: Float,
        ) = Unit

        override fun lineTo(
            x: Float,
            y: Float,
        ) = Unit

        override fun quadTo(
            controlX: Float,
            controlY: Float,
            x: Float,
            y: Float,
        ) = Unit

        override fun curveTo(
            control1X: Float,
            control1Y: Float,
            control2X: Float,
            control2Y: Float,
            x: Float,
            y: Float,
        ) = Unit

        override fun close() = Unit
    }
