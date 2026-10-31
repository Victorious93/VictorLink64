package org.victoriousframework.victorlink64.classic

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process

/**
 * Runs in its own process (":classic32") and tries to load the armeabi-v7a-only test library.
 * Note: Android picks one ABI per app, so on a phone where this app runs as 64-bit the load is
 * expected to fail; that result is reported honestly rather than hidden.
 */
class Classic32Service : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        val reply = Message.obtain(null, MSG_RESULT)
        reply.data = Bundle().apply {
            putBoolean(KEY_IS64, Process.is64Bit())
            putInt(KEY_PID, Process.myPid())
            val r = runCatching { System.loadLibrary("vl64classic32"); nativeHello() }
            putBoolean(KEY_OK, r.isSuccess)
            putString(KEY_TEXT, r.getOrElse { it.javaClass.simpleName + ": " + (it.message ?: "") })
        }
        runCatching { msg.replyTo?.send(reply) }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    companion object {
        const val MSG_RUN = 1
        const val MSG_RESULT = 2
        const val KEY_OK = "ok"
        const val KEY_TEXT = "text"
        const val KEY_IS64 = "is64"
        const val KEY_PID = "pid"
        @JvmStatic external fun nativeHello(): String
    }
}
