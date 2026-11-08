package com.rahcare.termuxbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI সহকারী (Gemini API): হাব-চ্যাটের কথা বুঝে ঠিক করে ব্রাউজারকে কী করতে হবে।
 * - AI শুধু সিদ্ধান্ত দেয়; কাজ করে ব্রাউজার, কেবল নিচের সীমিত তালিকার (ওয়ার্কস্পেস বানানো/খোলা/প্রশ্ন করা)।
 * - পাসওয়ার্ড কখনো AI-কে যায় না: পাঠানোর আগে লেখা থেকে মুছে দেওয়া হয়। ব্রাউজার নিজে পাসওয়ার্ড আলাদা রেখে (Keystore) লগইনের সময় বসায়।
 * - API কী Keystore-এ এনক্রিপ্টেড। কী না থাকলে/AI ব্যর্থ হলে আগের মতোই চলে।
 */
object AiRouter {
    const val KEY = "__gemini_key__"
    @Volatile var lastErr = ""

    class Result(val type: String, val items: List<AutoLogin.Item>, val names: List<String>, val question: String, val answer: String = "")

    /** হাতে দেওয়া মডেল (খালি = স্বয়ংক্রিয়) */
    private fun manualModel(c: Context) = (Store.prefs(c).getString("ai_model", "") ?: "").trim()
    /** সর্বশেষ যে মডেল সফল হয়েছে */
    fun lastModel(c: Context) = Store.prefs(c).getString("ai_model_ok", "") ?: ""

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"

