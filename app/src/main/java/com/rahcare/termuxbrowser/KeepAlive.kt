package com.rahcare.termuxbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * কমান্ড চলার সময় অ্যাপকে ফোরগ্রাউন্ড সার্ভিস বানিয়ে রাখে (AutoReply-এর KeepAlive-এর মতো),
 * যাতে মিনিমাইজ করলে Android অ্যাপ ফ্রিজ/বন্ধ না করে। কোনো কাজ না থাকলে নিজে থেমে যায়।
 */
/** ব্যাকগ্রাউন্ডে সাইটের ট্যাব সচল রাখার অনুরোধ (এই সময় পর্যন্ত; অ্যাপ সামনে এলে ০) */
object Keep { @Volatile var webUntil = 0L }

class KeepAliveService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null

    private var wfl: android.net.wifi.WifiManager.WifiLock? = null
    private var tick = 0
    private var lastText = ""

    private fun text() = when {
        Jobs.running() > 0 -> "কমান্ড চলছে…"
        else -> "ট্যাব ব্যাকগ্রাউন্ডে সচল আছে"
    }

    private fun notif(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        lastText = text()
        return Notification.Builder(this, "keep")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Daily Browser")
            .setContentText(lastText)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private val check = object : Runnable {
        override fun run() {
            val web = Keep.webUntil > System.currentTimeMillis()
            if (Jobs.running() == 0 && !web) { stopSelf(); return }
            // লাইভ সংযোগ ছিঁড়ে গেলে ব্যাকগ্রাউন্ডে থাকলেও নিজে থেকে আবার জোড়ে
            try { Runner.reconnect(this@KeepAliveService, 20000L) } catch (_: Exception) {}
            // অ্যাপ হঠাৎ বন্ধ হলেও যেন চলমান কাজের অবস্থা হারায় না
            tick++; if (tick % 5 == 0) try { Jobs.save(this@KeepAliveService) } catch (_: Exception) {}
            if (text() != lastText) try { getSystemService(NotificationManager::class.java).notify(8, notif()) } catch (_: Exception) {}
            h.postDelayed(this, 4000L)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("keep", "চলমান কমান্ড", NotificationManager.IMPORTANCE_LOW))
        val n = notif()
        if (Build.VERSION.SDK_INT >= 34) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(8, n)
        try {
            if (wl == null) wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termuxbrowser:jobs")
            if (wl?.isHeld != true) wl?.acquire(6 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
        // Wi-Fi ঘুমিয়ে পড়লে সাইটের লাইভ সংযোগ কেটে যায়; ফোরগ্রাউন্ড সার্ভিস চলাকালীন Wi-Fi জাগিয়ে রাখি
        try {
            if (wfl == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wfl = wm.createWifiLock(
                    if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "termuxbrowser:wifi")
                wfl?.setReferenceCounted(false)
            }
            if (wfl?.isHeld != true) wfl?.acquire()
        } catch (_: Exception) {}
        h.removeCallbacks(check); h.postDelayed(check, 4000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacks(check)
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
        try { if (wfl?.isHeld == true) wfl?.release() } catch (_: Exception) {}
    }

    companion object {
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }
}
