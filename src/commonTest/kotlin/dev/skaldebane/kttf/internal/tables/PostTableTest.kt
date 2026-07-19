package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostTableTest {
    @Test
    fun acceptsEverySupportedVersionWithExactHeaderOrPadding() {
        val versions = longArrayOf(0x0001_0000, 0x0002_0000, 0x0002_5000, 0x0003_0000, 0x0004_0000)
        for (version in versions) {
            val minimum = post(version = version, glyphIndexes = if (version == 0x0002_0000L) intArrayOf() else null)
            assertEquals(version, parse(minimum).version)
            assertEquals(version, parse(minimum + ByteArray(7)).version)
        }
    }

    @Test
    fun rejectsTruncatedHeadersUnknownVersionsAndTruncatedVersionTwoIndexes() {
        assertNull(PostTable.parse(ByteSlice.of(ByteArray(31))))
        assertNull(PostTable.parse(ByteSlice.of(post(version = 0))))
        assertNull(PostTable.parse(ByteSlice.of(post(version = 0x0005_0000))))

        val truncatedCount = post(version = 0x0002_0000).copyOf(32)
        assertNull(PostTable.parse(ByteSlice.of(truncatedCount)))

        val truncatedIndexes =
            TestByteBuilder()
                .raw(post(version = 0x0002_0000).copyOf(32))
                .uint16(2)
                .uint16(36)
                .build()
        assertNull(PostTable.parse(ByteSlice.of(truncatedIndexes)))
    }

    @Test
    fun readsFixedItalicAngleSignedUnderlineMetricsAndPitchFlag() {
        val table =
            parse(
                post(
                    version = 0x0003_0000,
                    italicAngleRaw = (-12.5f * 65536f).toInt(),
                    underlinePosition = -125,
                    underlineThickness = 35,
                    fixedPitch = 0x8000_0000L,
                ),
            )

        assertEquals(-12.5f, table.italicAngle)
        assertEquals(PostLineMetrics(position = -125, thickness = 35), table.underlineMetrics)
        assertTrue(table.isMonospaced)
        assertFalse(parse(post(version = 0x0001_0000)).isMonospaced)
    }

    @Test
    fun onlyVersionTwoProvidesGlyphNames() {
        val versions = longArrayOf(0x0001_0000, 0x0002_5000, 0x0003_0000, 0x0004_0000)
        for (version in versions) {
            val table = parse(post(version = version, trailingData = byteArrayOf(0, 1, 0, 36)))
            assertNull(table.glyphName(0))
            assertNull(table.glyphIndexByName("A"))
            assertNull(table.names().next())
        }
    }

    @Test
    fun resolvesMacintoshAndCustomNamesInBothDirections() {
        val table =
            parse(
                post(
                    version = 0x0002_0000,
                    glyphIndexes = intArrayOf(36, 258, 258, 259, 65535),
                    customNames = arrayOf(ascii("custom"), ascii("second")),
                ),
            )

        assertEquals("A", table.glyphName(0))
        assertEquals("custom", table.glyphName(1))
        assertEquals("custom", table.glyphName(2))
        assertEquals("second", table.glyphName(3))
        assertNull(table.glyphName(4))
        assertNull(table.glyphName(-1))
        assertNull(table.glyphName(5))

        assertEquals(0, table.glyphIndexByName("A"))
        assertEquals(1, table.glyphIndexByName("custom"))
        assertEquals(3, table.glyphIndexByName("second"))
        assertNull(table.glyphIndexByName("missing"))
    }

    @Test
    fun standardNameLookupDoesNotFallBackToAnIdenticalCustomName() {
        val table =
            parse(
                post(
                    version = 0x0002_0000,
                    glyphIndexes = intArrayOf(258),
                    customNames = arrayOf(ascii("A")),
                ),
            )

        assertEquals("A", table.glyphName(0))
        assertNull(table.glyphIndexByName("A"))
    }

    @Test
    fun decodesStrictUtf8IncludingSupplementaryCodePoints() {
        val table =
            parse(
                post(
                    version = 0x0002_0000,
                    glyphIndexes = intArrayOf(258, 259),
                    customNames =
                        arrayOf(
                            byteArrayOf(0xC3.toByte(), 0xA9.toByte()),
                            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()),
                        ),
                ),
            )

        assertEquals("\u00E9", table.glyphName(0))
        assertEquals("\uD83D\uDE00", table.glyphName(1))
        assertEquals(1, table.glyphIndexByName("\uD83D\uDE00"))
    }

    @Test
    fun malformedPascalNamesFailQueriesWithoutRejectingTheTable() {
        val malformedNames =
            listOf(
                byteArrayOf(0, 1, 'x'.code.toByte()),
                byteArrayOf(2, 'x'.code.toByte()),
                byteArrayOf(2, 0xC0.toByte(), 0x80.toByte()),
                byteArrayOf(3, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
                byteArrayOf(4, 0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            )

        for (names in malformedNames) {
            val data = post(version = 0x0002_0000, glyphIndexes = intArrayOf(258), rawNames = names)
            val table = PostTable.parse(ByteSlice.of(data))!!
            assertNull(table.glyphName(0))
            assertNull(table.glyphIndexByName("x"))
        }
    }

    @Test
    fun malformedEarlierNamePreventsAccessToLaterNamesLikeUpstreamIterator() {
        val table =
            parse(
                post(
                    version = 0x0002_0000,
                    glyphIndexes = intArrayOf(259),
                    rawNames = byteArrayOf(0, 5) + ascii("later"),
                ),
            )

        assertNull(table.glyphName(0))
        assertNull(table.glyphIndexByName("later"))
    }

    @Test
    fun parsingAndQueriesStayInsideTheProvidedSlice() {
        val outsideIndex = post(version = 0x0002_0000, glyphIndexes = intArrayOf(36))
        assertNull(PostTable.parse(ByteSlice.of(outsideIndex).slice(0, 34)!!))

        val outsideName =
            post(
                version = 0x0002_0000,
                glyphIndexes = intArrayOf(258),
                customNames = arrayOf(ascii("name")),
            )
        val bounded = ByteSlice.of(outsideName).slice(0, 36)!!
        val table = PostTable.parse(bounded)!!
        assertNull(table.glyphName(0))
        assertNull(table.names().next())
    }

    @Test
    fun retainsHeaderIndexesAndNamesWithoutCopying() {
        val tableBytes =
            post(
                version = 0x0002_0000,
                glyphIndexes = intArrayOf(3),
                customNames = arrayOf(ascii("mutable")),
            )
        val source = ByteArray(tableBytes.size + 6)
        tableBytes.copyInto(source, destinationOffset = 3)
        val table = PostTable.parse(ByteSlice.of(source).slice(3, tableBytes.size)!!)!!

        source.writeInt32(3 + 4, (7.25f * 65536f).toInt())
        source.writeUInt16(3 + 34, 258)
        source[3 + 37] = 'M'.code.toByte()

        assertEquals(7.25f, table.italicAngle)
        assertEquals("Mutable", table.glyphName(0))
    }

    @Test
    fun namesViewReadsPascalStringsSequentially() {
        val names =
            parse(
                post(
                    version = 0x0002_0000,
                    glyphIndexes = intArrayOf(),
                    customNames = arrayOf(ascii("one"), ascii("two")),
                ),
            ).names()

        assertEquals("one", names.next())
        assertEquals("two", names.next())
        assertNull(names.next())
    }

    private fun parse(data: ByteArray): PostTable = requireNotNull(PostTable.parse(ByteSlice.of(data)))

    private fun post(
        version: Long,
        italicAngleRaw: Int = 0,
        underlinePosition: Int = 0,
        underlineThickness: Int = 0,
        fixedPitch: Long = 0,
        glyphIndexes: IntArray? = null,
        customNames: Array<ByteArray> = emptyArray(),
        rawNames: ByteArray? = null,
        trailingData: ByteArray = ByteArray(0),
    ): ByteArray =
        TestByteBuilder()
            .uint32(version)
            .uint32(italicAngleRaw.toLong() and 0xFFFF_FFFFL)
            .int16(underlinePosition)
            .int16(underlineThickness)
            .uint32(fixedPitch)
            .zeroes(16)
            .apply {
                if (glyphIndexes != null) {
                    uint16(glyphIndexes.size)
                    glyphIndexes.forEach(::uint16)
                    if (rawNames != null) {
                        raw(rawNames)
                    } else {
                        customNames.forEach { name -> int8(name.size).raw(name) }
                    }
                }
                raw(trailingData)
            }.build()

    private fun ascii(value: String): ByteArray = ByteArray(value.length) { value[it].code.toByte() }

    private fun ByteArray.writeUInt16(
        offset: Int,
        value: Int,
    ) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.writeInt32(
        offset: Int,
        value: Int,
    ) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
