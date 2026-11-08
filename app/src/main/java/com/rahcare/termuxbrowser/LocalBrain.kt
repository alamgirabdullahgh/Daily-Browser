package com.rahcare.termuxbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar

/**
 * Daily Agent-এর অফলাইন মস্তিষ্ক (ইন্টারনেট না থাকলে বা Gemini ব্যর্থ হলে)।
 * স্তর ১: নিয়মভিত্তিক হাত — সময়, তারিখ, ব্যাটারি, হিসাব, টাইমার, কল-ডায়াল, অ্যাপ/ওয়ার্কস্পেস খোলা (মডেল ছাড়াই চলে)।
 * স্তর ২: ফোনের ভেতরে চলা AI মডেল (MediaPipe + Gemma .task ফাইল) — ফাইল দিলে যেকোনো প্রশ্নের উত্তর অফলাইনে।
 */
object LocalBrain {
    const val PICK_CODE = 4730
    private fun modelFile(c: Context) = File(c.filesDir, "offline_model.task")
    fun hasModel(c: Context) = modelFile(c).let { it.exists() && it.length() > 1_000_000 }
    @Volatile var lastErr = ""
    private var llm: Any? = null
    private var llmPath = ""
    private var hist = ArrayList<Pair<String, String>>()

    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { true }

    // ───────── ছোট সহায়ক ─────────
    private val BN = "০১২৩৪৫৬৭৮৯"
    private fun bn(n: Int) = n.toString().map { BN[it - '0'] }.joinToString("")
    private fun en(s: String) = s.map { if (it in BN) ('0' + BN.indexOf(it)) else it }.joinToString("")
    private val DAYS = listOf("রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার")
    private val MONTHS = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    private fun period(h: Int) = when (h) { in 4..5 -> "ভোর"; in 6..11 -> "সকাল"; in 12..14 -> "দুপুর"; in 15..16 -> "বিকেল"; in 17..18 -> "সন্ধ্যা"; else -> "রাত" }

    private fun timeText(): String {
        val c = Calendar.getInstance(); val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "এখন " + period(h) + " " + bn(h12) + "টা" + (if (m > 0) " " + bn(m) + " মিনিট" else "")
    }

    private fun dateText(): String {
        val c = Calendar.getInstance()
        return "আজ " + DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + bn(c.get(Calendar.DAY_OF_MONTH)) + " " + MONTHS[c.get(Calendar.MONTH)] + " " + bn(c.get(Calendar.YEAR))
    }

