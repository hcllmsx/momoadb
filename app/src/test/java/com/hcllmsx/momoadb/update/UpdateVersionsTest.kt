package com.hcllmsx.momoadb.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionsTest {
    @Test
    fun normalizeStripsPrefixAndValidates() {
        assertEquals("26.11.16", UpdateVersions.normalize("v26.11.16"))
        assertEquals("26.11.16", UpdateVersions.normalize(" 26.11.16 "))
        assertEquals("26.11.16.21", UpdateVersions.normalize("V26.11.16.21"))
        assertNull(UpdateVersions.normalize(null))
        assertNull(UpdateVersions.normalize(""))
        assertNull(UpdateVersions.normalize("latest"))
        assertNull(UpdateVersions.normalize("v26"))
        assertNull(UpdateVersions.normalize("v26.11.16-beta"))
        assertNull(UpdateVersions.normalize("<html>not found</html>"))
    }

    @Test
    fun isNewerComparesEachSegmentNumerically() {
        assertTrue(UpdateVersions.isNewer("26.11.17", "26.11.16"))
        assertTrue(UpdateVersions.isNewer("26.12.0", "26.11.16"))
        assertTrue(UpdateVersions.isNewer("27.0.0", "26.11.16"))
        assertFalse(UpdateVersions.isNewer("26.11.16", "26.11.16"))
        assertFalse(UpdateVersions.isNewer("26.11.15", "26.11.16"))
        // 字典序会把 26.9.9 判成比 26.10.1 新，这里必须按整型比较。
        assertTrue(UpdateVersions.isNewer("26.10.1", "26.9.9"))
    }

    @Test
    fun isNewerHandlesDifferentSegmentCounts() {
        assertTrue(UpdateVersions.isNewer("26.11.16.1", "26.11.16"))
        assertFalse(UpdateVersions.isNewer("26.11.16", "26.11.16.0"))
    }
}
