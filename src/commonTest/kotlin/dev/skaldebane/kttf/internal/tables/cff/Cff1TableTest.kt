package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.OutlineSink
import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.testCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Cff1TableTest {
    @Test
    fun parsesHeaderIndexAndNonDefaultHeaderSize() {
        val fixture = cff(chars = listOf(cs(10, HMOVETO, ENDCHAR)))
        val padded = bytes(fixture.copyOfRange(0, 4), byteArrayOf(90, 91, 92, 93), fixture.copyOfRange(4, fixture.size))
        padded[2] = 8
        val topDataStart = 4 + 4 + 2 + 2 + 1 + 8
        padded[topDataStart + 4] = (padded[topDataStart + 4] + 4).toByte()

        val table = assertNotNull(parse(padded))
        val sink = RecordingSink()
        assertEquals(1, table.glyphCount)
        assertEquals(FontBounds(10, 0, 10, 0), table.outline(0, sink))
        assertEquals(listOf("M 10.0 0.0", "Z"), sink.commands)
    }

    @Test
    fun resolvesStandardAndCustomGlyphNamesThroughCustomCharset() {
        val table =
            assertNotNull(
                parse(
                    cff(
                        chars = List(3) { cs(ENDCHAR) },
                        strings = listOf(byteArrayOf(99, 117, 115, 116, 111, 109)),
                        charset = byteArrayOf(0, 0, 34, 1, 0x87.toByte()),
                    ),
                ),
            )

        assertEquals(".notdef", table.glyphName(0))
        assertEquals("A", table.glyphName(1))
        assertEquals("custom", table.glyphName(2))
        assertEquals(0, table.glyphIdByName(".notdef"))
        assertEquals(1, table.glyphIdByName("A"))
        assertEquals(2, table.glyphIdByName("custom"))
        assertNull(table.glyphName(3))
        assertNull(table.glyphIdByName("missing"))
    }

    @Test
    fun decodesCustomNamesLazilyAndPreservesPredefinedReverseMapping() {
        val malformed =
            assertNotNull(
                parse(
                    cff(
                        chars = List(2) { cs(ENDCHAR) },
                        strings = listOf(byteArrayOf(0xC0.toByte(), 0x80.toByte())),
                        charset = byteArrayOf(0, 1, 0x87.toByte()),
                    ),
                ),
            )
        assertNull(malformed.glyphName(1))
        assertNull(malformed.glyphIdByName("missing"))

        val isoAdobe = assertNotNull(parse(cff(chars = List(2) { cs(ENDCHAR) })))
        assertEquals("space", isoAdobe.glyphName(1))
        assertNull(isoAdobe.glyphIdByName("space"))
    }

    @Test
    fun resolvesConfiguredEncodingBeforeStandardFallback() {
        val table =
            assertNotNull(
                parse(
                    cff(
                        chars = List(3) { cs(ENDCHAR) },
                        charset = byteArrayOf(0, 0, 34, 0, 35),
                        encoding = byteArrayOf(0, 1, 7),
                    ),
                ),
            )

        assertEquals(1, table.glyphIdForCode(7))
        assertEquals(1, table.glyphIdForCode(65))
        assertEquals(2, table.glyphIdForCode(66))
        assertEquals(0, table.glyphIdForCode(8))
        assertNull(table.glyphIdForCode(-1))
        assertNull(table.glyphIdForCode(256))
    }

    @Test
    fun rejectsIncompleteCidAndMalformedTableBoundaries() {
        assertNull(parse(byteArrayOf(2, 0, 4, 0)))
        assertNull(parse(cff(chars = listOf(cs(ENDCHAR)), cid = true)))

        val valid = cff(chars = listOf(cs(10, HMOVETO, ENDCHAR)))
        for (size in valid.indices) parse(valid.copyOf(size))

        val zeroCharStringsOffset = valid.copyOf()
        val topDataStart = 4 + 2 + 2 + 1 + 8
        zeroCharStringsOffset.fill(0, topDataStart + 1, topDataStart + 5)
        assertNull(parse(zeroCharStringsOffset))
    }

    @Test
    fun rejectsUndersizedHeadersAndMalformedTopDictionaries() {
        val valid = cff(chars = listOf(cs(ENDCHAR)))
        val undersizedHeader = valid.copyOf()
        undersizedHeader[2] = 3
        assertNull(parse(undersizedHeader))

        assertNull(parse(cff(chars = listOf(cs(ENDCHAR)), topDictionarySuffix = byteArrayOf(12))))
        assertNull(
            parse(
                cff(
                    chars = listOf(cs(ENDCHAR)),
                    topDictionarySuffix =
                        ByteArray(CffOperandStack.CFF1_CAPACITY + 2) {
                            if (it == CffOperandStack.CFF1_CAPACITY + 1) 0.toByte() else 139.toByte()
                        },
                ),
            ),
        )
        assertNull(
            parse(
                cff(
                    chars = listOf(cs(ENDCHAR)),
                    topDictionarySuffix = byteArrayOf(30, 0xDF.toByte(), 0),
                ),
            ),
        )
    }

    @Test
    fun requiresExactFiniteMatrixAndWidthOperands() {
        assertNull(parse(cff(chars = listOf(cs(ENDCHAR)), matrix = intArrayOf(1, 0, 0, 1, 0))))

        val oneExponentOneHundred = byteArrayOf(30, 0x1B, 0x10, 0x0F)
        val nonFiniteFloatMatrix =
            bytes(
                oneExponentOneHundred,
                ByteArray(5) { 139.toByte() },
                byteArrayOf(ESCAPE.value.toByte(), FONT_MATRIX.toByte()),
            )
        assertNull(parse(cff(chars = listOf(cs(ENDCHAR)), topDictionarySuffix = nonFiniteFloatMatrix)))

        assertNull(
            parse(
                cff(
                    chars = listOf(cs(ENDCHAR)),
                    privateDictionaryPrefix = bytes(oneExponentOneHundred, byteArrayOf(DEFAULT_WIDTH.toByte())),
                ),
            ),
        )
        assertNull(
            parse(
                cff(
                    chars = listOf(cs(ENDCHAR)),
                    privateDictionaryPrefix = byteArrayOf(139.toByte(), 139.toByte(), NOMINAL_WIDTH.toByte()),
                ),
            ),
        )
    }

    @Test
    fun outlinesMoveAndLineOperators() {
        assertOutline(
            cs(
                10,
                20,
                RMOVETO,
                30,
                40,
                RLINETO,
                50,
                60,
                RLINETO,
                ENDCHAR,
            ),
            FontBounds(10, 20, 90, 120),
            "M 10.0 20.0",
            "L 40.0 60.0",
            "L 90.0 120.0",
            "Z",
        )
        assertOutline(
            cs(10, HMOVETO, 20, 30, 40, HLINETO, ENDCHAR),
            FontBounds(10, 0, 70, 30),
            "M 10.0 0.0",
            "L 30.0 0.0",
            "L 30.0 30.0",
            "L 70.0 30.0",
            "Z",
        )
        assertOutline(
            cs(10, VMOVETO, 20, 30, 40, VLINETO, ENDCHAR),
            FontBounds(0, 10, 30, 70),
            "M 0.0 10.0",
            "L 0.0 30.0",
            "L 30.0 30.0",
            "L 30.0 70.0",
            "Z",
        )
    }

    @Test
    fun closesBeforeEachAdditionalMove() {
        assertOutline(
            cs(10, HMOVETO, 20, VMOVETO, 30, 40, RMOVETO, ENDCHAR),
            FontBounds(10, 0, 40, 60),
            "M 10.0 0.0",
            "Z",
            "M 10.0 20.0",
            "Z",
            "M 40.0 60.0",
            "Z",
        )
    }

    @Test
    fun outlinesGeneralCurveCurveLineAndLineCurveOperators() {
        assertOutline(
            cs(
                10,
                20,
                RMOVETO,
                1,
                2,
                3,
                4,
                5,
                6,
                RRCURVETO,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                RCURVELINE,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                RLINECURVE,
                ENDCHAR,
            ),
            FontBounds(10, 20, 51, 72),
            "M 10.0 20.0",
            "C 11.0 22.0 14.0 26.0 19.0 32.0",
            "C 20.0 34.0 23.0 38.0 28.0 44.0",
            "L 35.0 52.0",
            "L 36.0 54.0",
            "C 39.0 58.0 44.0 64.0 51.0 72.0",
            "Z",
        )
    }

    @Test
    fun outlinesHorizontalAndVerticalCurveOperators() {
        assertOutline(
            cs(10, 20, RMOVETO, 5, 1, 2, 3, 4, HHCURVETO, ENDCHAR),
            FontBounds(10, 20, 17, 28),
            "M 10.0 20.0",
            "C 11.0 25.0 13.0 28.0 17.0 28.0",
            "Z",
        )
        assertOutline(
            cs(10, 20, RMOVETO, 5, 1, 2, 3, 4, VVCURVETO, ENDCHAR),
            FontBounds(10, 20, 17, 28),
            "M 10.0 20.0",
            "C 15.0 21.0 17.0 24.0 17.0 28.0",
            "Z",
        )
    }

    @Test
    fun outlinesAlternatingCurveOperatorsAndFinalCoordinate() {
        assertOutline(
            cs(0, HMOVETO, 1, 2, 3, 4, 5, 6, 7, 8, 9, HVCURVETO, ENDCHAR),
            FontBounds(0, 0, 17, 28),
            "M 0.0 0.0",
            "C 1.0 0.0 3.0 3.0 3.0 7.0",
            "C 3.0 12.0 9.0 19.0 17.0 28.0",
            "Z",
        )
        assertOutline(
            cs(0, HMOVETO, 1, 2, 3, 4, 5, 6, 7, 8, 9, VHCURVETO, ENDCHAR),
            FontBounds(0, 0, 26, 19),
            "M 0.0 0.0",
            "C 0.0 1.0 2.0 4.0 6.0 4.0",
            "C 11.0 4.0 17.0 11.0 26.0 19.0",
            "Z",
        )
    }

    @Test
    fun outlinesEveryFlexOperator() {
        assertOutline(
            cs(0, HMOVETO, 1, 2, 3, 4, 5, 6, 7, ESCAPE, HFLEX, ENDCHAR),
            FontBounds(0, 0, 25, 3),
            "M 0.0 0.0",
            "C 1.0 0.0 3.0 3.0 7.0 3.0",
            "C 12.0 3.0 18.0 0.0 25.0 0.0",
            "Z",
        )
        assertOutline(
            cs(0, HMOVETO, (1..13).toList().toIntArray(), ESCAPE, FLEX, ENDCHAR),
            FontBounds(0, 0, 36, 42),
            "M 0.0 0.0",
            "C 1.0 2.0 4.0 6.0 9.0 12.0",
            "C 16.0 20.0 25.0 30.0 36.0 42.0",
            "Z",
        )
        assertOutline(
            cs(0, HMOVETO, (1..9).toList().toIntArray(), ESCAPE, HFLEX1, ENDCHAR),
            FontBounds(0, 0, 31, 14),
            "M 0.0 0.0",
            "C 1.0 2.0 4.0 6.0 9.0 6.0",
            "C 15.0 6.0 22.0 14.0 31.0 0.0",
            "Z",
        )
        assertOutline(
            cs(0, HMOVETO, (1..11).toList().toIntArray(), ESCAPE, FLEX1, ENDCHAR),
            FontBounds(0, 0, 25, 41),
            "M 0.0 0.0",
            "C 1.0 2.0 4.0 6.0 9.0 12.0",
            "C 16.0 20.0 25.0 30.0 0.0 41.0",
            "Z",
        )
    }

    @Test
    fun skipsAllStemAndMaskOperators() {
        val charString =
            cs(
                10,
                20,
                HSTEM,
                30,
                40,
                VSTEM,
                50,
                60,
                HSTEMHM,
                70,
                80,
                VSTEMHM,
                HINTMASK,
                raw(0xAA),
                CNTRMASK,
                raw(0x55),
                10,
                20,
                RMOVETO,
                ENDCHAR,
            )
        assertOutline(charString, FontBounds(10, 20, 10, 20), "M 10.0 20.0", "Z")
    }

    @Test
    fun parsesWidthsFromMovesStemsMasksAndEndChar() {
        val table =
            assertNotNull(
                parse(
                    cff(
                        chars =
                            listOf(
                                cs(20, 10, HMOVETO, ENDCHAR),
                                cs(10, HMOVETO, ENDCHAR),
                                cs(30, 10, 20, HSTEM, 10, HMOVETO, ENDCHAR),
                                cs(40, 10, 20, HINTMASK, raw(0), 10, HMOVETO, ENDCHAR),
                                cs(50, ENDCHAR),
                            ),
                        defaultWidth = 500,
                        nominalWidth = 100,
                    ),
                ),
            )

        assertEquals(120, table.glyphWidth(0))
        assertEquals(500, table.glyphWidth(1))
        assertEquals(130, table.glyphWidth(2))
        assertEquals(140, table.glyphWidth(3))
        assertEquals(150, table.glyphWidth(4))
        assertNull(table.glyphWidth(5))
    }

    @Test
    fun composesDeprecatedSeacUsingStandardEncoding() {
        val base = cs(10, 20, RMOVETO, 30, 0, RLINETO, ENDCHAR)
        val accent = cs(0, 0, RMOVETO, 0, 10, RLINETO, ENDCHAR)
        val composite = cs(50, 100, 200, 65, 66, ENDCHAR)
        val table =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(ENDCHAR), base, accent, composite),
                        charset = byteArrayOf(0, 0, 34, 0, 35, 0, 36),
                        encoding = byteArrayOf(0, 2, 66, 65),
                        nominalWidth = 100,
                    ),
                ),
            )
        val sink = RecordingSink()

        assertEquals(FontBounds(10, 20, 100, 210), table.outline(3, sink))
        assertEquals(
            listOf(
                "M 10.0 20.0",
                "L 40.0 20.0",
                "Z",
                "M 100.0 200.0",
                "L 100.0 210.0",
                "Z",
            ),
            sink.commands,
        )
        assertEquals(150, table.glyphWidth(3))
    }

    @Test
    fun executesLocalAndGlobalSubroutinesWithReturn() {
        val table =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(10, HMOVETO, -107, CALLSUBR, -107, CALLGSUBR, ENDCHAR)),
                        localSubrs = listOf(cs(20, 30, RLINETO, RETURN)),
                        globalSubrs = listOf(cs(40, 50, RLINETO, RETURN)),
                    ),
                ),
            )
        val sink = RecordingSink()

        assertEquals(FontBounds(10, 0, 70, 80), table.outline(0, sink))
        assertEquals(listOf("M 10.0 0.0", "L 30.0 30.0", "L 70.0 80.0", "Z"), sink.commands)
    }

    @Test
    fun appliesEverySubroutineBiasRange() {
        for ((count, bias) in listOf(1 to 107, 1240 to 1131, 33900 to 32768)) {
            val subroutines = List(count) { index -> if (index == 0) cs(1, 2, RLINETO, RETURN) else byteArrayOf() }
            val table =
                assertNotNull(
                    parse(
                        cff(
                            chars = listOf(cs(0, HMOVETO, -bias, CALLGSUBR, ENDCHAR)),
                            globalSubrs = subroutines,
                        ),
                    ),
                )

            assertEquals(FontBounds(0, 0, 1, 2), table.outline(0, RecordingSink()), "count=$count")
        }
    }

    @Test
    fun propagatesEndCharFromSubroutineAndRejectsCallerData() {
        val subroutine = cs(20, 30, RLINETO, ENDCHAR)
        val valid =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(10, HMOVETO, -107, CALLSUBR)),
                        localSubrs = listOf(subroutine),
                    ),
                ),
            )
        val invalid =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(10, HMOVETO, -107, CALLSUBR, 1, 2, RLINETO)),
                        localSubrs = listOf(subroutine),
                    ),
                ),
            )

        assertEquals(FontBounds(10, 0, 30, 30), valid.outline(0, RecordingSink()))
        assertNull(invalid.outline(0, RecordingSink()))
    }

    @Test
    fun boundsLocalGlobalAndMixedRecursion() {
        val localRecursive =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(0, HMOVETO, -107, CALLSUBR)),
                        localSubrs = listOf(cs(-107, CALLSUBR)),
                    ),
                ),
            )
        val globalRecursive =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(0, HMOVETO, -107, CALLGSUBR)),
                        globalSubrs = listOf(cs(-107, CALLGSUBR)),
                    ),
                ),
            )
        val mixedRecursive =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(0, HMOVETO, -107, CALLSUBR)),
                        localSubrs = listOf(cs(-107, CALLGSUBR)),
                        globalSubrs = listOf(cs(-107, CALLSUBR)),
                    ),
                ),
            )

        assertNull(localRecursive.outline(0, RecordingSink()))
        assertNull(globalRecursive.outline(0, RecordingSink()))
        assertNull(mixedRecursive.outline(0, RecordingSink()))
    }

    @Test
    fun boundsBranchingSubroutineExpansionWithSharedOperationBudget() {
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
                parse(
                    cff(
                        chars = listOf(cs(0, HMOVETO, -107, CALLSUBR, ENDCHAR)),
                        localSubrs = subroutines,
                    ),
                ),
            )

        assertNull(table.outline(0, RecordingSink()))
    }

    @Test
    fun supportsShortAndFixedType2Numbers() {
        val charString =
            bytes(
                type2Short(300),
                byteArrayOf(HMOVETO.value.toByte()),
                type2Fixed(1.5f),
                byteArrayOf(HLINETO.value.toByte(), ENDCHAR.value.toByte()),
            )
        assertOutline(
            charString,
            FontBounds(300, 0, 301, 0),
            "M 300.0 0.0",
            "L 301.5 0.0",
            "Z",
        )
    }

    @Test
    fun appliesFontMatrixAndUnitsPerEmToCallbacksAndBounds() {
        val table =
            assertNotNull(
                parse(
                    cff(
                        chars = listOf(cs(10, 20, RMOVETO, 5, 10, RLINETO, ENDCHAR)),
                        matrix = intArrayOf(2, 0, 0, 3, 10, -20),
                    ),
                    unitsPerEm = 2,
                ),
            )
        val sink = RecordingSink()

        assertEquals(FontBounds(60, 80, 80, 140), table.outline(0, sink))
        assertEquals(listOf("M 60.0 80.0", "L 80.0 140.0", "Z"), sink.commands)

        val scaledDefault = assertNotNull(parse(cff(chars = listOf(cs(10, HMOVETO, ENDCHAR))), unitsPerEm = 2000))
        assertEquals(FontBounds(20, 0, 20, 0), scaledDefault.outline(0, RecordingSink()))
    }

    @Test
    fun rejectsMalformedOperatorsArgumentsTerminationSubroutinesStackAndBounds() {
        val malformed =
            listOf(
                cs(10, raw(2), ENDCHAR),
                cs(10, 20, RLINETO, ENDCHAR),
                cs(1, RMOVETO, ENDCHAR),
                cs(1, 2, 3, 4, RMOVETO, ENDCHAR),
                cs(HMOVETO, ENDCHAR),
                cs(VMOVETO, ENDCHAR),
                cs(0, HMOVETO, 1, RLINETO, ENDCHAR),
                cs(0, HMOVETO, HLINETO, ENDCHAR),
                cs(0, HMOVETO, VLINETO, ENDCHAR),
                cs(0, HMOVETO, 1, 2, 3, 4, RRCURVETO, ENDCHAR),
                cs(0, HMOVETO, 1, 2, 3, RCURVELINE, ENDCHAR),
                cs(0, HMOVETO, 1, 2, 3, RLINECURVE, ENDCHAR),
                cs(0, HMOVETO, 1, 2, 3, HHCURVETO, ENDCHAR),
                cs(0, HMOVETO, 1, 2, 3, VVCURVETO, ENDCHAR),
                cs(0, HMOVETO, 1, 2, 3, HVCURVETO, ENDCHAR),
                cs(0, HMOVETO, 1, 2, 3, VHCURVETO, ENDCHAR),
                cs(0, HMOVETO, 1, 2, ESCAPE, HFLEX, ENDCHAR),
                cs(0, HMOVETO, IntArray(12) { 1 }, ESCAPE, FLEX, ENDCHAR),
                cs(0, HMOVETO, IntArray(8) { 1 }, ESCAPE, HFLEX1, ENDCHAR),
                cs(0, HMOVETO, IntArray(10) { 1 }, ESCAPE, FLEX1, ENDCHAR),
                cs(0, HMOVETO, ESCAPE, 0, ENDCHAR),
                cs(10, 20, HSTEM, HINTMASK),
                byteArrayOf(28, 0),
                byteArrayOf(255.toByte(), 0, 0, 0),
                cs(0, HMOVETO),
                cs(0, HMOVETO, ENDCHAR, ENDCHAR),
                cs(RETURN),
                cs(0, HMOVETO, CALLSUBR, ENDCHAR),
                cs(0, HMOVETO, -107, CALLSUBR, ENDCHAR),
                cs(0, 0, 0, 0, ENDCHAR),
                cs(32767, HMOVETO, 32767, HLINETO, ENDCHAR),
                cs(IntArray(49), ENDCHAR),
            )

        malformed.forEachIndexed { index, charString ->
            val table = assertNotNull(parse(cff(chars = listOf(charString))), "case $index")
            assertNull(table.outline(0, RecordingSink()), "case $index")
        }
    }

    @Test
    fun operationStateIsIsolatedAndSinkFailuresPropagate() {
        val table = assertNotNull(parse(cff(chars = listOf(cs(10, HMOVETO, 20, 30, RLINETO, ENDCHAR)))))
        val first = RecordingSink()
        val second = RecordingSink()

        assertNotNull(table.outline(0, first))
        assertNotNull(table.outline(0, second))
        assertEquals(first.commands, second.commands)
        assertFailsWith<ExpectedSinkFailure> {
            table.outline(
                0,
                object : OutlineSink by RecordingSink() {
                    override fun lineTo(
                        x: Float,
                        y: Float,
                    ): Unit = throw ExpectedSinkFailure()
                },
            )
        }
        assertNotNull(table.outline(0, RecordingSink()))
    }

    private fun assertOutline(
        charString: ByteArray,
        bounds: FontBounds,
        vararg commands: String,
    ) {
        val table = assertNotNull(parse(cff(chars = listOf(charString))))
        val sink = RecordingSink()
        assertEquals(bounds, table.outline(0, sink))
        assertEquals(commands.toList(), sink.commands)
    }

    private class RecordingSink : OutlineSink {
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
        ) {
            commands +=
                "Q ${controlX.testCoordinate()} ${controlY.testCoordinate()} " +
                "${x.testCoordinate()} ${y.testCoordinate()}"
        }

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

    private class ExpectedSinkFailure : RuntimeException()
}

