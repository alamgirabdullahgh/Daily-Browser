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
    @Volatile var connecting = false // কাজ শুরুর আগে Termux সংযোগ যাচাই/জোড়া চলছে (এ সময় লগ থেকে আবার জোড়ার চেষ্টা হয় না)
    var total = 0                    // মোট ধাপ (সংযোগ যাচাই ধরে); ০ = অজানা

    /** ধাপের শিরোনাম-লাইনগুলো ("▸ ..."), আউটপুট থেকে */
    fun stepCount(): Int = text().lineSequence().count { it.startsWith("▸ ") }

    /** সম্পূর্ণ কাজের প্রগ্রেস (০–৯৯); ধাপ না থাকলে -১ (অনির্দিষ্ট)। বর্তমান ধাপের "NN%" থাকলে সেটাও ধরে */
    fun progress(): Int {
        val lines = text().lines()
        val starts = lines.indices.filter { lines[it].startsWith("▸ ") }
        if (starts.isEmpty()) return -1
        val t = maxOf(total, starts.size, 1)
        val active = lines.drop(starts.last()).joinToString("\n").takeLast(300)
        val f = percent.findAll(active).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 } ?: 0
        return (((starts.size - 1) + f / 100.0) / t * 100).toInt().coerceIn(0, 99)
    }

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
    fun save(c: Context, sync: Boolean = false) {
        try {
            val a = JSONArray()
            synchronized(list) {
                list.forEach { j ->
                    a.put(JSONObject().put("k", j.key).put("t", j.title).put("st", j.status).put("o", j.out.takeLast(20000))
                        .put("tm", j.time).put("e", j.end).put("s", j.script).put("tot", j.total))
                }
            }
            Store.prefs(c).edit().putString("jobs", a.toString()).let { if (sync) it.commit() else it.apply() }
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
                j.end = o.optLong("e"); j.script = o.optString("s"); j.total = o.optInt("tot", 0); j.refreshPct()
                list.add(j)
            }
        } catch (_: Exception) {}
    }
    fun running() = list.count { it.status == "running" }
}

/** কম্পিউটার মোড: শুরু থেকে শেষ — আপডেট/আপগ্রেড, x11-repo, ডেস্কটপ প্যাকেজ, সার্ভার চালু; কোনো ধাপ ব্যর্থ হলে বাকি ধাপ চলে না */
val DESKTOP: String = """echo '▸ ধাপ ১/৪: প্যাকেজ আপডেট ও আপগ্রেড'
pkg update -y && pkg upgrade -y || { echo 'আপডেট/আপগ্রেড ব্যর্থ — উপরের ত্রুটি দেখুন'; exit 1; }
echo '▸ ধাপ ২/৪: X11 রিপো'
pkg install -y x11-repo || { echo 'x11-repo ইনস্টল ব্যর্থ'; exit 1; }
pkg update -y || exit 1
echo '▸ ধাপ ৩/৪: ডেস্কটপ প্যাকেজ'
pkg install -y termux-x11-nightly openbox tint2 xterm pcmanfm dbus || { echo 'ডেস্কটপ প্যাকেজ ইনস্টল ব্যর্থ'; exit 1; }
command -v termux-x11 >/dev/null 2>&1 || { echo 'termux-x11 কমান্ড পাওয়া যায়নি'; exit 1; }
echo '▸ ধাপ ৪/৪: ডেস্কটপ চালু'
pkill -f termux-x11 2>/dev/null; pkill -x openbox 2>/dev/null; pkill -x tint2 2>/dev/null; sleep 1
export XDG_RUNTIME_DIR=§{TMPDIR:-§PREFIX/tmp}
(termux-x11 :0 >/dev/null 2>&1 &)
sleep 3
echo 'ডেস্কটপ সার্ভার চালু — Termux:X11 খুলছে'
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
export DISPLAY=:0
(tint2 >/dev/null 2>&1 &)
if command -v dbus-launch >/dev/null 2>&1; then exec dbus-launch --exit-with-session openbox-session; else exec openbox-session; fi""".replace('§', '$')

/** ধাপভিত্তিক ডিফল্ট স্ক্রিপ্ট: "▸ " দিয়ে শুরু লাইন = একটি ধাপের শিরোনাম (কাজের ট্যাবে হেডিং হয়ে দেখায়) */
const val D1 = "echo '▸ প্যাকেজ তালিকা আপডেট'\npkg update -y || exit 1\necho '▸ প্যাকেজ আপগ্রেড'\npkg upgrade -y || exit 1\necho 'সম্পন্ন'"
const val D3 = "echo '▸ Python ইনস্টল'\npkg install -y python || exit 1\necho '▸ সংস্করণ যাচাই'\npython --version && pip --version"
const val D5 = "echo '▸ Node.js ইনস্টল'\npkg install -y nodejs || exit 1\necho '▸ সংস্করণ যাচাই'\nnode -v && npm -v"

