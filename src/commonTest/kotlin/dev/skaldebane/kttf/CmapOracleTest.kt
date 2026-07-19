package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertEquals

class CmapOracleTest {
    @Test
    fun matchesPinnedRustOracleForBasicCmapFixture() {
        val data =
            minimalSfnt(
                hmtxData = null,
                cmapData = testCmap(Triple(3, 1, testFormat4())),
            )
        val face = FontFace.parse(data)
        val cmap = requireNotNull(face.cmap)
        val output =
            buildString {
                appendLine("schema\t1")
                appendLine("upstream\t6e75b3c539ef6bfbacb020aaa3226414ddea7ce5")
                appendLine("collection\tnone")
                appendLine("face_index\t${face.faceIndex}")
                appendLine("face\tok")
                appendLine("units_per_em\t${face.unitsPerEm}")
                appendLine("bounds\t${face.bounds.xMin}\t${face.bounds.yMin}\t${face.bounds.xMax}\t${face.bounds.yMax}")
                appendLine("hhea\t${face.hheaAscender}\t${face.hheaDescender}\t${face.hheaLineGap}")
                appendLine("glyph_count\t${face.glyphCount}")
                for (tag in listOf("cmap", "head", "hhea", "hmtx", "maxp")) {
                    appendLine("table\t$tag\t${face.table(Tag.parse(tag))?.size ?: "none"}")
                }
                appendLine("cmap_count\t${cmap.subtables.size}")
                for ((index, subtable) in cmap.subtables.withIndex()) {
                    appendLine(
                        "subtable\t$index\t${subtable.platformId}\t${subtable.encodingId}\t" +
                            "${subtable.format.value}\t${subtable.isUnicode}",
                    )
                    subtable.forEachCodePoint { codePoint ->
                        appendLine("codepoint\t$index\t$codePoint\t${subtable.glyphId(codePoint)?.value ?: "none"}")
                    }
                }
                appendLine("mapping\t65\t${face.glyphId(65)?.value ?: "none"}")
            }

        assertEquals(EXPECTED_ORACLE, output)
    }

    private companion object {
        private val EXPECTED_ORACLE =
            "schema\t1\n" +
                "upstream\t6e75b3c539ef6bfbacb020aaa3226414ddea7ce5\n" +
                "collection\tnone\n" +
                "face_index\t0\n" +
                "face\tok\n" +
                "units_per_em\t1000\n" +
                "bounds\t-50\t-200\t1200\t900\n" +
                "hhea\t800\t-200\t100\n" +
                "glyph_count\t3\n" +
                "table\tcmap\t44\n" +
                "table\thead\t54\n" +
                "table\thhea\t36\n" +
                "table\thmtx\tnone\n" +
                "table\tmaxp\t6\n" +
                "cmap_count\t1\n" +
                "subtable\t0\tWindows\t1\t4\ttrue\n" +
                "codepoint\t0\t65\t1\n" +
                "mapping\t65\t1\n"
    }
}
