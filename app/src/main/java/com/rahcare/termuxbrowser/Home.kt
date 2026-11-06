package com.rahcare.termuxbrowser

import android.content.Context

/** ব্রাউজারের হোম পেজ: Termux কমান্ড বাটন + চলমান কাজ */
object Home {
    const val BASE = "https://termux.home/"
    const val JOB = BASE + "job/"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun mark(j: Job) = when (j.status) { "running" -> "চলছে…"; "ok" -> "সম্পন্ন"; "stopped" -> "বন্ধ করা হয়েছে"; else -> "ব্যর্থ" }

    /** একটি কাজের বর্তমান অবস্থা (JSON) — কাজের ট্যাবের পাতা এটা দিয়ে নিজেকে আঁকে */
    fun state(j: Job): String = org.json.JSONObject().put("s", j.status).put("p", j.pct).put("o", j.text())
        .put("t0", j.time).put("t1", j.end).toString()

    /** হোম পেজের কাজের তালিকা: প্রতিটিতে চাপলে সেই কাজের ট্যাব খোলে */
    fun jobsHtml(): String {
        if (Jobs.list.isEmpty()) return "<p class=m>এখনো কিছু চালানো হয়নি।</p>"
        val more = if (Jobs.list.size > 10) "<p class=m>…আরও ${Jobs.list.size - 10}টি পুরনো কাজ আছে (ওপরের 🗑 দিয়ে শেষ হওয়াগুলো মুছুন)</p>" else ""
        return Jobs.list.asReversed().take(10).joinToString("") { j ->
            val dot = when (j.status) { "running" -> "#f59e0b"; "ok" -> "#16a34a"; "stopped" -> "#6b7280"; else -> "#dc2626" }
            val bar = when {
                j.status == "ok" -> "<div class=mb><i class=okb></i></div>"
                j.status == "stopped" -> "<div class=mb><i class=sb></i></div>"
                j.status != "running" -> "<div class=mb><i class=fb></i></div>"
                j.pct >= 0 -> "<div class=mb><i style='width:${j.pct}%'></i></div>"
                else -> "<div class=mb><i class=ind></i></div>"
            }
            val last = j.lastLine()
            "<div class=jw><a class='job${if (j.status == "running") " r" else ""}' href='termux://job/${j.key}'><b><span class=dot style='background:$dot'></span>${esc(j.title)}</b> " +
                "<span class=m>${mark(j)} · ${j.elapsed()} সেকেন্ড</span>$bar" +
                (if (last.isNotBlank()) "<div class=ll>${esc(last)}</div>" else "") + "</a>" +
                (if (j.status == "running") "<a class=kl href='termux://kill/${j.key}'>⏹ থামান</a>" else "") + "</div>"
        } + more
    }

