package com.rahcare.termuxbrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class Tab(val web: WebView) {
    var title = "নতুন ট্যাব"
    var url = ""
    var jobKey: String? = null // কমান্ডের নিজস্ব ট্যাব হলে কাজের চাবি
    var site: String? = null   // ওয়ার্কস্পেস হলে তার আইডি (আলাদা লগইন-প্রোফাইলে চলে)
    var limitAt = 0L           // শেষ কখন "লিমিট শেষ" ধরা পড়েছে
    var stuckHits = 0          // পরপর কয়বার "আটকে আছে" লেখা দেখা গেছে
    var stuckReloadAt = 0L     // শেষ স্বয়ংক্রিয় রিলোডের সময়
    var stuckReloads = 0       // একটানা কয়বার রিলোড করা হয়েছে (অনন্ত চক্র এড়াতে)
    var mdSet = false      // setUserAgentMetadata বসানো আছে কিনা (বসানো না থাকলে মোবাইলে ছুঁই না)
    var deskScript: androidx.webkit.ScriptHandler? = null  // ডেস্কটপ-মোডের document-start স্ক্রিপ্ট (বন্ধ করলে সরানো হয়)
    var chatUrl = ""           // শেষ জানা চ্যাটের ঠিকানা (/chat/… বা /code/…)
    var limitHits = 0          // পরপর কয়বার লিমিটের লেখা দেখা গেছে (ভুল শনাক্ত এড়াতে ২ বার লাগে)
    var thumb: Bitmap? = null  // ট্যাব তালিকার ছোট প্রিভিউ
    var errUrl: String? = null // ত্রুটির পাতা দেখাচ্ছে হলে আসল ঠিকানা (নেট ফিরলে নিজে থেকে আবার খুলবে)
    var incognito = false      // ইনকগনিটো ট্যাব (সাময়িক আলাদা প্রোফাইল, ইতিহাস নেই)
    var profileName: String? = null  // ইনকগনিটো প্রোফাইলের নাম
    val isHome get() = url == Home.BASE || (url.startsWith(Home.BASE) && !url.startsWith(Home.JOB))
}

