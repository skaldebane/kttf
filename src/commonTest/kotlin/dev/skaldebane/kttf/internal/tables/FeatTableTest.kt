package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatTableTest {
    @Test
    fun readsUpstreamFeatureAndSettingFixture() {
        val data =
            TestByteBuilder()
                .uint32(0x0001_0000)
                .uint16(4)
                .zeroes(6)
                .feature(0, 1, 60, 0x0000, 260)
                .feature(1, 1, 64, 0x0000, 256)
                .feature(3, 3, 68, 0x8000, 262)
                .feature(6, 2, 80, 0xC001, 258)
                .setting(0, 261)
                .setting(2, 257)
                .setting(0, 268)
                .setting(3, 264)
                .setting(4, 265)
                .setting(0, 259)
                .setting(1, 260)
                .build()
        val names = parse(data).names

        assertEquals(4, names.size)
        val feature0 = names[0]!!
        assertEquals(0, feature0.feature)
        assertEquals(1, feature0.settingNames.size)
        assertFalse(feature0.exclusive)
        assertEquals(260, feature0.nameIndex)

        val feature2 = names.find(3)!!
        assertEquals(3, feature2.settingNames.size)
        assertTrue(feature2.exclusive)
        assertEquals(FeatSettingName(3, 264), feature2.settingNames[1])

        val feature3 = names[3]!!
        assertEquals(1, feature3.defaultSettingIndex)
        assertTrue(feature3.exclusive)
        assertEquals(listOf(0, 1, 3, 6), names.map { it.feature })
    }

    @Test
    fun rejectsHeadersAndDefersBadSettingOffsetsToQueries() {
        assertNull(FeatTable.parse(ByteSlice.of(ByteArray(11))))
        assertNull(FeatTable.parse(ByteSlice.of(TestByteBuilder().uint32(0).zeroes(8).build())))

        val badOffset =
            TestByteBuilder()
                .uint32(0x0001_0000)
                .uint16(1)
                .zeroes(6)
                .feature(2, 1, 100, 0, 256)
                .build()
        val names = parse(badOffset).names
        assertEquals(1, names.size)
        assertNull(names[0])
        assertNull(names.find(2))
        assertEquals(0, names.count())
    }

    @Test
    fun settingViewsRetainTheBoundedSource() {
        val bytes =
            TestByteBuilder()
                .uint32(0x0001_0000)
                .uint16(1)
                .zeroes(6)
                .feature(1, 1, 24, 0, 256)
                .setting(2, 257)
                .build()
        val source = ByteArray(bytes.size + 2)
        bytes.copyInto(source, 1)
        val names = FeatTable.parse(ByteSlice.of(source).slice(1, bytes.size)!!)!!.names
        source[1 + 24 + 1] = 3

        assertEquals(3, names[0]?.settingNames?.get(0)?.setting)
        assertNull(names[-1])
        assertNull(names[1])
    }

    private fun parse(data: ByteArray): FeatTable = requireNotNull(FeatTable.parse(ByteSlice.of(data)))
}

private fun TestByteBuilder.feature(
    feature: Int,
    settingsCount: Int,
    settingsOffset: Int,
    flags: Int,
    nameIndex: Int,
): TestByteBuilder =
    uint16(feature)
        .uint16(settingsCount)
        .uint32(settingsOffset.toLong())
        .uint16(flags)
        .uint16(nameIndex)

private fun TestByteBuilder.setting(
    setting: Int,
    nameIndex: Int,
): TestByteBuilder = uint16(setting).uint16(nameIndex)
