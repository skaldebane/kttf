package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.OutlineSink
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.tables.variations.NormalizedCoordinate
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates
import dev.skaldebane.kttf.testCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Cff2TableTest {
    @Test
    fun parsesHeaderTopDictionaryAndCff2Indexes() {
        val fixture = cff2(listOf(cs2(10, HMOVETO, 20, 30, RLINETO)))
        val padded = bytes(fixture.copyOfRange(0, 5), byteArrayOf(90, 91, 92), fixture.copyOfRange(5, fixture.size))
        padded[2] = 8
        val topStart = 8
        padded[topStart + 4] = (padded[topStart + 4] + 3).toByte()
        padded[topStart + 10] = (padded[topStart + 10] + 3).toByte()

        val table = assertNotNull(parseCff2(padded))
        val sink = Cff2RecordingSink()
        assertEquals(1, table.glyphCount)
        assertEquals(FontBounds(10, 0, 30, 30), table.outline(0, coordinates(), sink))
        assertEquals(listOf("M 10.0 0.0", "L 30.0 30.0"), sink.commands)
        assertNull(table.outline(-1, coordinates(), sink))
        assertNull(table.outline(1, coordinates(), sink))
    }

    @Test
    fun usesCff2EofWidthsAndHintSemantics() {
        assertOutline(cs2(1, 2, 3, HSTEM, 10, HMOVETO), FontBounds(10, 0, 10, 0), "M 10.0 0.0")
        assertOutline(
            cs2(1, 2, 3, HINTMASK, raw2(0), 10, HMOVETO),
            FontBounds(10, 0, 10, 0),
            "M 10.0 0.0",
        )

        val widthLikeMove = assertNotNull(parseCff2(cff2(listOf(cs2(50, 10, HMOVETO)))))
        assertNull(widthLikeMove.outline(0, coordinates(), Cff2RecordingSink()))
        for (operator in listOf(RETURN, ENDCHAR)) {
            val table = assertNotNull(parseCff2(cff2(listOf(cs2(10, HMOVETO, operator)))))
            assertNull(table.outline(0, coordinates(), Cff2RecordingSink()))
        }
    }

    @Test
    fun enforcesTheCff2OperandStackLimit() {
        val accepted = cs2(IntArray(513) { it }, HSTEM, 10, HMOVETO)
        val rejected = cs2(IntArray(514) { it }, HSTEM, 10, HMOVETO)

        assertOutline(accepted, FontBounds(10, 0, 10, 0), "M 10.0 0.0")
        val table = assertNotNull(parseCff2(cff2(listOf(rejected))))
        assertNull(table.outline(0, coordinates(), Cff2RecordingSink()))
    }

    @Test
    fun blendsAtDefaultIntermediateAndPeakCoordinates() {
        val charString = cs2(10, 20, 1, BLEND, HMOVETO)
        val store = variationStore(listOf(intArrayOf(0)), axisCount = 1, regions = listOf(intArrayOf(0, 16384, 16384)))
        val table = assertNotNull(parseCff2(cff2(listOf(charString), variationStore = store)))

        assertEquals(FontBounds(10, 0, 10, 0), table.outline(0, coordinates(0f), Cff2RecordingSink()))
        assertEquals(FontBounds(20, 0, 20, 0), table.outline(0, coordinates(0.5f), Cff2RecordingSink()))
        assertEquals(FontBounds(30, 0, 30, 0), table.outline(0, coordinates(1f), Cff2RecordingSink()))
    }

    @Test
    fun blendsMultipleValuesAndRegionsInPinnedUpstreamOrder() {
        val charString = cs2(10, 20, 1, 2, 3, 4, 2, BLEND, RMOVETO)
        val store =
            variationStore(
                dataRegionIndices = listOf(intArrayOf(0, 1)),
                axisCount = 1,
                regions =
                    listOf(
                        intArrayOf(0, 8192, 16384),
                        intArrayOf(-16384, -8192, 0),
                    ),
            )
        val table = assertNotNull(parseCff2(cff2(listOf(charString), variationStore = store)))

        assertEquals(FontBounds(11, 23, 11, 23), table.outline(0, coordinates(0.5f), Cff2RecordingSink()))
        assertEquals(FontBounds(12, 24, 12, 24), table.outline(0, coordinates(-0.5f), Cff2RecordingSink()))
    }

    @Test
    fun selectsVariationDataOnceBeforeBlend() {
        val store =
            variationStore(
                dataRegionIndices = listOf(intArrayOf(0), intArrayOf(1)),
                axisCount = 1,
                regions =
                    listOf(
                        intArrayOf(-16384, -16384, 0),
                        intArrayOf(0, 16384, 16384),
                    ),
            )
        val selected =
            assertNotNull(
                parseCff2(
                    cff2(
                        listOf(cs2(1, VSINDEX, 10, 20, 1, BLEND, HMOVETO)),
                        variationStore = store,
                    ),
                ),
            )
        assertEquals(FontBounds(30, 0, 30, 0), selected.outline(0, coordinates(1f), Cff2RecordingSink()))

        val malformed =
            listOf(
                cs2(VSINDEX, 10, HMOVETO),
                cs2(0, 1, VSINDEX, 10, HMOVETO),
                cs2(0, VSINDEX, 0, VSINDEX, 10, HMOVETO),
                cs2(10, 20, 1, BLEND, 0, VSINDEX, HMOVETO),
                cs2(9, VSINDEX, 10, HMOVETO),
                cs2(10, 2, BLEND, HMOVETO),
            )
        malformed.forEachIndexed { index, charString ->
            val table = assertNotNull(parseCff2(cff2(listOf(charString), variationStore = store)), "case $index")
            assertNull(table.outline(0, coordinates(1f), Cff2RecordingSink()), "case $index")
        }
    }

    @Test
    fun executesGlobalAndFirstAvailableFontDictionaryLocalSubroutines() {
        val table =
            assertNotNull(
                parseCff2(
                    cff2(
                        chars = listOf(cs2(10, HMOVETO, -107, CALLSUBR, -107, CALLGSUBR)),
                        globalSubrs = listOf(cs2(40, 50, RLINETO)),
                        localSubrs = listOf(cs2(20, 30, RLINETO)),
                    ),
                ),
            )
        val sink = Cff2RecordingSink()

        assertEquals(FontBounds(10, 0, 70, 80), table.outline(0, coordinates(), sink))
        assertEquals(listOf("M 10.0 0.0", "L 30.0 30.0", "L 70.0 80.0"), sink.commands)
    }

    @Test
    fun boundsSubroutineDepthAndBranchingOperationWork() {
        val recursive =
            assertNotNull(
                parseCff2(
                    cff2(
                        chars = listOf(cs2(0, HMOVETO, -107, CALLSUBR)),
                        localSubrs = listOf(cs2(-107, CALLSUBR)),
                    ),
                ),
            )
        assertNull(recursive.outline(0, coordinates(), Cff2RecordingSink()))

        val subroutines =
            List(6) { index ->
                if (index == 5) {
                    byteArrayOf()
                } else {
                    val calls = ArrayList<Any>(20)
                    repeat(10) {
                        calls += index + 1 - 107
                        calls += CALLSUBR
                    }
                    cs2(*calls.toTypedArray())
                }
            }
        val branching =
            assertNotNull(
                parseCff2(
                    cff2(chars = listOf(cs2(0, HMOVETO, -107, CALLSUBR)), localSubrs = subroutines),
                ),
            )
        assertNull(branching.outline(0, coordinates(), Cff2RecordingSink()))
    }

    @Test
    fun rejectsMalformedTablesStoresAndBlendRegionLimits() {
        val valid = cff2(listOf(cs2(10, HMOVETO)))
        for (size in valid.indices) parseCff2(valid.copyOf(size))
        assertNull(parseCff2(byteArrayOf(1, 0, 5, 0, 0)))

        val undersizedHeader = valid.copyOf()
        undersizedHeader[2] = 4
        assertNull(parseCff2(undersizedHeader))

        assertNull(parseCff2(cff2(listOf(cs2(10, HMOVETO)), topDictionarySuffix = byteArrayOf(12))))
        assertNull(
            parseCff2(
                cff2(
                    listOf(cs2(10, HMOVETO)),
                    topDictionarySuffix =
                        ByteArray(CffOperandStack.CFF2_CAPACITY + 2) {
                            if (it == CffOperandStack.CFF2_CAPACITY + 1) 0.toByte() else 139.toByte()
                        },
                ),
            ),
        )
        assertNull(
            parseCff2(
                cff2(
                    listOf(cs2(10, HMOVETO)),
                    topDictionarySuffix = byteArrayOf(139.toByte(), 140.toByte(), VARIATION_STORE2.toByte()),
                ),
            ),
        )
        assertNull(
            parseCff2(
                cff2(
                    listOf(cs2(10, HMOVETO)),
                    topDictionarySuffix = byteArrayOf(30, 0xDF.toByte(), 0),
                ),
            ),
        )
        assertNull(
            parseCff2(
                cff2(
                    chars = listOf(cs2(10, HMOVETO)),
                    localSubrs = listOf(byteArrayOf()),
                    fontDictionarySuffix = byteArrayOf(12),
                ),
            ),
        )
        assertNull(
            parseCff2(
                cff2(
                    chars = listOf(cs2(10, HMOVETO)),
                    localSubrs = listOf(byteArrayOf()),
                    privateDictionarySuffix = byteArrayOf(12),
                ),
            ),
        )

        val withoutStore = assertNotNull(parseCff2(cff2(listOf(cs2(10, HMOVETO)), variationStore = null)))
        assertNull(withoutStore.outline(0, coordinates(), Cff2RecordingSink()))

        val tooManyRegions = variationStore(listOf(IntArray(65)), axisCount = 0, regions = emptyList())
        val limited = assertNotNull(parseCff2(cff2(listOf(cs2(10, HMOVETO)), variationStore = tooManyRegions)))
        assertNull(limited.outline(0, coordinates(), Cff2RecordingSink()))

        val malformedStores =
            listOf(
                byteArrayOf(),
                byteArrayOf(0, 0),
                byteArrayOf(0, 0, 0, 2),
                bytes(byteArrayOf(0, 0), ushort(2), uint(0), ushort(0)),
            )
        malformedStores.forEachIndexed { index, store ->
            assertNull(parseCff2(cff2(listOf(cs2(10, HMOVETO)), variationStore = store)), "case $index")
        }
    }

    private fun assertOutline(
        charString: ByteArray,
        bounds: FontBounds,
        vararg commands: String,
    ) {
        val table = assertNotNull(parseCff2(cff2(listOf(charString))))
        val sink = Cff2RecordingSink()
        assertEquals(bounds, table.outline(0, coordinates(), sink))
        assertEquals(commands.toList(), sink.commands)
    }
}

