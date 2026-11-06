package com.rahcare.termuxbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** একটি বাটন = একটি Termux কমান্ড। background=true হলে Termux খোলে না, ব্রাউজারেই থাকা যায় */
data class Cmd(val id: String, var title: String, var script: String, var background: Boolean = true)

/** ব্যাকগ্রাউন্ডে চলা কাজ */
data class Job(val key: String, val title: String, var status: String, var out: String, val time: Long) {
    var end = 0L          // শেষ হওয়ার সময় (০ = এখনো চলছে)
    var script = ""
    var pct = -1          // আউটপুটে পাওয়া শেষ "NN%" (না পেলে -১)
    @Volatile var lastPush = 0L
    @Volatile var lastAttach = 0L    // শেষ কখন লাইভ সংযোগ আবার জোড়ার চেষ্টা হয়েছে
    @Volatile var attaches = 0       // কতবার চেষ্টা হয়েছে (ধীরে ধীরে বিরতি বাড়াতে)
    val links = java.util.concurrent.atomic.AtomicInteger(0)   // এখন কয়টি লাইভ সংযোগ খোলা (০ = আউটপুট আর আসছে না)
    @Volatile var reattach = false   // অ্যাপ নতুন করে চালুর পর লগ থেকে আবার স্ট্রিম নেওয়া হচ্ছে

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
    private val percent = Regex("(\\d{1,3})\\s?%")

    /** পর্দায় দেখানোর লেখা: রঙের কোড বাদ, \r দিয়ে বদলানো লাইনে শুধু শেষ অংশ */
    fun text(): String {
        var s = if (out.length > 20000) out.takeLast(20000) else out
        s = ansi.replace(s, "").replace("\r\n", "\n")
        return s.split('\n').joinToString("\n") { it.substringAfterLast('\r') }
    }

    fun refreshPct() {
        val m = percent.findAll(text().takeLast(400)).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        pct = if (m != null && m in 0..100) m else -1
    }

    fun elapsed() = ((if (end > 0) end else System.currentTimeMillis()) - time) / 1000
    fun lastLine() = text().lines().lastOrNull { it.isNotBlank() }?.trim()?.take(120) ?: ""
}

object Jobs {
    // একাধিক থ্রেড (লাইভ স্ট্রিম, UI, রিসিভার) একসাথে পড়ে/লেখে — সাধারণ তালিকায় ক্র্যাশের ঝুঁকি ছিল
    val list = java.util.concurrent.CopyOnWriteArrayList<Job>()

    /** exit কোড থেকে অবস্থা: 130/137/143 = ব্যবহারকারী বা সিস্টেম থামিয়েছে (ব্যর্থতা নয়) */
    fun statusFor(code: Int) = when (code) { 0 -> "ok"; 130, 137, 143 -> "stopped"; else -> "fail" }

    /** শেষ হওয়া সব কাজ মুছে ফেলে (চলমানগুলো থাকে); কয়টা মুছল তা ফেরত দেয় */
    fun clearFinished(): Int { val n = list.count { it.status != "running" }; list.removeIf { it.status != "running" }; return n }
    fun start(title: String, script: String = ""): Job {
        val j = Job("j${System.nanoTime()}", title, "running", "", System.currentTimeMillis())
        j.script = script
        list.add(j); if (list.size > 30) list.removeAt(0); return j
    }
    fun find(key: String) = list.firstOrNull { it.key == key }

    /** কাজের তালিকা সেভ করে, যাতে অ্যাপ বন্ধ/আপডেট হলেও ট্যাব ও অবস্থা হারায় না */
    fun save(c: Context) {
        try {
            val a = JSONArray()
            synchronized(list) {
                list.forEach { j ->
                    a.put(JSONObject().put("k", j.key).put("t", j.title).put("st", j.status).put("o", j.out.takeLast(20000))
                        .put("tm", j.time).put("e", j.end).put("s", j.script))
                }
            }
            Store.prefs(c).edit().putString("jobs", a.toString()).apply()
        } catch (_: Exception) {}
    }

