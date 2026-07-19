package dev.skaldebane.kttf.internal.tables.cff

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class CffAllocationStructureTest {
    @Test
    fun pointEmissionClassesDoNotReferencePair() {
        assertDoesNotReferencePair(CffTransform::class.java)
        assertDoesNotReferencePair(Class.forName("dev.skaldebane.kttf.internal.tables.cff.CffPath"))
    }

    private fun assertDoesNotReferencePair(type: Class<*>) {
        val resource = "/${type.name.replace('.', '/')}.class"
        val bytecode = assertNotNull(type.getResourceAsStream(resource)).use { it.readBytes() }
        assertFalse(bytecode.containsAscii("kotlin/Pair"), type.name)
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val expected = value.encodeToByteArray()
        var start = 0
        while (start <= size - expected.size) {
            var index = expected.indices.first
            while (index <= expected.lastIndex && this[start + index] == expected[index]) index++
            if (index > expected.lastIndex) return true
            start++
        }
        return false
    }
}
