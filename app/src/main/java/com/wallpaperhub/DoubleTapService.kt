package com.wallpaperhub

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Double-tap on the home screen locks the phone (uses the system "lock screen" action; nothing on screen is read). */
class DoubleTapService : AccessibilityService() {
    private var lastTap = 0L
    private var fg = ""
    private var overlay: View? = null

    private fun launcher(): String {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Cfg.init(this)
        val v = View(this)
        v.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) tapped(); false }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp); overlay = v }
    }

    private fun tapped() {
        if (!Cfg.b("dtlock", false)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 350 && fg == launcher()) {
            lastTap = 0
            if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else lastTap = now
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e != null && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString()
            if (p != null) fg = p
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        super.onDestroy()
    }
}