    private fun calc(t0: String): String? {
        var t = en(t0).lowercase()
        t = t.replace("প্লাস", "+").replace("যোগ", "+").replace("plus", "+").replace("বিয়োগ", "-").replace("minus", "-")
            .replace("গুণ", "*").replace("times", "*").replace("multiplied by", "*").replace("×", "*").replace("x", "*")
            .replace("ভাগ", "/").replace("divided by", "/").replace("÷", "/")
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)").find(t) ?: return null
        val a = m.groupValues[1].toDouble(); val b = m.groupValues[3].toDouble()
        val r = when (m.groupValues[2]) { "+" -> a + b; "-" -> a - b; "*" -> a * b; else -> if (b == 0.0) return "শূন্য দিয়ে ভাগ করা যায় না।" else a / b }
        val s = if (r == Math.floor(r) && Math.abs(r) < 1e15) r.toLong().toString() else String.format("%.4f", r).trimEnd('0').trimEnd('.')
        return "উত্তর " + s.map { if (it in '0'..'9') BN[it - '0'] else if (it == '-') 'ঋ' else it }.joinToString("").replace("ঋ", "মাইনাস ")
    }

    private val APP_ALIAS = mapOf(
        "ইউটিউব" to "youtube", "ফেসবুক" to "facebook", "হোয়াটসঅ্যাপ" to "whatsapp", "হোয়াটসঅ্যাপ" to "whatsapp", "ক্যামেরা" to "camera",
        "সেটিংস" to "settings", "গ্যালারি" to "gallery", "ছবি" to "photos", "ক্রোম" to "chrome", "ক্যালকুলেটর" to "calculator",
        "ঘড়ি" to "clock", "টেলিগ্রাম" to "telegram", "ইমো" to "imo", "মেসেঞ্জার" to "messenger", "ম্যাপ" to "maps", "গুগল" to "google",
        "প্লে স্টোর" to "play store", "বিকাশ" to "bkash", "নগদ" to "nagad", "ফোন" to "phone", "মেসেজ" to "messages", "কন্টাক্ট" to "contacts")

    private fun openApp(a: Activity, t: String): String? {
        val pm = a.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val lower = t.lowercase()
        var want = APP_ALIAS.entries.firstOrNull { lower.contains(it.key) }?.value
        val hit = apps.firstOrNull { want != null && it.first.lowercase().contains(want!!) }
            ?: apps.firstOrNull { lower.contains(it.first.lowercase()) && it.first.length >= 3 } ?: return null
        val li = pm.getLaunchIntentForPackage(hit.second) ?: return null
        a.runOnUiThread { try { a.startActivity(li) } catch (_: Exception) {} }
        return hit.first + " খুলছি।"
    }

    private fun openWorkspace(a: Activity, t: String): String? {
        val m = a as? MainActivity ?: return null
        val lower = t.lowercase()
        val s = Sites.all(a).firstOrNull { it.name.isNotBlank() && lower.contains(it.name.lowercase()) } ?: return null
        a.runOnUiThread { m.agOpenMany(listOf(s.id), false, 1000L) }
        return s.name + " ওয়ার্কস্পেস খুলছি।"
    }

    /** নিয়মে মিললে উত্তর/কাজের নিশ্চিতকরণ, নইলে null */
    fun rules(a: Activity, raw: String): String? {
        val t = raw.removePrefix("[ভয়েস]").trim()
        val l = t.lowercase()
        // টাইমার
        if (has(l, "টাইমার", "timer", "অ্যালার্ম", "অ্যালার্ট", "রিমাইন্ড")) {
            val num = Regex("(\\d+)").find(en(l))?.groupValues?.get(1)?.toIntOrNull()
            if (num != null) {
                val secs = if (has(l, "ঘণ্টা", "ঘন্টা", "hour")) num * 3600 else if (has(l, "সেকেন্ড", "second")) num else num * 60
                val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try { a.runOnUiThread { a.startActivity(i) }; "টাইমার সেট করলাম, " + bn(num) + (if (secs % 3600 == 0 && num * 3600 == secs) " ঘণ্টা" else if (has(l, "সেকেন্ড", "second")) " সেকেন্ড" else " মিনিট") + "।" } catch (e: Exception) { null }
            }
        }
        // কল (ডায়াল পর্দা খোলে, নিজে কল করে না)
        if (has(l, "কল", "ফোন করো", "dial", "call")) {
            val d = en(t).filter { it.isDigit() || it == '+' }
            if (d.length >= 5) {
                a.runOnUiThread { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$d")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return "ডায়াল পর্দায় নম্বরটা বসিয়ে দিলাম, কল বোতাম চাপুন।"
            }
        }
        if (has(l, "ব্যাটারি", "battery", "চার্জ কত")) {
            val b = a.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return "ব্যাটারি এখন " + bn(b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) + " শতাংশ" + (if (b.isCharging) ", চার্জ হচ্ছে।" else "।")
        }
        if (has(l, "সময়", "কয়টা", "কটা বাজে", "what time", "time now")) return timeText() + "।"
        if (has(l, "তারিখ", "কি বার", "কী বার", "কত তারিখ", "today", "date")) return dateText() + "।"
        calc(t)?.let { return it }
        if (has(l, "খোলো", "খুলো", "চালু কর", "open", "ওপেন")) {
            openWorkspace(a, t)?.let { return it }
            openApp(a, t)?.let { return it }
        }
        if (has(l, "তুমি কে", "তোমার নাম", "who are you", "your name")) return "আমি ডেইলি এজেন্ট, আপনার ব্রাউজারের সহকারী। ইন্টারনেট থাকলে আমি আরও অনেক কিছু পারি, আর অফলাইনেও সময়, হিসাব, টাইমার আর অ্যাপ খোলার মতো কাজ করতে পারি।"
        if (Regex("^(হ্যালো|হাই|হেলো|আসসালামু|সালাম|hello|hi|hey)").containsMatchIn(l)) return "জি, বলুন। আমি শুনছি।"
        if (has(l, "ধন্যবাদ", "thanks", "thank you")) return "আপনাকে স্বাগতম।"
        return null
    }

    // ───────── স্তর ২: ফোনের ভেতরের AI মডেল ─────────
    @Synchronized
    fun llm(a: Activity, raw: String): String? {
        if (!hasModel(a)) return null
        return try {
            val path = modelFile(a).absolutePath
            if (llm == null || llmPath != path) {
                val opts = com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path).setMaxTokens(1024).build()
                llm = com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(a.applicationContext, opts)
                llmPath = path
            }
            val eng = llm as com.google.mediapipe.tasks.genai.llminference.LlmInference
            val q = raw.removePrefix("[ভয়েস]").trim().take(600)
            val sb = StringBuilder()
            sb.append("<start_of_turn>user\nতুমি Daily Agent, একজন শান্ত, বুদ্ধিমান বাংলা সহকারী। ব্যবহারকারী যে ভাষায় লেখে সেই ভাষায় ২-৫টা ছোট বাক্যে নির্ভুল উত্তর দাও। না জানলে সৎভাবে বলো, অনুমান করে বানিয়ো না।<end_of_turn>\n")
            for ((u, m) in hist.takeLast(2)) sb.append("<start_of_turn>user\n").append(u).append("<end_of_turn>\n<start_of_turn>model\n").append(m).append("<end_of_turn>\n")
            sb.append("<start_of_turn>user\n").append(q).append("<end_of_turn>\n<start_of_turn>model\n")
            val out = eng.generateResponse(sb.toString()).trim()
            if (out.isNotBlank()) { hist.add(q to out.take(500)); while (hist.size > 4) hist.removeAt(0) }
            lastErr = ""
            out.ifBlank { null }
        } catch (e: Throwable) {
            lastErr = e.javaClass.simpleName + ": " + (e.message ?: "").take(80)
            llm = null
            null
        }
    }

    /** অফলাইন উত্তর: নিয়ম → মডেল → সৎ অপারগতা */
    fun answer(a: Activity, raw: String): String {
        rules(a, raw)?.let { return it }
        llm(a, raw)?.let { return it }
        return if (hasModel(a)) "অফলাইন মডেল উত্তর দিতে পারল না (" + lastErr.ifBlank { "অজানা কারণ" } + ")। ইন্টারনেট থাকলে আবার বলুন।"
        else "এটা ইন্টারনেট ছাড়া পারছি না। সময়, তারিখ, ব্যাটারি, হিসাব, টাইমার, কল-নম্বর আর অ্যাপ খোলা অফলাইনে পারি। যেকোনো প্রশ্নের উত্তর অফলাইনে চাইলে মেনু থেকে অফলাইন মডেল যোগ করুন।"
    }

    // ───────── মেনু: মডেল যোগ/মোছা ─────────
    fun dialog(a: Activity) {
        val st = if (hasModel(a)) "✔ অফলাইন মডেল আছে (" + (modelFile(a).length() / 1_000_000) + " MB)।" else "এখনো অফলাইন মডেল নেই।"
        val tv = TextView(a).apply {
            textSize = 13f; setPadding(48, 16, 48, 0)
            text = st + "\n\nঅফলাইনে যেকোনো প্রশ্নের উত্তরের জন্য একটা Gemma .task ফাইল লাগবে (Hugging Face-এর litert-community/Gemma3-1B-IT, প্রায় ৬০০ MB; Gemma-র শর্ত মেনে লগইন করে নামাতে হয়)। ফাইল নামিয়ে এখান থেকে বাছুন। ফোন বড়/নতুন হলে ভালো চলে।\n\nমডেল ছাড়াও অফলাইনে চলে: সময়, তারিখ, ব্যাটারি, হিসাব, টাইমার, কল-নম্বর বসানো, অ্যাপ ও ওয়ার্কস্পেস খোলা।"
        }
        AlertDialog.Builder(a).setTitle("অফলাইন মস্তিষ্ক").setView(tv)
            .setPositiveButton("মডেল ফাইল বাছুন") { _, _ ->
                try { a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_CODE) }
                catch (e: Exception) { Toast.makeText(a, "ফাইল-বাছাই খোলা যায়নি", Toast.LENGTH_LONG).show() }
            }
            .setNeutralButton("মডেল মুছুন") { _, _ -> llm = null; modelFile(a).delete(); Toast.makeText(a, "অফলাইন মডেল মোছা হয়েছে", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("বন্ধ", null).show()
    }

    /** onActivityResult থেকে: বাছা ফাইল নিজের জায়গায় কপি */
    fun importModel(a: Activity, u: Uri) {
        Toast.makeText(a, "মডেল কপি হচ্ছে… কয়েক মিনিট লাগতে পারে, অ্যাপ বন্ধ করবেন না", Toast.LENGTH_LONG).show()
        Thread {
            val tmp = File(a.filesDir, "offline_model.tmp")
            val ok = try {
                a.contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } } ?: throw Exception("খালি")
                synchronized(this) { llm = null }
                val f = modelFile(a); f.delete(); tmp.renameTo(f)
            } catch (e: Exception) { tmp.delete(); false }
            a.runOnUiThread { Toast.makeText(a, if (ok) "✔ অফলাইন মডেল যোগ হয়েছে" else "মডেল কপি ব্যর্থ", Toast.LENGTH_LONG).show() }
        }.start()
    }
}
