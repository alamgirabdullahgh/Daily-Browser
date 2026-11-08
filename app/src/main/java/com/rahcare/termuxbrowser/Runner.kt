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
    fun verified(c: Context): Boolean {
        val sp = Store.prefs(c)
        if (sp.getBoolean("termux_bad", false)) return false   // Termux-এর ডেটা মুছে/সেটিং হারিয়ে সংযোগ ভেঙেছে — আগের সফল কাজ দেখে ভুল করে "ঠিক আছে" ধরি না
        return sp.getBoolean("termux_ok", false) || Jobs.list.any { it.status == "ok" }
    }

    /** সংযোগ ভাঙা ধরা পড়েছে (যেমন Termux-এর ডেটা ক্লিয়ার করায় allow-external-apps হারিয়েছে) */
    fun markBad(c: Context) { Store.prefs(c).edit().putBoolean("termux_ok", false).putBoolean("termux_bad", true).apply() }

    /** সংযোগ ভেঙে যাওয়ায় যে কমান্ডটা চালানো যায়নি; সেটআপ শেষ হলে অ্যাপ নিজে থেকে এটা আবার চালায় */
    @Volatile var pending: Cmd? = null
    @Volatile var pendingAt = 0L

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন: ছোট কমান্ড পাঠিয়ে সংযোগ যাচাই। null = ঠিক; "perm" = allow-external-apps/অনুমতি নেই; "timeout" = সাড়া নেই */
    fun check(c: Context, waitMs: Long = 6000): String? {
        val r = probe(c, waitMs)
        if (r == null) Store.prefs(c).edit().putBoolean("termux_ok", true).remove("termux_bad").apply()
        else if (r == "perm" || r.contains("allow-external-apps", true)) markBad(c)
        return r
    }

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
        Jobs.list.filter { it.status == "running" && !it.connecting && it.links.get() == 0 && now - it.time > minAge }.forEach { j ->
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

    // ---------- Termux সংযোগ যাচাই ----------
    private val probes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()
    private val probeErr = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** ResultReceiver ডাকে: err খালি = Termux সাড়া দিয়েছে */
    fun probeDone(id: String, err: String) { probeErr[id] = err; probes[id]?.countDown() }

    private fun startSvc(c: Context, i: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
    }

    /** একটা ছোট কমান্ড পাঠিয়ে Termux সাড়া দেয় কিনা দেখে। null = সংযোগ ঠিক; "timeout" = সাড়া নেই; "perm" = অনুমতি/allow-external-apps; অন্যথায় Termux-এর ত্রুটি */
    private fun probe(c: Context, waitMs: Long): String? {
        val id = "p${System.nanoTime()}"
        val latch = java.util.concurrent.CountDownLatch(1); probes[id] = latch
        try {
            val i = Intent(ACTION).setClassName(TERMUX, SERVICE)
            i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "echo tb_ok"))
            val r = Intent(c, ResultReceiver::class.java).setData(Uri.parse("probe://$id")).putExtra("probe", true).putExtra("pid", id)
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", PendingIntent.getBroadcast(c, 0, r, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            startSvc(c, i)
        } catch (e: SecurityException) { probes.remove(id); return "perm"
        } catch (e: Exception) { probes.remove(id); return "ত্রুটি: ${e.message}" }
        val got = try { latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        probes.remove(id); val err = probeErr.remove(id)
        return if (!got) "timeout" else if (err.isNullOrBlank()) null else err
    }

    private fun refresh(c: Context) { try { c.sendBroadcast(Intent(MainActivity.REFRESH).setPackage(c.packageName)) } catch (_: Exception) {} }

    private fun failJob(c: Context, job: Job, msg: String) {
        job.connecting = false
        job.status = "fail"; job.end = System.currentTimeMillis(); job.out = msg
        Jobs.save(c, true); refresh(c)
    }

    private const val CONN_HEAD = "▸ Termux-এর সাথে সংযোগ যাচাই"

    /** কাজ শুরুর আগে: Termux সংযুক্ত কিনা দেখে; না থাকলে নিজে ৩ বার জোড়ার চেষ্টা; জুড়লে কাজ শুরু */
    private fun connectThenLaunch(c: Context, cmd: Cmd, job: Job, script: String) {
        val waits = longArrayOf(6000, 8000, 10000)
        var last = "timeout"
        for ((n, w) in waits.withIndex()) {
            job.out = "$CONN_HEAD\nযাচাই চলছে… (চেষ্টা ${n + 1}/${waits.size})"; refresh(c)
            val r = probe(c, w)
            if (job.status != "running") return   // ইতিমধ্যে ব্যবহারকারী থামিয়েছেন
            if (r == null) { last = ""; break }
            last = r
            if (r == "perm" || r.contains("allow-external-apps", true)) break   // আবার চেষ্টায় লাভ নেই
            try { Thread.sleep(1500) } catch (_: InterruptedException) {}
        }
        if (last.isNotEmpty()) {
            val why = if (last == "perm" || last.contains("allow-external-apps", true))
                "Termux বাইরের অ্যাপকে কমান্ড চালাতে দিচ্ছে না (allow-external-apps চালু নেই বা RUN_COMMAND অনুমতি নেই)।\n\nসেটআপ কমান্ডটা আমি ক্লিপবোর্ডে কপি করে Termux খুলে দিচ্ছি: সেখানে স্ক্রিনে লম্বা চেপে Paste করে Enter দিন, তারপর এই অ্যাপে ফিরে আসুন — কমান্ডটা নিজে থেকেই আবার চলবে।"
            else if (last == "timeout")
                "৩ বার চেষ্টার পরও Termux সাড়া দেয়নি।\n\nTermux অ্যাপটা একবার খুলে আবার চেষ্টা করুন। ফোনের ব্যাটারি-সেটিংসে Termux-কে \"Unrestricted\" করা থাকলে সাধারণত এটা ঠিক থাকে।"
            else "Termux থেকে ত্রুটি: $last"
            if (last == "perm" || last.contains("allow-external-apps", true)) {
                markBad(c); pending = cmd; pendingAt = System.currentTimeMillis()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { (c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("termux-setup", SETUP_CMD)) } catch (_: Exception) {}
                    openTermux(c)   // নিজেই Termux খুলে দিই — শুধু Paste + Enter বাকি
                }
            }
            failJob(c, job, "$CONN_HEAD\n✗ সংযোগ হয়নি\n\n$why")
            return
        }
        job.out = ""; job.connecting = false; refresh(c)
        val err = launchJob(c, job, script)
        if (err != null) failJob(c, job, "▸ কাজ শুরু\n$err")
    }

    /** আসল কমান্ড Termux-এ পাঠায় (ব্যাকগ্রাউন্ড); ত্রুটি থাকলে বার্তা ফেরত */
    private fun launchJob(c: Context, job: Job, userScript: String): String? {
        val i = Intent(ACTION).setClassName(TERMUX, SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        val script = try { Live.wrap(job, userScript) } catch (_: Exception) { userScript }
        val r = Intent(c, ResultReceiver::class.java).setData(Uri.parse("job://${job.key}")).putExtra("job", job.key)
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", PendingIntent.getBroadcast(c, 0, r, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        return try { startSvc(c, i); null }
        catch (e: SecurityException) { "অনুমতি নেই। Termux-এ চালান: echo 'allow-external-apps=true' >> ~/.termux/termux.properties, তারপর Termux রিস্টার্ট করুন" }
        catch (e: ActivityNotFoundException) { "Termux সার্ভিস পাওয়া যায়নি" }
        catch (e: Exception) { "ত্রুটি: ${e.message}" }
    }

    fun run(c: Context, cmd: Cmd): Pair<Job?, String?> {
        if (!installed(c)) return Pair(null, "Termux ইনস্টল করা নেই (F-Droid থেকে ইনস্টল করুন)")
        if (!hasPermission(c)) return Pair(null, "Termux-এর RUN_COMMAND অনুমতি দেওয়া নেই")
        if (cmd.background) {
            // স্ক্রিপ্টে ধাপের শিরোনাম ("▸ ...") না থাকলে পুরোটা এক ধাপ, কমান্ডের নামেই
            val script = if (cmd.script.contains("▸")) cmd.script else "echo '▸ ${cmd.title.replace("'", "")}'\n" + cmd.script
            val job = Jobs.start(cmd.title, cmd.script)
            job.total = maxOf(1, script.lines().count { it.contains("▸") }) + 1   // +১ = সংযোগ যাচাই
            job.connecting = true
            job.out = "$CONN_HEAD\nযাচাই চলছে…"
            KeepAliveService.start(c)
            Thread { try { connectThenLaunch(c, cmd, job, script) } catch (e: Exception) { failJob(c, job, "$CONN_HEAD\n✗ ত্রুটি: ${e.message}") } }.start()
            return Pair(job, null)
        }
        // Termux-এর নিজের স্ক্রিনে চলা কমান্ড: Termux নিজেই সামনে আসে
        val i = Intent(ACTION).setClassName(TERMUX, SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", Live.PKG_SHIM + cmd.script))
        return try { startSvc(c, i); Pair(null, null) }
        catch (e: SecurityException) { Pair(null, "অনুমতি নেই। Termux-এ চালান: echo 'allow-external-apps=true' >> ~/.termux/termux.properties, তারপর Termux রিস্টার্ট করুন") }
        catch (e: ActivityNotFoundException) { Pair(null, "Termux সার্ভিস পাওয়া যায়নি") }
        catch (e: Exception) { Pair(null, "ত্রুটি: ${e.message}") }
    }
}
