package com.rahcare.termuxbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * অটো-লগইন + এক কমান্ডে অসীম ওয়ার্কস্পেস।
 *
 * - প্রতিটি ওয়ার্কস্পেসের সাথে একটি ইমেইল জোড়া থাকে (pref "al_<siteId>"); পাসওয়ার্ড থাকে শুধু Creds-এ (Keystore-এনক্রিপ্টেড)।
 * - গুগল-ইমেইল হলে (বা সাইট google.com-এর হলে) accounts.google.com-এ ইমেইল → পাসওয়ার্ড → Next নিজে চলে; অন্য সাইটে সাধারণ লগইন-ফর্ম।
 * - নিরাপত্তা: পাসওয়ার্ড শুধু (ক) accounts.google.com বা (খ) ওয়ার্কস্পেসের নিজের সাইটের হোস্টে বসে; অন্য কোনো ঠিকানায় কখনো না।
 * - ২-ধাপ যাচাই/ক্যাপচা/"নিরাপদ নয়" এলে থেমে জানায়। এক ওয়ার্কস্পেসে ১০ মিনিটে সর্বোচ্চ ৪ বার চেষ্টা (ভুল পাসওয়ার্ডে অ্যাকাউন্ট আটকানো এড়াতে)।
 * - এজেন্ট-বাসের কমান্ড (bus.json → "ws_cmds"): পাসওয়ার্ড এতে থাকে না, শুধু নাম/সাইট/ইমেইল।
 */
object AutoLogin {
    data class Item(val name: String, val url: String, val email: String, val pw: String? = null)

    private fun pref(c: Context) = Store.prefs(c)
    fun emailOf(c: Context, siteId: String): String = pref(c).getString("al_$siteId", "") ?: ""
    fun setEmail(c: Context, siteId: String, email: String) { pref(c).edit().putString("al_$siteId", email.trim()).apply() }

    fun hostOf(u: String) = (Uri.parse(if (u.contains("://")) u else "https://$u").host ?: "").lowercase().removePrefix("www.")

    /** গুগল-পদ্ধতি: ইমেইল জিমেইল/গুগল ডোমেইনের, বা সাইট নিজেই গুগলের */
    fun isGoogle(email: String, siteUrl: String): Boolean {
        val e = email.lowercase()
        return e.endsWith("@gmail.com") || e.endsWith("@googlemail.com") || hostOf(siteUrl).endsWith("google.com")
    }

    // ───────────── JS: গুগলের লগইন পাতা ─────────────
    private const val COMMON = """
function vis(e){var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'}
function setv(el,v){el.focus();var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))}
function clk(b){try{b.click()}catch(e){}try{var o={bubbles:true,cancelable:true,view:window};b.dispatchEvent(new MouseEvent('mousedown',o));b.dispatchEvent(new MouseEvent('mouseup',o));b.dispatchEvent(new MouseEvent('click',o))}catch(e){}}
function txt(e){return (e.innerText||e.textContent||'').replace(/\s+/g,' ').trim()}
function q(s){var l=document.querySelectorAll(s);for(var i=0;i<l.length;i++)if(vis(l[i]))return l[i];return null}
function btn(re){var l=document.querySelectorAll('button,[role=button],input[type=submit],a');for(var i=0;i<l.length;i++){var t=l[i].value||txt(l[i]);if(vis(l[i])&&re.test(t))return l[i]}return null}
"""