/** দৈনিক রুকইয়াহ অ্যাপের ৬,২৩৬ আয়াতের অডিও (Alafasy, মনো, ৩২kbps, ২২.০৫kHz, গতি ×১.২০) ফোনেই নামিয়ে সূরাভিত্তিক zip বানায়।
 *  আবার চালালে যা হয়ে গেছে তা বাদ দিয়ে বাকিটা থেকে এগোয়। রিলিজে তোলা আলাদা ধাপ (টোকেন লাগে বলে এই কমান্ডে নেই)। */
val AUDIO_PACK: String = """echo '▸ ধাপ ১/৩: টুল ইনস্টল'
pkg install -y ffmpeg parallel || exit 1
command -v ffmpeg >/dev/null 2>&1 && command -v parallel >/dev/null 2>&1 || { echo 'ffmpeg/parallel ইনস্টল ব্যর্থ'; exit 1; }
echo 'প্রস্তুত'

echo '▸ ধাপ ২/৩: ডাউনলোড ও এনকোড (৩২kbps, ২২.০৫kHz, ×১.২০) — ৬,২৩৬ আয়াত, সময় লাগবে'
mkdir -p §HOME/audio/src §HOME/audio/out §HOME/audio/zips && cd §HOME/audio
AYN="7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,11,8,3,9,5,4,7,3,6,3,5,4,5,6"
python3 - "§AYN" > list.txt <<'PY'
import sys
n=0
for s,c in enumerate([int(x) for x in sys.argv[1].split(',')],1):
    for v in range(1,c+1):
        n+=1; print('%03d%03d %d'%(s,v,n))
PY
enc() { p=§1; n=§2
  [ -s out/§n.mp3 ] && return 0
  for i in 1 2 3 4 5; do
    curl -sf --max-time 90 -o src/§p.mp3 https://everyayah.com/data/Alafasy_128kbps/§p.mp3 && \
    ffmpeg -v error -y -i src/§p.mp3 -ac 1 -af atempo=1.2 -ar 22050 -c:a libmp3lame -b:a 32k -f mp3 out/§n.mp3.tmp && \
    mv out/§n.mp3.tmp out/§n.mp3 && rm -f src/§p.mp3 && return 0
    sleep §((i*3))
  done; return 1; }
export -f enc
( while true; do sleep 15; h=§(ls out/*.mp3 2>/dev/null | wc -l); echo "ডাউনলোড হয়েছে: §h/6236 (§((h*100/6236))%)"; [ "§h" -ge 6236 ] && break; done ) &
MON=§!
for pass in 1 2 3; do
  cat list.txt | parallel -j3 --colsep ' ' 'enc {1} {2}' || true
  have=§(ls out/*.mp3 2>/dev/null | wc -l); echo "পাস §pass শেষ: §have/6236"
  [ "§have" -ge 6236 ] && break
done
kill §MON 2>/dev/null
have=§(ls out/*.mp3 2>/dev/null | wc -l)
[ "§have" -eq 6236 ] || { echo "সব ফাইল আসেনি (§have/6236) — এই কমান্ডটা আবার চালান, যা হয়ে গেছে তা বাদ দিয়ে বাকিটা করবে"; exit 1; }
echo 'সব ৬,২৩৬টা আয়াত নামানো হয়েছে'

echo '▸ ধাপ ৩/৩: সূরাভিত্তিক zip তৈরি'
python3 - "§AYN" <<'PY'
import sys,zipfile
n=0
for s,c in enumerate([int(x) for x in sys.argv[1].split(',')],1):
    with zipfile.ZipFile('zips/s%03d.zip'%s,'w',zipfile.ZIP_STORED) as z:
        for v in range(c):
            n+=1; z.write('out/%d.mp3'%n,'%d.mp3'%n)
print('zip তৈরি, মোট আয়াত',n)
PY
du -sh zips
echo "সম্পন্ন: §HOME/audio/zips -এ s001.zip … s114.zip। GitHub রিলিজে তুলতে Termux-এ gh auth login করে gh release create চালান।"""".replace('§', '$')

object Store {
    /** আগের সংস্করণের ডিফল্ট স্ক্রিপ্ট → নতুন ধাপভিত্তিক স্ক্রিপ্ট (ব্যবহারকারী বদলে থাকলে ছোঁয়া হয় না) */
    private val legacy = mapOf(
        "d1" to Pair("pkg update -y && pkg upgrade -y && echo 'সম্পন্ন'", D1),
        "d3" to Pair("pkg install -y python && python --version && pip --version", D3),
        "d5" to Pair("pkg install -y nodejs && node -v && npm -v", D5)
    )
    private fun sp(c: Context) = c.getSharedPreferences("cmds", Context.MODE_PRIVATE)

