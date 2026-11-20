package com.rahcare.termuxbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** পেজ থেকে ডাকা যায় (শুধু রেকর্ডিং চলার সময়, টোকেন মিললে) */
class MacroBridge(private val act: Activity) {
    @JavascriptInterface fun rec(t: String, s: String): Int = Macro.addStep(t, s)
    @JavascriptInterface fun stop(t: String) { Macro.pageStop(act, t) }
}

/**
 * অটো-রেকর্ডার: সাইটে আপনার চাপ/লেখা রেকর্ড হয়, ধাপগুলো (JSON "কোড") সেভ হয়, পরে নামে চালানো যায়।
 * - রেকর্ড: assets/macro-rec.js পেজে ঢুকে চাপ/লেখা/নির্বাচন/Enter/ফাইল-বাছাই ধরে (পাসওয়ার্ড নয়)।
 * - রিপ্লে: assets/macro-run.js এক ধাপ চালায়; ফাইল-বাছাই ও ডাউনলোডের জন্য আসল আঙুলের চাপ (MotionEvent) দেওয়া হয়।
 * - ফাইল: সাধারণ ব্রাউজার নিজে ফাইল বাছাই করতে পারে না; এখানে ফোন থেকে বাছা ফোল্ডার/ফাইল নম্বর অনুযায়ী
 *   সাজিয়ে সাইটের "ফাইল বাছুন" অনুরোধে অ্যাপ নিজে দেয় (onShowFileChooser-এ Macro.fileChooser)।
 */
object Macro {
    const val PICK_FOLDER = 4821
    const val PICK_FILES = 4822

    interface Host {
        /** কাজ চালানোর ট্যাব (এজেন্ট-হাব/হোম নয়); না থাকলে null */
        fun web(): WebView?
        /** নতুন ট্যাবে ঠিকানা খোলে */
        fun open(url: String): WebView?
    }

    class Item(val name: String, val uri: Uri, val mime: String)
    private class Ctx(val a: Activity, val steps: JSONArray, val files: List<Item>, val run: Int)

    var host: Host? = null
    private val ui = Handler(Looper.getMainLooper())

