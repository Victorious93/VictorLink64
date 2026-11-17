package org.victoriousframework.victorlink64.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class ShizukuState(val label: String) {
    NOT_INSTALLED("Shizuku is not installed"),
    NOT_RUNNING("Shizuku is installed but not running"),
    NEEDS_PERMISSION("Shizuku is running; permission not granted yet"),
    READY("Shizuku is ready"),
}

/** Runs inside Shizuku's shell-privileged process. Receives a command string, returns its output and exit code. */
class ShellService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != EXEC) return super.onTransact(code, data, reply, flags)
        val cmd = data.readString().orEmpty()
        val sb = StringBuilder()
        var exit = -1
        try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val reader = Thread { runCatching { sb.append(p.inputStream.bufferedReader().readText()) } }.apply { start() }
            if (p.waitFor(10, TimeUnit.SECONDS)) { exit = p.exitValue(); reader.join(1000) } else { p.destroyForcibly(); sb.append("timed out") }
        } catch (e: Exception) {
            sb.append("Exception: ").append(e.message)
        }
        reply?.writeString(sb.toString())
        reply?.writeInt(exit)
        return true
    }

    companion object { const val EXEC = 1 }
}

private class BinderShell(private val binder: IBinder) : Shell {
    override fun exec(command: String): ShellResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(command)
            binder.transact(ShellService.EXEC, data, reply, 0)
            ShellResult(reply.readString().orEmpty(), reply.readInt())
        } catch (e: Exception) {
            ShellResult("Exception: ${e.message}", -1)
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}

/** Android-side access to Shizuku. Every call is safe when Shizuku is missing; nothing here is required to play. */
object ShizukuBridge {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val TAG = "VL64Shizuku"

    @Volatile private var binder: IBinder? = null

    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    fun state(ctx: Context): ShizukuState {
        if (!isInstalled(ctx)) return ShizukuState.NOT_INSTALLED
        return runCatching {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NEEDS_PERMISSION
                else -> ShizukuState.READY
            }
        }.getOrDefault(ShizukuState.NOT_RUNNING)
    }

    fun requestPermission(code: Int) {
        runCatching { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(code) }
            .onFailure { Log.w(TAG, "requestPermission", it) }
    }

    fun addPermissionListener(l: Shizuku.OnRequestPermissionResultListener) = runCatching { Shizuku.addRequestPermissionResultListener(l) }
    fun removePermissionListener(l: Shizuku.OnRequestPermissionResultListener) = runCatching { Shizuku.removeRequestPermissionResultListener(l) }

    /** Blocking; call off the main thread. Returns null unless Shizuku is ready and the helper process answered. */
    fun shell(ctx: Context, timeoutMs: Long = 5000): Shell? {
        if (state(ctx) != ShizukuState.READY) return null
        binder?.takeIf { it.pingBinder() }?.let { return BinderShell(it) }
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { binder = null }
        }
        return runCatching {
            val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
                .processNameSuffix("shell").daemon(false).debuggable(false).version(1)
            Shizuku.bindUserService(args, conn)
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) binder?.let { BinderShell(it) } else null
        }.onFailure { Log.w(TAG, "bindUserService", it) }.getOrNull()
    }
}
