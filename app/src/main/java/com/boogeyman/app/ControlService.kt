package com.boogeyman.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class ControlService : AccessibilityService() {
    companion object { var instance: ControlService? = null; var allowed = false }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(i: android.content.Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun handle(m: JSONObject) {
        if (!allowed) return
        when (m.getString("t")) {
            "g" -> gesture(m.getDouble("x1"), m.getDouble("y1"), m.getDouble("x2"), m.getDouble("y2"), m.getLong("d"))
            "nav" -> performGlobalAction(when (m.getString("a")) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                else -> GLOBAL_ACTION_RECENTS
            })
        }
    }

    // coordinates are fractions (0..1) of the screen
    private fun gesture(x1: Double, y1: Double, x2: Double, y2: Double, d: Long) {
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo((x1 * dm.widthPixels).toFloat(), (y1 * dm.heightPixels).toFloat())
            if (x1 != x2 || y1 != y2) lineTo((x2 * dm.widthPixels).toFloat(), (y2 * dm.heightPixels).toFloat())
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, d.coerceIn(1, 5000))).build()
        dispatchGesture(g, null, null)
    }
}