    /** Google-এর তালিকা থেকে generateContent-সক্ষম মডেলের নাম (ব্যর্থ হলে খালি) */
    private fun fetchModels(key: String): List<String> {
        return try {
            val cn = URL(BASE + "models?pageSize=200").openConnection() as HttpURLConnection
            cn.connectTimeout = 10000; cn.readTimeout = 15000
            cn.setRequestProperty("x-goog-api-key", key)
            if (cn.responseCode != 200) { cn.disconnect(); return emptyList() }
            val r = cn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            cn.disconnect()
            val arr = JSONObject(r).optJSONArray("models") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val methods = m.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
                val ok = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (ok) m.optString("name").removePrefix("models/") else null
            }
        } catch (e: Exception) { emptyList() }
    }

    private val BAD = listOf("tts", "image", "embed", "live", "audio", "native", "robotics", "computer", "thinking", "exp", "learnlm", "vision", "aqa", "customtools", "deep-research")

    /** সেরা থেকে কম ভালো: স্থিতিশীল (preview নয়) আগে, তারপর flash > flash-lite > pro, তারপর নতুন সংস্করণ আগে */
    private fun rank(all: List<String>): List<String> {
        val re = Regex("^gemini-(\\d+(?:\\.\\d+)?)-(flash-lite|flash|pro)(.*)$")
        data class M(val name: String, val ver: Double, val fam: Int, val stable: Boolean)
        return all.mapNotNull { n ->
            if (BAD.any { n.contains(it) }) return@mapNotNull null
            val m = re.find(n) ?: return@mapNotNull null
            val rest = m.groupValues[3]
            val stable = !rest.contains("preview") && !Regex("-\\d{2,}").containsMatchIn(rest)
            M(n, m.groupValues[1].toDouble(), when (m.groupValues[2]) { "flash" -> 3; "flash-lite" -> 2; else -> 1 }, stable)
        }.sortedWith(compareByDescending<M> { it.stable }.thenByDescending { it.fam }.thenByDescending { it.ver }.thenBy { it.name.length })
            .map { it.name }
    }

    /** চেষ্টার ক্রম: হাতে দেওয়া → সর্বশেষ সফল → তালিকা থেকে সেরাগুলো → স্থির বিকল্প */
    private fun candidates(c: Context, key: String): List<String> {
        val p = Store.prefs(c)
        val now = System.currentTimeMillis()
        var cached = p.getString("ai_models_cache", "") ?: ""
        if (cached.isBlank() || now - p.getLong("ai_models_at", 0) > 12L * 3600 * 1000) {
            val l = fetchModels(key)
            if (l.isNotEmpty()) { cached = l.joinToString(","); p.edit().putString("ai_models_cache", cached).putLong("ai_models_at", now).apply() }
        }
        val out = LinkedHashSet<String>()
        lastModel(c).let { if (it.isNotBlank()) out.add(it) }
        out.addAll(rank(cached.split(",").filter { it.isNotBlank() }).take(12))
        out.add("gemini-flash-latest"); out.add("gemini-2.5-flash")
        return out.toList()
    }

    fun hasKey(c: Context) = Creds.has(c, KEY)

    /** সীমা (429) শেষ হওয়া মডেল কিছুক্ষণ বাদ — অন্য মডেলে সরাসরি চলে যায় */
    private fun coolLeft(c: Context, m: String): Long = (Store.prefs(c).getLong("cool_" + m, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    private fun setCool(c: Context, m: String, body: String) {
        val perDay = body.contains("PerDay", true) || body.contains("per day", true)
        val rd = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
        val ms = if (perDay) 3L * 3600_000 else ((rd ?: 55L) + 5L) * 1000
        Store.prefs(c).edit().putLong("cool_" + m, System.currentTimeMillis() + ms).apply()
    }

    private fun prompt(c: Context): String {
        val have = Sites.all(c).joinToString(", ") { "\"" + it.name.replace("\"", "") + "\" (" + (try { URL(if (it.url.contains("://")) it.url else "https://" + it.url).host } catch (e: Exception) { "" }) + ")" }
        return "You are the command interpreter of an Android browser app. The app has 'workspaces': each workspace is one website with its own separate, persistent login. " +
            "The user writes Bengali or English. Decide what they want and answer ONLY with JSON: " +
            "{\"type\":\"create_workspaces|login_workspaces|open_workspaces|clarify|answer|pass_to_agent\",\"items\":[{\"name\":\"\",\"url\":\"\",\"email\":\"\"}],\"names\":[\"\"],\"question\":\"\",\"answer\":\"\"}. " +
            "Rules: login_workspaces = the user wants to log in inside workspaces that ALREADY EXIST (e.g. says they were already created, or asks to log in to each/all workspaces) using an email: produce items [{name,url,email}] where name is an existing workspace name copied exactly from this list: ["+have+"]; use name \"*\" with the email when the user means ALL existing workspaces; if different emails go to different workspaces make one item per workspace; never create new workspaces for this. NEVER output any password. "+
            "create_workspaces = user wants NEW workspaces (and optionally to log in to them); produce one item per workspace (if a number like 12 is given, produce that many items with numbered names); " +
            "url must be the real site host (gemini->gemini.google.com, chatgpt->chatgpt.com, claude->claude.ai, grok->grok.com, perplexity->perplexity.ai, deepseek->chat.deepseek.com, google ai studio->aistudio.google.com). If the user gives a full link, or names a specific page or tool of a site, the url MUST be that exact page WITH its path, never just the host (e.g. Google AI Studio text-to-speech / speech generation / TTS / গুগল এআই স্টুডিওর টেক্সট টু স্পিচ -> aistudio.google.com/generate-speech; the plain host aistudio.google.com is only for a general AI Studio request). " +
            "email only if the user wrote one, never invent; NEVER output any password. " +
            "open_workspaces = open already existing workspaces; names must come from this list: [" + have + "]. " +
            "clarify = something essential is missing; put one short Bengali question in 'question'. " +
            "answer = the user asks a question, chats, wants an explanation, advice, translation, summary, writing, ideas or any help you can give yourself in text: write the full, helpful reply in 'answer' in the SAME language the user wrote (Bengali stays Bengali); plain text, no markdown tables. " +
            "pass_to_agent = ONLY when the user explicitly wants a named AI agent/website to do it (Claude, ChatGPT, Grok, Perplexity, DeepSeek...), or needs real actions inside a website, or file/code work in their repository; when unsure between answer and pass_to_agent choose answer. Output JSON only."
    }

    /** Gemini-কে একটি অনুরোধ পাঠায় (মডেল স্বয়ংক্রিয়, ব্যর্থ হলে পরের মডেলে)। সফল হলে পুরো JSON উত্তর, নইলে null (lastErr-এ কারণ) */
    fun call(c: Context, body: JSONObject): JSONObject? {
        val key = Creds.get(c, KEY)
        if (key.isBlank()) { lastErr = "কী নেই"; return null }
            val deadline = System.currentTimeMillis() + 150000
            var resp: String? = null
            var tried = 0
            val retried = HashSet<String>()
            var stopAll = false
            var cooled = 0
            val cl = candidates(c, key)
            for (mdl in cl) {
                if (coolLeft(c, mdl) > 0) { cooled++; continue }
                var again = true
                while (again) {
                again = false
                if (tried >= 14 || System.currentTimeMillis() > deadline) break
                tried++
                try {
                    val cn = URL(BASE + "models/" + mdl + ":generateContent").openConnection() as HttpURLConnection
                    cn.requestMethod = "POST"; cn.connectTimeout = 15000; cn.readTimeout = 60000; cn.doOutput = true
                    cn.setRequestProperty("Content-Type", "application/json"); cn.setRequestProperty("x-goog-api-key", key)
                    cn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = cn.responseCode
                    if (code == 200) {
                        val r = cn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        cn.disconnect()
                        // খালি/ব্লক হওয়া উত্তর হলে পরের মডেলে
                        if (JSONObject(r).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content") == null) { lastErr = "$mdl: খালি উত্তর"; continue }
                        resp = r
                        Store.prefs(c).edit().putString("ai_model_ok", mdl).apply()
                        break
                    }
                    val eb = try { cn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                    cn.disconnect()
                    lastErr = "$mdl: HTTP $code"
                    if (code == 429) setCool(c, mdl, eb)
                    if (code == 401 || code == 403) { stopAll = true; break }            // কী/অনুমতির সমস্যা: মডেল বদলে লাভ নেই
                    if (code == 404) Store.prefs(c).edit().remove("ai_models_cache").remove("ai_model_ok").apply() // তালিকা পুরোনো
                    // 400/404/429/5xx: পরের মডেল চেষ্টা
                } catch (e: Exception) {
                    lastErr = mdl + ": " + e.javaClass.simpleName + (e.message?.let { " (" + it.take(25) + ")" } ?: "")
                    // ধীর নেটে টাইমআউট: মডেল বদলানোর আগে একই মডেলে একবার আবার
                    if (e is java.net.SocketTimeoutException && retried.add(mdl) && System.currentTimeMillis() < deadline) { tried--; again = true }
                }
                if (resp != null || stopAll) break
                }
                if (resp != null || stopAll || tried >= 14 || System.currentTimeMillis() > deadline) break
            }
        if (resp == null && !stopAll) {
            val left = cl.map { coolLeft(c, it) }.filter { it > 0 }.minOrNull()
            if (left != null && (cooled > 0 || lastErr.contains("429"))) lastErr = "সব Gemini মডেলের বিনামূল্যের সীমা শেষ — প্রায় " + (left / 60000 + 1) + " মিনিট পরে আবার চলবে"
        }
        return resp?.let { JSONObject(it) }
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। ব্যর্থ হলে null (lastErr-এ কারণ) */
    fun ask(c: Context, text: String): Result? {
        try {
            val key = Creds.get(c, KEY)
            if (key.isBlank()) return null
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(c)))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text.take(4000))))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3).put("maxOutputTokens", 8192))
            val resp = call(c, body)?.toString() ?: return null
            var out = JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
            out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val j = JSONObject(out)
            val its = j.optJSONArray("items") ?: JSONArray()
            val items = (0 until its.length()).mapNotNull { k ->
                val e = its.optJSONObject(k) ?: return@mapNotNull null
                val u = e.optString("url"); if (u.isBlank() && e.optString("name").isBlank()) null else AutoLogin.Item(e.optString("name"), u, e.optString("email"))
            }.take(100)
            val ns = j.optJSONArray("names") ?: JSONArray()
            val names = (0 until ns.length()).map { ns.optString(it) }.filter { it.isNotBlank() }
            lastErr = ""
            return Result(j.optString("type", "pass_to_agent"), items, names, j.optString("question"), j.optString("answer").trim())
        } catch (e: Exception) {
            lastErr = e.javaClass.simpleName + ": " + (e.message ?: "").take(60)
            return null
        }
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** মেনু: Gemini API কী (ও মডেল) সেট/মোছা */
    fun dialog(a: Activity) {
        val l = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0) }
        l.addView(TextView(a).apply {
            textSize = 12f
            text = (if (hasKey(a)) "✔ কী সংরক্ষিত আছে।\n" else "এখনো কী নেই।\n") +
                (if (lastModel(a).isNotBlank()) "চলছে: " + lastModel(a) + " (মডেল নিজে বাছাই হয়; ব্যর্থ হলে পরেরটায় যায়)\n" else "মডেল নিজে বাছাই হবে; ব্যর্থ হলে পরেরটায় যাবে।\n") +
                "Google AI Studio (aistudio.google.com) থেকে বিনামূল্যে API কী নিন। কী ফোনে এনক্রিপ্টেড থাকে। হাব-চ্যাটের লেখা (পাসওয়ার্ড বাদে) Google-এর Gemini-তে যায়।\nখালি রেখে সেভ করলে কী মুছে যাবে।"
        })
        val k = EditText(a).apply { hint = "Gemini API কী"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        l.addView(k)
        AlertDialog.Builder(a).setTitle("AI সহকারী (Gemini)").setView(l)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                Store.prefs(a).edit().remove("ai_model").apply()
                val key = k.text.toString().trim()
                if (key.isEmpty()) { if (hasKey(a)) { Creds.remove(a, KEY); Toast.makeText(a, "কী মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() } }
                else { Creds.put(a, KEY, key); Toast.makeText(a, "সংরক্ষিত — এখন থেকে হাবের কথা AI বুঝবে", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("বন্ধ", null).show()
    }
}
