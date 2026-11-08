package com.rahcare.termuxbrowser

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * হাব-চ্যাটের সম্পূর্ণ AI-এজেন্ট (Gemini function-calling)।
 * - এক চ্যাট-বক্সের কথা বুঝে Gemini নিজে টুল ডেকে ব্রাউজার চালায়: ওয়ার্কস্পেস দেখা/খোলা/লগইন/বানানো, প্রশ্নের উত্তর।
 * - পাসওয়ার্ড কখনো Gemini-কে যায় না: prepare() লেখা থেকে নিয়ে আগেই ফোনের Keystore-এ রাখে ও *** করে দেয়।
 *   লগইনের সময় ব্রাউজার নিজে ইমেইলের পাসওয়ার্ড বসায়।
 */
object AiAgent {
    class Out(val text: String, val handoff: Boolean)

    private val pool = Executors.newSingleThreadExecutor()
    fun submit(r: Runnable) { pool.execute(r) }

    private val RE_MAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    // একলাইনে "ইমেইল পাসওয়ার্ড" / "ইমেইল:পাসওয়ার্ড" / "ইমেইল | পাসওয়ার্ড"
    private val RE_LINE = Regex("^(\\s*(?:[-*\\d.)]+\\s*)?)(" + RE_MAIL.pattern + ")(\\s*(?:[:|,;]|\\s)\\s*)(\\S{3,})\\s*$")

    @Volatile private var handoff = false

    // ───────── পাসওয়ার্ড সরানো ─────────
    /** লেখা থেকে পাসওয়ার্ড নিয়ে Keystore-এ রাখে। ফেরত: (পাসওয়ার্ড-*** করা লেখা, এজেন্টের জন্য নোট) */
    fun prepare(c: Activity, raw: String): Pair<String, String> {
        val saved = linkedSetOf<String>()
        val rl = raw.lines()
        val lines = ArrayList<String>()
        var i = 0
        while (i < rl.size) {
            val ln = rl[i]
            val m = RE_LINE.find(ln)
            if (m != null && AutoLogin.stripPw(ln) == ln) {          // একই লাইনে: "ইমেইল পাসওয়ার্ড"
                val em = m.groupValues[2]
                if (Creds.put(c, em, m.groupValues[4])) saved.add(em)
                lines.add(m.groupValues[1] + em + m.groupValues[3] + "***"); i++; continue
            }
            val mm = RE_MAIL.find(ln)
            if (mm != null && ln.replace(mm.value, "").trim().trim(':', '-', '|', '=').isBlank()) {   // শুধু ইমেইলের লাইন → পরের লাইনে পাসওয়ার্ড
                var j = i + 1
                while (j < rl.size && rl[j].isBlank()) j++
                val nx = if (j < rl.size) rl[j].trim() else ""
                val labeled = nx.isNotEmpty() && AutoLogin.stripPw(nx) != nx
                val bare = nx.isNotEmpty() && nx.none { it.isWhitespace() } && !nx.contains('@') && nx.length in 4..80 && nx.none { it in '\u0980'..'\u09FF' }
                if (labeled || bare) {
                    val pw = if (labeled) AutoLogin.chatPw(nx) else nx
                    if (pw.isNotEmpty() && Creds.put(c, mm.value, pw)) saved.add(mm.value)
                    lines.add(ln); for (k in i + 1 until j) lines.add(rl[k]); lines.add("***"); i = j + 1; continue
                }
            }
            lines.add(ln); i++
        }
        val joined = lines.joinToString("\n")
        val pwm = AutoLogin.pwByEmail(raw)
        for ((em, pw) in pwm) if (Creds.put(c, em, pw)) saved.add(em)
        // সবার জন্য একটা পাসওয়ার্ড বলা থাকলে (লেবেল সহ, ইমেইল ছাড়া লাইনে)
        val gPw = AutoLogin.chatPw(raw)
        if (gPw.isNotEmpty()) for (em in RE_MAIL.findAll(raw).map { it.value }.distinct()) {
            if (pwm.keys.none { it.equals(em, true) } && em !in saved && Creds.put(c, em, gPw)) saved.add(em)
        }
        val safe = AutoLogin.stripPw(joined)
        val note = if (saved.isEmpty()) "" else "\n\n[অ্যাপের নোট: এই ইমেইলগুলোর পাসওয়ার্ড অ্যাপ নিরাপদে সংরক্ষণ করেছে: " + saved.joinToString(", ") + ". তুমি পাসওয়ার্ড দেখো না, চাইবেও না; login_workspaces নিজেই বসাবে।]"
        return safe to note
    }

