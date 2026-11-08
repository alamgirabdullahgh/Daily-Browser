package com.rahcare.termuxbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** বুকমার্ক (শিরোনাম + ঠিকানা), ফোনের ভেতরেই থাকে */
object Bookmarks {
    private fun sp(c: Context) = c.getSharedPreferences("browse", Context.MODE_PRIVATE)

    fun all(c: Context): List<Pair<String, String>> = try {
        val a = JSONArray(sp(c).getString("bm", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Pair(o.optString("t"), o.getString("u")) }
    } catch (_: Exception) { emptyList() }

    private fun save(c: Context, l: List<Pair<String, String>>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("t", it.first).put("u", it.second)) }
        sp(c).edit().putString("bm", a.toString()).apply()
    }

    fun has(c: Context, url: String) = all(c).any { it.second == url }
    fun add(c: Context, title: String, url: String) { if (!has(c, url)) save(c, all(c) + Pair(title.ifBlank { url }, url)) }
    fun remove(c: Context, url: String) = save(c, all(c).filter { it.second != url })
}

/** ব্রাউজিং ইতিহাস (সবচেয়ে নতুনটা আগে, সর্বোচ্চ ৩০০টি); ইনকগনিটো ট্যাবের কিছু এখানে আসে না */
object History {
    private fun sp(c: Context) = c.getSharedPreferences("browse", Context.MODE_PRIVATE)
    private const val MAX = 300

    fun all(c: Context): List<Triple<String, String, Long>> = try {
        val a = JSONArray(sp(c).getString("hist", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Triple(o.optString("t"), o.getString("u"), o.optLong("w")) }
    } catch (_: Exception) { emptyList() }

    fun add(c: Context, title: String, url: String) {
        val l = all(c).toMutableList()
        if (l.firstOrNull()?.second == url) l.removeAt(0)      // পরপর একই পেজ বারবার জমে না
        l.add(0, Triple(title.ifBlank { url }, url, System.currentTimeMillis()))
        val a = JSONArray()
        l.take(MAX).forEach { a.put(JSONObject().put("t", it.first).put("u", it.second).put("w", it.third)) }
        sp(c).edit().putString("hist", a.toString()).apply()
    }

    fun clear(c: Context) { sp(c).edit().putString("hist", "[]").apply() }
}

object Browse {
    /** ব্যাকগ্রাউন্ডে গেলে সাইট যেন "পেজ লুকানো" ধরে স্ট্রিম/পোলিং না থামায় */
    const val KEEP_JS = "(function(){try{if(window.__tbKeep)return;window.__tbKeep=1;" +
        "var d=document,P=Object.getPrototypeOf(d);" +
        "function def(k,v){try{Object.defineProperty(d,k,{get:function(){return v},configurable:true})}catch(e){}}" +
        "def('hidden',false);def('webkitHidden',false);def('visibilityState','visible');def('webkitVisibilityState','visible');" +
        "}catch(e){}})();"


    /** অডিও প্লেয়ারের ডানে একটা শেয়ার আইকন বসায় (প্লেয়ারের নিজের মেনুতে হাত দেয় না); blob/data হলে ফাইল, নইলে লিংক শেয়ার */
    const val SHARE_JS = "(function(){try{if(window.__tbShare)return;window.__tbShare=1;" +
        "var IC='<svg viewBox=\"0 0 24 24\" width=\"22\" height=\"22\" fill=\"currentColor\"><path d=\"M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z\"/></svg>';" +
        "function send(o){try{prompt('\\u0001rcshare',JSON.stringify(o))}catch(e){}}" +
        "function share(a){var u=a.currentSrc||a.src||'';if(!u){var s=a.querySelector('source');u=s?s.src:''}if(!u)return;" +
        "if(/^(blob|data):/.test(u)){fetch(u).then(function(r){return r.blob()}).then(function(b){var f=new FileReader();" +
        "f.onload=function(){var d=String(f.result||'');send({mime:b.type||'audio/mpeg',b64:d.substring(d.indexOf(',')+1)})};f.readAsDataURL(b)}).catch(function(){})}" +
        "else send({url:u})}" +
        "function add(a){if(a.__tbSh)return;a.__tbSh=1;var b=document.createElement('button');b.type='button';b.setAttribute('aria-label','শেয়ার');b.innerHTML=IC;" +
        "b.style.cssText='display:inline-flex;align-items:center;justify-content:center;width:40px;height:40px;margin-left:6px;padding:0;border:0;border-radius:20px;background:#eee;color:#333;vertical-align:middle;cursor:pointer;flex:none';" +
        "b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();share(a)});" +
        "try{if(a.style.width==='100%'||a.offsetWidth>=a.parentNode.clientWidth-4)a.style.maxWidth='calc(100% - 50px)'}catch(e){}" +
        "a.insertAdjacentElement('afterend',b)}" +
        "function scan(){var l=document.querySelectorAll('audio[controls]');for(var i=0;i<l.length;i++)add(l[i])}" +
        "scan();new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true})" +
        "}catch(e){}})();"

    const val ERR_TITLE = "পেজটি খোলা যায়নি"

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** সংযোগ/DNS/SSL ত্রুটিতে Chrome-এর মতো একটা সাধারণ ত্রুটির পাতা (আবার চেষ্টার লিংকসহ) */
    fun errorHtml(url: String, msg: String): String = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>$ERR_TITLE</title>
<style>body{font-family:system-ui,sans-serif;margin:0;padding:28px 22px;background:#fff;color:#374151}
h1{font-size:21px;margin:18px 0 8px;color:#111827}.u{color:#6b7280;font-size:13px;word-break:break-all}
.m{font-size:14px;margin:14px 0;color:#6b7280}a.b{display:inline-block;margin-top:10px;background:#2563eb;color:#fff;text-decoration:none;padding:11px 20px;border-radius:22px;font-size:14px}
ul{font-size:13.5px;line-height:1.7;color:#4b5563;padding-left:20px}</style></head><body>
<div style="font-size:46px">🌐</div><h1>$ERR_TITLE</h1><div class=u>${esc(url)}</div>
<div class=m>${esc(msg)}</div>
<ul><li>ইন্টারনেট সংযোগ (ডেটা/ওয়াইফাই) ঠিক আছে কিনা দেখুন</li><li>ঠিকানাটা ঠিকমতো লেখা আছে কিনা দেখুন</li></ul>
<a class=b href="${esc(url)}">আবার চেষ্টা করুন</a></body></html>"""

    /** ইনকগনিটো ট্যাবের শুরুর পাতা */
    fun incognitoHtml(): String = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>ইনকগনিটো</title>
<style>body{font-family:system-ui,sans-serif;margin:0;padding:34px 22px;background:#1f2937;color:#e5e7eb}h1{font-size:21px}li{margin:8px 0;font-size:14px;color:#d1d5db}</style></head>
<body><div style="font-size:46px">🕶</div><h1>ইনকগনিটো ট্যাব</h1>
<ul><li>এই ট্যাবের ইতিহাস সংরক্ষণ হয় না</li><li>কুকি ও লগইন আলাদা থাকে, ট্যাব বন্ধ করলে মুছে যায়</li><li>ডাউনলোড করা ফাইল ও বুকমার্ক থেকে যায়</li></ul></body></html>"""
}
