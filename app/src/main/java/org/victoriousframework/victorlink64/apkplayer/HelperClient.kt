package org.victoriousframework.victorlink64.apkplayer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import paulscode.android.mupen64plusae.BuildConfig

/** What the helper answered, or why there was no answer. */
data class HelperReply(val status: String, val message: String, val extras: Map<String, String> = emptyMap()) {
    val isLaunched get() = status == HelperProtocol.STATUS_LAUNCHED
}

/** Talks to the 32-bit helper app. All requests are explicit intents to one fixed package. */
object HelperClient {
    const val SHARE_AUTHORITY_SUFFIX = ".apkshare"

    val helperPackage: String get() = BuildConfig.HELPER_PACKAGE

    fun deviceCpu(): DeviceCpu = DeviceCpu(
        supported32 = Build.SUPPORTED_32_BIT_ABIS.toList(),
        supported64 = Build.SUPPORTED_64_BIT_ABIS.toList(),
        sdkInt = Build.VERSION.SDK_INT,
    )

    @Suppress("DEPRECATION")
    fun state(context: Context): HelperState {
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(helperPackage, PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            return HelperState.NOT_INSTALLED
        }
        if (pm.checkSignatures(context.packageName, helperPackage) != PackageManager.SIGNATURE_MATCH) {
            return HelperState.UNTRUSTED
        }
        val protocol = info.metaData?.getInt(HelperProtocol.META_PROTOCOL, 0) ?: 0
        return if (protocol == HelperProtocol.VERSION) HelperState.READY else HelperState.TOO_OLD
    }

    fun pingIntent(): Intent = Intent(HelperProtocol.ACTION_PING).setPackage(helperPackage)

    /** The APK is shared by content URI with a one-time read grant; the helper copies it into its own storage. */
    fun runIntent(context: Context, apk: StoredApk): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + SHARE_AUTHORITY_SUFFIX, apk.file)
        return Intent(HelperProtocol.ACTION_RUN_APK)
            .setPackage(helperPackage)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .putExtra(HelperProtocol.EXTRA_DISPLAY_NAME, apk.displayName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun parseReply(resultCode: Int, data: Intent?): HelperReply {
        if (data == null) {
            return HelperReply(
                HelperProtocol.STATUS_ERROR,
                if (resultCode == android.app.Activity.RESULT_CANCELED) "The helper closed without an answer." else "The helper sent no answer.",
            )
        }
        val extras = HashMap<String, String>()
        data.extras?.keySet()?.forEach { k -> data.extras?.get(k)?.let { extras[k] = it.toString() } }
        return HelperReply(
            status = data.getStringExtra(HelperProtocol.RESULT_STATUS) ?: HelperProtocol.STATUS_ERROR,
            message = data.getStringExtra(HelperProtocol.RESULT_MESSAGE).orEmpty(),
            extras = extras,
        )
    }

    /** Plain-language summary of a ping reply for the "Test helper" button. */
    fun describePing(reply: HelperReply): String {
        if (reply.status == HelperProtocol.STATUS_REJECTED) return reply.message
        if (reply.status != HelperProtocol.STATUS_OK) return reply.message.ifEmpty { "The helper did not answer." }
        val is64 = reply.extras[HelperProtocol.RESULT_IS_64_BIT] == "true"
        val bits = if (is64) "64-bit" else "32-bit"
        val verdict = if (is64) {
            "The helper started as 64-bit, so it cannot run 32-bit apps on this phone."
        } else {
            "The helper started as 32-bit, which is what old 32-bit apps need."
        }
        return "$verdict\nProcess ${reply.extras[HelperProtocol.RESULT_PID]} ran as $bits.\n" +
            "Chip types: ${reply.extras[HelperProtocol.RESULT_ABIS]}\n${reply.extras[HelperProtocol.RESULT_ENGINE]}"
    }
}
