package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.decodeUtf8

internal data class PostLineMetrics(
    internal val position: Int,
    internal val thickness: Int,
)

internal class PostNames internal constructor(
    private val data: ByteSlice?,
) {
    private var offset: Int = 0

    internal fun next(): String? {
        val bytes = data ?: return null
        if (offset >= bytes.size) return null

        val length = bytes.readUInt8(offset) ?: return null
        offset++
        if (length == 0) return null

        val name = bytes.slice(offset, length) ?: return null
        offset += length
        return name.decodeUtf8()
    }
}

internal class PostTable private constructor(
    private val data: ByteSlice,
    internal val version: Long,
    private val glyphIndexes: ByteSlice?,
    private val namesData: ByteSlice?,
) {
    internal val italicAngle: Float
        get() = (data.readInt32(ITALIC_ANGLE_OFFSET) ?: 0).toFloat() / FIXED_SCALE

    internal val underlineMetrics: PostLineMetrics
        get() =
            PostLineMetrics(
                position = data.readInt16(UNDERLINE_POSITION_OFFSET) ?: 0,
                thickness = data.readInt16(UNDERLINE_THICKNESS_OFFSET) ?: 0,
            )

    internal val isMonospaced: Boolean
        get() = data.readUInt32(IS_FIXED_PITCH_OFFSET) != 0L

    internal fun glyphName(glyphId: Int): String? {
        val nameIndex = glyphIndex(glyphId) ?: return null
        if (nameIndex < MACINTOSH_NAMES.size) return MACINTOSH_NAMES[nameIndex]

        val customIndex = nameIndex - MACINTOSH_NAMES.size
        val names = names()
        repeat(customIndex) { names.next() ?: return null }
        return names.next()
    }

    internal fun glyphIndexByName(name: String): Int? {
        val standardIndex = MACINTOSH_NAMES.indexOf(name)
        val nameIndex =
            if (standardIndex >= 0) {
                standardIndex
            } else {
                val names = names()
                var customIndex = 0
                while (true) {
                    val customName = names.next() ?: return null
                    if (customName == name) break
                    customIndex++
                }
                customIndex + MACINTOSH_NAMES.size
            }

        val indexes = glyphIndexes ?: return null
        var glyphId = 0
        while (glyphId < indexes.size / 2) {
            if (indexes.readUInt16(glyphId * 2) == nameIndex) return glyphId
            glyphId++
        }
        return null
    }

    internal fun names(): PostNames = PostNames(namesData)

    private fun glyphIndex(glyphId: Int): Int? {
        val indexes = glyphIndexes ?: return null
        if (glyphId < 0 || glyphId >= indexes.size / 2) return null
        return indexes.readUInt16(glyphId * 2)
    }

    internal companion object {
        private const val HEADER_SIZE: Int = 32
        private const val ITALIC_ANGLE_OFFSET: Int = 4
        private const val UNDERLINE_POSITION_OFFSET: Int = 8
        private const val UNDERLINE_THICKNESS_OFFSET: Int = 10
        private const val IS_FIXED_PITCH_OFFSET: Int = 12
        private const val FIXED_SCALE: Float = 65536f
        private const val VERSION_2: Long = 0x0002_0000L

        internal fun parse(data: ByteSlice): PostTable? {
            if (data.size < HEADER_SIZE) return null

            val version = data.readUInt32(0) ?: return null
            if (version !in SUPPORTED_VERSIONS) return null

            var glyphIndexes: ByteSlice? = null
            var namesData: ByteSlice? = null
            if (version == VERSION_2) {
                val count = data.readUInt16(HEADER_SIZE) ?: return null
                glyphIndexes = data.slice(HEADER_SIZE + 2, count * 2) ?: return null
                namesData = data.sliceFrom(HEADER_SIZE + 2 + count * 2) ?: return null
            }

            return PostTable(data, version, glyphIndexes, namesData)
        }

        private val SUPPORTED_VERSIONS: Set<Long> =
            setOf(
                0x0001_0000L,
                VERSION_2,
                0x0002_5000L,
                0x0003_0000L,
                0x0004_0000L,
            )
    }
}

