package dev.skaldebane.kttf

import java.io.File

public fun main(arguments: Array<String>) {
    val projectDirectory = File(requireNotNull(arguments.singleOrNull()))
    val oracleDirectory = projectDirectory.resolve("tools/ttf-parser-oracle")
    val fixture = oracleDirectory.resolve("fixtures/cmap-basic.hex")
    val data = decodeHex(fixture.readText())
    compareFixture(oracleDirectory, fixture, inspectWithKttf(data))

    val glyph = testTriangleGlyph()
    val metadataAndOutline =
        minimalSfnt(
            glyphCount = 3,
            locaData = testShortLoca(0, glyph.size, glyph.size, glyph.size),
            glyfData = glyph,
            nameData = testNameTable(),
            os2Data = testOs2Table(),
        )
    val temporary =
        kotlin.io.path
            .createTempFile("kttf-oracle-", ".ttf")
            .toFile()
    try {
        temporary.writeBytes(metadataAndOutline)
        compareFixture(oracleDirectory, temporary, inspectWithKttf(metadataAndOutline))
    } finally {
        temporary.delete()
    }

    val cffOutline =
        minimalSfnt(
            glyphCount = 4,
            numberOfMetrics = 1,
            cffData =
                testCffTable(
                    byteArrayOf(14),
                    byteArrayOf(149.toByte(), 159.toByte(), 21, 169.toByte(), 139.toByte(), 5, 14),
                    byteArrayOf(139.toByte(), 139.toByte(), 21, 139.toByte(), 149.toByte(), 5, 14),
                    byteArrayOf(239.toByte(), 247.toByte(), 92, 204.toByte(), 205.toByte(), 14),
                    charset = byteArrayOf(0, 0, 34, 0, 35, 0, 36),
                ),
        )
    val temporaryCff =
        kotlin.io.path
            .createTempFile("kttf-oracle-", ".otf")
            .toFile()
    try {
        temporaryCff.writeBytes(cffOutline)
        compareFixture(oracleDirectory, temporaryCff, inspectWithKttf(cffOutline))
    } finally {
        temporaryCff.delete()
    }
}

private fun compareFixture(
    oracleDirectory: File,
    fixture: File,
    kotlinOutput: String,
) {
    val process =
        ProcessBuilder("cargo", "run", "--locked", "--quiet", "--", fixture.absolutePath)
            .directory(oracleDirectory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    val rustOutput = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "ttf-parser oracle failed" }
    check(kotlinOutput == rustOutput) {
        "Differential output mismatch\n--- Rust\n$rustOutput--- Kotlin\n$kotlinOutput"
    }
}

private fun inspectWithKttf(data: ByteArray): String {
    val face = FontFace.parse(data)
    val cmap = face.cmap
    return buildString {
        appendLine("schema\t1")
        appendLine("upstream\t6e75b3c539ef6bfbacb020aaa3226414ddea7ce5")
        appendLine("collection\tnone")
        appendLine("face_index\t${face.faceIndex}")
        appendLine("face\tok")
        appendLine("units_per_em\t${face.unitsPerEm}")
        appendLine("bounds\t${face.bounds.xMin}\t${face.bounds.yMin}\t${face.bounds.xMax}\t${face.bounds.yMax}")
        appendLine("hhea\t${face.hheaAscender}\t${face.hheaDescender}\t${face.hheaLineGap}")
        appendLine("glyph_count\t${face.glyphCount}")
        if (face.table(Tag.parse("OS/2")) != null) {
            appendLine(
                "os2\t${face.weight.value}\t${face.width.value}\t${face.style}\t${face.isBold}\t" +
                    "${face.permissions ?: "none"}\t${face.ascender}\t${face.descender}\t${face.lineGap}",
            )
        }
        if (face.table(Tag.parse("name")) != null) {
            appendLine("name_count\t${face.names.size}")
            for (index in 0 until face.names.size) {
                val name = face.names[index]
                if (name == null) {
                    appendLine("name\t$index\tinvalid")
                } else {
                    appendLine(
                        "name\t$index\t${name.platformId}\t${name.encodingId}\t${name.languageId}\t" +
                            "${name.nameId}\t${name.decodeToString() ?: "none"}",
                    )
                }
            }
        }
        if (face.table(Tag.parse("glyf")) != null || face.table(Tag.parse("CFF ")) != null) {
            for (glyphId in 0 until face.glyphCount) {
                val sink = CanonicalOutlineSink()
                val bounds = face.outlineGlyph(GlyphId(glyphId), sink) ?: continue
                appendLine(
                    "outline\t$glyphId\t${bounds.xMin}\t${bounds.yMin}\t${bounds.xMax}\t${bounds.yMax}\t" +
                        sink.commands.joinToString("|"),
                )
            }
        }
        if (face.table(Tag.parse("CFF ")) != null) {
            for (glyphId in 0 until face.glyphCount) {
                appendLine("glyph_name\t$glyphId\t${face.glyphName(GlyphId(glyphId)) ?: "none"}")
            }
        }
        for (tag in listOf("cmap", "head", "hhea", "hmtx", "maxp")) {
            appendLine("table\t$tag\t${face.table(Tag.parse(tag))?.size ?: "none"}")
        }
        if (cmap == null) {
            appendLine("cmap\tnone")
            return@buildString
        }
        appendLine("cmap_count\t${cmap.subtables.size}")
        val effective = sortedSetOf<Long>()
        for (index in 0 until cmap.subtables.size) {
            val subtable = cmap.subtables[index]
            if (subtable == null) {
                appendLine("subtable\t$index\tinvalid")
                continue
            }
            appendLine(
                "subtable\t$index\t${subtable.platformId}\t${subtable.encodingId}\t" +
                    "${subtable.format.value}\t${subtable.isUnicode}",
            )
            subtable.forEachCodePoint { codePoint ->
                appendLine("codepoint\t$index\t$codePoint\t${subtable.glyphId(codePoint)?.value ?: "none"}")
                if (codePoint <= 0x10FFFF && codePoint !in 0xD800..0xDFFF) effective += codePoint
            }
        }
        for (codePoint in effective) {
            appendLine("mapping\t$codePoint\t${face.glyphId(codePoint.toInt())?.value ?: "none"}")
        }
    }
}

private class CanonicalOutlineSink : OutlineSink {
    val commands = mutableListOf<String>()

    override fun moveTo(
        x: Float,
        y: Float,
    ) {
        commands += "M $x $y"
    }

    override fun lineTo(
        x: Float,
        y: Float,
    ) {
        commands += "L $x $y"
    }

    override fun quadTo(
        controlX: Float,
        controlY: Float,
        x: Float,
        y: Float,
    ) {
        commands += "Q $controlX $controlY $x $y"
    }

    override fun curveTo(
        control1X: Float,
        control1Y: Float,
        control2X: Float,
        control2Y: Float,
        x: Float,
        y: Float,
    ) {
        commands += "C $control1X $control1Y $control2X $control2Y $x $y"
    }

    override fun close() {
        commands += "Z"
    }
}

private fun decodeHex(source: String): ByteArray {
    val digits = source.filter(Char::isHexDigit)
    require(digits.length % 2 == 0)
    return ByteArray(digits.length / 2) { index ->
        ((digits[index * 2].digitToInt(16) shl 4) or digits[index * 2 + 1].digitToInt(16)).toByte()
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
