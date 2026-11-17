package org.victoriousframework.victorlink64.apkplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Victorious Framework: the plain-language status shown for each kind of app on each kind of phone. */
class CompatibilityTest {
    private fun manifest(min: Int = 9, target: Int = 10) = ManifestInfo(
        "x.y", 1, "1", min, target, "App", 0, 0, emptyList(), "x.y.Main",
    )

    private fun apk(vararg abis: String, min: Int = 9, target: Int = 10) =
        ApkInfo(manifest(min, target), abis.toSet(), true, 1000)

    private val phone32 = DeviceCpu(listOf("armeabi-v7a", "armeabi"), listOf("arm64-v8a"), 36)
    private val phone64only = DeviceCpu(emptyList(), listOf("arm64-v8a"), 36)

    @Test fun bitsFromAbis() {
        assertEquals(AppBits.NO_NATIVE_CODE, Compatibility.bitsOf(emptySet()))
        assertEquals(AppBits.ARM32, Compatibility.bitsOf(setOf("armeabi")))
        assertEquals(AppBits.ARM32, Compatibility.bitsOf(setOf("armeabi-v7a", "x86")))
        assertEquals(AppBits.ARM64, Compatibility.bitsOf(setOf("arm64-v8a")))
        assertEquals(AppBits.ARM32_AND_64, Compatibility.bitsOf(setOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(AppBits.NOT_ARM, Compatibility.bitsOf(setOf("x86", "x86_64")))
    }

    @Test fun the32BitGameOnAPhoneWith32BitSupportAndHelperIsReady() {
        val a = Compatibility.assess(apk("armeabi-v7a"), phone32, HelperState.READY)
        assertEquals(Severity.GOOD, a.severity)
        assertEquals("Play", a.actionLabel)
        assertTrue(a.headline.contains("32-bit"))
        assertTrue(a.detail.contains("full speed"))
        assertTrue(a.detail.contains("not installed on your phone"))
    }

    @Test fun missingHelperExplainsTheOneMoreStep() {
        val a = Compatibility.assess(apk("armeabi-v7a"), phone32, HelperState.NOT_INSTALLED)
        assertEquals(Severity.INFO, a.severity)
        assertEquals("Set up helper", a.actionLabel)
        assertTrue(a.detail.contains("32-bit Helper"))
    }

    @Test fun untrustedAndOutdatedHelpersGetClearAdvice() {
        assertEquals(Severity.PROBLEM, Compatibility.assess(apk("armeabi-v7a"), phone32, HelperState.UNTRUSTED).severity)
        assertTrue(Compatibility.assess(apk("armeabi-v7a"), phone32, HelperState.UNTRUSTED).detail.contains("same key"))
        assertTrue(Compatibility.assess(apk("armeabi-v7a"), phone32, HelperState.TOO_OLD).headline.contains("Update"))
    }

    @Test fun a64BitOnlyPhoneSaysTranslationIsComingNotAvailable() {
        val a = Compatibility.assess(apk("armeabi-v7a"), phone64only, HelperState.NOT_INSTALLED)
        assertEquals(Severity.PROBLEM, a.severity)
        assertTrue(a.headline.contains("does not have 32-bit support"))
        assertTrue(a.detail.contains("phase 2"))
        assertTrue(a.detail.contains("not in this version"))
    }

    @Test fun nothingEverBlocksTryingAnyway() {
        val cases = listOf(
            apk("arm64-v8a"), apk("x86"), apk("armeabi-v7a"), apk(), apk("armeabi", "arm64-v8a"),
        )
        for (info in cases) for (dev in listOf(phone32, phone64only)) for (h in HelperState.entries) {
            val a = Compatibility.assess(info, dev, h)
            assertTrue("every state offers an action: $info $dev $h", a.actionLabel.isNotBlank())
            assertTrue(a.headline.isNotBlank() && a.detail.isNotBlank())
        }
        assertEquals("Try anyway", Compatibility.assess(apk("arm64-v8a"), phone32, HelperState.READY).actionLabel)
        assertEquals("Try anyway", Compatibility.assess(apk("x86"), phone32, HelperState.READY).actionLabel)
    }

    @Test fun javaOnlyAppsUseTheSameHelper() {
        val ok = Compatibility.assess(apk(), phone32, HelperState.READY)
        assertEquals(Severity.GOOD, ok.severity)
        assertTrue(ok.detail.contains("no native code"))
        assertEquals(Severity.PROBLEM, Compatibility.assess(apk(), phone64only, HelperState.READY).severity)
    }

    @Test fun bothBitnessesUseThe32BitCode() {
        val a = Compatibility.assess(apk("arm64-v8a", "armeabi-v7a"), phone32, HelperState.READY)
        assertEquals(Severity.GOOD, a.severity)
        assertTrue(a.detail.contains("will use the 32-bit code"))
    }

    @Test fun oldAndTooNewAppsGetNotesButStillRun() {
        val old = Compatibility.assess(apk("armeabi-v7a", min = 9, target = 10), phone32, HelperState.READY)
        assertTrue(old.notes.any { it.contains("Android 2.3") })
        val needsNewer = Compatibility.assess(apk("armeabi-v7a", min = 37, target = 37), phone32, HelperState.READY)
        assertTrue(needsNewer.notes.any { it.contains("asks for Android") })
        assertEquals("Play", needsNewer.actionLabel)
        val modern = Compatibility.assess(apk("armeabi-v7a", min = 26, target = 34), phone32, HelperState.READY)
        assertTrue(modern.notes.isEmpty())
    }

    @Test fun androidNamesAreHumanReadable() {
        assertEquals("2.3", Compatibility.androidName(10))
        assertEquals("4.0", Compatibility.androidName(14))
        assertEquals("16", Compatibility.androidName(36))
        assertEquals("API 99", Compatibility.androidName(99))
        assertFalse(Compatibility.androidName(1).isEmpty())
    }
}