    // ───────── Gemini-র নির্দেশ ও টুল ─────────
    private const val SYSTEM = "You are the built-in agent of the Android browser app 'Daily Browser'. The user talks to you in ONE chat box (Bengali or English) and you control the whole browser with your tools. " +
        "Concepts: a 'workspace' is a website (Gemini, ChatGPT, Claude, Grok...) with its own persistent login profile; workspaces usually ALREADY EXIST. Opening a workspace and logging in to it are two different actions. " +
        "RULES: " +
        "1) ACT, don't interrogate. Call list_workspaces first to see every workspace (number n, name, site, assigned email, open or not, and a login-state guess: login_page / logged_in / unknown). Never ask the user which site or which email when the message or the tools already tell you; the user does not need to name sites for existing workspaces. " +
        "2) Passwords: the app already saved the user's email/password pairs securely. You only ever see emails, never passwords; never ask for or repeat a password. " +
        "3) To log in existing workspaces call login_workspaces with assignments [{workspace: n or exact name, email}]. If the user gave several emails give each workspace a DIFFERENT email, in order; if only one email, use it for all chosen workspaces. When the user says the remaining / বাকিগুলো / not-logged-in ones, choose workspaces with state login_page first, then unknown ones (if you need more), never ones with state logged_in. When the user says all / সবগুলো / প্রত্যেকটা, include all. If there are more workspaces than emails, log in as many as there are emails and say how many are left. Not-open workspaces are fine: the app opens them itself. " +
        "4) After starting logins call wait(25) once or twice and list_workspaces again to verify, then report honestly (logged in, still on login page, needs the user e.g. 2-step verification / captcha). Page-state is only a guess; say so when unsure. " +
        "5) For normal questions, chat, explanations, writing, translation just answer yourself. " +
        "6) Use hand_to_coding_agent ONLY when the user clearly wants a coding agent (Claude Code etc.) to do coding/repository work. " +
        "7) Final answer: short, in the user's language (Bengali stays Bengali), say exactly what you did and what is left. Ask a question only if it is truly impossible to continue."

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.length() > 0) {
            val params = JSONObject().put("type", "object").put("properties", props)
            if (req.isNotEmpty()) params.put("required", JSONArray(req))
            o.put("parameters", params)
        }
        return o
    }

    private fun tools(): JSONArray {
        val ref = "workspace number n from list_workspaces (preferred) or exact name"
        val decl = JSONArray()
            .put(fn("list_workspaces", "List all workspaces with number, name, site, assigned email, whether open, and a login-state guess of open ones."))
            .put(fn("open_workspaces", "Open (switch to) existing workspaces one by one. Does not log in.",
                JSONObject().put("workspaces", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "$ref, one per item")), listOf("workspaces")))
            .put(fn("login_workspaces", "Log in existing workspaces: assigns the email to each workspace and opens/reloads it so the browser fills in the saved password automatically. Runs in the background, about 6 seconds per workspace.",
                JSONObject().put("assignments", JSONObject().put("type", "array").put("description", "one entry per workspace").put("items",
                    JSONObject().put("type", "object").put("properties", JSONObject().put("workspace", str(ref)).put("email", str("email address"))).put("required", JSONArray(listOf("workspace", "email"))))), listOf("assignments")))
            .put(fn("create_workspace", "Create a NEW workspace for a website and open it (only when the user wants a new one).",
                JSONObject().put("name", str("workspace name")).put("url", str("site host, e.g. gemini.google.com")).put("email", str("optional email")), listOf("name", "url")))
            .put(fn("saved_emails", "List emails whose passwords are saved in the app (passwords are never shown)."))
            .put(fn("wait", "Wait so that pages can load / logins can finish (max 25 seconds).", JSONObject().put("seconds", JSONObject().put("type", "integer").put("description", "1 to 25")), listOf("seconds")))
            .put(fn("hand_to_coding_agent", "Give the user's original request to the coding agent (Claude Code etc.) instead of doing it here."))
        return JSONArray().put(JSONObject().put("functionDeclarations", decl))
    }

    // ───────── UI থ্রেডে কাজ ─────────
    private fun inspect(act: Activity): Map<String, String> {
        val m = act as? MainActivity ?: return emptyMap()
        val latch = CountDownLatch(1)
        var res: Map<String, String> = emptyMap()
        act.runOnUiThread { try { m.agInspect { r -> res = r; latch.countDown() } } catch (e: Exception) { latch.countDown() } }
        latch.await(15, TimeUnit.SECONDS)
        return res
    }

    private fun stateOf(raw: String): Pair<String, String> {
        if (raw.isBlank()) return "unknown" to ""
        return try {
            val j = JSONObject(raw)
            val st = when { j.optBoolean("pw") -> "login_page"; j.optBoolean("out") -> "logged_in"; j.optBoolean("li") -> "login_page"; else -> "unknown" }
            st to j.optString("t").take(40)
        } catch (e: Exception) { "unknown" to "" }
    }

    private fun find(all: List<Site>, ref: String): Site? {
        val r = ref.trim()
        r.toIntOrNull()?.let { n -> return all.getOrNull(n - 1) }
        return all.firstOrNull { it.name.equals(r, true) } ?: all.firstOrNull { r.isNotEmpty() && it.name.contains(r, true) }
    }

    private fun exec(act: Activity, name: String, args: JSONObject): Any {
        val m = act as? MainActivity ?: return JSONObject().put("error", "ব্রাউজার প্রস্তুত নয়")
        val all = Sites.all(act)
        when (name) {
            "list_workspaces" -> {
                val st = inspect(act)
                val arr = JSONArray()
                all.forEachIndexed { i, s ->
                    val o = JSONObject().put("n", i + 1).put("name", s.name).put("site", AutoLogin.hostOf(s.url)).put("email", AutoLogin.emailOf(act, s.id)).put("open", st.containsKey(s.id))
                    if (st.containsKey(s.id)) { val p = stateOf(st[s.id] ?: ""); o.put("state", p.first); if (p.second.isNotEmpty()) o.put("title", p.second) }
                    arr.put(o)
                }
                return JSONObject().put("count", all.size).put("workspaces", arr)
            }
            "open_workspaces" -> {
                val refs = args.optJSONArray("workspaces") ?: JSONArray()
                val ids = mutableListOf<String>(); val bad = mutableListOf<String>()
                for (i in 0 until refs.length()) { val s = find(all, refs.optString(i)); if (s != null) ids.add(s.id) else bad.add(refs.optString(i)) }
                act.runOnUiThread { m.agOpenMany(ids.distinct(), false, 3000L) }
                return JSONObject().put("opening", ids.distinct().size).put("not_found", JSONArray(bad))
            }
            "login_workspaces" -> {
                val a = args.optJSONArray("assignments") ?: JSONArray()
                val ids = mutableListOf<String>(); val bad = mutableListOf<String>(); val noPw = linkedSetOf<String>()
                val done = mutableListOf<Pair<Site, String>>()
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    val s = find(all, e.optString("workspace")); val em = e.optString("email").trim()
                    if (s == null || em.isBlank()) { bad.add(e.optString("workspace")); continue }
                    AutoLogin.setEmail(act, s.id, em)
                    if (Creds.get(act, em).isEmpty()) noPw.add(em)
                    if (s.id !in ids) { ids.add(s.id); done.add(s to em) }
                }
                act.runOnUiThread { m.agOpenMany(ids, true, 6000L) }
                return JSONObject().put("started", ids.size).put("seconds_needed_approx", ids.size * 6 + 10)
                    .put("assigned", JSONArray(done.map { it.first.name + " → " + it.second }))
                    .put("workspace_not_found", JSONArray(bad)).put("emails_without_saved_password", JSONArray(noPw.toList()))
            }
            "create_workspace" -> {
                val made = AutoLogin.create(act, listOf(AutoLogin.Item(args.optString("name"), args.optString("url"), args.optString("email"))))
                act.runOnUiThread { m.agOpenMany(made.map { it.id }, false, 2000L) }
                return JSONObject().put("created", made.size)
            }
            "saved_emails" -> return JSONObject().put("emails", JSONArray(Creds.emails(act)))
            "wait" -> {
                val sec = args.optInt("seconds", 10).coerceIn(1, 25)
                Thread.sleep(sec * 1000L)
                return JSONObject().put("waited", sec)
            }
            "hand_to_coding_agent" -> { handoff = true; return JSONObject().put("ok", true).put("note", "কাজটা কোডিং এজেন্টকে দেওয়া হবে; ব্যবহারকারীকে এক লাইনে জানাও") }
        }
        return JSONObject().put("error", "অজানা টুল: $name")
    }

    // ───────── মূল লুপ ─────────
    private fun histArr(c: Activity): JSONArray = try { JSONArray(Store.prefs(c).getString("ai_hist", "[]")) } catch (e: Exception) { JSONArray() }

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। null = Gemini-ই ডাকা যায়নি (lastErr-এ কারণ) */
    fun run(act: Activity, userText: String): Out? {
        if (!AiRouter.hasKey(act)) return null
        handoff = false
        val contents = JSONArray()
        val hist = histArr(act)
        for (i in 0 until hist.length()) {
            val h = hist.optJSONObject(i) ?: continue
            contents.put(JSONObject().put("role", h.optString("r")).put("parts", JSONArray().put(JSONObject().put("text", h.optString("t")))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText.take(6000)))))
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
        var steps = 0
        while (steps < 16 && System.currentTimeMillis() < deadline) {
            steps++
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", contents)
                .put("tools", tools())
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 8192))
            val resp = AiRouter.call(act, body)
            if (resp == null) return if (steps == 1) null else Out("Gemini মাঝপথে সাড়া দেয়নি (" + AiRouter.lastErr + ") — কাজ আংশিক হয়ে থাকতে পারে; আবার বললে বাকিটা দেখে নেব।", false)
            val cand = resp.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            contents.put(cand)   // যেমন এসেছে তেমনই রাখি (thought signature সহ)
            val parts = cand.optJSONArray("parts") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                p.optJSONObject("functionCall")?.let { calls.add(it) }
                if (!p.has("functionCall") && p.has("text") && !p.optBoolean("thought")) text.append(p.optString("text"))
            }
            if (calls.isEmpty()) {
                val out = text.toString().trim().ifBlank { "কাজ শেষ।" }
                saveHist(act, userText, out)
                return Out(out, handoff)
            }
            val respParts = JSONArray()
            for (cl in calls) {
                val nm = cl.optString("name")
                val result: Any = try { exec(act, nm, cl.optJSONObject("args") ?: JSONObject()) } catch (e: Exception) { JSONObject().put("error", e.javaClass.simpleName + ": " + (e.message ?: "").take(80)) }
                respParts.put(JSONObject().put("functionResponse", JSONObject().put("name", nm).put("response", JSONObject().put("result", result))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", respParts))
        }
        return Out("ধাপের সীমা/সময় শেষ হয়ে গেছে — কাজ আংশিক হয়ে থাকতে পারে। আবার বললে বাকিটা দেখে নেব।", false)
    }

    private fun saveHist(c: Activity, user: String, answer: String) {
        try {
            val h = histArr(c)
            h.put(JSONObject().put("r", "user").put("t", user.take(1500)))
            h.put(JSONObject().put("r", "model").put("t", answer.take(1500)))
            val out = JSONArray()
            for (i in maxOf(0, h.length() - 8) until h.length()) out.put(h.get(i))   // শেষ ৪ জোড়া
            Store.prefs(c).edit().putString("ai_hist", out.toString()).apply()
        } catch (_: Exception) {}
    }
}
