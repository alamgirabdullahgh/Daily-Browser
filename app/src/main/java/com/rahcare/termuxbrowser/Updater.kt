package com.rahcare.termuxbrowser

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/** GitHub-এর নতুন রিলিজ ধরে এবং অ্যাপের ভেতর থেকেই ইনস্টল করে */
object Updater {
    private const val REPO = "alamgirabdullahgh/Daily-Browser"
    class Info(val v: Int, val url: String)

    /** অ্যাপের পর্দা এখন সামনে আছে কি না (MainActivity ঠিক করে) — সামনে থাকলে ফোন নীরব ইনস্টল না করে নিশ্চিত-পর্দা চায় */
    @Volatile var fg = false
    /** ইনস্টল বাতিল/ব্যর্থ হলে বোতাম আগের অবস্থায় ফেরাতে (MainActivity বসায়) */
    @Volatile var onFail: (() -> Unit)? = null
    private const val MAX_SILENT_FAILS = 3
    private fun sp(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** নিশ্চিত-পর্দা ছাড়া (নীরব) ইনস্টল চেষ্টা করা যাবে কি না: Android 12+, এবং পরপর ৩ বার ফোন আটকে না থাকলে */
    fun silentAllowed(c: Context) = Build.VERSION.SDK_INT >= 31 && sp(c).getInt("silent_fail", 0) < MAX_SILENT_FAILS
    fun noteSilentFail(c: Context) { sp(c).edit().putInt("silent_fail", sp(c).getInt("silent_fail", 0) + 1).apply() }
    fun noteSilentOk(c: Context) { sp(c).edit().putInt("silent_fail", 0).apply() }
    /** একই সংস্করণের জন্য নিজে থেকে চেষ্টা সর্বোচ্চ ৩ বার */
    fun autoTry(c: Context, v: Int): Boolean {
        val n = if (sp(c).getInt("auto_v", 0) == v) sp(c).getInt("auto_n", 0) else 0
        if (n >= 3) return false
        sp(c).edit().putInt("auto_v", v).putInt("auto_n", n + 1).apply(); return true
    }
    fun markSilent(c: Context, silent: Boolean) { sp(c).edit().putBoolean("inst_silent", silent).putBoolean("inst_pending", false).apply() }
    fun markPending(c: Context) { sp(c).edit().putBoolean("inst_pending", true).apply() }
    /** আপডেটের পর নতুন সংস্করণ চালু হলে: নীরব ইনস্টল কাজ করেছিল কি না মিলিয়ে দেখে */
    fun afterUpdate(c: Context) {
        val s = sp(c)
        if (s.getBoolean("inst_silent", false) && !s.getBoolean("inst_pending", false)) noteSilentOk(c)
        s.edit().remove("inst_silent").remove("inst_pending").apply()
        // আপডেটের পর "আপডেট হয়েছে" নোটিফিকেশন নয়; অ্যাপ আবার খোলার ব্যবস্থা Reopen-এ (সরাসরি চেষ্টা, না হলে শব্দহীন full-screen)
        try {
            Reopen.afterUpdate(c)
            c.getSystemService(NotificationManager::class.java).deleteNotificationChannel("updated")
            c.getSystemService(NotificationManager::class.java).cancel(12)
        } catch (_: Exception) {}
    }
    /** নিশ্চিত করার পর্দা ব্যাকগ্রাউন্ড থেকে খোলা যায় না, তাই নোটিফিকেশনে চাপলে খোলে */
    fun confirmNotice(c: Context, ui: Intent) {
        try { InstallClicker.instance?.startActivity(Intent(ui).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("update_q", "Daily Browser আপডেট", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(c, 12, ui, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(12, Notification.Builder(c, "update_q").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Daily Browser-এর নতুন সংস্করণ তৈরি").setContentText("চাপুন — ইনস্টল নিশ্চিত করুন")
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    fun curVer(c: Context): Int {
        @Suppress("DEPRECATION")
        return c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। নতুন সংস্করণ থাকলে Info, নইলে null */
    fun check(): Info? = try {
        val cn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        cn.instanceFollowRedirects = false; cn.connectTimeout = 10000; cn.readTimeout = 10000
        val tag = (cn.getHeaderField("Location") ?: "").substringAfterLast('/')
        cn.disconnect()
        Info(tag.substringAfterLast('.').toInt(), "https://github.com/$REPO/releases/download/$tag/DailyBrowser.apk")
    } catch (e: Exception) { null }

    /** APK নামিয়ে PackageInstaller-এ দেয়; ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। ব্যর্থ হলে বার্তা ফেরত */
    fun install(c: Context, url: String, silent: Boolean, progress: (Int) -> Unit): String? {
        val inst = c.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(
            if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = inst.createSession(p)
        return try {
            val s = inst.openSession(id)
            val cn = URL(url).openConnection() as HttpURLConnection
            cn.connectTimeout = 15000; cn.readTimeout = 30000
            if (cn.responseCode != 200) throw Exception("HTTP ${cn.responseCode}")
            val total = cn.contentLengthLong
            cn.inputStream.use { ins ->
                s.openWrite("a", 0, -1).use { out ->
                    val buf = ByteArray(32 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; progress(pct) } }
                    }
                    s.fsync(out)
                }
            }
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java).putExtra("silent", silent),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            markSilent(c, silent)
            ClickGate.open(c) // নিশ্চিত-পর্দা এলে InstallClicker নিজে "Install" চাপবে (শুধু এই অ্যাপের নাম থাকলে)
            Reopen.arm(c)     // আপডেটের পর অ্যাপ আবার খুলতে হবে (অন্য অ্যাপ ইনস্টল হলে MY_PACKAGE_REPLACED আসে না, তাই ক্ষতি নেই)
            s.commit(pi.intentSender); null
        } catch (e: Exception) {
            try { inst.abandonSession(id) } catch (_: Exception) {}
            "${e.javaClass.simpleName}: ${e.message?.take(70)}"
        }
    }
}

/** ইনস্টলারের ফল; নিশ্চিত করার পর্দা লাগলে: অ্যাপ সামনে থাকলে সরাসরি খোলে, নইলে নোটিফিকেশন */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ui = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                Updater.markPending(c)
                if (i.getBooleanExtra("silent", false)) Updater.noteSilentFail(c)
                if (ui != null) {
                    ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Updater.fg) { try { c.startActivity(ui) } catch (_: Exception) { Updater.confirmNotice(c, ui) } }
                    else Updater.confirmNotice(c, ui)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                Updater.markPending(c)
                if (i.getBooleanExtra("silent", false)) Updater.noteSilentFail(c)
                try { Updater.onFail?.invoke() } catch (_: Exception) {}
                Toast.makeText(c, "আপডেট হয়নি: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** আপডেটের পর নতুন সংস্করণ চালু হলে */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.afterUpdate(c) }
}
