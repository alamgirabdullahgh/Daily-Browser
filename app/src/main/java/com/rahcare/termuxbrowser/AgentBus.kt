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

    /** মিডিয়া এজেন্টের ক্যাটাগরি (অডিও/ইমেজ/ভিডিও): এগুলো আলাদা এজেন্ট, কোডিং/বাস টাস্ক পায় না; শুধু নিজের ক্যাটাগরির টাস্ক পায় */
    val MEDIA = listOf("audio", "image", "video")
    /** সাইটের হাতে-বেছে-নেওয়া ক্যাটাগরি ("" = স্বয়ংক্রিয়, audio/image/video, none = এজেন্ট নয়); MainActivity বসায় */
    @Volatile var catOverride: (String) -> String? = { null }
    fun isMedia(a: String?) = a != null && a in MEDIA
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private val MEDIA_RX = linkedMapOf(
        "audio" to Regex("audio|অডিও|সাউন্ড|\\btts\\b|voice|ভয়েস|speech|music|suno|eleven|podcast|aistudio|ai-studio|notebooklm|lyria", OPT),
        "image" to Regex("image|imagen|ইমেজ|ছবি|midjourney|\\bflux\\b|dall-?e|ideogram|leonardo|banana|whisk", OPT),
        "video" to Regex("video|ভিডিও|\\bvids?\\b|\\bveo\\b|\\bflow\\b|\\bsora\\b|runway|kling|\\bluma\\b|\\bpika\\b|hailuo", OPT)
    )

    /** সাইট কোন এজেন্ট/ক্যাটাগরি: হাতে বাছা থাকলে সেটা, নইলে নাম/হোস্ট থেকে অডিও/ইমেজ/ভিডিও, তারপর claude / gemini / grok; না মিললে null */
    fun agentOf(s: Site): String? {
        val ov = try { catOverride(s.id) } catch (_: Exception) { null }
        if (!ov.isNullOrEmpty()) return if (ov == "none") null else ov
        // আগে শুধু নাম দেখে মিলানো হয় (ব্যবহারকারীর নিজের দেওয়া নামই আসল কথা বলে)।
        // একই হোস্ট (যেমন aistudio.google.com) অডিও আর ইমেজ দুই কাজেই ব্যবহার হতে পারে, তাই শুধু হোস্ট দেখে
        // আগে ধরে ফেললে নামে স্পষ্ট "ছবি"/"ইমেজ" লেখা থাকলেও ভুলভাবে অডিও ধরা পড়ত — তাই নাম সবার আগে।
        val name = s.name.lowercase()
        for ((k, r) in MEDIA_RX) if (r.containsMatchIn(name)) return k
        val (host, path) = try { val u = android.net.Uri.parse(s.url); (u.host ?: "") to (u.path ?: "") } catch (_: Exception) { "" to "" }
        // নামে না মিললে পাথ দেখে (যেমন docs.google.com/videos = Google Vids, aistudio.google.com/generate-image = ইমেজ,
        // labs.google/fx/tools/flow = ভিডিও)। পাথ হোস্টের চেয়ে বেশি নির্দিষ্ট বলে হোস্টের আগে দেখা হয় —
        // নইলে aistudio.google.com-এর মতো হোস্ট (অডিও ও ইমেজ দুটোতেই ব্যবহার হয়) পাথে স্পষ্ট "image" থাকলেও
        // নিজের নামের মধ্যে থাকা "aistudio"-এর কারণে সবসময় অডিও হয়ে যেত।
        val namePath = (name + " " + path).lowercase()
        for ((k, r) in MEDIA_RX) if (r.containsMatchIn(namePath)) return k
        //  শেষ ভরসা: শুধু হোস্ট (পাথে কিছু না থাকলে, যেমন খালি aistudio.google.com)
        val hostOnly = host.lowercase()
        for ((k, r) in MEDIA_RX) if (r.containsMatchIn(hostOnly)) return k
        val u = s.url.lowercase(); val n = name
        return when {
            "claude" in u || "claude" in n -> "claude"
            "gemini" in u || "gemini" in n -> "gemini"
            "grok" in u || "grok" in n -> "grok"
            else -> null
        }
    }

    /** "text" (সাধারণ/কোডিং এজেন্ট ও অশ্রেণিভুক্ত) অথবা media ক্যাটাগরির নাম */
    fun kindOf(s: Site): String = agentOf(s)?.takeIf { isMedia(it) } ?: "text"
    /** গ্রুপ: কোডিং = "text" (Claude/Grok), চ্যাট = "chat" (Gemini: স্ক্রিপ্ট/লেখা), আর মিডিয়া (audio/image/video) */
    fun isChat(a: String?) = a == "gemini"
    fun groupOf(a: String): String = if (isMedia(a)) a else if (isChat(a)) "chat" else "text"
    fun groupLabel(g: String): String = when (g) {
        "audio" -> "অডিও"; "image" -> "ইমেজ"; "video" -> "ভিডিও"; "chat" -> "চ্যাট"; else -> "কোডিং"
    }

    /** হোমপেজের বড় ক্যাটাগরি (ক্লিক করলে খোলে): কোডিং (Claude/Gemini/Grok একসাথে) / অডিও / ইমেজ / ভিডিও এজেন্ট */
    val CATEGORIES = listOf("text", "chat", "audio", "image", "video")
    fun categoryLabel(g: String): String = when (g) {
        "audio" -> "Audio Agent"; "image" -> "Image Agent"; "video" -> "Video Agent"; "chat" -> "Chat Agent"; else -> "Coding Agent"
    }

    /** দেখানোর ক্রম */
    val ORDER = listOf("claude", "gemini", "grok", "audio", "image", "video")

    fun label(a: String): String = when (a) {
        "claude" -> "Claude"; "gemini" -> "Gemini"; "grok" -> "Grok"
        "audio" -> "অডিও এজেন্ট"; "image" -> "ইমেজ এজেন্ট"; "video" -> "ভিডিও এজেন্ট"
        else -> a.replaceFirstChar { it.uppercase() }
    }

    /** হোমে থাকা এজেন্ট/ক্যাটাগরির তালিকা (টেক্সট এজেন্ট আগে, তারপর অডিও/ইমেজ/ভিডিও) */
    fun agentsIn(sites: List<Site>): List<String> {
        val have = sites.mapNotNull { agentOf(it) }.toSet()
        return ORDER.filter { it in have }
    }

    /** মিডিয়া এজেন্টের জন্য সরল প্রম্পট: শুধু কাজ ও নোট (টোকেন/রিপো/শেল কিছু নয়) */
    fun mediaPrompt(text: String, note: String): String = text + (if (note.isNotBlank()) "\n\nনোট: $note" else "")

    /** সারির সব কাজ (status = queued), পুরনো আগে */
    fun queuedAll(d: JSONObject): List<JSONObject> {
        val a = d.optJSONArray("tasks") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) { val t = a.getJSONObject(i); if (t.optString("status") == "queued") out.add(t) }
        return out
    }

    /** ওয়ার্কস্পেস-পাতায় এজেন্টের বর্তমান মডেল/সংস্করণের নাম খোঁজার স্ক্রিপ্ট (পাতার বোতামের লেখা থেকে; না পেলে খালি) */
    fun verJs(ag: String): String {
        if (isMedia(ag)) return "(function(){return ''})()"
        val re = when (ag) {
            "claude" -> """(Opus|Sonnet|Haiku|Fable|Mythos)\\s*[0-9][0-9.]*"""
            "gemini" -> """(Gemini\\s*)?[0-9][0-9.]*\\s*(Pro|Flash)|(Pro|Flash)\\s*[0-9][0-9.]*"""
            else -> """Grok\\s*[0-9][0-9.]*"""
        }
        return """(function(){try{var re=new RegExp('$re','i');var b=document.querySelectorAll('button,[role=button],[role=combobox]');for(var i=0;i<b.length;i++){var x=(b[i].innerText||'').replace(/\s+/g,' ').trim();if(x.length>0&&x.length<60){var m=x.match(re);if(m)return m[0]}}return ''}catch(e){return ''}})()"""
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
