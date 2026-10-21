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

// Tiny signaling server that runs on the sharing phone itself (no PC / no internet server needed)
class LocalServer(private val room: String, private val onMsg: (JSONObject) -> Unit,
                  private val onPeer: (Boolean) -> Unit) : WebSocketServer(InetSocketAddress(ShareService.PORT)) {
    private var client: WebSocket? = null
    override fun onOpen(c: WebSocket, h: ClientHandshake) {
        if (!h.resourceDescriptor.contains("room=$room") || client != null) { c.close(); return }
        client = c; onPeer(true)
    }
    override fun onClose(c: WebSocket, code: Int, reason: String?, remote: Boolean) {
        if (c == client) { client = null; onPeer(false) }
    }
    override fun onMessage(c: WebSocket, msg: String) { if (c == client) onMsg(JSONObject(msg)) }
    override fun onError(c: WebSocket?, e: Exception?) {}
    override fun onStart() {}
    fun sendMsg(o: JSONObject) { client?.send(o.toString()) }
}

class ShareService : Service() {
    companion object { var onPeer: ((Boolean) -> Unit)? = null; const val PORT = 8765 }

    private var server: LocalServer? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var track: VideoTrack? = null
    private var dc: DataChannel? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || i.action == "stop") { stopSelf(); return START_NOT_STICKY }
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
        server = LocalServer(room, { onSignal(it) }, { connected ->
            if (connected) startOffer() else { pc?.close(); pc = null }
            onPeer?.invoke(connected)
        }).also { it.setReuseAddr(true); it.start() }
        return START_NOT_STICKY
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
            "answer" -> pc?.setRemoteDescription(SimpleSdp(),
                SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            "ice" -> pc?.addIceCandidate(iceFrom(m))
        }
    }

    private fun startOffer() {
        pc?.close()
        val p = Rtc.factory.createPeerConnection(Rtc.config(), object : PcObserver() {
            override fun onIceCandidate(c: IceCandidate?) { c?.let { server?.sendMsg(iceMsg(it)) } }
        })!!
        p.addTrack(track, listOf("s"))
        val ch = p.createDataChannel("ctl", DataChannel.Init())
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {}
            override fun onMessage(b: DataChannel.Buffer) {
                val bytes = ByteArray(b.data.remaining()); b.data.get(bytes)
                ControlService.instance?.handle(JSONObject(String(bytes)))
            }
        })
        dc = ch
        p.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                p.setLocalDescription(SimpleSdp(), d)
                server?.sendMsg(JSONObject().put("type", "offer").put("sdp", d!!.description))
            }
        }, MediaConstraints())
        pc = p
    }

    override fun onDestroy() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); pc?.close(); helper?.dispose()
        try { server?.stop(500) } catch (_: Exception) {}
        super.onDestroy()
    }
}
