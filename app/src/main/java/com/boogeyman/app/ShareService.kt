package com.boogeyman.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.IBinder
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import org.webrtc.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer

// Tiny signaling server that runs on the sharing phone itself (no PC / no internet server needed)
class LocalServer(private val room: String, private val onMsg: (JSONObject) -> Unit,
                  private val onPeer: (Boolean) -> Unit, private val onInfo: (String) -> Unit,
                  private val accept: () -> Boolean = { true }) : WebSocketServer(InetSocketAddress(ShareService.PORT)) {
    private var client: WebSocket? = null
    override fun onOpen(c: WebSocket, h: ClientHandshake) {
        if (!h.resourceDescriptor.contains("room=$room") || !accept()) { c.close(); return }
        val old = client
        client = c
        old?.close()   // a new scan replaces a stale connection
        onPeer(true)
    }
    override fun onClose(c: WebSocket, code: Int, reason: String?, remote: Boolean) {
        if (c == client) { client = null; onPeer(false) }
    }
    override fun onMessage(c: WebSocket, msg: String) { if (c == client) onMsg(JSONObject(msg)) }
    override fun onError(c: WebSocket?, e: Exception?) { if (c == null) onInfo("error: " + (e?.message ?: e.toString())) }
    override fun onStart() { onInfo("ready") }
    fun sendMsg(o: JSONObject) { client?.send(o.toString()) }
}

class ShareService : Service() {
    companion object {
        var onPeer: ((Boolean) -> Unit)? = null
        var onError: ((String) -> Unit)? = null
        var onInfo: ((String) -> Unit)? = null
        const val PORT = 8765
    }

    private var link: Link? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var track: VideoTrack? = null
    private var dc: DataChannel? = null
    private val iceQ = IceQueue()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || i.action == "stop") { stopSelf(); return START_NOT_STICKY }
        try { begin(i) } catch (e: Throwable) { onError?.invoke(e.message ?: e.toString()); stopSelf() }
        return START_NOT_STICKY
    }

    private fun begin(i: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bm", "BOOGEYMAN", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bm").setContentTitle("BOOGEYMAN")
            .setContentText("Screen sharing is on").setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        Rtc.init(this)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")!!
        val room = i.getStringExtra("room")!!
        startCapture(data)
        link = Link(room, { onSignal(it) },
            { startOffer(); onPeer?.invoke(true) },
            { pc?.close(); pc = null; onPeer?.invoke(false) },
            { info -> onInfo?.invoke(info) }).also {
            it.listen()
            // If the viewer phone is the hotspot, it may not be able to call us: we call it (its address = our gateway)
            gatewayIp(this)?.let { gw -> it.dial(listOf("$gw:$PORT"), 5000) }
        }
    }

    private fun startCapture(data: Intent) {
        val dm = resources.displayMetrics
        val s = 720f / minOf(dm.widthPixels, dm.heightPixels)
        val w = (dm.widthPixels * s).toInt() / 2 * 2
        val h = (dm.heightPixels * s).toInt() / 2 * 2
        capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        helper = SurfaceTextureHelper.create("cap", Rtc.egl.eglBaseContext)
        val src = Rtc.factory.createVideoSource(true)
        capturer!!.initialize(helper, this, src.capturerObserver)
        capturer!!.startCapture(w, h, 30)
        track = Rtc.factory.createVideoTrack("v0", src)
    }

    private fun onSignal(m: JSONObject) {
        when (m.getString("type")) {
            "answer" -> pc?.setRemoteDescription(object : SimpleSdp() {
                override fun onSetSuccess() { iceQ.flush(pc) }
            }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            "ice" -> iceQ.add(pc, iceFrom(m))
        }
    }

    private fun startOffer() {
        pc?.close()
        iceQ.reset()
        val p = Rtc.factory.createPeerConnection(Rtc.config(), object : PcObserver() {
            override fun onIceCandidate(c: IceCandidate?) { c?.let { link?.send(iceMsg(it)) } }
        })!!
        p.addTrack(track, listOf("s"))?.let { sender ->
            try {
                val prm = sender.parameters
                prm.encodings.forEach { it.maxBitrateBps = 4_000_000; it.maxFramerate = 30 }
                sender.setParameters(prm)
            } catch (_: Exception) {}
        }
        val ch = p.createDataChannel("ctl", DataChannel.Init())
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {}
            override fun onMessage(b: DataChannel.Buffer) {
                val bytes = ByteArray(b.data.remaining()); b.data.get(bytes)
                val msg = JSONObject(String(bytes))
                val why = ControlService.check()
                if (why == null) ControlService.instance?.handle(msg)
                else ch.send(DataChannel.Buffer(
                    ByteBuffer.wrap(JSONObject().put("t", "err").put("m", why).toString().toByteArray()), false))
            }
        })
        dc = ch
        p.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                p.setLocalDescription(SimpleSdp(), d)
                link?.send(JSONObject().put("type", "offer").put("sdp", d!!.description))
            }
        }, MediaConstraints())
        pc = p
    }

    override fun onDestroy() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); pc?.close(); helper?.dispose()
        link?.close()
        super.onDestroy()
    }
}
