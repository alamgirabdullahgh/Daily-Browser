package com.rahcare.termuxbrowser

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * অটো-ক্লিকের নিয়ম রিপোর autoclick-rules.json ফাইল থেকে (APK বদলানো ছাড়াই)।
 * অ্যাপ ফাইলটা নামিয়ে ফোনে রাখে; না পেলে/ভুল হলে AutoClick.kt-র নিজস্ব নিয়ম চলে।
 * রিজার্ভ এজেন্ট শুধু এই ফাইল ঠিক করে পুশ করে — ফাইলের গঠন AGENT_NOTES.md-তে।
 */
object RemoteRules {
    private const val RAW = "https://raw.githubusercontent.com/alamgirabdullahgh/Daily-Browser/main/autoclick-rules.json"
    @Volatile private var profiles: JSONObject = JSONObject()
    @Volatile private var hosts: JSONObject = JSONObject()
    @Volatile var version: Int = 0
        private set
    @Volatile private var lastTry = 0L

    private fun file(c: Context) = File(c.filesDir, "autoclick-rules.json")

    private fun parse(text: String) {
        val o = JSONObject(text)
        profiles = o.optJSONObject("profiles") ?: JSONObject()
        hosts = o.optJSONObject("hosts") ?: JSONObject()
        version = o.optInt("version", 0)
    }

    /** ফোনে আগে নামানো ফাইল পড়ে (অ্যাপ চালুর সময়) */
    fun load(c: Context) { try { val f = file(c); if (f.exists()) parse(f.readText()) } catch (_: Exception) {} }

    /** রিপো থেকে নতুন ফাইল আনে (১০ মিনিটে একবারের বেশি নয়); ভুল ফাইল হলে আগেরটাই থাকে */
    fun refresh(c: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < 10 * 60 * 1000L) return
        lastTry = now
        val app = c.applicationContext
        Thread {
            try {
                val con = URL("$RAW?t=$now").openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 8000
                if (con.responseCode == 200) {
                    val text = con.inputStream.bufferedReader().use { it.readText() }
                    if (text.length < 200000) { parse(text); file(app).writeText(text) }
                }
                con.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun strs(o: JSONObject, k: String, d: List<String>): List<String> {
        val a = o.optJSONArray(k) ?: return d
        val l = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        return if (l.isEmpty()) d else l
    }

    /** রেজেক্স ঠিক না হলে আগেরটাই রাখে (ভুল নিয়ম যাতে অটো-ক্লিক না ভাঙে) */
    private fun rx(s: String, d: String): String = if (s.isBlank()) d else try { Regex(s); s } catch (_: Exception) { d }

    /** প্রোফাইলের উপর রিপোর নিয়ম বসায় (যে ঘরগুলো ফাইলে আছে শুধু সেগুলো বদলায়) */
    fun apply(key: String, p: AutoClick.Profile): AutoClick.Profile {
        val o = profiles.optJSONObject(key) ?: return p
        val pre = if (o.has("prepRe")) { val s = o.optString("prepRe", ""); if (s.isEmpty()) "" else rx(s, p.prepRe) } else p.prepRe
        return AutoClick.Profile(
            key, strs(o, "fillSel", p.fillSel), o.optBoolean("largest", p.largest), strs(o, "sendSel", p.sendSel),
            rx(o.optString("sendRe", ""), p.sendRe), rx(o.optString("notRe", ""), p.notRe),
            o.optBoolean("modEnter", p.modEnter), pre
        )
    }

    /** হোস্ট → প্রোফাইলের নাম (অচেনা সাইটকে নিয়ম শিখিয়ে দেওয়া); না থাকলে null */
    fun agentForHost(host: String): String? {
        val h = host.lowercase().removePrefix("www.")
        return hosts.optString(h, "").ifBlank { null }
    }
}
