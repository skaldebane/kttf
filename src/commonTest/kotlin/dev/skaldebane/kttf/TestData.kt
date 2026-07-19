package dev.skaldebane.kttf

internal class TestByteBuilder {
    private val bytes = mutableListOf<Byte>()

    internal val size: Int
        get() = bytes.size

    internal fun int8(value: Int): TestByteBuilder =
        apply {
            bytes += value.toByte()
        }

    internal fun uint16(value: Int): TestByteBuilder =
        apply {
            bytes += (value ushr 8).toByte()
            bytes += value.toByte()
        }

    internal fun int16(value: Int): TestByteBuilder = uint16(value and 0xFFFF)

    internal fun uint32(value: Long): TestByteBuilder =
        apply {
            bytes += (value ushr 24).toByte()
            bytes += (value ushr 16).toByte()
            bytes += (value ushr 8).toByte()
            bytes += value.toByte()
        }

    internal fun raw(value: ByteArray): TestByteBuilder =
        apply {
            value.forEach(bytes::add)
        }

    internal fun zeroes(count: Int): TestByteBuilder =
        apply {
            repeat(count) { bytes += 0 }
        }

    internal fun build(): ByteArray = ByteArray(bytes.size) { bytes[it] }
}

internal fun minimalSfnt(
    baseOffset: Int = 0,
    unitsPerEm: Int = 1000,
    ascender: Int = 800,
    descender: Int = -200,
    lineGap: Int = 100,
    glyphCount: Int = 3,
    numberOfMetrics: Int = 2,
    hmtxData: ByteArray? = hmtxTable(),
    cmapData: ByteArray? = null,
    locaData: ByteArray? = null,
    glyfData: ByteArray? = null,
    longLocationOffsets: Boolean = false,
    nameData: ByteArray? = null,
    os2Data: ByteArray? = null,
    postData: ByteArray? = null,
    vheaData: ByteArray? = null,
    vmtxData: ByteArray? = null,
    cffData: ByteArray? = null,
    additionalTables: Map<Tag, ByteArray> = emptyMap(),
    sortTables: Boolean = true,
): ByteArray {
    val entries =
        buildList {
            if (cmapData != null) add(Tag.parse("cmap") to cmapData)
            if (cffData != null) add(Tag.parse("CFF ") to cffData)
            add(Tag.parse("head") to headTable(unitsPerEm, longLocationOffsets))
            add(Tag.parse("hhea") to hheaTable(ascender, descender, lineGap, numberOfMetrics))
            if (hmtxData != null) add(Tag.parse("hmtx") to hmtxData)
            if (locaData != null) add(Tag.parse("loca") to locaData)
            add(Tag.parse("maxp") to maxpTable(glyphCount))
            if (glyfData != null) add(Tag.parse("glyf") to glyfData)
            if (nameData != null) add(Tag.parse("name") to nameData)
            if (os2Data != null) add(Tag.parse("OS/2") to os2Data)
            if (postData != null) add(Tag.parse("post") to postData)
            if (vheaData != null) add(Tag.parse("vhea") to vheaData)
            if (vmtxData != null) add(Tag.parse("vmtx") to vmtxData)
            addAll(additionalTables.entries.map { it.key to it.value })
        }
    val tables = if (sortTables) entries.sortedBy { it.first.value } else entries.reversed()

    val directorySize = 12 + tables.size * 16
    var tableOffset = directorySize
    val records = mutableListOf<Triple<Tag, Int, ByteArray>>()
    for ((tag, data) in tables) {
        records += Triple(tag, baseOffset + tableOffset, data)
        tableOffset += data.size
        tableOffset = (tableOffset + 3) and -4
    }

    val output =
        TestByteBuilder()
            .uint32(if (cffData == null && Tag.parse("CFF2") !in additionalTables) 0x00010000 else 0x4F54544F)
            .uint16(records.size)
            .uint16(0)
            .uint16(0)
            .uint16(0)

    for ((tag, offset, data) in records) {
        output
            .uint32(tag.value.toLong() and 0xFFFF_FFFFL)
            .uint32(0)
            .uint32(offset.toLong())
            .uint32(data.size.toLong())
    }

    for ((_, _, data) in records) {
        output.raw(data)
        while (output.size % 4 != 0) output.int8(0)
    }

    return output.build()
}

