package com.hcllmsx.momoadb.ui.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallApkFileNameTest {
    @Test
    fun acceptsStandardApkNames() {
        assertTrue(isApkFileName("momoadb-v26.11.16.apk"))
        assertTrue(isApkFileName("APP.APK"))
        assertTrue(isApkFileName("应用.apk"))
    }

    @Test
    fun acceptsNumericSuffixAppendedByDownloadManagers() {
        assertTrue(isApkFileName("momoadb-v26.11.16.apk.1"))
        assertTrue(isApkFileName("momoadb-v26.11.16.apk.2"))
        assertTrue(isApkFileName("app.APK.10"))
    }

    @Test
    fun rejectsNonApkFiles() {
        for (name in listOf("", "app", "app.zip", "apk", "app.apk.bak", "app.apk.", "app.apk.1.zip")) {
            assertFalse(name, isApkFileName(name))
        }
    }
}