    private val defaults = listOf(
        Cmd("d1", "প্যাকেজ আপডেট ও আপগ্রেড", D1),
        Cmd("d2", "স্টোরেজ অনুমতি", "termux-setup-storage", false),
        Cmd("d3", "Python ইনস্টল", D3),
        Cmd("d4", "Git ইনস্টল", "pkg install -y git && git --version"),
        Cmd("d5", "Node.js ইনস্টল", D5),
        Cmd("d6", "SSH সার্ভার চালু", "pkg install -y openssh || exit 1\npkill -x sshd 2>/dev/null; sshd && echo 'sshd চালু, পোর্ট 8022'\necho \"ব্যবহারকারী: \$(whoami)\"\necho 'পাসওয়ার্ড সেট করতে Termux স্ক্রিনে passwd চালান'"),
        Cmd("d7", "ডিস্ক ব্যবহার", "df -h \$HOME"),
        Cmd("d8", "আইপি দেখুন", "(command -v ifconfig >/dev/null || pkg install -y net-tools) && ifconfig 2>/dev/null | grep -E \"inet |wlan|rmnet\" || /system/bin/ip -4 addr 2>/dev/null | grep inet"),
        Cmd("d9", "সিস্টেম তথ্য", "uname -a && echo && free -h 2>/dev/null | head -3"),
        Cmd("d10", "🖥 কম্পিউটার মোড (হালকা ডেস্কটপ)", DESKTOP),
        // ওয়েব-টার্মিনাল: Termux-এ ttyd চালু হয়, তারপর অ্যাপ নতুন ট্যাবে খোলে। শুধু 127.0.0.1-এ, পাসওয়ার্ডসহ (@@WEBPW@@ অ্যাপ বসায়)
        Cmd("d12", "🌐 ওয়েব-টার্মিনাল (ট্যাবে পূর্ণ টার্মিনাল)",
            "pkg install -y ttyd || exit 1\n" +
            "pkill -x ttyd 2>/dev/null; sleep 1\n" +
            "nohup ttyd -W -i 127.0.0.1 -p 7681 -c tb:@@WEBPW@@ bash -l >/dev/null 2>&1 &\n" +
            "sleep 3\n" +
            "if curl -s -o /dev/null --max-time 5 -u tb:@@WEBPW@@ http://127.0.0.1:7681/; then echo 'ওয়েব-টার্মিনাল চালু — নতুন ট্যাব খুলছে'; else echo 'ttyd চালু হয়নি — উপরের ত্রুটি দেখুন'; exit 1; fi"),
        Cmd("d13", "⏹ আটকে থাকা প্যাকেজ কাজ থামান",
            "pkill -x apt 2>/dev/null; pkill -x apt-get 2>/dev/null; pkill -x dpkg 2>/dev/null; sleep 1; dpkg --configure -a --force-confold 2>&1 | tail -5; echo 'থামানো হয়েছে — এখন নতুন কমান্ড চালাতে পারেন'"),
        // ধীর/আটকে যাওয়া মিরর থেকে Termux-এর ডিফল্ট (Cloudflare) মিররে ফেরায়। আগে পৌঁছানো যায় কিনা যাচাই করে, পুরনো ফাইলের কপি রাখে
        Cmd("d14", "🔄 মিরর ডিফল্টে ফেরান (আপডেট ধীর হলে)",
            "U=https://packages-cf.termux.dev/apt/termux-main; S=\$PREFIX/etc/apt/sources.list\n" +
            "if curl -fsI --max-time 20 \"\$U/dists/stable/InRelease\" >/dev/null 2>&1; then cp \"\$S\" \"\$S.bak\" 2>/dev/null; echo \"deb \$U stable main\" > \"\$S\"; echo 'মিরর ডিফল্টে ফেরানো হয়েছে'; pkg update -y; " +
            "else echo 'ডিফল্ট মিরর এখন পৌঁছানো যাচ্ছে না — Termux-এ termux-change-repo চালিয়ে অন্য মিরর বাছুন'; exit 1; fi"),
        Cmd("d11", "কম্পিউটার মোড বন্ধ", "pkill -f openbox; pkill -f tint2; pkill -f termux-x11; echo বন্ধ হয়েছে"),
        Cmd("d15", "🎧 রুকইয়াহ অডিও প্যাক ডাউনলোড (৩২kbps×১.২০)", AUDIO_PACK),
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
        // v7: কমান্ডগুলো নিজে-সারানো প্রিলুড ও পূর্ণ কম্পিউটার-মোড স্ক্রিপ্টে নতুন করে বসানো
        if (sp(c).getInt("ver", 1) < 7) { out.forEach { o -> defaults.firstOrNull { it.id == o.id }?.let { n -> o.script = n.script; o.title = n.title } }; changed = true }
        // v8: অপরিবর্তিত ডিফল্ট কমান্ডগুলো ধাপভিত্তিক স্ক্রিপ্টে
        if (sp(c).getInt("ver", 1) < 8) { out.forEach { o -> legacy[o.id]?.let { (old, nw) -> if (o.script == old) o.script = nw } }; changed = true }
        for (d in defaults) if (d.id !in seen && out.none { it.id == d.id }) { out.add(d.copy()); changed = true }
        if (changed) save(c, out)
        return out
    }

    fun save(c: Context, list: List<Cmd>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("s", it.script).put("b", it.background)) }
        sp(c).edit().putString("list", a.toString()).putInt("ver", 8)
            .putString("seen", defaults.joinToString(",") { it.id }).apply()
    }

    fun prefs(c: Context) = c.getSharedPreferences("browser", Context.MODE_PRIVATE)
}
