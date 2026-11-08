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
    private fun setEmail(c: Context, siteId: String, email: String) { pref(c).edit().putString("al_$siteId", email.trim()).apply() }

    private fun hostOf(u: String) = (Uri.parse(if (u.contains("://")) u else "https://$u").host ?: "").lowercase().removePrefix("www.")

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
