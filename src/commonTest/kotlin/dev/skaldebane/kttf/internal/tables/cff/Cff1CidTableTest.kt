package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.FontFace
import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.OutlineSink
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.minimalSfnt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Cff1CidTableTest {
    @Test
    fun exposesCidIdentityAndDisablesSidOnlyQueries() {
        val table =
            assertNotNull(
                parseCid(
                    cidCff(
                        chars = listOf(cs(ENDCHAR), cs(10, HMOVETO, ENDCHAR), byteArrayOf(0)),
                        cids = intArrayOf(0x1234, 0xFEDC),
                        fdSelect = fdSelect0(9, 9, 9),
                        fonts = emptyList(),
                        encodingOffset = Int.MAX_VALUE,
                        topPrivateRange = 20 to Int.MAX_VALUE,
                    ),
                ),
            )

        assertEquals(3, table.glyphCount)
        assertEquals(0, table.glyphCid(0))
        assertEquals(0x1234, table.glyphCid(1))
        assertEquals(0xFEDC, table.glyphCid(2))
        assertNull(table.glyphCid(-1))
        assertNull(table.glyphCid(3))
        assertNull(table.glyphName(0))
        assertNull(table.glyphIdByName(".notdef"))
        assertNull(table.glyphIdForCode(65))
        assertNull(table.glyphWidth(1))
        assertNull(table.glyphWidth(2))
        assertEquals(FontBounds(10, 0, 10, 0), table.outline(1, NoOpSink))
    }

    @Test
    fun exposesCidIdentityThroughFontFace() {
        val cff =
            cidCff(
                chars = listOf(cs(ENDCHAR), cs(ENDCHAR), cs(ENDCHAR)),
                cids = intArrayOf(0x1234, 0xFEDC),
                fdSelect = fdSelect0(0, 0, 0),
                fonts = emptyList(),
            )
        val face = FontFace.parse(minimalSfnt(glyphCount = 3, cffData = cff))

        assertEquals(0, face.glyphCid(GlyphId(0)))
        assertEquals(0x1234, face.glyphCid(GlyphId(1)))
        assertEquals(0xFEDC, face.glyphCid(GlyphId(2)))
        assertNull(face.glyphCffWidth(GlyphId(1)))
    }

    @Test
    fun format0SelectsPerGlyphLocalSubroutines() {
        val table =
            assertNotNull(
                parseCid(
                    cidCff(
                        chars = List(3) { localSubroutineCharString() },
                        cids = intArrayOf(100, 200),
                        fdSelect = fdSelect0(0, 0, 1),
                        fonts =
                            listOf(
                                CidFontFixture(localSubroutines = listOf(cs(10, 20, RLINETO, RETURN))),
                                CidFontFixture(localSubroutines = listOf(cs(30, 40, RLINETO, RETURN))),
                            ),
                    ),
                ),
            )

        assertEquals(FontBounds(0, 0, 10, 20), table.outline(1, NoOpSink))
        assertEquals(FontBounds(0, 0, 30, 40), table.outline(2, NoOpSink))
    }

    @Test
    fun format3SelectsRangesAndHonorsTheSentinel() {
        val table =
            assertNotNull(
                parseCid(
                    cidCff(
                        chars = List(4) { localSubroutineCharString() },
                        cids = intArrayOf(10, 20, 30),
                        fdSelect = fdSelect3(0 to 0, 2 to 1, sentinel = 4),
                        fonts =
                            listOf(
                                CidFontFixture(localSubroutines = listOf(cs(5, 10, RLINETO, RETURN))),
                                CidFontFixture(localSubroutines = listOf(cs(15, 20, RLINETO, RETURN))),
                            ),
                    ),
                ),
            )

        assertEquals(FontBounds(0, 0, 5, 10), table.outline(1, NoOpSink))
        assertEquals(FontBounds(0, 0, 15, 20), table.outline(2, NoOpSink))
        assertEquals(FontBounds(0, 0, 15, 20), table.outline(3, NoOpSink))
    }

    @Test
    fun rosAloneSelectsCidBehavior() {
        val sid =
            assertNotNull(
                parseCid(
                    cidCff(
                        chars = listOf(cs(ENDCHAR), cs(ENDCHAR)),
                        cids = intArrayOf(34),
                        hasRos = false,
                    ),
                ),
            )

        assertEquals("A", sid.glyphName(1))
        assertEquals(1, sid.glyphIdByName("A"))
        assertNull(sid.glyphCid(1))
    }

    @Test
    fun requiresCustomCharsetFdArrayAndFdSelectForCidFonts() {
        val chars = List(3) { cs(ENDCHAR) }
        assertNull(parseCid(cidCff(chars, charset = null)))
        for (predefinedCharset in 0..2) {
            assertNull(parseCid(cidCff(chars, charset = null, charsetValue = predefinedCharset)))
        }
        assertNull(parseCid(cidCff(chars, charset = byteArrayOf(3))))
        assertNull(parseCid(cidCff(chars, fdSelect = null)))
        assertNull(parseCid(cidCff(chars, fdSelect = byteArrayOf(2))))
        assertNull(parseCffFdSelect(ByteSlice.of(fdSelect0(0, 0)), 0, chars.size))
        assertNull(parseCid(cidCff(chars, fonts = null)))
        assertNull(parseCid(cidCff(chars, fdArray = byteArrayOf(0, 1, 0))))
    }

    @Test
    fun defersFormat3ValidationUntilLocalSubroutinesAreRequested() {
        val malformedSelectors =
            listOf(
                byteArrayOf(3),
                byteArrayOf(3, 0, 0),
                byteArrayOf(3, 0xFF.toByte(), 0xFF.toByte()),
                byteArrayOf(3, 0, 1, 0, 0, 0),
            )

        for ((index, fdSelect) in malformedSelectors.withIndex()) {
            val withoutCall =
                assertNotNull(
                    parseCid(
                        cidCff(
                            chars = listOf(cs(10, HMOVETO, ENDCHAR)),
                            fdSelect = fdSelect,
                            fonts = emptyList(),
                        ),
                    ),
                    "case $index",
                )
            val withCall =
                assertNotNull(
                    parseCid(
                        cidCff(
                            chars = listOf(localSubroutineCharString()),
                            fdSelect = fdSelect,
                            fonts = emptyList(),
                        ),
                    ),
                    "case $index",
                )

            assertEquals(FontBounds(10, 0, 10, 0), withoutCall.outline(0, NoOpSink), "case $index")
            assertNull(withCall.outline(0, NoOpSink), "case $index")
        }
    }

    @Test
    fun defersFdIndexFontDictionaryAndPrivateDictionaryFailures() {
        val noCall = cs(10, HMOVETO, ENDCHAR)
        val call = localSubroutineCharString()

        fun table(
            charString: ByteArray,
            fdSelect: ByteArray,
            fonts: List<CidFontFixture>,
        ): Cff1Table = assertNotNull(parseCid(cidCff(listOf(charString), fdSelect = fdSelect, fonts = fonts)))

        assertEquals(FontBounds(10, 0, 10, 0), table(noCall, fdSelect0(9), emptyList()).outline(0, NoOpSink))
        assertNull(table(call, fdSelect0(9), emptyList()).outline(0, NoOpSink))

        val malformedFont = CidFontFixture(fontDictionary = byteArrayOf(PRIVATE_DICTIONARY.toByte()))
        assertEquals(FontBounds(10, 0, 10, 0), table(noCall, fdSelect0(0), listOf(malformedFont)).outline(0, NoOpSink))
        assertNull(table(call, fdSelect0(0), listOf(malformedFont)).outline(0, NoOpSink))

        val outOfBoundsSubrs =
            CidFontFixture(privateDictionary = bytes(dictInt(Int.MAX_VALUE), byteArrayOf(LOCAL_SUBRS.toByte())))
        assertEquals(
            FontBounds(10, 0, 10, 0),
            table(noCall, fdSelect0(0), listOf(outOfBoundsSubrs)).outline(0, NoOpSink),
        )
        assertNull(table(call, fdSelect0(0), listOf(outOfBoundsSubrs)).outline(0, NoOpSink))
    }

    @Test
    fun doesNotInspectUnselectedFontDictionaries() {
        val table =
            assertNotNull(
                parseCid(
                    cidCff(
                        chars = listOf(localSubroutineCharString()),
                        fdSelect = fdSelect0(0),
                        fonts =
                            listOf(
                                CidFontFixture(localSubroutines = listOf(cs(12, 24, RLINETO, RETURN))),
                                CidFontFixture(fontDictionary = byteArrayOf(PRIVATE_DICTIONARY.toByte())),
                            ),
                    ),
                ),
            )

        assertEquals(FontBounds(0, 0, 12, 24), table.outline(0, NoOpSink))
    }

    @Test
    fun cidLocalSubroutinesShareTheType2OperationBudget() {
        val subroutines =
            List(6) { index ->
                if (index == 5) {
                    cs(RETURN)
                } else {
                    val calls = ArrayList<Any>(20)
                    repeat(10) {
                        calls += index + 1 - 107
                        calls += CALLSUBR
                    }
                    cs(*calls.toTypedArray(), RETURN)
                }
            }
        val table =
            assertNotNull(
                parseCid(
                    cidCff(
                        chars = listOf(localSubroutineCharString()),
                        fdSelect = fdSelect0(0),
                        fonts = listOf(CidFontFixture(localSubroutines = subroutines)),
                    ),
                ),
            )

        assertNull(table.outline(0, NoOpSink))
    }
}

