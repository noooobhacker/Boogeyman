package com.boogeyman.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

val RED = Color.parseColor("#E50914")

fun redBtn(c: Context, t: String, f: () -> Unit) = Button(c).apply {
    text = t; setTextColor(Color.WHITE); isAllCaps = false; textSize = 16f
    background = GradientDrawable().apply { setColor(RED); cornerRadius = 48f }
    setOnClickListener { f() }
}

// This phone's address on the current Wi-Fi / hotspot network
fun localIp(): String? {
    val found = mutableListOf<Pair<String, String>>()
    for (nif in NetworkInterface.getNetworkInterfaces()) {
        if (!nif.isUp || nif.isLoopback) continue
        for (a in nif.inetAddresses) {
            if (a is Inet4Address && a.isSiteLocalAddress) found.add(nif.name to a.hostAddress!!)
        }
    }
    return (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("ap") } ?: found.firstOrNull())?.second
}

class MainActivity : AppCompatActivity() {
    private lateinit var home: LinearLayout
    private lateinit var shareBox: LinearLayout
    private lateinit var qrImage: ImageView
    private lateinit var status: TextView
    private lateinit var touchSwitch: Switch
    private var pendingIp: String? = null

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) startShare(d)
    }
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("qr")?.let { startActivity(Intent(this, ViewerActivity::class.java).putExtra("qr", it)) }
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * dp).toInt(), (40 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "BOOGEYMAN"; textSize = 32f; setTextColor(RED); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "Phone A: tap Show my QR (its screen gets shared)\nPhone B: tap Scan QR (watches and controls Phone A)"
            setTextColor(Color.LTGRAY); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lp = { LinearLayout.LayoutParams(-1, (90 * dp).toInt()).apply { topMargin = (20 * dp).toInt() } }
        home.addView(redBtn(this, "▦   Show my QR\n(shares THIS phone's screen)") {
            val ip = localIp()
            if (ip == null) {
                Toast.makeText(this, "Connect to Wi-Fi or turn on Hotspot first", Toast.LENGTH_LONG).show()
            } else {
                pendingIp = ip
                projLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
        }, lp())
        home.addView(redBtn(this, "📷   Scan QR\n(view & control the OTHER phone)") {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }, lp())
        root.addView(home)

        shareBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 16f }
        shareBox.addView(status)
        qrImage = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(16, 16, 16, 16) }
        shareBox.addView(qrImage, LinearLayout.LayoutParams((280 * dp).toInt(), (280 * dp).toInt()).apply { topMargin = (16 * dp).toInt() })
        touchSwitch = Switch(this).apply {
            text = "Touch control  "; setTextColor(Color.WHITE); textSize = 18f
            thumbTintList = ColorStateList.valueOf(RED)
            trackTintList = ColorStateList.valueOf(Color.parseColor("#7A0509"))
            setOnCheckedChangeListener { _, on ->
                ControlService.allowed = on
                if (on && ControlService.instance == null) {
                    Toast.makeText(this@MainActivity,
                        "Accessibility > Downloaded apps > BOOGEYMAN > turn ON", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        shareBox.addView(touchSwitch, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (20 * dp).toInt() })
        shareBox.addView(redBtn(this, "Stop sharing") { stopShare() },
            LinearLayout.LayoutParams(-1, (60 * dp).toInt()).apply { topMargin = (20 * dp).toInt() })
        root.addView(shareBox)

        root.addView(TextView(this).apply {
            text = "BOOGEYMAN v1.0\nDeveloper: Sham Shyam\n\u00a9 2026 Sham Shyam. All rights reserved."
            setTextColor(Color.GRAY); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, 0)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
    }

    private fun startShare(data: Intent) {
        val ip = pendingIp ?: localIp() ?: return
        val room = UUID.randomUUID().toString().take(8)
        ShareService.onPeer = { connected ->
            runOnUiThread {
                qrImage.visibility = if (connected) View.GONE else View.VISIBLE
                status.text = if (connected) "Connected ✔\nTHIS phone's screen is being shared" else "Waiting for the other phone to scan..."
            }
        }
        startForegroundService(Intent(this, ShareService::class.java)
            .putExtra("data", data).putExtra("room", room))
        qrImage.setImageBitmap(BarcodeEncoder().encodeBitmap("$ip:${ShareService.PORT}|$room", BarcodeFormat.QR_CODE, 700, 700))
        qrImage.visibility = View.VISIBLE
        status.text = "THIS phone's screen will be shared.\nScan this QR with the OTHER phone."
        home.visibility = View.GONE; shareBox.visibility = View.VISIBLE
    }

    private fun stopShare() {
        startService(Intent(this, ShareService::class.java).setAction("stop"))
        ShareService.onPeer = null
        touchSwitch.isChecked = false
        shareBox.visibility = View.GONE; home.visibility = View.VISIBLE
    }
}
