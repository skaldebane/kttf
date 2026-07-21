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
            """
            schema	1
            upstream	6e75b3c539ef6bfbacb020aaa3226414ddea7ce5
            collection	none
            face_index	0
            face	ok
            units_per_em	1000
            bounds	-50	-200	1200	900
            hhea	800	-200	100
            glyph_count	3
            table	cmap	44
            table	head	54
            table	hhea	36
            table	hmtx	none
            table	maxp	6
            cmap_count	1
            subtable	0	Windows	1	4	true
            codepoint	0	65	1
            mapping	65	1
            """.trimIndent() + "\n"
    }
}
