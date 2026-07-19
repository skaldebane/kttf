package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.jvm.JvmInline

internal enum class OS2WeightKind {
    THIN,
    EXTRA_LIGHT,
    LIGHT,
    NORMAL,
    MEDIUM,
    SEMI_BOLD,
    BOLD,
    EXTRA_BOLD,
    BLACK,
    OTHER,
}

internal data class OS2Weight(
    internal val value: Int,
) {
    internal val kind: OS2WeightKind
        get() =
            when (value) {
                100 -> OS2WeightKind.THIN
                200 -> OS2WeightKind.EXTRA_LIGHT
                300 -> OS2WeightKind.LIGHT
                400 -> OS2WeightKind.NORMAL
                500 -> OS2WeightKind.MEDIUM
                600 -> OS2WeightKind.SEMI_BOLD
                700 -> OS2WeightKind.BOLD
                800 -> OS2WeightKind.EXTRA_BOLD
                900 -> OS2WeightKind.BLACK
                else -> OS2WeightKind.OTHER
            }
}

internal enum class OS2Width(
    internal val value: Int,
) {
    ULTRA_CONDENSED(1),
    EXTRA_CONDENSED(2),
    CONDENSED(3),
    SEMI_CONDENSED(4),
    NORMAL(5),
    SEMI_EXPANDED(6),
    EXPANDED(7),
    EXTRA_EXPANDED(8),
    ULTRA_EXPANDED(9),
}

internal enum class OS2Permissions {
    INSTALLABLE,
    RESTRICTED,
    PREVIEW_AND_PRINT,
    EDITABLE,
}

internal enum class OS2Style {
    NORMAL,
    ITALIC,
    OBLIQUE,
}

internal data class OS2ScriptMetrics(
    internal val xSize: Int,
    internal val ySize: Int,
    internal val xOffset: Int,
    internal val yOffset: Int,
)

internal data class OS2LineMetrics(
    internal val thickness: Int,
    internal val position: Int,
)

@JvmInline
internal value class OS2SelectionFlags(
    internal val value: Int,
) {
    internal val isItalic: Boolean get() = hasBit(0)
    internal val isUnderscored: Boolean get() = hasBit(1)
    internal val isNegative: Boolean get() = hasBit(2)
    internal val isOutlined: Boolean get() = hasBit(3)
    internal val isStrikeout: Boolean get() = hasBit(4)
    internal val isBold: Boolean get() = hasBit(5)
    internal val isRegular: Boolean get() = hasBit(6)
    internal val usesTypographicMetrics: Boolean get() = hasBit(7)
    internal val hasWwsNames: Boolean get() = hasBit(8)
    internal val isOblique: Boolean get() = hasBit(9)

    private fun hasBit(bit: Int): Boolean = value and (1 shl bit) != 0
}

internal class OS2UnicodeRanges internal constructor(
    private val word0: Long,
    private val word1: Long,
    private val word2: Long,
    private val word3: Long,
) {
    internal fun contains(codePoint: Int): Boolean {
        if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) return false
        val range = unicodeRangeIndex(codePoint)
        return range != null && containsRange(range)
    }

    internal fun contains(character: Char): Boolean = contains(character.code)

    internal fun containsRange(index: Int): Boolean {
        if (index !in 0..127) return false
        val word =
            when (index / 32) {
                0 -> word0
                1 -> word1
                2 -> word2
                else -> word3
            }
        return word and (1L shl (index % 32)) != 0L
    }

    internal fun word(index: Int): Long? =
        when (index) {
            0 -> word0
            1 -> word1
            2 -> word2
            3 -> word3
            else -> null
        }
}

