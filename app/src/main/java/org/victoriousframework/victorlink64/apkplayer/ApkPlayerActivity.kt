package org.victoriousframework.victorlink64.apkplayer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.Navy
import org.victoriousframework.victorlink64.ui.NavyCard
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.NeonRed
import org.victoriousframework.victorlink64.ui.VictorTheme
import org.victoriousframework.victorlink64.ui.clip16
import java.io.File

/**
 * APK Player (phase 1): pick an APK, keep a private copy, see in plain language whether this phone can
 * run it, and hand it to the 32-bit helper. Nothing is installed on the phone.
 */
class ApkPlayerActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ApkStore(File(filesDir, "apk-player"))
        setContent { VictorTheme { ApkPlayerScreen(store, resumeTick, ::finish) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

private sealed interface Dialog {
    data class Message(val title: String, val body: String) : Dialog
    data class ConfirmRemove(val apk: StoredApk) : Dialog
    data object HelperSetup : Dialog
}

@Composable
private fun ApkPlayerScreen(store: ApkStore, resumeTick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apks by remember { mutableStateOf(emptyList<StoredApk>()) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var helper by remember { mutableStateOf(HelperState.NOT_INSTALLED) }
    val device = remember { HelperClient.deviceCpu() }

    LaunchedEffect(resumeTick) {
        helper = HelperClient.state(ctx)
        apks = withContext(Dispatchers.IO) { store.list() }
    }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { importApk(ctx, store, uri) }
            apks = withContext(Dispatchers.IO) { store.list() }
            busy = false
            outcome.exceptionOrNull()?.let { dialog = Dialog.Message("Could not add this file", it.message ?: "Unknown error") }
        }
    }

    val helperResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val reply = HelperClient.parseReply(r.resultCode, r.data)
        dialog = when {
            reply.extras.containsKey(HelperProtocol.RESULT_PROTOCOL) ->
                Dialog.Message("32-bit helper test", HelperClient.describePing(reply))
            reply.isLaunched -> null
            else -> Dialog.Message("The app did not start", reply.message.ifEmpty { "The helper reported an unknown problem." })
        }
    }

    fun startHelper(intent: Intent) {
        try {
            helperResult.launch(intent)
        } catch (e: ActivityNotFoundException) {
            dialog = Dialog.HelperSetup
        } catch (e: SecurityException) {
            dialog = Dialog.Message("Could not reach the helper", e.message ?: "Android refused the request.")
        }
    }

    Column(Modifier.fillMaxSize().background(Navy).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("APK Player", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Run an Android app in a private sandbox. It is not installed on your phone.", color = Muted, fontSize = 13.sp)
            }
            TextButton(onClick = onBack) { Text("Close", color = NeonBlue) }
        }
        Spacer(Modifier.height(12.dp))
        PhoneCard(device, helper, onTest = { startHelper(HelperClient.pingIntent()) }, onSetup = { dialog = Dialog.HelperSetup })
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { pickApk.launch(arrayOf("*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Navy),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Navy, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Copying…", fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Filled.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Choose an APK file", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Only run apps you trust. Sandboxed apps can use the internet and are not scanned. " +
                "The file is copied into VictorLink64's private storage; your original stays where it is.",
            color = Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (apks.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No apps yet. Choose an APK file to begin.", color = Muted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(apks, key = { it.id }) { apk ->
                    ApkCard(
                        apk, device, helper,
                        onPlay = { a ->
                            when (helper) {
                                HelperState.READY -> startHelper(HelperClient.runIntent(ctx, a))
                                else -> dialog = Dialog.HelperSetup
                            }
                        },
                        onRemove = { dialog = Dialog.ConfirmRemove(apk) },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is Dialog.Message -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK", color = NeonBlue) } },
            title = { Text(d.title) }, text = { Text(d.body) },
            containerColor = NavyCard, titleContentColor = Color.White, textContentColor = Muted,
        )
        is Dialog.ConfirmRemove -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.remove(d.apk.id) }
                        apks = withContext(Dispatchers.IO) { store.list() }
                        dialog = null
                    }
                }) { Text("Remove", color = NeonBlue) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep", color = Muted) } },
            title = { Text("Remove this app?") },
            text = { Text("Deletes VictorLink64's private copy of ${d.apk.displayName}. Your original file is not touched.") },
            containerColor = NavyCard, titleContentColor = Color.White, textContentColor = Muted,
        )
        Dialog.HelperSetup -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK", color = NeonBlue) } },
            title = { Text("32-bit helper") },
            text = { Text(helperSetupText(helper, device)) },
            containerColor = NavyCard, titleContentColor = Color.White, textContentColor = Muted,
        )
    }
}

private fun helperSetupText(helper: HelperState, device: DeviceCpu): String = when {
    !device.has32BitArm ->
        "Your phone only supports 64-bit apps, so the 32-bit helper cannot run on it. Translation for 64-bit-only phones is planned " +
            "(phase 2) but is not part of this version."
    helper == HelperState.NOT_INSTALLED ->
        "Old apps are 32-bit, and Android runs each app in either 32-bit or 64-bit mode. VictorLink64 therefore uses a small companion " +
            "app, \"VictorLink64 32-bit Helper\", to run them.\n\nInstall it once from the same download as this app " +
            "(VictorLink64-Box32-debug.apk), then come back here. If Android asks, allow installing from your file manager or browser. " +
            "The helper is for VictorLink64 only; the app you choose is never installed."
    helper == HelperState.UNTRUSTED ->
        "A helper app is installed, but it was signed with a different key than this VictorLink64, so your files will not be sent to it. " +
            "Uninstall \"VictorLink64 32-bit Helper\", then install the helper that came from the same download as this app."
    helper == HelperState.TOO_OLD ->
        "The installed helper is from a different version. Install the helper that came from the same download as this app."
    else -> "The helper is installed and trusted."
}