    fun load(c: Context) {
        try {
            val a = JSONArray(Store.prefs(c).getString("jobs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (find(o.getString("k")) != null) continue
                // ২ দিনের বেশি পুরনো শেষ-হওয়া কাজ আর ফেরাই না (তালিকা জমতে না দিতে)
                val st0 = o.getString("st"); val t0 = o.optLong("e").takeIf { it > 0 } ?: o.getLong("tm")
                if (st0 != "running" && System.currentTimeMillis() - t0 > 2 * 86400000L) continue
                val j = Job(o.getString("k"), o.getString("t"), o.getString("st"), o.optString("o"), o.getLong("tm"))
                j.end = o.optLong("e"); j.script = o.optString("s"); j.refreshPct()
                list.add(j)
            }
        } catch (_: Exception) {}
    }
    fun running() = list.count { it.status == "running" }
}

/** ইনস্টল ব্যর্থ হলে আর Termux:X11 খোলে না (আগে ; দিয়ে জোড়া ছিল বলে ব্যর্থ হলেও খুলে যেত) */
const val DESKTOP = "pkg install -y x11-repo && pkg install -y termux-x11-nightly openbox tint2 xterm pcmanfm || { echo 'ইনস্টল ব্যর্থ — উপরের ত্রুটি দেখুন'; exit 1; }\n" +
    "pkill -f termux-x11 2>/dev/null\n" +
    "(termux-x11 :0 >/dev/null 2>&1 &)\n" +
    "sleep 3\n" +
    "echo 'ডেস্কটপ সার্ভার চালু — Termux:X11 খুলছে'\n" +
    "am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1\n" +
    "export DISPLAY=:0\n" +
    "(tint2 >/dev/null 2>&1 &)\n" +
    "exec openbox-session"

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("cmds", Context.MODE_PRIVATE)

    private val defaults = listOf(
        Cmd("d1", "প্যাকেজ আপডেট", "pkg update -y && pkg upgrade -y"),
        Cmd("d2", "স্টোরেজ অনুমতি", "termux-setup-storage", false),
        Cmd("d3", "Python ইনস্টল", "pkg install -y python"),
        Cmd("d4", "Git ইনস্টল", "pkg install -y git"),
        Cmd("d5", "Node.js ইনস্টল", "pkg install -y nodejs"),
        Cmd("d6", "SSH সার্ভার চালু", "pkg install -y openssh && sshd && echo 'sshd চালু, পোর্ট 8022' && whoami"),
        Cmd("d7", "ডিস্ক ব্যবহার", "df -h \$HOME"),
        Cmd("d8", "আইপি দেখুন", "(command -v ifconfig >/dev/null || pkg install -y net-tools) && ifconfig 2>/dev/null | grep -E \"inet |wlan|rmnet\" || /system/bin/ip -4 addr 2>/dev/null | grep inet"),
        Cmd("d9", "সিস্টেম তথ্য", "uname -a && echo && free -h 2>/dev/null | head -3"),
        Cmd("d10", "🖥 কম্পিউটার মোড (হালকা ডেস্কটপ)", DESKTOP),
        // ওয়েব-টার্মিনাল: Termux-এ ttyd চালু হয়, তারপর অ্যাপ নতুন ট্যাবে খোলে। শুধু 127.0.0.1-এ, পাসওয়ার্ডসহ (@@WEBPW@@ অ্যাপ বসায়)
        Cmd("d12", "🌐 ওয়েব-টার্মিনাল (ট্যাবে পূর্ণ টার্মিনাল)",
            "pkg install -y ttyd && { pkill -x ttyd 2>/dev/null; nohup setsid ttyd -W -i 127.0.0.1 -p 7681 -c tb:@@WEBPW@@ bash -l >/dev/null 2>&1 & sleep 2; echo 'ওয়েব-টার্মিনাল চালু — নতুন ট্যাব খুলছে'; }"),
        Cmd("d13", "⏹ আটকে থাকা প্যাকেজ কাজ থামান",
            "pkill -x apt 2>/dev/null; pkill -x apt-get 2>/dev/null; pkill -x dpkg 2>/dev/null; sleep 1; dpkg --configure -a --force-confold 2>&1 | tail -5; echo 'থামানো হয়েছে — এখন নতুন কমান্ড চালাতে পারেন'"),
        // ধীর/আটকে যাওয়া মিরর থেকে Termux-এর ডিফল্ট (Cloudflare) মিররে ফেরায়। আগে পৌঁছানো যায় কিনা যাচাই করে, পুরনো ফাইলের কপি রাখে
        Cmd("d14", "🔄 মিরর ডিফল্টে ফেরান (আপডেট ধীর হলে)",
            "U=https://packages-cf.termux.dev/apt/termux-main; S=\$PREFIX/etc/apt/sources.list\n" +
            "if curl -fsI --max-time 20 \"\$U/dists/stable/InRelease\" >/dev/null 2>&1; then cp \"\$S\" \"\$S.bak\" 2>/dev/null; echo \"deb \$U stable main\" > \"\$S\"; echo 'মিরর ডিফল্টে ফেরানো হয়েছে'; pkg update -y; " +
            "else echo 'ডিফল্ট মিরর এখন পৌঁছানো যাচ্ছে না — Termux-এ termux-change-repo চালিয়ে অন্য মিরর বাছুন'; exit 1; fi"),
        Cmd("d11", "কম্পিউটার মোড বন্ধ", "pkill -f openbox; pkill -f tint2; pkill -f termux-x11; echo বন্ধ হয়েছে"),
    )