internal enum class OS2CodePage(
    internal val bit: Int,
) {
    LATIN_1_1252(0),
    LATIN_2_1250(1),
    CYRILLIC_1251(2),
    GREEK_1253(3),
    TURKISH_1254(4),
    HEBREW_1255(5),
    ARABIC_1256(6),
    BALTIC_1257(7),
    VIETNAMESE_1258(8),
    THAI_874(16),
    JIS_932(17),
    SIMPLIFIED_CHINESE_936(18),
    KOREAN_WANSUNG_949(19),
    TRADITIONAL_CHINESE_950(20),
    KOREAN_JOHAB_1361(21),
    MACINTOSH_CHARACTER_SET(29),
    OEM_CHARACTER_SET(30),
    SYMBOL_CHARACTER_SET(31),
    IBM_GREEK_869(48),
    MS_DOS_RUSSIAN_866(49),
    MS_DOS_NORDIC_865(50),
    ARABIC_864(51),
    MS_DOS_CANADIAN_FRENCH_863(52),
    HEBREW_862(53),
    MS_DOS_ICELANDIC_861(54),
    MS_DOS_PORTUGUESE_860(55),
    IBM_TURKISH_857(56),
    IBM_CYRILLIC_855(57),
    LATIN_2_852(58),
    MS_DOS_BALTIC_775(59),
    GREEK_737(60),
    ARABIC_708(61),
    LATIN_1_850(62),
    US_437(63),
}

internal class OS2CodePageRanges internal constructor(
    private val word0: Long,
    private val word1: Long,
) {
    internal fun contains(codePage: OS2CodePage): Boolean = containsBit(codePage.bit)

    internal fun containsBit(index: Int): Boolean {
        if (index !in 0..63) return false
        val word = if (index < 32) word0 else word1
        return word and (1L shl (index % 32)) != 0L
    }

    internal fun word(index: Int): Long? =
        when (index) {
            0 -> word0
            1 -> word1
            else -> null
        }
}

