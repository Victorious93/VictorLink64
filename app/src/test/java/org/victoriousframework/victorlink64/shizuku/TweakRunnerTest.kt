package org.victoriousframework.victorlink64.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakRunnerTest {
    private class FakeShell(val replies: MutableMap<String, ShellResult> = mutableMapOf()) : Shell {
        val log = mutableListOf<String>()
        override fun exec(command: String): ShellResult { log += command; return replies[command] ?: ShellResult("", 0) }
    }

    private class Mem : TweakMemory {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    private val pkg = "org.victoriousframework.victorlink64"
    private fun runner(shell: Shell, mem: TweakMemory = Mem(), sdk: Int = 36, hz: Float = 90f) = TweakRunner(shell, pkg, mem, sdk, hz)

    @Test fun gameModeAppliesAndRestoresStandard() {
        val shell = FakeShell()
        val r = runner(shell)
        assertEquals(Outcome.Applied, r.apply(Tweak.GAME_MODE))
        assertEquals(Outcome.Applied, r.revert(Tweak.GAME_MODE))
        assertEquals(listOf("cmd game mode performance $pkg", "cmd game mode standard $pkg"), shell.log)
    }

    @Test fun sustainedPerformanceTogglesThePowerService() {
        val shell = FakeShell()
        val r = runner(shell)
        r.apply(Tweak.SUSTAINED); r.revert(Tweak.SUSTAINED)
        assertEquals(listOf("cmd power set-fixed-performance-mode-enabled true", "cmd power set-fixed-performance-mode-enabled false"), shell.log)
    }

    @Test fun batteryExemptionIsAddedAndRemoved() {
        val shell = FakeShell()
        val r = runner(shell)
        assertEquals(Outcome.Applied, r.apply(Tweak.BATTERY))
        assertEquals(Outcome.Applied, r.revert(Tweak.BATTERY))
        assertTrue("cmd deviceidle whitelist +$pkg" in shell.log)
        assertTrue("cmd deviceidle whitelist -$pkg" in shell.log)
    }

    @Test fun anExistingBatteryExemptionIsLeftAlone() {
        val shell = FakeShell(mutableMapOf("cmd deviceidle whitelist" to ShellResult("user,$pkg,10250\n", 0)))
        val mem = Mem()
        val r = runner(shell, mem)
        assertTrue(r.apply(Tweak.BATTERY) is Outcome.Skipped)
        assertTrue(r.revert(Tweak.BATTERY) is Outcome.Skipped)
        assertFalse(shell.log.any { it.contains("+$pkg") || it.contains("-$pkg") })
    }

    @Test fun refreshRateRemembersAndRestoresThePreviousSettings() {
        val shell = FakeShell(mutableMapOf(
            "settings get system min_refresh_rate" to ShellResult("60.0\n", 0),
            "settings get system peak_refresh_rate" to ShellResult("null\n", 0),
        ))
        val r = runner(shell, hz = 120f)
        assertEquals(Outcome.Applied, r.apply(Tweak.REFRESH))
        assertTrue("settings put system min_refresh_rate 120" in shell.log)
        assertTrue("settings put system peak_refresh_rate 120" in shell.log)
        shell.log.clear()
        assertEquals(Outcome.Applied, r.revert(Tweak.REFRESH))
        assertEquals(listOf("settings put system min_refresh_rate 60.0", "settings delete system peak_refresh_rate"), shell.log)
    }

    @Test fun refreshRateDoesNothingOnA60HzPanel() {
        val shell = FakeShell()
        assertTrue(runner(shell, hz = 60f).apply(Tweak.REFRESH) is Outcome.Skipped)
        assertTrue(shell.log.isEmpty())
    }

    @Test fun revertWithoutAnApplyIsHarmless() {
        val shell = FakeShell()
        assertTrue(runner(shell).revert(Tweak.REFRESH) is Outcome.Skipped)
        assertTrue(shell.log.isEmpty())
    }

    @Test fun tweaksNeedingNewerAndroidAreSkippedAndRunNothing() {
        val shell = FakeShell()
        val r = runner(shell, sdk = 29)
        assertTrue(r.apply(Tweak.GAME_MODE) is Outcome.Skipped)
        assertTrue(r.apply(Tweak.SUSTAINED) is Outcome.Skipped)
        assertTrue(r.apply(Tweak.REFRESH) is Outcome.Skipped)
        assertTrue(shell.log.isEmpty())
    }

    @Test fun failuresAreReportedFromTheShellText() {
        val shell = FakeShell(mutableMapOf("cmd game mode performance $pkg" to ShellResult("Error: Unknown command: game", 0)))
        assertTrue(runner(shell).apply(Tweak.GAME_MODE) is Outcome.Failed)
        val bad = FakeShell(mutableMapOf("cmd power set-fixed-performance-mode-enabled true" to ShellResult("", 255)))
        assertTrue(runner(bad).apply(Tweak.SUSTAINED) is Outcome.Failed)
    }

    @Test fun unsafePackageNamesAreRefused() {
        var refused = false
        try { TweakRunner(FakeShell(), "x; rm -rf /", Mem(), 36, 90f) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }

    @Test fun everyTweakExplainsItselfInPlainLanguage() {
        Tweak.entries.forEach {
            assertTrue(it.id, it.plain.length > 40)
            assertFalse(it.id, it.plain.contains("adb") || it.plain.contains("shell uid"))
        }
        assertEquals(setOf(Tweak.BATTERY), Tweak.entries.filter { it.persistent }.toSet())
    }
}