private data class CidFontFixture(
    val localSubroutines: List<ByteArray>? = null,
    val privateDictionary: ByteArray? = null,
    val fontDictionary: ByteArray? = null,
)

private data class CidPrivateData(
    val dictionary: ByteArray,
    val suffix: ByteArray,
)

private fun parseCid(data: ByteArray): Cff1Table? = Cff1Table.parse(ByteSlice.of(data))

private fun cidCff(
    chars: List<ByteArray>,
    cids: IntArray = IntArray((chars.size - 1).coerceAtLeast(0)) { it + 1 },
    fdSelect: ByteArray? = fdSelect0(*IntArray(chars.size)),
    fonts: List<CidFontFixture>? = listOf(CidFontFixture()),
    charset: ByteArray? = charset0(cids),
    charsetValue: Int? = null,
    fdArray: ByteArray? = null,
    hasRos: Boolean = true,
    encodingOffset: Int? = null,
    topPrivateRange: Pair<Int, Int>? = null,
): ByteArray {
    require(cids.size == (chars.size - 1).coerceAtLeast(0))
    val charStrings = cffIndex(chars)
    val privateData = fonts?.map(::cidPrivateData).orEmpty()

    fun fontDictionary(
        font: CidFontFixture,
        privateStart: Int,
        private: CidPrivateData,
    ): ByteArray =
        font.fontDictionary
            ?: if (font.localSubroutines != null || font.privateDictionary != null) {
                bytes(
                    dictInt(private.dictionary.size),
                    dictInt(privateStart),
                    byteArrayOf(PRIVATE_DICTIONARY.toByte()),
                )
            } else {
                byteArrayOf()
            }

    fun topDictionary(
        charStringsOffset: Int,
        charsetOffset: Int,
        fdSelectOffset: Int,
        fdArrayOffset: Int,
    ): ByteArray {
        val result = mutableListOf<Byte>()
        if (hasRos) {
            repeat(3) { result += dictInt(0).toList() }
            result += DICT_ESCAPE.toByte()
            result += ROS.toByte()
        }
        if (charset != null || charsetValue != null) {
            result += dictInt(charsetValue ?: charsetOffset).toList()
            result += CHARSET.toByte()
        }
        encodingOffset?.let {
            result += dictInt(it).toList()
            result += ENCODING.toByte()
        }
        result += dictInt(charStringsOffset).toList()
        result += CHAR_STRINGS.toByte()
        if (fonts != null || fdArray != null) {
            result += dictInt(fdArrayOffset).toList()
            result += DICT_ESCAPE.toByte()
            result += FD_ARRAY.toByte()
        }
        if (fdSelect != null) {
            result += dictInt(fdSelectOffset).toList()
            result += DICT_ESCAPE.toByte()
            result += FD_SELECT.toByte()
        }
        topPrivateRange?.let { (length, offset) ->
            result += dictInt(length).toList()
            result += dictInt(offset).toList()
            result += PRIVATE_DICTIONARY.toByte()
        }
        return result.toByteArray()
    }

    val placeholderTop = topDictionary(0, 0, 0, 0)
    val topIndexSize = cffIndex(listOf(placeholderTop)).size
    val charStringsOffset = CFF_PREFIX_SIZE + topIndexSize
    val charsetOffset = charStringsOffset + charStrings.size
    val fdSelectOffset = charsetOffset + (charset?.size ?: 0)
    val fdArrayOffset = fdSelectOffset + (fdSelect?.size ?: 0)
    val placeholderFontDictionaries =
        fonts?.mapIndexed { index, font -> fontDictionary(font, 0, privateData[index]) }.orEmpty()
    val placeholderFdArray = fdArray ?: cffIndex(placeholderFontDictionaries)

    var privateStart = fdArrayOffset + placeholderFdArray.size
    val fontDictionaries =
        fonts
            ?.mapIndexed { index, font ->
                val private = privateData[index]
                val dictionary = fontDictionary(font, privateStart, private)
                privateStart += private.dictionary.size + private.suffix.size
                dictionary
            }.orEmpty()
    val fdArrayData = fdArray ?: cffIndex(fontDictionaries)
    check(fdArrayData.size == placeholderFdArray.size)

    val top = topDictionary(charStringsOffset, charsetOffset, fdSelectOffset, fdArrayOffset)
    check(cffIndex(listOf(top)).size == topIndexSize)
    return bytes(
        byteArrayOf(1, 0, 4, 0),
        byteArrayOf(0, 0),
        cffIndex(listOf(top)),
        byteArrayOf(0, 0),
        byteArrayOf(0, 0),
        charStrings,
        charset ?: byteArrayOf(),
        fdSelect ?: byteArrayOf(),
        fdArrayData,
        *privateData.flatMap { listOf(it.dictionary, it.suffix) }.toTypedArray(),
    )
}