    /** একটি কাজের নিজস্ব ট্যাব: শিরোনাম, অবস্থা, প্রগ্রেস বার, সময় ও লাইভ আউটপুট */
    fun jobHtml(j: Job): String = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:14px;background:#f3f4f6;color:#111827}
.top{display:flex;align-items:center;gap:10px}h1{font-size:18px;margin:0;flex:1;word-break:break-word}
.chip{font-size:12px;font-weight:700;padding:4px 10px;border-radius:99px;background:#fef3c7;color:#92400e;white-space:nowrap}
.chip.ok{background:#dcfce7;color:#166534}.chip.fail{background:#fee2e2;color:#991b1b}.chip.stopped{background:#e5e7eb;color:#374151}
.m{color:#6b7280;font-size:13px;margin:6px 0}
.bar{height:8px;background:#e5e7eb;border-radius:4px;overflow:hidden;margin:10px 0}
.bar i{display:block;height:100%;background:#2563eb;transition:width .3s}
.bar i.ind{width:30%;animation:mv 1.1s ease-in-out infinite}
@keyframes mv{0%{margin-left:-30%}100%{margin-left:100%}}
.bar.ok i{background:#16a34a}.bar.fail i{background:#dc2626}.bar.stopped i{background:#9ca3af}
code.c{display:block;background:#e5e7eb;border-radius:8px;padding:8px;font-size:11.5px;word-break:break-all;margin-bottom:10px;max-height:90px;overflow:auto}
pre{background:#0b1220;color:#d1fae5;border-radius:10px;padding:10px;font-size:12px;line-height:1.45;white-space:pre-wrap;word-break:break-all;margin:0;min-height:120px}
</style></head><body>
<div class=top><h1>${esc(j.title)}</h1><span id=st class=chip>চলছে…</span></div>
<div class=m><span id=pc></span><span id=el></span></div>
<div class=bar id=bar><i id=pb class=ind></i></div>
<code class=c>${esc(j.script)}</code>
<pre id=o></pre>
<script>
var S=${state(j)};
function two(n){return n<10?'0'+n:n}
function fmt(s){s=Math.floor(s);return Math.floor(s/60)+':'+two(s%60)}
function tick(){document.getElementById('el').textContent='সময়: '+fmt(((S.t1||Date.now())-S.t0)/1000)}
function upd(x){S=x;draw()}
function draw(){
 var st=document.getElementById('st'),bar=document.getElementById('bar'),pb=document.getElementById('pb');
 var run=S.s==='running';
 st.className='chip'+(S.s==='ok'?' ok':S.s==='fail'?' fail':S.s==='stopped'?' stopped':'');
 st.textContent=run?'চলছে…':S.s==='ok'?'সম্পন্ন ✓':S.s==='stopped'?'বন্ধ করা হয়েছে ■':'ব্যর্থ ✗';
 bar.className='bar'+(S.s==='ok'?' ok':S.s==='fail'?' fail':S.s==='stopped'?' stopped':'');
 if(run&&S.p<0){pb.className='ind';pb.style.width=''}else{pb.className='';pb.style.width=(run?S.p:100)+'%'}
 document.getElementById('pc').textContent=(run&&S.p>=0)?S.p+'% · ':'';
 var o=document.getElementById('o'),near=(innerHeight+scrollY)>=document.body.scrollHeight-90;
 o.textContent=S.o||(run?'আউটপুটের অপেক্ষা…':'(কোনো আউটপুট নেই)');
 if(near)scrollTo(0,document.body.scrollHeight);
 tick()
}
setInterval(function(){if(S.s==='running')tick()},1000);
draw();
</script></body></html>"""

    fun html(c: Context): String {
        val ready = Runner.installed(c) && Runner.hasPermission(c)
        val cards = Store.all(c).joinToString("") { d ->
            "<div class=card><a class=run href='termux://run/${d.id}'>${esc(d.title)}</a>" +
                "<a class=ed href='termux://edit/${d.id}'>✎</a></div>"
        }
        val siteCards = Sites.all(c).joinToString("") { s ->
            "<div class=card><a class=run href='termux://site/${s.id}'>${esc(s.name)}</a>" +
                "<a class=ed href='termux://siteedit/${s.id}'>✎</a></div>"
        }
        val setupBtn = "<a class=add href='termux://setup'>⚡ এক চাপে সেটআপ (কমান্ড কপি + Termux খোলা)</a>"
        val bm = Bookmarks.all(c).take(12)
        val bmSection = if (bm.isEmpty()) "" else "<h2>⭐ বুকমার্ক</h2><div class=g>" + bm.joinToString("") { b ->
            "<div class=card><a class=run href=\"${esc(b.second)}\">${esc(b.first.take(30))}</a></div>" } + "</div>"
        val warn = if (ready && Runner.verified(c)) "" else if (ready)
            "<div class=warn><b>Termux সংযোগ বাকি</b><br>নিচের বোতামে চাপলে কমান্ড কপি হয়ে Termux খুলবে — সেখানে স্ক্রিন লম্বা চেপে Paste করে Enter চাপুন। এরপর যেকোনো কমান্ড চালালেই সংযোগ নিশ্চিত হবে।$setupBtn</div>"
        else
            "<div class=warn><b>সেটআপ দরকার</b><br>Termux (F-Droid) ইনস্টল করুন, তারপর ⋮ মেনু › অ্যাপ অনুমতি থেকে \"Run commands in Termux environment\" দিন, এরপর নিচের বোতামে চাপুন।$setupBtn</div>"
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:14px;background:#f3f4f6;color:#111827}
h1{font-size:20px;margin:6px 2px 2px}h2{font-size:15px;margin:18px 2px 8px;color:#374151}
.m{color:#6b7280;font-size:13px}.warn{background:#fef3c7;color:#78350f;border-radius:12px;padding:12px;margin:10px 0;font-size:13.5px}
code{background:#fde68a;padding:2px 4px;border-radius:4px;word-break:break-all;font-size:12px}
.g{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:8px}@media(min-width:520px){.g{grid-template-columns:repeat(4,1fr)}}
.card{position:relative;background:#fff;border-radius:14px;overflow:hidden}
.run{display:flex;align-items:center;justify-content:center;text-align:center;min-height:68px;padding:10px 8px;color:#111827;text-decoration:none;font-size:13px;font-weight:600;line-height:1.35;overflow-wrap:anywhere}
.ed{position:absolute;top:0;right:0;padding:5px 8px;color:#9ca3af;text-decoration:none;font-size:12px}
.add{display:block;text-align:center;padding:12px;background:#111827;color:#fff;border-radius:14px;text-decoration:none;margin-top:4px}
.job{display:block;text-decoration:none;color:inherit;background:#fff;border-radius:12px;padding:10px 12px;margin-bottom:8px}
.mb{height:5px;background:#e5e7eb;border-radius:3px;overflow:hidden;margin:7px 0 2px}.mb i{display:block;height:100%;background:#2563eb}.mb i.ind{width:30%;animation:mv 1.1s ease-in-out infinite}.mb i.okb{width:100%;background:#16a34a}.mb i.fb{width:100%;background:#dc2626}.mb i.sb{width:100%;background:#9ca3af}.jw{position:relative}.job.r{padding-right:92px}.kl{position:absolute;right:10px;top:9px;font-size:12px;font-weight:700;color:#991b1b;background:#fee2e2;border-radius:8px;padding:4px 9px;text-decoration:none}.clr{float:right;font-size:12px;font-weight:600;color:#dc2626;text-decoration:none}@keyframes mv{0%{margin-left:-30%}100%{margin-left:100%}}
.ll{color:#6b7280;font:11.5px monospace;margin-top:4px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:7px}
pre{background:#111827;color:#d1fae5;border-radius:8px;padding:8px;font-size:11.5px;white-space:pre-wrap;word-break:break-all;margin:8px 0 0;max-height:220px;overflow:auto}
</style></head><body>
<h1>Daily Browser</h1><div class=m>কমান্ডে চাপলে নতুন ট্যাবে কাজ চলবে — সেখানেই লাইভ আউটপুট, সময় আর প্রগ্রেস দেখা যাবে।</div>
$warn
$bmSection<h2>🌐 ওয়ার্কস্পেস</h2><div class=m>যেকোনো সাইট যোগ করা যায়। প্রতিটির লগইন আলাদা ও স্থায়ী। কোনোটার লিমিট শেষ হলে নিজে থেকেই পরেরটায় চলে যায়, চ্যাটের শেষ অংশ সাথে নিয়ে পাঠিয়ে দেয় (⋮ মেনু থেকে বন্ধ করা যায়)।</div>
${if (siteCards.isEmpty()) "<div class=warn style='margin-top:8px'>এখনো কোনো ওয়ার্কস্পেস নেই। যেকোনো সাইট খুলে <b>⋮ › 🌐 এই সাইটকে ওয়ার্কস্পেস বানান</b> চাপুন, অথবা নিচের ＋ দিয়ে ঠিকানা লিখে যোগ করুন।</div>" else "<div class=g style='margin-top:8px'>$siteCards</div>"}<a class=add href='termux://siteedit/new'>＋ নতুন ওয়ার্কস্পেস</a>
<h2>কমান্ড</h2><div class=g>$cards</div><a class=add href='termux://edit/new'>＋ নতুন কমান্ড</a>
<h2>চলমান ও শেষ হওয়া কাজ <a class=clr href='termux://clear/all'>🗑 শেষ হওয়া মুছুন</a></h2><div id=jobs>${jobsHtml()}</div>
</body></html>"""
    }
}