class MainActivity : Activity() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    companion object { const val REFRESH = "com.rahcare.termuxbrowser.REFRESH" }

    private val tabs = mutableListOf<Tab>()
    private var cur: Tab? = null
    private var desktop = false
    private lateinit var content: FrameLayout
    private lateinit var address: EditText
    private lateinit var countBox: TextView
    private lateinit var runBox: TextView
    private var refreshQueued = false
    private var lastOverlay = 0L
    private lateinit var progress: ProgressBar
    private var overlay: View? = null
    private var upd: Updater.Info? = null
    private var updBusy = false
    private var pendingInstall = false
    private lateinit var updBtn: TextView   // নিচের ভাসমান আপডেট বোতাম (AutoReply-এর মতো)
    private var updPct = -1
    private var updTxt = ""
    private lateinit var banner: TextView
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var fileCb: ValueCallback<Array<Uri>>? = null     // ওয়েবসাইটের "ফাইল বাছুন" অনুরোধ
    private var pendingWebPerm: PermissionRequest? = null     // ক্যামেরা/মাইকের অনুরোধ (Android অনুমতির অপেক্ষায়)

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { scheduleRefresh() }
    }

    private fun statusMark(j: Job) = when (j.status) { "running" -> "⏳ "; "ok" -> "✓ "; "stopped" -> "■ "; else -> "✗ " }

    /** লাইভ আপডেট বারবার এলে ২০০ms-এ একবার আঁকা */
    private fun scheduleRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        handler.postDelayed({ refreshQueued = false; refreshAll() }, 200)
    }

    /** সব ট্যাব, প্রগ্রেস বার, চলমান-সংখ্যা ও ট্যাব তালিকা এখনকার অবস্থায় আনে */
    private fun refreshAll() {
        val homeJs = "var e=document.getElementById('jobs');if(e)e.innerHTML=${JSONObject.quote(Home.jobsHtml())}"
        tabs.forEach { t ->
            val j = t.jobKey?.let { Jobs.find(it) }
            when {
                t.isHome -> t.web.evaluateJavascript(homeJs, null)
                j != null -> { t.title = statusMark(j) + j.title; t.web.evaluateJavascript("if(window.upd)upd(${Home.state(j)})", null) }
            }
        }
        val n = Jobs.running()
        runBox.visibility = if (n > 0) View.VISIBLE else View.GONE
        runBox.text = "⟳ $n"
        syncProgress(); updateBar()
        val now = System.currentTimeMillis()
        if (overlay != null && now - lastOverlay > 1000) showSwitcher()
    }

    /** চলমান (বা আটকে থাকা) কাজ জোর করে থামায়: Termux-এ প্রক্রিয়া মারে, তালিকায় "বন্ধ করা হয়েছে" দেখায় */
    private fun killJob(j: Job) {
        AlertDialog.Builder(this).setTitle("কাজ থামাবেন?")
            .setMessage("“${j.title}” Termux-এ থামানো হবে। এরপর তালিকা থেকে 🗑 দিয়ে মুছতে পারবেন।")
            .setPositiveButton("থামান") { _, _ ->
                Runner.kill(this, j)
                j.status = "stopped"; j.end = System.currentTimeMillis(); j.reattach = false
                j.out = (j.out + "\n(আপনি থামিয়েছেন)").trim()
                Jobs.save(this); scheduleRefresh()
            }
            .setNegativeButton("না", null).show()
    }

    /** শেষ হওয়া সব কাজ মুছে তালিকা পরিষ্কার করে (চলমানগুলো থাকে) */
    private fun clearFinishedJobs() {
        val n = Jobs.clearFinished()
        Jobs.save(this)
        Toast.makeText(this, if (n > 0) "$n টি শেষ হওয়া কাজ মুছেছে" else "মোছার মতো শেষ হওয়া কাজ নেই", Toast.LENGTH_SHORT).show()
        if (overlay != null) showSwitcher()
        scheduleRefresh()
    }

    /** কাজের ট্যাব খোলা থাকলে ওপরের প্রগ্রেস বার পেজ-লোডের মতো চলে: শতাংশ জানলে ভরে, নইলে ঘুরতে থাকে */
    private fun syncProgress() {
        val j = cur?.jobKey?.let { Jobs.find(it) } ?: return
        if (j.status == "running") {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = j.pct < 0
            if (j.pct >= 0) progress.progress = j.pct
        } else { progress.isIndeterminate = false; progress.visibility = View.GONE }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, r: Int = 20, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(2), stroke)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        AgentBus.catOverride = { id -> Store.prefs(this).getString("agcat_$id", "") }
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        desktop = Store.prefs(this).getBoolean("desktop", false)
        autoSwitch = Store.prefs(this).getBoolean("autoswitch", true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4)); setBackgroundColor(Color.WHITE)
        }
        fun icon(t: String, size: Float = 22f, click: () -> Unit) = TextView(this).apply {
            text = t; textSize = size; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#374151"))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(44)); setOnClickListener { click() }
        }
        bar.addView(icon("⌂", 26f) { openHome() })
        address = EditText(this).apply {
            setSingleLine(); textSize = 14f; hint = "সার্চ বা ঠিকানা লিখুন"; background = bg(Color.parseColor("#F1F3F4"), 22)
            setPadding(dp(16), 0, dp(16), 0); imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, a, e ->
                if (a == EditorInfo.IME_ACTION_GO || (e != null && e.keyCode == KeyEvent.KEYCODE_ENTER)) { go(text.toString()); true } else false
            }
            setOnFocusChangeListener { v, f -> if (f) (v as EditText).post { v.selectAll() } }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(icon("↻", 24f) { reloadCur() })
        bar.addView(icon("＋", 24f) { newTab(null); openHome() })
        runBox = TextView(this).apply {
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = bg(Color.parseColor("#F59E0B"), 13); visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(26)).apply { setMargins(dp(4), 0, dp(2), 0) }
            setOnClickListener { showSwitcher() }
        }
        bar.addView(runBox)
        countBox = TextView(this).apply {
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#374151"))
            background = bg(Color.WHITE, 6, Color.parseColor("#374151"))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(dp(8), 0, dp(8), 0) }
            setOnClickListener { val m = this@MainActivity; if (m.overlay?.parent != null) m.hideSwitcher() else { m.overlay = null; m.showSwitcher() } }
        }
        bar.addView(countBox)
        val dots = icon("⋮", 24f) {}
        dots.setOnClickListener { menu(dots) }
        bar.addView(dots)
        bar.elevation = dp(3).toFloat()
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        banner = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2563EB"))
            setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE; setOnClickListener { startUpdate() }
        }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F3F4F6")); setPadding(dp(8), dp(2), dp(4), dp(2)); visibility = View.GONE
        }
        findInput = EditText(this).apply {
            hint = "পেজে খুঁজুন"; setSingleLine(); textSize = 14f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { runFind(s?.toString() ?: "") }
            })
        }
        findCount = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#6B7280")); setPadding(dp(6), 0, dp(6), 0) }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        findBar.addView(findCount)
        findBar.addView(icon("▲", 16f) { cur?.web?.findNext(false) })
        findBar.addView(icon("▼", 16f) { cur?.web?.findNext(true) })
        findBar.addView(icon("✕", 18f) { closeFind() })
        root.addView(findBar, LinearLayout.LayoutParams(-1, -2))
        content = FrameLayout(this)
        // টেনে রিফ্রেশ: শুধু সাধারণ ওয়েবপেজে, পেজ একদম ওপরে থাকলে। চ্যাট-ওয়ার্কস্পেসে বন্ধ (ভেতরের স্ক্রলে ভুলে রিলোড হয়ে লেখা হারাত) — সেখানে ↻ বোতাম আছে
        swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            addView(content, ViewGroup.LayoutParams(-1, -1))
            setOnChildScrollUpCallback { _, _ -> val t = cur; t == null || t.site != null || t.jobKey != null || overlay != null || t.web.scrollY > 0 }
            setOnRefreshListener { reloadCur(); handler.postDelayed({ isRefreshing = false }, 1500L) }
        }
        root.addView(swipe, LinearLayout.LayoutParams(-1, 0, 1f))
        // নিচে ভাসমান আপডেট বোতাম (AutoReply-এর মতো): নতুন সংস্করণ থাকলে মূল পেজে দেখায়; চাপলে বোতামটাই প্রগ্রেস বার হয়ে যায়
        updBtn = object : TextView(this) {
            private val fp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
                color = Color.WHITE
                textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
            }
            override fun onDraw(c: Canvas) {
                if (updBusy) {
                    val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
                    fp.color = Color.parseColor("#93C5FD")
                    c.drawRoundRect(0f, 0f, w, h, r, r, fp)
                    if (updPct >= 0) {
                        fp.color = Color.parseColor("#1D4ED8")
                        c.save(); c.clipRect(0f, 0f, w * updPct / 100f, h)
                        c.drawRoundRect(0f, 0f, w, h, r, r, fp)
                        c.restore()
                    }
                }
                super.onDraw(c)
                if (updBusy && updTxt.isNotEmpty()) {
                    val y = height / 2f - (tp.descent() + tp.ascent()) / 2f
                    c.drawText(updTxt, width / 2f, y, tp)
                }
            }
        }.apply {
            text = "Update"; textSize = 22f; includeFontPadding = false
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            background = bg(Color.parseColor("#2563EB"), 26)
            elevation = dp(6).toFloat(); visibility = View.GONE
            setOnClickListener { if (!updBusy) startUpdate() }
        }
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(updBtn, FrameLayout.LayoutParams(dp(170), dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(27) })
        setContentView(frame)

        // আগের চালুর ইনকগনিটো প্রোফাইল (অ্যাপ হঠাৎ বন্ধ হলে থেকে যেতে পারে) মুছে দিই
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                ProfileStore.getInstance().allProfileNames.filter { it.startsWith("incog_") }.forEach { try { ProfileStore.getInstance().deleteProfile(it) } catch (_: Exception) {} }
        } catch (_: Exception) {}
        // একবারের পরিষ্কার: আগের স্বয়ংক্রিয় কপিতে মিশে যাওয়া পুরনো GitHub লগইন মুছে দিই, যাতে প্রতিটি জায়গায় নতুন করে ঠিক অ্যাকাউন্টে লগইন করা যায়
        if (!Store.prefs(this).getBoolean("gh_reset_v1", false)) {
            ghClearAll(); Store.prefs(this).edit().putBoolean("gh_reset_v1", true).apply()
            Toast.makeText(this, "GitHub লগইন রিসেট হয়েছে — প্রতিটি ওয়ার্কস্পেসে নিজের অ্যাকাউন্টে লগইন করুন", Toast.LENGTH_LONG).show()
        }
        watchNetwork()
        try { java.io.File(cacheDir, "up").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600 * 1000L) it.deleteRecursively() } } catch (_: Exception) {}
        restore()
        if (tabs.isEmpty()) { newTab(null); openHome() }
        handleIntent(intent)
    }

    /** ↻ বোতাম/টেনে রিফ্রেশ/মেনু — সব একই কাজ */
    private fun reloadCur() {
        val t = cur ?: return
        when {
            t.isHome -> loadHome(t)
            t.jobKey != null -> scheduleRefresh()
            else -> t.web.reload()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 4711) {
            val cb = fileCb; fileCb = null
            if (cb == null) return
            // একাধিক ছবি: clipData-র সব ছবি + data.data নিই
            val uris = linkedSetOf<Uri>()
            if (res == RESULT_OK && data != null) {
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) } }
                data.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) { cb.onReceiveValue(null); return }
            // বাছাইয়ের অ্যাপের অস্থায়ী অনুমতি হারিয়ে যেতে পারে (Chrome-এর মতো) — তাই ফাইলগুলো নিজের ক্যাশে কপি করে সেটা দিই
            Thread {
                val out = uris.map { copyPicked(it) ?: it }
                runOnUiThread { cb.onReceiveValue(out.toTypedArray()) }
            }.start()
        }
    }

    /** বাছাই করা ফাইল ক্যাশে নাম-সহ কপি করে file:// ঠিকানা দেয়; না পারলে null (তখন আসল ঠিকানাই যায়) */
    private fun copyPicked(u: Uri): Uri? = try {
        if (u.scheme == "file") null else {
            var name = "file"
            contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            name = name.replace('/', '_').ifBlank { "file" }
            val dir = java.io.File(cacheDir, "up/" + System.nanoTime()); dir.mkdirs()
            val f = java.io.File(dir, name)
            contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: throw Exception("empty")
            Uri.fromFile(f)
        }
    } catch (_: Exception) { null }

    /** কিবোর্ড/পেস্ট থেকে আসা ছবি পড়ে ওয়েবপেজে "পেস্ট" হিসেবে পাঠায় */
    private fun pasteImage(w: WebView, uri: Uri, mime0: String?) {
        val mime = if (mime0 != null && mime0.startsWith("image/")) mime0 else "image/png"
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("empty")
                if (bytes.size > 12 * 1024 * 1024) { runOnUiThread { Toast.makeText(this, "ছবিটা অনেক বড় (১২ MB-র বেশি)", Toast.LENGTH_LONG).show() }; return@Thread }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val ext = mime.substringAfter('/').substringBefore('+').ifBlank { "png" }
                val js = Sites.pasteImageJs(b64, mime, "image.$ext")
                runOnUiThread {
                    w.evaluateJavascript(js) { r ->
                        if (r == null || !(r.contains("pasted") || r.contains("input")))
                            Toast.makeText(this, "ছবি বসানো যায়নি — ＋ চেপে গ্যালারি থেকে দিন", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) { runOnUiThread { Toast.makeText(this, "ক্লিপবোর্ডের ছবি পড়া যায়নি", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    /** ওয়েবসাইট ক্যামেরা/মাইক চাইলে: আগে ব্যবহারকারীকে জিজ্ঞেস, তারপর Android-এর অনুমতি, তারপর সাইটকে দেওয়া */
    private fun askWebPermission(r: PermissionRequest) {
        val need = mutableListOf<String>(); val names = mutableListOf<String>()
        r.resources.forEach {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { need.add(android.Manifest.permission.CAMERA); names.add("ক্যামেরা") }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { need.add(android.Manifest.permission.RECORD_AUDIO); names.add("মাইক্রোফোন") }
            }
        }
        if (need.isEmpty()) { r.deny(); return }
        AlertDialog.Builder(this).setTitle("অনুমতি চাইছে")
            .setMessage("${r.origin.host ?: r.origin} এই সাইটটি ${names.joinToString(" ও ")} ব্যবহার করতে চায়। দেবেন?")
            .setPositiveButton("দিন") { _, _ ->
                val missing = need.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) r.grant(r.resources)
                else { pendingWebPerm = r; requestPermissions(missing.toTypedArray(), 4712) }
            }
            .setNegativeButton("না") { _, _ -> r.deny() }
            .setOnCancelListener { r.deny() }.show()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleIntent(i) }
    private fun handleIntent(i: Intent?) {
        val u = i?.data?.toString() ?: return
        i?.data = null   // আবার চালু হলে একই লিংক দ্বিতীয়বার না খোলার জন্য
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return
        newTab(null); go(u)
    }

    override fun onResume() {
        super.onResume()
        Updater.fg = true
        Reopen.done(this)   // আপডেটের পর অ্যাপ খুলে গেছে: "আবার খুলতে হবে" চিহ্ন ও নোটিফিকেশন মুছি
        AutoUpdate.fg = true; AutoUpdate.schedule(this)
        Updater.onFail = { runOnUiThread { if (updBusy) resetUpd() } }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rx, IntentFilter(REFRESH), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rx, IntentFilter(REFRESH))
        Live.onUpdate = { _ -> handler.post { scheduleRefresh() } }
        Keep.webUntil = 0L
        // অন্য অ্যাপ থেকে ফিরলে যেসব চলমান কাজের লাইভ সংযোগ ছিঁড়ে গেছে সেগুলো এখনই আবার জুড়ি
        try { Runner.reconnect(this, 3000L, true) } catch (_: Exception) {}
        retryErrorTabs()
        // ফিরে এলে প্রতিটি সাইটকে "আবার দেখো" সংকেত: ছেঁড়া স্ট্রিম থাকলে সাইট নিজেই উত্তর/অবস্থা আবার টেনে আনে
        tabs.forEach { t -> if (t.jobKey == null && !t.isHome && t.url.startsWith("http"))
            try { t.web.resumeTimers(); t.web.evaluateJavascript("try{window.dispatchEvent(new Event('online'));window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'))}catch(e){}", null) } catch (_: Exception) {} }
        // অনুমতি/ইনস্টল বদলে থাকলে হোম পেজ নতুন করে আঁকি
        cur?.let { if (it.isHome) loadHome(it) }
        scheduleRefresh()
        askRunPermission()
        askBackgroundSetup()
        handler.postDelayed({ if (!isFinishing) { termuxHealthCheck(); offerTermuxSetup() } }, 2000L)
        if (pendingInstall && packageManager.canRequestPackageInstalls()) { pendingInstall = false; startUpdate() }
        handler.removeCallbacks(updCheck); updCheck.run()
        handler.removeCallbacks(jobSaver); handler.postDelayed(jobSaver, 5000L)
        handler.removeCallbacks(siteWatcher); handler.postDelayed(siteWatcher, 6000L)
        handler.removeCallbacks(agentTick); handler.postDelayed(agentTick, 15000L)
    }

    // কোনো সময়ের বাধা নেই: অ্যাপ খুললেই খোঁজে, আর সামনে থাকলে প্রতি ২০ সেকেন্ডে আবার খোঁজে
    private val updCheck = object : Runnable {
        override fun run() {
            // সামনে থাকা অবস্থায় নিয়মিত অবস্থা সংরক্ষণ: আপডেট বসে প্রসেস মারা গেলেও ট্যাব/চ্যাট আগের জায়গায় ফেরে
            try { save() } catch (_: Exception) {}
            Thread { val i = Updater.check(); runOnUiThread { if (i != null) { upd = i.takeIf { it.v > Updater.curVer(this@MainActivity) } }; showBanner(); autoStartUpdate() } }.start()
            handler.postDelayed(this, 20000L)
        }
    }

    /** অ্যাপ খোলা অবস্থায় নতুন সংস্করণ এলে বোতাম না চাপলেও নিজে থেকে শুরু হয় (AutoReply-এর মতো): বোতামটাই প্রগ্রেস বার হয়ে যায়।
     *  প্রতি সংস্করণে একবারই; বাতিল করলে আবার জোর করে না, তখন বোতাম ব্যবহার করা যাবে। "অজানা উৎস" অনুমতি না থাকলে সেটিংস-পর্দা জোর করে খোলে না */
    private fun autoStartUpdate() {
        val u = upd ?: return
        if (updBusy || isFinishing || !packageManager.canRequestPackageInstalls()) return
        val p = Store.prefs(this)
        if (p.getInt("fg_auto_v", 0) == u.v) return
        p.edit().putInt("fg_auto_v", u.v).apply()
        startUpdate()
    }

    // ব্যানার আর নেই: নতুন সংস্করণ এলে নিচের বোতাম দেখায়, আর আপডেট নিজে নিজেও ব্যাকগ্রাউন্ডে হয়ে যায়
    private fun showBanner() { banner.visibility = View.GONE; refreshUpdBtn() }

    /** বোতাম যেকোনো ট্যাব/পেজের নিচে দেখায় (নতুন সংস্করণ থাকলে বা আপডেট চলার সময়); নতুন সংস্করণ না থাকলে লুকানো থাকে।
     *  শুধু ট্যাব-তালিকা খোলা থাকলে বা পূর্ণ-পর্দা ভিডিও চলার সময় লুকায় */
    private fun refreshUpdBtn() {
        if (!::updBtn.isInitialized) return
        val switcherOpen = overlay?.parent != null
        val show = (upd != null || updBusy) && !switcherOpen && customView == null
        if (!updBusy) updBtn.text = if (packageManager.canRequestPackageInstalls()) "Update" else "Allow update"
        updBtn.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun resetUpd() {
        updBusy = false; updPct = -1; updTxt = ""
        updBtn.setTextColor(Color.WHITE)
        updBtn.invalidate(); refreshUpdBtn()
    }

    private fun startUpdate() {
        val u = upd ?: return
        if (updBusy) return
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            Toast.makeText(this, "\"Allow from this source\" চালু করে ফিরে আসুন — আপডেট নিজেই শুরু হবে", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        // বোতামটাই প্রগ্রেস বার; অ্যাপ সামনে থাকায় নিশ্চিত-পর্দা স্বাভাবিক (নীরব ইনস্টল ব্যাকগ্রাউন্ডে নিজে হয়)
        updBusy = true; updPct = 0; updTxt = "0%"
        updBtn.setTextColor(Color.argb(115, 255, 255, 255))
        updBtn.invalidate(); refreshUpdBtn()
        runInstall(u, false)
    }

    private fun runInstall(u: Updater.Info, silent: Boolean) {
        try { save(true) } catch (_: Exception) {}   // ইনস্টলের ঠিক আগে অবস্থা সংরক্ষণ (সিঙ্ক্রোনাস: প্রসেস মরার আগেই ডিস্কে পৌঁছাক)
        Thread {
            val err = Updater.install(this, u.url, silent) { p -> runOnUiThread { updPct = p; updTxt = if (p < 100) "$p%" else "Installing…"; updBtn.invalidate() } }
            runOnUiThread {
                if (err != null) { resetUpd(); Toast.makeText(this, "আপডেট শুরু হয়নি ($err)", Toast.LENGTH_LONG).show() }
                else handler.postDelayed({ if (updBusy) resetUpd() }, 180000L)
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        Updater.fg = false
        AutoUpdate.fg = false; AutoUpdate.kick(this, 60_000L)
        handler.removeCallbacks(updCheck); handler.removeCallbacks(jobSaver); handler.removeCallbacks(siteWatcher); handler.removeCallbacks(agentTick)
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        Live.onUpdate = null
        // সাইটের ট্যাব খোলা থাকলে ব্যাকগ্রাউন্ডেও সচল রাখি (ফোরগ্রাউন্ড সার্ভিস + CPU/Wi-Fi জাগানো)
        if (Store.prefs(this).getBoolean("keep_web", true) && tabs.any { it.jobKey == null && !it.isHome && it.url.startsWith("http") }) {
            Keep.webUntil = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
            KeepAliveService.start(this)
        }
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { netCb?.let { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        tabs.forEach { it.web.destroy() }
    }

    // ---------- নেট ফিরলে ত্রুটির পাতা নিজে থেকে আবার খোলা ----------
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    private fun watchNetwork() {
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: android.net.Network) { handler.post { retryErrorTabs() } }
            }
            cm.registerDefaultNetworkCallback(cb); netCb = cb
        } catch (_: Exception) {}
    }
    private fun retryErrorTabs() {
        tabs.toList().forEach { t ->
            val u = t.errUrl
            if (u != null) { t.errUrl = null; try { t.web.loadUrl(u) } catch (_: Exception) {} }
        }
    }

    // ---------- ট্যাব ----------
    private fun newTab(url: String?, site: String? = null, incognito: Boolean = false, reuseProfile: String? = null): Tab {
        val w = object : WebView(this@MainActivity) {
            // কিবোর্ডের (Gboard ইত্যাদি) ক্লিপবোর্ড/স্ক্রিনশট থেকে ছবি পাঠানো: ওয়েবভিউকে জানাতে হয় যে এই ঘর ছবি নিতে পারে
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val ic = super.onCreateInputConnection(outAttrs) ?: return null
                EditorInfoCompat.setContentMimeTypes(outAttrs, arrayOf("image/png", "image/jpeg", "image/gif", "image/webp"))
                return InputConnectionCompat.createWrapper(ic, outAttrs) { info, flags, _ ->
                    if ((flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                        try { info.requestPermission() } catch (_: Exception) { return@createWrapper false }
                    }
                    pasteImage(this, info.contentUri, info.description.getMimeType(0))
                    true
                }
            }
        }
        // আলাদা লগইন-প্রোফাইল WebView-এর অন্য কোনো কাজের আগেই বসাতে হয়; না বসলে লগইন মিশে যেত, তাই তখন সাইট লোডই করি না
        var siteOk: String? = null
        if (site != null) {
            siteOk = try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    val pn = Sites.profile(site)
                    ProfileStore.getInstance().getOrCreateProfile(pn)
                    WebViewCompat.setProfile(w, pn); site
                } else null
            } catch (_: Exception) { null }
            if (siteOk == null) Toast.makeText(this, "আলাদা লগইন চালু করা যায়নি — Android System WebView আপডেট করুন", Toast.LENGTH_LONG).show()
        }
        var incName: String? = null
        if (incognito) {
            incName = try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    val nm = reuseProfile ?: ("incog_" + System.nanoTime())
                    ProfileStore.getInstance().getOrCreateProfile(nm); WebViewCompat.setProfile(w, nm); nm
                } else null
            } catch (_: Exception) { null }
        }
        val t = Tab(w)
        t.site = siteOk
        t.profileName = incName
        t.incognito = incName != null
        if (t.incognito) t.title = "🕶 ইনকগনিটো"
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
        }
        applyDesktop(t); applyView(t)
        // ব্যাকগ্রাউন্ডে গেলেও ওয়েব-রেন্ডারার "গুরুত্বপূর্ণ" থাকে, যাতে সাইটের কাজ/স্ট্রিম থামে না
        if (android.os.Build.VERSION.SDK_INT >= 26) try { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) } catch (_: Exception) {}
        try { if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(w, Browse.KEEP_JS, setOf("*")) } catch (_: Exception) {}
        w.setFindListener { idx, n, _ -> if (t === cur) findCount.text = if (n == 0) "০" else "${idx + 1}/$n" }
        w.setOnLongClickListener { _ -> handleLongPress(t) }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): android.webkit.WebResourceResponse? {
                val us = r.url.toString()
                return when {
                    us.startsWith(AGENT) -> agentLoader.shouldInterceptRequest(r.url)
                    else -> null
                }
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                when (u.scheme) {
                    "http", "https" -> return false
                    "termux" -> {
                        // শুধু আমাদের নিজস্ব হোম পেজ থেকেই Termux কমান্ড চলবে; অন্য কোনো সাইট পারবে না
                        // v.url ডেটা-লোড করা পেজে অনির্ভরযোগ্য (null/about:blank হতে পারে), তাই ট্যাবের নিজের url-ও দেখি
                        if (v.url?.startsWith(Home.BASE) == true || t.url.startsWith(Home.BASE)) {
                            handler.post { try { termuxAction(u, t) } catch (e: Exception) { Toast.makeText(this@MainActivity, "ত্রুটি: ${e.message}", Toast.LENGTH_LONG).show() } }
                        } else Toast.makeText(this@MainActivity, "এই পেজ থেকে Termux কমান্ড চলে না", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    "tel", "mailto" -> try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                }
                return true
            }
            override fun onReceivedHttpAuthRequest(v: WebView, h: HttpAuthHandler, host: String, realm: String) {
                // শুধু নিজের ফোনের ওয়েব-টার্মিনালের জন্য পাসওয়ার্ড দিই
                if (host == "127.0.0.1") h.proceed("tb", webPw()) else h.cancel()
            }
            // সংযোগ/DNS/SSL ত্রুটিতে Chrome-এর মতো ত্রুটির পাতা (আবার চেষ্টার লিংকসহ)
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                val c = e.errorCode
                if (c == WebViewClient.ERROR_HOST_LOOKUP || c == WebViewClient.ERROR_CONNECT || c == WebViewClient.ERROR_TIMEOUT || c == WebViewClient.ERROR_IO || c == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                    val u = r.url.toString()
                    t.errUrl = u
                    v.loadDataWithBaseURL(u, Browse.errorHtml(u, e.description.toString()), "text/html", "utf-8", u)
                }
            }
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                h.cancel()
                if (e.url == v.url) v.loadDataWithBaseURL(e.url, Browse.errorHtml(e.url, "সংযোগ নিরাপদ নয় (SSL): সাইটের সার্টিফিকেট যাচাই করা যায়নি"), "text/html", "utf-8", e.url)
            }
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { t.url = u; if (t === cur) updateBar() }
            override fun onPageFinished(v: WebView, u: String) {
                if (v.title != Browse.ERR_TITLE) t.errUrl = null
                if (u.startsWith("http")) v.evaluateJavascript(Browse.KEEP_JS, null)
                // সাইটভেদে অটো-ক্লিক (যেমন claude.ai-এর অনুমতি-কার্ড) — সব AutoClick.kt-তে; স্ক্রিপ্ট নিজেই হোস্ট যাচাই করে
                AutoClick.pageJs(u)?.let { js -> v.evaluateJavascript(js, null) }
                AutoLogin.onPage(this@MainActivity, t.site, u, v) { m -> Toast.makeText(this@MainActivity, m, Toast.LENGTH_LONG).show() }
                if (u.startsWith(AGENT)) {
                    // অ্যাপে সংরক্ষিত GitHub টোকেন (এনক্রিপ্টেড) থাকলে হাব-পেজকে একবার দিই; পেজে আগে থেকে টোকেন থাকলে ছুঁই না
                    val tk = Secret.get(this@MainActivity)
                    if (tk.isNotEmpty()) {
                        val q = org.json.JSONObject.quote(tk)
                        v.evaluateJavascript("(function(){try{if(!localStorage.getItem('gh_tok')){localStorage.setItem('gh_tok',$q);location.reload()}}catch(e){}})()", null)
                    }
                    pushAgents(); detectAgentVersions()
                }
                t.url = u; if (t.isHome) t.title = "Termux হোম" else if (t.jobKey == null && !v.title.isNullOrBlank()) t.title = v.title!!
                t.site?.let { sid -> if (!(Uri.parse(u).host ?: "").endsWith("github.com")) detectEmail(t, sid, true) }
                if (!t.incognito && !t.isHome && t.jobKey == null && u.startsWith("http") && !u.startsWith(AGENT) && v.title != Browse.ERR_TITLE) History.add(this@MainActivity, v.title ?: u, u)
                if (t === cur) { updateBar(); swipe.isRefreshing = false }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (t === cur && t.jobKey == null) { progress.isIndeterminate = false; progress.progress = p; progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE }
            }
            override fun onReceivedTitle(v: WebView, title: String?) { if (!t.isHome && t.jobKey == null && !title.isNullOrBlank()) t.title = title }
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: Message): Boolean {
                val n = newTab(null, t.site, t.incognito, t.profileName)  // পপআপ (যেমন লগইন) একই প্রোফাইলে খুলুক
                (m.obj as WebView.WebViewTransport).webView = n.web; m.sendToTarget(); return true
            }
            override fun onCloseWindow(v: WebView) { closeTab(t) }
            // ছবি/অডিও/ভিডিও/যেকোনো ফাইল আপলোড: ফোনের ফাইল-বাছাইয়ের পর্দা খোলে (accept ও একাধিক-নির্বাচন সাইটের চাওয়া অনুযায়ী)
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return try { startActivityForResult(p.createIntent(), 4711); true }
                catch (_: Exception) { fileCb = null; Toast.makeText(this@MainActivity, "ফাইল বাছাইয়ের অ্যাপ পাওয়া যায়নি", Toast.LENGTH_LONG).show(); false }
            }
            // ক্যামেরা/মাইক চাইলে ব্যবহারকারীকে জিজ্ঞেস করে তবেই (সাইটকে নিজে থেকে দেওয়া হয় না)
            override fun onPermissionRequest(r: PermissionRequest) { handler.post { askWebPermission(r) } }
            override fun onPermissionRequestCanceled(r: PermissionRequest) { if (pendingWebPerm === r) pendingWebPerm = null }
            override fun onJsAlert(v: WebView, url: String?, message: String?, r: JsResult): Boolean = jsAlert(url, message, r)
            override fun onJsConfirm(v: WebView, url: String?, message: String?, r: JsResult): Boolean = jsConfirm(url, message, r)
            override fun onJsPrompt(v: WebView, url: String?, message: String?, defaultValue: String?, r: JsPromptResult): Boolean = jsPrompt(url, message, defaultValue, r)
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { showFullscreen(view, callback) }
            override fun onHideCustomView() { hideFullscreen() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { handler.post { askGeo(origin, callback) } }
        }
        w.setDownloadListener { u, ua, cd, mime, _ -> enqueueDownload(t, u, ua, cd, mime) }
        tabs.add(t); select(t)
        if (url != null && !(site != null && siteOk == null) && !(incognito && !t.incognito)) w.loadUrl(url)
        return t
    }

    /** Google লগইন মোড (মোবাইল মোডে): 0 = WebView-এর নিজস্ব; 1 = Chrome-সদৃশ (UA, Client Hints, X-Requested-With সব একসাথে মেলানো); 2 = ১ + window.chrome ইত্যাদি */
    private fun gmode(): Int = if (desktop) 0 else Store.prefs(this).getInt("gmode", 0)

    private fun applyDesktop(t: Tab) {
        val ds = t.web.settings
        val ua = WebSettings.getDefaultUserAgent(this)
        //  আসল WebView-এর Chrome ভার্সনই বসে (পুরনো/ভুয়া ভার্সন দেখলে সাইট আটকায়)
        val ver = Regex("Chrome/([0-9.]+)").find(ua)?.groupValues?.get(1) ?: "131.0.0.0"
        val major = ver.substringBefore('.')
        val gm = gmode()
        fun bv(brand: String, mj: String, fv: String) = androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand(brand).setMajorVersion(mj).setFullVersion(fv).build()
        val brands = listOf(bv("Chromium", major, ver), bv("Google Chrome", major, ver), bv("Not_A Brand", "24", "24.0.0.0"))
        ds.userAgentString = if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
            else if (gm >= 1) ua.replace("; wv)", ")").replace("Version/4.0 ", "") else ua
        //  Client Hints: UA-র সাথে মিল থাকতে হবে (Google দুটো মিলিয়ে দেখে — মিল না থাকলে WebView/বট ধরে)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val md = if (desktop) androidx.webkit.UserAgentMetadata.Builder().setBrandVersionList(brands)
                    .setFullVersion(ver).setPlatform("Linux").setPlatformVersion("6.5.0").setArchitecture("x86").setBitness(64).setModel("").setMobile(false).build()
                else if (gm >= 1) {
                    val av = Regex("Android ([0-9.]+)").find(ua)?.groupValues?.get(1) ?: "14"
                    val pv = av.split('.').let { l -> (l + List(3) { "0" }).take(3).joinToString(".") }
                    val model = Regex("Android [0-9.]+; ([^;)]*?)(?: Build/[^;)]*)?[;)]").find(ua)?.groupValues?.get(1)?.trim() ?: ""
                    androidx.webkit.UserAgentMetadata.Builder().setBrandVersionList(brands).setFullVersion(ver)
                        .setPlatform("Android").setPlatformVersion(pv).setModel(model).setMobile(true).build()
                } else null
                if (md != null) { androidx.webkit.WebSettingsCompat.setUserAgentMetadata(ds, md); t.mdSet = true }
                else if (t.mdSet) { androidx.webkit.WebSettingsCompat.setUserAgentMetadata(ds, androidx.webkit.UserAgentMetadata.Builder().build()); t.mdSet = false }
            }
        } catch (_: Exception) {}
        //  X-Requested-With (অ্যাপের প্যাকেজ নাম) — মোড ১/২-এ বাদ, মোড ০-তে WebView-এর স্বাভাবিক আচরণ
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(ds, if (!desktop && gm >= 1) emptySet() else setOf("*"))
        } catch (_: Exception) {}
        //  ডেস্কটপ লেআউট (width=1280), navigator.platform ও userAgentData: পেজ লোডের শুরুতেই বসে
        try {
            t.deskScript?.let { try { it.remove() } catch (_: Exception) {} }; t.deskScript = null
            val scr = if (desktop) DesktopMode.JS else if (gm >= 2) ChromeShim.JS else null
            if (scr != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                t.deskScript = WebViewCompat.addDocumentStartJavaScript(t.web, scr, setOf("https://*", "http://*"))
        } catch (_: Exception) {}
    }

    /** ট্যাবের ছোট ছবি তোলে (ট্যাব তালিকায় ক্রোমের মতো প্রিভিউ দেখাতে) */
    private fun snap(t: Tab) {
        try {
            val w = t.web.width; val h = t.web.height
            if (w <= 0 || h <= 0) return
            val sc = 0.4f
            val b = Bitmap.createBitmap(maxOf(1, (w * sc).toInt()), maxOf(1, (h * sc).toInt()), Bitmap.Config.RGB_565)
            val c = Canvas(b); c.drawColor(Color.WHITE); c.scale(sc, sc); t.web.draw(c)
            t.thumb = b
        } catch (_: Exception) {}
    }

    private fun select(t: Tab) {
        if (overlay == null) cur?.let { snap(it) }
        cur = t; hideSwitcher()
        content.removeAllViews()
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        content.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        if (t.jobKey == null) { progress.isIndeterminate = false; progress.visibility = View.GONE }
        updateBar(); syncProgress()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.remove(t); t.web.destroy()
        t.profileName?.let { pn -> if (tabs.none { o -> o.profileName == pn }) try { ProfileStore.getInstance().deleteProfile(pn) } catch (_: Exception) {} }
        if (tabs.isEmpty()) { newTab(null); openHome(); return }
        if (cur === t) select(tabs[minOf(i, tabs.size - 1)]) else updateBar()
        if (overlay != null) showSwitcher()
    }

    private fun updateBar() {
        countBox.text = tabs.size.toString()
        refreshUpdBtn()
        val t = cur ?: return
        if (!address.hasFocus()) address.setText(if (t.isHome || t.url == "about:blank") "" else if (t.jobKey != null) "⚙ " + t.title else t.url)
        address.hint = if (t.isHome) "Termux হোম — সার্চ বা ঠিকানা লিখুন" else "সার্চ বা ঠিকানা লিখুন"
    }

    private fun loadHome(t: Tab) {
        t.url = Home.BASE; t.title = "Termux হোম"
        t.web.loadDataWithBaseURL(Home.BASE, Home.html(this), "text/html", "utf-8", null)
        if (t === cur) updateBar()
    }

    private fun openHome() { cur?.let { loadHome(it) } }

    private fun go(input: String) {
        val q = input.trim(); if (q.isEmpty()) return
        val u = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            !q.contains(" ") && (q.startsWith("localhost") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").containsMatchIn(q)) -> "http://$q"
            !q.contains(" ") && q.contains(".") -> "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
        address.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        cur?.web?.loadUrl(u)
    }

    // ---------- Termux ----------
    private var askedRun = false

    private fun webPw(): String {
        val sp = Store.prefs(this)
        var p = sp.getString("webpw", null)
        if (p == null) {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"; val r = java.security.SecureRandom()
            p = (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
            sp.edit().putString("webpw", p).apply()
        }
        return p
    }

    /** ttyd চালু হলে (পোর্ট ৭৬৮১ খুললে) নতুন ট্যাবে টার্মিনাল খোলে */
    private fun waitWebTerm() {
        Thread {
            var up = false
            for (i in 1..120) {
                try { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 7681), 400) }; up = true; break } catch (_: Exception) { Thread.sleep(1000) }
            }
            runOnUiThread {
                if (up) { newTab(null); go("http://127.0.0.1:7681/") }
                else Toast.makeText(this, "ওয়েব-টার্মিনাল চালু হয়নি — কাজের ট্যাবে ত্রুটি দেখুন", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Termux-এর RUN_COMMAND অনুমতি রানটাইমে চায়; নতুন ইনস্টলে এটা না দিলে কোনো আইটেম চলে না */
    private fun askRunPermission(force: Boolean = false) {
        if (!Runner.installed(this) || Runner.hasPermission(this)) return
        if (askedRun && !force) return
        askedRun = true
        try { requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"), 77) } catch (_: Exception) {}
    }

    // ---------- ক্রমানুসারে কমান্ড (একটা শেষ হলে পরেরটা নিজে চলে; ব্যর্থ হলে নিজে ঠিক করে একবার আবার চেষ্টা) ----------
    private val chainQ = ArrayList<Cmd>()
    private var chainJob: Job? = null
    private var chainTries = 0
    private var chainPaused = false   // Termux সংযোগ সেটআপের অপেক্ষায়; ঠিক হলে নিজে আবার চলে

    private fun chooseChain() {
        if (!Runner.installed(this)) { Toast.makeText(this, "আগে F-Droid থেকে Termux ইনস্টল করুন", Toast.LENGTH_LONG).show(); return }
        if (chainQ.isNotEmpty()) { Toast.makeText(this, "আগের ক্রম এখনো চলছে", Toast.LENGTH_SHORT).show(); return }
        // যেগুলোর বিশেষ ব্যবস্থা লাগে (ডেস্কটপ মোড, ওয়েব-টার্মিনাল) সেগুলো বাদ; Termux-পর্দায় চলা কমান্ডেরও কাজ-ট্র্যাকিং নেই
        val all = Store.all(this).filter { it.background && it.id != "d10" && it.id != "d11" && it.id != "d12" }
        val sel = BooleanArray(all.size)
        AlertDialog.Builder(this).setTitle("ক্রমানুসারে চালাতে বাছুন (তালিকার ক্রমে চলবে)")
            .setMultiChoiceItems(all.map { it.title }.toTypedArray(), sel) { _, i, on -> sel[i] = on }
            .setPositiveButton("▶ শুরু") { _, _ ->
                val pick = all.filterIndexed { i, _ -> sel[i] }
                if (pick.isEmpty()) Toast.makeText(this, "কিছু বাছা হয়নি", Toast.LENGTH_SHORT).show() else startChain(pick)
            }.setNegativeButton("বাতিল", null).show()
    }

    private fun startChain(list: List<Cmd>) {
        chainQ.clear(); chainQ.addAll(list); chainJob = null; chainTries = 0; chainPaused = false
        Toast.makeText(this, "${list.size}টি কমান্ড একটার পর একটা চলবে", Toast.LENGTH_SHORT).show()
        chainNext()
    }

    private fun chainNext() {
        val cmd = chainQ.firstOrNull() ?: run { chainJob = null; Toast.makeText(this, "সব কমান্ড শেষ ✓", Toast.LENGTH_LONG).show(); return }
        // পুনঃচেষ্টায় আগে আটকে থাকা apt/dpkg থামিয়ে প্যাকেজ-ডেটাবেস মেরামত করি
        val toRun = if (chainTries > 0) cmd.copy(script = "pkill -x apt 2>/dev/null; pkill -x apt-get 2>/dev/null; pkill -x dpkg 2>/dev/null; sleep 1; tb_fix\n" + cmd.script) else cmd
        val (job, err) = Runner.run(this, toRun)
        if (err != null) {
            chainQ.clear(); chainJob = null
            AlertDialog.Builder(this).setTitle("ক্রম থেমেছে").setMessage(err).setPositiveButton("ঠিক আছে", null).show()
            return
        }
        chainJob = job
        if (job == null) { chainQ.removeAt(0); chainNext(); return }
        openJobTab(job); chainWatch()
    }

    private fun chainWatch() {
        handler.postDelayed({
            val j = chainJob ?: return@postDelayed
            if (j.status == "running") { chainWatch(); return@postDelayed }
            val cmd = chainQ.firstOrNull()
            chainJob = null
            when {
                j.status == "ok" -> { if (chainQ.isNotEmpty()) chainQ.removeAt(0); chainTries = 0; chainNext() }
                j.status == "stopped" -> { chainQ.clear(); Toast.makeText(this, "ক্রম বন্ধ — কাজ থামানো হয়েছিল", Toast.LENGTH_LONG).show() }
                Runner.pending != null && Runner.pending?.id == cmd?.id -> chainPaused = true   // সংযোগ সেটআপ শেষ হলে নিজে আবার চলবে
                chainTries < 1 -> { chainTries++; Toast.makeText(this, "ব্যর্থ — নিজে ঠিক করে আবার চেষ্টা হচ্ছে", Toast.LENGTH_LONG).show(); chainNext() }
                else -> {
                    val t = cmd?.title ?: ""
                    chainQ.clear()
                    AlertDialog.Builder(this).setTitle("ক্রম থেমেছে")
                        .setMessage("\"$t\" দুইবার চেষ্টায়ও হয়নি। কাজের ট্যাবে কারণ দেখুন; ঠিক হলে আবার চালান।")
                        .setPositiveButton("ঠিক আছে", null).show()
                }
            }
        }, 2500L)
    }

    private var termuxProbed = false

    /** অ্যাপ খোলার সময় একবার (আর সেটআপের পর ফিরলে) Termux-এর সংযোগ যাচাই: ডেটা ক্লিয়ার হয়ে allow-external-apps হারালে নিজে ধরে সেটআপ প্রস্তাব করে; ঠিক হলে আটকে থাকা কমান্ড নিজে আবার চালায় */
    private fun termuxHealthCheck() {
        if (!Runner.installed(this) || !Runner.hasPermission(this)) return
        val pend = Runner.pending
        val fresh = pend != null && System.currentTimeMillis() - Runner.pendingAt < 15 * 60 * 1000
        if (termuxProbed && !fresh) return
        if (Jobs.list.any { it.status == "running" }) return
        termuxProbed = true
        Thread {
            val r = Runner.check(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                cur?.let { if (it.isHome) loadHome(it) }
                if (r == null) {
                    val id = if (fresh) pend?.id else null
                    Runner.pending = null
                    if (chainPaused) { chainPaused = false; chainTries = 0; Toast.makeText(this, "Termux সংযুক্ত হয়েছে — ক্রম আবার চলছে", Toast.LENGTH_SHORT).show(); chainNext() }
                    else if (id != null) { Toast.makeText(this, "Termux সংযুক্ত হয়েছে — কমান্ডটা আবার চালানো হচ্ছে", Toast.LENGTH_SHORT).show(); runById(id) }
                } else if (r == "perm" || r.contains("allow-external-apps", true)) offerTermuxSetup()
            }
        }.start()
    }

    private var termuxSetupAsked = false

    /** সেটআপ কমান্ড ক্লিপবোর্ডে কপি করে Termux খোলে — ব্যবহারকারী শুধু Paste করে Enter চাপবে (Termux অন্য অ্যাপকে নিজের ফোল্ডারে লিখতে দেয় না, তাই এটুকু হাতে করতে হয়) */
    private fun setupTermux() {
        if (!Runner.installed(this)) { Toast.makeText(this, "আগে F-Droid থেকে Termux ইনস্টল করুন", Toast.LENGTH_LONG).show(); return }
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("termux-setup", Runner.SETUP_CMD))
        Toast.makeText(this, "কমান্ড কপি হয়েছে — Termux-এ স্ক্রিন লম্বা চেপে Paste করে Enter চাপুন", Toast.LENGTH_LONG).show()
        if (!Runner.openTermux(this)) Toast.makeText(this, "Termux খোলা যায়নি — নিজে খুলে Paste করুন", Toast.LENGTH_LONG).show()
    }

    /** Termux ইনস্টল ও অনুমতি আছে অথচ এখনো সংযোগ নিশ্চিত হয়নি — অ্যাপ চালুর সময় একবার এক-চাপের সেটআপ প্রস্তাব করে */
    private fun offerTermuxSetup() {
        val sp = Store.prefs(this)
        if (termuxSetupAsked || !Runner.installed(this) || !Runner.hasPermission(this) || Runner.verified(this) || sp.getBoolean("termux_nomore", false)) return
        termuxSetupAsked = true
        AlertDialog.Builder(this).setTitle("Termux-এর সাথে সংযোগ")
            .setMessage("একবার এই ধাপ সারলেই Termux সব সময়ের জন্য সংযুক্ত থাকবে:\n১. নিচের বোতামে চাপুন — কমান্ড কপি হয়ে Termux খুলবে\n২. Termux-এ স্ক্রিন লম্বা চেপে Paste করে Enter চাপুন\n৩. কাজ না হলে Termux পুরো বন্ধ করে আবার খুলুন\n\nএরপর এখান থেকে যেকোনো কমান্ড চালালেই সংযোগ নিশ্চিত হবে, আর এই বার্তা আসবে না।")
            .setPositiveButton("⚡ এক চাপে সেটআপ") { _, _ -> setupTermux() }
            .setNegativeButton("পরে", null)
            .setNeutralButton("আর দেখাবেন না") { _, _ -> sp.edit().putBoolean("termux_nomore", true).apply() }.show()
    }

    /** AutoReply-এর মতো: নোটিফিকেশন অনুমতি + ব্যাটারি সীমাবদ্ধতা তুলে নেওয়া, যাতে ব্যাকগ্রাউন্ডে কাজ না থামে */
    private fun askBackgroundSetup() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 78) } catch (_: Exception) {}
        val pm = getSystemService(android.os.PowerManager::class.java)
        val sp = Store.prefs(this)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !sp.getBoolean("bat_asked", false)) {
            sp.edit().putBoolean("bat_asked", true).apply()
            AlertDialog.Builder(this).setTitle("ব্যাকগ্রাউন্ডে চালু রাখুন")
                .setMessage("অ্যাপ মিনিমাইজ করলেও কমান্ড যেন চলতে থাকে, তাই ব্যাটারি সীমাবদ্ধতা তুলে দিন (পরের পর্দায় \"অনুমতি দিন/Allow\" চাপুন)।")
                .setPositiveButton("ঠিক আছে") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }.setNegativeButton("পরে", null).show()
        } else if (!ClickGate.enabled(this) && !sp.getBoolean("a11y_asked", false)) {
            // একবারই: আপডেটের "Install" নিশ্চিত-পর্দা নিজে চাপার জন্য (Android এটা নিজে থেকে চালু করতে দেয় না)
            sp.edit().putBoolean("a11y_asked", true).apply()
            AlertDialog.Builder(this).setTitle("আপডেট পুরোপুরি স্বয়ংক্রিয় করুন")
                .setMessage("Android আপডেটের সময় একটা \"Install\" পর্দা দেখায়। সেটা নিজে চেপে দিতে একবার চালু করুন:\n১. নিচের বোতামে চাপুন\n২. তালিকায় \"Daily Browser\" খুলে চালু করুন\n\nএই সার্ভিস শুধু এই অ্যাপের নিজের আপডেটের ঠিক পরে, ইনস্টলার পর্দায় \"Install/Update\" বোতাম চাপে — আর কিছু পড়ে না, চাপে না।\n(\"Restricted setting\" বার্তা এলে: অ্যাপ info › ⋮ › Allow restricted settings, তারপর আবার চালু করুন)")
                .setPositiveButton("সেটিংসে যান") { _, _ -> try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} }
                .setNegativeButton("পরে", null).show()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (rc == 4712) {
            val r = pendingWebPerm; pendingWebPerm = null
            if (r != null) {
                if (g.isNotEmpty() && g.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) r.grant(r.resources)
                else { r.deny(); Toast.makeText(this, "অনুমতি পাওয়া যায়নি: ⋮ › অ্যাপ অনুমতি থেকে ক্যামেরা/মাইক দিন", Toast.LENGTH_LONG).show() }
            }
        }
        if (rc == 4713) {
            val gp = pendingGeo; pendingGeo = null
            gp?.second?.invoke(gp.first, g.isNotEmpty() && g.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }, false)
        }
        if (rc == 77) {
            cur?.let { if (it.isHome) loadHome(it) }
            if (g.isNotEmpty() && g[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "অনুমতি না দিলে আইটেম চলবে না: ⋮ › অ্যাপ অনুমতি থেকে দিন", Toast.LENGTH_LONG).show()
        }
    }

    /** একটি কমান্ড (id ধরে) চালায় — হোমের বোতাম আর সংযোগ ঠিক হওয়ার পর স্বয়ংক্রিয় পুনঃচেষ্টা দুটোই এটা ব্যবহার করে */
    private fun runById(id: String) {
        val cmd = Store.all(this).firstOrNull { it.id == id }
        if (cmd == null) { Toast.makeText(this, "কমান্ড পাওয়া যায়নি, হোম রিফ্রেশ করুন", Toast.LENGTH_SHORT).show(); return }
        if (Runner.installed(this) && !Runner.hasPermission(this)) { askRunPermission(true); return }
        if (cmd.id == "d10" && !x11Installed()) {
            AlertDialog.Builder(this).setTitle("Termux:X11 অ্যাপও লাগবে")
                .setMessage("কম্পিউটার মোডের পর্দা দেখাতে আলাদা Termux:X11 অ্যাপ দরকার। এখন নামিয়ে ইনস্টল করব? (ইনস্টল শেষে কম্পিউটার মোডে আবার চাপুন)")
                .setPositiveButton("ইনস্টল") { _, _ -> installX11() }
                .setNegativeButton("বাতিল", null).show()
            return
        }
        val run = if (cmd.id == "d12") cmd.copy(script = cmd.script.replace("@@WEBPW@@", webPw())) else cmd
        val (job, err) = Runner.run(this, run)
        if (err == null && cmd.id == "d12") waitWebTerm()
        if (err == null && cmd.id == "d10" && job != null) openX11WhenReady(job)
        if (err != null) AlertDialog.Builder(this).setTitle("চালানো যায়নি").setMessage(err).setPositiveButton("ঠিক আছে", null).show()
        else if (job != null) openJobTab(job)
        else Toast.makeText(this, "Termux স্ক্রিনে চলছে: ${cmd.title}", Toast.LENGTH_SHORT).show()
    }

    private fun termuxAction(u: Uri, t: Tab) {
        val segs = u.pathSegments.ifEmpty { listOf(u.host ?: "") }
        val kind = u.host ?: ""
        val id = segs.lastOrNull() ?: ""
        when (kind) {
            "setup" -> setupTermux()
            "run" -> runById(id)
            "chain" -> chooseChain()
            "job" -> Jobs.find(id)?.let { openJobTab(it) }
            "clear" -> clearFinishedJobs()
            "kill" -> Jobs.find(id)?.let { killJob(it) }
            "site" -> openSite(id)
            "siteedit" -> editSite(Sites.all(this).firstOrNull { it.id == id })
            "edit" -> edit(Store.all(this).firstOrNull { it.id == id })
        }
    }

    /** কমান্ডের জন্য নিজস্ব ট্যাব (আগে খোলা থাকলে সেটাতেই যায়) */
    private fun openJobTab(j: Job) {
        tabs.firstOrNull { it.jobKey == j.key }?.let { select(it); scheduleRefresh(); return }
        val t = newTab(null)
        t.jobKey = j.key; t.url = Home.JOB + j.key; t.title = statusMark(j) + j.title
        t.web.loadDataWithBaseURL(t.url, Home.jobHtml(j), "text/html", "utf-8", null)
        select(t); scheduleRefresh()
    }

    private fun edit(cmd: Cmd?) {
        val list = Store.all(this)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val t = EditText(this).apply { hint = "বাটনের নাম"; setText(cmd?.title ?: "") }
        val s = EditText(this).apply { hint = "কমান্ড (bash)"; typeface = Typeface.MONOSPACE; setText(cmd?.script ?: ""); minLines = 3; gravity = Gravity.TOP }
        val b = CheckBox(this).apply { text = "Termux টার্মিনাল স্ক্রিনে দেখাও (ঐচ্ছিক — না দিলে ব্যাকগ্রাউন্ডে চলবে)"; isChecked = cmd?.background == false }
        l.addView(t); l.addView(s); l.addView(b)
        val d = AlertDialog.Builder(this).setTitle(if (cmd == null) "নতুন কমান্ড" else "কমান্ড সম্পাদনা").setView(l)
            .setPositiveButton("সেভ") { _, _ ->
                val title = t.text.toString().trim(); val script = s.text.toString().trim()
                if (title.isEmpty() || script.isEmpty()) return@setPositiveButton
                if (cmd == null) list.add(Cmd("c${System.currentTimeMillis()}", title, script, !b.isChecked))
                else list.firstOrNull { it.id == cmd.id }?.let { it.title = title; it.script = script; it.background = !b.isChecked }
                Store.save(this, list); refreshHomes()
            }.setNegativeButton("বাতিল", null)
        if (cmd != null) d.setNeutralButton("মুছুন") { _, _ -> list.removeAll { it.id == cmd.id }; Store.save(this, list); refreshHomes() }
        d.show()
    }

    // ---------- ওয়ার্কস্পেস (যেকোনো সাইট, আলাদা লগইন) ----------
    private var limitDialogOpen = false
    private var autoSwitch = true
    /** যে ওয়ার্কস্পেসের লিমিট শেষ হয়েছে সেটা কতক্ষণ আবার বাছা হবে না (ওয়ার্কস্পেস-আইডি → সময়) */
    private val siteLimited = HashMap<String, Long>()

    // ---------- ব্রাউজারের সাধারণ ফিচার (Chrome-এর মতো) ----------
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    /** সেভ করা লেখার আকার ট্যাবে প্রয়োগ করে */
    private fun applyView(t: Tab) {
        try { t.web.settings.textZoom = Store.prefs(this).getInt("textzoom", 100) } catch (_: Exception) {}
    }

    private fun urlOk(t: Tab?) = t != null && !t.isHome && t.jobKey == null && t.url.startsWith("http")

    /** চলতি ট্যাবে (কাজের ট্যাব হলে নতুন ট্যাবে) ঠিকানা খোলে */
    private fun openUrl(u: String) {
        val t = cur
        if (t == null || t.jobKey != null) newTab(u) else t.web.loadUrl(u)
    }

    // JS alert/confirm/prompt: না দেখালে অনেক সাইটের লগইন/ফর্ম ঠিকমতো চলে না
    private fun jsAlert(url: String?, msg: String?, r: JsResult): Boolean = try {
        AlertDialog.Builder(this).setTitle(Uri.parse(url ?: "").host ?: "পেজ").setMessage(msg ?: "")
            .setPositiveButton("ঠিক আছে") { _, _ -> r.confirm() }.setOnCancelListener { r.cancel() }.show(); true
    } catch (_: Exception) { r.cancel(); true }

    private fun jsConfirm(url: String?, msg: String?, r: JsResult): Boolean = try {
        AlertDialog.Builder(this).setTitle(Uri.parse(url ?: "").host ?: "পেজ").setMessage(msg ?: "")
            .setPositiveButton("ঠিক আছে") { _, _ -> r.confirm() }.setNegativeButton("বাতিল") { _, _ -> r.cancel() }
            .setOnCancelListener { r.cancel() }.show(); true
    } catch (_: Exception) { r.cancel(); true }

    private fun jsPrompt(url: String?, msg: String?, def: String?, r: JsPromptResult): Boolean = try {
        val e = EditText(this).apply { setText(def ?: ""); setSingleLine() }
        val l = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        AlertDialog.Builder(this).setTitle(Uri.parse(url ?: "").host ?: "পেজ").setMessage(msg ?: "").setView(l)
            .setPositiveButton("ঠিক আছে") { _, _ -> r.confirm(e.text.toString()) }
            .setNegativeButton("বাতিল") { _, _ -> r.cancel() }.setOnCancelListener { r.cancel() }.show(); true
    } catch (_: Exception) { r.cancel(); true }

    // পূর্ণ-পর্দা ভিডিও (YouTube ইত্যাদি)
    private fun showFullscreen(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) { cb.onCustomViewHidden(); return }
        customView = v; customCb = cb
        refreshUpdBtn()
        v.setBackgroundColor(Color.BLACK)
        (window.decorView as ViewGroup).addView(v, ViewGroup.LayoutParams(-1, -1))
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun hideFullscreen() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customCb?.onCustomViewHidden(); customCb = null
        refreshUpdBtn()
    }

    // সাইট অবস্থান চাইলে: আগে ব্যবহারকারীকে জিজ্ঞেস, তারপর Android-এর অনুমতি
    private fun askGeo(origin: String, cb: GeolocationPermissions.Callback) {
        AlertDialog.Builder(this).setTitle("অবস্থান জানতে চায়")
            .setMessage("${Uri.parse(origin).host ?: origin} আপনার অবস্থান জানতে চায়। দেবেন?")
            .setPositiveButton("দিন") { _, _ ->
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) cb.invoke(origin, true, false)
                else { pendingGeo = Pair(origin, cb); requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 4713) }
            }
            .setNegativeButton("না") { _, _ -> cb.invoke(origin, false, false) }
            .setOnCancelListener { cb.invoke(origin, false, false) }.show()
    }

    // লিংক/ছবিতে লম্বা চাপ: Chrome-এর মতো মেনু
    private fun handleLongPress(t: Tab): Boolean {
        val h = t.web.hitTestResult
        when (h.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Message.obtain(object : android.os.Handler(android.os.Looper.getMainLooper()) {
                    override fun handleMessage(m: Message) { linkMenu(t, m.data?.getString("url") ?: h.extra, m.data?.getString("src")) }
                })
                t.web.requestFocusNodeHref(msg); return true
            }
            WebView.HitTestResult.IMAGE_TYPE -> { linkMenu(t, null, h.extra); return true }
        }
        return false
    }

    private fun linkMenu(t: Tab, link: String?, img: String?) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (!link.isNullOrBlank()) {
            items.add(Pair("নতুন ট্যাবে খুলুন") { newTab(link, t.site, t.incognito, t.profileName) })
            items.add(Pair("লিংক কপি করুন") { copyText(link) })
            items.add(Pair("লিংক শেয়ার করুন") { shareText(link) })
        }
        if (!img.isNullOrBlank()) {
            items.add(Pair("ছবি ডাউনলোড করুন") { enqueueDownload(t, img, t.web.settings.userAgentString, null, null) })
            items.add(Pair("ছবি নতুন ট্যাবে খুলুন") { newTab(img, t.site, t.incognito, t.profileName) })
        }
        if (items.isEmpty()) return
        AlertDialog.Builder(this).setTitle((link ?: img ?: "").take(80))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun copyText(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("link", s))
        Toast.makeText(this, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(s: String, subject: String? = null) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s)
        if (subject != null) i.putExtra(Intent.EXTRA_SUBJECT, subject)
        try { startActivity(Intent.createChooser(i, "শেয়ার করুন")) } catch (_: Exception) {}
    }

    /** ওয়ার্কস্পেস/ইনকগনিটো প্রোফাইলের কুকি (লগইন করা সাইট থেকে ডাউনলোডে লাগে) */
    private fun cookiesFor(t: Tab?, u: String): String? = try {
        val sid = t?.site; val pn = t?.profileName
        val cm: CookieManager = (if (sid != null) ghProfile(sid)?.cookieManager
            else if (pn != null) ProfileStore.getInstance().getProfile(pn)?.cookieManager else null) ?: CookieManager.getInstance()
        cm.getCookie(u)
    } catch (_: Exception) { null }

    /** ডাউনলোড: ফোনের পাবলিক Downloads ফোল্ডারে (না পারলে অ্যাপের নিজস্ব ফোল্ডারে), লগইন-কুকিসহ */
    private fun enqueueDownload(t: Tab?, u: String, ua: String?, cd: String?, mime: String?) {
        if (u.startsWith("blob:") || u.startsWith("data:")) { Toast.makeText(this, "এই ধরনের (blob/data) ডাউনলোড এখনো সমর্থিত নয়", Toast.LENGTH_LONG).show(); return }
        try {
            val name = URLUtil.guessFileName(u, cd, mime)
            val ck = cookiesFor(t, u)
            fun build(pub: Boolean): DownloadManager.Request {
                val rq = DownloadManager.Request(Uri.parse(u))
                if (!mime.isNullOrBlank()) rq.setMimeType(mime)
                if (!ua.isNullOrBlank()) rq.addRequestHeader("User-Agent", ua)
                if (!ck.isNullOrBlank()) rq.addRequestHeader("Cookie", ck)
                rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (pub) rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                else rq.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
                return rq
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            try { dm.enqueue(build(true)) } catch (_: Exception) { dm.enqueue(build(false)) }
            Toast.makeText(this, "ডাউনলোড শুরু হয়েছে: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "ডাউনলোড ব্যর্থ", Toast.LENGTH_SHORT).show() }
    }

    // পেজে খুঁজুন
    private fun showFind() {
        findBar.visibility = View.VISIBLE; findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(findInput, 0)
    }

    private fun closeFind() {
        cur?.web?.clearMatches(); findBar.visibility = View.GONE; findCount.text = ""
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    private fun runFind(q: String) {
        val w = cur?.web ?: return
        if (q.isEmpty()) { w.clearMatches(); findCount.text = "" } else w.findAllAsync(q)
    }

    // বুকমার্ক ও ইতিহাস
    private fun showBookmarks() {
        val l = Bookmarks.all(this)
        if (l.isEmpty()) { Toast.makeText(this, "এখনো কোনো বুকমার্ক নেই — ⋮ › ☆ চেপে যোগ করুন", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("বুকমার্ক")
            .setItems(l.map { it.first.take(40) + "\n" + it.second.take(60) }.toTypedArray()) { _, i -> openUrl(l[i].second) }
            .setNeutralButton("মুছুন…") { _, _ ->
                AlertDialog.Builder(this).setTitle("কোনটা মুছবেন?")
                    .setItems(l.map { it.first.take(50) }.toTypedArray()) { _, i -> Bookmarks.remove(this, l[i].second); refreshHomes(); Toast.makeText(this, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("বাতিল", null).show()
            }.setNegativeButton("বন্ধ", null).show()
    }

    private fun showHistory() {
        val l = History.all(this).take(150)
        if (l.isEmpty()) { Toast.makeText(this, "ইতিহাস খালি", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("ইতিহাস")
            .setItems(l.map { it.first.take(40) + "\n" + it.second.take(60) }.toTypedArray()) { _, i -> openUrl(l[i].second) }
            .setNeutralButton("সব মুছুন") { _, _ -> History.clear(this); Toast.makeText(this, "ইতিহাস মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("বন্ধ", null).show()
    }

    private fun printCur() {
        val t = cur ?: return
        try {
            val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
            val n = t.title.ifBlank { "page" }
            pm.print(n, t.web.createPrintDocumentAdapter(n), android.print.PrintAttributes.Builder().build())
        } catch (_: Exception) { Toast.makeText(this, "প্রিন্ট চালু করা যায়নি", Toast.LENGTH_SHORT).show() }
    }

    /** ইনকগনিটো: আলাদা সাময়িক প্রোফাইল — কুকি/লগইন/ক্যাশ আলাদা, ট্যাব বন্ধ করলে মুছে যায়, ইতিহাসে আসে না */
    private fun newIncognito() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            Toast.makeText(this, "এই ফোনের WebView পুরনো — Play Store থেকে Android System WebView আপডেট করুন", Toast.LENGTH_LONG).show(); return
        }
        val t = newTab(null, null, true)
        if (t.incognito) t.web.loadDataWithBaseURL("about:blank", Browse.incognitoHtml(), "text/html", "utf-8", null)
    }

    private fun pickTextSize() {
        val opts = arrayOf("৮০%", "১০০% (সাধারণ)", "১২০%", "১৫০%", "২০০%"); val vals = intArrayOf(80, 100, 120, 150, 200)
        AlertDialog.Builder(this).setTitle("লেখার আকার").setItems(opts) { _, i ->
            Store.prefs(this).edit().putInt("textzoom", vals[i]).apply(); tabs.forEach { applyView(it) }
        }.show()
    }

    // ---------- ওয়ার্কস্পেসের নাম = লগইন করা জিমেইল ----------
    private val emailOk = HashSet<String>()                 // এই চালুতে যে ওয়ার্কস্পেসের ইমেইল পাওয়া গেছে
    private val emailTries = HashMap<String, Int>()         // চেষ্টার সংখ্যা (লগইন না থাকলে অকারণে বারবার না ডাকতে)
    private val emailLast = HashMap<String, Long>()         // শেষ চেষ্টার সময়

    private fun autoName(id: String) = Store.prefs(this).getBoolean("autoname_$id", true)

    /** ওয়ার্কস্পেসে লগইন করা অ্যাকাউন্টের ইমেইল সাইটের নিজের API থেকে পড়ে ওয়ার্কস্পেসের নাম বসায়।
     *  ব্যবহারকারী হাতে নাম দিলে (autoname=false) বা ইমেইল আগেই পাওয়া গেলে কিছু করে না */
    private fun detectEmail(t: Tab, sid: String, force: Boolean) {
        if (!autoName(sid) || emailOk.contains(sid)) return
        val now = System.currentTimeMillis()
        val n = emailTries[sid] ?: 0
        if (n >= 25 || (!force && now - (emailLast[sid] ?: 0L) < 20000L) || now - (emailLast[sid] ?: 0L) < 4000L) return
        emailTries[sid] = n + 1; emailLast[sid] = now
        t.web.evaluateJavascript(Sites.EMAIL_JS, null)
        handler.postDelayed({
            if (!tabs.contains(t)) return@postDelayed
            t.web.evaluateJavascript(Sites.EMAIL_READ_JS) { raw ->
                val em = Sites.unquote(raw).trim()
                if (em.contains("@") && em.length < 120) applyEmail(sid, em)
            }
        }, 3000L)
    }

    private fun applyEmail(sid: String, em: String) {
        emailOk.add(sid)
        val list = Sites.all(this)
        val s = list.firstOrNull { it.id == sid } ?: return
        if (s.name != em) { s.name = em; Sites.save(this, list); refreshHomes() }
    }

    /** ওয়ার্কস্পেস খোলে (আগে খোলা থাকলে সেটাতেই যায়); fresh=true হলে নতুন চ্যাট লোড করে */
    private fun openSite(id: String, fresh: Boolean = false, url: String? = null): Tab? {
        val site = Sites.all(this).firstOrNull { it.id == id } ?: return null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            AlertDialog.Builder(this).setTitle("আলাদা লগইন চালু করা যাচ্ছে না")
                .setMessage("এই ফোনের Android System WebView পুরনো। Play Store থেকে \"Android System WebView\" (ও Chrome) আপডেট করে আবার চেষ্টা করুন। নইলে সব সাইটের লগইন মিশে যেত, তাই খোলা হচ্ছে না।")
                .setPositiveButton("ঠিক আছে", null).show()
            return null
        }
        val ex = tabs.firstOrNull { it.site == id }
        if (ex != null) { select(ex); if (fresh) ex.web.loadUrl(url ?: site.url); return ex }
        return newTab(url ?: site.url, id)
    }

    /** খোলা যেকোনো সাইটকে এক চাপে ওয়ার্কস্পেস বানায় (নাম = সাইটের ডোমেইন) এবং আলাদা লগইনে নতুন ট্যাবে খোলে */
    private fun makeWorkspace(t: Tab) {
        val host = (Uri.parse(t.url).host ?: return).removePrefix("www.")
        val list = Sites.all(this)
        val site = list.firstOrNull { (Uri.parse(it.url).host ?: "").removePrefix("www.") == host }
            ?: Site("s${System.currentTimeMillis()}", host, t.url).also { list.add(it); Sites.save(this, list) }
        refreshHomes()
        val nt = openSite(site.id) ?: return
        if (nt !== t) closeTab(t)
        Toast.makeText(this, "“${site.name}” আলাদা লগইনে খোলা হয়েছে — এখানে নতুন করে লগইন করুন (নাম বদলাতে হোমে ✎)", Toast.LENGTH_LONG).show()
    }

    private fun editSite(site: Site?) {
        val list = Sites.all(this)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val n = EditText(this).apply { hint = "নাম (খালি রাখলে লগইন করা জিমেইল নিজে বসবে)"; setText(site?.name ?: "") }
        val u = EditText(this).apply { hint = "ওয়েবসাইট (যেমন claude.ai)"; setText(site?.url ?: ""); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val catKeys = listOf("", "audio", "image", "video", "none")
        val catSp = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("ক্যাটাগরি: স্বয়ংক্রিয়", "অডিও এজেন্ট", "ইমেজ এজেন্ট", "ভিডিও এজেন্ট", "অন্যান্য (এজেন্ট নয়)"))
            setSelection(catKeys.indexOf(site?.let { Store.prefs(this@MainActivity).getString("agcat_${it.id}", "") } ?: "").coerceAtLeast(0))
        }
        l.addView(n); l.addView(u); l.addView(catSp)
        val d = AlertDialog.Builder(this).setTitle(if (site == null) "নতুন ওয়ার্কস্পেস" else "ওয়ার্কস্পেস সম্পাদনা").setView(l)
            .setPositiveButton("সেভ") { _, _ ->
                val typed = n.text.toString().trim(); val url = Sites.normalizeUrl(u.text.toString())
                if (url.isEmpty()) return@setPositiveButton
                val sp = Store.prefs(this)
                val catKey = catKeys[catSp.selectedItemPosition]
                if (site == null) {
                    val id = "s${System.currentTimeMillis()}"
                    val host = (Uri.parse(url).host ?: url).removePrefix("www.")
                    val ns = Site(id, typed.ifEmpty { host }, url); list.add(ns)
                    sp.edit().putBoolean("autoname_$id", typed.isEmpty()).apply()
                    sp.edit().putString("agcat_$id", catKey).apply()
                    AgentBus.agentOf(ns)?.let { ag -> Toast.makeText(this, "${AgentBus.label(ag)} এজেন্ট হিসেবে ধরা হয়েছে — হোমে “${AgentBus.label(ag)}” হেডিংয়ের নিচে দেখাবে", Toast.LENGTH_LONG).show() }
                } else list.firstOrNull { it.id == site.id }?.let { s ->
                    sp.edit().putString("agcat_${s.id}", catKey).apply()
                    // নতুন নাম লিখলে সেটাই থাকবে (আর জিমেইল বসবে না); খালি রাখলে আবার জিমেইল বসবে
                    if (typed.isNotEmpty() && typed != s.name) { s.name = typed; sp.edit().putBoolean("autoname_${s.id}", false).apply() }
                    else if (typed.isEmpty()) { sp.edit().putBoolean("autoname_${s.id}", true).apply(); emailOk.remove(s.id); emailTries.remove(s.id) }
                    s.url = url
                }
                Sites.save(this, list); refreshHomes()
            }.setNegativeButton("বাতিল", null)
        if (site != null) d.setNeutralButton("মুছুন") { _, _ ->
            AlertDialog.Builder(this).setTitle("“${site.name}” মুছবেন?")
                .setMessage("এই ওয়ার্কস্পেসের লগইন ও সেশনও মুছে যাবে।")
                .setPositiveButton("মুছুন") { _, _ ->
                    tabs.filter { it.site == site.id }.forEach { closeTab(it) }
                    list.removeAll { it.id == site.id }; Sites.save(this, list)
                    try { ProfileStore.getInstance().deleteProfile(Sites.profile(site.id)) } catch (_: Exception) {}
                    refreshHomes()
                }.setNegativeButton("না", null).show()
        }
        d.show()
    }

    /** সামনের ওয়ার্কস্পেস ট্যাবে "লিমিট শেষ" ধরনের লেখা এসেছে কিনা প্রতি ৬ সেকেন্ডে দেখে (যেকোনো সাইটে একই নিয়ম)।
     *  ভুল শনাক্ত এড়াতে পরপর ২ বার দেখা যেতে হয় এবং একই ট্যাবে ১০ মিনিটে একবারের বেশি চলে না */
    private val siteWatcher = object : Runnable {
        override fun run() {
            // কোনো ত্রুটি হলেও নিজেকে আবার নির্ধারণ করে — নইলে একবার ব্যর্থ হলে ওয়াচার চিরতরে থেমে যেত
            try {
                val t = cur
                if (t != null && t.site != null && overlay == null) detectEmail(t, t.site!!, false)
                // নেট ধীর হয়ে সাইট "Checking for a response…" দেখিয়ে আটকে গেলে নিজে সারায়: আগে নরম চেষ্টা, না হলে চ্যাট রিলোড
                if (t != null && t.jobKey == null && !t.isHome && t.url.startsWith("http") && overlay == null) checkStuck(t)
                if (t != null && t.site != null && overlay == null && !limitDialogOpen && System.currentTimeMillis() - t.limitAt > 600000L) {
                    t.web.evaluateJavascript(Sites.LIMIT_JS) { raw ->
                        val hit = Sites.unquote(raw)
                        if (hit.isBlank() || t !== cur) t.limitHits = 0
                        else {
                            t.limitHits++
                            // আপগ্রেড পপআপের লেখা স্পষ্ট সংকেত — সাথে সাথে সুইচ; অন্যগুলো পরপর ২ বার দেখলে
                            val strong = Regex("hit your|upgrade to keep chatting", RegexOption.IGNORE_CASE).containsMatchIn(hit)
                            if ((strong || t.limitHits >= 2) && !limitDialogOpen && System.currentTimeMillis() - t.limitAt > 600000L) {
                                t.limitHits = 0; t.limitAt = System.currentTimeMillis(); onLimit(t, hit)
                            }
                        }
                        t.web.evaluateJavascript(Sites.DISMISS_JS, null)
                    }
                } else if (t != null && t.site != null && overlay == null) {
                    t.web.evaluateJavascript(Sites.DISMISS_JS, null)
                }
                // Claude বলছে GitHub টুল/কানেকশন নেই → নিজে কানেক্ট করার চেষ্টা (সেটিংসে চালু ও ইমেইল/পাসওয়ার্ড দেওয়া থাকলে)
                if (t != null && t.site != null && overlay == null && gcTab == null && gcAuto() && GitCred.has(this@MainActivity)
                    && (t.web.url ?: "").startsWith("https://claude.ai/") && System.currentTimeMillis() - gcLastRun > 20 * 60000L) {
                    t.web.evaluateJavascript(GitConnect.DETECT_JS) { raw ->
                        val hit = Sites.unquote(raw)
                        if (hit.isNotBlank() && t === cur && gcTab == null && hit != gcLastHit) { gcLastHit = hit; gcBegin(t) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                handler.postDelayed(this, 6000L)
            }
        }
    }

    // ---------- GitHub অটো-কানেক্ট ----------
    private var gcTab: Tab? = null
    private var gcStartAt = 0L
    private var gcLastRun = 0L
    private var gcLastHit = ""
    private var gcBack = ""
    private var gcLogins = 0
    private var gcInstalled = false
    private var gcConnectClicks = 0
    private var gcPending: String? = null   // switch-er por box-e boshano lekha; connect check er por abar boshiye pathano hobe
    private var gcPendSend = false
    private var gcCodeTries = 0
    private var gcAskedNotif = false
    private fun gcAuto() = Store.prefs(this).getBoolean("gc_auto", false)

    private fun gcBegin(t: Tab, pending: String? = null, pendSend: Boolean = false, back: String? = null) {
        gcTab = t; gcStartAt = System.currentTimeMillis(); gcLastRun = gcStartAt
        gcBack = (t.web.url ?: t.url).takeIf { it.contains("/chat/") || it.contains("/code") } ?: t.chatUrl
        gcLogins = 0; gcInstalled = false; gcConnectClicks = 0
        gcPending = pending; gcPendSend = pendSend; gcCodeTries = 0; gcAskedNotif = false
        if (!back.isNullOrBlank()) gcBack = back
        Toast.makeText(this, "Claude-এ GitHub কানেক্ট নেই — নিজে কানেক্ট করা হচ্ছে…", Toast.LENGTH_LONG).show()
        t.web.loadUrl(GitConnect.CONNECTORS)
        handler.postDelayed(gcTick, 5000L)
    }

    private fun gcFinish(t: Tab, ok: Boolean, msg: String) {
        gcTab = null; gcLastRun = System.currentTimeMillis()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        val pend = gcPending; val ps = gcPendSend; gcPending = null; gcPendSend = false
        if (pend != null) {
            //  workspace switch flow: chat-e fire giye lekha abar boshao; GitHub connected hole (ok) tobei send
            if (tabs.contains(t)) {
                t.web.loadUrl(if (gcBack.startsWith("http")) gcBack else "https://claude.ai/new")
                fillInput(t, pend, 0, ps && ok)
            }
        } else if (ok && gcBack.startsWith("http") && tabs.contains(t)) {
            t.web.loadUrl(gcBack)
            fillInput(t, "GitHub এখন কানেক্ট হয়েছে। এই চ্যাটে আগের কাজটা GitHub টুল দিয়ে চালিয়ে যাও।", 0, true)
        }
    }

    private val gcTick = object : Runnable {
        override fun run() {
            val t = gcTab ?: return
            try {
                if (!tabs.contains(t)) { gcTab = null; return }
                if (System.currentTimeMillis() - gcStartAt > 5 * 60000L) { gcFinish(t, false, "GitHub কানেক্ট: সময় শেষ — হাতে শেষ করুন"); return }
                val u = t.web.url ?: ""
                val uri = Uri.parse(u); val host = uri.host ?: ""; val path = uri.path ?: ""
                when {
                    host == "github.com" -> {
                        val isLogin = (path == "/login" || path == "/session") && gcLogins < 2
                        val cred = if (isLogin) GitCred.get(this@MainActivity) else null
                        val js = GitConnect.githubJs(cred?.first, cred?.second, CodeBox.peek(10 * 60000L))
                        t.web.evaluateJavascript(js) { raw ->
                            when (val r = Sites.unquote(raw)) {
                                "login" -> gcLogins++
                                "needcode" -> gcNeedCode()
                                "codefilled" -> { CodeBox.clear(); gcLastRun = System.currentTimeMillis(); if (++gcCodeTries > 3) gcFinish(t, false, "GitHub কোড মেলেনি, নিজে দিন") }
                                "manual" -> gcFinish(t, false, "GitHub ২-ধাপ যাচাই/ক্যাপচা চাইছে — এই পাতায় আপনি হাতে শেষ করুন")
                                "nocred" -> gcFinish(t, false, "GitHub ইমেইল/পাসওয়ার্ড দেওয়া নেই — মেনু থেকে দিন")
                                // রিপোজিটরি অ্যাক্সেস: Only select → All → Save হয়ে গেছে; claude.ai-তে ফিরে কানেক্ট যাচাই
                                "repodone" -> { gcLastRun = System.currentTimeMillis(); Toast.makeText(this@MainActivity, "All repositories সেভ হয়েছে — কানেক্ট যাচাই হচ্ছে…", Toast.LENGTH_SHORT).show(); t.web.loadUrl(GitConnect.CONNECTORS) }
                                else -> if (r.startsWith("clicked:")) { gcLastRun = System.currentTimeMillis() }
                            }
                        }
                    }
                    host == "claude.ai" || host == "www.claude.ai" -> {
                        if (!path.startsWith("/customize/connectors")) { if (System.currentTimeMillis() - gcStartAt > 8000L) t.web.loadUrl(GitConnect.CONNECTORS) }
                        else t.web.evaluateJavascript(GitConnect.claudeJs(!gcInstalled)) { raw ->
                            val r = Sites.unquote(raw)
                            if (r == "clicked:Install") gcInstalled = true
                            if (r == "clicked:Connect" && ++gcConnectClicks > 4) gcFinish(t, false, "GitHub Connect বারবার চেপেও হয়নি — হাতে করুন")
                            else if (r == "connected") gcFinish(t, true, "GitHub কানেক্ট হয়েছে ✔ — চ্যাটে ফিরছি")
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (gcTab != null) handler.postDelayed(this, 3000L)
            }
        }
    }

    /** GitHub ইমেইলে কোড চেয়েছে: নোটিফিকেশন-অ্যাক্সেস না থাকলে একবার সেটিংস খুলে দেয়; কোড এলে পরের টিকে নিজে বসে যায় */
    private fun gcNeedCode() {
        val on = try { android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true } catch (_: Exception) { false }
        if (!on && !gcAskedNotif) {
            gcAskedNotif = true
            Toast.makeText(this, "নোটিফিকেশন অ্যাক্সেস চালু করুন, তাহলে GitHub-এর কোড নিজে বসে যাবে", Toast.LENGTH_LONG).show()
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private fun editGitCred() {
        val cur = GitCred.get(this)
        val e = EditText(this).apply { hint = "GitHub ইমেইল/ইউজারনেম"; setText(cur?.first ?: ""); inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val pw = EditText(this).apply { hint = if (cur != null) "পাসওয়ার্ড (বদলাতে নতুনটা লিখুন)" else "GitHub পাসওয়ার্ড"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val sw = android.widget.CheckBox(this).apply { text = "Claude GitHub নেই বললে নিজে কানেক্ট করুক"; isChecked = gcAuto() || cur == null }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(e); addView(pw); addView(sw) }
        AlertDialog.Builder(this).setTitle("GitHub অটো-কানেক্ট")
            .setMessage("ইমেইল ও পাসওয়ার্ড ফোনের Keystore-এ এনক্রিপ্ট হয়ে থাকে এবং শুধু github.com-এর লগইন পাতায় বসে। ২-ধাপ যাচাই চালু থাকলে লগইনে আপনাকেই কোড দিতে হবে।\nখালি রেখে সেভ করলে মুছে যাবে।")
            .setView(l)
            .setPositiveButton("সেভ") { _, _ ->
                val em = e.text.toString().trim(); val pp = pw.text.toString().ifEmpty { cur?.second ?: "" }
                GitCred.put(this, em, if (em.isBlank()) "" else pp)
                Store.prefs(this).edit().putBoolean("gc_auto", sw.isChecked && em.isNotBlank()).apply()
                Toast.makeText(this, if (em.isBlank()) "মুছে ফেলা হয়েছে" else "সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("এখনই চালান") { _, _ ->
                val t = cur2()
                if (t == null || t.site == null) Toast.makeText(this, "আগে একটা Claude ওয়ার্কস্পেস ট্যাব খুলুন", Toast.LENGTH_LONG).show()
                else gcBegin(t)
            }
            .setNegativeButton("বাতিল", null).show()
    }
    private fun cur2(): Tab? = cur

    private fun checkStuck(t: Tab) {
        val u = t.url
        if (u.contains("/chat/") || u.contains("/code/")) t.chatUrl = u
        t.web.evaluateJavascript(Sites.STUCK_JS) { raw ->
            val hit = Sites.unquote(raw)
            if (hit.isBlank() || t !== cur) { t.stuckHits = 0; if (hit.isBlank() && t.stuckReloads > 0 && System.currentTimeMillis() - t.stuckReloadAt > 120000L) t.stuckReloads = 0; return@evaluateJavascript }
            t.stuckHits++
            if (t.stuckHits == 1) { t.web.evaluateJavascript(Sites.STUCK_SOFT_JS, null); return@evaluateJavascript }
            val now = System.currentTimeMillis()
            if (t.stuckHits >= 3 && now - t.stuckReloadAt > 40000L && t.stuckReloads < 6) {
                t.stuckHits = 0; t.stuckReloadAt = now; t.stuckReloads++
                val target = if (u.contains("/chat/") || u.contains("/code/")) null else t.chatUrl.ifBlank { null }
                if (!u.contains("/chat/") && !u.contains("/code/") && target == null) return@evaluateJavascript
                Toast.makeText(this, "নেট ধীর ছিল — চ্যাট আবার লোড হচ্ছে", Toast.LENGTH_SHORT).show()
                if (target == null) t.web.reload() else t.web.loadUrl(target)
            }
        }
    }

    /** তালিকায় এর পরের সেই ওয়ার্কস্পেস যার লিমিট সম্প্রতি শেষ হয়নি; না থাকলে null (দুটো সাইটের মধ্যে অনন্ত দৌড় এড়ায়) */
    private fun nextAvailable(list: List<Site>, id: String?): Site? {
        if (list.size < 2) return null
        val i = list.indexOfFirst { it.id == id }; val now = System.currentTimeMillis()
        val ck = list.getOrNull(i)?.let { AgentBus.kindOf(it) } ?: "text"
        for (k in 1 until list.size) {
            val s = list[((i + k) % list.size + list.size) % list.size]
            //  অডিও/ইমেজ/ভিডিও এজেন্ট আর টেক্সট এজেন্ট একে অপরের বদলি নয়: লিমিটে শুধু নিজের ক্যাটাগরিতে যাবে
            if (AgentBus.kindOf(s) != ck) continue
            if ((siteLimited[s.id] ?: 0L) < now) return s
        }
        return null
    }

    // ---------- এজেন্ট স্বয়ংক্রিয় পাঠানো: সারিতে কাজ থাকলে ওয়ার্কস্পেসের চ্যাটে নির্দেশ বসিয়ে পাঠায় ----------
    private var agBusy = false
    private var agLastLimitAt = 0L
    private var agNoTokWarned = false
    private val agSent = HashMap<Int, Long>()
    private val agTries = HashMap<Int, Int>()
    private val agWarned = HashSet<Int>()
    /** কাজ #id-র জন্য এজেন্টের সাইট নিজে খোলার চেষ্টা কতবার হয়েছে (সর্বোচ্চ ২, অনন্ত খোলা এড়াতে) */
    private val agOpened = HashMap<Int, Int>()
    private fun agentAuto() = Store.prefs(this).getBoolean("agent_auto", true)
    private val agentTick = object : Runnable {
        override fun run() {
            // আগে ব্রাউজার নিজে করার মতো কাজ (ওয়ার্কস্পেস কমান্ড) সেরে নেয়, তারপর বাকিগুলো এজেন্টকে পাঠায়
            try {
                val tk = Secret.get(this@MainActivity)
                if (tk.isNotEmpty()) Thread {
                    try { AutoLogin.syncSites(this@MainActivity, tk) { refreshHomes() } } catch (_: Exception) {}
                    try { AutoLogin.pollTasks(this@MainActivity, tk) { st -> openSite(st.id); refreshHomes() } } catch (_: Exception) {}
                    try { AutoLogin.pollBus(this@MainActivity, tk) { st -> openSite(st.id); refreshHomes() } } catch (_: Exception) {}
                    runOnUiThread { try { if (agentAuto() && !agBusy && overlay == null) agentDispatch() } catch (_: Exception) {} }
                }.start()
                else if (agentAuto() && !agBusy && overlay == null) agentDispatch()
            } catch (_: Exception) {}
            handler.postDelayed(this, 30000L)
        }
    }

    private fun agentDispatch() {
        val tok = Secret.get(this)
        if (tok.isBlank()) {
            if (!agNoTokWarned && tabs.any { it.site != null }) { agNoTokWarned = true; Toast.makeText(this, "এজেন্ট স্বয়ংক্রিয় পাঠানোর জন্য মেনুতে GitHub টোকেন সেট করুন", Toast.LENGTH_LONG).show() }
            return
        }
        if (System.currentTimeMillis() - agLastLimitAt < 120000L) return
        val sites = Sites.all(this)
        val cands = tabs.filter { it.site != null }.mapNotNull { t ->
            val s = sites.firstOrNull { it.id == t.site } ?: return@mapNotNull null
            val a = AgentBus.agentOf(s) ?: return@mapNotNull null
            if ((siteLimited[s.id] ?: 0L) > System.currentTimeMillis()) null else Triple(t, s, a)
        }
        // ট্যাব খোলা নেই এমন এজেন্ট-সাইট: কাজ এলে নিজে খুলবে (অটো-ওপেন)
        val nowMs = System.currentTimeMillis()
        val closed = sites.filter { s -> AgentBus.agentOf(s) != null && tabs.none { it.site == s.id } && (siteLimited[s.id] ?: 0L) <= nowMs }
        if (cands.isEmpty() && closed.isEmpty()) return
        agBusy = true
        Thread {
            try {
                // ওয়ার্কস্পেস বানানো/লগইন ব্রাউজারের নিজের কাজ: আগে সেগুলো সেরে নিই, কোনোভাবেই এজেন্টকে (Claude) পাঠাই না
                try { AutoLogin.pollTasks(this, tok) { st -> openSite(st.id); refreshHomes() } } catch (_: Exception) {}
                val d = (AgentBus.load(tok) ?: return@Thread).d
                val now = System.currentTimeMillis()
                val ok = cands.filter { !AgentBus.inLimit(d, it.third) }
                var task: org.json.JSONObject? = null
                var pickX: Triple<Tab, Site, String>? = null
                for (tk0 in AgentBus.queuedAll(d)) {
                    val id0 = tk0.optInt("id")
                    if (AutoLogin.parseChat(this, tk0.optString("text")) != null) continue   // ব্রাউজারের কাজ — এজেন্টকে নয়
                    // একই কাজ ১০ মিনিটের মধ্যে আবার নয়; একই কাজে সর্বোচ্চ ৩ বার (এজেন্ট না ধরলে অনন্ত পাঠানো এড়াতে)
                    if (now - (agSent[id0] ?: 0L) < 600000L || (agTries[id0] ?: 0) >= 3) return@Thread
                    // হাবে বেছে নেওয়া এজেন্ট (target) থাকলে শুধু তার ওয়ার্কস্পেসে; তার লিমিট শেষ হলে অন্য এজেন্টে যেতে পারে
                    val want = tk0.optString("target")
                    val pool = when {
                        want.isNotEmpty() && !AgentBus.inLimit(d, want) -> ok.filter { it.third == want }
                        AgentBus.isMedia(want) -> emptyList()
                        else -> ok.filter { AgentBus.groupOf(it.third) == "text" }
                    }
                    if (pool.isEmpty()) {
                        // এই কাজের এজেন্টের সাইট খোলা না থাকলে নিজে খুলি, কয়েক সেকেন্ড পর আবার পাঠানোর চেষ্টা
                        val cs = closed.firstOrNull { c ->
                            val a = AgentBus.agentOf(c) ?: return@firstOrNull false
                            !AgentBus.inLimit(d, a) && (if (want.isNotEmpty()) a == want else AgentBus.groupOf(a) == "text")
                        }
                        if (cs != null && (agOpened[id0] ?: 0) < 2) {
                            agOpened[id0] = (agOpened[id0] ?: 0) + 1
                            runOnUiThread {
                                Toast.makeText(this, "কাজ #$id0 — ${cs.name} খোলা হচ্ছে…", Toast.LENGTH_LONG).show()
                                openSite(cs.id)
                                handler.postDelayed({ if (!agBusy && overlay == null) agentDispatch() }, 9000L)
                            }
                            return@Thread
                        }
                        if (want.isNotEmpty() && agWarned.add(id0)) runOnUiThread { Toast.makeText(this, "কাজ #$id0 ${AgentBus.label(want)}-এর জন্য — ${AgentBus.label(want)}-এর ওয়ার্কস্পেস ট্যাব খুলুন", Toast.LENGTH_LONG).show() }
                        continue
                    }
                    task = tk0; pickX = pool.firstOrNull { it.first === cur } ?: pool.first(); break
                }
                val tkF = task ?: return@Thread
                val pick = pickX ?: return@Thread
                val id = tkF.optInt("id")
                val text = if (AgentBus.isMedia(pick.third) || AgentBus.isChat(pick.third)) AgentBus.mediaPrompt(tkF.optString("text"), tkF.optString("note")) else AgentBus.prompt(pick.third, id, tkF.optString("text"), tkF.optString("note"), tkF.optInt("progress"), tok)
                agSent[id] = now; agTries[id] = (agTries[id] ?: 0) + 1
                AgentBus.mutate(tok, "dispatch") { dd ->
                    AgentBus.ev(dd, "টাস্ক #$id ${pick.third}-কে পাঠানো হয়েছে")
                    //  মিডিয়া এজেন্ট bus.sh চালায় না, তাই পাঠানোর পরই টাস্কটা হাতে-দেওয়া হিসেবে শেষ ধরা হয় (নইলে বারবার পাঠাত)
                    if (AgentBus.isMedia(pick.third) || AgentBus.isChat(pick.third)) dd.optJSONArray("tasks")?.let { a ->
                        for (q in 0 until a.length()) { val t = a.getJSONObject(q); if (t.optInt("id") == id) t.put("status", "done").put("agent", pick.third).put("progress", 100).put("note", "এজেন্টকে পাঠানো হয়েছে").put("updated", AgentBus.now()) }
                    }
                    true
                }
                runOnUiThread {
                    if (tabs.contains(pick.first)) {
                        Toast.makeText(this, "কাজ #$id ${pick.second.name}-এর চ্যাটে পাঠানো হচ্ছে…", Toast.LENGTH_LONG).show()
                        fillInput(pick.first, text, 0, true)
                    }
                }
            } finally { agBusy = false }
        }.start()
    }

    private fun onLimit(t: Tab, hit: String) {
        agLastLimitAt = System.currentTimeMillis()
        t.site?.let { sid -> Sites.all(this).firstOrNull { it.id == sid }?.let { AgentBus.agentOf(it) }?.let { ag ->
            val tk = Secret.get(this); if (tk.isNotBlank()) Thread { AgentBus.limit(tk, ag, hit) }.start() } }
        t.site?.let { siteLimited[it] = System.currentTimeMillis() + 5 * 3600 * 1000L }
        val nx = nextAvailable(Sites.all(this), t.site)
        if (nx == null) { Toast.makeText(this, "সব ওয়ার্কস্পেসের লিমিট শেষ মনে হচ্ছে — পরে আবার চেষ্টা করুন", Toast.LENGTH_LONG).show(); return }
        if (autoSwitch) {
            Toast.makeText(this, "লিমিট শেষ (“$hit”) — ${nx.name}-এ নেওয়া হচ্ছে", Toast.LENGTH_LONG).show()
            handoff(t, nx, true)
        } else askSwitch(t, hit, nx)
    }

    private fun askSwitch(t: Tab, hit: String, nx: Site) {
        val from = Sites.all(this).firstOrNull { it.id == t.site }?.name ?: "এই সাইট"
        limitDialogOpen = true
        AlertDialog.Builder(this).setTitle("লিমিট শেষ মনে হচ্ছে")
            .setMessage("$from-এ পাওয়া গেছে: “$hit”\n\n${nx.name}-এ যাবেন? এই চ্যাটের শেষ অংশ কপি হয়ে নতুন চ্যাটের ঘরে বসানোর চেষ্টা হবে।")
            .setPositiveButton("${nx.name}-এ যান") { _, _ -> handoff(t, nx) }
            .setNeutralButton("অন্যটি বেছে নিন") { _, _ -> pickHandoff(t) }
            .setNegativeButton("না", null)
            .setOnDismissListener { limitDialogOpen = false }.show()
    }

    /** এই চ্যাট কোন ওয়ার্কস্পেসে নেবেন তা বেছে নেওয়ার তালিকা */
    private fun pickHandoff(t: Tab) {
        val others = Sites.all(this).filter { it.id != t.site }
        if (others.isEmpty()) { Toast.makeText(this, "আর কোনো ওয়ার্কস্পেস নেই — হোম থেকে নতুন যোগ করুন", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("এই চ্যাট কোথায় নেবেন?")
            .setItems(others.map { it.name }.toTypedArray()) { _, i -> handoff(t, others[i]) }
            .setNegativeButton("বাতিল", null).show()
    }

    /** চলতি চ্যাটের শেষ অংশ ক্লিপবোর্ডে কপি করে অন্য ওয়ার্কস্পেসের নতুন চ্যাটে খুলে ইনপুট ঘরে বসায়।
     *  send=true (স্বয়ংক্রিয় সুইচ) হলে নিজেই পাঠিয়ে দেয়; হাতে বেছে নিলে শুধু বসায়, পাঠায় না। আগের ট্যাব খোলা থাকে */
    private fun handoff(from: Tab, to: Site, send: Boolean = false) {
        val fromName = Sites.all(this).firstOrNull { it.id == from.site }?.name ?: "আগের সাইট"
        // আগের ট্যাব Claude Code চ্যাট হলে গন্তব্যেও সাধারণ চ্যাট নয়, Claude Code (/code) পেজ খোলে এবং রিপোর নাম সাথে পাঠায়
        val fromUrl = from.web.url ?: from.url
        val isCode = Sites.isCodeHandoff(fromUrl, to)
        val destUrl = Sites.handoffUrl(fromUrl, to)
        from.web.evaluateJavascript(Sites.EXTRACT_JS) { raw ->
            val chat = Sites.unquote(raw).trim()
            fun deliver(repo: String, cands: List<String>) {
                // সংরক্ষিত GitHub টোকেন শুধু তখনই যায় যখন কাজটা GitHub/Claude Code-সংক্রান্ত (রিপো জানা আছে বা চ্যাটে github আছে)
                val tok = Secret.get(this)
                val useTok = tok.isNotBlank() && (isCode || repo.isNotBlank() || Sites.looksGit(chat))
                val payload = if (chat.isBlank()) "" else Sites.handoffText(fromName, chat, repo, if (useTok) tok else "", if (useTok) cands else emptyList())
                if (payload.isNotEmpty()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("chat", payload)
                    if (useTok) {
                        // টোকেন থাকলে ক্লিপবোর্ডে "সংবেদনশীল" চিহ্ন (Android ১৩+ এ পর্দায় দেখায় না) এবং ২ মিনিট পর মুছে ফেলা
                        try { if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) } } catch (_: Exception) {}
                        handler.postDelayed({ try { if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() } catch (_: Exception) {} }, 120000L)
                    }
                    cm.setPrimaryClip(clip)
                }
                val tab = openSite(to.id, true, destUrl)
                if (tab != null) {
                    if (payload.isEmpty()) Toast.makeText(this, "কপি করার মতো লেখা পাওয়া যায়নি — শুধু ${to.name} খোলা হয়েছে", Toast.LENGTH_LONG).show()
                    else {
                        if (!send) Toast.makeText(this, "চ্যাট কপি হয়েছে — ${to.name}-এর ঘরে বসানোর চেষ্টা চলছে (না বসলে পেস্ট করুন)", Toast.LENGTH_LONG).show()
                        //  workspace switch: age box-e lekha boshao, tarpor settings-e giye GitHub connected kina dekho (na thakle connect), shesh hole abar boshiye (send hole) pathao
                        val toClaude = (Uri.parse(destUrl).host ?: "").endsWith("claude.ai")
                        if (toClaude && gcAuto() && GitCred.has(this) && gcTab == null)
                            fillInput(tab, payload, 0, false) { if (tabs.contains(tab) && gcTab == null) gcBegin(tab, payload, send, destUrl) }
                        else fillInput(tab, payload, 0, send)
                    }
                }
            }
            // রিপো কোনটা: ১) চ্যাটের লেখায় উল্লেখ ২) (Claude Code পেজে) পাতার GitHub লিংক ৩) মেনুর ডিফল্ট। কিছুই না মিললে (কিন্তু কাজটা GitHub-সংক্রান্ত ও টোকেন আছে) GitHub থেকে সাম্প্রতিক রিপোর তালিকা এনে বার্তায় দেয়
            val gitRelated = isCode || Sites.looksGit(chat)
            val prefs = Store.prefs(this)
            val saved = prefs.getString("gitrepo", "")?.trim().orEmpty()
            fun proceed(repo: String) {
                val tok = Secret.get(this)
                if (repo.isBlank() && gitRelated && tok.isNotBlank()) {
                    Thread { val c = Sites.recentRepos(tok); runOnUiThread { deliver("", c) } }.start()
                } else deliver(repo, emptyList())
            }
            val fromChat = Sites.repoFromChat(chat)
            when {
                fromChat.isNotEmpty() -> proceed(fromChat)
                isCode -> from.web.evaluateJavascript(Sites.REPO_JS) { r -> proceed(Sites.unquote(r).trim().ifEmpty { saved }) }
                gitRelated -> proceed(saved)
                else -> proceed("")
            }
        }
    }

    /** GitHub টোকেন একবার দিলে এনক্রিপ্ট হয়ে ফোনে থাকে; লিমিটে অন্য AI-তে সুইচের সময় (GitHub-সংক্রান্ত কাজে) চ্যাটের সাথে আপনা-আপনি যায় */
    private fun editToken() {
        val e = EditText(this).apply {
            hint = "ghp_… বা github_pat_…"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val l = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        val b = AlertDialog.Builder(this).setTitle("GitHub টোকেন")
            .setMessage("টোকেন ফোনের Keystore দিয়ে এনক্রিপ্ট হয়ে থাকে। লিমিট শেষে অন্য AI-তে (যেমন Claude) সুইচের সময় কাজটা GitHub/Claude Code-সংক্রান্ত হলে এটা চ্যাটের বার্তায় নিজে থেকে যোগ হবে, যাতে সে রিপোতে কাজ চালাতে পারে।\n\n⚠ মনে রাখুন: বার্তাটা ওই সাইটের চ্যাট ইতিহাসে জমা থাকবে। তাই শুধু একটি রিপোর জন্য সীমিত (fine-grained), স্বল্প মেয়াদের টোকেন দিন এবং কাজ শেষে বাতিল করুন। পুরো অ্যাকাউন্টের ক্ষমতাওয়ালা টোকেন দেবেন না।")
            .setView(l)
            .setPositiveButton("সেভ") { _, _ ->
                val v = e.text.toString().trim()
                when {
                    v.isEmpty() -> Toast.makeText(this, "কিছু লেখা হয়নি", Toast.LENGTH_SHORT).show()
                    v.length < 20 || v.any { it.isWhitespace() } -> Toast.makeText(this, "টোকেনটা ঠিক মনে হচ্ছে না (ফাঁকা বা খুব ছোট)", Toast.LENGTH_LONG).show()
                    Secret.put(this, v) -> Toast.makeText(this, "টোকেন এনক্রিপ্ট হয়ে সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, "সংরক্ষণ করা যায়নি", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("বাতিল", null)
        if (Secret.has(this)) b.setNeutralButton("🗑 মুছুন") { _, _ -> Secret.put(this, ""); Toast.makeText(this, "টোকেন মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() }
        b.show()
    }

    /** মেনু থেকে ডিফল্ট GitHub রিপো (মালিক/রিপো) সেট করা — Claude Code হ্যান্ডঅফে এটাই সাথে যায় */
    private fun editRepo() {
        val e = EditText(this).apply { hint = "মালিক/রিপো (যেমন name/project)"; setText(Store.prefs(this@MainActivity).getString("gitrepo", "") ?: "") }
        val l = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        AlertDialog.Builder(this).setTitle("ডিফল্ট GitHub রিপো")
            .setMessage("Claude Code চ্যাট অন্য ওয়ার্কস্পেসে নিলে এই রিপোর নাম চ্যাটের সাথে যাবে, নতুন সেশন নিজেই রিপো যোগ করে নেবে। খালি রাখলে পাতার GitHub লিংক থেকে খুঁজে নেওয়া হবে। কোনো টোকেন বা পাসওয়ার্ড এখানে দেবেন না।")
            .setView(l)
            .setPositiveButton("সেভ") { _, _ -> Store.prefs(this).edit().putString("gitrepo", e.text.toString().trim().removePrefix("https://github.com/").trim('/')).apply() }
            .setNegativeButton("বাতিল", null).show()
    }

    /** ট্যাবের সাইট কোন এজেন্ট (claude/gemini/grok/audio/image/video); সাইট না হলে/অচেনা হলে null → সাধারণ আচরণ */
    private fun agentOfTab(t: Tab): String? = t.site?.let { sid -> Sites.all(this).firstOrNull { it.id == sid }?.let { AgentBus.agentOf(it) } }

    private fun fillInput(t: Tab, text: String, n: Int, send: Boolean, onFilled: (() -> Unit)? = null) {
        if (n >= 12) { if (send) Toast.makeText(this, "ঘরে বসানো যায়নি — চ্যাট ক্লিপবোর্ডে আছে, পেস্ট করে পাঠান", Toast.LENGTH_LONG).show(); onFilled?.invoke(); return }
        handler.postDelayed({
            if (!tabs.contains(t)) return@postDelayed
            t.web.evaluateJavascript(AutoClick.fillJs(agentOfTab(t), text)) { r ->
                if (r == null || !r.contains("ok")) {
                    // ইনপুট ঘর নেই: ১) মিডিয়া/অচেনা সাইটে "Create new dialog"-জাতীয় শুরুর বোতাম চাপি; ২) নইলে প্ল্যান/স্বাগত পেজ আটকালে "Use Claude for free" বা ← চেপে পার করি; তারপর আবার চেষ্টা
                    t.web.evaluateJavascript(AutoClick.prepJs(agentOfTab(t))) { pr ->
                        if (pr != null && pr.contains("clicked")) fillInput(t, text, n + 1, send, onFilled)
                        else t.web.evaluateJavascript(Sites.PASS_JS) { _ -> fillInput(t, text, n + 1, send, onFilled) }
                    }
                } else if (send) sendInput(t, 0) else onFilled?.invoke()
            }
        }, if (n == 0) 4000L else 3000L)
    }

    /** বসানো লেখা পাঠায়: বোতাম চেপে, না পেলে Enter দিয়ে (সাইটভেদে বোতাম তৈরি হতে একটু সময় লাগে, তাই কয়েকবার চেষ্টা) */
    private fun sendInput(t: Tab, n: Int) {
        // বসানোর পর পাঠানোর বোতাম চালু হতে সময় লাগে (লেখা লম্বা হলে আরও) — তাই চালু বোতাম পাওয়া পর্যন্ত ১২ বার চেষ্টা; শেষে Enter
        if (n > 12) return
        handler.postDelayed({
            if (!tabs.contains(t)) return@postDelayed
            val ag = agentOfTab(t)
            val js = if (n == 12) AutoClick.fallbackJs(ag) else AutoClick.sendJs(ag)
            t.web.evaluateJavascript(js) { r -> if (r == null || !r.contains("clicked") && !r.contains("enter")) sendInput(t, n + 1) }
        }, if (n == 0) 2500L else 1500L)
    }

    // ---------- GitHub লগইন: প্রতিটি ওয়ার্কস্পেসে আলাদা। নিজে থেকে কখনো কপি হয় না (আগে হতো, তাই লগআউটের পর পুরনো অ্যাকাউন্ট ফিরে আসত) ----------
    private fun ghProfile(id: String, create: Boolean = false) = try {
        if (create) ProfileStore.getInstance().getOrCreateProfile(Sites.profile(id)) else ProfileStore.getInstance().getProfile(Sites.profile(id))
    } catch (_: Exception) { null }

    /** id = ওয়ার্কস্পেস; null = সাধারণ (ওয়ার্কস্পেস নয় এমন) ট্যাবের প্রোফাইল */
    private fun ghManager(id: String?, create: Boolean = false): CookieManager? = try {
        if (id == null) CookieManager.getInstance() else ghProfile(id, create)?.cookieManager
    } catch (_: Exception) { null }

    /** এখানে GitHub-এ লগইন থাকলে তার কুকি-স্ট্রিং, নইলে null */
    private fun ghCookies(id: String?): String? = try {
        ghManager(id)?.getCookie("https://github.com")?.takeIf { it.contains("logged_in=yes") }
    } catch (_: Exception) { null }

    /** GitHub-এর সব কুকি মুছে ফেলে (লগআউট)। Claude ইত্যাদি অন্য সাইটের লগইন অক্ষত থাকে */
    private fun ghClear(id: String?) {
        try {
            val cm = ghManager(id) ?: return
            val raw = cm.getCookie("https://github.com") ?: return
            val names = raw.split(";").map { it.trim().substringBefore("=") }.filter { it.isNotEmpty() }
            for (n in names) for (attr in listOf("", "; Domain=github.com", "; Domain=.github.com"))
                cm.setCookie("https://github.com", "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure$attr")
            cm.flush()
        } catch (_: Exception) {}
    }

    private fun ghClearAll() {
        ghClear(null); Sites.all(this).forEach { ghClear(it.id) }
    }

    /** আগে গন্তব্যের পুরনো GitHub কুকি মুছে, তারপর নতুনগুলো বসায় (দুই অ্যাকাউন্টের কুকি মিশে যাওয়া এড়াতে) */
    private fun ghCopy(raw: String, toId: String) {
        try {
            val cm = ghManager(toId, true) ?: return
            ghClear(toId)
            raw.split(";").map { it.trim() }.filter { it.contains("=") }
                .forEach { cm.setCookie("https://github.com", "$it; Path=/; Secure; HttpOnly; Max-Age=1209600") }
            cm.flush()
        } catch (_: Exception) {}
    }

    /** হাতে বেছে: বর্তমান জায়গার GitHub লগইন অন্য কোন কোন ওয়ার্কস্পেসে কপি হবে */
    private fun ghShareDialog(srcId: String?) {
        val raw = ghCookies(srcId)
        if (raw == null) { Toast.makeText(this, "এখানে GitHub-এ লগইন নেই — আগে লগইন করুন", Toast.LENGTH_LONG).show(); return }
        val targets = Sites.all(this).filter { it.id != srcId }
        if (targets.isEmpty()) { Toast.makeText(this, "কপি করার মতো অন্য ওয়ার্কস্পেস নেই", Toast.LENGTH_SHORT).show(); return }
        val checked = BooleanArray(targets.size)
        AlertDialog.Builder(this).setTitle("এই GitHub লগইন কোথায় কপি করবেন?")
            .setMultiChoiceItems(targets.map { it.name }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("কপি করুন") { _, _ ->
                var n = 0
                targets.forEachIndexed { i, s -> if (checked[i]) { ghCopy(raw, s.id); n++ } }
                Toast.makeText(this, "$n টি ওয়ার্কস্পেসে কপি হয়েছে — সেখানে পেজ রিলোড করুন", Toast.LENGTH_LONG).show()
            }.setNegativeButton("বাতিল", null).show()
    }

    private fun ghClearAllAsk() {
        AlertDialog.Builder(this).setTitle("সব জায়গার GitHub লগইন মুছবেন?")
            .setMessage("সব ওয়ার্কস্পেস ও সাধারণ ট্যাব থেকে GitHub-এর লগআউট হবে। Claude-সহ অন্য সাইটের লগইন থাকবে। এরপর প্রতিটি জায়গায় নিজের ইচ্ছামতো অ্যাকাউন্টে লগইন করতে পারবেন।")
            .setPositiveButton("মুছুন") { _, _ ->
                ghClearAll()
                tabs.forEach { if (it.web.url?.contains("github.com") == true) it.web.reload() }
                Toast.makeText(this, "সব জায়গার GitHub লগইন মোছা হয়েছে", Toast.LENGTH_LONG).show()
            }.setNegativeButton("না", null).show()
    }

    private fun refreshHomes() { tabs.filter { it.isHome }.forEach { loadHome(it) }; pushAgents() }

    // ---------- ট্যাব তালিকা ----------
    private fun hideSwitcher() { overlay?.let { content.removeView(it) }; overlay = null; refreshUpdBtn() }

    private fun showSwitcher() {
        handler.post { refreshUpdBtn() }   // তালিকা সাজানো শেষ হলে বোতাম লুকাই
        val keep = (overlay as? ScrollView)?.scrollY ?: 0
        lastOverlay = System.currentTimeMillis()
        overlay?.let { content.removeView(it) }
        val sv = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F3F4F6")); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        sv.addView(col)
        fun head(t: String) = TextView(this).apply {
            text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#374151")); setPadding(dp(2), dp(10), 0, dp(8))
        }
        col.addView(head("খোলা ট্যাব (${tabs.size})"))
        if (keep == 0 && overlay == null) cur?.let { snap(it) }
        for (r in tabs.chunked(2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
            for (k in 0..1) {
                val t = r.getOrNull(k)
                if (t == null) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); continue }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = bg(if (t === cur) Color.parseColor("#BFDBFE") else Color.WHITE, 14, if (t === cur) Color.parseColor("#2563EB") else Color.parseColor("#E5E7EB"))
                    setPadding(dp(6), dp(4), dp(6), dp(6)); setOnClickListener { select(t) }
                }
                val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(TextView(this).apply { text = t.title; textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.parseColor("#111827")); setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(TextView(this).apply { text = "✕"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#374151"))
                    setOnClickListener { closeTab(t) } }, LinearLayout.LayoutParams(dp(40), dp(36)))
                card.addView(head)
                val st = t.jobKey?.let { jk -> Jobs.find(jk)?.let { j -> if (j.status == "running") "চলছে…" else if (j.status == "ok") "সম্পন্ন ✓" else if (j.status == "stopped") "বন্ধ ■" else "ব্যর্থ ✗" } }
                val th = t.thumb
                if (th != null && st == null) {
                    card.addView(ImageView(this).apply { setImageBitmap(th); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(-1, dp(150)))
                } else {
                    card.addView(TextView(this).apply { text = st ?: (if (t.isHome) "Termux হোম" else t.url); textSize = 13f; gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#6B7280")); background = bg(Color.parseColor("#F3F4F6"), 10) }, LinearLayout.LayoutParams(-1, dp(150)))
                }
                line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (k == 0) rightMargin = dp(5) else leftMargin = dp(5) })
            }
            col.addView(line)
        }
        col.addView(Button(this).apply { text = "＋ নতুন ট্যাব"; isAllCaps = false; setOnClickListener { newTab(null); openHome() } })
        col.addView(head("Termux-এ ব্যাকগ্রাউন্ডে কাজ (${Jobs.running()} চলছে)"))
        if (Jobs.list.any { it.status != "running" }) col.addView(Button(this).apply { text = "🗑 শেষ হওয়া কাজ মুছুন"; isAllCaps = false; setOnClickListener { clearFinishedJobs() } })
        if (Jobs.list.isEmpty()) col.addView(TextView(this).apply { text = "এখনো কোনো কমান্ড চালানো হয়নি"; setTextColor(Color.parseColor("#6B7280")); textSize = 13f })
        Jobs.list.asReversed().forEach { j ->
            val c = when (j.status) { "running" -> "#F59E0B"; "ok" -> "#16A34A"; "stopped" -> "#6B7280"; else -> "#DC2626" }
            val st = when (j.status) { "running" -> "চলছে…"; "ok" -> "সম্পন্ন"; "stopped" -> "বন্ধ করা হয়েছে"; else -> "ব্যর্থ" }
            col.addView(TextView(this).apply {
                text = "●  ${j.title} — $st · ${j.elapsed()} সেকেন্ড"; textSize = 14f; setTextColor(Color.parseColor(c)); background = bg(Color.WHITE, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                setOnClickListener { openJobTab(j) }
            })
        }
        overlay = sv
        content.addView(sv, FrameLayout.LayoutParams(-1, -1))
        sv.post { sv.scrollTo(0, keep) }
    }

    // ---------- এজেন্ট হাব (Claude/Gemini/Grok-এর কাজের তালিকা) ----------
    private val agentLoader by lazy {
        androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this)).build()
    }
    private val AGENT = "https://appassets.androidplatform.net/assets/agent-hub.html"

    /** হাবের চ্যাট-বক্সের এজেন্ট বাছাইয়ের তালিকা: হোমপেজের ওয়ার্কস্পেস থেকে (Claude → Gemini → Grok), জানা থাকলে সংস্করণ সহ */
    private fun pushAgents() {
        val hub = tabs.filter { it.url.startsWith(AGENT) }
        if (hub.isEmpty()) return
        val arr = org.json.JSONArray()
        AgentBus.agentsIn(Sites.all(this)).forEach { id ->
            arr.put(org.json.JSONObject().put("id", id).put("label", AgentBus.label(id)).put("cat", AgentBus.groupOf(id)).put("catLabel", AgentBus.groupLabel(AgentBus.groupOf(id))).put("ver", Store.prefs(this).getString("agver_$id", "") ?: ""))
        }
        hub.forEach { it.web.evaluateJavascript("if(window.setAgents)setAgents($arr)", null) }
    }

    /** খোলা ওয়ার্কস্পেস-ট্যাব থেকে প্রতিটি এজেন্টের বর্তমান মডেল/সংস্করণ পড়ার চেষ্টা (পাতা বদলালে না-ও মিলতে পারে; তখন আগের জানা মান থাকে) */
    private fun detectAgentVersions() {
        val sites = Sites.all(this)
        for (ag in AgentBus.agentsIn(sites)) {
            val tb = tabs.firstOrNull { x -> x.site != null && sites.firstOrNull { it.id == x.site }?.let { AgentBus.agentOf(it) } == ag } ?: continue
            tb.web.evaluateJavascript(AgentBus.verJs(ag)) { raw ->
                val v = Sites.unquote(raw).trim()
                if (v.isNotEmpty() && v != (Store.prefs(this).getString("agver_$ag", "") ?: "")) {
                    Store.prefs(this).edit().putString("agver_$ag", v).apply(); pushAgents()
                }
            }
        }
    }

    private fun openAgentHub() {
        tabs.firstOrNull { it.url.startsWith(AGENT) }?.let { select(it); pushAgents(); detectAgentVersions(); return }
        val t = newTab(AGENT)
        t.title = "এজেন্ট"
        select(t)
    }

    // ---------- মেনু ----------
    private fun menu(anchor: View) {
        val m = SheetMenu()
        if (upd != null) m.menu.add(0, 8, 0, "⬆ অ্যাপ আপডেট করুন")
        m.menu.add(0, 1, 0, "নতুন ট্যাব")
        m.menu.add(0, 2, 1, "রিলোড")
        if (cur?.web?.canGoForward() == true) m.menu.add(0, 12, 1, "এগিয়ে যান →")
        m.menu.add(0, 3, 2, "ডেস্কটপ সাইট").apply { isCheckable = true; isChecked = desktop }
        m.menu.add(0, 60, 2, "Google লগইন মোড: " + listOf("০ WebView", "১ Chrome-সদৃশ", "২ Chrome-সদৃশ+")[Store.prefs(this).getInt("gmode", 0).coerceIn(0, 2)] + (if (desktop) " (ডেস্কটপ মোড চালু — প্রযোজ্য নয়)" else ""))
        m.menu.add(0, 61, 3, "লগইন তথ্য (ইমেইল-পাসওয়ার্ড)")
        m.menu.add(0, 62, 3, "একসাথে ওয়ার্কস্পেস বানান + অটো-লগইন")
        m.menu.add(0, 4, 3, "Termux কমান্ড ম্যানেজ")
        m.menu.add(0, 5, 4, "Termux সেটআপ ও অবস্থা")
        m.menu.add(0, 6, 5, "অ্যাপ অনুমতি (সেটিংস)")
        m.menu.add(0, 7, 6, "ব্রাউজিং ডেটা মুছুন")
        cur?.takeIf { it.site != null }?.let { m.menu.add(0, 9, 0, "🔁 এই চ্যাট অন্য ওয়ার্কস্পেসে নিন") }
        cur?.takeIf { it.site == null && it.jobKey == null && !it.isHome && it.url.startsWith("http") }?.let { m.menu.add(0, 11, 1, "🌐 এই সাইটকে ওয়ার্কস্পেস বানান (আলাদা লগইন)") }
        cur?.takeIf { it.site != null }?.let { m.menu.add(0, 14, 9, "🔍 লিমিট শনাক্তকরণ পরীক্ষা") }
        m.menu.add(0, 18, 8, if (GitCred.has(this)) "🔗 GitHub অটো-কানেক্ট: " + (if (gcAuto()) "চালু" else "বন্ধ") else "🔗 GitHub অটো-কানেক্ট (ইমেইল/পাসওয়ার্ড)")
        m.menu.add(0, 17, 9, "🔍 claude.ai অনুমতি-কার্ড পরীক্ষা")
        m.menu.add(0, 15, 7, "🔒 ব্যাকগ্রাউন্ডে ট্যাব সচল রাখুন").apply { isCheckable = true; isChecked = Store.prefs(this@MainActivity).getBoolean("keep_web", true) }
        m.menu.add(0, 10, 7, "⚡ লিমিটে স্বয়ংক্রিয় সুইচ").apply { isCheckable = true; isChecked = autoSwitch }
        m.menu.add(0, 13, 8, "🔗 ডিফল্ট GitHub রিপো (Claude Code)")
        m.menu.add(0, 16, 8, if (Secret.has(this)) "🔑 GitHub টোকেন: সেট আছে (বদল/মুছুন)" else "🔑 GitHub টোকেন (হ্যান্ডঅফে সাথে যাবে)")
        m.menu.add(0, 40, 9, "🐙 GitHub লগইন মুছুন (এই ওয়ার্কস্পেস/ট্যাব)")
        m.menu.add(0, 41, 9, "🐙 GitHub লগইন অন্য ওয়ার্কস্পেসে কপি")
        m.menu.add(0, 42, 9, "🐙 সব জায়গার GitHub লগইন মুছুন")
        val ct = cur
        if (urlOk(ct)) {
            m.menu.add(0, 20, 1, if (Bookmarks.has(this, ct!!.url)) "★ বুকমার্ক সরান" else "☆ বুকমার্ক যোগ করুন")
            m.menu.add(0, 23, 1, "🔎 পেজে খুঁজুন")
            m.menu.add(0, 24, 1, "↗ শেয়ার করুন")
            m.menu.add(0, 25, 1, "🔗 লিংক কপি")
            m.menu.add(0, 26, 1, "🖨 প্রিন্ট / PDF সেভ")
        }
        m.menu.add(0, 50, 2, "🤖 এজেন্ট")
        m.menu.add(0, 51, 2, "📤 কাজ নিজে থেকে এজেন্টকে পাঠানো").apply { isCheckable = true; isChecked = agentAuto() }
        m.menu.add(0, 21, 2, "📚 বুকমার্ক তালিকা")
        m.menu.add(0, 22, 2, "🕘 ইতিহাস")
        m.menu.add(0, 27, 2, "🕶 নতুন ইনকগনিটো ট্যাব")
        m.menu.add(0, 29, 2, "🔠 লেখার আকার")
        m.menu.add(0, 30, 2, "⬇ ডাউনলোড তালিকা")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                50 -> openAgentHub()
                51 -> { val on = !agentAuto(); Store.prefs(this).edit().putBoolean("agent_auto", on).apply()
                    Toast.makeText(this, if (on) "সারিতে কাজ এলে খোলা ওয়ার্কস্পেসের চ্যাটে নিজে থেকে পাঠানো হবে" else "স্বয়ংক্রিয় পাঠানো বন্ধ — কাজ হাতে পাঠাতে হবে", Toast.LENGTH_LONG).show() }
                13 -> editRepo()
                16 -> editToken()
                40 -> { ghClear(cur?.site); cur?.let { t -> if (t.web.url?.contains("github.com") == true) t.web.reload() }
                    Toast.makeText(this, "এখানকার GitHub লগইন মোছা হয়েছে", Toast.LENGTH_SHORT).show() }
                41 -> ghShareDialog(cur?.site)
                42 -> ghClearAllAsk()
                20 -> cur?.let { t ->
                    if (Bookmarks.has(this, t.url)) { Bookmarks.remove(this, t.url); Toast.makeText(this, "বুকমার্ক সরানো হয়েছে", Toast.LENGTH_SHORT).show() }
                    else { Bookmarks.add(this, t.title, t.url); Toast.makeText(this, "বুকমার্ক যোগ হয়েছে", Toast.LENGTH_SHORT).show() }
                    refreshHomes()
                }
                21 -> showBookmarks()
                22 -> showHistory()
                23 -> showFind()
                24 -> cur?.let { t -> shareText(t.url, t.title) }
                25 -> cur?.let { t -> copyText(t.url) }
                26 -> printCur()
                27 -> newIncognito()
                29 -> pickTextSize()
                30 -> try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                8 -> startUpdate()
                9 -> cur?.let { t -> pickHandoff(t) }
                14 -> cur?.let { t -> t.web.evaluateJavascript(Sites.LIMIT_DEBUG_JS) { raw ->
                    AlertDialog.Builder(this).setTitle("লিমিট শনাক্তকরণ").setMessage(Sites.unquote(raw).ifBlank { "উত্তর পাওয়া যায়নি" }).setPositiveButton("ঠিক আছে", null).show() } }
                11 -> cur?.let { t -> makeWorkspace(t) }
                18 -> editGitCred()
                17 -> cur?.let { t -> t.web.evaluateJavascript("(function(){try{if(location.hostname.indexOf('claude.ai')<0)return 'এটা claude.ai ট্যাব নয়';return window.__rcDbg?window.__rcDbg():'স্ক্রিপ্ট লোড হয়নি (পেজ রিলোড করুন)'}catch(e){return 'ERR '+e}})()") { raw ->
                    AlertDialog.Builder(this).setTitle("অনুমতি-কার্ড পরীক্ষা").setMessage(Sites.unquote(raw).ifBlank { "উত্তর পাওয়া যায়নি" }).setPositiveButton("ঠিক আছে", null).show() } }
                15 -> { val on = !Store.prefs(this).getBoolean("keep_web", true); Store.prefs(this).edit().putBoolean("keep_web", on).apply()
                    Toast.makeText(this, if (on) "অন্য অ্যাপে গেলেও ট্যাব সচল থাকবে (নোটিফিকেশন দেখাবে)" else "অন্য অ্যাপে গেলে ট্যাব থেমে যেতে পারে", Toast.LENGTH_LONG).show() }
                10 -> { autoSwitch = !autoSwitch; Store.prefs(this).edit().putBoolean("autoswitch", autoSwitch).apply()
                    Toast.makeText(this, if (autoSwitch) "স্বয়ংক্রিয় সুইচ চালু" else "স্বয়ংক্রিয় সুইচ বন্ধ — শুধু প্রস্তাব আসবে", Toast.LENGTH_SHORT).show() }
                1 -> { newTab(null); openHome() }
                2 -> reloadCur()
                12 -> cur?.web?.goForward()
                3 -> { desktop = !desktop; Store.prefs(this).edit().putBoolean("desktop", desktop).apply()
                    tabs.forEach { t -> applyDesktop(t) }; cur?.let { t -> if (!t.isHome) t.web.reload() } }
                60 -> { val n = (Store.prefs(this).getInt("gmode", 0) + 1) % 3; Store.prefs(this).edit().putInt("gmode", n).apply()
                    tabs.forEach { t -> applyDesktop(t) }
                    Toast.makeText(this, listOf("মোড ০: WebView-এর নিজস্ব অবস্থা", "মোড ১: Chrome-সদৃশ (UA + হিন্ট + হেডার মিলানো)", "মোড ২: মোড ১ + window.chrome ইত্যাদি")[n] + "\nএখন নতুন ট্যাবে সাইন-ইন চেষ্টা করুন", Toast.LENGTH_LONG).show() }
                61 -> AutoLogin.credsDialog(this)
                62 -> AutoLogin.bulkDialog(this) { st -> openSite(st.id); refreshHomes() }
                4 -> manage()
                5 -> status()
                6 -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                7 -> { WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null)
                    tabs.forEach { t -> t.web.clearCache(true); t.web.clearHistory() }; Toast.makeText(this, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() }
            }
            true
        }
        showMenuSheet(m, anchor)
    }

    class MI(val itemId: Int, val title: String) { var isCheckable = false; var isChecked = false }
    class SheetMenu {
        val items = mutableListOf<MI>()
        val menu = this
        var click: ((MI) -> Boolean)? = null
        fun add(@Suppress("UNUSED_PARAMETER") g: Int, id: Int, @Suppress("UNUSED_PARAMETER") order: Int, title: String): MI = MI(id, title).also { items.add(it) }
        fun setOnMenuItemClickListener(l: (MI) -> Boolean) { click = l }
    }

    // অ্যাপের APK "Daily Browser.apk" নামে শেয়ার (ফাইলের নাম ApkShareProvider ঠিক করে)
    private fun shareApp() {
        try {
            val u = ApkShareProvider.uri(this)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/vnd.android.package-archive"
            i.putExtra(Intent.EXTRA_STREAM, u)
            i.putExtra(Intent.EXTRA_SUBJECT, "Daily Browser")
            i.clipData = android.content.ClipData.newRawUri(ApkShareProvider.NAME, u)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Daily Browser শেয়ার করুন"))
        } catch (e: Exception) {
            Toast.makeText(this, "শেয়ার করা যায়নি", Toast.LENGTH_LONG).show()
        }
    }

    /** ডান পাশ থেকে খোলা সাইডবার মেনু */
    private fun showMenuSheet(m: SheetMenu, anchor: View) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val groups = listOf(
            "এজেন্ট এবং ওয়ার্কস্পেস" to listOf(50, 51, 9, 11, 10, 15, 14, 17),
            "পেজ" to listOf(20, 23, 24, 25, 26, 3, 29, 2, 12, 1),
            "লাইব্রেরি" to listOf(21, 22, 30, 27),
            "GitHub" to listOf(18, 13, 16, 40, 41, 42),
            "Termux" to listOf(4, 5),
            "অ্যাপ" to listOf(6, 7)
        )
        val icons = mapOf(50 to "🤖", 51 to "📤", 18 to "🔗", 17 to "🔍", 1 to "＋", 2 to "↻", 12 to "→", 3 to "🖥", 4 to "⌘", 5 to "⚙", 6 to "🛡", 7 to "🧹")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(22)) }
        fun pick(it: MI) { d.dismiss(); m.click?.invoke(it) }
        m.items.firstOrNull { it.itemId == 8 }?.let { u ->
            col.addView(TextView(this).apply {
                text = "⬆  অ্যাপ আপডেট করুন"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED"))).apply { cornerRadius = dp(14).toFloat() }
                setOnClickListener { pick(u) }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        }
        for ((name, ids) in groups) {
            val its = ids.mapNotNull { id -> m.items.firstOrNull { it.itemId == id } }
            if (its.isEmpty()) continue
            col.addView(TextView(this).apply {
                text = name; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6B7280")); setPadding(dp(6), dp(10), 0, dp(6))
            })
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.parseColor("#F5F6FA"), 16) }
            its.forEachIndexed { i, it ->
                val raw = it.title
                val clean = raw.replace(Regex("^[^\\p{L}\\p{N}]+"), "")
                val ic = icons[it.itemId] ?: raw.takeWhile { c -> !c.isLetterOrDigit() }.trim().ifEmpty { "•" }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)) }
                row.addView(TextView(this).apply {
                    text = ic; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#4F46E5")); background = bg(Color.parseColor("#E0E7FF"), 10)
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) })
                row.addView(TextView(this).apply { text = clean; textSize = 14.5f; setTextColor(Color.parseColor("#111827")) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (it.isCheckable) row.addView(TextView(this).apply {
                    text = if (it.isChecked) "চালু" else "বন্ধ"; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor(if (it.isChecked) "#166534" else "#6B7280"))
                    background = bg(Color.parseColor(if (it.isChecked) "#DCFCE7" else "#E5E7EB"), 12); setPadding(dp(10), dp(3), dp(10), dp(3))
                })
                row.setOnClickListener { _ -> pick(it) }
                card.addView(row)
                if (i < its.size - 1) card.addView(View(this).apply { setBackgroundColor(Color.parseColor("#E8EAF2")) },
                    LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(58) })
            }
            col.addView(card)
        }
        val sv = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
        // সবার নিচে স্থির: অ্যাপের নাম + সংস্করণ এবং শেয়ার বাটন
        @Suppress("DEPRECATION")
        val vName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1" } catch (_: Exception) { "0.1" }
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(12)); setBackgroundColor(Color.parseColor("#F5F6FA"))
        }
        foot.addView(TextView(this).apply {
            text = "Daily Browser v$vName.${Updater.curVer(this@MainActivity)}"; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#111827")); maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })
        foot.addView(TextView(this).apply {
            text = "↗ Share"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#4F46E5"))
            background = bg(Color.parseColor("#E0E7FF"), 12); setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { shareApp() }
        })
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); val r = dp(20).toFloat(); cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) }
            clipToOutline = true
            addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(foot, LinearLayout.LayoutParams(-1, -2))
        }
        d.setContentView(outer, ViewGroup.LayoutParams(-1, -1))
        d.setCanceledOnTouchOutside(true)
        // ডান পাশের সাইডবার: টুলবারের নিচের দাগ থেকে শুরু (পর্দার ওপর থেকে মাপা), তাই ⋮ বা বাইরে চাপলে স্বাভাবিকভাবে বন্ধ হয়
        val barView = (anchor.parent as? View) ?: anchor
        val loc = IntArray(2); barView.getLocationOnScreen(loc)
        val top = loc[1] + barView.height
        val dm = resources.displayMetrics
        val w = minOf(dp(320), (dm.widthPixels * 0.84).toInt())
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.TOP or Gravity.END)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(w, dm.heightPixels - top)
            attributes = attributes.apply { y = top; x = 0; dimAmount = 0.4f }
            setWindowAnimations(R.style.SideSheetAnim)
        }
        d.show()
    }

    // ---------- Termux:X11 (কম্পিউটার মোডের পর্দা) ----------
    private val X11_PKG = "com.termux.x11"
    private val X11_URL = "https://github.com/termux/termux-x11/releases/download/nightly/termux-x11-universal-debug.apk"
    private fun x11Installed() = try { packageManager.getPackageInfo(X11_PKG, 0); true } catch (_: Exception) { false }

    private fun openX11() {
        try { packageManager.getLaunchIntentForPackage(X11_PKG)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it) } } catch (_: Exception) {}
    }

    /** কম্পিউটার মোডের সার্ভার চালু হয়েছে বোঝা গেলে (আউটপুটের চিহ্ন দেখে) Termux:X11 অ্যাপ নিজেই খুলে দেয় */
    private fun openX11WhenReady(job: Job) {
        var n = 0
        val r = object : Runnable {
            override fun run() {
                if (job.text().contains("ডেস্কটপ সার্ভার চালু")) { openX11(); return }
                if (job.status != "running" || ++n > 450) return
                handler.postDelayed(this, 2000L)
            }
        }
        handler.postDelayed(r, 2000L)
    }

    private fun installX11() {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "\"Allow from this source\" চালু করে ফিরে এসে আবার চাপুন", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        Toast.makeText(this, "Termux:X11 নামানো হচ্ছে… শেষ হলে ইনস্টল নিশ্চিত করুন", Toast.LENGTH_LONG).show()
        Thread {
            val err = Updater.install(this, X11_URL, false) { }
            if (err != null) runOnUiThread { Toast.makeText(this, "নামানো যায়নি ($err)", Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun manage() {
        val list = Store.all(this)
        val names = list.map { it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("কমান্ড (চাপলে সম্পাদনা)")
            .setItems(names) { _, i -> edit(list[i]) }
            .setPositiveButton("＋ নতুন") { _, _ -> edit(null) }.setNegativeButton("বন্ধ", null).show()
    }

    private fun status() {
        val msg = buildString {
            append(if (Runner.installed(this@MainActivity)) "✓ Termux ইনস্টল আছে\n" else "✗ Termux ইনস্টল নেই (F-Droid থেকে নিন)\n")
            append(if (Runner.hasPermission(this@MainActivity)) "✓ RUN_COMMAND অনুমতি আছে\n\n" else "✗ RUN_COMMAND অনুমতি নেই (⋮ › অ্যাপ অনুমতি)\n\n")
            append(if (Runner.verified(this@MainActivity)) "✓ Termux থেকে সাড়া পাওয়া গেছে — সংযোগ ঠিক আছে\n\n" else "✗ এখনো সংযোগ নিশ্চিত হয়নি — নিচের \"এক চাপে সেটআপ\" চাপুন, Termux-এ Paste করে Enter দিন, তারপর যেকোনো কমান্ড চালান\n\n")
            append("Termux-এ ওয়েব সার্ভার চালালে (যেমন code-server) এখানে localhost:পোর্ট লিখে ব্রাউজারেই ব্যবহার করুন।")
        }
        AlertDialog.Builder(this).setTitle("Termux অবস্থা").setMessage(msg).setPositiveButton("ঠিক আছে", null)
            .setNeutralButton("⚡ এক চাপে সেটআপ") { _, _ -> setupTermux() }.show()
    }

    // ---------- সেশন ----------
    private fun save(sync: Boolean = false) {
        val a = JSONArray()
        val keep = tabs.filter { it.jobKey == null && !it.incognito }
        // ওয়েবভিউয়ের আসল বর্তমান URL (claude.ai-র মতো সিঙ্গেল-পেজ সাইটে চ্যাট বদলালেও সঠিক চ্যাটেই ফিরতে)
        keep.forEach { a.put(if (it.isHome || it.url.isBlank()) "home" else (it.web.url?.takeIf { u -> u.startsWith("http") } ?: it.url)) }
        val sa = JSONArray(); keep.forEach { sa.put(it.site ?: "") }
        val jt = JSONArray(); tabs.forEach { t -> t.jobKey?.let { jt.put(it) } }
        Store.prefs(this).edit().putString("tabs", a.toString()).putInt("cur", maxOf(0, keep.indexOf(cur))).putString("jobtabs", jt.toString()).putString("tabsite", sa.toString()).let { if (sync) it.commit() else it.apply() }
        Jobs.save(this, sync)
    }

    private fun restore() {
        try {
            val a = JSONArray(Store.prefs(this).getString("tabs", "[]"))
            val sa = JSONArray(Store.prefs(this).getString("tabsite", "[]"))
            for (i in 0 until a.length()) {
                val u = a.getString(i)
                val sid = sa.optString(i, "").ifBlank { null }
                val t = newTab(null, sid)
                // ওয়ার্কস্পেস ট্যাবের আলাদা প্রোফাইল ফিরিয়ে আনা না গেলে সাইট লোড করি না (লগইন মিশে যেত)
                if (u == "home" || (sid != null && t.site == null)) loadHome(t) else { t.url = u; t.web.loadUrl(u) }
            }
            val c = Store.prefs(this).getInt("cur", 0)
            if (tabs.isNotEmpty()) select(tabs[c.coerceIn(0, tabs.size - 1)])
        } catch (_: Exception) {}
        // কাজের ট্যাব ও চলমান কাজ ফিরিয়ে আনি: লগ থেকে আবার লাইভ স্ট্রিম শুরু
        try {
            Jobs.load(this)
            val keepCur = cur
            val jt = JSONArray(Store.prefs(this).getString("jobtabs", "[]"))
            for (i in 0 until jt.length()) Jobs.find(jt.getString(i))?.let { openJobTab(it) }
            keepCur?.let { select(it) }
            Jobs.list.filter { it.status == "running" }.forEach { it.reattach = true; Runner.attach(this, it) }
            // Termux ৩ মিনিটেও (প্রতি ১৫ সেকেন্ডে আবার চেষ্টার পরেও) সাড়া না দিলে কাজটা আর চলছে না ধরে নিই — নইলে চিরকাল "চলছে…" দেখাত আর ফোরগ্রাউন্ড সার্ভিস/ওয়েকলক ছাড়ত না
            handler.postDelayed({
                var changed = false
                Jobs.list.filter { it.status == "running" && it.reattach && it.links.get() == 0 && it.attaches >= 4 }.forEach {
                    it.status = "fail"; it.end = System.currentTimeMillis(); it.reattach = false
                    it.out = (it.out + "\n(Termux থেকে সাড়া মেলেনি — কাজটি সম্ভবত আর চলছে না)").trim(); changed = true
                }
                if (changed) { Jobs.save(this); scheduleRefresh() }
            }, 180000L)
            if (Jobs.running() > 0) KeepAliveService.start(this)
        } catch (_: Exception) {}
    }

    private val jobSaver = object : Runnable {
        override fun run() { Jobs.save(this@MainActivity); handler.postDelayed(this, 5000L) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val t = cur
        when {
            customView != null -> hideFullscreen()
            findBar.visibility == View.VISIBLE -> closeFind()
            overlay != null -> hideSwitcher()
            t != null && t.web.canGoBack() -> t.web.goBack()
            t != null && !t.isHome -> loadHome(t)
            tabs.size > 1 && t != null -> closeTab(t)
            else -> super.onBackPressed()
        }
    }
}