private fun cidPrivateData(font: CidFontFixture): CidPrivateData {
    if (font.localSubroutines != null) {
        val dictionary = bytes(dictInt(6), byteArrayOf(LOCAL_SUBRS.toByte()))
        return CidPrivateData(dictionary, cffIndex(font.localSubroutines))
    }
    return CidPrivateData(font.privateDictionary ?: byteArrayOf(), byteArrayOf())
}

private fun charset0(cids: IntArray): ByteArray {
    val result = mutableListOf<Byte>(0)
    cids.forEach { appendUInt16(result, it) }
    return result.toByteArray()
}

private fun fdSelect0(vararg indices: Int): ByteArray = byteArrayOf(0, *indices.map(Int::toByte).toByteArray())

private fun fdSelect3(
    vararg ranges: Pair<Int, Int>,
    sentinel: Int,
): ByteArray {
    val result = mutableListOf<Byte>(3)
    appendUInt16(result, ranges.size)
    ranges.forEach { (firstGlyph, index) ->
        appendUInt16(result, firstGlyph)
        result += index.toByte()
    }
    appendUInt16(result, sentinel)
    return result.toByteArray()
}

private fun localSubroutineCharString(): ByteArray = cs(0, 0, RMOVETO, -107, CALLSUBR, ENDCHAR)

