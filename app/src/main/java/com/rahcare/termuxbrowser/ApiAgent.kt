package com.rahcare.termuxbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * API এজেন্ট: ওয়ার্কস্পেসের লিংকের ঘরে লিংকের বদলে Gemini API key দিলে সেটা ওয়ার্কস্পেস হয়ে যায়, কিন্তু ওয়েবসাইট না খুলে
 * এই এজেন্ট খোলে। এক key দিয়ে ছবি, অডিও (TTS), ভিডিও ও চ্যাট — কাজ অনুযায়ী মডেল নিজে বাছে; কোনো মডেলের সীমা শেষ বা মডেল
 * বন্ধ হলে তালিকার পরের মডেলে নিজে চলে যায়। মডেলের নাম কোডে বসানো নেই: key-র তালিকা (ListModels) থেকে ক্ষমতা দেখে বাছা হয়,
 * তাই Google নাম বদলালেও চলে।
 * key কখনো Site.url-এ বা ব্যাকআপে যায় না: url হয় "apikey://gemini/<id>", আসল key Android Keystore-এ এনক্রিপ্ট করা (Secret.putFor)।
 */
object ApiAgent {
    const val PREFIX = "apikey://gemini/"
    const val DEFAULT_NAME = "Gemini API"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"
    private val KEY_RX = Regex("^AIza[0-9A-Za-z_\\-]{30,60}$")
    private val OTHER_RX = Regex("^(sk-|xai-|gsk_)[A-Za-z0-9_\\-]{20,}$")

    fun isKey(s: String): Boolean = KEY_RX.matches(s.trim())
    fun isOtherKey(s: String): Boolean = OTHER_RX.matches(s.trim())
    fun isApiSite(s: Site): Boolean = s.url.startsWith(PREFIX)
    fun store(c: Context, id: String, key: String): String { Secret.putFor(c, id, key.trim()); return PREFIX + id }
    fun forget(c: Context, id: String) { Secret.putFor(c, id, "") }
    private fun keyOf(c: Context, id: String): String = Secret.getFor(c, id)

    class Out(val kind: String, val model: String = "", val text: String = "", val file: File? = null, val err: String = "")
    private class Fail(val code: Int, val msg: String) : Exception(msg)
    private class Mdl(val id: String, val methods: Set<String>)

    // ───────── HTTP ─────────
    private fun http(method: String, url: String, key: String, body: String?, readMs: Int): Pair<Int, String> {
        val cn = URL(url).openConnection() as HttpURLConnection
        try {
            cn.requestMethod = method; cn.connectTimeout = 20000; cn.readTimeout = readMs
            cn.setRequestProperty("x-goog-api-key", key)
            if (body != null) {
                cn.doOutput = true; cn.setRequestProperty("Content-Type", "application/json")
                cn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = cn.responseCode
            val st = if (code in 200..299) cn.inputStream else cn.errorStream
            val txt = st?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, txt)
        } finally { cn.disconnect() }
    }

    private fun errMsg(txt: String): String = try {
        JSONObject(txt).optJSONObject("error")?.optString("message", txt) ?: txt
    } catch (_: Exception) { txt.take(300) }

    private fun download(url: String, key: String, to: File) {
        val cn = URL(url).openConnection() as HttpURLConnection
        try {
            cn.connectTimeout = 20000; cn.readTimeout = 180000
            cn.setRequestProperty("x-goog-api-key", key)
            if (cn.responseCode !in 200..299) throw Fail(cn.responseCode, "ডাউনলোড ব্যর্থ")
            cn.inputStream.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
        } finally { cn.disconnect() }
    }

    // ───────── মডেল তালিকা ও বাছাই ─────────
    private var mdlCache: List<Mdl> = emptyList()
    private var mdlAt = 0L
    private var mdlKey = ""