private fun parseCff2(data: ByteArray): Cff2Table? = Cff2Table.parse(ByteSlice.of(data))

private fun cff2(
    chars: List<ByteArray>,
    globalSubrs: List<ByteArray> = emptyList(),
    localSubrs: List<ByteArray> = emptyList(),
    variationStore: ByteArray? = variationStore(listOf(intArrayOf()), 0, emptyList()),
    topDictionarySuffix: ByteArray = byteArrayOf(),
    fontDictionarySuffix: ByteArray = byteArrayOf(),
    privateDictionarySuffix: ByteArray = byteArrayOf(),
): ByteArray {
    val globalIndex = cff2Index(globalSubrs)
    val charStringsIndex = cff2Index(chars)
    val privateDictionary =
        if (localSubrs.isEmpty()) {
            byteArrayOf()
        } else {
            bytes(dictInt2(6), byteArrayOf(LOCAL_SUBRS2.toByte()), privateDictionarySuffix)
        }
    val localIndex = if (localSubrs.isEmpty()) byteArrayOf() else cff2Index(localSubrs)

    fun topDictionary(
        charStringsOffset: Int,
        variationStoreOffset: Int,
        fontDictionaryOffset: Int,
    ): ByteArray {
        val result = mutableListOf<Byte>()
        result += dictInt2(charStringsOffset).toList()
        result += CHAR_STRINGS2.toByte()
        if (variationStore != null) {
            result += dictInt2(variationStoreOffset).toList()
            result += VARIATION_STORE2.toByte()
        }
        if (localSubrs.isNotEmpty()) {
            result += dictInt2(fontDictionaryOffset).toList()
            result += DICT_ESCAPE2.toByte()
            result += FD_ARRAY2.toByte()
        }
        result += topDictionarySuffix.toList()
        return result.toByteArray()
    }

    val placeholderTop = topDictionary(0, 0, 0)
    val charStringsOffset = CFF2_HEADER_SIZE + placeholderTop.size + globalIndex.size
    val variationStoreOffset = charStringsOffset + charStringsIndex.size
    val fontDictionaryOffset = variationStoreOffset + (variationStore?.size ?: 0)
    val placeholderFontDictionary =
        if (localSubrs.isEmpty()) {
            byteArrayOf()
        } else {
            bytes(
                dictInt2(privateDictionary.size),
                dictInt2(0),
                byteArrayOf(PRIVATE2.toByte()),
                fontDictionarySuffix,
            )
        }
    val placeholderFontIndex = if (localSubrs.isEmpty()) byteArrayOf() else cff2Index(listOf(placeholderFontDictionary))
    val privateOffset = fontDictionaryOffset + placeholderFontIndex.size
    val fontDictionary =
        if (localSubrs.isEmpty()) {
            byteArrayOf()
        } else {
            bytes(
                dictInt2(privateDictionary.size),
                dictInt2(privateOffset),
                byteArrayOf(PRIVATE2.toByte()),
                fontDictionarySuffix,
            )
        }
    val fontIndex = if (localSubrs.isEmpty()) byteArrayOf() else cff2Index(listOf(fontDictionary))
    val top = topDictionary(charStringsOffset, variationStoreOffset, fontDictionaryOffset)
    check(top.size == placeholderTop.size)
    check(fontIndex.size == placeholderFontIndex.size)

    return bytes(
        byteArrayOf(2, 0, 5),
        ushort(top.size),
        top,
        globalIndex,
        charStringsIndex,
        variationStore ?: byteArrayOf(),
        fontIndex,
        privateDictionary,
        localIndex,
    )
}

