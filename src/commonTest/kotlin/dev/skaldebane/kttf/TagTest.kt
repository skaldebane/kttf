package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TagTest {
    @Test
    fun roundTripsFourByteText() {
        val tag = Tag.parse("head")

        assertEquals("head", tag.toString())
        assertEquals(tag, Tag.of('h', 'e', 'a', 'd'))
    }

    @Test
    fun rejectsInvalidText() {
        assertNull(Tag.parseOrNull("abc"))
        assertNull(Tag.parseOrNull("abcde"))
        assertNull(Tag.parseOrNull("aĀcd"))
        assertFailsWith<IllegalArgumentException> { Tag.parse("abc") }
    }
}