    private fun models(key: String): List<Mdl> {
        val now = System.currentTimeMillis()
        if (mdlCache.isNotEmpty() && mdlKey == key && now - mdlAt < 3600000L) return mdlCache
        val out = ArrayList<Mdl>()
        var tok = ""
        for (page in 0 until 6) {
            val r = http("GET", BASE + "models?pageSize=200" + (if (tok.isEmpty()) "" else "&pageToken=" + tok), key, null, 30000)
            if (r.first !in 200..299) throw Fail(r.first, errMsg(r.second))
            val j = JSONObject(r.second)
            val a = j.optJSONArray("models") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ms = HashSet<String>()
                val m = o.optJSONArray("supportedGenerationMethods")
                if (m != null) {
                    for (k in 0 until m.length()) { ms.add(m.getString(k)) }
                }
                out.add(Mdl(o.optString("name").removePrefix("models/"), ms))
            }
            tok = j.optString("nextPageToken", "")
            if (tok.isEmpty()) break
        }
        mdlCache = out; mdlAt = now; mdlKey = key
        return out
    }

    private val NOT_CHAT = Regex("tts|image|live|embed|imagen|veo|omni|aqa|audio|robotics|computer|gemma|learnlm|deep-research|native")
    private fun ver(n: String): Double = Regex("(\\d+(?:\\.\\d+)?)").find(n)?.value?.toDoubleOrNull() ?: 0.0
    private fun tier(n: String): Int {
        if (n.contains("flash-lite")) return 1
        if (n.contains("flash")) return 0
        if (n.contains("pro")) return 2
        return 1
    }

    /** কাজের ধরন অনুযায়ী মডেল: আগে flash (বড় কোটা), তারপর lite, তারপর pro; একই স্তরে নতুন সংস্করণ আগে */
    private fun pick(all: List<Mdl>, kind: String): List<Mdl> {
        val l = ArrayList<Mdl>()
        for (x in all) {
            val gc = "generateContent" in x.methods
            val ok: Boolean
            if (kind == "text") {
                ok = gc && x.id.startsWith("gemini") && !NOT_CHAT.containsMatchIn(x.id)
            } else if (kind == "image") {
                ok = (gc && x.id.contains("image") && !x.id.contains("imagen")) || ("predict" in x.methods && x.id.contains("imagen"))
            } else if (kind == "audio") {
                ok = gc && x.id.contains("tts")
            } else {
                ok = "predictLongRunning" in x.methods && !x.id.contains("embed")
            }
            if (ok) l.add(x)
        }
        return l.sortedBy { tier(it.id) * 1000.0 - ver(it.id) }
    }

    private val blocked = HashMap<String, Long>()
    private fun isBlocked(m: String): Boolean = (blocked[m] ?: 0L) > System.currentTimeMillis()
    private fun block(m: String, ms: Long) { blocked[m] = System.currentTimeMillis() + ms }
    private fun isModelProblem(msg: String): Boolean {
        val t = msg.lowercase()
        return t.contains("not found") || t.contains("not supported") || t.contains("unsupported") || t.contains("unavailable") ||
            t.contains("not enabled") || t.contains("only supports") || t.contains("modalit") || t.contains("deprecated")
    }

    fun detect(p: String): String {
        val t = p.lowercase()
        if (Regex("ভিডিও|ভিডিয়ো|video|clip|animation|অ্যানিমেশন").containsMatchIn(t)) return "video"
        if (Regex("অডিও|audio|voice|ভয়েস|tts|পড়ে শোনাও|পড়ে শুনাও|কণ্ঠ|speak|narrat").containsMatchIn(t)) return "audio"
        if (Regex("ছবি|ইমেজ|image|picture|draw|আঁক|আকো|logo|লোগো|poster|পোস্টার|wallpaper|ওয়ালপেপার|photo").containsMatchIn(t)) return "image"
        return "text"
    }

    // ───────── চালানো: মডেল ব্যর্থ হলে পরেরটায় ─────────
    fun generate(key: String, kind0: String, prompt: String, dir: File, say: (String) -> Unit): Out {
        val kind = if (kind0 == "auto") detect(prompt) else kind0
        var all: List<Mdl> = emptyList()
        try {
            all = models(key)
        } catch (e: Fail) {
            return Out(kind, err = explain(e))
        } catch (e: Exception) {
            return Out(kind, err = "নেট বা সংযোগ সমস্যা: " + (e.message ?: ""))
        }
        val cands = pick(all, kind)
        if (cands.isEmpty()) return Out(kind, err = "এই key-তে এই ধরনের কাজের কোনো মডেল পাওয়া যায়নি")
        var last = ""
        for (m in cands) {
            if (isBlocked(m.id)) continue
            say("মডেল: " + m.id)
            try {
                return gen(kind, m, key, prompt, dir, say)
            } catch (e: Fail) {
                last = m.id + ": " + e.msg
                if (e.code == 401 || (e.code == 400 && e.msg.contains("API key", true))) return Out(kind, err = explain(e))
                if (e.code == 429) {
                    val day = e.msg.contains("PerDay", true) || e.msg.contains("per day", true)
                    block(m.id, if (day) 6L * 3600000L else 90000L)
                    say(m.id + "-এর সীমা শেষ — পরের মডেলে যাচ্ছি")
                } else if (e.code == 400 && !isModelProblem(e.msg)) {
                    return Out(kind, err = e.msg)
                } else {
                    block(m.id, 600000L)
                    say(m.id + " এখন চলছে না — পরের মডেলে যাচ্ছি")
                }
            } catch (e: Exception) {
                return Out(kind, err = "নেট বা সংযোগ সমস্যা: " + (e.message ?: ""))
            }
        }
        return Out(kind, err = "সব মডেলের সীমা শেষ বা চলছে না। কিছুক্ষণ পরে আবার চেষ্টা করুন." + (if (last.isEmpty()) "" else "\nশেষ কারণ — " + last.take(200)))
    }

    private fun explain(e: Fail): String =
        if (e.code == 401 || (e.code == 400 && e.msg.contains("API key", true))) "API key ঠিক নেই বা বন্ধ হয়ে গেছে (✎ চেপে নতুন key দিন)" else e.msg

    private fun content(model: String, key: String, prompt: String, gc: JSONObject?): JSONObject {
        val b = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (gc != null) b.put("generationConfig", gc)
        val r = http("POST", BASE + "models/" + model + ":generateContent", key, b.toString(), 180000)
        if (r.first !in 200..299) throw Fail(r.first, errMsg(r.second))
        val j = JSONObject(r.second)
        val block = j.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        if (block.isNotEmpty()) throw Fail(400, "অনুরোধটা নিরাপত্তা ফিল্টারে আটকে গেছে ($block) — অন্যভাবে লিখুন")
        return j
    }

    private fun partsOf(j: JSONObject): JSONArray =
        j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()

    private fun textOf(j: JSONObject): String {
        val p = partsOf(j); val sb = StringBuilder()
        for (i in 0 until p.length()) { val t = p.getJSONObject(i).optString("text", ""); if (t.isNotEmpty()) sb.append(t) }
        return sb.toString().trim()
    }

    private fun inlineOf(j: JSONObject): Pair<String, ByteArray>? {
        val p = partsOf(j)
        for (i in 0 until p.length()) {
            val o = p.getJSONObject(i)
            val d = o.optJSONObject("inlineData") ?: o.optJSONObject("inline_data") ?: continue
            val data = d.optString("data", "")
            if (data.isNotEmpty()) return Pair(d.optString("mimeType", d.optString("mime_type", "")), Base64.decode(data, Base64.DEFAULT))
        }
        return null
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val bo = ByteArrayOutputStream()
        fun le32(v: Int) { bo.write(v and 255); bo.write((v shr 8) and 255); bo.write((v shr 16) and 255); bo.write((v shr 24) and 255) }
        fun le16(v: Int) { bo.write(v and 255); bo.write((v shr 8) and 255) }
        bo.write("RIFF".toByteArray()); le32(36 + pcm.size); bo.write("WAVE".toByteArray())
        bo.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        bo.write("data".toByteArray()); le32(pcm.size); bo.write(pcm)
        return bo.toByteArray()
    }

    private fun gen(kind: String, m: Mdl, key: String, prompt: String, dir: File, say: (String) -> Unit): Out {
        val ts = System.currentTimeMillis()
        if (kind == "text") {
            val t = textOf(content(m.id, key, prompt, null))
            if (t.isEmpty()) throw Fail(500, "খালি উত্তর এসেছে")
            return Out(kind, m.id, text = t)
        }
        if (kind == "image") {
            if ("predict" in m.methods && m.id.contains("imagen")) {
                val body = JSONObject().put("instances", JSONArray().put(JSONObject().put("prompt", prompt))).put("parameters", JSONObject().put("sampleCount", 1))
                val r = http("POST", BASE + "models/" + m.id + ":predict", key, body.toString(), 120000)
                if (r.first !in 200..299) throw Fail(r.first, errMsg(r.second))
                val b64 = JSONObject(r.second).optJSONArray("predictions")?.optJSONObject(0)?.optString("bytesBase64Encoded", "") ?: ""
                if (b64.isEmpty()) throw Fail(500, "ছবি আসেনি (নিরাপত্তা ফিল্টার হতে পারে)")
                val f = File(dir, "image-$ts.png"); f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                return Out(kind, m.id, file = f)
            }
            val gc = JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            val d = inlineOf(content(m.id, key, prompt, gc)) ?: throw Fail(500, "ছবি আসেনি")
            val f = File(dir, "image-$ts." + (if (d.first.contains("jpeg") || d.first.contains("jpg")) "jpg" else "png"))
            f.writeBytes(d.second)
            return Out(kind, m.id, file = f)
        }
        if (kind == "audio") {
            val voice = JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))
            val gc = JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put("speechConfig", JSONObject().put("voiceConfig", voice))
            val d = inlineOf(content(m.id, key, prompt, gc)) ?: throw Fail(500, "অডিও আসেনি")
            val rate = Regex("rate=(\\d+)").find(d.first)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            val f = File(dir, "audio-$ts.wav"); f.writeBytes(wav(d.second, rate))
            return Out(kind, m.id, file = f)
        }
        // ভিডিও: দীর্ঘ-চলা অপারেশন; তৈরি হলে লিংক থেকে নামায়
        val body = JSONObject().put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
        val r = http("POST", BASE + "models/" + m.id + ":predictLongRunning", key, body.toString(), 60000)
        if (r.first !in 200..299) throw Fail(r.first, errMsg(r.second))
        val op = JSONObject(r.second).optString("name", "")
        if (op.isEmpty()) throw Fail(500, "ভিডিও কাজের আইডি আসেনি")
        var waited = 0
        while (waited < 600) {
            Thread.sleep(10000); waited += 10
            say("ভিডিও বানাচ্ছে… " + waited + " সেকেন্ড (" + m.id + ")")
            val p = http("GET", BASE + op, key, null, 60000)
            if (p.first !in 200..299) throw Fail(p.first, errMsg(p.second))
            val o = JSONObject(p.second)
            if (!o.optBoolean("done", false)) continue
            val er = o.optJSONObject("error")
            if (er != null) { val cd = er.optInt("code", 500); throw Fail(if (cd == 8) 429 else if (cd in 100..599) cd else 500, er.optString("message", "ভিডিও ব্যর্থ")) }
            val uri = o.optJSONObject("response")?.optJSONObject("generateVideoResponse")?.optJSONArray("generatedSamples")
                ?.optJSONObject(0)?.optJSONObject("video")?.optString("uri", "") ?: ""
            if (uri.isEmpty()) throw Fail(500, "ভিডিওর লিংক আসেনি (নিরাপত্তা ফিল্টার হতে পারে)")
            val f = File(dir, "video-$ts.mp4"); download(uri, key, f)
            return Out(kind, m.id, file = f)
        }
        throw Fail(504, "ভিডিও বানাতে অনেক সময় লাগছে")
    }

    // ───────── পর্দা ─────────
    fun show(act: Activity, site: Site) {
        val key = keyOf(act, site.id)
        if (key.isBlank()) { Toast.makeText(act, "এই ওয়ার্কস্পেসের API key পাওয়া যায়নি — ✎ চেপে key আবার দিন", Toast.LENGTH_LONG).show(); return }
        val dp = { v: Int -> (v * act.resources.displayMetrics.density).toInt() }
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val kinds = listOf("auto", "image", "audio", "video", "text")
        val sp = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, listOf("স্বয়ংক্রিয় (লেখা দেখে বুঝবে)", "ছবি", "অডিও", "ভিডিও", "চ্যাট"))
        }
        val et = EditText(act).apply {
            hint = "কী বানাতে চান? (ছবি/ভিডিওর বর্ণনা, বা অডিওতে যা পড়াতে চান — অডিওতে শুধু পড়ার লেখাটুকু)"
            minLines = 3; gravity = Gravity.TOP or Gravity.START
        }
        val go = Button(act).apply { text = "চালাও" }
        val st = TextView(act).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sp); root.addView(et); root.addView(go); root.addView(st); root.addView(box)
        val sv = ScrollView(act).apply { addView(root) }
        var mp: MediaPlayer? = null
        val d = AlertDialog.Builder(act).setTitle(site.name + " · API এজেন্ট").setView(sv).setNegativeButton("বন্ধ", null).create()
        d.setOnDismissListener { try { mp?.release() } catch (_: Exception) {}; mp = null }
        val dir = (act.getExternalFilesDir("api-agent") ?: File(act.filesDir, "api-agent")).also { it.mkdirs() }
        go.setOnClickListener {
            val p = et.text.toString().trim()
            if (p.isEmpty()) return@setOnClickListener
            go.isEnabled = false; box.removeAllViews(); st.text = "⏳ শুরু হচ্ছে…"
            val kind = kinds[sp.selectedItemPosition]
            Thread {
                var r = Out(kind, err = "অজানা ত্রুটি")
                try {
                    r = generate(key, kind, p, dir) { s -> act.runOnUiThread { st.text = "⏳ " + s } }
                } catch (e: Exception) {
                    r = Out(kind, err = "ত্রুটি: " + (e.message ?: ""))
                }
                val res = r
                act.runOnUiThread {
                    go.isEnabled = true
                    if (res.err.isNotEmpty()) { st.text = "❌ " + res.err }
                    else {
                        val f = res.file
                        st.text = "✅ মডেল: " + res.model + (if (f != null) "\nসংরক্ষিত: " + f.absolutePath else "")
                        if (res.kind == "text") {
                            box.addView(TextView(act).apply { text = res.text; setTextIsSelectable(true); textSize = 15f })
                        } else if (f != null && res.kind == "image") {
                            box.addView(ImageView(act).apply { setImageBitmap(BitmapFactory.decodeFile(f.absolutePath)); adjustViewBounds = true })
                        } else if (f != null && res.kind == "audio") {
                            box.addView(Button(act).apply {
                                text = "▶ চালান"
                                setOnClickListener {
                                    try { mp?.release(); mp = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() } } catch (_: Exception) {}
                                }
                            })
                        } else if (f != null && res.kind == "video") {
                            val vv = VideoView(act)
                            vv.setMediaController(MediaController(act)); vv.setVideoPath(f.absolutePath)
                            box.addView(vv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
                            vv.start()
                        }
                    }
                }
            }.start()
        }
        d.show()
    }
}
