package org.victoriousframework.victorlink64.classic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.Navy
import org.victoriousframework.victorlink64.ui.NavyCard
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.VictorTheme
import org.victoriousframework.victorlink64.ui.clip16

/** Milestone 1 of the Classic Android player: can this phone run old 32-bit apps natively? */
class ClassicCheckActivity : ComponentActivity() {
    private var conn: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { ClassicCheckScreen(::runProof) } }
    }

    private fun runProof(done: (String) -> Unit) {
        conn?.let { runCatching { unbindService(it) } }
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val replyTo = Messenger(Handler(Looper.getMainLooper()) { m ->
                    val d = m.data
                    val bits = if (d.getBoolean(Classic32Service.KEY_IS64)) "64-bit" else "32-bit"
                    done(
                        (if (d.getBoolean(Classic32Service.KEY_OK)) "Passed: " else "Did not load: ") +
                            d.getString(Classic32Service.KEY_TEXT) +
                            "\nTest process ${d.getInt(Classic32Service.KEY_PID)} ran as $bits."
                    )
                    true
                })
                runCatching {
                    Messenger(binder).send(Message.obtain(null, Classic32Service.MSG_RUN).apply { this.replyTo = replyTo })
                }.onFailure { done("Could not reach the test process: ${it.message}") }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        conn = c
        if (!bindService(Intent(this, Classic32Service::class.java), c, Context.BIND_AUTO_CREATE)) {
            done("Could not start the test process.")
        }
    }

    override fun onDestroy() {
        conn?.let { runCatching { unbindService(it) } }
        super.onDestroy()
    }
}

/** Reads ro.zygote via hidden SystemProperties, falling back to `getprop`. */
fun readZygoteProperty(): String {
    runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val v = sp.getMethod("get", String::class.java).invoke(null, "ro.zygote") as String
        if (v.isNotBlank()) return v
    }
    runCatching {
        val p = ProcessBuilder("getprop", "ro.zygote").redirectErrorStream(true).start()
        val v = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (v.isNotBlank()) return v
    }
    return "unknown (could not read)"
}

private fun abis(a: Array<String>) = if (a.isEmpty()) "none" else a.joinToString(", ")

@Composable
fun ClassicCheckScreen(runProof: ((String) -> Unit) -> Unit) {
    val zygote = remember { readZygoteProperty() }
    val has32 = Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()
    var proof by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Navy).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Classic Android check", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Can this phone run old 32-bit Android games?", color = Muted)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip16().background(NavyCard)
                .border(1.dp, NeonBlue, RoundedCornerShape(16.dp)).padding(16.dp)
        ) {
            Text(
                if (has32) "Your phone can run old 32-bit games natively"
                else "Your phone is 64-bit only; old games will need translation",
                color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (has32) "It lists 32-bit chip types (${abis(Build.SUPPORTED_32_BIT_ABIS)}) as supported."
                else "It does not list any 32-bit chip types, so 32-bit code can't run directly.",
                color = Muted, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Fact("Phone", "${Build.MANUFACTURER} ${Build.MODEL}")
        Fact("Android version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Fact("All supported chip types (SUPPORTED_ABIS)", abis(Build.SUPPORTED_ABIS))
        Fact("64-bit chip types (SUPPORTED_64_BIT_ABIS)", abis(Build.SUPPORTED_64_BIT_ABIS))
        Fact("32-bit chip types (SUPPORTED_32_BIT_ABIS)", abis(Build.SUPPORTED_32_BIT_ABIS))
        Fact("App launcher mode (ro.zygote)", zygote)
        Fact("This app is running as", if (Process.is64Bit()) "64-bit" else "32-bit")
        Spacer(Modifier.height(16.dp))
        Text("Proof test", color = NeonBlue, fontWeight = FontWeight.Bold)
        Text(
            "Starts a separate test process and tries to load a tiny 32-bit ARM library. " +
                "Android runs every process of one app with the same chip type, so on a phone where " +
                "VictorLink64 runs as 64-bit this is expected to not load; a real player will need its own 32-bit helper.",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { running = true; proof = null; runProof { proof = it; running = false } },
            enabled = !running,
            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Navy)
        ) { Text(if (running) "Testing…" else "Run 32-bit test") }
        proof?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color.White, fontSize = 13.sp) }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = NavyCard)
}
