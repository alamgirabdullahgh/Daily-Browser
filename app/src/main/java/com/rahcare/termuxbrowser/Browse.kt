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
        "var stop=function(e){e.stopImmediatePropagation()};" +
        "d.addEventListener('visibilitychange',stop,true);d.addEventListener('webkitvisibilitychange',stop,true);" +
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