internal class OS2Table private constructor(
    private val data: ByteSlice,
    internal val version: Int,
) {
    internal val weight: OS2Weight
        get() = OS2Weight(data.readUInt16(WEIGHT_CLASS_OFFSET) ?: 0)

    internal val width: OS2Width
        get() =
            when (data.readUInt16(WIDTH_CLASS_OFFSET)) {
                1 -> OS2Width.ULTRA_CONDENSED
                2 -> OS2Width.EXTRA_CONDENSED
                3 -> OS2Width.CONDENSED
                4 -> OS2Width.SEMI_CONDENSED
                5 -> OS2Width.NORMAL
                6 -> OS2Width.SEMI_EXPANDED
                7 -> OS2Width.EXPANDED
                8 -> OS2Width.EXTRA_EXPANDED
                9 -> OS2Width.ULTRA_EXPANDED
                else -> OS2Width.NORMAL
            }

    internal val embeddingFlags: Int
        get() = data.readUInt16(TYPE_OFFSET) ?: 0

    internal val permissions: OS2Permissions?
        get() {
            val flags = embeddingFlags and 0xF
            if (version <= 2) {
                return when {
                    flags == 0 -> OS2Permissions.INSTALLABLE
                    flags and 8 != 0 -> OS2Permissions.EDITABLE
                    flags and 4 != 0 -> OS2Permissions.PREVIEW_AND_PRINT
                    else -> OS2Permissions.RESTRICTED
                }
            }

            return when (flags) {
                0 -> OS2Permissions.INSTALLABLE
                2 -> OS2Permissions.RESTRICTED
                4 -> OS2Permissions.PREVIEW_AND_PRINT
                8 -> OS2Permissions.EDITABLE
                else -> null
            }
        }

    internal val isSubsettingAllowed: Boolean
        get() = version <= 1 || embeddingFlags and 0x0100 == 0

    internal val isOutlineEmbeddingAllowed: Boolean
        get() = version <= 1 || embeddingFlags and 0x0200 == 0

    internal val subscriptMetrics: OS2ScriptMetrics
        get() = scriptMetricsAt(SUBSCRIPT_METRICS_OFFSET)

    internal val superscriptMetrics: OS2ScriptMetrics
        get() = scriptMetricsAt(SUPERSCRIPT_METRICS_OFFSET)

    internal val strikeoutMetrics: OS2LineMetrics
        get() =
            OS2LineMetrics(
                thickness = data.readInt16(STRIKEOUT_SIZE_OFFSET) ?: 0,
                position = data.readInt16(STRIKEOUT_POSITION_OFFSET) ?: 0,
            )

    internal val unicodeRanges: OS2UnicodeRanges
        get() =
            OS2UnicodeRanges(
                word0 = data.readUInt32(UNICODE_RANGES_OFFSET) ?: 0,
                word1 = data.readUInt32(UNICODE_RANGES_OFFSET + 4) ?: 0,
                word2 = data.readUInt32(UNICODE_RANGES_OFFSET + 8) ?: 0,
                word3 = data.readUInt32(UNICODE_RANGES_OFFSET + 12) ?: 0,
            )

    internal val selectionFlags: OS2SelectionFlags
        get() = OS2SelectionFlags(data.readUInt16(SELECTION_OFFSET) ?: 0)

    internal val style: OS2Style
        get() =
            when {
                selectionFlags.isItalic -> OS2Style.ITALIC
                version >= 4 && selectionFlags.isOblique -> OS2Style.OBLIQUE
                else -> OS2Style.NORMAL
            }

    internal val isBold: Boolean
        get() = selectionFlags.isBold

    internal val usesTypographicMetrics: Boolean
        get() = version >= 4 && selectionFlags.usesTypographicMetrics

    internal val typographicAscender: Int
        get() = data.readInt16(TYPO_ASCENDER_OFFSET) ?: 0

    internal val typographicDescender: Int
        get() = data.readInt16(TYPO_DESCENDER_OFFSET) ?: 0

    internal val typographicLineGap: Int
        get() = data.readInt16(TYPO_LINE_GAP_OFFSET) ?: 0

    internal val windowsAscender: Int
        get() = data.readInt16(WINDOWS_ASCENT_OFFSET) ?: 0

    internal val windowsDescender: Int
        get() {
            val value = data.readInt16(WINDOWS_DESCENT_OFFSET) ?: 0
            return if (value == Short.MIN_VALUE.toInt()) value else -value
        }

    internal val codePageRanges: OS2CodePageRanges?
        get() =
            if (version < 1) {
                null
            } else {
                OS2CodePageRanges(
                    word0 = data.readUInt32(CODE_PAGE_RANGES_OFFSET) ?: 0,
                    word1 = data.readUInt32(CODE_PAGE_RANGES_OFFSET + 4) ?: 0,
                )
            }

    internal val xHeight: Int?
        get() = if (version < 2) null else data.readInt16(X_HEIGHT_OFFSET)

    internal val capitalHeight: Int?
        get() = if (version < 2) null else data.readInt16(CAP_HEIGHT_OFFSET)

    internal val defaultCharacter: Int?
        get() = if (version < 2) null else data.readUInt16(DEFAULT_CHARACTER_OFFSET)

    internal val breakCharacter: Int?
        get() = if (version < 2) null else data.readUInt16(BREAK_CHARACTER_OFFSET)

    internal val maximumContext: Int?
        get() = if (version < 2) null else data.readUInt16(MAXIMUM_CONTEXT_OFFSET)

    internal val lowerOpticalPointSize: Float?
        get() = if (version < 5) null else data.readUInt16(LOWER_OPTICAL_POINT_SIZE_OFFSET)?.div(20f)

    internal val upperOpticalPointSize: Float?
        get() = if (version < 5) null else data.readUInt16(UPPER_OPTICAL_POINT_SIZE_OFFSET)?.div(20f)

    private fun scriptMetricsAt(offset: Int): OS2ScriptMetrics =
        OS2ScriptMetrics(
            xSize = data.readInt16(offset) ?: 0,
            ySize = data.readInt16(offset + 2) ?: 0,
            xOffset = data.readInt16(offset + 4) ?: 0,
            yOffset = data.readInt16(offset + 6) ?: 0,
        )

    internal companion object {
        private const val WEIGHT_CLASS_OFFSET: Int = 4
        private const val WIDTH_CLASS_OFFSET: Int = 6
        private const val TYPE_OFFSET: Int = 8
        private const val SUBSCRIPT_METRICS_OFFSET: Int = 10
        private const val SUPERSCRIPT_METRICS_OFFSET: Int = 18
        private const val STRIKEOUT_SIZE_OFFSET: Int = 26
        private const val STRIKEOUT_POSITION_OFFSET: Int = 28
        private const val UNICODE_RANGES_OFFSET: Int = 42
        private const val SELECTION_OFFSET: Int = 62
        private const val TYPO_ASCENDER_OFFSET: Int = 68
        private const val TYPO_DESCENDER_OFFSET: Int = 70
        private const val TYPO_LINE_GAP_OFFSET: Int = 72
        private const val WINDOWS_ASCENT_OFFSET: Int = 74
        private const val WINDOWS_DESCENT_OFFSET: Int = 76
        private const val CODE_PAGE_RANGES_OFFSET: Int = 78
        private const val X_HEIGHT_OFFSET: Int = 86
        private const val CAP_HEIGHT_OFFSET: Int = 88
        private const val DEFAULT_CHARACTER_OFFSET: Int = 90
        private const val BREAK_CHARACTER_OFFSET: Int = 92
        private const val MAXIMUM_CONTEXT_OFFSET: Int = 94
        private const val LOWER_OPTICAL_POINT_SIZE_OFFSET: Int = 96
        private const val UPPER_OPTICAL_POINT_SIZE_OFFSET: Int = 98

        internal fun parse(data: ByteSlice): OS2Table? {
            val version = data.readUInt16(0) ?: return null
            val minimumLength =
                when (version) {
                    0 -> 78
                    1 -> 86
                    2, 3, 4 -> 96
                    5 -> 100
                    else -> return null
                }
            if (data.size < minimumLength) return null
            return OS2Table(data, version)
        }
    }
}