    private fun googleJs(email: String, pw: String) = """(function(em,pw){try{
if(window.__alRun)return 'running';window.__alRun=1;window.__alResult='';
$COMMON
var n=0,f={};
var t=setInterval(function(){n++;
if(n>45){clearInterval(t);window.__alRun=0;return}
var body=(document.body&&document.body.innerText)||'';
var pwd=q('input[name=Passwd],input[type=password]'),id=q('input#identifierId,input[type=email]');
if(!pwd&&!id&&/2-Step Verification|Verify it.s you|not a robot|captcha|Couldn.t sign you in|may not be secure|Try again|Wrong password/i.test(body)){clearInterval(t);window.__alRun=0;window.__alResult='challenge';return}
if(pwd){if(!f.p){f.p=1;setv(pwd,pw);setTimeout(function(){var b=q('#passwordNext button')||q('#passwordNext')||btn(/^(next|sign in)${'$'}/i);if(b)clk(b)},600)}return}
if(id){if(!f.e){f.e=1;setv(id,em);setTimeout(function(){var b=q('#identifierNext button')||q('#identifierNext')||btn(/^next${'$'}/i);if(b)clk(b)},600)}return}
var acc=document.querySelectorAll('[data-identifier]');
for(var i=0;i<acc.length;i++){if(vis(acc[i])&&(acc[i].getAttribute('data-identifier')||'').toLowerCase()===em.toLowerCase()){if(!f.a){f.a=1;clk(acc[i])}return}}
if(!f.o){var o=btn(/use another account/i);if(o){f.o=1;clk(o)}}
},700);
return 'started'}catch(x){return 'err'}})(${JSONObject.quote(email)},${JSONObject.quote(pw)})"""

    // ───────────── JS: সাইটের নিজের পাতা (সাইন-ইন বোতাম / সাধারণ ফর্ম) ─────────────
    private fun siteJs(email: String, pw: String, google: Boolean) = """(function(em,pw,g){try{
if(window.__alSite)return 'running';window.__alSite=1;
$COMMON
var n=0,f={};
var t=setInterval(function(){n++;
if(n>25){clearInterval(t);window.__alSite=0;return}
var pe=q('input[type=password]');
if(pe&&!g){var ue=q('input[type=email],input[autocomplete=username],input[name*=email i],input[name*=user i],input[name*=login i],input[id*=email i],input[id*=user i],input[type=text]');
 if(ue&&!f.u&&!ue.value){f.u=1;setv(ue,em)}
 if(!f.p){f.p=1;setv(pe,pw);setTimeout(function(){var b=q('button[type=submit],input[type=submit]')||btn(/^(sign in|log in|login|continue|submit)${'$'}/i);if(b)clk(b)},600)}return}
if(!pe&&!g){var ue2=q('input[type=email],input[autocomplete=username],input[name*=email i],input[id*=email i]');
 if(ue2&&!f.u2){f.u2=1;setv(ue2,em);setTimeout(function(){var b=q('button[type=submit],input[type=submit]')||btn(/^(next|continue|sign in|log in|login)${'$'}/i);if(b)clk(b)},600);return}}
if(g&&!f.g){var gb=btn(/(continue|sign in|log in|login)? ?(with|using)? ?google${'$'}/i);if(gb){f.g=1;clk(gb);return}}
if(!f.s){var sb=btn(/^(sign in|log in|login|sign in to .{1,30})${'$'}/i);if(sb){f.s=1;clk(sb)}}
},800);
return 'started'}catch(x){return 'err'}})(${JSONObject.quote(email)},${JSONObject.quote(pw)},$google)"""

    private val tries = HashMap<String, MutableList<Long>>()
    private val warned = HashSet<String>()

    private fun allow(siteId: String): Boolean {
        val now = System.currentTimeMillis()
        val l = tries.getOrPut(siteId) { mutableListOf() }
        l.removeAll { now - it > 10 * 60 * 1000L }
        if (l.size >= 4) return false
        l.add(now); return true
    }