    // ---------------- সংরক্ষণ ----------------
    private fun dir(c: Context): File = File(c.filesDir, "macros").also { it.mkdirs() }
    private fun fileOf(c: Context, name: String) = File(dir(c), name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim() + ".json")
    fun names(c: Context): List<String> =
        (dir(c).listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.sorted()
    fun load(c: Context, name: String): JSONObject? = try { JSONObject(fileOf(c, name).readText()) } catch (_: Exception) { null }
    private fun save(c: Context, name: String, steps: JSONArray) {
        val o = JSONObject().put("name", name).put("created", System.currentTimeMillis()).put("steps", steps)
        fileOf(c, name).writeText(o.toString())
    }
    fun delete(c: Context, name: String) { fileOf(c, name).delete() }

    private fun asset(c: Context, n: String): String =
        try { c.assets.open(n).bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (_: Exception) { "" }
    private var runJsCache: String? = null
    private fun runJs(c: Context): String = runJsCache ?: asset(c, "macro-run.js").also { if (it.isNotEmpty()) runJsCache = it }

    // ---------------- রেকর্ড ----------------
    @Volatile private var recording = false
    private var token = ""
    private val rec = JSONArray()
    private var recLast = 0L
    fun isRecording() = recording
    fun isRunning() = running

    private fun sameEl(a: JSONObject, b: JSONObject): Boolean {
        val x = a.optJSONObject("c")?.optString("css") ?: return false
        val y = b.optJSONObject("c")?.optString("css") ?: return false
        return x == y
    }

    private fun doAdd(o: JSONObject): Int {
        val now = System.currentTimeMillis()
        val last = if (rec.length() > 0) rec.optJSONObject(rec.length() - 1) else null
        when (o.optString("t")) {
            "page" -> {
                // প্রথম পেজের ঠিকানা = "খুলুন" ধাপ (ঠিকানা-বারে লেখা ঠিকানাও এতে আসে)
                if (rec.length() == 0) rec.put(JSONObject().put("t", "open").put("u", o.optString("u")).put("g", 0))
                return rec.length()
            }
            "file" -> if (o.optBoolean("prev")) {
                // সাইটের নিজের বোতাম input.click() ডেকেছে: আগের "চাপ" ধাপটাই ফাইল-ধাপ
                if (last != null && last.optString("t") == "click" && now - recLast < 2000) {
                    last.put("t", "file"); last.put("mul", o.optBoolean("mul")); last.put("acc", o.optString("acc"))
                }
                return rec.length()
            }
            "type" -> if (last != null && last.optString("t") == "type" && sameEl(last, o)) {
                o.put("g", last.optInt("g")); rec.put(rec.length() - 1, o); recLast = now
                return rec.length()
            }
        }
        rec.put(o); recLast = now
        return rec.length()
    }

    fun addStep(t: String, s: String): Int {
        if (!recording || t != token) return -1
        val o = try { JSONObject(s) } catch (_: Exception) { return rec.length() }
        return synchronized(rec) { doAdd(o) }
    }

    private fun injectRec(c: Context, web: WebView) {
        val js = asset(c, "macro-rec.js").replace("__TOKEN__", token)
        if (js.isNotEmpty()) web.evaluateJavascript(js, null)
    }

    fun startRecording(a: Activity, web: WebView?) {
        if (web == null) { Toast.makeText(a, "আগে একটা ট্যাব খুলুন", Toast.LENGTH_LONG).show(); return }
        if (running) { Toast.makeText(a, "একটা কাজ চলছে — আগে থামান", Toast.LENGTH_LONG).show(); return }
        token = java.util.UUID.randomUUID().toString().replace("-", "")
        synchronized(rec) { while (rec.length() > 0) rec.remove(0) }
        recLast = 0L
        recording = true
        val u = web.url ?: ""
        if (u.startsWith("http")) injectRec(a, web)
        Toast.makeText(a, "🔴 রেকর্ড চালু — এখন সাইটে স্বাভাবিকভাবে কাজ করুন। শেষে পেজের লাল 'থামান' চাপুন।", Toast.LENGTH_LONG).show()
    }

    fun pageStop(a: Activity, t: String) {
        if (!recording || t != token) return
        ui.post { stopRecording(a, host?.web()) }
    }

    fun stopRecording(a: Activity, web: WebView?) {
        if (!recording) return
        recording = false
        try { web?.evaluateJavascript("try{var b=document.getElementById('__dm_bar');if(b)b.remove();window.__dmRecOn=null}catch(e){}", null) } catch (_: Exception) {}
        val n = rec.length()
        if (n == 0) { Toast.makeText(a, "কোনো ধাপ ধরা পড়েনি", Toast.LENGTH_LONG).show(); return }
        val copy = JSONArray(rec.toString())
        val et = EditText(a).apply { hint = "কাজের নাম (যেমন: পিডিএফ থেকে জেপিজি)"; setSingleLine(true) }
        AlertDialog.Builder(a).setTitle("$n টি ধাপ ধরা পড়েছে — নাম দিন").setView(et)
            .setPositiveButton("সেভ") { _, _ ->
                val nm = et.text.toString().trim().ifBlank { "কাজ " + (names(a).size + 1) }
                save(a, nm, copy)
                Toast.makeText(a, "✅ ‘$nm’ সেভ হয়েছে। Daily Agent-কে বলুন: চালাও $nm", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("বাতিল", null).show()
    }

    /** পেজ লোড শেষ: রেকর্ড চললে নতুন পেজেও রেকর্ডার ঢোকে; রিপ্লে "খুলুন" ধাপ লোডের অপেক্ষায় থাকলে জানায় */
    fun onPage(a: Activity, v: WebView, u: String) {
        if (!u.startsWith("http")) return
        pageLoaded = true
        if (recording) injectRec(a, v)
    }

    // ---------------- রিপ্লে ----------------
    @Volatile private var running = false
    private var runToken = 0
    private var pending: List<Item>? = null
    @Volatile private var chooserDone = false
    @Volatile private var pageLoaded = false
    private var jobWeb: WebView? = null
    private var lastCopy: File? = null
    var lastReport = ""

    private fun alive(c: Ctx) = running && c.run == runToken

    private fun first(c: JSONObject?, vararg keys: String): String {
        if (c == null) return ""
        for (k in keys) { val v = c.optString(k); if (v.isNotBlank()) return v }
        return ""
    }

    fun describe(s: JSONObject): String {
        val nm = first(s.optJSONObject("c"), "tx", "al", "ph", "nm", "css").take(40)
        return when (s.optString("t")) {
            "open" -> "খুলুন " + s.optString("u").removePrefix("https://").removePrefix("http://").take(40)
            "click" -> "চাপুন «$nm»"
            "type" -> "লিখুন «$nm»" + (if (s.optBoolean("sec")) " (পাসওয়ার্ড — বাদ)" else "")
            "select" -> "বাছুন «$nm»"
            "key" -> "Enter চাপুন"
            "file" -> "ফাইল দিন «$nm»"
            else -> s.optString("t")
        }
    }

    private fun parse(r: String?): JSONObject? = try {
        if (r == null || r == "null") null else JSONObject(JSONArray("[$r]").getString(0))
    } catch (_: Exception) { null }

    /** আসল আঙুলের চাপ: ফাইল-বাছাই ও ডাউনলোডের মতো কাজে "ব্যবহারকারীর চাপ" লাগে, শুধু JS-চাপে হয় না */
    private fun tap(w: WebView, fx: Double, fy: Double, iw: Double) {
        val sc = if (iw > 0.0 && w.width > 0) w.width / iw else 1.0
        val x = (fx * sc).toFloat(); val y = (fy * sc).toFloat()
        val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        d.source = InputDevice.SOURCE_TOUCHSCREEN
        w.dispatchTouchEvent(d); d.recycle()
        ui.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val u = MotionEvent.obtain(t2 - 60, t2, MotionEvent.ACTION_UP, x, y, 0)
            u.source = InputDevice.SOURCE_TOUCHSCREEN
            w.dispatchTouchEvent(u); u.recycle()
        }, 70L)
    }

    private fun begin(c: Ctx, i: Int, done: (String?) -> Unit) {
        if (!alive(c)) return
        if (i >= c.steps.length()) { done(null); return }
        val s = c.steps.getJSONObject(i)
        val gap = if (i == 0) 200L else minOf(s.optLong("g", 0L), 4000L) / 3 + 600L
        ui.postDelayed({ attempt(c, i, System.currentTimeMillis(), done) }, gap)
    }

    private fun waitLoad(c: Ctx, i: Int, t0: Long, done: (String?) -> Unit) {
        if (!alive(c)) return
        if (pageLoaded || System.currentTimeMillis() - t0 > 40000L) { ui.postDelayed({ begin(c, i + 1, done) }, 800L); return }
        ui.postDelayed({ waitLoad(c, i, t0, done) }, 400L)
    }

    private fun waitChooser(c: Ctx, i: Int, t0: Long, done: (String?) -> Unit) {
        if (!alive(c)) return
        if (chooserDone) { ui.postDelayed({ begin(c, i + 1, done) }, 1500L); return }
        if (System.currentTimeMillis() - t0 > 20000L) {
            pending = null
            done("ধাপ ${i + 1}: সাইট ফাইল-বাছাই খোলেনি (ফাইল দেওয়ার বোতাম চাপ ধরেনি)")
            return
        }
        ui.postDelayed({ waitChooser(c, i, t0, done) }, 300L)
    }

    private fun attempt(c: Ctx, i: Int, t0: Long, done: (String?) -> Unit) {
        if (!alive(c)) return
        val s = c.steps.getJSONObject(i)
        val kind = s.optString("t")
        fun fail(why: String) { done("ধাপ ${i + 1} (${describe(s)}) ব্যর্থ: $why") }
        fun retry(why: String, limit: Long) {
            if (System.currentTimeMillis() - t0 > limit) fail(why) else ui.postDelayed({ attempt(c, i, t0, done) }, 500L)
        }
        if (kind == "open") {
            val u = s.optString("u")
            pageLoaded = false
            val cw = host?.web()
            val w = if (cw != null && cw === jobWeb) { cw.loadUrl(u); cw } else host?.open(u)
            if (w == null) { fail("ট্যাব খোলা যায়নি"); return }
            jobWeb = w
            waitLoad(c, i, System.currentTimeMillis(), done)
            return
        }
        val w = host?.web()
        if (w == null) { fail("চালানোর মতো ট্যাব নেই"); return }
        if (kind == "file") { pending = c.files; chooserDone = false }
        w.evaluateJavascript(runJs(c.a) + ";__dmRun(" + s.toString() + ")") { r ->
            if (alive(c)) {
                val o = parse(r) ?: JSONObject()
                when (o.optString("s")) {
                    "ok" -> if (kind == "file") waitChooser(c, i, System.currentTimeMillis(), done) else begin(c, i + 1, done)
                    "tap" -> {
                        tap(w, o.optDouble("x"), o.optDouble("y"), o.optDouble("iw"))
                        if (kind == "file") waitChooser(c, i, System.currentTimeMillis(), done) else begin(c, i + 1, done)
                    }
                    "wait" -> { if (kind == "file") pending = null; retry("এলিমেন্ট পাওয়া যায়নি বা নিষ্ক্রিয় (" + o.optString("w") + ")", 30000L) }
                    else -> { if (kind == "file") pending = null; retry("পেজ উত্তর দেয়নি", 30000L) }
                }
            }
        }
    }

    private fun go(a: Activity, name: String, steps: JSONArray, files: List<Item>, perFile: Boolean) {
        running = true; runToken++
        val my = runToken
        val total = if (perFile) files.size else 1
        val log = StringBuilder()
        fun finish(msg: String) {
            running = false; pending = null
            lastReport = msg + "\n" + log
            Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
        }
        fun one(k: Int) {
            if (my != runToken || !running) return
            if (k >= total) { finish("✅ ‘$name’ শেষ হয়েছে" + (if (total > 1) " ($total বার)" else "")); return }
            val fl = if (perFile) listOf(files[k]) else files
            val nm = fl.firstOrNull()?.name ?: ""
            Toast.makeText(a, "▶ $name" + (if (total > 1) " (${k + 1}/$total: $nm)" else ""), Toast.LENGTH_SHORT).show()
            begin(Ctx(a, steps, fl, my), 0) { err ->
                if (err != null) { log.append(if (nm.isNotEmpty()) "[$nm] " else "").append(err).append('\n'); finish("⚠ ‘$name’ থেমে গেছে: $err") }
                else { log.append(if (nm.isNotEmpty()) "[$nm] ✓\n" else "✓\n"); ui.postDelayed({ one(k + 1) }, 1500L) }
            }
        }
        one(0)
    }

    fun stopRun(a: Context, why: String) {
        if (!running) return
        running = false; runToken++; pending = null
        Toast.makeText(a, why, Toast.LENGTH_SHORT).show()
    }

    fun start(a: Activity, name: String) {
        if (running) { Toast.makeText(a, "আগের কাজ চলছে — মেনু থেকে থামান", Toast.LENGTH_LONG).show(); return }
        if (recording) { Toast.makeText(a, "রেকর্ড চলছে — আগে থামান", Toast.LENGTH_LONG).show(); return }
        val m = load(a, name) ?: run { Toast.makeText(a, "‘$name’ নামে কাজ নেই", Toast.LENGTH_LONG).show(); return }
        val steps = m.optJSONArray("steps") ?: return
        var needFiles = false; var multiple = false; var acc = ""
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            if (s.optString("t") == "file") { needFiles = true; if (s.optBoolean("mul")) multiple = true; if (acc.isEmpty()) acc = s.optString("acc") }
        }
        if (!needFiles) { go(a, name, steps, emptyList(), false); return }
        askFiles(a, acc) { files ->
            if (files.isEmpty()) Toast.makeText(a, "কোনো ফাইল বাছা হয়নি", Toast.LENGTH_LONG).show()
            else go(a, name, steps, files, !multiple)
        }
    }

    // ---------------- ফাইল বাছাই ----------------
    private var filesCb: ((List<Item>) -> Unit)? = null
    private var filesAcc = ""

    private fun askFiles(a: Activity, acc: String, cb: (List<Item>) -> Unit) {
        filesCb = cb; filesAcc = acc
        AlertDialog.Builder(a).setTitle("ফোনের ফাইল কোথা থেকে নেব?")
            .setMessage("নম্বর অনুযায়ী (১, ২, ১০…) সাজিয়ে একটার পর একটা সাইটে দেওয়া হবে।\n\n(Android-এ মূল Downloads ফোল্ডার ‘ফোল্ডার’ হিসেবে বাছা যায় না; তখন ‘ফাইল বাছুন’ ব্যবহার করুন।)")
            .setPositiveButton("📁 ফোল্ডার") { _, _ ->
                try { a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER) }
                catch (_: Exception) { filesCb = null; Toast.makeText(a, "ফোল্ডার-বাছাই খোলা যায়নি", Toast.LENGTH_LONG).show() }
            }
            .setNeutralButton("📄 ফাইল বাছুন") { _, _ ->
                try {
                    a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_FILES)
                } catch (_: Exception) { filesCb = null; Toast.makeText(a, "ফাইল-বাছাই খোলা যায়নি", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("বাতিল") { _, _ -> filesCb = null }.show()
    }

    private fun accepts(acc: String, name: String, mime: String): Boolean {
        val toks = acc.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (toks.isEmpty()) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return toks.any { t ->
            when {
                t.startsWith(".") -> ext == t.drop(1) || (t == ".jpg" && ext == "jpeg") || (t == ".jpeg" && ext == "jpg")
                t.endsWith("/*") -> mime.startsWith(t.removeSuffix("*"))
                else -> mime == t || (t == "application/pdf" && ext == "pdf")
            }
        }
    }

    private fun nat(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val ra = re.findAll(a.lowercase()).map { it.value }.toList()
        val rb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val xs = x.trimStart('0'); val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size)
    }

    /** MainActivity.onActivityResult-এর শুরুতে ডাকুন; নিজের কোড হলে true */
    fun onResult(a: Activity, rc: Int, res: Int, data: Intent?): Boolean {
        if (rc != PICK_FOLDER && rc != PICK_FILES) return false
        val cb = filesCb; filesCb = null
        if (cb == null) return true
        if (res != Activity.RESULT_OK || data == null) { cb(emptyList()); return true }
        val acc = filesAcc
        Thread {
            val out = ArrayList<Item>()
            try {
                if (rc == PICK_FOLDER) {
                    val tree = data.data
                    if (tree != null) {
                        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                        a.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                            while (c.moveToNext()) {
                                val id = c.getString(0) ?: continue
                                val nm = c.getString(1) ?: continue
                                val mime = c.getString(2) ?: ""
                                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                                if (accepts(acc, nm, mime)) out.add(Item(nm, DocumentsContract.buildDocumentUriUsingTree(tree, id), mime))
                            }
                        }
                    }
                } else {
                    val uris = linkedSetOf<Uri>()
                    data.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) } }
                    data.data?.let { uris.add(it) }
                    for (u in uris) {
                        var nm = "file"
                        a.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0)?.let { nm = it }
                        }
                        out.add(Item(nm, u, a.contentResolver.getType(u) ?: ""))
                    }
                }
            } catch (_: Exception) {}
            val sorted = out.sortedWith(Comparator { x, y -> nat(x.name, y.name) })
            ui.post {
                if (sorted.isEmpty()) Toast.makeText(a, "মানানসই কোনো ফাইল পাওয়া যায়নি" + (if (acc.isNotBlank()) " (সাইট চায়: $acc)" else ""), Toast.LENGTH_LONG).show()
                else Toast.makeText(a, "${sorted.size} টি ফাইল: ${sorted.first().name} … ${sorted.last().name}", Toast.LENGTH_LONG).show()
                cb(sorted)
            }
        }.start()
        return true
    }

    /** onShowFileChooser-এর শুরুতে ডাকুন; রিপ্লে ফাইল রেডি রাখলে সাইটকে সেগুলো দেয় ও true ফেরত দেয় */
    fun fileChooser(a: Activity, cb: ValueCallback<Array<Uri>>): Boolean {
        val f = pending ?: return false
        pending = null
        Thread {
            val out = ArrayList<Uri>()
            try {
                lastCopy?.deleteRecursively()
                val dir = File(a.cacheDir, "up/m" + System.nanoTime()); dir.mkdirs()
                lastCopy = dir
                for ((n, item) in f.withIndex()) {
                    try {
                        val file = File(dir, item.name.replace('/', '_').ifBlank { "file$n" })
                        a.contentResolver.openInputStream(item.uri)?.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                        if (file.exists()) out.add(Uri.fromFile(file))
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            ui.post { cb.onReceiveValue(if (out.isEmpty()) null else out.toTypedArray()); chooserDone = true }
        }.start()
        return true
    }

    // ---------------- মেনু ও এজেন্ট ----------------
    fun dialog(a: Activity, web: WebView?) {
        val items = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        if (running) { items.add("⏹ চলমান কাজ থামান"); acts.add { stopRun(a, "থামানো হয়েছে") } }
        if (recording) { items.add("⏹ রেকর্ড থামান ও সেভ"); acts.add { stopRecording(a, web) } }
        else { items.add("🔴 নতুন রেকর্ড শুরু"); acts.add { startRecording(a, web) } }
        for (n in names(a)) { items.add("▶ $n"); acts.add { itemMenu(a, n) } }
        if (lastReport.isNotBlank()) { items.add("📋 শেষ কাজের রিপোর্ট"); acts.add { AlertDialog.Builder(a).setTitle("শেষ রিপোর্ট").setMessage(lastReport).setPositiveButton("ঠিক আছে", null).show() } }
        AlertDialog.Builder(a).setTitle("🎬 অটো-রেকর্ডার")
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("বন্ধ", null).show()
    }

    private fun itemMenu(a: Activity, n: String) {
        AlertDialog.Builder(a).setTitle(n)
            .setItems(arrayOf("▶ চালান", "📄 ধাপগুলো দেখুন", "🗑 মুছুন")) { _, i ->
                when (i) {
                    0 -> start(a, n)
                    1 -> {
                        val st = load(a, n)?.optJSONArray("steps")
                        val sb = StringBuilder()
                        if (st != null) for (k in 0 until st.length()) sb.append(k + 1).append(". ").append(describe(st.getJSONObject(k))).append('\n')
                        AlertDialog.Builder(a).setTitle("‘$n’ — ধাপ").setMessage(sb.toString()).setPositiveButton("ঠিক আছে", null).show()
                    }
                    else -> AlertDialog.Builder(a).setTitle("‘$n’ মুছবেন?")
                        .setPositiveButton("মুছুন") { _, _ -> delete(a, n) }.setNegativeButton("না", null).show()
                }
            }.show()
    }

    /** Daily Agent-এর কথা: "চালাও <কাজের নাম>"; মিললে উত্তর-লেখা ফেরত দেয়, নইলে null (তখন আগের মতোই চলে) */
    fun command(a: Activity, text: String): String? {
        val t = text.trim().lowercase()
        val verbs = listOf("চালাও", "চালান", "চালিয়ে", "চালা", "রান", "run", "শুরু")
        if (verbs.none { t.contains(it) }) return null
        val nm = names(a).filter { t.contains(it.lowercase()) }.maxByOrNull { it.length } ?: return null
        ui.post { start(a, nm) }
        return "▶ ‘$nm’ চালু করছি…"
    }
}
