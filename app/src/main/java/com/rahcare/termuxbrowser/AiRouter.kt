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

    class Result(val type: String, val items: List<AutoLogin.Item>, val names: List<String>, val question: String)

    private fun model(c: Context) = Store.prefs(c).getString("ai_model", "gemini-2.5-flash")?.ifBlank { "gemini-2.5-flash" } ?: "gemini-2.5-flash"
    fun hasKey(c: Context) = Creds.has(c, KEY)

    private fun prompt(c: Context): String {
        val have = Sites.all(c).joinToString(", ") { "\"" + it.name.replace("\"", "") + "\"" }
        return "You are the command interpreter of an Android browser app. The app has 'workspaces': each workspace is one website with its own separate, persistent login. " +
            "The user writes Bengali or English. Decide what they want and answer ONLY with JSON: " +
            "{\"type\":\"create_workspaces|open_workspaces|clarify|pass_to_agent\",\"items\":[{\"name\":\"\",\"url\":\"\",\"email\":\"\"}],\"names\":[\"\"],\"question\":\"\"}. " +
            "Rules: create_workspaces = user wants new workspaces and/or to log in to sites with an email; produce one item per workspace (if a number like 12 is given, produce that many items with numbered names); " +
            "url must be the real site host (gemini->gemini.google.com, chatgpt->chatgpt.com, claude->claude.ai, grok->grok.com, perplexity->perplexity.ai, deepseek->chat.deepseek.com); " +
            "email only if the user wrote one, never invent; NEVER output any password. " +
            "open_workspaces = open already existing workspaces; names must come from this list: [" + have + "]. " +
            "clarify = something essential is missing; put one short Bengali question in 'question'. " +
            "pass_to_agent = everything else (writing, coding, questions, work for AI agents); use it whenever unsure. Output JSON only."
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। ব্যর্থ হলে null (lastErr-এ কারণ) */
    fun ask(c: Context, text: String): Result? {
        try {
            val key = Creds.get(c, KEY)
            if (key.isBlank()) return null
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(c)))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text.take(4000))))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0))
            val cn = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model(c) + ":generateContent").openConnection() as HttpURLConnection
            cn.requestMethod = "POST"; cn.connectTimeout = 15000; cn.readTimeout = 30000; cn.doOutput = true
            cn.setRequestProperty("Content-Type", "application/json"); cn.setRequestProperty("x-goog-api-key", key)
            cn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = cn.responseCode
            if (code != 200) { lastErr = "HTTP $code"; cn.disconnect(); return null }
            val resp = cn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            cn.disconnect()
            var out = JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
            out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val j = JSONObject(out)
            val its = j.optJSONArray("items") ?: JSONArray()
            val items = (0 until its.length()).mapNotNull { k ->
                val e = its.optJSONObject(k) ?: return@mapNotNull null
                val u = e.optString("url"); if (u.isBlank()) null else AutoLogin.Item(e.optString("name"), u, e.optString("email"))
            }.take(100)
            val ns = j.optJSONArray("names") ?: JSONArray()
            val names = (0 until ns.length()).map { ns.optString(it) }.filter { it.isNotBlank() }
            lastErr = ""
            return Result(j.optString("type", "pass_to_agent"), items, names, j.optString("question"))
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
                "Google AI Studio (aistudio.google.com) থেকে বিনামূল্যে API কী নিন। কী ফোনে এনক্রিপ্টেড থাকে। হাব-চ্যাটের লেখা (পাসওয়ার্ড বাদে) Google-এর Gemini-তে যায়।\nখালি রেখে সেভ করলে কী মুছে যাবে।"
        })
        val k = EditText(a).apply { hint = "Gemini API কী"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val m = EditText(a).apply { hint = "মডেল (ডিফল্ট gemini-2.5-flash)"; setText(Store.prefs(a).getString("ai_model", "") ?: ""); inputType = InputType.TYPE_CLASS_TEXT }
        l.addView(k); l.addView(m)
        AlertDialog.Builder(a).setTitle("AI সহকারী (Gemini)").setView(l)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                Store.prefs(a).edit().putString("ai_model", m.text.toString().trim()).apply()
                val key = k.text.toString().trim()
                if (key.isEmpty()) { if (hasKey(a)) { Creds.remove(a, KEY); Toast.makeText(a, "কী মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() } }
                else { Creds.put(a, KEY, key); Toast.makeText(a, "সংরক্ষিত — এখন থেকে হাবের কথা AI বুঝবে", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("বন্ধ", null).show()
    }
}