private fun parse(
    data: ByteArray,
    unitsPerEm: Int? = null,
): Cff1Table? = Cff1Table.parse(ByteSlice.of(data), unitsPerEm)

private fun cff(
    chars: List<ByteArray>,
    strings: List<ByteArray> = emptyList(),
    globalSubrs: List<ByteArray> = emptyList(),
    localSubrs: List<ByteArray> = emptyList(),
    defaultWidth: Int? = null,
    nominalWidth: Int? = null,
    matrix: IntArray? = null,
    charset: ByteArray? = null,
    encoding: ByteArray? = null,
    cid: Boolean = false,
    topDictionarySuffix: ByteArray = byteArrayOf(),
    privateDictionaryPrefix: ByteArray = byteArrayOf(),
): ByteArray {
    val stringIndex = cffIndex(strings)
    val globalIndex = cffIndex(globalSubrs)
    val charStringsIndex = cffIndex(chars)
    val privateDictionary = privateDictionaryPrefix.toMutableList()
    defaultWidth?.let {
        privateDictionary += dictInt(it).toList()
        privateDictionary += DEFAULT_WIDTH.toByte()
    }
    nominalWidth?.let {
        privateDictionary += dictInt(it).toList()
        privateDictionary += NOMINAL_WIDTH.toByte()
    }
    if (localSubrs.isNotEmpty()) {
        privateDictionary += dictInt(privateDictionary.size + 6).toList()
        privateDictionary += LOCAL_SUBRS.toByte()
    }
    val privateBytes = privateDictionary.toByteArray()
    val localSubroutinesIndex = cffIndex(localSubrs).takeIf { localSubrs.isNotEmpty() } ?: byteArrayOf()

    fun topDictionary(
        charStringsOffset: Int,
        privateOffset: Int,
        charsetOffset: Int,
        encodingOffset: Int,
    ): ByteArray {
        val bytes = mutableListOf<Byte>()
        matrix?.forEach { bytes += dictInt(it).toList() }
        if (matrix != null) bytes += listOf(ESCAPE.value.toByte(), FONT_MATRIX.toByte())
        if (cid) bytes += listOf(ESCAPE.value.toByte(), ROS.toByte())
        if (charset != null) {
            bytes += dictInt(charsetOffset).toList()
            bytes += CHARSET.toByte()
        }
        if (encoding != null) {
            bytes += dictInt(encodingOffset).toList()
            bytes += ENCODING.toByte()
        }
        bytes += dictInt(charStringsOffset).toList()
        bytes += CHAR_STRINGS.toByte()
        if (privateBytes.isNotEmpty()) {
            bytes += dictInt(privateBytes.size).toList()
            bytes += dictInt(privateOffset).toList()
            bytes += PRIVATE_DICTIONARY.toByte()
        }
        bytes += topDictionarySuffix.toList()
        return bytes.toByteArray()
    }

    val placeholderTop = topDictionary(0, 0, 0, 0)
    val topIndexSize = cffIndex(listOf(placeholderTop)).size
    val charStringsOffset = 4 + 2 + topIndexSize + stringIndex.size + globalIndex.size
    val privateOffset = charStringsOffset + charStringsIndex.size
    val charsetOffset = privateOffset + privateBytes.size + localSubroutinesIndex.size
    val encodingOffset = charsetOffset + (charset?.size ?: 0)
    val topIndex = cffIndex(listOf(topDictionary(charStringsOffset, privateOffset, charsetOffset, encodingOffset)))
    assertEquals(topIndexSize, topIndex.size)

    return bytes(
        byteArrayOf(1, 0, 4, 0),
        byteArrayOf(0, 0),
        topIndex,
        stringIndex,
        globalIndex,
        charStringsIndex,
        privateBytes,
        localSubroutinesIndex,
        charset ?: byteArrayOf(),
        encoding ?: byteArrayOf(),
    )
}

