package com.rahcare.termuxbrowser

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** এজেন্ট-বাস (daily-ruqyah রিপোর agent-bus ব্রাঞ্চের bus.json) পড়া/লেখা — হাব-পেজ ও bus.sh-এর সাথে একই ফরম্যাট */
object AgentBus {
    const val REPO = "alamgirabdullahgh/daily-ruqyah"
    const val BRANCH = "agent-bus"
    private const val API = "https://api.github.com/repos/$REPO/contents/bus.json"

    class Snap(val sha: String, val d: JSONObject)

    private fun req(tok: String, method: String, url: String, body: String? = null): Pair<Int, String> {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.requestMethod = method; cn.connectTimeout = 12000; cn.readTimeout = 15000
        cn.setRequestProperty("Authorization", "Bearer $tok")
        cn.setRequestProperty("Accept", "application/vnd.github+json")
        if (body != null) {
            cn.doOutput = true; cn.setRequestProperty("Content-Type", "application/json")
            cn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = cn.responseCode
        val txt = (if (code in 200..299) cn.inputStream else cn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        cn.disconnect()
        return code to txt
    }

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun now(): String = fmt().format(Date())

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। না পেলে/ত্রুটিতে null */
    fun load(tok: String): Snap? = try {
        val (c, t) = req(tok, "GET", "$API?ref=$BRANCH&t=${System.currentTimeMillis()}")
        if (c != 200) null else {
            val j = JSONObject(t)
            val raw = String(Base64.decode(j.getString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
            Snap(j.getString("sha"), JSONObject(raw))
        }
    } catch (_: Exception) { null }

    /** bus.json-এ বদলান; f true দিলে সংরক্ষণ হয়। অন্য কেউ একই সময়ে লিখলে ৪ বার আবার চেষ্টা */
    fun mutate(tok: String, msg: String, f: (JSONObject) -> Boolean): Boolean {
        for (i in 0 until 4) {
            val s = load(tok) ?: return false
            if (!f(s.d)) return true
            try {
                val body = JSONObject().put("message", "bus: $msg").put("branch", BRANCH).put("sha", s.sha)
                    .put("content", Base64.encodeToString(s.d.toString(1).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                val (c, _) = req(tok, "PUT", API, body.toString())
                if (c == 200 || c == 201) return true
            } catch (_: Exception) {}
            try { Thread.sleep(700) } catch (_: Exception) {}
        }
        return false
    }

    fun ev(d: JSONObject, msg: String) {
        val a = d.optJSONArray("events") ?: org.json.JSONArray().also { d.put("events", it) }
        a.put(JSONObject().put("t", now()).put("msg", msg))
        while (a.length() > 60) a.remove(0)
    }

    /** সারির প্রথম কাজ (status = queued) */
    fun queued(d: JSONObject): JSONObject? {
        val a = d.optJSONArray("tasks") ?: return null
        for (i in 0 until a.length()) { val t = a.getJSONObject(i); if (t.optString("status") == "queued") return t }
        return null
    }

    /** এজেন্টের অবস্থা limit এবং সেটা ৩ ঘণ্টার মধ্যের হলে true */
    fun inLimit(d: JSONObject, agent: String): Boolean {
        val a = d.optJSONObject("agents")?.optJSONObject(agent) ?: return false
        if (a.optString("state") != "limit") return false
        return try { System.currentTimeMillis() - fmt().parse(a.optString("seen"))!!.time < 3 * 3600 * 1000L } catch (_: Exception) { true }
    }

    /** ওয়ার্কস্পেস কোন এজেন্ট: ঠিকানা বা নামে claude / gemini / grok থাকলে সেটা, নইলে null */
    fun agentOf(s: Site): String? {
        val u = s.url.lowercase(); val n = s.name.lowercase()
        return when {
            "claude" in u || "claude" in n -> "claude"
            "gemini" in u || "gemini" in n -> "gemini"
            "grok" in u || "grok" in n -> "grok"
            else -> null
        }
    }

    /** এই এজেন্টের চলমান কাজ সারিতে ফেরায় (লিমিট শেষ) */
    fun limit(tok: String, agent: String, note: String) {
        mutate(tok, "limit") { d ->
            val ts = d.optJSONArray("tasks")
            var hit = 0
            if (ts != null) for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i)
                if (t.optString("status") == "running" && t.optString("agent") == agent) {
                    t.put("status", "queued").put("note", (note.ifBlank { "লিমিট শেষ" }) + " — wip থেকে চালিয়ে যাও").put("updated", now())
                    ev(d, "$agent-এর লিমিট শেষ → কাজ #${t.optInt("id")} সারিতে ফিরল"); hit++
                }
            }
            val ag = d.optJSONObject("agents") ?: JSONObject().also { d.put("agents", it) }
            val a = ag.optJSONObject(agent) ?: JSONObject().also { ag.put(agent, it) }
            a.put("state", "limit").put("task", 0).put("seen", now())
            true
        }
    }

    /** এজেন্টের চ্যাটে বসানোর নির্দেশ। টোকেন চ্যাটের লেখায় যায় (ক্লিপবোর্ডে নয়) */
    fun prompt(agent: String, id: Int, text: String, note: String, progress: Int, tok: String): String {
        val dir = REPO.substringAfter('/')
        return buildString {
            append("[এজেন্ট-বাস] তুমি $agent। কাজের তালিকা থেকে নিচের কাজটা ধরে সম্পূর্ণ করো।\n\n")
            append("১) টোকেন (গোপন: কোনো ফাইল, কমিট, লগ বা উত্তরে লিখবে না, রিপোর remote URL-এও রাখবে না): export GH_TOKEN=$tok\n")
            append("২) git clone --depth 1 https://x-access-token:\$GH_TOKEN@github.com/$REPO.git && cd $dir && git remote set-url origin https://github.com/$REPO.git\n")
            append("৩) নিয়ম পড়ো: tools/treat-marking/HANDOFF.md\n")
            append("৪) কাজ ধরো: bash tools/agents/bus.sh next $agent\n")
            append("৫) কাজের মাঝে অগ্রগতি: bash tools/agents/bus.sh progress $id <০-১০০> \"নোট\"\n")
            append("৬) শেষ হলে: bash tools/agents/bus.sh done $id \"নোট\"\n")
            append("৭) তোমার লিমিট শেষ হলে আগে wip পুশ করো, তারপর: bash tools/agents/bus.sh limit $agent \"কতটুকু হয়েছে\"\n")
            append("কাজ শেষে আমাকে জানিয়ো যেন আমি টোকেনটা বাতিল করি।\n\n")
            append("কাজ #$id (অগ্রগতি $progress%): $text")
            if (note.isNotBlank()) append("\nআগের নোট: $note")
        }
    }
}