private val MACINTOSH_NAMES: Array<String> =
    arrayOf(
        ".notdef",
        ".null",
        "nonmarkingreturn",
        "space",
        "exclam",
        "quotedbl",
        "numbersign",
        "dollar",
        "percent",
        "ampersand",
        "quotesingle",
        "parenleft",
        "parenright",
        "asterisk",
        "plus",
        "comma",
        "hyphen",
        "period",
        "slash",
        "zero",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
        "colon",
        "semicolon",
        "less",
        "equal",
        "greater",
        "question",
        "at",
        "A",
        "B",
        "C",
        "D",
        "E",
        "F",
        "G",
        "H",
        "I",
        "J",
        "K",
        "L",
        "M",
        "N",
        "O",
        "P",
        "Q",
        "R",
        "S",
        "T",
        "U",
        "V",
        "W",
        "X",
        "Y",
        "Z",
        "bracketleft",
        "backslash",
        "bracketright",
        "asciicircum",
        "underscore",
        "grave",
        "a",
        "b",
        "c",
        "d",
        "e",
        "f",
        "g",
        "h",
        "i",
        "j",
        "k",
        "l",
        "m",
        "n",
        "o",
        "p",
        "q",
        "r",
        "s",
        "t",
        "u",
        "v",
        "w",
        "x",
        "y",
        "z",
        "braceleft",
        "bar",
        "braceright",
        "asciitilde",
        "Adieresis",
        "Aring",
        "Ccedilla",
        "Eacute",
        "Ntilde",
        "Odieresis",
        "Udieresis",
        "aacute",
        "agrave",
        "acircumflex",
        "adieresis",
        "atilde",
        "aring",
        "ccedilla",
        "eacute",
        "egrave",
        "ecircumflex",
        "edieresis",
        "iacute",
        "igrave",
        "icircumflex",
        "idieresis",
        "ntilde",
        "oacute",
        "ograve",
        "ocircumflex",
        "odieresis",
        "otilde",
        "uacute",
        "ugrave",
        "ucircumflex",
        "udieresis",
        "dagger",
        "degree",
        "cent",
        "sterling",
        "section",
        "bullet",
        "paragraph",
        "germandbls",
        "registered",
        "copyright",
        "trademark",
        "acute",
        "dieresis",
        "notequal",
        "AE",
        "Oslash",
        "infinity",
        "plusminus",
        "lessequal",
        "greaterequal",
        "yen",
        "mu",
        "partialdiff",
        "summation",
        "product",
        "pi",
        "integral",
        "ordfeminine",
        "ordmasculine",
        "Omega",
        "ae",
        "oslash",
        "questiondown",
        "exclamdown",
        "logicalnot",
        "radical",
        "florin",
        "approxequal",
        "Delta",
        "guillemotleft",
        "guillemotright",
        "ellipsis",
        "nonbreakingspace",
        "Agrave",
        "Atilde",
        "Otilde",
        "OE",
        "oe",
        "endash",
        "emdash",
        "quotedblleft",
        "quotedblright",
        "quoteleft",
        "quoteright",
        "divide",
        "lozenge",
        "ydieresis",
        "Ydieresis",
        "fraction",
        "currency",
        "guilsinglleft",
        "guilsinglright",
        "fi",
        "fl",
        "daggerdbl",
        "periodcentered",
        "quotesinglbase",
        "quotedblbase",
        "perthousand",
        "Acircumflex",
        "Ecircumflex",
        "Aacute",
        "Edieresis",
        "Egrave",
        "Iacute",
        "Icircumflex",
        "Idieresis",
        "Igrave",
        "Oacute",
        "Ocircumflex",
        "apple",
        "Ograve",
        "Uacute",
        "Ucircumflex",
        "Ugrave",
        "dotlessi",
        "circumflex",
        "tilde",
        "macron",
        "breve",
        "dotaccent",
        "ring",
        "cedilla",
        "hungarumlaut",
        "ogonek",
        "caron",
        "Lslash",
        "lslash",
        "Scaron",
        "scaron",
        "Zcaron",
        "zcaron",
        "brokenbar",
        "Eth",
        "eth",
        "Yacute",
        "yacute",
        "Thorn",
        "thorn",
        "minus",
        "multiply",
        "onesuperior",
        "twosuperior",
        "threesuperior",
        "onehalf",
        "onequarter",
        "threequarters",
        "franc",
        "Gbreve",
        "gbreve",
        "Idotaccent",
        "Scedilla",
        "scedilla",
        "Cacute",
        "cacute",
        "Ccaron",
        "ccaron",
        "dcroat",
    )
