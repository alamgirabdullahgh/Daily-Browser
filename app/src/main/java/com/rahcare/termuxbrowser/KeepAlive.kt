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
class KeepAliveService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null

    private val check = object : Runnable {
        override fun run() {
            if (Jobs.running() == 0) { stopSelf(); return }
            h.postDelayed(this, 4000L)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("keep", "চলমান কমান্ড", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "keep")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Daily Browser")
            .setContentText("কমান্ড চলছে…")
            .setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(8, n)
        try {
            if (wl == null) wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termuxbrowser:jobs")
            if (wl?.isHeld != true) wl?.acquire(6 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
        h.removeCallbacks(check); h.postDelayed(check, 4000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacks(check)
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
    }

    companion object {
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }
}
