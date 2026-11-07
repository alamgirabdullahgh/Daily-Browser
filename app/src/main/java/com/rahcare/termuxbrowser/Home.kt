package com.rahcare.termuxbrowser

import android.content.Context

/** ব্রাউজারের হোম পেজ: Termux কমান্ড বাটন + চলমান কাজ */
object Home {
    const val BASE = "https://termux.home/"
    const val JOB = BASE + "job/"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun mark(j: Job) = when (j.status) { "running" -> "চলছে…"; "ok" -> "সম্পন্ন"; "stopped" -> "বন্ধ করা হয়েছে"; else -> "ব্যর্থ" }

    /** একটি কাজের বর্তমান অবস্থা (JSON) — কাজের ট্যাবের পাতা এটা দিয়ে নিজেকে আঁকে */
    fun state(j: Job): String = org.json.JSONObject().put("s", j.status).put("p", j.progress()).put("tot", j.total).put("o", j.text())
        .put("t0", j.time).put("t1", j.end).toString()

    /** হোম পেজের কাজের তালিকা: প্রতিটিতে চাপলে সেই কাজের ট্যাব খোলে */
    fun jobsHtml(): String {
        if (Jobs.list.isEmpty()) return "<div class=empty>এখনো কিছু চালানো হয়নি।</div>"
        val more = if (Jobs.list.size > 10) "<p class=m>…আরও ${Jobs.list.size - 10}টি পুরনো কাজ আছে (ওপরের 🗑 দিয়ে শেষ হওয়াগুলো মুছুন)</p>" else ""
        return Jobs.list.asReversed().take(10).joinToString("") { j ->
            val dot = when (j.status) { "running" -> "#f59e0b"; "ok" -> "#16a34a"; "stopped" -> "#6b7280"; else -> "#dc2626" }
            val bar = when {
                j.status == "ok" -> "<div class=mb><i class=okb></i></div>"
                j.status == "stopped" -> "<div class=mb><i class=sb></i></div>"
                j.status != "running" -> "<div class=mb><i class=fb></i></div>"
                j.progress() >= 0 -> "<div class=mb><i style='width:${j.progress()}%'></i></div>"
                else -> "<div class=mb><i class=ind></i></div>"
            }
            val last = j.lastLine()
            "<div class=jw><a class='job${if (j.status == "running") " r" else ""}' href='termux://job/${j.key}'><b><span class=dot style='background:$dot'></span>${esc(j.title)}</b> " +
                "<span class=m>${mark(j)} · ${j.elapsed()} সেকেন্ড${if (j.status == "running" && j.stepCount() > 0) " · ধাপ ${j.stepCount()}/${maxOf(j.total, j.stepCount())}" else ""}</span>$bar" +
                (if (last.isNotBlank()) "<div class=ll>${esc(last)}</div>" else "") + "</a>" +
                (if (j.status == "running") "<a class=kl href='termux://kill/${j.key}'>⏹ থামান</a>" else "") + "</div>"
        } + more
    }

