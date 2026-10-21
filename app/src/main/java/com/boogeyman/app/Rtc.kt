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

fun iceMsg(c: IceCandidate) = JSONObject().put("type", "ice")
    .put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("cand", c.sdp)

fun iceFrom(m: JSONObject) = IceCandidate(m.getString("mid"), m.getInt("idx"), m.getString("cand"))

class Signaling(private val url: String, private val onMsg: (JSONObject) -> Unit) {
    private var ws: WebSocket? = null
    fun connect() {
        ws = OkHttpClient().newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { onMsg(JSONObject(text)) }
        })
    }
    fun send(o: JSONObject) { ws?.send(o.toString()) }
    fun close() { ws?.close(1000, null) }
}
