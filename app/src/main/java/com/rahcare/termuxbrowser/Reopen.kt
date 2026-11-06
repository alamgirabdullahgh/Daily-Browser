package com.rahcare.termuxbrowser

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * আপডেট বসলে Android অ্যাপের প্রসেস বন্ধ করে দেয় (এটা ঠেকানো যায় না)। তাই:
 *  ১) আপডেট শুরুর ঠিক আগে [arm] — "আপডেটের পর অ্যাপ আবার খুলতে হবে" চিহ্ন বসে (আর MainActivity ট্যাব সংরক্ষণ করে)
 *  ২) নতুন সংস্করণ বসলে [afterUpdate] — সরাসরি খোলার চেষ্টা; ৫ সেকেন্ডেও না খুললে [fallback]: পর্দা-জুড়ে-খোলা (full-screen) নোটিফিকেশন, শব্দ/কাঁপুনি ছাড়া
 *  ৩) InstallClicker সার্ভিস আবার চালু হলে সেখান থেকেও খোলার চেষ্টা (অ্যাক্সেসিবিলিটি সার্ভিস ব্যাকগ্রাউন্ড থেকে অ্যাপ খুলতে পারে)
 *  ৪) অ্যাপ খুললেই MainActivity.onResume → [done]: চিহ্ন ও নোটিফিকেশন মোছে; ট্যাব আগের মতোই ফেরে (restore())
 */
object Reopen {
    private const val NID = 31
    private const val CH = "reopen"
    private const val WINDOW_MS = 15 * 60 * 1000L
    private fun sp(c: Context) = c.getSharedPreferences("reopen", Context.MODE_PRIVATE)

    fun arm(c: Context) { try { sp(c).edit().putLong("want", System.currentTimeMillis()).commit() } catch (_: Exception) {} }

    fun pending(c: Context): Boolean {
        val w = sp(c).getLong("want", 0L)
        return w > 0L && System.currentTimeMillis() - w < WINDOW_MS
    }

    /** অ্যাপ সামনে এলে ডাকা হয় */
    fun done(c: Context) {
        try { sp(c).edit().remove("want").apply() } catch (_: Exception) {}
        try { c.getSystemService(NotificationManager::class.java).cancel(NID) } catch (_: Exception) {}
    }

    private fun go(c: Context) = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** সরাসরি খোলার চেষ্টা (Android ব্যাকগ্রাউন্ড থেকে আটকালে নিঃশব্দে ব্যর্থ হয়, তাই ফলাফল জানা যায় না) */
    fun launch(c: Context) { try { c.startActivity(go(c)) } catch (_: Exception) {} }

    /** MY_PACKAGE_REPLACED-এ ডাকা হয় */
    fun afterUpdate(c: Context) {
        if (!pending(c)) return
        launch(c)
        try {
            val pi = PendingIntent.getBroadcast(c, NID, Intent(c, ReopenCheck::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000L, pi)
        } catch (_: Exception) {}
    }

    /** ৫ সেকেন্ড পরও অ্যাপ না খুলে থাকলে: শব্দহীন full-screen নোটিফিকেশন, যেটা অ্যাপকে সামনে আনে */
    fun fallback(c: Context) {
        if (!pending(c)) return
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "আপডেটের পর আবার খোলা", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
            val pi = PendingIntent.getActivity(c, NID, go(c), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NID, Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Daily Browser আপডেট হয়েছে")
                .setContentText("খুলছে… না খুললে চাপুন")
                .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}

class ReopenCheck : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Reopen.fallback(c) }
}
