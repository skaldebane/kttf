package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OS2TableTest {
    @Test
    fun acceptsEverySupportedVersionAtItsMinimumLengthAndPadding() {
        val lengths = intArrayOf(78, 86, 96, 96, 96, 100)
        for (version in lengths.indices) {
            assertEquals(version, parse(os2(version, lengths[version])).version)
            assertEquals(version, parse(os2(version, lengths[version] + 7)).version)
        }
    }

    @Test
    fun rejectsUnknownVersionsAndEveryTruncatedVersion() {
        val lengths = intArrayOf(78, 86, 96, 96, 96, 100)
        for (version in lengths.indices) {
            assertNull(OS2Table.parse(ByteSlice.of(os2(version, lengths[version] - 1))))
        }
        assertNull(OS2Table.parse(ByteSlice.of(os2(6, 100))))
        assertNull(OS2Table.parse(ByteSlice.of(byteArrayOf(0))))
    }

    @Test
    fun readsWeightWidthAndDefaultsInvalidWidth() {
        val data = os2(0, 78)
        data.writeUInt16(4, 700)
        data.writeUInt16(6, 8)

        val table = parse(data)
        assertEquals(OS2Weight(700), table.weight)
        assertEquals(OS2WeightKind.BOLD, table.weight.kind)
        assertEquals(OS2Width.EXTRA_EXPANDED, table.width)

        data.writeUInt16(4, 350)
        data.writeUInt16(6, 10)
        assertEquals(OS2WeightKind.OTHER, table.weight.kind)
        assertEquals(350, table.weight.value)
        assertEquals(OS2Width.NORMAL, table.width)
    }

    @Test
    fun appliesVersionSpecificEmbeddingPermissionRules() {
        val old = parse(os2(2, 96))
        val current = parse(os2(3, 96))

        old.bytes.writeUInt16(8, 0x000E)
        current.bytes.writeUInt16(8, 0x000E)
        assertEquals(OS2Permissions.EDITABLE, old.permissions)
        assertNull(current.permissions)

        val validPermissions =
            listOf(
                0 to OS2Permissions.INSTALLABLE,
                2 to OS2Permissions.RESTRICTED,
                4 to OS2Permissions.PREVIEW_AND_PRINT,
                8 to OS2Permissions.EDITABLE,
            )
        for ((bits, expected) in validPermissions) {
            current.bytes.writeUInt16(8, bits)
            assertEquals(expected, current.permissions)
        }
    }

    @Test
    fun embeddingRestrictionFlagsExistOnlyFromVersionTwo() {
        val version1 = parse(os2(1, 86))
        val version2 = parse(os2(2, 96))
        version1.bytes.writeUInt16(8, 0x0300)
        version2.bytes.writeUInt16(8, 0x0300)

        assertTrue(version1.isSubsettingAllowed)
        assertTrue(version1.isOutlineEmbeddingAllowed)
        assertFalse(version2.isSubsettingAllowed)
        assertFalse(version2.isOutlineEmbeddingAllowed)
    }

    @Test
    fun readsSignedScriptStrikeoutAndFaceMetrics() {
        val data = os2(2, 96)
        listOf(100, -200, 300, -400).forEachIndexed { index, value -> data.writeInt16(10 + index * 2, value) }
        listOf(-500, 600, -700, 800).forEachIndexed { index, value -> data.writeInt16(18 + index * 2, value) }
        data.writeInt16(26, -20)
        data.writeInt16(28, 250)
        data.writeInt16(68, 900)
        data.writeInt16(70, -250)
        data.writeInt16(72, 100)
        data.writeInt16(74, 1000)
        data.writeInt16(76, 300)

        val table = parse(data)
        assertEquals(OS2ScriptMetrics(100, -200, 300, -400), table.subscriptMetrics)
        assertEquals(OS2ScriptMetrics(-500, 600, -700, 800), table.superscriptMetrics)
        assertEquals(OS2LineMetrics(-20, 250), table.strikeoutMetrics)
        assertEquals(900, table.typographicAscender)
        assertEquals(-250, table.typographicDescender)
        assertEquals(100, table.typographicLineGap)
        assertEquals(1000, table.windowsAscender)
        assertEquals(-300, table.windowsDescender)
    }

    @Test
    fun readsAllSelectionFlagsButGatesNewSemanticsByVersion() {
        val old = parse(os2(3, 96))
        val current = parse(os2(4, 96))
        val allFlags = 0x03FF
        old.bytes.writeUInt16(62, allFlags)
        current.bytes.writeUInt16(62, allFlags)

        val flags = current.selectionFlags
        assertTrue(flags.isItalic)
        assertTrue(flags.isUnderscored)
        assertTrue(flags.isNegative)
        assertTrue(flags.isOutlined)
        assertTrue(flags.isStrikeout)
        assertTrue(flags.isBold)
        assertTrue(flags.isRegular)
        assertTrue(flags.usesTypographicMetrics)
        assertTrue(flags.hasWwsNames)
        assertTrue(flags.isOblique)
        assertFalse(old.usesTypographicMetrics)
        assertTrue(current.usesTypographicMetrics)
        assertEquals(OS2Style.ITALIC, current.style)

        old.bytes.writeUInt16(62, 1 shl 9)
        current.bytes.writeUInt16(62, 1 shl 9)
        assertEquals(OS2Style.NORMAL, old.style)
        assertEquals(OS2Style.OBLIQUE, current.style)
    }

    @Test
    fun italicTakesPrecedenceOverOblique() {
        val table = parse(os2(4, 96))
        table.bytes.writeUInt16(62, (1 shl 0) or (1 shl 9))
        assertEquals(OS2Style.ITALIC, table.style)
    }

    @Test
    fun unicodeRangesUseOpenTypeBitOrderAndMergedRanges() {
        val data = os2(0, 78)
        data.writeUInt32(42, (1L shl 0) or (1L shl 31))
        data.writeUInt32(46, (1L shl (59 - 32)))
        data.writeUInt32(50, (1L shl (90 - 64)))
        data.writeUInt32(54, (1L shl (122 - 96)))
        val ranges = parse(data).unicodeRanges

        assertTrue(ranges.contains('A'))
        assertTrue(ranges.contains(0x2E10))
        assertTrue(ranges.contains(0x20000))
        assertTrue(ranges.contains(0xF0000))
        assertTrue(ranges.contains(0x1F000))
        assertFalse(ranges.contains(0x0370))
        assertFalse(ranges.contains(0xD800))
        assertFalse(ranges.contains(0x110000))
        assertFalse(ranges.containsRange(57))
    }

    @Test
    fun codePageRangesAreAbsentInVersionZeroAndSpanBothWords() {
        assertNull(parse(os2(0, 78)).codePageRanges)

        val data = os2(1, 86)
        data.writeUInt32(78, (1L shl 0) or (1L shl 31))
        data.writeUInt32(82, (1L shl (48 - 32)) or (1L shl (63 - 32)))
        val ranges = parse(data).codePageRanges!!

        assertTrue(ranges.contains(OS2CodePage.LATIN_1_1252))
        assertTrue(ranges.contains(OS2CodePage.SYMBOL_CHARACTER_SET))
        assertTrue(ranges.contains(OS2CodePage.IBM_GREEK_869))
        assertTrue(ranges.contains(OS2CodePage.US_437))
        assertFalse(ranges.contains(OS2CodePage.CYRILLIC_1251))
        assertFalse(ranges.containsBit(-1))
        assertFalse(ranges.containsBit(64))
    }

    @Test
    fun versionTwoAndFiveFieldsAreVersionGated() {
        val version1 = parse(os2(1, 86))
        assertNull(version1.xHeight)
        assertNull(version1.capitalHeight)
        assertNull(version1.defaultCharacter)
        assertNull(version1.breakCharacter)
        assertNull(version1.maximumContext)
        assertNull(version1.lowerOpticalPointSize)

        val version2Data = os2(2, 96)
        version2Data.writeInt16(86, 450)
        version2Data.writeInt16(88, 700)
        version2Data.writeUInt16(90, 0)
        version2Data.writeUInt16(92, 32)
        version2Data.writeUInt16(94, 12)
        val version2 = parse(version2Data)
        assertEquals(450, version2.xHeight)
        assertEquals(700, version2.capitalHeight)
        assertEquals(0, version2.defaultCharacter)
        assertEquals(32, version2.breakCharacter)
        assertEquals(12, version2.maximumContext)
        assertNull(version2.lowerOpticalPointSize)

        val version5Data = os2(5, 100)
        version5Data.writeUInt16(96, 160)
        version5Data.writeUInt16(98, 1440)
        val version5 = parse(version5Data)
        assertEquals(8f, version5.lowerOpticalPointSize)
        assertEquals(72f, version5.upperOpticalPointSize)
    }

    @Test
    fun retainsTheOriginalBoundedBytesWithoutCopying() {
        val source = ByteArray(110)
        source.writeUInt16(5, 0)
        val slice = ByteSlice.of(source).slice(5, 100)!!
        val table = OS2Table.parse(slice)!!

        source.writeUInt16(5 + 4, 400)
        assertEquals(400, table.weight.value)
    }

    private fun parse(data: ByteArray): ParsedTable {
        val table = OS2Table.parse(ByteSlice.of(data))!!
        return ParsedTable(table, data)
    }

    private class ParsedTable(
        private val table: OS2Table,
        internal val bytes: ByteArray,
    ) {
        internal val version: Int get() = table.version
        internal val weight: OS2Weight get() = table.weight
        internal val width: OS2Width get() = table.width
        internal val permissions: OS2Permissions? get() = table.permissions
        internal val isSubsettingAllowed: Boolean get() = table.isSubsettingAllowed
        internal val isOutlineEmbeddingAllowed: Boolean get() = table.isOutlineEmbeddingAllowed
        internal val subscriptMetrics: OS2ScriptMetrics get() = table.subscriptMetrics
        internal val superscriptMetrics: OS2ScriptMetrics get() = table.superscriptMetrics
        internal val strikeoutMetrics: OS2LineMetrics get() = table.strikeoutMetrics
        internal val typographicAscender: Int get() = table.typographicAscender
        internal val typographicDescender: Int get() = table.typographicDescender
        internal val typographicLineGap: Int get() = table.typographicLineGap
        internal val windowsAscender: Int get() = table.windowsAscender
        internal val windowsDescender: Int get() = table.windowsDescender
        internal val selectionFlags: OS2SelectionFlags get() = table.selectionFlags
        internal val usesTypographicMetrics: Boolean get() = table.usesTypographicMetrics
        internal val style: OS2Style get() = table.style
        internal val unicodeRanges: OS2UnicodeRanges get() = table.unicodeRanges
        internal val codePageRanges: OS2CodePageRanges? get() = table.codePageRanges
        internal val xHeight: Int? get() = table.xHeight
        internal val capitalHeight: Int? get() = table.capitalHeight
        internal val defaultCharacter: Int? get() = table.defaultCharacter
        internal val breakCharacter: Int? get() = table.breakCharacter
        internal val maximumContext: Int? get() = table.maximumContext
        internal val lowerOpticalPointSize: Float? get() = table.lowerOpticalPointSize
        internal val upperOpticalPointSize: Float? get() = table.upperOpticalPointSize
    }

    private fun os2(
        version: Int,
        length: Int,
    ): ByteArray = ByteArray(length).also { it.writeUInt16(0, version) }

    private fun ByteArray.writeUInt16(
        offset: Int,
        value: Int,
    ) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.writeInt16(
        offset: Int,
        value: Int,
    ) = writeUInt16(offset, value and 0xFFFF)

    private fun ByteArray.writeUInt32(
        offset: Int,
        value: Long,
    ) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
