package com.rahcare.termuxbrowser

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Daily Agent-এর কান ও মুখ।
 * - কান: Android SpeechRecognizer (বাংলা bn-BD / ইংরেজি en-US)।
 * - মুখ: Gemini TTS (আসল মানুষের মতো কণ্ঠ, বাংলা-ইংরেজি দুটোই); ব্যর্থ হলে ফোনের নিজস্ব TextToSpeech।
 * শুধু হাব-পাতা এটা ডাকতে পারে (VoiceBridge-এ পাতার url যাচাই হয়)।
 */
object Voice {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"
    const val PERM_CODE = 4720

    @Volatile private var sink: ((String, String) -> Unit)? = null
    private var rec: SpeechRecognizer? = null
    private var pendingAct: Activity? = null
    private var pendingLang = "bn-BD"
    @Volatile private var speakGen = 0
    @Volatile private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile var lastErr = ""

    private fun emit(type: String, text: String = "") { main.post { sink?.invoke(type, text) } }

    // ───────── কান ─────────
    fun listen(a: Activity, lang: String, s: (String, String) -> Unit) {
        sink = s
        stopSpeak()
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAct = a; pendingLang = lang
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_CODE)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(a)) { emit("error", "unavailable"); return }
        try { rec?.destroy() } catch (_: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(a)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { emit("ready") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { emit("end") }
            override fun onError(e: Int) {
                emit("error", if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "nomatch" else if (e == 12 || e == 13) "offline_pack" else "code$e")
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                emit(if (t.isBlank()) "error" else "final", if (t.isBlank()) "nomatch" else t)
            }
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) emit("partial", t)
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, a.packageName)
            if (!LocalBrain.online(a)) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        }
        r.startListening(i)
    }

    fun stopListen() { try { rec?.cancel() } catch (_: Exception) {} }

    /** MainActivity.onRequestPermissionsResult থেকে */
    fun onPerm(granted: Boolean) {
        val a = pendingAct; pendingAct = null
        if (granted && a != null) { val s = sink; if (s != null) listen(a, pendingLang, s) }
        else emit("error", "perm")
    }

    // ───────── মুখ ─────────
    fun stopSpeak() {
        speakGen++
        try { track?.let { it.stop(); it.release() } } catch (_: Exception) {}
        track = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun speak(a: Activity, text: String, lang: String, s: (String, String) -> Unit) {
        sink = s
        stopSpeak()
        val gen = speakGen
        val clean = text.trim().take(1800)
        if (clean.isEmpty()) { emit("speak_end"); return }
        val app = a.applicationContext
        pool.execute {
            try {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) emit("speak_note", "মিডিয়া ভলিউম শূন্য — ফোনের ভলিউম বাড়ান")
            } catch (_: Exception) {}
            lastErr = ""
            val ok = try { geminiSpeak(app, clean, gen) } catch (e: Exception) { lastErr = e.javaClass.simpleName + ": " + (e.message ?: "").take(50); false }
            if (gen != speakGen) return@execute
            if (ok) emit("speak_end")
            else { emit("speak_note", "Gemini কণ্ঠ হয়নি (" + lastErr.ifBlank { "অজানা" } + ") — ফোনের নিজস্ব কণ্ঠে বলছি"); androidSpeak(app, clean, lang, gen) }
        }
    }

    private class Item(val pcm: ByteArray?, val end: Boolean)

    private fun split(t: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var limit = 420
        for (ch in t) {
            sb.append(ch)
            val end = ch == '\u0964' || ch == '.' || ch == '!' || ch == '?' || ch == '\n'
            if ((end && sb.length >= limit) || sb.length >= limit + 150) { out.add(sb.toString().trim()); sb.setLength(0); limit = 420 }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotBlank() }
    }

    /** TTS-সক্ষম সব মডেল (flash আগে)। একটার সীমা শেষ হলে পরেরটা */
    private fun ttsModels(c: Context, key: String): List<String> {
        val p = Store.prefs(c)
        val saved = p.getString("tts_models", "") ?: ""
        if (saved.isNotBlank()) return saved.split(",").filter { it.isNotBlank() }
        var list = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")
        try {
            val cn = URL(BASE + "models?pageSize=200").openConnection() as HttpURLConnection
            cn.connectTimeout = 10000; cn.readTimeout = 15000; cn.setRequestProperty("x-goog-api-key", key)
            if (cn.responseCode == 200) {
                val arr = JSONObject(cn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optJSONArray("models") ?: JSONArray()
                val names = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.removePrefix("models/") }.filter { it.contains("tts") }
                if (names.isNotEmpty()) list = names.sortedWith(compareBy({ if (it.contains("flash") && !it.contains("lite")) 0 else if (it.contains("lite")) 1 else 2 }, { it.length }))
            }
            cn.disconnect()
        } catch (_: Exception) {}
        p.edit().putString("tts_models", list.joinToString(",")).apply()
        return list
    }
    @Volatile private var lastCode = 0
    @Volatile private var lastBody = ""
    private fun ttsCoolLeft(c: Context, m: String) = (Store.prefs(c).getLong("cool_tts_" + m, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    private fun ttsSetCool(c: Context, m: String, body: String) {
        val perDay = body.contains("PerDay", true) || body.contains("per day", true)
        val rd = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
        val ms = if (perDay) 3L * 3600_000 else ((rd ?: 55L) + 5L) * 1000
        Store.prefs(c).edit().putLong("cool_tts_" + m, System.currentTimeMillis() + ms).apply()
    }

    private fun fetchPcm(key: String, model: String, text: String, voice: String): ByteArray? {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        repeat(2) {
            try {
                val cn = URL(BASE + "models/" + model + ":generateContent").openConnection() as HttpURLConnection
                cn.requestMethod = "POST"; cn.connectTimeout = 15000; cn.readTimeout = 60000; cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/json"); cn.setRequestProperty("x-goog-api-key", key)
                cn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = cn.responseCode
                lastCode = code
                if (code == 200) {
                    val r = cn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    cn.disconnect()
                    val d = JSONObject(r).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (d.isNotEmpty()) return Base64.decode(d, Base64.DEFAULT)
                    lastErr = "tts: খালি"
                } else {
                    lastBody = try { cn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                    lastErr = "tts " + model.removePrefix("gemini-") + " HTTP $code"; cn.disconnect()
                    if (code == 401 || code == 403 || code == 404 || code == 429) return null
                }
            } catch (e: Exception) { lastErr = "tts " + e.javaClass.simpleName }
        }
        return null
    }

    /** Gemini TTS: বাক্য-টুকরো করে আনে, প্রথম টুকরো আসতেই বাজানো শুরু (বাকিগুলো পেছনে আসতে থাকে) */
    private fun geminiSpeak(c: Context, text: String, gen: Int): Boolean {
        if (!LocalBrain.online(c)) { lastErr = "অফলাইন"; return false }
        val key = Creds.get(c, AiRouter.KEY)
        if (key.isBlank()) { lastErr = "কী নেই"; return false }
        val models = ttsModels(c, key)
        val voice = Store.prefs(c).getString("tts_voice", "Charon") ?: "Charon"
        val chunks = split(text)
        if (chunks.isEmpty()) return true
        val q = LinkedBlockingQueue<Item>()
        pool.execute {
            for (ch in chunks) {
                if (gen != speakGen) break
                var pcm: ByteArray? = null
                for (m in models) {
                    if (gen != speakGen) break
                    if (ttsCoolLeft(c, m) > 0) continue
                    pcm = fetchPcm(key, m, ch, voice)
                    if (pcm != null) break
                    if (lastCode == 429) ttsSetCool(c, m, lastBody)
                    else if (lastCode == 404 || lastCode == 400) ttsSetCool(c, m, "")
                }
                if (pcm == null) { q.put(Item(null, true)); return@execute }
                q.put(Item(pcm, false))
            }
            q.put(Item(null, true))
        }
        var played = false
        var frames = 0L
        var t: AudioTrack? = null
        try {
            while (gen == speakGen) {
                val it = q.poll(70, TimeUnit.SECONDS) ?: break
                if (it.end) break
                val pcm = it.pcm ?: break
                if (t == null) {
                    val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    t = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(maxOf(min, 24000 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
                    track = t; t.play()
                }
                var off = 0
                while (off < pcm.size && gen == speakGen) {
                    val n = t.write(pcm, off, pcm.size - off)
                    if (n <= 0) break
                    off += n
                }
                frames += pcm.size / 2
                played = true
            }
            // বাফারে যা আছে তা বাজা শেষ হওয়া পর্যন্ত অপেক্ষা
            val tr = t
            if (tr != null && played) {
                val until = System.currentTimeMillis() + frames * 1000 / 24000 + 3000
                while (gen == speakGen && tr.playbackHeadPosition < frames && System.currentTimeMillis() < until) Thread.sleep(60)
            }
        } finally {
            try { t?.stop(); t?.release() } catch (_: Exception) {}
            if (track === t) track = null
        }
        return played
    }

    /** ফোনের নিজস্ব কণ্ঠ (Gemini না চললে) */
    private fun androidSpeak(c: Context, text: String, lang: String, gen: Int) {
        main.post {
            if (gen != speakGen) return@post
            fun go() {
                val t = tts ?: run { emit("speak_end", "err"); return }
                val r = t.setLanguage(Locale.forLanguageTag(lang))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    emit("speak_end", "nolang")
                    try { c.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    return
                }
                val started = java.util.concurrent.atomic.AtomicBoolean(false)
                main.postDelayed({ if (gen == speakGen && !started.get()) { emit("speak_end", "err") } }, 6000)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { started.set(true) }
                    override fun onDone(id: String?) { emit("speak_end") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { emit("speak_end", "err") }
                })
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u$gen")
            }
            if (tts != null && ttsReady) go()
            else {
                try { tts?.shutdown() } catch (_: Exception) {}
                ttsReady = false
                tts = TextToSpeech(c) { st ->
                    if (st == TextToSpeech.SUCCESS) { ttsReady = true; main.post { if (gen == speakGen) go() } }
                    else emit("speak_end", "err")
                }
            }
        }
    }
}

/** হাব-পাতার JS-কে ভয়েসের দরজা। ok() = এখন পাতাটা সত্যিই আমাদের হাব কি না (অন্য সাইট ঢুকলে কিছু চলবে না) */
class VoiceBridge(private val act: Activity, private val ok: () -> Boolean, private val js: (String) -> Unit) {
    private val sink: (String, String) -> Unit = { t, x ->
        if (ok()) js("window.onVoice&&onVoice(" + JSONObject.quote(t) + "," + JSONObject.quote(x) + ")")
    }
    private fun guard(f: () -> Unit) { act.runOnUiThread { if (ok()) f() } }

    @JavascriptInterface fun listen(lang: String) { guard { Voice.listen(act, lang, sink) } }
    @JavascriptInterface fun stopListen() { guard { Voice.stopListen() } }
    @JavascriptInterface fun speak(text: String, lang: String) { guard { Voice.speak(act, text, lang, sink) } }
    @JavascriptInterface fun stopSpeak() { guard { Voice.stopSpeak() } }
    @JavascriptInterface fun lastError(): String = Voice.lastErr
    /** সরাসরি প্রশ্ন (GitHub বাস না ঘুরে)। অনলাইনে Gemini, নইলে/ব্যর্থ হলে অফলাইন মস্তিষ্ক। উত্তর onVoice("answer"|"handoff"|"ai_err") */
    @JavascriptInterface fun ask(text: String) {
        act.runOnUiThread { if (!ok()) return@runOnUiThread
            Thread {
                var type = "answer"; var body = ""; var mode = "online"
                try {
                    var done = false
                    if (LocalBrain.online(act) && AiRouter.hasKey(act)) {
                        val pr = AiAgent.prepare(act, text)
                        val out = AiAgent.run(act, pr.first + pr.second)
                        if (out != null && out.handoff) { type = "handoff"; done = true }
                        else if (out != null) { body = out.text; done = true }
                    }
                    if (!done) { mode = "offline"; body = LocalBrain.answer(act, text) }
                } catch (e: Exception) {
                    mode = "offline"
                    body = try { LocalBrain.answer(act, text) } catch (e2: Exception) { type = "ai_err"; e.javaClass.simpleName + ": " + (e.message ?: "").take(60) }
                }
                act.runOnUiThread { sink("mode", mode); sink(type, body) }
            }.start()
        }
    }
}