private fun variationStore(
    dataRegionIndices: List<IntArray>,
    axisCount: Int,
    regions: List<IntArray>,
): ByteArray {
    val itemData =
        dataRegionIndices.map { indices ->
            bytes(
                ushort(0),
                ushort(0),
                ushort(indices.size),
                *indices.map(::ushort).toTypedArray(),
            )
        }
    val storeHeaderSize = 2 + 4 + 2 + dataRegionIndices.size * 4
    val regionListOffset = storeHeaderSize + itemData.sumOf(ByteArray::size)
    val regionList =
        bytes(
            ushort(axisCount),
            ushort(regions.size),
            *regions
                .map { region ->
                    require(region.size == axisCount * 3)
                    bytes(*region.map(::short2).toTypedArray())
                }.toTypedArray(),
        )
    var itemOffset = storeHeaderSize
    val offsets =
        dataRegionIndices.mapIndexed { index, _ ->
            val result = uint(itemOffset)
            itemOffset += itemData[index].size
            result
        }
    val store =
        bytes(
            ushort(1),
            uint(regionListOffset),
            ushort(dataRegionIndices.size),
            *offsets.toTypedArray(),
            *itemData.toTypedArray(),
            regionList,
        )
    return bytes(ushort(store.size), store)
}

private fun cff2Index(objects: List<ByteArray>): ByteArray {
    if (objects.isEmpty()) return uint(0)
    var offset = 1
    val offsets = mutableListOf(uint(offset))
    objects.forEach {
        offset += it.size
        offsets += uint(offset)
    }
    return bytes(uint(objects.size), byteArrayOf(4), *offsets.toTypedArray(), *objects.toTypedArray())
}