internal fun minimalCollection(): ByteArray {
    val headerSize = 20
    val first = minimalSfnt(baseOffset = headerSize, unitsPerEm = 1000)
    val secondOffset = headerSize + first.size
    val second = minimalSfnt(baseOffset = secondOffset, unitsPerEm = 2048, ascender = 1600)

    return TestByteBuilder()
        .uint32(0x74746366)
        .uint32(0x00010000)
        .uint32(2)
        .uint32(headerSize.toLong())
        .uint32(secondOffset.toLong())
        .raw(first)
        .raw(second)
        .build()
}

internal fun testCmap(vararg records: Triple<Int, Int, ByteArray>): ByteArray {
    val headerSize = 4 + records.size * 8
    var offset = headerSize
    val builder = TestByteBuilder().uint16(0).uint16(records.size)
    for ((platform, encoding, data) in records) {
        builder.uint16(platform).uint16(encoding).uint32(offset.toLong())
        offset += data.size
    }
    records.forEach { builder.raw(it.third) }
    return builder.build()
}

internal fun testFormat4(
    codePoint: Int = 0x41,
    glyphId: Int = 1,
): ByteArray {
    val delta = (glyphId - codePoint).toShort().toInt()
    return TestByteBuilder()
        .uint16(4)
        .uint16(32)
        .uint16(0)
        .uint16(4)
        .uint16(2)
        .uint16(0)
        .uint16(2)
        .uint16(codePoint)
        .uint16(0xFFFF)
        .uint16(0)
        .uint16(codePoint)
        .uint16(0xFFFF)
        .int16(delta)
        .int16(1)
        .uint16(0)
        .uint16(0)
        .build()
}

internal fun testFormat12(
    codePoint: Long = 0x10000,
    glyphId: Int = 2,
): ByteArray =
    TestByteBuilder()
        .uint16(12)
        .uint16(0)
        .uint32(28)
        .uint32(0)
        .uint32(1)
        .uint32(codePoint)
        .uint32(codePoint)
        .uint32(glyphId.toLong())
        .build()

internal fun testFormat14(): ByteArray =
    TestByteBuilder()
        .uint16(14)
        .uint32(38)
        .uint32(1)
        .int8(0)
        .int8(0xFE)
        .int8(0x0F)
        .uint32(21)
        .uint32(29)
        .uint32(1)
        .int8(0)
        .int8(0)
        .int8(0x41)
        .int8(0)
        .uint32(1)
        .int8(0)
        .int8(0)
        .int8(0x42)
        .uint16(2)
        .build()

internal fun testCffTriangleCharString(): ByteArray =
    byteArrayOf(
        139.toByte(),
        139.toByte(),
        21,
        239.toByte(),
        139.toByte(),
        39,
        239.toByte(),
        139.toByte(),
        39,
        5,
        14,
    )

internal fun Float.testCoordinate(): String =
    if (isFinite() && this == toInt().toFloat()) {
        "${toInt()}.0"
    } else {
        toString()
    }

internal fun testCffTable(
    vararg charStrings: ByteArray,
    charset: ByteArray? = null,
): ByteArray {
    fun topDictionary(
        charStringsOffset: Int,
        charsetOffset: Int,
    ): ByteArray =
        TestByteBuilder()
            .apply {
                if (charset != null) {
                    int8(29).uint32(charsetOffset.toLong()).int8(15)
                }
            }.int8(29)
            .uint32(charStringsOffset.toLong())
            .int8(17)
            .build()

    val placeholderTopIndex = testCffIndex(topDictionary(0, 0))
    val charStringsOffset = 4 + 2 + placeholderTopIndex.size + 2 + 2
    val charStringsIndex = testCffIndex(*charStrings)
    val charsetOffset = charStringsOffset + charStringsIndex.size

    return TestByteBuilder()
        .int8(1)
        .int8(0)
        .int8(4)
        .int8(4)
        .uint16(0)
        .raw(testCffIndex(topDictionary(charStringsOffset, charsetOffset)))
        .uint16(0)
        .uint16(0)
        .raw(charStringsIndex)
        .raw(charset ?: byteArrayOf())
        .build()
}

