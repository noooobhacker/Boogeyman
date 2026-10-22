package com.boogeyman.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import okhttp3.*

object Rtc {
    lateinit var factory: PeerConnectionFactory
    val egl: EglBase by lazy { EglBase.create() }
    val ice = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    // For use over the internet add your TURN server here, e.g.
    // PeerConnection.IceServer.builder("turn:YOUR_SERVER:3478").setUsername("u").setPassword("p").createIceServer()

    fun init(ctx: Context) {
        if (::factory.isInitialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun config() = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
}

open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

open class PcObserver : PeerConnection.Observer {
    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(c: IceCandidate?) {}
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
    override fun onAddStream(s: MediaStream?) {}
    override fun onRemoveStream(s: MediaStream?) {}
    override fun onDataChannel(d: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
}

// Candidates that arrive before the remote description is applied must wait, otherwise they are dropped
class IceQueue {
    private val pending = mutableListOf<IceCandidate>()
    private var ready = false
    @Synchronized fun add(pc: PeerConnection?, c: IceCandidate) { if (ready) pc?.addIceCandidate(c) else pending.add(c) }
    @Synchronized fun flush(pc: PeerConnection?) { ready = true; pending.forEach { pc?.addIceCandidate(it) }; pending.clear() }
    @Synchronized fun reset() { ready = false; pending.clear() }
}

fun iceMsg(c: IceCandidate) = JSONObject().put("type", "ice")
    .put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("cand", c.sdp)

fun iceFrom(m: JSONObject) = IceCandidate(m.getString("mid"), m.getInt("idx"), m.getString("cand"))

class Signaling(private val url: String, private val onMsg: (JSONObject) -> Unit,
                private val onEnd: (String?) -> Unit = {}, private val onOpened: () -> Unit = {}) {
    private var ws: WebSocket? = null
    fun connect() {
        val client = OkHttpClient.Builder().connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onOpened() }
            override fun onMessage(webSocket: WebSocket, text: String) { onMsg(JSONObject(text)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onEnd(null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onEnd(t.message ?: "error") }
        })
    }
    fun send(o: JSONObject) { ws?.send(o.toString()) }
    fun close() { ws?.close(1000, null) }
}

// Address of the Wi-Fi gateway (when this phone is connected to another phone's hotspot, that is the hotspot phone)
@Suppress("DEPRECATION")
fun gatewayIp(ctx: Context): String? = try {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    val g = wm.dhcpInfo.gateway
    if (g == 0) null else "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g shr 24) and 0xff}"
} catch (e: Exception) { null }

// One signaling channel between the two phones. Either phone can open it (dial) or accept it (listen),
// so it works whichever phone is the hotspot.
class Link(private val room: String, private val onMsg: (JSONObject) -> Unit,
           private val onUp: () -> Unit, private val onDown: () -> Unit,
           private val onInfo: (String) -> Unit = {}) {
    private val lock = Any()
    private var server: LocalServer? = null
    private var out: Signaling? = null
    private var kind = ""            // "", "server" (they called us) or "client" (we called them)
    @Volatile private var closed = false

    fun isUp(): Boolean = synchronized(lock) { kind != "" }

    fun listen() {
        server = LocalServer(room, { onMsg(it) }, { connected ->
            if (connected) {
                synchronized(lock) { kind = "server" }
                onUp()
            } else {
                var was = false
                synchronized(lock) { if (kind == "server") { kind = ""; was = true } }
                if (was) onDown()
            }
        }, { onInfo(it) }, { synchronized(lock) { kind != "client" } }).also { it.setReuseAddr(true); it.start() }
    }

    fun dial(hosts: List<String>, delayMs: Long) {
        if (hosts.isEmpty()) return
        Thread {
            val start = System.currentTimeMillis()
            while (!closed) {
                if (!isUp() && System.currentTimeMillis() - start >= delayMs) {
                    for (h in hosts) {
                        if (closed || isUp()) break
                        tryOne(h)
                    }
                }
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun tryOne(host: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val ref = arrayOfNulls<Signaling>(1)
        val s = Signaling("ws://$host/?room=$room", { onMsg(it) }, { _ ->
            latch.countDown()
            var was = false
            synchronized(lock) { if (kind == "client" && out === ref[0]) { kind = ""; out = null; was = true } }
            if (was) onDown()
        }, {
            var ok = false
            synchronized(lock) { if (kind == "") { kind = "client"; out = ref[0]; ok = true } }
            if (ok) onUp() else ref[0]?.close()
            latch.countDown()
        })
        ref[0] = s
        s.connect()
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        synchronized(lock) { if (out !== s) s.close() }
    }

    fun send(o: JSONObject) {
        var k = ""
        var s: Signaling? = null
        synchronized(lock) { k = kind; s = out }
        if (k == "server") server?.sendMsg(o) else if (k == "client") s?.send(o)
    }

    fun close() {
        closed = true
        try { server?.stop(300) } catch (_: Exception) {}
        synchronized(lock) { out?.close(); out = null }
    }
}
