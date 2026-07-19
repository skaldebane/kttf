package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitmapTablesTest {
    @Test
    fun supportsEveryCblcIndexFormat() {
        assertLocation(indexFormat = 1, payload = TestByteBuilder().uint32(7).build(), expectedOffset = 107)
        assertLocation(
            indexFormat = 2,
            startGlyph = 1,
            glyphId = 2,
            payload = TestByteBuilder().uint32(9).raw(bigMetrics()).build(),
            expectedOffset = 109,
            expectedMetrics = BitmapMetrics(x = -2, y = 7, width = 3, height = 4),
        )
        assertLocation(indexFormat = 3, payload = TestByteBuilder().uint16(7).build(), expectedOffset = 107)
        assertLocation(
            indexFormat = 4,
            payload =
                TestByteBuilder()
                    .uint32(1)
                    .uint16(2)
                    .uint16(7)
                    .uint16(3)
                    .uint16(9)
                    .build(),
            expectedOffset = 107,
        )
        assertLocation(
            indexFormat = 5,
            startGlyph = 0,
            glyphId = 4,
            payload =
                TestByteBuilder()
                    .uint32(9)
                    .raw(bigMetrics())
                    .uint32(3)
                    .uint16(2)
                    .uint16(4)
                    .uint16(8)
                    .build(),
            expectedOffset = 109,
            expectedMetrics = BitmapMetrics(x = -2, y = 7, width = 3, height = 4),
        )
    }

    @Test
    fun selectsClosestUpperStrikeThenLargestLowerStrike() {
        val table = CblcTable.parse(ByteSlice.of(twoStrikeCblc()))

        assertEquals(16, table.get(0, 12)?.pixelsPerEm)
        assertEquals(216, table.get(0, 12)?.offset)
        assertEquals(32, table.get(0, 40)?.pixelsPerEm)
        assertEquals(132, table.get(0, 40)?.offset)
    }

    @Test
    fun decodesSupportedBitmapFormatsAndMetrics() {
        val cases =
            listOf(
                BitmapCase(1, 1, RasterImageFormat.BITMAP_MONO, 4),
                BitmapCase(1, 2, RasterImageFormat.BITMAP_GRAY_2, 6),
                BitmapCase(1, 4, RasterImageFormat.BITMAP_GRAY_4, 10),
                BitmapCase(1, 8, RasterImageFormat.BITMAP_GRAY_8, 18),
                BitmapCase(1, 32, RasterImageFormat.BITMAP_PREMUL_BGRA_32, 72),
                BitmapCase(2, 1, RasterImageFormat.BITMAP_MONO_PACKED, 3),
                BitmapCase(2, 2, RasterImageFormat.BITMAP_GRAY_2_PACKED, 5),
                BitmapCase(2, 4, RasterImageFormat.BITMAP_GRAY_4_PACKED, 9),
                BitmapCase(2, 8, RasterImageFormat.BITMAP_GRAY_8, 18),
                BitmapCase(2, 32, RasterImageFormat.BITMAP_PREMUL_BGRA_32, 72),
            )

        for (case in cases) {
            val payload = ByteArray(case.dataSize) { (it + 1).toByte() }
            val imageBytes = TestByteBuilder().raw(smallMetrics()).raw(payload).build()
            val image = bitmapImage(imageFormat = case.imageFormat, bitDepth = case.bitDepth, data = imageBytes)

            assertEquals(case.format, image.format, case.toString())
            assertEquals(-2, image.x)
            assertEquals(3, image.y)
            assertEquals(9, image.width)
            assertEquals(2, image.height)
            assertEquals(16, image.pixelsPerEm)
            assertContentEquals(payload, image.data.values())
        }
    }

    @Test
    fun decodesBigAndSharedPngMetrics() {
        val png = byteArrayOf(1, 2, 3, 4)
        val bigData =
            TestByteBuilder()
                .raw(bigMetrics())
                .uint32(png.size.toLong())
                .raw(png)
                .build()
        val big = bitmapImage(imageFormat = 18, bitDepth = 8, data = bigData)
        assertEquals(BitmapMetrics(-2, 7, 3, 4), big.metrics())
        assertEquals(3, big.y)
        assertContentEquals(png, big.data.values())

        val sharedLocations =
            cblc(
                indexFormat = 2,
                imageFormat = 19,
                bitDepth = 8,
                payload = TestByteBuilder().uint32(8).raw(bigMetrics()).build(),
            )
        val sharedData = TestByteBuilder().uint32(png.size.toLong()).raw(png).build()
        val shared =
            assertNotNull(
                CbdtTable.parse(CblcTable.parse(ByteSlice.of(sharedLocations)), ByteSlice.of(sharedData)).get(2, 16),
            )
        assertEquals(BitmapMetrics(-2, 7, 3, 4), shared.metrics())
        assertEquals(3, shared.y)
        assertContentEquals(png, shared.data.values())
    }

    @Test
    fun legacyTableNamesReuseTheSameParsers() {
        val locations: EblcTable = BlocTable.parse(ByteSlice.of(cblc()))
        val ebdt: EbdtTable = EbdtTable.parse(locations, ByteSlice.of(smallBitmapData()))
        val bdat: BdatTable = BdatTable.parse(locations, ByteSlice.of(smallBitmapData()))

        assertEquals(RasterImageFormat.BITMAP_GRAY_8, ebdt.get(2, 16)?.format)
        assertEquals(RasterImageFormat.BITMAP_GRAY_8, bdat.get(2, 16)?.format)
    }

    @Test
    fun imageDataIsBoundedAndZeroCopy() {
        val imageBytes = smallBitmapData()
        val source = byteArrayOf(99) + imageBytes + byteArrayOf(88)
        val bounded = ByteSlice.of(source).slice(1, imageBytes.size)!!
        val image =
            assertNotNull(
                CbdtTable.parse(CblcTable.parse(ByteSlice.of(cblc())), bounded).get(2, 16),
            )

        assertEquals(18, image.data.size)
        assertNull(image.data[-1])
        assertNull(image.data[18])
        assertEquals(1, image.data[0])
        source[6] = 42
        assertEquals(42, image.data[0])
        assertTrue(!image.data.copyInto(ByteArray(17)))
        val copy = ByteArray(18)
        assertTrue(image.data.copyInto(copy))
        assertContentEquals(ByteArray(18) { if (it == 0) 42 else (it + 1).toByte() }, copy)
    }

    @Test
    fun malformedTablesAndImagesReturnNull() {
        assertNull(CblcTable.parse(ByteSlice.of(ByteArray(0))).get(0, 1))
        assertNull(CblcTable.parse(ByteSlice.of(cblc(indexFormat = 0))).get(2, 16))
        assertNull(CblcTable.parse(ByteSlice.of(cblc(imageFormat = 3))).get(2, 16))
        assertNull(CblcTable.parse(ByteSlice.of(cblc())).get(-1, 16))
        assertNull(CblcTable.parse(ByteSlice.of(cblc())).get(2, -1))

        val locations = CblcTable.parse(ByteSlice.of(cblc()))
        for (length in smallBitmapData().indices) {
            val data = ByteSlice.of(smallBitmapData().copyOf(length))
            assertNull(CbdtTable.parse(locations, data).get(2, 16), "length=$length")
        }
        val unsupportedDepth = CblcTable.parse(ByteSlice.of(cblc(bitDepth = 3)))
        assertNull(CbdtTable.parse(unsupportedDepth, ByteSlice.of(smallBitmapData())).get(2, 16))
    }

    private fun assertLocation(
        indexFormat: Int,
        payload: ByteArray,
        expectedOffset: Int,
        startGlyph: Int = 2,
        glyphId: Int = 2,
        expectedMetrics: BitmapMetrics = BitmapMetrics(),
    ) {
        val location =
            assertNotNull(
                CblcTable
                    .parse(
                        ByteSlice.of(
                            cblc(
                                indexFormat = indexFormat,
                                startGlyph = startGlyph,
                                endGlyph = 8,
                                imageDataOffset = 100,
                                payload = payload,
                            ),
                        ),
                    ).get(
                        glyphId,
                        16,
                    ),
            )
        assertEquals(expectedOffset, location.offset)
        assertEquals(expectedMetrics, location.metrics)
    }

    private fun bitmapImage(
        imageFormat: Int,
        bitDepth: Int,
        data: ByteArray,
    ): RasterGlyphImage {
        val locationBytes = cblc(imageFormat = imageFormat, bitDepth = bitDepth)
        val locations = CblcTable.parse(ByteSlice.of(locationBytes))
        return assertNotNull(CbdtTable.parse(locations, ByteSlice.of(data)).get(2, 16))
    }

    private fun cblc(
        indexFormat: Int = 1,
        imageFormat: Int = 1,
        bitDepth: Int = 8,
        startGlyph: Int = 2,
        endGlyph: Int = 2,
        imageDataOffset: Int = 0,
        payload: ByteArray = TestByteBuilder().uint32(0).build(),
    ): ByteArray =
        TestByteBuilder()
            .uint32(0x0003_0000)
            .uint32(1)
            .uint32(56)
            .uint32(0)
            .uint32(1)
            .uint32(0)
            .zeroes(24)
            .uint16(startGlyph)
            .uint16(endGlyph)
            .int8(16)
            .int8(16)
            .int8(bitDepth)
            .int8(0)
            .uint16(startGlyph)
            .uint16(endGlyph)
            .uint32(8)
            .uint16(indexFormat)
            .uint16(imageFormat)
            .uint32(imageDataOffset.toLong())
            .raw(payload)
            .build()

    private fun twoStrikeCblc(): ByteArray =
        TestByteBuilder()
            .uint32(0x0003_0000)
            .uint32(2)
            .raw(sizeRecord(arrayOffset = 104, pixelsPerEm = 32))
            .raw(sizeRecord(arrayOffset = 112, pixelsPerEm = 16))
            .uint16(0)
            .uint16(0)
            .uint32(16)
            .uint16(0)
            .uint16(0)
            .uint32(20)
            .uint16(1)
            .uint16(1)
            .uint32(125)
            .uint32(7)
            .uint16(1)
            .uint16(1)
            .uint32(209)
            .uint32(7)
            .build()

    private fun sizeRecord(
        arrayOffset: Int,
        pixelsPerEm: Int,
    ): ByteArray =
        TestByteBuilder()
            .uint32(arrayOffset.toLong())
            .uint32(0)
            .uint32(1)
            .uint32(0)
            .zeroes(24)
            .uint16(0)
            .uint16(0)
            .int8(pixelsPerEm)
            .int8(pixelsPerEm)
            .int8(8)
            .int8(0)
            .build()

    private fun smallMetrics(): ByteArray = byteArrayOf(2, 9, -2, 5, 6)

    private fun bigMetrics(): ByteArray = byteArrayOf(4, 3, -2, 7, 8, -1, 2, 9)

    private fun smallBitmapData(): ByteArray =
        TestByteBuilder().raw(smallMetrics()).raw(ByteArray(18) { (it + 1).toByte() }).build()

    private fun RasterImageData.values(): ByteArray = ByteArray(size) { assertNotNull(this[it]) }

    private fun RasterGlyphImage.metrics(): BitmapMetrics = BitmapMetrics(x, y + height, width, height)

    private data class BitmapCase(
        val imageFormat: Int,
        val bitDepth: Int,
        val format: RasterImageFormat,
        val dataSize: Int,
    )
}