@Suppress("ktlint:standard:blank-line-between-when-conditions")
private fun unicodeRangeIndex(codePoint: Int): Int? =
    when (codePoint) {
        in 0x0000..0x007F -> 0
        in 0x0080..0x00FF -> 1
        in 0x0100..0x017F -> 2
        in 0x0180..0x024F -> 3
        in 0x0250..0x02AF, in 0x1D00..0x1DBF -> 4
        in 0x02B0..0x02FF, in 0xA700..0xA71F -> 5
        in 0x0300..0x036F, in 0x1DC0..0x1DFF -> 6
        in 0x0370..0x03FF -> 7
        in 0x2C80..0x2CFF -> 8
        in 0x0400..0x052F, in 0x2DE0..0x2DFF, in 0xA640..0xA69F -> 9
        in 0x0530..0x058F -> 10
        in 0x0590..0x05FF -> 11
        in 0xA500..0xA63F -> 12
        in 0x0600..0x06FF, in 0x0750..0x077F -> 13
        in 0x07C0..0x07FF -> 14
        in 0x0900..0x097F -> 15
        in 0x0980..0x09FF -> 16
        in 0x0A00..0x0A7F -> 17
        in 0x0A80..0x0AFF -> 18
        in 0x0B00..0x0B7F -> 19
        in 0x0B80..0x0BFF -> 20
        in 0x0C00..0x0C7F -> 21
        in 0x0C80..0x0CFF -> 22
        in 0x0D00..0x0D7F -> 23
        in 0x0E00..0x0E7F -> 24
        in 0x0E80..0x0EFF -> 25
        in 0x10A0..0x10FF, in 0x2D00..0x2D2F -> 26
        in 0x1B00..0x1B7F -> 27
        in 0x1100..0x11FF -> 28
        in 0x1E00..0x1EFF, in 0x2C60..0x2C7F, in 0xA720..0xA7FF -> 29
        in 0x1F00..0x1FFF -> 30
        in 0x2000..0x206F, in 0x2E00..0x2E7F -> 31
        in 0x2070..0x209F -> 32
        in 0x20A0..0x20CF -> 33
        in 0x20D0..0x20FF -> 34
        in 0x2100..0x214F -> 35
        in 0x2150..0x218F -> 36
        in 0x2190..0x21FF, in 0x27F0..0x27FF, in 0x2900..0x297F, in 0x2B00..0x2BFF -> 37
        in 0x2200..0x22FF, in 0x27C0..0x27EF, in 0x2980..0x29FF, in 0x2A00..0x2AFF -> 38
        in 0x2300..0x23FF -> 39
        in 0x2400..0x243F -> 40
        in 0x2440..0x245F -> 41
        in 0x2460..0x24FF -> 42
        in 0x2500..0x257F -> 43
        in 0x2580..0x259F -> 44
        in 0x25A0..0x25FF -> 45
        in 0x2600..0x26FF -> 46
        in 0x2700..0x27BF -> 47
        in 0x3000..0x303F -> 48
        in 0x3040..0x309F -> 49
        in 0x30A0..0x30FF, in 0x31F0..0x31FF -> 50
        in 0x3100..0x312F, in 0x31A0..0x31BF -> 51
        in 0x3130..0x318F -> 52
        in 0xA840..0xA87F -> 53
        in 0x3200..0x32FF -> 54
        in 0x3300..0x33FF -> 55
        in 0xAC00..0xD7AF -> 56
        in 0x10900..0x1091F -> 58
        in 0x2E80..0x2FDF, in 0x2FF0..0x2FFF, in 0x3190..0x319F, in 0x3400..0x4DBF,
        in 0x4E00..0x9FFF, in 0x20000..0x2A6DF,
        -> 59
        in 0xE000..0xF8FF -> 60
        in 0x31C0..0x31EF, in 0xF900..0xFAFF, in 0x2F800..0x2FA1F -> 61
        in 0xFB00..0xFB4F -> 62
        in 0xFB50..0xFDFF -> 63
        in 0xFE20..0xFE2F -> 64
        in 0xFE10..0xFE1F, in 0xFE30..0xFE4F -> 65
        in 0xFE50..0xFE6F -> 66
        in 0xFE70..0xFEFF -> 67
        in 0xFF00..0xFFEF -> 68
        in 0xFFF0..0xFFFF -> 69
        in 0x0F00..0x0FFF -> 70
        in 0x0700..0x074F -> 71
        in 0x0780..0x07BF -> 72
        in 0x0D80..0x0DFF -> 73
        in 0x1000..0x109F -> 74
        in 0x1200..0x139F, in 0x2D80..0x2DDF -> 75
        in 0x13A0..0x13FF -> 76
        in 0x1400..0x167F -> 77
        in 0x1680..0x169F -> 78
        in 0x16A0..0x16FF -> 79
        in 0x1780..0x17FF, in 0x19E0..0x19FF -> 80
        in 0x1800..0x18AF -> 81
        in 0x2800..0x28FF -> 82
        in 0xA000..0xA48F, in 0xA490..0xA4CF -> 83
        in 0x1700..0x177F -> 84
        in 0x10300..0x1032F -> 85
        in 0x10330..0x1034F -> 86
        in 0x10400..0x1044F -> 87
        in 0x1D000..0x1D24F -> 88
        in 0x1D400..0x1D7FF -> 89
        in 0xF0000..0xFFFFD, in 0x100000..0x10FFFD -> 90
        in 0xFE00..0xFE0F, in 0xE0100..0xE01EF -> 91
        in 0xE0000..0xE007F -> 92
        in 0x1900..0x194F -> 93
        in 0x1950..0x197F -> 94
        in 0x1980..0x19DF -> 95
        in 0x1A00..0x1A1F -> 96
        in 0x2C00..0x2C5F -> 97
        in 0x2D30..0x2D7F -> 98
        in 0x4DC0..0x4DFF -> 99
        in 0xA800..0xA82F -> 100
        in 0x10000..0x1013F -> 101
        in 0x10140..0x1018F -> 102
        in 0x10380..0x1039F -> 103
        in 0x103A0..0x103DF -> 104
        in 0x10450..0x1047F -> 105
        in 0x10480..0x104AF -> 106
        in 0x10800..0x1083F -> 107
        in 0x10A00..0x10A5F -> 108
        in 0x1D300..0x1D35F -> 109
        in 0x12000..0x123FF, in 0x12400..0x1247F -> 110
        in 0x1D360..0x1D37F -> 111
        in 0x1B80..0x1BBF -> 112
        in 0x1C00..0x1C4F -> 113
        in 0x1C50..0x1C7F -> 114
        in 0xA880..0xA8DF -> 115
        in 0xA900..0xA92F -> 116
        in 0xA930..0xA95F -> 117
        in 0xAA00..0xAA5F -> 118
        in 0x10190..0x101CF -> 119
        in 0x101D0..0x101FF -> 120
        in 0x10280..0x1029F, in 0x102A0..0x102DF, in 0x10920..0x1093F -> 121
        in 0x1F000..0x1F02F, in 0x1F030..0x1F09F -> 122
        else -> null
    }