    /** একটি কাজের নিজস্ব ট্যাব: শিরোনাম, অবস্থা, মোট প্রগ্রেস বার, ধাপ-গণনা, আর প্রতিটি ধাপ হেডিং হিসেবে — চাপলে ভেতরের আউটপুট খোলে */
    fun jobHtml(j: Job): String = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{--bg:#f3f4f6;--card:#fff;--tx:#111827;--mu:#6b7280;--ln:#e5e7eb;--code:#0b1220;--cx:#d1fae5}
@media(prefers-color-scheme:dark){:root{--bg:#0b1020;--card:#151b2e;--tx:#e5e7eb;--mu:#94a3b8;--ln:#232b45;--code:#05080f;--cx:#bbf7d0}}
body{font-family:system-ui,-apple-system,"Noto Sans Bengali",sans-serif;margin:0;padding:14px;background:var(--bg);color:var(--tx)}
.top{display:flex;align-items:center;gap:10px}h1{font-size:18px;margin:0;flex:1;word-break:break-word}
.chip{font-size:12px;font-weight:700;padding:4px 10px;border-radius:99px;background:#fef3c7;color:#92400e;white-space:nowrap}
.chip.ok{background:#dcfce7;color:#166534}.chip.fail{background:#fee2e2;color:#991b1b}.chip.stopped{background:#e5e7eb;color:#374151}
.sum{display:flex;justify-content:space-between;color:var(--mu);font-size:13px;margin:10px 0 4px}.sum b{color:var(--tx)}
.bar{height:9px;background:var(--ln);border-radius:5px;overflow:hidden;margin:2px 0 14px}
.bar i{display:block;height:100%;background:#2563eb;border-radius:5px;transition:width .35s}
.bar i.ind{width:30%;animation:mv 1.1s ease-in-out infinite}
@keyframes mv{0%{margin-left:-30%}100%{margin-left:100%}}
.bar.ok i{background:#16a34a}.bar.fail i{background:#dc2626}.bar.stopped i{background:#9ca3af}
details.cmd{margin-bottom:12px}details.cmd summary{font-size:12px;color:var(--mu);cursor:pointer}
code.c{display:block;background:var(--ln);border-radius:8px;padding:8px;font-size:11.5px;word-break:break-all;margin-top:6px;max-height:140px;overflow:auto;white-space:pre-wrap}
.step{background:var(--card);border:1px solid var(--ln);border-radius:12px;margin-bottom:8px;overflow:hidden}
.step summary{list-style:none;display:flex;gap:10px;align-items:flex-start;padding:11px 12px;cursor:pointer;-webkit-tap-highlight-color:transparent}
.step summary::-webkit-details-marker{display:none}
.ic{flex:none;width:22px;height:22px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:800;color:#fff;background:#16a34a;margin-top:1px}
.ic.run{background:#f59e0b;animation:sp 1.2s linear infinite}.ic.fail{background:#dc2626}.ic.stopped{background:#9ca3af}
@keyframes sp{0%{opacity:1}50%{opacity:.45}100%{opacity:1}}
.tt{flex:1;min-width:0}.tt b{font-size:14px;font-weight:600;word-break:break-word}
.hint{display:block;color:var(--mu);font:11.5px ui-monospace,monospace;margin-top:3px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.chev{flex:none;color:var(--mu);font-size:12px;margin-top:3px;transition:transform .15s}details[open]>summary .chev{transform:rotate(90deg)}
.step pre{background:var(--code);color:var(--cx);margin:0;padding:10px 12px;font-size:12px;line-height:1.45;white-space:pre-wrap;word-break:break-all;max-height:300px;overflow:auto}
.pend{color:var(--mu);font-size:13px;padding:6px 4px}
</style></head><body>
<div class=top><h1>${esc(j.title)}</h1><span id=st class=chip>চলছে…</span></div>
<div class=sum><span id=sc></span><span><b id=pc></b> <span id=el></span></span></div>
<div class=bar id=bar><i id=pb class=ind></i></div>
<details class=cmd><summary>কমান্ডের কোড দেখুন</summary><code class=c>${esc(j.script)}</code></details>
<div id=steps></div>
<script>
var S=${state(j)},OPEN={};
function two(n){return n<10?'0'+n:n}
function fmt(s){s=Math.floor(s);return Math.floor(s/60)+':'+two(s%60)}
function esc(x){return String(x).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function tick(){document.getElementById('el').textContent='· '+fmt(((S.t1||Date.now())-S.t0)/1000)}
function upd(x){S=x;draw()}
function parse(t){var L=(t||'').split('\n'),st=[],c=null;
 for(var i=0;i<L.length;i++){var l=L[i];
  if(l.indexOf('▸ ')===0){c={t:l.slice(2).trim(),b:[]};st.push(c)}
  else{if(!c){if(!l.trim())continue;c={t:'শুরু',b:[]};st.push(c)}c.b.push(l)}}
 return st}
function lastLine(b){for(var i=b.length-1;i>=0;i--){if(b[i].trim())return b[i].trim()}return ''}
function draw(){
 var run=S.s==='running',steps=parse(S.o),T=Math.max(S.tot||0,steps.length,1);
 var st=document.getElementById('st'),bar=document.getElementById('bar'),pb=document.getElementById('pb');
 var cls=S.s==='ok'?' ok':S.s==='fail'?' fail':S.s==='stopped'?' stopped':'';
 st.className='chip'+cls;
 st.textContent=run?'চলছে…':S.s==='ok'?'সম্পন্ন ✓':S.s==='stopped'?'বন্ধ করা হয়েছে ■':'ব্যর্থ ✗';
 bar.className='bar'+cls;
 var done=S.s==='ok'?T:Math.max(0,steps.length-1);
 if(run&&S.p<0){pb.className='ind';pb.style.width=''}
 else{pb.className='';pb.style.width=(S.s==='ok'?100:(S.p<0?(steps.length?Math.round(done/T*100):0):S.p))+'%'}
 document.getElementById('sc').textContent=S.s==='ok'?'সব ধাপ সম্পন্ন ('+T+'/'+T+')':'ধাপ: '+done+'/'+T+' সম্পন্ন';
 document.getElementById('pc').textContent=S.s==='ok'?'100%':(S.p>=0?S.p+'%':'');
 var h='';
 for(var i=0;i<steps.length;i++){
  var last=i===steps.length-1,ic,k;
  if(!last||S.s==='ok'){ic='✓';k=''}else if(run){ic='●';k=' run'}else if(S.s==='fail'){ic='✕';k=' fail'}else{ic='■';k=' stopped'}
  var body=steps[i].b.join('\n').trim();
  var open=OPEN[i]!==undefined?OPEN[i]:(last&&S.s==='fail');
  var hint=(last&&run)?lastLine(steps[i].b):'';
  h+='<details class=step'+(open?' open':'')+' ontoggle="OPEN['+i+']=this.open"><summary><span class="ic'+k+'">'+ic+'</span><span class=tt><b>'+esc(steps[i].t)+'</b>'+(hint&&!open?'<span class=hint>'+esc(hint)+'</span>':'')+'</span><span class=chev>▶</span></summary><pre>'+(body?esc(body):'(এখনো আউটপুট নেই)')+'</pre></details>';
 }
 if(!steps.length)h='<div class=pend>শুরু হচ্ছে…</div>';
 else if(run&&T>steps.length)h+='<div class=pend>○ আরও '+(T-steps.length)+'টি ধাপ বাকি</div>';
 document.getElementById('steps').innerHTML=h;
 tick()
}
setInterval(function(){if(S.s==='running')tick()},1000);
draw();
</script></body></html>"""

    private fun mono(s: String): String = if (s.isBlank()) "•" else esc(String(Character.toChars(s.trim().codePointAt(0))).uppercase())

    /** ওয়ার্কস্পেসের নাম জিমেইল হলে শেষের @gmail.com বাদ দিয়ে দেখায় (অন্য ডোমেইনের ইমেইল পুরোটাই) */
    private fun shortName(n: String): String = if (n.endsWith("@gmail.com", ignoreCase = true)) n.dropLast("@gmail.com".length) else n

    fun html(c: Context): String {
        val ready = Runner.installed(c) && Runner.hasPermission(c)
        val cmds = Store.all(c)
        val sites = Sites.all(c)
        val cards = cmds.joinToString("") { d ->
            "<div class=card><a class=run href='termux://run/${d.id}'><span class=mo>${mono(d.title)}</span><span class=nm>${esc(d.title)}</span></a>" +
                "<a class=ed href='termux://edit/${d.id}'>✎</a></div>"
        }
        fun siteCard(s: Site) =
            "<div class=card><a class=run href='termux://site/${s.id}'><span class='mo w'>${mono(shortName(s.name))}</span><span class=nm>${esc(shortName(s.name))}</span></a>" +
                "<a class=ed href='termux://siteedit/${s.id}'>✎</a></div>"
        // ওয়ার্কস্পেস এজেন্ট অনুযায়ী আলাদা হেডিংয়ে (Claude / Gemini / Grok); চেনা না গেলে "অন্যান্য ওয়ার্কস্পেস" (শুধু সেগুলোই থাকলে "ওয়ার্কস্পেস")
        val siteSection = if (sites.isEmpty()) "<h2>ওয়ার্কস্পেস</h2><div class=empty>এখনো কোনো ওয়ার্কস্পেস নেই। যেকোনো সাইট খুলে মেনু › ওয়ার্কস্পেস থেকে যোগ করুন, বা নিচের বোতামে ঠিকানা দিন।</div>" else {
            val b = StringBuilder()
            for (ag in AgentBus.ORDER) {
                val l = sites.filter { AgentBus.agentOf(it) == ag }
                if (l.isNotEmpty()) b.append("<h2>${esc(AgentBus.label(ag))}<span class=m>${l.size}</span></h2><div class=g>${l.joinToString("") { siteCard(it) }}</div>")
            }
            val rest = sites.filter { AgentBus.agentOf(it) == null }
            if (rest.isNotEmpty()) b.append("<h2>${if (rest.size == sites.size) "ওয়ার্কস্পেস" else "অন্যান্য ওয়ার্কস্পেস"}<span class=m>${rest.size}</span></h2><div class=g>${rest.joinToString("") { siteCard(it) }}</div>")
            b.toString()
        }
        val setupBtn = "<a class=add href='termux://setup'>⚡ এক চাপে সেটআপ</a>"
        val bm = Bookmarks.all(c).take(12)
        val bmSection = if (bm.isEmpty()) "" else "<h2>বুকমার্ক</h2><div class=g>" + bm.joinToString("") { b ->
            "<div class=card><a class=run href=\"${esc(b.second)}\"><span class='mo b'>${mono(b.first)}</span><span class=nm>${esc(b.first.take(30))}</span></a></div>" } + "</div>"
        val warn = if (ready && Runner.verified(c)) "" else if (ready)
            "<div class=warn><b>Termux সংযোগ বাকি</b><span>নিচের বোতামে চাপলে কমান্ড কপি হয়ে Termux খুলবে। সেখানে লম্বা চেপে Paste করে Enter দিন।</span>$setupBtn</div>"
        else
            "<div class=warn><b>সেটআপ দরকার</b><span>Termux (F-Droid) ইনস্টল করে মেনু › অ্যাপ › অনুমতি থেকে \"Run commands in Termux environment\" দিন, তারপর নিচের বোতাম চাপুন।</span>$setupBtn</div>"
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{--bg:#f5f6fa;--card:#fff;--tx:#0f172a;--mu:#64748b;--ln:#eceef5;--sh:0 1px 2px rgba(15,23,42,.04),0 6px 18px rgba(15,23,42,.06)}
@media(prefers-color-scheme:dark){:root{--bg:#0b1020;--card:#151b2e;--tx:#e5e7eb;--mu:#94a3b8;--ln:#232b45;--sh:0 1px 2px rgba(0,0,0,.3),0 6px 18px rgba(0,0,0,.25)}}
*{box-sizing:border-box}
body{font-family:system-ui,-apple-system,"Noto Sans Bengali",sans-serif;margin:0;padding:14px 14px 28px;background:var(--bg);color:var(--tx);-webkit-font-smoothing:antialiased}
.hero{position:relative;overflow:hidden;border-radius:22px;padding:22px 20px 20px;color:#fff;background:linear-gradient(135deg,#312e81 0%,#4f46e5 55%,#7c3aed 100%);box-shadow:0 10px 30px rgba(79,70,229,.35)}
.hero:after{content:"";position:absolute;right:-40px;top:-50px;width:170px;height:170px;border-radius:50%;background:rgba(255,255,255,.10)}
.hero:before{content:"";position:absolute;right:30px;bottom:-60px;width:120px;height:120px;border-radius:50%;background:rgba(255,255,255,.07)}
.hero h1{font-size:24px;font-weight:800;letter-spacing:-.3px;margin:0 0 4px;position:relative}
.hero p{margin:0;font-size:13px;opacity:.82;position:relative}
.stats{display:flex;gap:8px;margin-top:16px;position:relative;flex-wrap:wrap}
.stat{background:rgba(255,255,255,.16);border:1px solid rgba(255,255,255,.18);border-radius:12px;padding:7px 12px;font-size:12px;font-weight:600}
.stat b{font-size:15px;margin-right:4px}
h2{font-size:12px;font-weight:700;letter-spacing:.4px;color:var(--mu);margin:24px 4px 10px;display:flex;align-items:center;justify-content:space-between}
.m{color:var(--mu);font-size:13px}
.g{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin-bottom:10px}@media(min-width:560px){.g{grid-template-columns:repeat(3,1fr)}}
.card{position:relative;background:var(--card);border:1px solid var(--ln);border-radius:16px;box-shadow:var(--sh);overflow:hidden}
.run{display:flex;align-items:center;gap:10px;min-height:62px;padding:11px 28px 11px 11px;color:var(--tx);text-decoration:none}
.mo{flex:none;width:36px;height:36px;border-radius:11px;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:15px;color:#fff;background:linear-gradient(135deg,#6366f1,#8b5cf6)}
.mo.w{background:linear-gradient(135deg,#0ea5e9,#6366f1)}.mo.b{background:linear-gradient(135deg,#f59e0b,#f97316)}
.nm{font-size:13.5px;font-weight:600;line-height:1.3;overflow-wrap:anywhere}
.ed{position:absolute;top:2px;right:2px;padding:6px 9px;color:var(--mu);text-decoration:none;font-size:12px;opacity:.7}
.add{display:flex;align-items:center;justify-content:center;gap:6px;padding:13px;background:var(--card);color:#4f46e5;border:1.5px dashed #a5b4fc;border-radius:14px;text-decoration:none;font-size:13.5px;font-weight:700}
.warn{background:#fffbeb;color:#78350f;border:1px solid #fde68a;border-radius:16px;padding:14px;margin:14px 0 0;font-size:13px;line-height:1.5}
.warn b{display:block;font-size:14px;margin-bottom:3px}.warn span{display:block;margin-bottom:10px}
.warn .add{background:#4f46e5;color:#fff;border:0}
.job{display:block;text-decoration:none;color:inherit;background:var(--card);border:1px solid var(--ln);box-shadow:var(--sh);border-radius:14px;padding:11px 13px;margin-bottom:9px}
.job b{font-size:13.5px}.job .m{display:block;margin-top:3px;font-size:12px}
.mb{height:5px;background:var(--ln);border-radius:3px;overflow:hidden;margin:8px 0 2px}.mb i{display:block;height:100%;background:#4f46e5;border-radius:3px}.mb i.ind{width:30%;animation:mv 1.1s ease-in-out infinite}.mb i.okb{width:100%;background:#16a34a}.mb i.fb{width:100%;background:#dc2626}.mb i.sb{width:100%;background:#9ca3af}
.jw{position:relative}.job.r{padding-right:92px}.kl{position:absolute;right:10px;top:10px;font-size:12px;font-weight:700;color:#991b1b;background:#fee2e2;border-radius:9px;padding:5px 10px;text-decoration:none}
.clr{font-size:12px;font-weight:700;color:#dc2626;text-decoration:none;letter-spacing:0}
@keyframes mv{0%{margin-left:-30%}100%{margin-left:100%}}
.ll{color:var(--mu);font:11.5px ui-monospace,monospace;margin-top:5px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:7px}
.empty{background:var(--card);border:1px dashed var(--ln);border-radius:14px;padding:14px;color:var(--mu);font-size:13px;margin-bottom:10px;line-height:1.5}
</style></head><body>
<div class=hero><h1>Daily Browser</h1><p>আপনার কাজের জন্য তৈরি ব্রাউজার</p>
<div class=stats><div class=stat><b>${sites.size}</b>ওয়ার্কস্পেস</div><div class=stat><b>${cmds.size}</b>কমান্ড</div><div class=stat><b>${Jobs.list.count { it.status == "running" }}</b>চলছে</div></div></div>
$warn
$bmSection$siteSection<a class=add href='termux://siteedit/new'>＋ নতুন ওয়ার্কস্পেস</a>
<h2>কমান্ড</h2><div class=g>$cards</div><a class=add href='termux://edit/new'>＋ নতুন কমান্ড</a>
<h2>কাজ <a class=clr href='termux://clear/all'>শেষ হওয়াগুলো মুছুন</a></h2><div id=jobs>${jobsHtml()}</div>
</body></html>"""
    }
}
