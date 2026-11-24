package com.focusguard.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-JVM tests for the Updater version logic (no Android framework needed). */
class UpdaterVersionTest {

    @Test
    fun `parse handles plain and v-prefixed versions`() {
        assertEquals("2.9", Updater.normalizeVersion("2.9"))
        assertEquals("2.9", Updater.normalizeVersion("v2.9"))
        assertEquals("3.10.1", Updater.normalizeVersion("V3.10.1"))
        assertEquals("3.0", Updater.normalizeVersion("3.0"))
    }

    @Test
    fun `parse rejects malformed tags`() {
        assertNull(Updater.normalizeVersion("latest"))
        assertNull(Updater.normalizeVersion("2"))
        assertNull(Updater.normalizeVersion("2."))
        assertNull(Updater.normalizeVersion("2.9-beta"))
        assertNull(Updater.normalizeVersion("2.9.0.1"))
        assertNull(Updater.normalizeVersion("02.9"))
        assertNull(Updater.normalizeVersion(""))
    }

    @Test
    fun `isNewer compares numerically per segment`() {
        assertTrue(Updater.isNewer("2.10", "2.9"))
        assertTrue(Updater.isNewer("2.9.1", "2.9"))
        assertTrue(Updater.isNewer("2.9", "2.8"))
        assertFalse(Updater.isNewer("2.9", "2.10"))
        assertFalse(Updater.isNewer("2.9", "2.9"))
        assertFalse(Updater.isNewer("2.8", "2.9"))
        assertFalse(Updater.isNewer("garbage", "2.9"))
        assertFalse(Updater.isNewer("2.9", "garbage"))
    }

    @Test
    fun `release notes versionCode declaration is read`() {
        assertEquals(9L, Updater.releaseNotesVersionCode("What changed\n\nversionCode: 9\n"))
        assertEquals(12L, Updater.releaseNotesVersionCode("versionCode=12"))
        assertNull(Updater.releaseNotesVersionCode("no declaration here"))
        assertNull(Updater.releaseNotesVersionCode("versionCode: abc"))
        assertNull(Updater.releaseNotesVersionCode("prefix versionCode: 9"))
    }

    @Test
    fun `sha256 format check`() {
        val h = "a".repeat(64)
        assertTrue(Updater.sha256Pattern.matches(h))
        assertTrue(Updater.sha256Pattern.matches("A".repeat(64)))
        assertFalse(Updater.sha256Pattern.matches("a".repeat(63)))
        assertFalse(Updater.sha256Pattern.matches("g".repeat(64)))
    }
}
