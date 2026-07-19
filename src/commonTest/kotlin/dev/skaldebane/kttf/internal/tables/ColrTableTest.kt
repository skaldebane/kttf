package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColrTableTest {
    private val foreground = ColorRgba(1, 2, 3, 200)

    @Test
    fun versionZeroMatchesPinnedLayerCommandsAndPaletteBounds() {
        val colr =
            Bytes()
                .u16(0)
                .u16(1)
                .u32(14)
                .u32(20)
                .u16(2)
                .u16(7)
                .u16(0)
                .u16(2)
                .u16(10)
                .u16(1)
                .u16(11)
                .u16(0xFFFF)
                .build()
        val table = requireNotNull(ColrTable.parse(palette(), ByteSlice.of(colr)))
        val painter = RecordingPainter()

        assertTrue(table.isSimple)
        assertTrue(table.contains(7))
        assertFalse(table.contains(8))
        assertTrue(table.paint(7, 0, foreground, painter))
        assertEquals(
            listOf(
                Command.Outline(10),
                Command.PushClip,
                Command.Fill(ColrPaint.Solid(ColorRgba(70, 60, 50, 80))),
                Command.PopClip,
                Command.Outline(11),
                Command.PushClip,
                Command.Fill(ColrPaint.Solid(foreground)),
                Command.PopClip,
            ),
            painter.commands,
        )
        assertFalse(table.paint(7, 1, foreground, RecordingPainter()))
    }

    @Test
    fun solidAndVariableSolidResolveForegroundAlphaAndInjectedDeltas() {
        val static =
            parseV1(
                v1(
                    listOf(
                        7 to
                            Bytes()
                                .u8(2)
                                .u16(0xFFFF)
                                .f2(0.5f)
                                .build(),
                    ),
                ),
            )
        val staticPainter = RecordingPainter()
        assertTrue(static.paint(7, 0, foreground, staticPainter))
        assertEquals(
            ColorRgba(1, 2, 3, 100),
            assertIs<ColrPaint.Solid>((staticPainter.commands.single() as Command.Fill).paint).color,
        )

        val calls = mutableListOf<Long>()
        val variable =
            parseV1(
                v1(
                    listOf(
                        7 to
                            Bytes()
                                .u8(3)
                                .u16(0)
                                .f2(0.5f)
                                .u32(9)
                                .build(),
                    ),
                ),
            )
        val variablePainter = RecordingPainter()
        variable.paint(
            7,
            0,
            foreground,
            variablePainter,
            floatArrayOf(0.25f),
            ColrDeltaProvider { index, _ ->
                calls += index
                4096f
            },
        )
        val color = assertIs<ColrPaint.Solid>((variablePainter.commands.single() as Command.Fill).paint).color
        assertEquals(30, color.alpha)
        assertEquals(listOf(9L), calls)
    }

    @Test
    fun embeddedMapAndStoreResolveVariableSolidAndAllowOverrides() {
        val variation = embeddedVariation(9 to 4096)
        val table =
            parseV1(
                v1(
                    listOf(
                        7 to
                            Bytes()
                                .u8(3)
                                .u16(0)
                                .f2(0.5f)
                                .u32(9)
                                .build(),
                    ),
                    varIndexMap = variation.map,
                    variationStore = variation.store,
                ),
            )

        assertEquals(30, solidColor(table, floatArrayOf(1f)).alpha)
        assertEquals(20, solidColor(table, floatArrayOf(0f)).alpha)
        assertEquals(
            20,
            solidColor(table, floatArrayOf(1f), ColrDeltaProvider { _, _ -> 0f }).alpha,
        )
        assertEquals(
            30,
            solidColor(table, floatArrayOf(1f), ColrDeltaProvider { _, _ -> null }).alpha,
        )
    }

    @Test
    fun embeddedVariationsResolveEveryGradientConsumer() {
        val variation =
            embeddedVariation(
                100 to 10,
                101 to 20,
                102 to 30,
                103 to 40,
                104 to 50,
                105 to 60,
                200 to 4096,
                201 to 8192,
            )
        for (format in listOf(5, 7, 9)) {
            val table =
                parseV1(
                    v1(
                        listOf(7 to gradient(format, variable = true)),
                        varIndexMap = variation.map,
                        variationStore = variation.store,
                    ),
                )
            val painter = RecordingPainter()

            assertTrue(table.paint(7, 0, foreground, painter, floatArrayOf(1f)))
            when (val paint = (painter.commands.single() as Command.Fill).paint) {
                is ColrPaint.LinearGradient -> {
                    assertEquals(listOf(10f, 21f, 32f, 43f, 54f, 65f), paint.geometry())
                    assertVariedStop(paint.stops.single())
                }

                is ColrPaint.RadialGradient -> {
                    assertEquals(listOf(11f, 22f, 33f, 44f, 55f, 66f), paint.geometry())
                    assertVariedStop(paint.stops.single())
                }

                is ColrPaint.SweepGradient -> {
                    assertEquals(11f, paint.centerX)
                    assertEquals(22f, paint.centerY)
                    assertEquals(-0.5f + 30f / 16384f, paint.startAngle)
                    assertEquals(0.5f + 40f / 16384f, paint.endAngle)
                    assertVariedStop(paint.stops.single())
                }

                else -> {
                    error("unexpected paint format $format")
                }
            }
        }
    }

    @Test
    fun embeddedVariationsResolveEveryVariableTransformConsumer() {
        val variation =
            embeddedVariation(
                300 to 1024,
                301 to 2048,
                302 to 3072,
                303 to 4096,
                304 to 5120,
                305 to 6144,
            )
        for (format in 13..31 step 2) {
            val bytes =
                v1(
                    listOf(7 to transform(format)),
                    varIndexMap = variation.map,
                    variationStore = variation.store,
                )
            val table = parseV1(bytes)
            val defaults = RecordingPainter()
            val varied = RecordingPainter()

            assertTrue(table.paint(7, 0, foreground, defaults))
            assertTrue(table.paint(7, 0, foreground, varied, floatArrayOf(1f)))
            assertNotEquals(
                defaults.commands.filterIsInstance<Command.Transform>(),
                varied.commands.filterIsInstance<Command.Transform>(),
                "format $format",
            )
        }
    }

    @Test
    fun everyStaticAndVariableGradientFormatEmitsResolvedStops() {
        val formats = listOf(4, 5, 6, 7, 8, 9)
        for (format in formats) {
            val variable = format % 2 == 1
            val root = gradient(format, variable)
            val table = parseV1(v1(listOf(7 to root)))
            val painter = RecordingPainter()
            val indices = mutableListOf<Long>()
            assertTrue(
                table.paint(
                    7,
                    0,
                    foreground,
                    painter,
                    floatArrayOf(1f),
                    ColrDeltaProvider { index, _ ->
                        indices += index
                        0f
                    },
                ),
            )
            val paint = (painter.commands.single() as Command.Fill).paint
            val stops =
                when (paint) {
                    is ColrPaint.LinearGradient -> paint.stops
                    is ColrPaint.RadialGradient -> paint.stops
                    is ColrPaint.SweepGradient -> paint.stops
                    else -> error("unexpected paint")
                }
            assertEquals(ColrGradientStop(0.25f, ColorRgba(30, 20, 10, 10)), stops.single())
            if (variable) assertTrue(indices.isNotEmpty()) else assertTrue(indices.isEmpty())
        }
    }

    @Test
    fun layersGlyphReferencesAndAllTransformFormatsUseStableCommands() {
        val layerRoot =
            Bytes()
                .u8(1)
                .u8(1)
                .u32(0)
                .build()
        val layerList =
            Bytes()
                .u32(1)
                .u32(8)
                .raw(solid())
                .build()
        val layerPainter = RecordingPainter()
        parseV1(v1(listOf(7 to layerRoot), layerList = layerList)).paint(7, 0, foreground, layerPainter)
        assertIs<Command.Fill>(layerPainter.commands.single())

        val glyphRoot =
            Bytes()
                .u8(10)
                .u24(6)
                .u16(42)
                .raw(solid())
                .build()
        val glyphPainter = RecordingPainter()
        parseV1(v1(listOf(7 to glyphRoot))).paint(7, 0, foreground, glyphPainter)
        assertEquals(Command.Outline(42), glyphPainter.commands.first())
        assertEquals(Command.PopClip, glyphPainter.commands.last())

        val colrGlyph = v1(listOf(7 to Bytes().u8(11).u16(8).build(), 8 to solid()))
        val colrGlyphPainter = RecordingPainter()
        parseV1(colrGlyph).paint(7, 0, foreground, colrGlyphPainter)
        assertIs<Command.Fill>(colrGlyphPainter.commands.single())

        val variableIndices = mutableListOf<Long>()
        for (format in 12..31) {
            val painter = RecordingPainter()
            val root = transform(format)
            assertTrue(
                parseV1(v1(listOf(7 to root))).paint(
                    7,
                    0,
                    foreground,
                    painter,
                    floatArrayOf(1f),
                    ColrDeltaProvider { index, _ ->
                        variableIndices += index
                        0f
                    },
                ),
                "format $format",
            )
            assertTrue(painter.commands.any { it is Command.Transform }, "format $format")
            assertTrue(painter.commands.any { it is Command.Fill }, "format $format")
            assertEquals(
                painter.commands.count { it is Command.Transform },
                painter.commands.count { it == Command.PopTransform },
                "format $format",
            )
        }
        assertTrue(variableIndices.isNotEmpty())
    }

    @Test
    fun compositeSupportsEveryModeAndBalancesLayers() {
        for (mode in ColrCompositeMode.entries.indices) {
            val root =
                Bytes()
                    .u8(32)
                    .u24(8)
                    .u8(mode)
                    .u24(13)
                    .raw(solid(0))
                    .raw(solid(1))
                    .build()
            val painter = RecordingPainter()
            parseV1(v1(listOf(7 to root))).paint(7, 0, foreground, painter)
            assertEquals(Command.Layer(ColrCompositeMode.SOURCE_OVER), painter.commands[0])
            assertEquals(Command.Layer(ColrCompositeMode.entries[mode]), painter.commands[2])
            assertEquals(2, painter.commands.count { it == Command.PopLayer })
        }
    }

    @Test
    fun staticAndVariableClipsUseFirstMatchingRecordAndInjectedDeltas() {
        val staticClip = clipList(format = 1)
        val staticPainter = RecordingPainter()
        val static = parseV1(v1(listOf(7 to solid()), clipList = staticClip))
        static.paint(7, 0, foreground, staticPainter)
        assertEquals(Command.ClipBox(ColrClipBox(1f, 2f, 30f, 40f)), staticPainter.commands.first())
        assertEquals(Command.PopClip, staticPainter.commands.last())

        val variable = parseV1(v1(listOf(7 to solid()), clipList = clipList(format = 2)))
        assertEquals(
            ColrClipBox(2f, 3f, 31f, 41f),
            variable.clipBox(7, floatArrayOf(1f), ColrDeltaProvider { _, _ -> 1f }),
        )
        assertNull(variable.clipBox(9))
    }

    @Test
    fun embeddedVariationsResolveVariableClipBoxes() {
        val variation =
            embeddedVariation(
                500 to 1,
                501 to 2,
                502 to 3,
                503 to 4,
            )
        val table =
            parseV1(
                v1(
                    listOf(7 to solid()),
                    clipList = clipList(format = 2),
                    varIndexMap = variation.map,
                    variationStore = variation.store,
                ),
            )

        assertEquals(ColrClipBox(2f, 4f, 33f, 44f), table.clipBox(7, floatArrayOf(1f)))
    }

    @Test
    fun embeddedVariationOffsetsAreBoundedAndBothTablesAreRequired() {
        val variation = embeddedVariation(9 to 4096)
        val paint =
            Bytes()
                .u8(3)
                .u16(0)
                .f2(0.5f)
                .u32(9)
                .build()
        val valid =
            v1(
                listOf(7 to paint),
                varIndexMap = variation.map,
                variationStore = variation.store,
            )
        assertNotNull(ColrTable.parse(palette(), ByteSlice.of(valid)))

        val badMapOffset = valid.copyOf().also { writeU32(it, 26, 0xFFFF_FFFFL) }
        val badStoreOffset = valid.copyOf().also { writeU32(it, 30, 0xFFFF_FFFFL) }
        assertNull(ColrTable.parse(palette(), ByteSlice.of(badMapOffset)))
        assertNull(ColrTable.parse(palette(), ByteSlice.of(badStoreOffset)))

        val malformedMap = valid.copyOf().also { writeU32(it, 26, (it.size - 1).toLong()) }
        val parsedMalformedMap = assertNotNull(ColrTable.parse(palette(), ByteSlice.of(malformedMap)))
        assertEquals(20, solidColor(parsedMalformedMap, floatArrayOf(1f)).alpha)

        val onlyMap = parseV1(v1(listOf(7 to paint), varIndexMap = variation.map))
        val onlyStore = parseV1(v1(listOf(7 to paint), variationStore = variation.store))
        assertEquals(20, solidColor(onlyMap, floatArrayOf(1f)).alpha)
        assertEquals(20, solidColor(onlyStore, floatArrayOf(1f)).alpha)
    }

    @Test
    fun cyclesDepthAndOperationWorkAreBounded() {
        val chain = Bytes()
        repeat(70) {
            chain
                .u8(14)
                .u24(8)
                .i16(0)
                .i16(0)
        }
        chain.raw(solid())
        val painter = CountingPainter()
        assertTrue(parseV1(v1(listOf(7 to chain.build()))).paint(7, 0, foreground, painter))
        assertEquals(64, painter.transforms)
        assertEquals(64, painter.poppedTransforms)
        assertEquals(0, painter.paints)

        val offsets = Bytes().u32(765)
        repeat(255) { offsets.u32((4 + 765 * 4).toLong()) }
        repeat(255) { offsets.u32((10 + 765 * 4).toLong()) }
        repeat(255) { offsets.u32((16 + 765 * 4).toLong()) }
        offsets.u8(1).u8(255).u32(255)
        offsets.u8(1).u8(255).u32(510)
        offsets.raw(solid())
        val bounded = CountingPainter()
        parseV1(
            v1(
                listOf(
                    7 to
                        Bytes()
                            .u8(1)
                            .u8(255)
                            .u32(0)
                            .build(),
                ),
                layerList = offsets.build(),
            ),
        ).paint(7, 0, foreground, bounded)
        assertTrue(bounded.paints < 100_000)
        assertTrue(bounded.paints > 90_000)
    }

    @Test
    fun rejectsTableBoundsButIgnoresUnknownAndMalformedChildPaintsLikeUpstream() {
        assertNull(ColrTable.parse(palette(), ByteSlice.of(ByteArray(13))))
        val unsupported = v1(listOf(7 to byteArrayOf(99)))
        val painter = RecordingPainter()
        assertTrue(parseV1(unsupported).paint(7, 0, foreground, painter))
        assertTrue(painter.commands.isEmpty())

        val malformedChild =
            Bytes()
                .u8(10)
                .u24(6)
                .u16(5)
                .u8(2)
                .build()
        val malformedPainter = RecordingPainter()
        assertTrue(parseV1(v1(listOf(7 to malformedChild))).paint(7, 0, foreground, malformedPainter))
        assertEquals(listOf(Command.Outline(5), Command.PushClip, Command.PopClip), malformedPainter.commands)
    }

    private fun gradient(
        format: Int,
        variable: Boolean,
    ): ByteArray {
        val root = Bytes().u8(format)
        val rootSize =
            when (format) {
                4, 6 -> 16
                5, 7 -> 20
                8 -> 12
                else -> 16
            }
        root.u24(rootSize.toLong())
        when (format) {
            4, 5 -> {
                repeat(6) { root.i16(it) }
            }

            6, 7 -> {
                root
                    .i16(1)
                    .i16(2)
                    .u16(3)
                    .i16(4)
                    .i16(5)
                    .u16(6)
            }

            else -> {
                root
                    .i16(1)
                    .i16(2)
                    .f2(-0.5f)
                    .f2(0.5f)
            }
        }
        if (variable) root.u32(100)
        root
            .u8(1)
            .u16(1)
            .f2(0.25f)
            .u16(0)
            .f2(0.25f)
        if (variable) root.u32(200)
        return root.build()
    }

    private fun transform(format: Int): ByteArray {
        val variable = format % 2 == 1
        val root = Bytes().u8(format)
        when (format) {
            12, 13 -> {
                val rootSize = 7
                root.u24(rootSize.toLong()).u24(12).raw(solid())
                repeat(6) { index -> root.fixed(if (index == 0 || index == 3) 1f else 0f) }
                if (variable) root.u32(300)
            }

            14, 15 -> {
                val size = if (variable) 12 else 8
                root.u24(size.toLong()).i16(2).i16(3)
                if (variable) root.u32(300)
                root.raw(solid())
            }

            16, 17, 28, 29 -> {
                val size = if (variable) 12 else 8
                root.u24(size.toLong()).f2(0.5f).f2(0.75f)
                if (variable) root.u32(300)
                root.raw(solid())
            }

            18, 19, 30, 31 -> {
                val size = if (variable) 16 else 12
                root
                    .u24(size.toLong())
                    .f2(0.5f)
                    .f2(0.75f)
                    .i16(10)
                    .i16(20)
                if (variable) root.u32(300)
                root.raw(solid())
            }

            20, 21, 24, 25 -> {
                val size = if (variable) 10 else 6
                root.u24(size.toLong()).f2(0.5f)
                if (variable) root.u32(300)
                root.raw(solid())
            }

            22, 23, 26, 27 -> {
                val size = if (variable) 14 else 10
                root
                    .u24(size.toLong())
                    .f2(0.5f)
                    .i16(10)
                    .i16(20)
                if (variable) root.u32(300)
                root.raw(solid())
            }
        }
        return root.build()
    }

    private fun clipList(format: Int): ByteArray =
        Bytes()
            .u8(1)
            .u32(1)
            .u16(7)
            .u16(8)
            .u24(12)
            .u8(format)
            .i16(1)
            .i16(2)
            .i16(30)
            .i16(40)
            .apply { if (format == 2) u32(500) }
            .build()

    private fun solid(paletteEntry: Int = 0): ByteArray =
        Bytes()
            .u8(2)
            .u16(paletteEntry)
            .f2(1f)
            .build()

    private fun solidColor(
        table: ColrTable,
        coordinates: FloatArray,
        provider: ColrDeltaProvider? = null,
    ): ColorRgba {
        val painter = RecordingPainter()
        assertTrue(table.paint(7, 0, foreground, painter, coordinates, provider))
        return assertIs<ColrPaint.Solid>((painter.commands.single() as Command.Fill).paint).color
    }

    private fun assertVariedStop(stop: ColrGradientStop) {
        assertEquals(0.5f, stop.offset)
        assertEquals(ColorRgba(30, 20, 10, 30), stop.color)
    }

    private fun ColrPaint.LinearGradient.geometry(): List<Float> = listOf(x0, y0, x1, y1, x2, y2)

    private fun ColrPaint.RadialGradient.geometry(): List<Float> = listOf(x0, y0, r0, x1, y1, r1)

    private fun palette(): CpalTable {
        val data =
            Bytes()
                .u16(0)
                .u16(2)
                .u16(1)
                .u16(2)
                .u32(14)
                .u16(0)
                .u8(10)
                .u8(20)
                .u8(30)
                .u8(40)
                .u8(50)
                .u8(60)
                .u8(70)
                .u8(80)
                .build()
        return requireNotNull(CpalTable.parse(ByteSlice.of(data)))
    }

    private fun parseV1(data: ByteArray): ColrTable = requireNotNull(ColrTable.parse(palette(), ByteSlice.of(data)))

    private fun v1(
        paints: List<Pair<Int, ByteArray>>,
        layerList: ByteArray? = null,
        clipList: ByteArray? = null,
        varIndexMap: ByteArray? = null,
        variationStore: ByteArray? = null,
    ): ByteArray {
        val baseListOffset = 34
        val baseListSize = 4 + paints.size * 6
        val paintBytes = paints.sumOf { it.second.size }
        val layerOffset = if (layerList == null) 0 else baseListOffset + baseListSize + paintBytes
        val clipOffset =
            if (clipList ==
                null
            ) {
                0
            } else {
                baseListOffset + baseListSize + paintBytes + (layerList?.size ?: 0)
            }
        val varIndexMapOffset =
            if (varIndexMap == null) {
                0
            } else {
                baseListOffset + baseListSize + paintBytes + (layerList?.size ?: 0) + (clipList?.size ?: 0)
            }
        val variationStoreOffset =
            if (variationStore == null) {
                0
            } else {
                baseListOffset +
                    baseListSize +
                    paintBytes +
                    (layerList?.size ?: 0) +
                    (clipList?.size ?: 0) +
                    (varIndexMap?.size ?: 0)
            }
        val output =
            Bytes()
                .u16(1)
                .u16(0)
                .u32(0)
                .u32(0)
                .u16(0)
                .u32(baseListOffset.toLong())
                .u32(layerOffset.toLong())
                .u32(clipOffset.toLong())
                .u32(varIndexMapOffset.toLong())
                .u32(variationStoreOffset.toLong())
                .u32(paints.size.toLong())
        var paintOffset = baseListSize
        for ((glyphId, paint) in paints) {
            output.u16(glyphId).u32(paintOffset.toLong())
            paintOffset += paint.size
        }
        paints.forEach { output.raw(it.second) }
        if (layerList != null) output.raw(layerList)
        if (clipList != null) output.raw(clipList)
        if (varIndexMap != null) output.raw(varIndexMap)
        if (variationStore != null) output.raw(variationStore)
        return output.build()
    }

    private fun embeddedVariation(vararg deltas: Pair<Int, Int>): EmbeddedVariation {
        require(deltas.size <= 15)
        val mapEntries = IntArray((deltas.maxOfOrNull { it.first } ?: 0) + 1)
        deltas.forEachIndexed { index, entry -> mapEntries[entry.first] = index + 1 }
        val map = Bytes().u8(0).u8(0x13).u16(mapEntries.size)
        mapEntries.forEach(map::u16)

        val store =
            Bytes()
                .u16(1)
                .u32(12)
                .u16(1)
                .u32(22)
                .u16(1)
                .u16(1)
                .i16(0)
                .i16(16384)
                .i16(16384)
                .u16(deltas.size + 1)
                .u16(1)
                .u16(1)
                .u16(0)
                .i16(0)
        deltas.forEach { store.i16(it.second) }
        return EmbeddedVariation(map.build(), store.build())
    }

    private fun writeU32(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private data class EmbeddedVariation(
        val map: ByteArray,
        val store: ByteArray,
    )

    private sealed interface Command {
        data class Outline(
            val glyphId: Int,
        ) : Command

        data class Fill(
            val paint: ColrPaint,
        ) : Command

        data class ClipBox(
            val box: ColrClipBox,
        ) : Command

        data class Layer(
            val mode: ColrCompositeMode,
        ) : Command

        data class Transform(
            val transform: ColrTransform,
        ) : Command

        data object PushClip : Command

        data object PopClip : Command

        data object PopLayer : Command

        data object PopTransform : Command
    }

    private class RecordingPainter : ColrPainter {
        val commands = mutableListOf<Command>()

        override fun outlineGlyph(glyphId: Int) {
            commands += Command.Outline(glyphId)
        }

        override fun paint(paint: ColrPaint) {
            commands += Command.Fill(paint)
        }

        override fun pushClip() {
            commands += Command.PushClip
        }

        override fun pushClipBox(clipBox: ColrClipBox) {
            commands += Command.ClipBox(clipBox)
        }

        override fun popClip() {
            commands += Command.PopClip
        }

        override fun pushLayer(mode: ColrCompositeMode) {
            commands += Command.Layer(mode)
        }

        override fun popLayer() {
            commands += Command.PopLayer
        }

        override fun pushTransform(transform: ColrTransform) {
            commands += Command.Transform(transform)
        }

        override fun popTransform() {
            commands += Command.PopTransform
        }
    }

    private class CountingPainter : ColrPainter {
        var paints = 0
        var transforms = 0
        var poppedTransforms = 0

        override fun outlineGlyph(glyphId: Int) = Unit

        override fun paint(paint: ColrPaint) {
            paints++
        }

        override fun pushClip() = Unit

        override fun pushClipBox(clipBox: ColrClipBox) = Unit

        override fun popClip() = Unit

        override fun pushLayer(mode: ColrCompositeMode) = Unit

        override fun popLayer() = Unit

        override fun pushTransform(transform: ColrTransform) {
            transforms++
        }

        override fun popTransform() {
            poppedTransforms++
        }
    }

    private class Bytes {
        private val bytes = mutableListOf<Byte>()

        fun u8(value: Int) = apply { bytes += value.toByte() }

        fun u16(value: Int) =
            apply {
                u8(value ushr 8)
                u8(value)
            }

        fun i16(value: Int) = u16(value and 0xFFFF)

        fun u24(value: Long) =
            apply {
                u8((value ushr 16).toInt())
                u8((value ushr 8).toInt())
                u8(value.toInt())
            }

        fun u32(value: Long) =
            apply {
                u8((value ushr 24).toInt())
                u8((value ushr 16).toInt())
                u8((value ushr 8).toInt())
                u8(value.toInt())
            }

        fun f2(value: Float) = i16((value * 16384f).toInt())

        fun fixed(value: Float) = u32((value * 65536f).toLong() and 0xFFFF_FFFFL)

        fun raw(value: ByteArray) = apply { value.forEach { bytes += it } }

        fun build(): ByteArray = ByteArray(bytes.size) { bytes[it] }
    }
}
