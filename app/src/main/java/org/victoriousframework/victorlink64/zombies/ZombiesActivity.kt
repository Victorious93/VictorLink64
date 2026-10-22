package org.victoriousframework.victorlink64.zombies

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.victorious.zombie.Entry
import com.victorious.zombie.VictorOpsGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.victoriousframework.victorlink64.connect.ConnectSession
import org.victoriousframework.victorlink64.connect.PeerState

enum class ZombiesRole { SOLO, HOST, CLIENT }

/**
 * Victorious Framework: hosts VictorOps Zombies (libGDX) inside VictorLink64.
 * Runs in the app process on purpose: [ConnectSession] is process-local, and the game reuses it.
 */
class ZombiesActivity : AndroidApplication() {
    companion object {
        private const val EXTRA_ROLE = "role"
        /** Solo needs no session. Host/client launch only makes sense while [ConnectSession] is connected. */
        fun launch(ctx: Context, role: ZombiesRole) =
            ctx.startActivity(Intent(ctx, ZombiesActivity::class.java).putExtra(EXTRA_ROLE, role.name))

        /** Which role the Connect tab should start for the current session. */
        fun roleForSession(): ZombiesRole = if (ConnectSession.isHost) ZombiesRole.HOST else ZombiesRole.CLIENT
    }

    // AndroidApplication is a plain Activity (not a LifecycleOwner), so own the scope: cancelled in onDestroy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var hostWatch: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val role = runCatching { ZombiesRole.valueOf(intent.getStringExtra(EXTRA_ROLE) ?: "") }.getOrDefault(ZombiesRole.SOLO)
        val connected = ConnectSession.status.value.state == PeerState.CONNECTED
        val entry = when {
            role == ZombiesRole.HOST && connected -> Entry.Host(onSession = { session ->
                // A fresh link per connected period: a peer that drops and reconnects through the Connect tab re-joins.
                hostWatch = scope.launch {
                    ConnectSession.status.map { it.state }.distinctUntilChanged().collect { state ->
                        if (state == PeerState.CONNECTED) session.attach(ConnectNetLink.forSession(scope))
                    }
                }
            }, onClose = { hostWatch?.cancel() })
            role == ZombiesRole.CLIENT && connected -> Entry.Client(ConnectNetLink.forSession(scope))
            else -> {
                if (role != ZombiesRole.SOLO) android.widget.Toast.makeText(this, "Not connected - starting solo", android.widget.Toast.LENGTH_LONG).show()
                Entry.Solo
            }
        }
        initialize(VictorOpsGame(entry), AndroidApplicationConfiguration().apply { useImmersiveMode = true })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
