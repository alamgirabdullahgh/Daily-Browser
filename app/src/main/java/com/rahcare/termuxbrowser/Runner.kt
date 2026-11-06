package com.rahcare.termuxbrowser

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Termux-এর RUN_COMMAND ইন্টেন্ট দিয়ে কমান্ড চালায় */
object Runner {
    const val TERMUX = "com.termux"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    fun installed(c: Context) = try {
        c.packageManager.getPackageInfo(TERMUX, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Termux-এ একবার চালাতে হয় এমন সেটআপ কমান্ড (allow-external-apps চালু করে সেটিংস রিলোড করে) */
    const val SETUP_CMD = "mkdir -p ~/.termux && { grep -qs '^allow-external-apps=true' ~/.termux/termux.properties || echo 'allow-external-apps=true' >> ~/.termux/termux.properties; }; termux-reload-settings"

    /** Termux অ্যাপ খোলে; না পেলে false */
    fun openTermux(c: Context): Boolean = try {
        val i = c.packageManager.getLaunchIntentForPackage(TERMUX)
        if (i == null) false else { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i); true }
    } catch (_: Exception) { false }

    /** Termux থেকে অন্তত একবার সাড়া পাওয়া গেছে (কোনো কাজ সফল হয়েছে) — মানে সংযোগ ঠিক আছে */
    fun verified(c: Context) = Store.prefs(c).getBoolean("termux_ok", false) || Jobs.list.any { it.status == "ok" }

    fun hasPermission(c: Context) =
        c.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED

    /** (কাজ, ত্রুটি): ত্রুটি null হলে সফল; ব্যাকগ্রাউন্ড কমান্ড হলে কাজটাও ফেরত আসে (নতুন ট্যাবে দেখানোর জন্য) */
    /** অ্যাপ নতুন করে চালু হলে চলমান কাজের লগ আবার অ্যাপে স্ট্রিম করতে বলে */
    fun attach(c: Context, job: Job) {
        if (!installed(c) || !hasPermission(c)) return
        val i = Intent(ACTION).setClassName(TERMUX, SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", Live.attach(job)))
        try { if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (_: Exception) {}
    }

    /**
     * লাইভ সংযোগ ছিঁড়ে গেছে এমন চলমান কাজ আবার লগ থেকে জুড়ে দেয়। কাজ নিজে Termux-এ চলতেই থাকে;
     * শুধু অ্যাপের পর্দায় আউটপুট আসা থেমে যেত। force=true হলে অপেক্ষা না করে এখনই (অ্যাপে ফিরে এলে)।
     */
    fun reconnect(c: Context, minAge: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        Jobs.list.filter { it.status == "running" && it.links.get() == 0 && now - it.time > minAge }.forEach { j ->
            val gap = if (j.attaches < 4) 15000L else 60000L
            if (j.attaches >= 8 && !force) {
                // প্রায় ৫ মিনিট টানা চেষ্টার পরেও Termux সাড়া দেয়নি: কাজটা আর চলছে না ধরি (নইলে চিরকাল "চলছে…" ও নোটিফিকেশন থাকত)
                j.status = "fail"; j.end = now; j.reattach = false
                j.out = (j.out + "\n(Termux থেকে সাড়া মেলেনি — কাজটি সম্ভবত আর চলছে না)").trim()
                Jobs.save(c); c.sendBroadcast(Intent(MainActivity.REFRESH).setPackage(c.packageName))
                return@forEach
            }
            if (force || now - j.lastAttach > gap) {
                j.lastAttach = now; j.attaches++; j.reattach = true
                attach(c, j)
            }
        }
    }

    /** চলমান কাজের প্রক্রিয়া (ও তার সব সন্তান-প্রক্রিয়া, যেমন আটকে থাকা apt) Termux-এ থামায় */
    fun kill(c: Context, job: Job) {
        if (!installed(c) || !hasPermission(c)) return
        val k = job.key
        val script = "P=\"\$HOME/.tb/$k.pid\"\n" +
            "kt() { for c in \$(pgrep -P \"\$1\" 2>/dev/null); do kt \"\$c\"; done; kill -9 \"\$1\" 2>/dev/null; }\n" +
            "[ -f \"\$P\" ] && kt \"\$(cat \"\$P\")\"\n" +
            "rm -f \"\$P\"; termux-wake-unlock 2>/dev/null\n" +
            "echo \"@@EXIT:143:\$(date +%s)\" >> \"\$HOME/.tb/$k.log\"\n"
        val i = Intent(ACTION).setClassName(TERMUX, SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        try { if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (_: Exception) {}
    }

    fun run(c: Context, cmd: Cmd): Pair<Job?, String?> {
        if (!installed(c)) return Pair(null, "Termux ইনস্টল করা নেই (F-Droid থেকে ইনস্টল করুন)")
        if (!hasPermission(c)) return Pair(null, "Termux-এর RUN_COMMAND অনুমতি দেওয়া নেই")
        val i = Intent(ACTION).setClassName(TERMUX, SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", cmd.background)
        var job: Job? = null
        var script = cmd.script
        if (cmd.background) {
            job = Jobs.start(cmd.title, cmd.script)
            KeepAliveService.start(c)
            script = try { Live.wrap(job, cmd.script) } catch (_: Exception) { cmd.script }
            val r = Intent(c, ResultReceiver::class.java).setData(Uri.parse("job://${job.key}")).putExtra("job", job.key)
            val pi = PendingIntent.getBroadcast(c, 0, r, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        } else i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        if (!cmd.background) script = Live.PKG_SHIM + script
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        fun fail(msg: String): Pair<Job?, String?> {
            job?.status = "fail"; job?.out = msg; job?.end = System.currentTimeMillis()
            return Pair(null, msg)
        }
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            Pair(job, null)
        } catch (e: SecurityException) {
            fail("অনুমতি নেই। Termux-এ চালান: echo 'allow-external-apps=true' >> ~/.termux/termux.properties, তারপর Termux রিস্টার্ট করুন")
        } catch (e: ActivityNotFoundException) {
            fail("Termux সার্ভিস পাওয়া যায়নি")
        } catch (e: Exception) {
            fail("ত্রুটি: ${e.message}")
        }
    }
}