private fun cffIndex(objects: List<ByteArray>): ByteArray {
    if (objects.isEmpty()) return byteArrayOf(0, 0)
    val bytes = mutableListOf<Byte>()
    appendUInt16(bytes, objects.size)
    bytes += 4
    var offset = 1L
    appendUInt32(bytes, offset)
    for (data in objects) {
        offset += data.size
        appendUInt32(bytes, offset)
    }
    objects.forEach { bytes += it.toList() }
    return bytes.toByteArray()
}

private fun cs(vararg values: Any): ByteArray {
    val bytes = mutableListOf<Byte>()
    for (value in values) {
        when (value) {
            is Int -> bytes += type2Int(value).toList()
            is RawByte -> bytes += value.value.toByte()
            is IntArray -> value.forEach { bytes += type2Int(it).toList() }
            else -> error("unsupported charstring fixture value: $value")
        }
    }
    return bytes.toByteArray()
}

private fun raw(value: Int): RawByte = RawByte(value)

private data class RawByte(
    val value: Int,
)

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

        in Short.MIN_VALUE..Short.MAX_VALUE -> {
            type2Short(value)
        }

        else -> {
            error("test Type 2 integer out of range: $value")
        }
    }

private fun type2Short(value: Int): ByteArray = byteArrayOf(28, (value shr 8).toByte(), value.toByte())

