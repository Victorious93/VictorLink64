package org.victoriousframework.victorlink64.shizuku

/**
 * Optional performance tweaks that need Shizuku (shell-level access). Everything here is off by default and the
 * app works the same without it. Victorious Framework.
 */
enum class Tweak(
    val id: String,
    val title: String,
    /** Plain-language description shown in Settings. */
    val plain: String,
    val minSdk: Int,
    /** True: stays applied until switched off. False: applied when a game starts, undone when it ends. */
    val persistent: Boolean,
) {
    GAME_MODE(
        "game_mode", "Game mode: performance",
        "Tells Android this app is a game that should favour speed over battery while you play. It goes back to the standard mode when you stop.",
        31, false,
    ),
    BATTERY(
        "battery", "Don't let Android slow it down in the background",
        "Exempts VictorLink64 from battery optimization, so Android is less likely to pause or throttle it. Stays on until you switch it off here.",
        23, true,
    ),
    REFRESH(
        "refresh", "Fastest screen refresh rate",
        "Asks the display for its highest refresh rate while you play, then puts your usual setting back.",
        30, false,
    ),
    SUSTAINED(
        "sustained", "Steady speed (sustained performance)",
        "Asks the phone for steady speed instead of short bursts, which can reduce slowdowns when it warms up. Some phones don't support it.",
        31, false,
    );

    companion object {
        fun fromId(id: String): Tweak? = entries.firstOrNull { it.id == id }
    }
}

data class ShellResult(val output: String, val exit: Int) {
    /** Android shell commands often print errors yet exit 0, so look at the text too. */
    val ok: Boolean
        get() = exit == 0 && !output.contains("Exception", ignoreCase = true) &&
            !output.contains("Unknown command", ignoreCase = true) && !output.contains("Error:", ignoreCase = true)
}

interface Shell {
    fun exec(command: String): ShellResult
}

/** Tiny key-value memory for values that must survive until a tweak is undone. */
interface TweakMemory {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

sealed class Outcome {
    data object Applied : Outcome()
    data class Skipped(val why: String) : Outcome()
    data class Failed(val why: String) : Outcome()
}

/** Issues the shell commands for each [Tweak]. Pure apart from [shell], so it is unit-tested with a fake shell. */
class TweakRunner(
    private val shell: Shell,
    private val pkg: String,
    private val memory: TweakMemory,
    private val sdk: Int,
    private val peakRefreshHz: Float,
) {
    init { require(Regex("[A-Za-z0-9._]+").matches(pkg)) { "unsafe package name" } }

    fun apply(t: Tweak): Outcome {
        if (sdk < t.minSdk) return Outcome.Skipped("Needs Android ${androidName(t.minSdk)} or newer")
        return when (t) {
            Tweak.GAME_MODE -> run("cmd game mode performance $pkg")
            Tweak.SUSTAINED -> run("cmd power set-fixed-performance-mode-enabled true")
            Tweak.BATTERY -> {
                val already = shell.exec("cmd deviceidle whitelist").output.contains(",$pkg,")
                memory.put("battery.preexisting", if (already) "1" else null)
                if (already) Outcome.Skipped("Already exempt") else run("cmd deviceidle whitelist +$pkg")
            }
            Tweak.REFRESH -> {
                val hz = peakRefreshHz.toInt()
                if (hz <= 60) return Outcome.Skipped("This display has no faster mode")
                memory.put("refresh.min", shell.exec("settings get system min_refresh_rate").output.trim())
                memory.put("refresh.peak", shell.exec("settings get system peak_refresh_rate").output.trim())
                val a = run("settings put system min_refresh_rate $hz")
                if (a is Outcome.Applied) run("settings put system peak_refresh_rate $hz") else a
            }
        }
    }

    fun revert(t: Tweak): Outcome {
        if (sdk < t.minSdk) return Outcome.Skipped("Not applicable")
        return when (t) {
            Tweak.GAME_MODE -> run("cmd game mode standard $pkg")
            Tweak.SUSTAINED -> run("cmd power set-fixed-performance-mode-enabled false")
            Tweak.BATTERY -> {
                if (memory.get("battery.preexisting") == "1") { memory.put("battery.preexisting", null); Outcome.Skipped("It was exempt before; left as is") }
                else run("cmd deviceidle whitelist -$pkg")
            }
            Tweak.REFRESH -> {
                val min = memory.get("refresh.min") ?: return Outcome.Skipped("Nothing to restore")
                val peak = memory.get("refresh.peak") ?: return Outcome.Skipped("Nothing to restore")
                val a = restoreSetting("min_refresh_rate", min)
                val b = restoreSetting("peak_refresh_rate", peak)
                memory.put("refresh.min", null); memory.put("refresh.peak", null)
                if (a is Outcome.Applied) b else a
            }
        }
    }

    private fun restoreSetting(key: String, previous: String): Outcome =
        if (Regex("[0-9]+(\\.[0-9]+)?").matches(previous)) run("settings put system $key $previous") else run("settings delete system $key")

    private fun run(cmd: String): Outcome {
        val r = shell.exec(cmd)
        return if (r.ok) Outcome.Applied else Outcome.Failed(r.output.trim().ifEmpty { "exit ${r.exit}" }.take(160))
    }

    private fun androidName(sdk: Int) = when (sdk) { 23 -> "6"; 30 -> "11"; 31 -> "12"; else -> sdk.toString() }
}