private fun cffIndex(objects: List<ByteArray>): ByteArray {
    if (objects.isEmpty()) return byteArrayOf(0, 0)
    val result = mutableListOf<Byte>()
    appendUInt16(result, objects.size)
    result += 4
    var offset = 1L
    appendUInt32(result, offset)
    for (data in objects) {
        offset += data.size
        appendUInt32(result, offset)
    }
    objects.forEach { result += it.toList() }
    return result.toByteArray()
}

private fun cs(vararg values: Any): ByteArray {
    val result = mutableListOf<Byte>()
    for (value in values) {
        when (value) {
            is Int -> result += type2Int(value).toList()
            is CidRawByte -> result += value.value.toByte()
            else -> error("unsupported charstring fixture value: $value")
        }
    }
    return result.toByteArray()
}

private fun type2Int(value: Int): ByteArray =
    when (value) {
        in -107..107 -> {
            byteArrayOf((value + 139).toByte())
        }

        in 108..1131 -> {
            val adjusted = value - 108
            byteArrayOf((247 + adjusted / 256).toByte(), (adjusted % 256).toByte())
        }

        in -1131..-108 -> {
            val adjusted = -value - 108
            byteArrayOf((251 + adjusted / 256).toByte(), (adjusted % 256).toByte())
        }

        else -> {
            byteArrayOf(28, (value shr 8).toByte(), value.toByte())
        }
    }

private fun dictInt(value: Int): ByteArray =
    byteArrayOf(29, (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

private fun appendUInt16(
    result: MutableList<Byte>,
    value: Int,
) {
    result += (value shr 8).toByte()
    result += value.toByte()
}

private fun appendUInt32(
    result: MutableList<Byte>,
    value: Long,
) {
    result += (value shr 24).toByte()
    result += (value shr 16).toByte()
    result += (value shr 8).toByte()
    result += value.toByte()
}

private fun bytes(vararg values: ByteArray): ByteArray {
    val result = ByteArray(values.sumOf(ByteArray::size))
    var offset = 0
    for (value in values) {
        value.copyInto(result, offset)
        offset += value.size
    }
    return result
}

private data class CidRawByte(
    val value: Int,
)

private fun raw(value: Int): CidRawByte = CidRawByte(value)

private data object NoOpSink : OutlineSink {
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

private val RLINETO = raw(5)
private val CALLSUBR = raw(10)
private val RETURN = raw(11)
private val ESCAPE = raw(12)
private val ENDCHAR = raw(14)
private val RMOVETO = raw(21)
private val HMOVETO = raw(22)
private const val CFF_PREFIX_SIZE = 10
private const val CHARSET = 15
private const val ENCODING = 16
private const val CHAR_STRINGS = 17
private const val PRIVATE_DICTIONARY = 18
private const val LOCAL_SUBRS = 19
private const val DICT_ESCAPE = 12
private const val ROS = 30
private const val FD_ARRAY = 36
private const val FD_SELECT = 37
