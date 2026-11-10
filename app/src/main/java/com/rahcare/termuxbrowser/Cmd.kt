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
    @Volatile var sock: java.net.Socket? = null   // এখন যে সংযোগ থেকে আউটপুট নেওয়া হচ্ছে (একটাই; নতুন এলে পুরনো বন্ধ)
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
    /** নতুন কমান্ড চালানোর আগে আগের ব্যর্থ কাজগুলো নিজে মুছে ফেলি (চলমান ও সফল/থামানোগুলো থাকে) */
    fun clearFailed() { list.removeIf { it.status == "fail" } }

    fun start(title: String, script: String = ""): Job {
        clearFailed()
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

/** প্রতিটি কাজের নিজস্ব আপডেট-ধাপ (আলাদা "আপডেট/আপগ্রেড" বোতাম নেই): ঘুম আটকানো, আধা-থামা dpkg সারানো, আপডেট (ব্যর্থ হলে ডিফল্ট মিররে ফিরে আবার), তারপর আপগ্রেড — রুকইয়াহ অডিও প্যাকের মতো */
fun pre(label: String = "▸ প্যাকেজ আপডেট ও আপগ্রেড"): String = ("termux-wake-lock 2>/dev/null\n" +
    "echo '" + label + "'\n" +
    "dpkg --configure -a --force-confold >/dev/null 2>&1\n" +
    "if ! pkg update -y; then\n" +
    "  echo 'আপডেট ব্যর্থ — ডিফল্ট মিররে ফিরে আবার চেষ্টা হচ্ছে'\n" +
    "  U=https://packages-cf.termux.dev/apt/termux-main; S=SSPREFIX/etc/apt/sources.list\n" +
    "  if curl -fsI --max-time 20 \"SSU/dists/stable/InRelease\" >/dev/null 2>&1; then cp \"SSS\" \"SSS.bak\" 2>/dev/null; echo \"deb SSU stable main\" > \"SSS\"; fi\n" +
    "  pkg update -y || { echo 'আপডেট ব্যর্থ — নেট দেখে আবার চালান'; exit 1; }\n" +
    "fi\n" +
    "pkg upgrade -y || exit 1\n").replace("SS", "\$")

/** উবুন্টু কম্পিউটার: proot-distro দিয়ে আসল উবুন্টু + পূর্ণ XFCE ডেস্কটপ। প্রতি ধাপ আগে দেখে নেয় হয়েছে কি না, তাই থেমে গেলে আবার চাপলে বাকিটা থেকে চলে (অডিও-প্যাকের মতো) */
val UBUNTU: String = """if [ -n "§(find §HOME/.cm_upg -mmin -360 2>/dev/null)" ]; then echo '▸ ধাপ ১/৫: আপডেট ও আপগ্রেড আগেই হয়েছে — বাদ'; termux-wake-lock 2>/dev/null; else
""".replace('§', '$') + pre("▸ ধাপ ১/৫: প্যাকেজ আপডেট ও আপগ্রেড") + """touch §HOME/.cm_upg
fi
ok() { dpkg -s "§1" 2>/dev/null | grep -q '^Status: install ok installed'; }
echo '▸ ধাপ ২/৫: টুল (proot-distro, X11)'
ok x11-repo || { pkg install -y x11-repo || exit 1; pkg update -y || exit 1; }
need=''
for p in proot-distro termux-x11-nightly; do ok §p || need="§need §p"; done
if [ -z "§need" ]; then echo 'সব টুল আগেই আছে — বাদ'; else pkg install -y §need || { echo 'টুল ইনস্টল ব্যর্থ — আবার চাপলে এখান থেকেই শুরু হবে'; exit 1; }; fi
command -v proot-distro >/dev/null 2>&1 && command -v termux-x11 >/dev/null 2>&1 || { echo 'proot-distro/termux-x11 পাওয়া যায়নি'; exit 1; }
echo '▸ ধাপ ৩/৫: উবুন্টু ইনস্টল (বড় ফাইল, ধীর নেটে সময় লাগবে)'
if proot-distro login ubuntu --shared-tmp -- test -x /bin/bash >/dev/null 2>&1; then echo 'উবুন্টু আগেই আছে — বাদ'; else
  echo 'অসম্পূর্ণ/আধা-নামা উবুন্টু থাকলে সরিয়ে আবার বসাচ্ছি (ডাউনলোড করা ফাইল ক্যাশে থাকলে আবার নামবে না)'
  proot-distro remove ubuntu >/dev/null 2>&1
  rm -rf "§PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu" "§PREFIX/var/lib/proot-distro/containers/ubuntu"
  proot-distro install ubuntu || { echo 'উবুন্টু নামেনি — নেট দেখে আবার চাপুন, এখান থেকেই শুরু হবে'; exit 1; }
  proot-distro login ubuntu --shared-tmp -- test -x /bin/bash >/dev/null 2>&1 || { echo 'উবুন্টু চালু হয়নি — আবার চাপুন'; exit 1; }
fi
echo '▸ ধাপ ৪/৫: উবুন্টুর ভেতরে ডেস্কটপ (XFCE) ইনস্টল — সময় লাগবে'
if proot-distro login ubuntu --shared-tmp -- test -f /root/.cm_ok >/dev/null 2>&1; then echo 'ডেস্কটপ আগেই বসানো — বাদ'; else
T=§{TMPDIR:-§PREFIX/tmp}; mkdir -p "§T"
cat > "§T/cm_setup.sh" <<'SETUP'
export DEBIAN_FRONTEND=noninteractive TZ=Asia/Dhaka LANG=C.UTF-8
echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/99cm-sandbox
dpkg --configure -a
apt-get update || exit 1
apt-get install -y xfce4 xfce4-terminal dbus-x11 x11-xserver-utils fonts-noto-core nano curl || exit 1
touch /root/.cm_ok
SETUP
  proot-distro login ubuntu --shared-tmp -- bash /tmp/cm_setup.sh || { echo 'ডেস্কটপ ইনস্টল ব্যর্থ — আবার চাপলে বাকিটা থেকেই শুরু হবে'; exit 1; }
fi
echo '▸ ধাপ ৫/৫: উবুন্টু ডেস্কটপ চালু'
pkill -f "com.termux.x11.CmdEntry""Point" 2>/dev/null; pkill -x proot 2>/dev/null; sleep 1
export XDG_RUNTIME_DIR=§{TMPDIR:-§PREFIX/tmp}
(termux-x11 :0 >/dev/null 2>&1 &)
sleep 3
echo 'ডেস্কটপ সার্ভার চালু — Termux:X11 খুলছে'
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
exec proot-distro login ubuntu --shared-tmp -- env DISPLAY=:0 dbus-launch --exit-with-session startxfce4""".replace('§', '$')

/** ধাপভিত্তিক ডিফল্ট স্ক্রিপ্ট: "▸ " দিয়ে শুরু লাইন = একটি ধাপের শিরোনাম (কাজের ট্যাবে হেডিং হয়ে দেখায়) */
val D3 = pre() + "echo '▸ Python ইনস্টল'\npkg install -y python || exit 1\necho '▸ সংস্করণ যাচাই'\npython --version && pip --version"
val D5 = pre() + "echo '▸ Node.js ইনস্টল'\npkg install -y nodejs || exit 1\necho '▸ সংস্করণ যাচাই'\nnode -v && npm -v"

/** দৈনিক রুকইয়াহ অ্যাপের ৬,২৩৬ আয়াতের অডিও (Alafasy, মনো, ৩২kbps, ২২.০৫kHz, গতি ×১.২০) ফোনেই নামিয়ে সূরাভিত্তিক zip বানায়।
 *  আবার চালালে যা হয়ে গেছে তা বাদ দিয়ে বাকিটা থেকে এগোয়। রিলিজে তোলা আলাদা ধাপ (টোকেন লাগে বলে এই কমান্ডে নেই)। */
val AUDIO_PACK: String = """termux-wake-lock 2>/dev/null
echo '▸ ধাপ ১/৩: টুল ইনস্টল'
echo 'আগে সব প্যাকেজ আপগ্রেড হচ্ছে (Termux-এর লাইব্রেরি মেলাতে — নইলে ffmpeg চলে না)'
pkg upgrade -y || exit 1
pkg install -y ffmpeg zip || exit 1
command -v ffmpeg >/dev/null 2>&1 && command -v zip >/dev/null 2>&1 || { echo 'ffmpeg/zip ইনস্টল ব্যর্থ'; exit 1; }
echo 'প্রস্তুত'

echo '▸ ধাপ ২/৩: ডাউনলোড ও এনকোড (৩২kbps, ২২.০৫kHz, ×১.২০) — ৬,২৩৬ আয়াত, সময় লাগবে'
mkdir -p §HOME/audio/src §HOME/audio/out §HOME/audio/zips && cd §HOME/audio || exit 1
echo "কাজের ফোল্ডার: §HOME/audio"
AYN="7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,11,8,3,9,5,4,7,3,6,3,5,4,5,6"
echo 'আয়াতের তালিকা বানানো হচ্ছে…'
echo "§AYN" | awk -F, '{n=0; for(s=1;s<=NF;s++) for(v=1;v<=§s;v++){n++; printf "%03d%03d %d\n", s, v, n}}' > list.txt
echo "তালিকায় আয়াত: §(wc -l < list.txt)"
[ "§(wc -l < list.txt)" -eq 6236 ] || { echo 'তালিকা তৈরি ব্যর্থ'; exit 1; }
code=§(curl -s -o /dev/null -r 0-0 -w '%{http_code}' --connect-timeout 15 --max-time 30 https://everyayah.com/data/Alafasy_128kbps/001001.mp3 2>/dev/null)
case "§code" in 200|206) echo 'সার্ভার ঠিক আছে — ডাউনলোড শুরু হচ্ছে';; *) echo "সার্ভারের সাড়া কোড: §code (নেট খুব ধীর বা সাইট বন্ধ হতে পারে) — তবু চেষ্টা চলছে";; esac
enc() { p=§1; n=§2
  [ -s out/§n.mp3 ] && return 0
  for i in 1 2 3 4 5; do
    # ধীর নেটে বড় ফাইল ৯০ সেকেন্ডে আসে না; তাই সময়সীমা নয়, আটকে গেলেই (৬০ সেকেন্ড প্রায় কিছু আসেনি) থামে, আর অর্ধেক নামা ফাইল থেকে আবার শুরু করে
    if curl -sf -C - --connect-timeout 20 --speed-limit 100 --speed-time 60 -o src/§p.mp3 https://everyayah.com/data/Alafasy_128kbps/§p.mp3; then
      if nice -n 10 ffmpeg -nostdin -threads 1 -v error -y -i src/§p.mp3 -ac 1 -af atempo=1.2 -ar 22050 -c:a libmp3lame -b:a 32k -f mp3 out/§n.mp3.tmp && mv out/§n.mp3.tmp out/§n.mp3; then rm -f src/§p.mp3; return 0; fi
      rm -f src/§p.mp3 out/§n.mp3.tmp
    fi
    sleep §((i*3))
  done; return 1; }
export -f enc
( while true; do h=§(ls out/*.mp3 2>/dev/null | wc -l); echo "ডাউনলোড হয়েছে: §h/6236 (§((h*100/6236))%)"; echo "§(date +%T) §h §(grep MemAvailable /proc/meminfo 2>/dev/null)" >> §HOME/audio/progress.log; [ "§h" -ge 6236 ] && break; sleep 15; done ) &
MON=§!
for pass in 1 2 3; do
  xargs -n2 -P2 bash -c 'enc "§0" "§1"' < list.txt || true
  have=§(ls out/*.mp3 2>/dev/null | wc -l); echo "পাস §pass শেষ: §have/6236"
  [ "§have" -ge 6236 ] && break
done
kill §MON 2>/dev/null
have=§(ls out/*.mp3 2>/dev/null | wc -l)
[ "§have" -eq 6236 ] || { echo "সব ফাইল আসেনি (§have/6236) — এই কমান্ডটা আবার চালান, যা হয়ে গেছে তা বাদ দিয়ে বাকিটা করবে"; exit 1; }
echo 'সব ৬,২৩৬টা আয়াত নামানো হয়েছে'

echo '▸ ধাপ ৩/৩: সূরাভিত্তিক zip তৈরি'
cd §HOME/audio/out || exit 1
n=0; s=0
for c in §(echo "§AYN" | tr ',' ' '); do
  s=§((s+1)); z=../zips/§(printf 's%03d' §s).zip
  : > ../zips/list.tmp
  for v in §(seq 1 §c); do n=§((n+1)); echo "§n.mp3" >> ../zips/list.tmp; done
  rm -f "§z"
  zip -0 -q "§z" -@ < ../zips/list.tmp || { echo "zip ব্যর্থ: সূরা §s"; exit 1; }
done
rm -f ../zips/list.tmp
echo "zip তৈরি, মোট আয়াত §n"
du -sh ../zips
echo "সম্পন্ন: §HOME/audio/zips -এ s001.zip … s114.zip। GitHub রিলিজে তুলতে Termux-এ gh auth login করে gh release create চালান।"""".replace('§', '$')

object Store {
    /** আগের সংস্করণের ডিফল্ট স্ক্রিপ্ট → নতুন ধাপভিত্তিক স্ক্রিপ্ট (ব্যবহারকারী বদলে থাকলে ছোঁয়া হয় না) */
    private val legacy = mapOf(
        "d3" to Pair("pkg install -y python && python --version && pip --version", D3),
        "d5" to Pair("pkg install -y nodejs && node -v && npm -v", D5)
    )
    private fun sp(c: Context) = c.getSharedPreferences("cmds", Context.MODE_PRIVATE)

    private val defaults = listOf(
        Cmd("d2", "স্টোরেজ অনুমতি", "termux-setup-storage", false),
        Cmd("d3", "Python ইনস্টল", D3),
        Cmd("d4", "Git ইনস্টল", pre() + "pkg install -y git && git --version"),
        Cmd("d5", "Node.js ইনস্টল", D5),
        Cmd("d6", "SSH সার্ভার চালু", pre() + "pkg install -y openssh || exit 1\npkill -x sshd 2>/dev/null; sshd && echo 'sshd চালু, পোর্ট 8022'\necho \"ব্যবহারকারী: \$(whoami)\"\necho 'পাসওয়ার্ড সেট করতে Termux স্ক্রিনে passwd চালান'"),
        Cmd("d7", "ডিস্ক ব্যবহার", "df -h \$HOME"),
        Cmd("d8", "আইপি দেখুন", "(command -v ifconfig >/dev/null || pkg install -y net-tools) && ifconfig 2>/dev/null | grep -E \"inet |wlan|rmnet\" || /system/bin/ip -4 addr 2>/dev/null | grep inet"),
        Cmd("d9", "সিস্টেম তথ্য", "uname -a && echo && free -h 2>/dev/null | head -3"),
        Cmd("d17", "🖥 কম্পিউটার মোড (উবুন্টু, পূর্ণ ডেস্কটপ)", UBUNTU, false),
        // ওয়েব-টার্মিনাল: Termux-এ ttyd চালু হয়, তারপর অ্যাপ নতুন ট্যাবে খোলে। শুধু 127.0.0.1-এ, পাসওয়ার্ডসহ (@@WEBPW@@ অ্যাপ বসায়)
        Cmd("d12", "🌐 ওয়েব-টার্মিনাল (ট্যাবে পূর্ণ টার্মিনাল)",
            pre() + "pkg install -y ttyd || exit 1\n" +
            "pkill -x ttyd 2>/dev/null; sleep 1\n" +
            "nohup ttyd -W -i 127.0.0.1 -p 7681 -c tb:@@WEBPW@@ bash -l >/dev/null 2>&1 &\n" +
            "sleep 3\n" +
            "if curl -s -o /dev/null --max-time 5 -u tb:@@WEBPW@@ http://127.0.0.1:7681/; then echo 'ওয়েব-টার্মিনাল চালু — নতুন ট্যাব খুলছে'; else echo 'ttyd চালু হয়নি — উপরের ত্রুটি দেখুন'; exit 1; fi"),
        Cmd("d13", "⏹ আটকে থাকা প্যাকেজ কাজ থামান",
            "pkill -x apt 2>/dev/null; pkill -x apt-get 2>/dev/null; pkill -x dpkg 2>/dev/null; sleep 1; dpkg --configure -a --force-confold 2>&1 | tail -5; echo 'থামানো হয়েছে — এখন নতুন কমান্ড চালাতে পারেন'"),
        Cmd("d11", "কম্পিউটার মোড বন্ধ", "pkill -x openbox; pkill -x tint2; pkill -x proot; pkill -f \"com.termux.x11.CmdEntry\"\"Point\"; echo বন্ধ হয়েছে"),
        Cmd("d15", "🎧 রুকইয়াহ অডিও প্যাক ডাউনলোড (৩২kbps×১.২০)", AUDIO_PACK, false),
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
        // v7: কমান্ডগুলো নিজে-সারানো প্রিলুড ও পূর্ণ কম্পিউটার-মোড স্ক্রিপ্টে নতুন করে বসানো
        if (sp(c).getInt("ver", 1) < 7) { out.forEach { o -> defaults.firstOrNull { it.id == o.id }?.let { n -> o.script = n.script; o.title = n.title } }; changed = true }
        // v8: অপরিবর্তিত ডিফল্ট কমান্ডগুলো ধাপভিত্তিক স্ক্রিপ্টে
        if (sp(c).getInt("ver", 1) < 8) { out.forEach { o -> legacy[o.id]?.let { (old, nw) -> if (o.script == old) o.script = nw } }; changed = true }
        // v9: অডিও-প্যাক কমান্ডে ইনস্টলের আগে আপগ্রেড যোগ (অপরিবর্তিত ডিফল্ট হলে)
        if (sp(c).getInt("ver", 1) < 9) { out.filter { it.id == "d15" && it.script.contains("pkg install -y ffmpeg parallel") && !it.script.contains("pkg upgrade") }.forEach { o -> defaults.firstOrNull { it.id == "d15" }?.let { n -> o.script = n.script } }; changed = true }
        // v10: অডিও-প্যাক কমান্ড python/parallel ছাড়া (awk/xargs/zip), ধীর নেটে resume ও লাইভ বার্তাসহ নতুন স্ক্রিপ্টে
        if (sp(c).getInt("ver", 1) < 10) { out.filter { it.id == "d15" && (it.script.contains("python3") || it.script.contains("parallel")) }.forEach { o -> defaults.firstOrNull { it.id == "d15" }?.let { n -> o.script = n.script } }; changed = true }
        // v11: অডিও-প্যাক ফোনের চাপ কমিয়ে (২ সমান্তরাল, nice, ffmpeg ১ থ্রেড), সার্ভার-যাচাইয়ের ভুল সতর্কবার্তা ঠিক ও progress.log সহ
        if (sp(c).getInt("ver", 1) < 11) { out.filter { it.id == "d15" && it.script.contains("-P3") }.forEach { o -> defaults.firstOrNull { it.id == "d15" }?.let { n -> o.script = n.script } }; changed = true }
        // v12: অডিও-প্যাক Termux-এর খোলা পর্দায় চলবে (ব্যাকগ্রাউন্ড প্রক্রিয়া সিস্টেম মেরে ফেলছিল)
        if (sp(c).getInt("ver", 1) < 12) { out.filter { it.id == "d15" }.forEach { o -> o.background = false; defaults.firstOrNull { it.id == "d15" }?.let { n -> if (!o.script.contains("termux-wake-lock")) o.script = n.script } }; changed = true }
        // v13: আলাদা আপডেট/আপগ্রেড ও মিরর বোতাম বাদ (প্রতিটি কাজে নিজস্ব আপডেট-ধাপ); কম্পিউটার মোড অডিও-প্যাকের মতো Termux-এর খোলা পর্দায় ও নতুন স্ক্রিপ্টে
        if (sp(c).getInt("ver", 1) < 13) {
            out.removeAll { it.id == "d1" || it.id == "d14" }
            out.filter { it.id == "d10" }.forEach { o -> o.background = false; defaults.firstOrNull { it.id == "d10" }?.let { n -> o.script = n.script } }
            out.filter { it.id in setOf("d3", "d4", "d5", "d6", "d12") && !it.script.contains("pkg upgrade") }.forEach { o -> defaults.firstOrNull { it.id == o.id }?.let { n -> o.script = n.script } }
            changed = true
        }
        // v14: কম্পিউটার মোড থেমে গেলে আবার চাপলে যেখান থেকে থেমেছিল সেখান থেকে এগোয় (অডিও-প্যাকের মতো); নিজের প্রক্রিয়া মারার ভুল (signal 15) ঠিক
        if (sp(c).getInt("ver", 1) < 14) {
            out.filter { it.id == "d10" || it.id == "d11" }.forEach { o -> defaults.firstOrNull { it.id == o.id }?.let { n -> o.script = n.script } }
            changed = true
        }
        // v15: কম্পিউটার মোডে ডেস্কটপের রঙ, বার, আইকন ও টার্মিনাল নিজে খোলা (আগে শুধু কালো পর্দা)
        if (sp(c).getInt("ver", 1) < 15) {
            out.filter { it.id == "d10" }.forEach { o -> defaults.firstOrNull { it.id == "d10" }?.let { n -> o.script = n.script } }
            changed = true
        }
        // v16: বন্ধ-কমান্ড উবুন্টুও থামায়
        if (sp(c).getInt("ver", 1) < 16) {
            out.filter { it.id == "d11" }.forEach { o -> defaults.firstOrNull { it.id == "d11" }?.let { n -> o.script = n.script } }
            changed = true
        }
        // v17: xterm-এর বদলে aterm বসলে ওটাকেও "আছে" ধরা (নইলে প্রতিবার xterm আবার ইনস্টলের চেষ্টা হতো)
        if (sp(c).getInt("ver", 1) < 17) {
            out.filter { it.id == "d10" }.forEach { o -> defaults.firstOrNull { it.id == "d10" }?.let { n -> o.script = n.script } }
            changed = true
        }
        // v18: পুরনো হালকা কম্পিউটার-মোড (d10) বাদ; তার জায়গায় শুধু উবুন্টু কম্পিউটার-মোড
        if (sp(c).getInt("ver", 1) < 18) {
            out.removeAll { it.id == "d10" }
            out.filter { it.id == "d16" }.forEach { o -> defaults.firstOrNull { it.id == "d16" }?.let { n -> o.title = n.title } }
            changed = true
        }
        // v20: "Daily Agent ডাউনলোড ও আপডেট" কমান্ড চিরতরে বাদ (d16)। উবুন্টু এখন d17; আগে দুটোর আইডি এক (d16) ছিল বলে একটা অন্যটাকে ঢেকে দিত
        if (sp(c).getInt("ver", 1) < 20) {
            out.removeAll { it.id == "d16" || it.id == "d17" }
            out.add(defaults.first { it.id == "d17" }.copy())
            changed = true
        }
        for (d in defaults) if (d.id !in seen && out.none { it.id == d.id }) { out.add(d.copy()); changed = true }
        if (changed) save(c, out)
        return out
    }

    fun save(c: Context, list: List<Cmd>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("s", it.script).put("b", it.background)) }
        sp(c).edit().putString("list", a.toString()).putInt("ver", 20)
            .putString("seen", defaults.joinToString(",") { it.id }).apply()
    }

    fun prefs(c: Context) = c.getSharedPreferences("browser", Context.MODE_PRIVATE)
}
