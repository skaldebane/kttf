package dev.skaldebane.kttf.internal.tables.name

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameTableTest {
    @Test
    fun parsesBinaryBackedRecordsAndMetadata() {
        val bytes =
            nameTable(
                records =
                    listOf(
                        Record(3, 1, 0x0409, NameId.FAMILY, byteArrayOf(0, 65)),
                        Record(1, 0, 0, NameId.SUBFAMILY, byteArrayOf(0x42)),
                    ),
            )
        val table = parse(bytes)

        assertEquals(0, table.format)
        assertEquals(2, table.size)
        assertFalse(table.isEmpty)
        assertEquals(NamePlatform.WINDOWS, table[0]?.platform)
        assertEquals(1, table[0]?.encodingId)
        assertEquals(0x0409, table[0]?.languageId)
        assertEquals(NameId.FAMILY, table[0]?.nameId)
        assertTrue(table[0]!!.isUnicode)
        assertEquals("A", table[0]?.decode())
        assertEquals(NamePlatform.MACINTOSH, table[1]?.platform)
        assertFalse(table[1]!!.isUnicode)
        assertNull(table[1]?.decode())
        assertNull(table[-1])
        assertNull(table[2])

        bytes[bytes.lastIndex - 2] = 0
        bytes[bytes.lastIndex - 1] = 67
        assertEquals("C", table[0]?.decode())
    }

    @Test
    fun followsUpstreamUnicodeEncodingPolicy() {
        val records =
            listOf(
                Record(0, 99, 0, 1, byteArrayOf(0, 65)),
                Record(3, 0, 0, 1, byteArrayOf(0, 66)),
                Record(3, 1, 0, 1, byteArrayOf(0, 67)),
                Record(3, 10, 0, 1, byteArrayOf(0, 68)),
                Record(2, 0, 0, 1, byteArrayOf(0, 69)),
            )
        val table = parse(nameTable(records))

        assertEquals(listOf(true, true, true, false, false), (0 until table.size).map { table[it]!!.isUnicode })
        assertEquals(listOf("A", "B", "C", null, null), (0 until table.size).map { table[it]!!.decode() })
    }

    @Test
    fun decodesStrictUtf16BeOnlyWhenRequested() {
        val table =
            parse(
                nameTable(
                    listOf(
                        Record(0, 0, 0, 1, byteArrayOf()),
                        Record(0, 0, 0, 1, byteArrayOf(0, 65, 0xD8.toByte(), 0x3D, 0xDE.toByte(), 0)),
                        Record(0, 0, 0, 1, byteArrayOf(0)),
                        Record(0, 0, 0, 1, byteArrayOf(0xD8.toByte(), 0, 0, 65)),
                        Record(0, 0, 0, 1, byteArrayOf(0xDC.toByte(), 0)),
                    ),
                ),
            )

        assertEquals("", table[0]?.decode())
        assertEquals("A\uD83D\uDE00", table[1]?.decode())
        assertNull(table[2]?.decode())
        assertNull(table[3]?.decode())
        assertNull(table[4]?.decode())
    }

    @Test
    fun parsesFormatOneLanguageTags() {
        val table =
            parse(
                nameTable(
                    records = listOf(Record(0, 4, 0x8001, NameId.FULL_NAME, byteArrayOf(0, 65))),
                    languageTags = listOf(utf16("en-US"), utf16("fr")),
                ),
            )

        assertEquals(1, table.format)
        assertEquals("fr", table[0]?.decodeLanguageTag())
        assertEquals("A", table[0]?.decode())
    }

    @Test
    fun returnsNullForMalformedIndividualRecords() {
        val outOfBounds = nameTable(listOf(Record(0, 0, 0, 1, byteArrayOf(0, 65))))
        outOfBounds[14] = 0xFF.toByte()
        outOfBounds[15] = 0xFF.toByte()
        assertNull(parse(outOfBounds)[0])

        val unknownPlatform = nameTable(listOf(Record(5, 0, 0, 1, byteArrayOf())))
        assertNull(parse(unknownPlatform)[0])

        val badTagReference =
            parse(
                nameTable(
                    records = listOf(Record(0, 0, 0x8001, 1, byteArrayOf())),
                    languageTags = listOf(utf16("en")),
                ),
            )
        assertNull(badTagReference[0]?.decodeLanguageTag())
    }

    @Test
    fun rejectsMalformedAndTruncatedTables() {
        val unsupportedFormat =
            TestByteBuilder()
                .uint16(2)
                .uint16(0)
                .uint16(6)
                .build()
        val truncatedRecord =
            TestByteBuilder()
                .uint16(0)
                .uint16(1)
                .uint16(18)
                .zeroes(11)
                .build()
        val storagePastEnd =
            TestByteBuilder()
                .uint16(0)
                .uint16(0)
                .uint16(7)
                .build()
        val truncatedLanguageTags =
            TestByteBuilder()
                .uint16(1)
                .uint16(0)
                .uint16(8)
                .uint16(1)
                .build()
        val overlappingLanguageTags =
            TestByteBuilder()
                .uint16(1)
                .uint16(0)
                .uint16(7)
                .uint16(0)
                .build()

        assertNull(parseOrNull(ByteArray(5)))
        assertNull(parseOrNull(unsupportedFormat))
        assertNull(parseOrNull(truncatedRecord))
        assertNull(parseOrNull(storagePastEnd))
        assertNull(parseOrNull(truncatedLanguageTags))
        assertNull(parseOrNull(overlappingLanguageTags))

        val valid = nameTable(listOf(Record(0, 0, 0, 1, byteArrayOf(0, 65))))
        valid[4] = 0
        valid[5] = 17
        assertNull(parseOrNull(valid))
    }

    @Test
    fun parsesAnEmptyTable() {
        val table = parse(nameTable(emptyList()))

        assertEquals(0, table.size)
        assertTrue(table.isEmpty)
    }

    private fun parse(data: ByteArray): NameTable = requireNotNull(parseOrNull(data))

    private fun parseOrNull(data: ByteArray): NameTable? = NameTable.parse(ByteSlice.of(data))

    private fun nameTable(
        records: List<Record>,
        languageTags: List<ByteArray>? = null,
    ): ByteArray {
        val format = if (languageTags == null) 0 else 1
        val metadataSize = 6 + records.size * 12 + if (languageTags == null) 0 else 2 + languageTags.size * 4
        val strings = records.map(Record::value) + languageTags.orEmpty()
        val offsets = IntArray(strings.size)
        var offset = 0
        strings.forEachIndexed { index, value ->
            offsets[index] = offset
            offset += value.size
        }

        val builder =
            TestByteBuilder()
                .uint16(format)
                .uint16(records.size)
                .uint16(metadataSize)
                .apply {
                    records.forEachIndexed { index, record ->
                        uint16(record.platform)
                        uint16(record.encoding)
                        uint16(record.language)
                        uint16(record.name)
                        uint16(record.value.size)
                        uint16(offsets[index])
                    }
                    if (languageTags != null) {
                        uint16(languageTags.size)
                        languageTags.forEachIndexed { index, tag ->
                            uint16(tag.size)
                            uint16(offsets[records.size + index])
                        }
                    }
                    strings.forEach(::raw)
                }
        return builder.build()
    }

    private fun utf16(value: String): ByteArray =
        TestByteBuilder()
            .apply { value.forEach { uint16(it.code) } }
            .build()

    private data class Record(
        val platform: Int,
        val encoding: Int,
        val language: Int,
        val name: Int,
        val value: ByteArray,
    )
}
