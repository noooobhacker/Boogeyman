package com.boogeyman.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

class ViewerActivity : AppCompatActivity() {
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var holder: FrameLayout
    private var sig: Signaling? = null
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private val iceQ = IceQueue()
    private lateinit var info: TextView
    private var vw = 0
    private var vh = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Rtc.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Phone's own Back button = Back on the other phone (use the ✕ button to leave)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { send(JSONObject().put("t", "nav").put("a", "back")) }
        })
        val parts = intent.getStringExtra("qr")!!.split("|")
        val hostPort = parts[0]; val room = parts[1]

        holder = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        renderer = SurfaceViewRenderer(this)
        holder.addView(renderer, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        info = TextView(this).apply { text = "Connecting to the other phone…"; setTextColor(RED); textSize = 18f; gravity = Gravity.CENTER }
        holder.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // floating control panel: dim handle at the bottom; tap to expand, tap arrow to collapse
        val dp = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000")); alpha = 0.35f
        }
        val row = LinearLayout(this).apply { visibility = View.GONE }
        listOf("◀ Back" to "back", "● Home" to "home", "▣ Apps" to "recents").forEach { (label, a) ->
            row.addView(redBtn(this, label) { send(JSONObject().put("t", "nav").put("a", a)) }.apply {
                minWidth = 0; minimumWidth = 0; setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(6, 8, 6, 0) })
        }
        row.addView(redBtn(this, "✕ Exit") { finish() }.apply {
            minWidth = 0; minimumWidth = 0; setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(6, 8, 6, 0) })
        val arrow = TextView(this).apply {
            text = "▲"; textSize = 26f; setTextColor(RED); gravity = Gravity.CENTER
            setPadding((28 * dp).toInt(), 0, (28 * dp).toInt(), 0)
        }
        var open = false
        arrow.setOnClickListener {
            open = !open
            row.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "▼" else "▲"
            panel.alpha = if (open) 1f else 0.35f
        }
        panel.addView(row); panel.addView(arrow)
        holder.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(holder)

        renderer.init(Rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() { runOnUiThread { info.visibility = View.GONE } }
            override fun onFrameResolutionChanged(w: Int, h: Int, rot: Int) { runOnUiThread { vw = w; vh = h; fit(w, h) } }
        })
        renderer.setEnableHardwareScaler(true)
        holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (vw > 0) fit(vw, vh) }

        var x0 = 0f; var y0 = 0f; var t0 = 0L
        renderer.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; t0 = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val d = (SystemClock.uptimeMillis() - t0).coerceAtLeast(50)
                    send(JSONObject().put("t", "g")
                        .put("x1", (x0 / v.width).toDouble()).put("y1", (y0 / v.height).toDouble())
                        .put("x2", (e.x / v.width).toDouble()).put("y2", (e.y / v.height).toDouble()).put("d", d))
                }
            }
            true
        }

        sig = Signaling("ws://$hostPort/?room=$room", { onSignal(it) }, { err ->
            runOnUiThread {
                if (err != null) Toast.makeText(this, "Could not connect. Are both phones on the same Wi-Fi / hotspot?", Toast.LENGTH_LONG).show()
                finish()
            }
        }).also { it.connect() }
    }

    private fun fit(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || holder.width == 0 || holder.height == 0) return
        val sc = minOf(holder.width / w.toFloat(), holder.height / h.toFloat())
        val nw = (w * sc).toInt(); val nh = (h * sc).toInt()
        val cur = renderer.layoutParams
        if (cur != null && cur.width == nw && cur.height == nh) return
        renderer.layoutParams = FrameLayout.LayoutParams(nw, nh, Gravity.CENTER)
    }

    private fun send(o: JSONObject) {
        dc?.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray()), false))
    }

    private fun onSignal(m: JSONObject) {
        when (m.getString("type")) {
            "offer" -> {
                val p = Rtc.factory.createPeerConnection(Rtc.config(), object : PcObserver() {
                    override fun onIceCandidate(c: IceCandidate?) { c?.let { sig?.send(iceMsg(it)) } }
                    override fun onDataChannel(d: DataChannel?) {
                        dc = d
                        d?.registerObserver(object : DataChannel.Observer {
                            override fun onBufferedAmountChange(l: Long) {}
                            override fun onStateChange() {}
                            override fun onMessage(b: DataChannel.Buffer) {
                                val bytes = ByteArray(b.data.remaining()); b.data.get(bytes)
                                val m = JSONObject(String(bytes))
                                if (m.optString("t") == "err")
                                    runOnUiThread { Toast.makeText(this@ViewerActivity, m.optString("m"), Toast.LENGTH_SHORT).show() }
                            }
                        })
                    }
                    override fun onIceConnectionChange(st: PeerConnection.IceConnectionState?) {
                        if (st == PeerConnection.IceConnectionState.FAILED || st == PeerConnection.IceConnectionState.DISCONNECTED)
                            runOnUiThread { info.visibility = View.VISIBLE; info.text = "Video link failed.\nSame Wi-Fi? Turn off router AP isolation / VPN." }
                    }
                    override fun onAddStream(s: MediaStream?) { s?.videoTracks?.firstOrNull()?.addSink(renderer) }
                    override fun onTrack(t: RtpTransceiver?) { (t?.receiver?.track() as? VideoTrack)?.addSink(renderer) }
                })!!
                pc = p
                p.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        iceQ.flush(p)
                        p.createAnswer(object : SimpleSdp() {
                            override fun onCreateSuccess(d: SessionDescription?) {
                                p.setLocalDescription(SimpleSdp(), d)
                                sig?.send(JSONObject().put("type", "answer").put("sdp", d!!.description))
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, m.getString("sdp")))
            }
            "ice" -> iceQ.add(pc, iceFrom(m))
            "peer-left" -> runOnUiThread { finish() }
        }
    }

    override fun onDestroy() {
        sig?.close(); pc?.close(); renderer.release()
        super.onDestroy()
    }
}