    /** প্রতিটি পাতা লোড শেষে ডাকা হয় (UI থ্রেডে)। */
    fun onPage(c: Context, siteId: String?, url: String, web: WebView, toast: (String) -> Unit) {
        if (siteId == null || !url.startsWith("https://")) return
        val email = emailOf(c, siteId); if (email.isBlank()) return
        val site = Sites.all(c).firstOrNull { it.id == siteId } ?: return
        val pw = Creds.get(c, email)
        if (pw.isEmpty()) {
            if (warned.add(siteId)) toast("“${site.name}”: $email-এর পাসওয়ার্ড সংরক্ষিত নেই — মেনু → লগইন তথ্য থেকে দিন")
            return
        }
        val host = hostOf(url); val siteHost = hostOf(site.url)
        val google = isGoogle(email, site.url)
        val js = when {
            google && host == "accounts.google.com" -> googleJs(email, pw)
            host == siteHost || host.endsWith(".$siteHost") -> siteJs(email, pw, google)
            else -> return   // অন্য কোনো ঠিকানায় পাসওয়ার্ড কখনো নয়
        }
        if (!allow(siteId)) {
            if (warned.add("lim$siteId")) toast("“${site.name}”: ১০ মিনিটে ৪ বার চেষ্টা হয়ে গেছে — অ্যাকাউন্ট আটকানো এড়াতে থামলাম। হাতে লগইন করুন")
            return
        }
        web.evaluateJavascript(js, null)
        if (google && host == "accounts.google.com") {
            web.postDelayed({
                web.evaluateJavascript("(function(){try{return window.__alResult||''}catch(e){return ''}})()") { r ->
                    if (r != null && r.contains("challenge")) toast("“${site.name}”: গুগল অতিরিক্ত যাচাই (২-ধাপ/ক্যাপচা/ভুল পাসওয়ার্ড) চাইছে — বাকিটা হাতে করুন")
                }
            }, 32000L)
        }
    }

    // ───────────── ওয়ার্কস্পেস তৈরি ─────────────
    /** আইটেম অনুযায়ী ওয়ার্কস্পেস বানায় (সংখ্যার সীমা নেই), ইমেইল জোড়ে, পাসওয়ার্ড দেওয়া থাকলে Creds-এ রাখে। নতুন সাইটগুলো ফেরত দেয়। */
    fun create(c: Context, items: List<Item>): List<Site> {
        val list = Sites.all(c)
        val made = mutableListOf<Site>()
        val base = System.currentTimeMillis()
        items.forEachIndexed { i, it ->
            if (it.url.isBlank()) return@forEachIndexed
            val url = Sites.normalizeUrl(it.url)
            val name = it.name.ifBlank { hostOf(url) }
            val s = Site("s${base + i}", name, url)
            list.add(s); made.add(s)
            if (it.email.isNotBlank()) {
                setEmail(c, s.id, it.email)
                if (!it.pw.isNullOrEmpty()) Creds.put(c, it.email, it.pw)
            }
        }
        Sites.save(c, list)
        return made
    }

    /** "নাম | সাইট | ইমেইল | পাসওয়ার্ড(ঐচ্ছিক)" লাইন পার্স */
    fun parse(text: String): List<Item> = text.lines().mapNotNull { ln ->
        val p = ln.split("|").map { it.trim() }
        if (p.size < 2 || p[1].isBlank()) null
        else Item(p[0], p[1], p.getOrElse(2) { "" }, p.getOrNull(3)?.takeIf { it.isNotEmpty() })
    }

