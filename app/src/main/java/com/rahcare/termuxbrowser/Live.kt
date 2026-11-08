package com.rahcare.termuxbrowser

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Termux-এর কমান্ডের আউটপুট লাইভ পেতে: অ্যাপ শুধু 127.0.0.1-এ একটা পোর্ট খোলে,
 * কমান্ড নিজের আউটপুট bash-এর /dev/tcp দিয়ে সেখানে পাঠায় (বাইরের কোনো প্যাকেজ লাগে না)।
 */
object Live {
    @Volatile private var port = 0
    @Volatile var onUpdate: ((Job) -> Unit)? = null
    private val exit = Regex("@@EXIT:(-?\\d+)(?::(\\d+))?")
    private val gone = Regex("@@GONE:(\\d+)")

    @Synchronized private fun ensure(): Int {
        if (port != 0) return port
        val ss = ServerSocket(0, 30, InetAddress.getByName("127.0.0.1"))
        port = ss.localPort
        Thread {
            while (true) {
                try { val s = ss.accept(); Thread { serve(s) }.apply { isDaemon = true }.start() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
        return port
    }

    /** ব্যবহারকারীর স্ক্রিপ্টকে এমনভাবে মুড়ে দেয় যে আউটপুট লাইভ অ্যাপে আসে; সংযোগ না হলে স্ক্রিপ্ট সাধারণভাবেই চলে */
    /** pkg-র ধীর মিরর-পরীক্ষা এড়াতে pkg-কে apt-এ বদলায় (ব্যাকগ্রাউন্ড ও Termux-পর্দা দুই ক্ষেত্রেই) */
    /** প্রতিটি কমান্ডের আগে বসে: pkg-কে নিজে-সারানো আপডেট/ইনস্টলে বদলায় (তালা মোছা, dpkg মেরামত, পুনরায় চেষ্টা, মিরর বদল) */
    val PKG_SHIM: String = """export DEBIAN_FRONTEND=noninteractive
PREFIX=§{PREFIX:-/data/data/com.termux/files/usr}
tb_say() { echo "↻ §*"; }
tb_run() { "§@" & p=§!; s=§(date +%s); ( while kill -0 §p 2>/dev/null; do sleep 8; kill -0 §p 2>/dev/null || break; d=§(du -sk "§PREFIX/var/cache/apt/archives/partial" "§PREFIX/var/lib/apt/lists/partial" 2>/dev/null | awk '{t+=§1} END{print t+0}'); echo "… এখনও চলছে (§((§(date +%s)-s)) সেকেন্ড) — নামানো §{d} KB · ধীর নেটে সময় লাগে, অপেক্ষা করুন"; done ) & h=§!; wait §p; r=§?; kill §h 2>/dev/null; wait §h 2>/dev/null; return §r; }
tb_aptwait() { command -v pgrep >/dev/null 2>&1 || return 0; w=0; while pgrep -x apt >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x dpkg >/dev/null; do [ §w = 0 ] && { echo 'অন্য একটি প্যাকেজ কাজ চলছে — শেষ হওয়ার অপেক্ষা… (আটকে গেলে হোমের ⏹ আটকে থাকা প্যাকেজ কাজ থামান চাপুন)'; }; w=1; sleep 3; done; }
tb_net() { curl -sI -o /dev/null --connect-timeout 10 --max-time 25 https://packages-cf.termux.dev/apt/termux-main/dists/stable/InRelease || curl -sI -o /dev/null --connect-timeout 10 --max-time 25 https://github.com; }
tb_chk() { [ -n "§(timeout 20 dpkg --audit 2>/dev/null)" ] || return 0; echo 'আগের অসম্পূর্ণ ইনস্টল মেরামত হচ্ছে (সর্বোচ্চ ৩ মিনিট)…'; tb_run timeout 180 dpkg --configure -a --force-confold || echo 'মেরামত শেষ হয়নি — এগিয়ে যাচ্ছি'; return 0; }
tb_fix() { rm -f "§PREFIX/var/lib/dpkg/lock" "§PREFIX/var/lib/dpkg/lock-frontend" "§PREFIX/var/lib/apt/lists/lock" "§PREFIX/var/cache/apt/archives/lock" 2>/dev/null; timeout 180 dpkg --configure -a --force-confold >/dev/null 2>&1; timeout 300 apt-get -f install -y -o Dpkg::Options::=--force-confold >/dev/null 2>&1; return 0; }
tb_mirror() { U=https://packages-cf.termux.dev/apt/termux-main; S="§PREFIX/etc/apt/sources.list"; if curl -fsI --max-time 20 "§U/dists/stable/InRelease" >/dev/null 2>&1; then [ -f "§S.bak" ] || cp "§S" "§S.bak" 2>/dev/null; echo "deb §U stable main" > "§S"; tb_say 'মিরর ডিফল্টে ফেরানো হলো'; return 0; fi; return 1; }
tb_update() { n=0; while :; do tb_run apt-get update -o Acquire::Retries=3 && { touch "§PREFIX/var/lib/apt/lists/.tb_ok" 2>/dev/null; return 0; }; n=§((n+1)); [ §n -ge 3 ] && return 1; tb_say "তালিকা আপডেট ব্যর্থ — নিজে ঠিক করে আবার চেষ্টা (§n/2)"; tb_fix; [ §n = 2 ] && tb_mirror; sleep 2; done; }
tb_apt() { tb_run apt-get -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef -o Acquire::Retries=3 "§@"; }
tb_install() {
  [ "§(ls "§PREFIX/var/lib/apt/lists" 2>/dev/null | wc -l)" -gt 3 ] || { tb_say 'প্যাকেজ তালিকা আপডেট হচ্ছে'; tb_update; }
  tb_apt install -y "§@" && return 0
  tb_say 'ইনস্টল ব্যর্থ — Termux-এর লাইব্রেরি মেলাতে সব প্যাকেজ আপগ্রেড করে আবার চেষ্টা (ধীর নেটে সময় লাগবে)'; tb_fix; tb_upgrade; tb_fix; tb_apt install -y "§@" && return 0
  tb_mirror && tb_update && tb_apt install -y "§@" && return 0
  echo "ইনস্টল ব্যর্থ: §*"; return 1
}
tb_upgrade() { [ -n "§(find "§PREFIX/var/lib/apt/lists/.tb_ok" -mmin -10 2>/dev/null)" ] || tb_update || return 1; tb_apt upgrade -y && return 0; tb_say 'আপগ্রেড ব্যর্থ — নিজে ঠিক করে আবার চেষ্টা'; tb_fix; tb_apt upgrade -y; }
pkg() { echo 'শুরু হচ্ছে…'; tb_aptwait; c="§1"; shift
  case "§c" in
    update|up|upgrade|full-upgrade|install|i|add) echo 'ইন্টারনেট যাচাই হচ্ছে…'; tb_net || { echo 'ইন্টারনেট সংযোগ নেই (বা খুবই ধীর) — নেট ঠিক করে আবার চেষ্টা করুন'; return 1; }; echo 'নেট ঠিক আছে'; tb_chk ;;
  esac
  case "§c" in
    update|up) tb_update ;;
    upgrade|full-upgrade) tb_upgrade ;;
    install|i|add) tb_install "§@" ;;
    *) command pkg "§c" "§@" ;;
  esac; }
""".replace('§', '$')

    fun wrap(job: Job, script: String): String {
        val port = ensure()
        val k = job.key
        // আউটপুট আগে ফাইলে লেখা হয়, তাই অ্যাপ মিনিমাইজ/ফ্রিজ হলেও কমান্ড আটকায় না;
        // tail আলাদা করে সেটা অ্যাপে পাঠায় (অ্যাপ ফ্রিজ থাকলে শুধু tail থামে, কমান্ড নয়)
        return "mkdir -p \"\$HOME/.tb\" 2>/dev/null\n" +
            "L=\"\$HOME/.tb/$k.log\"; P=\"\$HOME/.tb/$k.pid\"; : > \"\$L\"; echo \$\$ > \"\$P\"\n" +
            "echo '▸ Termux-এর সাথে সংযোগ ঠিক আছে' >> \"\$L\"\n" +
            "termux-wake-lock 2>/dev/null\n" +
            // pkg প্রতিবার সব মিরর পরীক্ষা করে (ধীর নেটে বহু মিনিট); তাই pkg-কে সরাসরি apt দিয়ে বদলাই
            PKG_SHIM +
            "FW=\n" +
            "if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then\n" +
            "exec 3<>/dev/tcp/127.0.0.1/$port\n" +
            "echo \"@@KEY:$k\" >&3\n" +
            "tail -n +1 -f \"\$L\" >&3 2>/dev/null &\n" +
            "FW=\$!\n" +
            "fi\n" +
            "(\n$script\n) >> \"\$L\" 2>&1\n" +
            "c=\$?\n" +
            "echo \"@@EXIT:\$c:\$(date +%s)\" >> \"\$L\"\n" +
            "if [ -n \"\$FW\" ]; then sleep 2; kill \$FW 2>/dev/null; fi\n" +
            "termux-wake-unlock 2>/dev/null\n" +
            "tail -c 8000 \"\$L\" | sed '/^@@EXIT/d'\n" +
            "rm -f \"\$P\"\n" +
            // লগ রেখে দিই (অ্যাপ আপডেটের পর আবার দেখানোর জন্য), শুধু সবচেয়ে নতুন ১৫টা রাখি
            "n=0; for f in \$(ls -t \"\$HOME\"/.tb/*.log 2>/dev/null); do n=\$((n+1)); [ \$n -gt 15 ] && rm -f \"\$f\"; done\n" +
            "exit \$c\n"
    }

    /** অ্যাপ নতুন করে চালু হওয়ার পর চলমান/শেষ-হওয়া কাজের লগ আবার অ্যাপে পাঠায় (লগ ফাইল থেকে, শুরু থেকে) */
    fun attach(job: Job): String {
        val port = ensure()
        val k = job.key
        return "L=\"\$HOME/.tb/$k.log\"; P=\"\$HOME/.tb/$k.pid\"\n" +
            "exec 3<>/dev/tcp/127.0.0.1/$port || exit 1\n" +
            "echo \"@@KEY:$k\" >&3\n" +
            "if [ ! -f \"\$L\" ]; then echo '@@LOST' >&3; exit 0; fi\n" +
            "if [ -f \"\$P\" ] && kill -0 \"\$(cat \"\$P\")\" 2>/dev/null; then\n" +
            "tail -n +1 -f --pid=\"\$(cat \"\$P\")\" \"\$L\" >&3\n" +
            "sleep 1; if grep -q '^@@EXIT' \"\$L\"; then grep '^@@EXIT' \"\$L\" >&3; else echo \"@@GONE:\$(date +%s)\" >&3; fi\n" +
            "else cat \"\$L\" >&3; grep -q '^@@EXIT' \"\$L\" || echo \"@@GONE:\$(stat -c %Y \"\$L\" 2>/dev/null || date +%s)\" >&3; fi\n" +
            "exec 3>&-\n"
    }

    /** ts = কমান্ড আসলে যখন শেষ হয়েছিল (সেকেন্ড); না থাকলে এখনকার সময়। আবার সংযোগের সময় "এখন" ধরলে সময় ভুল দেখাত */
    private fun endTime(job: Job, ts: Long?) = if (ts != null && ts > 0) maxOf(ts * 1000, job.time) else System.currentTimeMillis()

    private fun finish(job: Job, code: Int, ts: Long?) {
        job.status = Jobs.statusFor(code)
        job.end = endTime(job, ts)
        job.out = job.out.trimEnd() + "\n(exit $code" + (if (job.status == "stopped") " — থামানো/বন্ধ করা হয়েছে" else "") + ")"
    }

    private fun serve(s: Socket) {
        var job: Job? = null
        var linked = false
        try {
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val first = r.readLine() ?: return
            if (!first.startsWith("@@KEY:")) return
            job = Jobs.find(first.removePrefix("@@KEY:")) ?: return
            job.links.incrementAndGet(); linked = true; job.attaches = 0
            // প্রতিটি সংযোগই লগ শুরু থেকে পাঠায়; তাই একসাথে দুটো চললে প্রতিটি লাইন দুইবার আসত।
            // নতুন সংযোগ এলে পুরনোটা বন্ধ করে আউটপুট খালি করি — সবসময় একটাই স্ট্রিম
            val old = job.sock; job.sock = s
            try { if (old != null && old !== s) old.close() } catch (_: Exception) {}
            job.out = ""; job.reattach = false
            val buf = CharArray(4096)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                if (job.sock !== s) break   // নতুন সংযোগ এসে গেছে — এটার বাকি ডেটা ফেলে দিই
                job.out = (job.out + String(buf, 0, n)).takeLast(60000)
                var done = false
                if (job.out.contains("@@LOST")) {
                    job.out = "(এই কাজের লগ পাওয়া যায়নি — সম্ভবত অনেক আগের বা আপডেটের আগের সংস্করণে চালু হয়েছিল)"
                    job.status = "fail"; job.end = System.currentTimeMillis(); done = true
                }
                exit.find(job.out)?.let { m ->
                    job.out = job.out.replace(m.value, "")
                    if (job.status == "running") { finish(job, m.groupValues[1].toInt(), m.groupValues[2].toLongOrNull()); done = true }
                }
                gone.find(job.out)?.let { m ->
                    job.out = job.out.replace(m.value, "").trimEnd() + "\n(প্রক্রিয়া মাঝপথে বন্ধ হয়ে গেছে — Android বা Termux বন্ধ করে দিয়েছে)"
                    if (job.status == "running") { job.status = "fail"; job.end = endTime(job, m.groupValues[1].toLongOrNull()); done = true }
                }
                val now = System.currentTimeMillis()
                if (done || now - job.lastPush > 150) {
                    job.lastPush = now; job.refreshPct(); onUpdate?.invoke(job)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            if (linked) job?.links?.decrementAndGet()
            job?.let { it.refreshPct(); onUpdate?.invoke(it) }
        }
    }
}