private fun type2Fixed(value: Float): ByteArray {
    val fixed = (value * 65536f).toInt()
    return byteArrayOf(
        255.toByte(),
        (fixed shr 24).toByte(),
        (fixed shr 16).toByte(),
        (fixed shr 8).toByte(),
        fixed.toByte(),
    )
}

private fun dictInt(value: Int): ByteArray =
    byteArrayOf(29, (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

private fun appendUInt16(
    bytes: MutableList<Byte>,
    value: Int,
) {
    bytes += (value shr 8).toByte()
    bytes += value.toByte()
}

private fun appendUInt32(
    bytes: MutableList<Byte>,
    value: Long,
) {
    bytes += (value shr 24).toByte()
    bytes += (value shr 16).toByte()
    bytes += (value shr 8).toByte()
    bytes += value.toByte()
}

private fun bytes(vararg values: ByteArray): ByteArray {
    val result = ByteArray(values.sumOf { it.size })
    var offset = 0
    for (value in values) {
        value.copyInto(result, offset)
        offset += value.size
    }
    return result
}

private val HSTEM = raw(1)
private val VSTEM = raw(3)
private val VMOVETO = raw(4)
private val RLINETO = raw(5)
private val HLINETO = raw(6)
private val VLINETO = raw(7)
private val RRCURVETO = raw(8)
private val CALLSUBR = raw(10)
private val RETURN = raw(11)
private val ESCAPE = raw(12)
private val ENDCHAR = raw(14)
private val HSTEMHM = raw(18)
private val HINTMASK = raw(19)
private val CNTRMASK = raw(20)
private val RMOVETO = raw(21)
private val HMOVETO = raw(22)
private val VSTEMHM = raw(23)
private val RCURVELINE = raw(24)
private val RLINECURVE = raw(25)
private val VVCURVETO = raw(26)
private val HHCURVETO = raw(27)
private val CALLGSUBR = raw(29)
private val VHCURVETO = raw(30)
private val HVCURVETO = raw(31)
private val HFLEX = raw(34)
private val FLEX = raw(35)
private val HFLEX1 = raw(36)
private val FLEX1 = raw(37)
private const val CHAR_STRINGS = 17
private const val CHARSET = 15
private const val ENCODING = 16
private const val PRIVATE_DICTIONARY = 18
private const val LOCAL_SUBRS = 19
private const val DEFAULT_WIDTH = 20
private const val NOMINAL_WIDTH = 21
private const val FONT_MATRIX = 7
private const val ROS = 30
