package org.victoriousframework.victorlink64.pc

import android.content.Context
import android.util.Base64
import com.limelight.binding.PlatformBinding
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/*
 * Victorious Framework: saved PCs, stream settings and the calls into Moonlight's
 * HTTP/pairing client. Every network call here blocks; call it off the main thread.
 */
class PcRepo(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("vl64_pc_streaming", Context.MODE_PRIVATE)

    /** Random per-install client id that the PC remembers after pairing (Moonlight does the same). */
    val uniqueId: String
        get() = sp.getString("unique_id", null) ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { sp.edit().putString("unique_id", it).apply() }

    fun hosts(): List<PcHost> = PcHost.listFromJson(sp.getString("hosts", null))
    fun saveHosts(list: List<PcHost>) = sp.edit().putString("hosts", PcHost.listToJson(list)).apply()
    fun upsert(h: PcHost) = saveHosts(PcHost.upsert(hosts(), h))
    fun remove(uuid: String) = saveHosts(hosts().filterNot { it.uuid == uuid })
    fun host(uuid: String) = hosts().firstOrNull { it.uuid == uuid }

    var settings: StreamSettings
        get() = StreamSettings(
            res = runCatching { StreamRes.valueOf(sp.getString("res", "P720")!!) }.getOrDefault(StreamRes.P720),
            fps = sp.getInt("fps", 60),
            bitrateKbps = sp.getInt("kbps", 0),
            codec = runCatching { StreamCodec.valueOf(sp.getString("codec", "AUTO")!!) }.getOrDefault(StreamCodec.AUTO),
            pacing = runCatching { FramePacing.valueOf(sp.getString("pacing", "BALANCED")!!) }.getOrDefault(FramePacing.BALANCED),
            touchControls = sp.getBoolean("touch", true),
            showStats = sp.getBoolean("stats", false),
            playAudioOnPc = sp.getBoolean("pc_audio", false),
            swapAbxy = sp.getBoolean("swap_abxy", false),
        )
        set(s) = sp.edit().putString("res", s.res.name).putInt("fps", s.fps).putInt("kbps", s.bitrateKbps)
            .putString("codec", s.codec.name).putString("pacing", s.pacing.name)
            .putBoolean("touch", s.touchControls).putBoolean("stats", s.showStats)
            .putBoolean("pc_audio", s.playAudioOnPc).putBoolean("swap_abxy", s.swapAbxy).apply()

    private fun http(h: PcHost) = NvHTTP(
        ComputerDetails.AddressTuple(h.address, h.httpPort), h.httpsPort, uniqueId,
        certOf(h), PlatformBinding.getCryptoProvider(ctx)
    )

    /** Asks the PC who it is. Returns an updated host (name, uuid, ports) or throws. */
    fun probe(address: String, port: Int = PcHost.DEFAULT_HTTP_PORT, known: PcHost? = null, manual: Boolean = false): PcHost {
        val base = known ?: PcHost(uuid = "", name = address, address = address, httpPort = port, manual = manual)
        val http = NvHTTP(ComputerDetails.AddressTuple(address, port), base.httpsPort, uniqueId,
            certOf(base), PlatformBinding.getCryptoProvider(ctx))
        val d = http.getComputerDetails(true)
        return PcHost(
            uuid = d.uuid, name = d.name ?: address, address = address, httpPort = port,
            httpsPort = if (d.httpsPort > 0) d.httpsPort else PcHost.DEFAULT_HTTPS_PORT,
            serverCertB64 = known?.serverCertB64 ?: host(d.uuid)?.serverCertB64,
            manual = manual || (known?.manual ?: false),
        )
    }

    enum class PairResult(val message: String) {
        PAIRED("Paired! You can now pick a game."),
        WRONG_PIN("The PIN didn't match. Try again and type the exact 4 digits."),
        BUSY("Your PC is already pairing with another device. Wait a moment and try again."),
        FAILED("Pairing failed. Make sure Sunshine is open on your PC and try again."),
    }

    /** Runs the PIN pairing handshake. Blocks until the PIN is entered on the PC (or it gives up). */
    fun pair(h: PcHost, pin: String): Pair<PairResult, PcHost> {
        val http = NvHTTP(ComputerDetails.AddressTuple(h.address, h.httpPort), h.httpsPort, uniqueId,
            null, PlatformBinding.getCryptoProvider(ctx))
        val pm = http.pairingManager
        val state = pm.pair(http.getServerInfo(true), pin)
        return when (state) {
            PairingManager.PairState.PAIRED -> {
                val cert = pm.pairedCert
                val paired = h.copy(serverCertB64 = cert?.let { Base64.encodeToString(it.encoded, Base64.NO_WRAP) })
                upsert(paired); PairResult.PAIRED to paired
            }
            PairingManager.PairState.PIN_WRONG -> PairResult.WRONG_PIN to h
            PairingManager.PairState.ALREADY_IN_PROGRESS -> PairResult.BUSY to h
            else -> PairResult.FAILED to h
        }
    }

    fun apps(h: PcHost): List<NvApp> = http(h).appList.toList()

    fun boxArt(h: PcHost, app: NvApp): ByteArray? = runCatching { http(h).getBoxArt(app).use { it.readBytes() } }.getOrNull()

    fun quitApp(h: PcHost): Boolean = runCatching { http(h).quitApp() }.getOrDefault(false)

    fun unpair(h: PcHost) {
        runCatching { http(h).unpair() }
        saveHosts(hosts().map { if (it.uuid == h.uuid) it.copy(serverCertB64 = null) else it })
    }

    companion object {
        fun certOf(h: PcHost): X509Certificate? = h.serverCertB64?.let { b64 ->
            runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT))) as X509Certificate
            }.getOrNull()
        }
    }
}
