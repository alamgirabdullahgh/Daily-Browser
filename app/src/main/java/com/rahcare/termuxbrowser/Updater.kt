package com.rahcare.termuxbrowser

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/** GitHub-এর নতুন রিলিজ ধরে এবং অ্যাপের ভেতর থেকেই ইনস্টল করে */
object Updater {
    private const val REPO = "alamgirabdullahgh/Daily-Browser"
    class Info(val v: Int, val url: String)

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
    fun install(c: Context, url: String, progress: (Int) -> Unit): String? {
        val inst = c.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
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
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender); null
        } catch (e: Exception) {
            try { inst.abandonSession(id) } catch (_: Exception) {}
            "${e.javaClass.simpleName}: ${e.message?.take(70)}"
        }
    }
}

/** ইনস্টলারের ফল; নিশ্চিত করার পর্দা লাগলে খোলে */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ui = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (ui != null) try { c.startActivity(ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(c, "আপডেট হয়নি: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