private fun testCffIndex(vararg objects: ByteArray): ByteArray {
    if (objects.isEmpty()) return TestByteBuilder().uint16(0).build()

    val builder = TestByteBuilder().uint16(objects.size).int8(1)
    var offset = 1
    builder.int8(offset)
    for (data in objects) {
        offset += data.size
        builder.int8(offset)
    }
    objects.forEach(builder::raw)
    return builder.build()
}

internal fun testNameTable(
    value: String = "kttf Test",
    nameId: Int = NameIds.FAMILY,
): ByteArray {
    val encoded = TestByteBuilder().apply { value.forEach { uint16(it.code) } }.build()
    return TestByteBuilder()
        .uint16(0)
        .uint16(1)
        .uint16(18)
        .uint16(3)
        .uint16(1)
        .uint16(0x0409)
        .uint16(nameId)
        .uint16(encoded.size)
        .uint16(0)
        .raw(encoded)
        .build()
}

internal fun testOs2Table(
    weight: Int = 700,
    width: Int = 5,
    selectionFlags: Int = (1 shl 5) or (1 shl 7),
): ByteArray {
    val data = ByteArray(96)
    data.writeUInt16(0, 4)
    data.writeUInt16(4, weight)
    data.writeUInt16(6, width)
    data.writeInt16(68, 900)
    data.writeInt16(70, -250)
    data.writeInt16(72, 100)
    data.writeUInt16(74, 1000)
    data.writeUInt16(76, 300)
    data.writeUInt16(62, selectionFlags)
    data.writeInt16(86, 450)
    data.writeInt16(88, 700)
    return data
}

internal fun testTriangleGlyph(): ByteArray =
    TestByteBuilder()
        .int16(1)
        .int16(0)
        .int16(0)
        .int16(100)
        .int16(100)
        .uint16(2)
        .uint16(0)
        .int8(0x31)
        .int8(0x33)
        .int8(0x27)
        .int8(100)
        .int8(100)
        .int8(100)
        .build()

internal fun testShortLoca(vararg offsets: Int): ByteArray =
    TestByteBuilder()
        .apply {
            offsets.forEach {
                require(it % 2 == 0)
                uint16(it / 2)
            }
        }.build()

internal fun testPostTable(): ByteArray =
    TestByteBuilder()
        .uint32(0x00020000)
        .uint32(0x00018000)
        .int16(-100)
        .int16(20)
        .uint32(1)
        .zeroes(16)
        .uint16(3)
        .uint16(0)
        .uint16(258)
        .uint16(1)
        .int8(6)
        .raw(byteArrayOf(99, 117, 115, 116, 111, 109))
        .build()

internal fun testVheaTable(): ByteArray {
    val data = ByteArray(36)
    data.writeInt16(4, 900)
    data.writeInt16(6, -300)
    data.writeInt16(8, 50)
    data.writeUInt16(34, 2)
    return data
}

internal fun testVmtxTable(): ByteArray =
    TestByteBuilder()
        .uint16(1000)
        .int16(10)
        .uint16(1100)
        .int16(20)
        .int16(30)
        .build()

private fun headTable(
    unitsPerEm: Int,
    longLocationOffsets: Boolean,
): ByteArray {
    val data = ByteArray(54)
    data.writeUInt16(18, unitsPerEm)
    data.writeInt16(36, -50)
    data.writeInt16(38, -200)
    data.writeInt16(40, 1200)
    data.writeInt16(42, 900)
    data.writeUInt16(50, if (longLocationOffsets) 1 else 0)
    return data
}

private fun hheaTable(
    ascender: Int,
    descender: Int,
    lineGap: Int,
    numberOfMetrics: Int,
): ByteArray {
    val data = ByteArray(36)
    data.writeInt16(4, ascender)
    data.writeInt16(6, descender)
    data.writeInt16(8, lineGap)
    data.writeUInt16(34, numberOfMetrics)
    return data
}

private fun maxpTable(glyphCount: Int): ByteArray =
    TestByteBuilder()
        .uint32(0x00010000)
        .uint16(glyphCount)
        .build()

private fun hmtxTable(): ByteArray =
    TestByteBuilder()
        .uint16(500)
        .int16(10)
        .uint16(600)
        .int16(20)
        .int16(30)
        .build()

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
) {
    writeUInt16(offset, value and 0xFFFF)
}