private fun cs2(vararg values: Any): ByteArray {
    val result = mutableListOf<Byte>()
    values.forEach { value ->
        when (value) {
            is Int -> result += type2Int2(value).toList()
            is IntArray -> value.forEach { result += type2Int2(it).toList() }
            is Cff2RawByte -> result += value.value.toByte()
            else -> error("unsupported CFF2 charstring fixture value: $value")
        }
    }
    return result.toByteArray()
}

private fun type2Int2(value: Int): ByteArray =
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

        in Short.MIN_VALUE..Short.MAX_VALUE -> {
            bytes(byteArrayOf(28), short2(value))
        }

        else -> {
            error("CFF2 test integer out of range: $value")
        }
    }

private fun dictInt2(value: Int): ByteArray = bytes(byteArrayOf(29), uint(value))

private fun ushort(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

private fun short2(value: Int): ByteArray = ushort(value)

private fun uint(value: Int): ByteArray =
    byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

private fun bytes(vararg values: ByteArray): ByteArray {
    val result = ByteArray(values.sumOf(ByteArray::size))
    var offset = 0
    values.forEach {
        it.copyInto(result, offset)
        offset += it.size
    }
    return result
}

private fun coordinates(vararg values: Float): VariationCoordinates =
    VariationCoordinates.copyOf(values.map { NormalizedCoordinate.fromFloat(it) })

private data class Cff2RawByte(
    val value: Int,
)

private fun raw2(value: Int): Cff2RawByte = Cff2RawByte(value)

private class Cff2RecordingSink : OutlineSink {
    val commands = mutableListOf<String>()

    override fun moveTo(
        x: Float,
        y: Float,
    ) {
        commands += "M ${x.testCoordinate()} ${y.testCoordinate()}"
    }

    override fun lineTo(
        x: Float,
        y: Float,
    ) {
        commands += "L ${x.testCoordinate()} ${y.testCoordinate()}"
    }

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
    ) {
        commands +=
            "C ${control1X.testCoordinate()} ${control1Y.testCoordinate()} " +
            "${control2X.testCoordinate()} ${control2Y.testCoordinate()} " +
            "${x.testCoordinate()} ${y.testCoordinate()}"
    }

    override fun close() {
        commands += "Z"
    }
}

private val HSTEM = raw2(1)
private val RLINETO = raw2(5)
private val CALLSUBR = raw2(10)
private val RETURN = raw2(11)
private val ENDCHAR = raw2(14)
private val VSINDEX = raw2(15)
private val BLEND = raw2(16)
private val HINTMASK = raw2(19)
private val RMOVETO = raw2(21)
private val HMOVETO = raw2(22)
private val CALLGSUBR = raw2(29)
private const val CFF2_HEADER_SIZE = 5
private const val CHAR_STRINGS2 = 17
private const val PRIVATE2 = 18
private const val LOCAL_SUBRS2 = 19
private const val VARIATION_STORE2 = 24
private const val DICT_ESCAPE2 = 12
private const val FD_ARRAY2 = 36
