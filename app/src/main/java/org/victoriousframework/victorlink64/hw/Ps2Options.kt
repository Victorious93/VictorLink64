package org.victoriousframework.victorlink64.hw

import android.content.Context

/**
 * PlayStation 2 quality choices and the core options they become. Victorious Framework.
 * The LRPS2 core reads its settings by key (libretro "core options"); hwfront.c answers those reads from the table
 * built here. Pure Kotlin apart from [Ps2Prefs], so the mapping is unit-tested on the JVM.
 */
enum class Ps2Resolution(val id: String, val multiplier: Int, val label: String, val output: String, val detail: String) {
    NATIVE("native", 1, "Native", "640×448 (the PS2's own picture)",
        "Fastest. Looks like the original console, a bit soft on a phone screen."),
    X2("2x", 2, "2× sharper", "1280×896 (between 720p and 1080p)",
        "Noticeably crisper. Needs a much faster phone than Native."),
    X3("3x", 3, "3× sharpest", "1920×1344 (about 1080p)",
        "Best picture. Only for flagship-class phones; slow on everything else.");

    companion object {
        val DEFAULT = NATIVE
        fun fromId(id: String?): Ps2Resolution = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

object Ps2Options {
    /**
     * Core option key/value pairs for one launch. Only keys the LRPS2 core defines (libretro/ps2,
     * libretro/libretro_core_options.h). [bios] is the file name inside the BIOS folder; without it the core would
     * refuse to start, because this frontend (unlike RetroArch) does not apply an option's default by itself.
     */
    fun build(resolution: Ps2Resolution, bios: String): Map<String, String> = linkedMapOf(
        "pcsx2_bios" to bios,
        "pcsx2_renderer" to "Auto",
        // atoi() of the value picks the multiplier in the core; "1x (Native)" is the label the core itself uses.
        "pcsx2_upscale_multiplier" to if (resolution == Ps2Resolution.NATIVE) "1x (Native)" else "${resolution.multiplier}x",
        "pcsx2_fastboot" to "enabled",
        // Games that boot the pad in digital mode would otherwise ignore the on-screen sticks until ANALOG is pressed.
        "pcsx2_analog_mode1" to "enabled",
    )
}

/** What the phone can reasonably do with PlayStation 2, from the facts Android gives us. */
object Ps2Performance {
    enum class Level { UNSUPPORTED, ENTRY, MID, HIGH }
    data class Assessment(val level: Level, val headline: String, val advice: String)

    const val GENERAL_NOTE =
        "PlayStation 2 is by far the heaviest system VictorLink64 plays. It emulates a 2000-era console with " +
        "several processors, so it needs a fast phone. Many games will be slow on entry-level and mid-range phones, " +
        "even at Native resolution."

    const val ENTRY_CHIP_NOTE =
        "Entry-level phones, for example ones with a MediaTek Dimensity 6300, may run PlayStation 2 slowly or not " +
        "at all. VictorLink64 has not been tested on one yet, so treat PS2 there as an experiment."

    /** [totalRamMb] is what Android reports (a phone sold as 8 GB shows about 7.4 GB). */
    fun assess(totalRamMb: Long, is64Bit: Boolean): Assessment = when {
        !is64Bit -> Assessment(Level.UNSUPPORTED, "This phone can't run PlayStation 2",
            "PlayStation 2 needs a 64-bit ARM (arm64-v8a) phone.")
        totalRamMb < 5_900 -> Assessment(Level.ENTRY, "Expect PlayStation 2 to be slow on this phone",
            "This phone has about ${gb(totalRamMb)} of memory. Stay on Native resolution and try lighter games first.")
        totalRamMb < 7_800 -> Assessment(Level.MID, "PlayStation 2 may struggle on this phone",
            "This phone has about ${gb(totalRamMb)} of memory. Native is the safe choice; try 2× only for light games.")
        else -> Assessment(Level.HIGH, "This phone has plenty of memory for PlayStation 2",
            "Memory is not the limit, speed is: start on Native and raise the picture only if the game runs full speed.")
    }

    private fun gb(mb: Long) = "%.1f GB".format(mb / 1024.0)

    /** Warning shown when the chosen picture size is more than the phone likely handles; null when fine. */
    fun resolutionWarning(level: Level, r: Ps2Resolution): String? = when {
        level == Level.UNSUPPORTED -> null
        r == Ps2Resolution.NATIVE -> null
        level == Level.ENTRY -> "${r.label} is very likely too heavy for this phone. Use Native."
        level == Level.MID && r == Ps2Resolution.X3 -> "3× is probably too heavy for this phone. Try 2× or Native."
        else -> null
    }
}

/** Saved PS2 choices (resolution, which BIOS to use when several are imported). */
class Ps2Prefs(context: Context) {
    private val sp = context.getSharedPreferences("victorlink64_ps2", Context.MODE_PRIVATE)
    var resolution: Ps2Resolution
        get() = Ps2Resolution.fromId(sp.getString("resolution", null))
        set(v) { sp.edit().putString("resolution", v.id).apply() }
    var bios: String?
        get() = sp.getString("bios", null)
        set(v) { sp.edit().putString("bios", v).apply() }
}
