package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FvarTableTest {
    @Test
    fun parsesStridedAxesAndUsesUpstreamBoundsAndFlags() {
        val bytes =
            fvar(
                axisSize = 24,
                axes =
                    listOf(
                        axis("wght", 100f, 400f, 900f, flags = 0x0008, nameId = 256, size = 24),
                        axis("wdth", 200f, 100f, 50f, flags = 0xFFF7, nameId = 257, size = 24),
                    ),
            )
        val table = assertNotNull(FvarTable.parse(ByteSlice.of(bytes)))

        assertEquals(2, table.axes.size)
        val weight = assertNotNull(table.axes[0])
        assertEquals(Tag.parse("wght"), weight.tag)
        assertEquals(100f, weight.minValue)
        assertEquals(400f, weight.defaultValue)
        assertEquals(900f, weight.maxValue)
        assertEquals(256, weight.nameId)
        assertTrue(weight.hidden)

        val width = assertNotNull(table.axes[1])
        assertEquals(100f, width.minValue)
        assertEquals(100f, width.defaultValue)
        assertEquals(100f, width.maxValue)
        assertFalse(width.hidden)
        assertNull(table.axes[-1])
        assertNull(table.axes[2])
        assertEquals(listOf("wght", "wdth"), table.axes.map { it.tag.toString() })
    }

    @Test
    fun normalizesAxisValuesWithPinnedTruncatingSemantics() {
        val table =
            FvarTable.parse(
                ByteSlice.of(fvar(axes = listOf(axis("wght", 100f, 400f, 900f, nameId = 256)))),
            )!!
        val axis = table.axes[0]!!

        assertEquals(-16384, axis.normalize(-100f).rawValue)
        assertEquals(-8192, axis.normalize(250f).rawValue)
        assertEquals(0, axis.normalize(400f).rawValue)
        assertEquals(8192, axis.normalize(650f).rawValue)
        assertEquals(16384, axis.normalize(1000f).rawValue)
    }

    @Test
    fun parsesNamedInstancesWithOptionalPostScriptNames() {
        val axes =
            listOf(
                axis("wght", 100f, 400f, 900f, nameId = 256),
                axis("wdth", 50f, 100f, 200f, nameId = 257),
            )
        val withPostScriptName = instance(300, 1, floatArrayOf(700f, 125f), 301, size = 16)
        val missingPostScriptName = instance(302, 0, floatArrayOf(400f, 100f), 0xFFFF, size = 16)
        val table =
            assertNotNull(
                FvarTable.parse(
                    ByteSlice.of(
                        fvar(
                            axes = axes,
                            instanceSize = 16,
                            instances = listOf(withPostScriptName, missingPostScriptName),
                        ),
                    ),
                ),
            )

        assertEquals(2, table.namedInstances.size)
        val bold = assertNotNull(table.namedInstances[0])
        assertEquals(300, bold.subfamilyNameId)
        assertEquals(1, bold.flags)
        assertEquals(listOf(700f, 125f), bold.coordinates.toList())
        assertEquals(301, bold.postScriptNameId)
        assertNull(table.namedInstances[1]?.postScriptNameId)
        assertNull(table.namedInstances[-1])
        assertNull(table.namedInstances[2])

        val withoutPostScriptName =
            FvarTable.parse(
                ByteSlice.of(
                    fvar(
                        axes = listOf(axes[0]),
                        instanceSize = 8,
                        instances = listOf(instance(303, 0, floatArrayOf(500f), size = 8)),
                    ),
                ),
            )!!
        assertNull(withoutPostScriptName.namedInstances[0]?.postScriptNameId)
    }

    @Test
    fun recordsAreBoundedZeroCopyViews() {
        val bytes =
            fvar(
                axes = listOf(axis("wght", 100f, 400f, 900f, nameId = 256)),
                instanceSize = 8,
                instances = listOf(instance(300, 0, floatArrayOf(400f), size = 8)),
            )
        val table = FvarTable.parse(ByteSlice.of(bytes))!!

        bytes[16] = 'W'.code.toByte()
        putFixed(bytes, 16 + 20 + 4, 650f)

        assertEquals("Wght", table.axes[0]?.tag.toString())
        assertEquals(650f, table.namedInstances[0]?.coordinates?.get(0))
    }

    @Test
    fun validatesVersionCountsStridesOffsetsAndRanges() {
        val valid = fvar(axes = listOf(axis("wght", 100f, 400f, 900f, nameId = 256)))
        assertNotNull(FvarTable.parse(ByteSlice.of(valid)))
        assertNull(FvarTable.parse(ByteSlice.of(valid.copyOf(15))))

        val wrongVersion = valid.copyOf().also { putUInt32(it, 0, 0x0002_0000) }
        val noAxes = valid.copyOf().also { putUInt16(it, 8, 0) }
        val shortAxis = valid.copyOf().also { putUInt16(it, 10, 19) }
        val headerOverlap = valid.copyOf().also { putUInt16(it, 4, 15) }
        val truncatedAxis = valid.copyOf(valid.size - 1)
        val shortInstance =
            fvar(
                axes = listOf(axis("wght", 100f, 400f, 900f, nameId = 256)),
                instanceSize = 7,
                instances = listOf(ByteArray(7)),
            )
        val truncatedInstance =
            fvar(
                axes = listOf(axis("wght", 100f, 400f, 900f, nameId = 256)),
                instanceSize = 8,
                instances = listOf(instance(300, 0, floatArrayOf(400f), size = 8)),
            ).let { it.copyOf(it.size - 1) }

        assertNull(FvarTable.parse(ByteSlice.of(wrongVersion)))
        assertNull(FvarTable.parse(ByteSlice.of(noAxes)))
        assertNull(FvarTable.parse(ByteSlice.of(shortAxis)))
        assertNull(FvarTable.parse(ByteSlice.of(headerOverlap)))
        assertNull(FvarTable.parse(ByteSlice.of(truncatedAxis)))
        assertNull(FvarTable.parse(ByteSlice.of(shortInstance)))
        assertNull(FvarTable.parse(ByteSlice.of(truncatedInstance)))
    }

    private fun fvar(
        axes: List<ByteArray>,
        axisSize: Int = 20,
        instanceSize: Int = 0,
        instances: List<ByteArray> = emptyList(),
    ): ByteArray {
        val bytes = ByteArray(16 + axes.size * axisSize + instances.size * instanceSize)
        putUInt32(bytes, 0, 0x0001_0000)
        putUInt16(bytes, 4, 16)
        putUInt16(bytes, 8, axes.size)
        putUInt16(bytes, 10, axisSize)
        putUInt16(bytes, 12, instances.size)
        putUInt16(bytes, 14, instanceSize)
        axes.forEachIndexed { index, axis -> axis.copyInto(bytes, 16 + index * axisSize) }
        val instancesOffset = 16 + axes.size * axisSize
        instances.forEachIndexed { index, instance -> instance.copyInto(bytes, instancesOffset + index * instanceSize) }
        return bytes
    }

    private fun axis(
        tag: String,
        minimum: Float,
        default: Float,
        maximum: Float,
        flags: Int = 0,
        nameId: Int,
        size: Int = 20,
    ): ByteArray {
        val bytes = ByteArray(size)
        tag.forEachIndexed { index, char -> bytes[index] = char.code.toByte() }
        putFixed(bytes, 4, minimum)
        putFixed(bytes, 8, default)
        putFixed(bytes, 12, maximum)
        putUInt16(bytes, 16, flags)
        putUInt16(bytes, 18, nameId)
        return bytes
    }

    private fun instance(
        nameId: Int,
        flags: Int,
        coordinates: FloatArray,
        postScriptNameId: Int? = null,
        size: Int,
    ): ByteArray {
        val bytes = ByteArray(size)
        putUInt16(bytes, 0, nameId)
        putUInt16(bytes, 2, flags)
        coordinates.forEachIndexed { index, value -> putFixed(bytes, 4 + index * 4, value) }
        if (postScriptNameId != null) putUInt16(bytes, 4 + coordinates.size * 4, postScriptNameId)
        return bytes
    }

    private fun putFixed(
        data: ByteArray,
        offset: Int,
        value: Float,
    ) = putUInt32(data, offset, (value * 65536f).toInt())

    private fun putUInt16(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun putUInt32(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
