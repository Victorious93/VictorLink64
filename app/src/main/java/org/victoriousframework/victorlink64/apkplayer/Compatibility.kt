package org.victoriousframework.victorlink64.apkplayer

/** The CPU types a phone reports, copied from `Build` so the rules below can be tested on a laptop. */
data class DeviceCpu(
    val supported32: List<String>,
    val supported64: List<String>,
    val sdkInt: Int,
) {
    /** True when the phone lists an ARM 32-bit type (armeabi-v7a / armeabi). */
    val has32BitArm get() = supported32.any { it.startsWith("armeabi") }
    val has64BitArm get() = supported64.any { it == "arm64-v8a" }
}

/** Which kind of machine code an app carries. */
enum class AppBits { NO_NATIVE_CODE, ARM32, ARM64, ARM32_AND_64, NOT_ARM }

enum class Severity { GOOD, INFO, PROBLEM }

/** Whether the 32-bit helper app (see docs/APK-Player-Design.md) is present, and trusted. */
enum class HelperState { READY, NOT_INSTALLED, UNTRUSTED, TOO_OLD }

data class Assessment(
    val bits: AppBits,
    val severity: Severity,
    val headline: String,
    val detail: String,
    /** Extra plain-language notes (old Android version, missing features); never block launching. */
    val notes: List<String>,
    /** Short label for the primary button. The button is never disabled by this assessment. */
    val actionLabel: String,
)

/**
 * Turns an APK's contents and the phone's capabilities into a plain-language status.
 * This is advice only. Nothing here prevents the user from trying to launch an app.
 */
object Compatibility {
    private val ARM32_ABIS = setOf("armeabi", "armeabi-v7a")

    fun bitsOf(abis: Set<String>): AppBits {
        if (abis.isEmpty()) return AppBits.NO_NATIVE_CODE
        val has32 = abis.any { it in ARM32_ABIS }
        val has64 = "arm64-v8a" in abis
        return when {
            has32 && has64 -> AppBits.ARM32_AND_64
            has32 -> AppBits.ARM32
            has64 -> AppBits.ARM64
            else -> AppBits.NOT_ARM
        }
    }

    fun assess(info: ApkInfo, device: DeviceCpu, helper: HelperState): Assessment {
        val bits = bitsOf(info.nativeAbis)
        val notes = versionNotes(info.manifest, device)
        val phoneLabel = "Your phone"

        val (severity, headline, detail, action) = when (bits) {
            AppBits.NOT_ARM -> Verdict(
                Severity.PROBLEM,
                "This app is not built for phone chips",
                "Its native code is for ${info.nativeAbis.joinToString(", ")}, not ARM. It cannot run on a phone, " +
                    "and no translator planned for this project covers that.",
                "Try anyway",
            )
            AppBits.ARM64 -> Verdict(
                Severity.PROBLEM,
                "This app needs 64-bit support",
                "It only contains 64-bit code (arm64-v8a). The first release of the APK Player runs apps in a " +
                    "32-bit helper, so 64-bit-only apps are not supported yet.",
                "Try anyway",
            )
            AppBits.NO_NATIVE_CODE, AppBits.ARM32, AppBits.ARM32_AND_64 -> needs32(bits, device, helper, phoneLabel)
        }
        return Assessment(bits, severity, headline, detail, notes, action)
    }

    private data class Verdict(val severity: Severity, val headline: String, val detail: String, val action: String)

    private fun needs32(bits: AppBits, device: DeviceCpu, helper: HelperState, phone: String): Verdict {
        val kind = when (bits) {
            AppBits.NO_NATIVE_CODE -> "This app has no native code, so it is not tied to one chip type."
            AppBits.ARM32_AND_64 -> "This app contains both 32-bit and 64-bit code; it will use the 32-bit code."
            else -> "This app needs 32-bit support."
        }
        if (!device.has32BitArm) {
            return Verdict(
                Severity.PROBLEM,
                if (bits == AppBits.NO_NATIVE_CODE) "No container for 64-bit-only phones yet" else "Your phone does not have 32-bit support",
                "$kind $phone only lists 64-bit chip types (${device.supported64.joinToString(", ").ifEmpty { "none" }}), " +
                    "so the 32-bit helper cannot run here. Translation for 64-bit-only phones is planned (phase 2) but is not in this version.",
                "Try anyway",
            )
        }
        return when (helper) {
            HelperState.READY -> Verdict(
                Severity.GOOD,
                "Ready: your phone has 32-bit support",
                "$kind $phone supports 32-bit apps, so it runs natively at full speed inside the VictorLink64 32-bit helper. " +
                    "It stays in a private sandbox and is not installed on your phone.",
                "Play",
            )
            HelperState.NOT_INSTALLED -> Verdict(
                Severity.INFO,
                "One more step: install the 32-bit helper",
                "$kind $phone supports 32-bit apps. Android runs each app in either 32-bit or 64-bit mode, so " +
                    "VictorLink64 uses a small companion app, \"VictorLink64 32-bit Helper\", to run it. Install it once (it comes from the same download as VictorLink64).",
                "Set up helper",
            )
            HelperState.UNTRUSTED -> Verdict(
                Severity.PROBLEM,
                "The 32-bit helper on this phone is not trusted",
                "A helper app is installed but it was not signed with the same key as this VictorLink64, so your file will not be sent to it. " +
                    "Uninstall it and install the helper from the same download as this app.",
                "How to fix",
            )
            HelperState.TOO_OLD -> Verdict(
                Severity.INFO,
                "Update the 32-bit helper",
                "The installed helper speaks an older protocol than this VictorLink64. Install the helper from the same download as this app.",
                "How to fix",
            )
        }
    }

    fun versionNotes(m: ManifestInfo, device: DeviceCpu): List<String> {
        val notes = ArrayList<String>()
        if (m.minSdk > device.sdkInt) {
            notes.add("It asks for Android ${androidName(m.minSdk)} or newer; this phone has Android ${androidName(device.sdkInt)}.")
        }
        if (m.targetSdk in 1..22) {
            notes.add(
                "It was written for Android ${androidName(m.targetSdk)}. Old apps often still start, but things like " +
                    "permission prompts, the Menu button, old graphics drivers or old storage rules can misbehave."
            )
        }
        return notes
    }

    /** "2.3" style label for common API levels, falling back to "API n". */
    fun androidName(api: Int): String = when (api) {
        1, 2, 3 -> "1.x"
        4 -> "1.6"
        5, 6, 7 -> "2.1"
        8 -> "2.2"
        9, 10 -> "2.3"
        11, 12, 13 -> "3.x"
        14, 15 -> "4.0"
        16 -> "4.1"
        17 -> "4.2"
        18 -> "4.3"
        19, 20 -> "4.4"
        21, 22 -> "5.x"
        23 -> "6.0"
        24, 25 -> "7.x"
        26, 27 -> "8.x"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31, 32 -> "12"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> "API $api"
    }
}