    // ───────────── এজেন্ট-বাস কমান্ড ─────────────
    /** bus.json-এ "ws_cmds": [{"id":"..","status":"queued","items":[{"name":"","url":"","email":""}]}]  — ব্যাকগ্রাউন্ড থ্রেডে ডাকুন */
    fun pollBus(act: Activity, tok: String, open: (Site) -> Unit) {
        val d = AgentBus.load(tok)?.d ?: return
        val arr = d.optJSONArray("ws_cmds") ?: return
        val seen = pref(act).getStringSet("al_bus_done", emptySet())!!.toMutableSet()
        val todo = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            if (o.optString("status") == "queued" && id.isNotEmpty() && id !in seen) todo.add(o)
        }
        if (todo.isEmpty()) return
        for (o in todo) {
            seen.add(o.optString("id")); pref(act).edit().putStringSet("al_bus_done", seen).apply()
            val its = o.optJSONArray("items") ?: continue
            val items = (0 until its.length()).map { k ->
                val e = its.getJSONObject(k); Item(e.optString("name"), e.optString("url"), e.optString("email"))   // পাসওয়ার্ড কমান্ডে নেওয়া হয় না
            }
            act.runOnUiThread {
                val made = create(act, items)
                Toast.makeText(act, "এজেন্ট কমান্ড: ${made.size}টি ওয়ার্কস্পেস তৈরি হয়েছে", Toast.LENGTH_LONG).show()
                made.forEachIndexed { i, s -> act.window.decorView.postDelayed({ open(s) }, 4000L * (i + 1)) }
            }
        }
        val ids = todo.map { it.optString("id") }.toSet()
        AgentBus.mutate(tok, "ws_cmds done") { dd ->
            val a = dd.optJSONArray("ws_cmds") ?: return@mutate false
            var ch = false
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") in ids && o.optString("status") == "queued") { o.put("status", "done").put("updated", AgentBus.now()); ch = true } }
            if (ch) AgentBus.ev(dd, "ওয়ার্কস্পেস কমান্ড সম্পন্ন: ${ids.joinToString()}")
            ch
        }
    }

    // ───────────── হাব-চ্যাটের কথা থেকে সরাসরি ওয়ার্কস্পেস (কোনো এজেন্ট ছাড়াই) ─────────────
    private val RE_MAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val RE_URL = Regex("(?i)(https?://[^\\s,;|)]+|\\b(?:[a-z0-9-]+\\.)+(?:com|ai|io|org|net|app|co|dev|me|bd|xyz|tv)\\b(?:/[^\\s,;|)]*)?)")
    private val RE_PW = Regex("(?i)(?:পাসওয়ার্ড|পাসওয়ার্ডটা|password|passwd|pass)\\s*(?:[:=]|হলো|হল|is)\\s*(\\S+)")
    private val RE_LOGIN = Regex("(?i)(লগইন|লগ\\s?ইন|login|log\\s?in|sign\\s?in)")
    private val RE_TRIGGER = Regex("(?i)(ওয়ার্ক\\s?স্পেস|workspace|work\\s?space)")
    private val KNOWN = mapOf("gemini" to "gemini.google.com", "জেমিনি" to "gemini.google.com", "chatgpt" to "chatgpt.com", "claude" to "claude.ai",
        "ক্লদ" to "claude.ai", "grok" to "grok.com", "perplexity" to "perplexity.ai", "deepseek" to "chat.deepseek.com", "github" to "github.com")

    private val BN_DIGITS = "০১২৩৪৫৬৭৮৯"
    private val WORDS = mapOf("এক" to 1, "দুই" to 2, "দুটি" to 2, "তিন" to 3, "চার" to 4, "পাঁচ" to 5, "ছয়" to 6, "সাত" to 7, "আট" to 8, "নয়" to 9, "দশ" to 10,
        "এগার" to 11, "এগারো" to 11, "বার" to 12, "বারো" to 12, "তের" to 13, "তেরো" to 13, "চৌদ্দ" to 14, "পনের" to 15, "পনেরো" to 15, "ষোল" to 16, "ষোলো" to 16,
        "সতের" to 17, "সতেরো" to 17, "আঠার" to 18, "আঠারো" to 18, "উনিশ" to 19, "বিশ" to 20)
    private val RE_COUNT = Regex("([0-9০-৯]+|" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")\\s*(?:টি|টা|ট)?\\s*(?:ওয়ার্ক\\s?স্পেস|(?i:workspaces?|work\\s?spaces?))")

    /** "১২টি ওয়ার্কস্পেস", "বারটি ওয়ার্ক স্পেস", "5 workspaces" থেকে সংখ্যা; না পেলে ০ */
    fun countOf(text: String): Int {
        val m = RE_COUNT.find(text) ?: return 0
        val w = m.groupValues[1]
        val digits = w.map { ch -> val i = BN_DIGITS.indexOf(ch); if (i >= 0) ('0' + i) else ch }.joinToString("")
        digits.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        return WORDS[w] ?: 0
    }

    class Parsed(val items: List<Item>, val redacted: String, val savedPw: Boolean, val note: String)

    /** চ্যাটের লেখা থেকে ওয়ার্কস্পেসের তালিকা বানায়। পাসওয়ার্ড থাকলে আইটেমে নেয় আর লেখা থেকে মুছে (***) দেয়। null = এটা ওয়ার্কস্পেস-কমান্ড নয় */
    fun parseChat(c: Context, text: String): Parsed? {
        // ওয়ার্কস্পেসের কথা থাকলে, অথবা (লগইন-শব্দ + ইমেইল ঠিকানা) থাকলে ধরি। শুধু "login" লেখা কোডিং-কাজ ছুঁই না
        if (!RE_TRIGGER.containsMatchIn(text) && !(RE_LOGIN.containsMatchIn(text) && RE_MAIL.containsMatchIn(text))) return null
        val gMail = RE_MAIL.find(text)?.value ?: ""
        val gPw = RE_PW.find(text)?.groupValues?.get(1)?.trim('.', ',', '।')?.takeIf { it != "***" } ?: ""
        val saved = Creds.emails(c)
        val out = mutableListOf<Item>()
        for (ln in text.lines()) {
            val mail = RE_MAIL.find(ln)?.value ?: ""
            val pw = RE_PW.find(ln)?.groupValues?.get(1)?.trim('.', ',', '।')?.takeIf { it != "***" } ?: ""
            val scan = RE_PW.replace(RE_MAIL.replace(ln, " "), " ")
            val urls = RE_URL.findAll(scan).toList()
            val em = mail.ifBlank { gMail }.ifBlank { if (saved.size == 1) saved[0] else "" }
            val p = pw.ifBlank { if (mail.isBlank() || mail == gMail) gPw else "" }.ifBlank { null }
            for (u in urls) {
                var name = ""
                if (urls.size == 1) name = scan.substring(0, u.range.first).trim().trimEnd(':', '-', '=', '|', '–').trim().takeIf { it.length in 1..30 && !RE_TRIGGER.containsMatchIn(it) } ?: ""
                out.add(Item(name, u.value, em, p))
            }
            if (urls.isEmpty()) for ((k, v) in KNOWN) if (scan.contains(k, ignoreCase = true) && !RE_TRIGGER.matches(scan.trim())) out.add(Item(k, v, em, p))
        }
        val uniq0 = out.distinctBy { hostOf(it.url) + "|" + it.name + "|" + it.email }
        if (uniq0.isEmpty()) return null
        // "এই লিংক দিয়ে বারটি ওয়ার্কস্পেস" — একটাই লিংক আর সংখ্যা N হলে N টি বানায়; একাধিক ইমেইল দিলে পরপর একটা করে বসে
        val n = countOf(text)
        val uniq = if (uniq0.size == 1 && n > 1) {
            val b = uniq0[0]
            val ms = RE_MAIL.findAll(text).map { it.value }.distinct().toList()
            (0 until n).map { i ->
                val em = ms.getOrNull(i) ?: b.email
                Item(b.name.ifBlank { hostOf(b.url) } + " " + (i + 1), b.url, em, if (em == b.email) b.pw else null)
            }
        } else uniq0
        var red = text
        for (m in RE_PW.findAll(text).toList().reversed()) red = red.replaceRange(m.groups[1]!!.range, "***")
        val noMail = uniq.count { it.email.isBlank() }
        val note = if (noMail > 0) "${noMail}টির ইমেইল দেওয়া ছিল না — লগইন ছাড়াই খোলা হবে" else ""
        return Parsed(uniq, red, gPw.isNotEmpty() || uniq.any { !it.pw.isNullOrEmpty() }, note)
    }

    /** ওয়ার্কস্পেসের তালিকা (নাম+সাইট; পাসওয়ার্ড/কুকি নয়) bus.json-এ ব্যাকআপ রাখে; একদম নতুন ইনস্টলে (তালিকা কখনো ছিল না) সেখান থেকে ফিরিয়ে আনে। ব্যাকগ্রাউন্ড থ্রেডে ডাকুন */
    fun syncSites(act: Activity, tok: String, onRestored: () -> Unit) {
        val fresh = !act.getSharedPreferences("sites", Context.MODE_PRIVATE).contains("list")
        val d = AgentBus.load(tok)?.d ?: return
        val remote = d.optJSONArray("sites_backup")
        if (fresh) {
            if (remote != null && remote.length() > 0) {
                val l = (0 until remote.length()).map { val o = remote.getJSONObject(it); Site(o.getString("id"), o.getString("n"), o.getString("u")) }.toMutableList()
                Sites.save(act, l)
                act.runOnUiThread { Toast.makeText(act, "ব্যাকআপ থেকে ${l.size}টি ওয়ার্কস্পেস ফিরে এসেছে — লগইন আবার করতে হবে", Toast.LENGTH_LONG).show(); onRestored() }
            }
            return
        }
        val cur = org.json.JSONArray()
        Sites.all(act).forEach { cur.put(JSONObject().put("id", it.id).put("n", it.name).put("u", it.url)) }
        if (cur.length() == 0 || remote?.toString() == cur.toString()) return
        AgentBus.mutate(tok, "sites backup") { dd -> dd.put("sites_backup", cur); true }
    }

    /** AI-কে পাঠানোর আগে পাসওয়ার্ড মুছে দেয় (মূল লেখা বদলায় না) */
    fun stripPw(text: String): String {
        var red = text
        for (m in RE_PW.findAll(text).toList().reversed()) red = red.replaceRange(m.groups[1]!!.range, "***")
        return red
    }
    fun chatPw(text: String) = RE_PW.find(text)?.groupValues?.get(1)?.trim('.', ',', '।')?.takeIf { it != "***" } ?: ""

    /** AI-র আগে-না-দেখা queued টাস্ক কি এখনো ক্লাসিফাই হয়নি? (হলে এজেন্টকে পাঠানো থামে) */
    fun aiPending(c: Context, id: Int): Boolean = AiRouter.hasKey(c) && id.toString() !in pref(c).getStringSet("al_ai_seen", emptySet())!!

    private val lock = Any()

    /** ব্রাউজারের নিজের কাজ: প্রথমে রেগুলার ধরন, তারপর (কী থাকলে) AI। ব্যাকগ্রাউন্ড থ্রেডে ডাকুন */
    fun pollTasks(act: Activity, tok: String, open: (Site) -> Unit) {
        synchronized(lock) {
            if (AiRouter.hasKey(act)) {
                // কী থাকলে AI-ই আগে বোঝে (আগে-থেকে-বানানো ওয়ার্কস্পেসে লগইন বনাম নতুন বানানো); রেগুলার ধরন শুধু AI ব্যর্থ হলে বিকল্প
                try { aiStep(act, tok, open) } catch (_: Exception) {}
                val failed = pref(act).getStringSet("al_ai_fail", emptySet())!!.toSet()
                if (failed.isNotEmpty()) try { pollRegex(act, tok, open, failed) } catch (_: Exception) {}
            } else {
                try { pollRegex(act, tok, open, null) } catch (_: Exception) {}
            }
        }
    }

    fun pwByEmail(text: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (ln in text.lines()) {
            val mail = RE_MAIL.find(ln)?.value ?: continue
            val pw = RE_PW.find(ln)?.groupValues?.get(1)?.trim('.', ',', '।')?.takeIf { it != "***" } ?: continue
            if (pw.isNotEmpty()) m[mail.lowercase()] = pw
        }
        return m
    }

    fun matchSites(all: List<Site>, name: String, url: String): List<Site> {
        val n = name.trim()
        if (n == "*") return all
        val byName = all.filter { n.isNotEmpty() && (it.name.equals(n, true) || it.name.contains(n, true)) }
        if (byName.isNotEmpty()) return byName
        val h = if (url.isBlank()) "" else hostOf(url)
        return if (h.isNotEmpty()) all.filter { val x = hostOf(it.url); x == h || x.endsWith(".$h") } else emptyList()
    }

    /** নতুন queued কাজ ধরে Gemini-এজেন্টকে দেয়। পাসওয়ার্ড আগেই ফোনে সংরক্ষিত হয়ে বাস থেকে মুছে যায় */
    private fun aiStep(act: Activity, tok: String, open: (Site) -> Unit) {
        val d = AgentBus.load(tok)?.d ?: return
        val arr = d.optJSONArray("tasks") ?: return
        val seen = pref(act).getStringSet("al_ai_seen", emptySet())!!.toMutableSet()
        val jobs = mutableListOf<Triple<Int, String, String>>()   // আইডি, নিরাপদ লেখা (পাসওয়ার্ড ***), এজেন্টের জন্য লেখা (+ নোট)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val id = o.optInt("id")
            if (o.optString("status") != "queued" || id.toString() in seen) continue
            val text = o.optString("text")
            seen.add(id.toString()); pref(act).edit().putStringSet("al_ai_seen", seen).apply()
            if (text.isBlank()) continue
            val pr = AiAgent.prepare(act, text)
            jobs.add(Triple(id, pr.first, pr.first + pr.second))
        }
        if (jobs.isEmpty()) return
        val safe = jobs.associate { it.first to it.second }
        AgentBus.mutate(tok, "ai claim") { dd ->
            val a = dd.optJSONArray("tasks") ?: return@mutate false
            var ch = false
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val t = safe[o.optInt("id")] ?: continue
                if (o.optString("status") != "queued") continue
                o.put("status", "running").put("agent", "browser").put("progress", 15).put("updated", AgentBus.now()).put("text", t).put("note", "🤖 Gemini কাজ করছে…")
                ch = true
            }
            ch
        }
        for (j in jobs) AiAgent.submit(Runnable { finishTask(act, tok, j.first, j.second, j.third) })
    }

    private fun finishTask(act: Activity, tok: String, id: Int, safeText: String, agentText: String) {
        val out = try { AiAgent.run(act, agentText) } catch (e: Exception) { AiRouter.lastErr = e.javaClass.simpleName + ": " + (e.message ?: "").take(60); null }
        if (out == null) {
            pref(act).edit().putStringSet("al_ai_fail", pref(act).getStringSet("al_ai_fail", emptySet())!! + id.toString()).apply()
            act.runOnUiThread { Toast.makeText(act, "AI ব্যর্থ (" + AiRouter.lastErr + ") — কাজটা সাধারণভাবে চলছে", Toast.LENGTH_LONG).show() }
        }
        AgentBus.mutate(tok, "ai done") { dd ->
            val a = dd.optJSONArray("tasks") ?: return@mutate false
            var ch = false
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); if (o.optInt("id") != id) continue
                if (o.optString("status") != "running") continue
                o.put("updated", AgentBus.now())
                when {
                    out == null -> { o.put("status", "queued").put("agent", "").put("progress", 0).put("note", "Gemini ব্যর্থ (" + AiRouter.lastErr + ") — সাধারণ পথে") }
                    out.handoff -> { o.put("status", "queued").put("agent", "").put("progress", 0).put("note", "Gemini: কোডিং এজেন্টকে দেওয়া হলো") }
                    else -> { o.put("status", "done").put("agent", "browser").put("progress", 100).put("note", "🤖 Gemini:\n" + out.text.take(6000)) }
                }
                AgentBus.ev(dd, "টাস্ক #$id: Gemini " + (if (out == null) "ব্যর্থ" else if (out.handoff) "এজেন্টে পাঠাল" else "শেষ করল"))
                ch = true
            }
            ch
        }
    }

    /** রেগুলার ধরন: queued টাস্কে ওয়ার্কস্পেস-কথা থাকলে ব্রাউজারই করে ফেলে */
    private fun pollRegex(act: Activity, tok: String, open: (Site) -> Unit, onlyIds: Set<String>?) {
        val d = AgentBus.load(tok)?.d ?: return
        val arr = d.optJSONArray("tasks") ?: return
        val seen = pref(act).getStringSet("al_task_done", emptySet())!!.toMutableSet()
        val hits = mutableListOf<Pair<Int, Parsed>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optInt("id")
            if (o.optString("status") != "queued" || id.toString() in seen) continue
            if (onlyIds != null && id.toString() !in onlyIds) continue
            val p = parseChat(act, o.optString("text")) ?: continue
            hits.add(id to p)
        }
        for ((id, p) in hits) {
            seen.add(id.toString()); pref(act).edit().putStringSet("al_task_done", seen).apply()
            act.runOnUiThread {
                val made = create(act, p.items)
                Toast.makeText(act, "হাব-কমান্ড: ${made.size}টি ওয়ার্কস্পেস তৈরি — একে একে খুলে লগইন হবে" + (if (p.savedPw) "\nপাসওয়ার্ড ফোনে সংরক্ষিত; GitHub ইতিহাসে থাকতে পারে — ভবিষ্যতে মেনুতে দিন" else ""), Toast.LENGTH_LONG).show()
                made.forEachIndexed { i, s -> act.window.decorView.postDelayed({ open(s) }, 4000L * (i + 1)) }
            }
        }
        if (hits.isEmpty()) return
        val byId = hits.toMap()
        AgentBus.mutate(tok, "ws tasks done") { dd ->
            val a = dd.optJSONArray("tasks") ?: return@mutate false
            var ch = false
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val p = byId[o.optInt("id")] ?: continue
                if (o.optString("status") != "queued") continue
                o.put("status", "done").put("agent", "browser").put("progress", 100).put("updated", AgentBus.now())
                    .put("text", p.redacted).put("note", "ব্রাউজার নিজে ${p.items.size}টি ওয়ার্কস্পেস বানিয়েছে" + (if (p.note.isNotEmpty()) " — ${p.note}" else ""))
                AgentBus.ev(dd, "টাস্ক #${o.optInt("id")}: ওয়ার্কস্পেস ব্রাউজারই বানাল (${p.items.joinToString { it.name.ifBlank { hostOf(it.url) } }})")
                ch = true
            }
            ch
        }
    }

    // ───────────── ডায়ালগ ─────────────
    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** লগইন তথ্য: ইমেইল + পাসওয়ার্ড যোগ/মোছা (Keystore-এনক্রিপ্টেড) */
    fun credsDialog(a: Activity) {
        val l = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0) }
        val saved = Creds.emails(a)
        l.addView(TextView(a).apply { text = if (saved.isEmpty()) "এখনো কোনো ইমেইল সংরক্ষিত নেই।" else "সংরক্ষিত: " + saved.joinToString(", "); textSize = 12f })
        val e = EditText(a).apply { hint = "ইমেইল"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT }
        val p = EditText(a).apply { hint = "পাসওয়ার্ড"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        l.addView(e); l.addView(p)
        AlertDialog.Builder(a).setTitle("লগইন তথ্য (ফোনে এনক্রিপ্টেড)").setView(l)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                val ok = Creds.put(a, e.text.toString(), p.text.toString())
                Toast.makeText(a, if (ok) "সংরক্ষিত" else "ইমেইল ও পাসওয়ার্ড দুটোই দিন", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("এই ইমেইল মুছুন") { _, _ ->
                if (e.text.isNotBlank()) { Creds.remove(a, e.text.toString()); Toast.makeText(a, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("বন্ধ", null).show()
    }

    /** এক সাথে অনেক ওয়ার্কস্পেস: প্রতি লাইনে "নাম | সাইট | ইমেইল | পাসওয়ার্ড(ঐচ্ছিক)" */
    fun bulkDialog(a: Activity, open: (Site) -> Unit) {
        val l = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0) }
        l.addView(TextView(a).apply { text = "প্রতি লাইনে একটি:\nনাম | সাইট | ইমেইল | পাসওয়ার্ড (পাসওয়ার্ড ঐচ্ছিক — আগে সংরক্ষিত থাকলে লাগে না)"; textSize = 12f })
        val t = EditText(a).apply {
            hint = "যেমন:\nজেমিনি ১ | gemini.google.com | me@gmail.com | পাসওয়ার্ড\nজেমিনি ২ | gemini.google.com | other@gmail.com"
            minLines = 6; gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        l.addView(t)
        AlertDialog.Builder(a).setTitle("একসাথে ওয়ার্কস্পেস + অটো-লগইন").setView(l)
            .setPositiveButton("বানান") { _, _ ->
                val made = create(a, parse(t.text.toString()))
                Toast.makeText(a, "${made.size}টি ওয়ার্কস্পেস তৈরি — একে একে খুলে লগইন হবে", Toast.LENGTH_LONG).show()
                made.forEachIndexed { i, s -> a.window.decorView.postDelayed({ open(s) }, 4000L * i) }
            }
            .setNegativeButton("বন্ধ", null).show()
    }
}