@Composable
private fun PhoneCard(device: DeviceCpu, helper: HelperState, onTest: () -> Unit, onSetup: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip16().background(NavyCard).border(1.dp, NeonBlue.copy(alpha = .4f), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text("Your phone", color = NeonBlue, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (device.has32BitArm) "Supports 32-bit apps (${device.supported32.joinToString(", ")})"
            else "64-bit only: no 32-bit support",
            color = Color.White, fontSize = 14.sp,
        )
        Text(
            "Android ${Compatibility.androidName(device.sdkInt)} · " +
                when (helper) {
                    HelperState.READY -> "32-bit helper installed"
                    HelperState.NOT_INSTALLED -> "32-bit helper not installed"
                    HelperState.UNTRUSTED -> "32-bit helper not trusted"
                    HelperState.TOO_OLD -> "32-bit helper out of date"
                },
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (helper == HelperState.READY) {
            OutlinedButton(onClick = onTest, border = androidx.compose.foundation.BorderStroke(1.dp, NeonBlue)) {
                Text("Test the 32-bit helper", color = NeonBlue)
            }
        } else if (device.has32BitArm) {
            OutlinedButton(onClick = onSetup, border = androidx.compose.foundation.BorderStroke(1.dp, NeonBlue)) {
                Text("How to set up the helper", color = NeonBlue)
            }
        }
    }
}

@Composable
private fun ApkCard(apk: StoredApk, device: DeviceCpu, helper: HelperState, onPlay: (StoredApk) -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val assessment = remember(apk, device, helper) { Compatibility.assess(apk.info, device, helper) }
    val look by produceState(PackageLook(apk.displayName, null), apk.id) {
        value = withContext(Dispatchers.IO) { loadLook(ctx, apk) }
    }
    val accent = when (assessment.severity) {
        Severity.GOOD -> NeonBlue
        Severity.INFO -> Muted
        Severity.PROBLEM -> NeonRed
    }
    val m = apk.info.manifest
    Column(
        Modifier.fillMaxWidth().clip16().background(NavyCard)
            .border(1.dp, accent.copy(alpha = .55f), RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip16().background(Navy), Alignment.Center) {
                val icon = look.icon
                if (icon != null) Image(icon, null, Modifier.size(44.dp)) else Icon(Icons.Filled.Android, null, tint = NeonBlue)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(look.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(m.packageName, color = Muted, fontSize = 11.sp)
                Text(
                    listOfNotNull(
                        m.versionName?.let { "v$it" },
                        "%.1f MB".format(apk.info.sizeBytes / 1048576f),
                        "Android ${Compatibility.androidName(m.minSdk)}+ (made for ${Compatibility.androidName(m.targetSdk)})",
                    ).joinToString(" · "),
                    color = Muted, fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Needs: " + describeBits(assessment.bits, apk.info.nativeAbis), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(assessment.headline, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(assessment.detail, color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
        assessment.notes.forEach { Text("• $it", color = Muted, fontSize = 12.sp) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onPlay(apk) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Navy),
            ) {
                if (assessment.actionLabel == "Play" || assessment.actionLabel == "Try anyway") {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                }
                Text(assessment.actionLabel, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Remove", color = Muted) }
        }
    }
}

private fun describeBits(bits: AppBits, abis: Set<String>): String = when (bits) {
    AppBits.NO_NATIVE_CODE -> "no chip-specific code (Java only)"
    AppBits.ARM32 -> "32-bit ARM (${abis.joinToString(", ")})"
    AppBits.ARM64 -> "64-bit ARM (${abis.joinToString(", ")})"
    AppBits.ARM32_AND_64 -> "32-bit and 64-bit ARM (${abis.joinToString(", ")})"
    AppBits.NOT_ARM -> "other chips (${abis.joinToString(", ")})"
}

private class PackageLook(val label: String, val icon: ImageBitmap?)

/** Asks Android to read the APK's label and icon without installing it; falls back to manifest text. */
private fun loadLook(ctx: Context, apk: StoredApk): PackageLook {
    val pm = ctx.packageManager
    val path = apk.file.absolutePath
    return try {
        val pi = pm.getPackageArchiveInfo(path, 0)
        val ai = pi?.applicationInfo ?: return PackageLook(apk.displayName, null)
        ai.sourceDir = path
        ai.publicSourceDir = path
        val label = ai.loadLabel(pm).toString().ifBlank { apk.displayName }
        PackageLook(label, ai.loadIcon(pm).toBitmapImage())
    } catch (_: Exception) {
        PackageLook(apk.displayName, null)
    }
}

private fun Drawable.toBitmapImage(size: Int = 96): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(c)
    return bmp.asImageBitmap()
}

private fun importApk(ctx: Context, store: ApkStore, uri: Uri): Result<StoredApk> = runCatching {
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    val stream = ctx.contentResolver.openInputStream(uri) ?: throw ApkParseException("The file could not be opened.")
    store.import(stream, name)
}
