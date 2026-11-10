package com.wallpaperhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps running in the background: timed slideshow + change on every unlock / lock. */
class HubService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var last = 0L

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val unlock = i.action == Intent.ACTION_USER_PRESENT
            val on = if (unlock) Cfg.b("unlock", true) else Cfg.b("lock", false)
            if (on) fire()
        }
    }

    private fun fire() {
        if (System.currentTimeMillis() - last < 20_000) return      // avoid back-to-back changes
        last = System.currentTimeMillis()
        scope.launch { Engine.changeNow(applicationContext) }
    }

    override fun onCreate() {
        super.onCreate()
        Cfg.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hub", "Wallpaper Hub", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "hub").setContentTitle("Wallpaper Hub is running")
            .setSmallIcon(android.R.drawable.ic_menu_gallery).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        val f = IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            while (isActive) {
                delay(Cfg.i("interval", 30).coerceAtLeast(1) * 60_000L)
                if (Cfg.b("slide", true)) fire()
            }
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { runCatching { unregisterReceiver(rx) }; scope.cancel(); super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Cfg.init(c)
        if (Cfg.b("on", false)) ContextCompat.startForegroundService(c, Intent(c, HubService::class.java))
    }
}
