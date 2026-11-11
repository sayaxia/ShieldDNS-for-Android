package com.shielddns.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本比较：纯函数，不碰网络与 Android API。 */
class UpdateCheckerTest {

    @Test
    fun patchBump() {
        assertEquals(1, UpdateChecker.compareVersion("0.1.3", "0.1.2"))
        assertEquals(-1, UpdateChecker.compareVersion("0.1.2", "0.1.3"))
    }

    @Test
    fun tagPrefixIgnored() {
        assertEquals(0, UpdateChecker.compareVersion("v0.1.2", "0.1.2"))
        assertEquals(0, UpdateChecker.compareVersion("V0.1.2", "v0.1.2"))
    }

    @Test
    fun testSuffixTreatedAsSameRelease() {
        // 测试版 0.1.2v3 与正式版 0.1.2 是同一主线，不该提示升级
        assertEquals(0, UpdateChecker.compareVersion("0.1.2v3", "0.1.2"))
        assertFalse(UpdateChecker.isNewer("0.1.2v3", "0.1.2"))
    }

    @Test
    fun minorAndMajorWin() {
        assertEquals(1, UpdateChecker.compareVersion("0.2.0", "0.1.9"))
        assertEquals(1, UpdateChecker.compareVersion("1.0.0", "0.9.9"))

        assertEquals(-1, UpdateChecker.compareVersion("0.1", "0.1.5"))
    }

    @Test
    fun shortVersionFillsZero() {
        assertEquals(0, UpdateChecker.compareVersion("0.1", "0.1.0"))
        assertEquals(1, UpdateChecker.compareVersion("0.1.1", "0.1"))
    }

    @Test
    fun newerOnlyWhenStrictlyGreater() {
        assertTrue(UpdateChecker.isNewer("0.1.3", "0.1.2"))
        assertFalse(UpdateChecker.isNewer("0.1.2", "0.1.2"))
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.2"))
    }

    @Test
    fun partsParsing() {
        assertEquals(listOf(0, 1, 2), UpdateChecker.versionParts("v0.1.2"))
        assertEquals(listOf(0, 1, 2), UpdateChecker.versionParts("0.1.2-beta1"))
    }
}
