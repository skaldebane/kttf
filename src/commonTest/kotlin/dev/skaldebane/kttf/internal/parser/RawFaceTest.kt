package dev.skaldebane.kttf.internal.parser

import dev.skaldebane.kttf.TestByteBuilder
import kotlin.test.Test
import kotlin.test.assertNull

class RawFaceTest {
    @Test
    fun rejectsCollectionFaceOffsetBeforeEndOfOffsetArray() {
        val data =
            TestByteBuilder()
                .uint32(0x74746366)
                .uint32(0x00010000)
                .uint32(1)
                .uint32(4)
                .build()

        assertNull(RawFace.parse(data, 0))
    }
}