    fun all(c: Context): MutableList<Cmd> {
        val raw = sp(c).getString("list", null) ?: return defaults.map { it.copy() }.toMutableList()
        val out = mutableListOf<Cmd>()
        val seen = (sp(c).getString("seen", "") ?: "").split(",").toSet()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Cmd(o.getString("id"), o.getString("t"), o.getString("s"), o.optBoolean("b", true)))
            }
        } catch (_: Exception) { return defaults.map { it.copy() }.toMutableList() }
        var changed = false
        // আগের সংস্করণে সব কমান্ড Termux স্ক্রিনে চলত — এখন ডিফল্ট ব্যাকগ্রাউন্ড
        if (sp(c).getInt("ver", 1) < 2) { out.forEach { it.background = true }; changed = true }
        // স্টোরেজ অনুমতি ও কম্পিউটার মোডে Termux-এর আসল পর্দা লাগে (Android-এর অনুমতি-পর্দা/ডেস্কটপ), ব্যাকগ্রাউন্ডে চললে কিছুই হয় না
        if (sp(c).getInt("ver", 1) < 3) { out.filter { it.id == "d2" || it.id == "d10" }.forEach { it.background = false }; changed = true }
        // পুরনো "ip" কমান্ড Termux-এ নেই (ip: command not found) — নতুনটা দিয়ে বদলাই
        if (sp(c).getInt("ver", 1) < 4) { out.filter { it.id == "d8" && it.script == "ip -4 addr | grep inet" }.forEach { it.script = defaults.first { d -> d.id == "d8" }.script }; changed = true }
        // কম্পিউটার মোড আবার ব্রাউজারের ট্যাবেই চলবে (Termux পর্দায় নিয়ে যাবে না)
        if (sp(c).getInt("ver", 1) < 5) { out.filter { it.id == "d10" }.forEach { it.background = true }; changed = true }
        // পুরনো কম্পিউটার-মোড স্ক্রিপ্ট ব্যর্থ ইনস্টলেও X11 খুলত — নতুনটা বসাই
        if (sp(c).getInt("ver", 1) < 6) { out.filter { it.id == "d10" }.forEach { it.script = DESKTOP }; changed = true }
        for (d in defaults) if (d.id !in seen && out.none { it.id == d.id }) { out.add(d.copy()); changed = true }
        if (changed) save(c, out)
        return out
    }

    fun save(c: Context, list: List<Cmd>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("s", it.script).put("b", it.background)) }
        sp(c).edit().putString("list", a.toString()).putInt("ver", 6)
            .putString("seen", defaults.joinToString(",") { it.id }).apply()
    }

    fun prefs(c: Context) = c.getSharedPreferences("browser", Context.MODE_PRIVATE)
}
