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
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
    var limitHits = 0          // পরপর কয়বার লিমিটের লেখা দেখা গেছে (ভুল শনাক্ত এড়াতে ২ বার লাগে)
    var thumb: Bitmap? = null  // ট্যাব তালিকার ছোট প্রিভিউ
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
    private lateinit var banner: TextView

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
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        banner = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2563EB"))
            setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE; setOnClickListener { startUpdate() }
        }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        restore()
        if (tabs.isEmpty()) { newTab(null); openHome() }
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleIntent(i) }
    private fun handleIntent(i: Intent?) {
        val u = i?.data?.toString() ?: return
        newTab(null); go(u)
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rx, IntentFilter(REFRESH), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rx, IntentFilter(REFRESH))
        Live.onUpdate = { _ -> handler.post { scheduleRefresh() } }
        // অনুমতি/ইনস্টল বদলে থাকলে হোম পেজ নতুন করে আঁকি
        cur?.let { if (it.isHome) loadHome(it) }
        scheduleRefresh()
        askRunPermission()
        askBackgroundSetup()
        if (pendingInstall && packageManager.canRequestPackageInstalls()) { pendingInstall = false; startUpdate() }
        handler.removeCallbacks(updCheck); updCheck.run()
        handler.removeCallbacks(jobSaver); handler.postDelayed(jobSaver, 5000L)
        handler.removeCallbacks(siteWatcher); handler.postDelayed(siteWatcher, 6000L)
    }

    // কোনো সময়ের বাধা নেই: অ্যাপ খুললেই খোঁজে, আর সামনে থাকলে প্রতি ২০ সেকেন্ডে আবার খোঁজে
    private val updCheck = object : Runnable {
        override fun run() {
            Thread { val i = Updater.check(); runOnUiThread { if (i != null) { upd = i.takeIf { it.v > Updater.curVer(this@MainActivity) } }; showBanner() } }.start()
            handler.postDelayed(this, 20000L)
        }
    }

    private fun showBanner() {
        val u = upd
        if (u == null || updBusy) { if (!updBusy) banner.visibility = View.GONE; return }
        banner.text = "⬆ নতুন সংস্করণ এসেছে — চাপুন, আপডেট হয়ে যাবে"; banner.visibility = View.VISIBLE
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
        updBusy = true; banner.text = "ডাউনলোড হচ্ছে… 0%"
        Thread {
            val err = Updater.install(this, u.url) { p -> runOnUiThread { banner.text = if (p < 100) "ডাউনলোড হচ্ছে… $p%" else "ইনস্টল হচ্ছে…" } }
            runOnUiThread {
                if (err != null) { updBusy = false; showBanner(); Toast.makeText(this, "আপডেট শুরু হয়নি ($err)", Toast.LENGTH_LONG).show() }
                else handler.postDelayed({ updBusy = false; showBanner() }, 60000L)
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updCheck); handler.removeCallbacks(jobSaver); handler.removeCallbacks(siteWatcher)
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        Live.onUpdate = null
        save()
    }

    override fun onDestroy() { super.onDestroy(); tabs.forEach { it.web.destroy() } }

    // ---------- ট্যাব ----------
    private fun newTab(url: String?, site: String? = null): Tab {
        val w = WebView(this)
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
        val t = Tab(w)
        t.site = siteOk
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
        }
        applyDesktop(t)
        w.webViewClient = object : WebViewClient() {
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
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { t.url = u; if (t === cur) updateBar() }
            override fun onPageFinished(v: WebView, u: String) {
                t.url = u; if (t.isHome) t.title = "Termux হোম" else if (t.jobKey == null && !v.title.isNullOrBlank()) t.title = v.title!!
                if (t === cur) updateBar()
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (t === cur && t.jobKey == null) { progress.isIndeterminate = false; progress.progress = p; progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE }
            }
            override fun onReceivedTitle(v: WebView, title: String?) { if (!t.isHome && t.jobKey == null && !title.isNullOrBlank()) t.title = title }
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: Message): Boolean {
                val n = newTab(null, t.site)  // পপআপ (যেমন লগইন) একই প্রোফাইলে খুলুক
                (m.obj as WebView.WebViewTransport).webView = n.web; m.sendToTarget(); return true
            }
            override fun onCloseWindow(v: WebView) { closeTab(t) }
        }
        w.setDownloadListener { u, ua, cd, mime, _ ->
            try {
                val rq = DownloadManager.Request(Uri.parse(u)).setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(u, cd, mime))
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(rq)
                Toast.makeText(this, "ডাউনলোড শুরু হয়েছে", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this, "ডাউনলোড ব্যর্থ", Toast.LENGTH_SHORT).show() }
        }
        tabs.add(t); select(t)
        if (url != null && !(site != null && siteOk == null)) w.loadUrl(url)
        return t
    }

    private fun applyDesktop(t: Tab) {
        val ds = t.web.settings
        val ua = WebSettings.getDefaultUserAgent(this)
        ds.userAgentString = if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36" else ua
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
        if (tabs.isEmpty()) { newTab(null); openHome(); return }
        if (cur === t) select(tabs[minOf(i, tabs.size - 1)]) else updateBar()
        if (overlay != null) showSwitcher()
    }

    private fun updateBar() {
        countBox.text = tabs.size.toString()
        val t = cur ?: return
        if (!address.hasFocus()) address.setText(if (t.isHome) "" else if (t.jobKey != null) "⚙ " + t.title else t.url)
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
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (rc == 77) {
            cur?.let { if (it.isHome) loadHome(it) }
            if (g.isNotEmpty() && g[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "অনুমতি না দিলে আইটেম চলবে না: ⋮ › অ্যাপ অনুমতি থেকে দিন", Toast.LENGTH_LONG).show()
        }
    }

    private fun termuxAction(u: Uri, t: Tab) {
        val segs = u.pathSegments.ifEmpty { listOf(u.host ?: "") }
        val kind = u.host ?: ""
        val id = segs.lastOrNull() ?: ""
        when (kind) {
            "run" -> {
                val cmd = Store.all(this).firstOrNull { it.id == id }
                if (cmd == null) { Toast.makeText(this, "কমান্ড পাওয়া যায়নি, হোম রিফ্রেশ করুন", Toast.LENGTH_SHORT).show(); return }
                if (Runner.installed(this) && !Runner.hasPermission(this)) { askRunPermission(true); return }
                val run = if (cmd.id == "d12") cmd.copy(script = cmd.script.replace("@@WEBPW@@", webPw())) else cmd
                val (job, err) = Runner.run(this, run)
                if (err == null && cmd.id == "d12") waitWebTerm()
                if (err != null) AlertDialog.Builder(this).setTitle("চালানো যায়নি").setMessage(err).setPositiveButton("ঠিক আছে", null).show()
                else if (job != null) openJobTab(job)
                else Toast.makeText(this, "Termux স্ক্রিনে চলছে: ${cmd.title}", Toast.LENGTH_SHORT).show()
            }
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

    /** ওয়ার্কস্পেস খোলে (আগে খোলা থাকলে সেটাতেই যায়); fresh=true হলে নতুন চ্যাট লোড করে */
    private fun openSite(id: String, fresh: Boolean = false): Tab? {
        val site = Sites.all(this).firstOrNull { it.id == id } ?: return null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            AlertDialog.Builder(this).setTitle("আলাদা লগইন চালু করা যাচ্ছে না")
                .setMessage("এই ফোনের Android System WebView পুরনো। Play Store থেকে \"Android System WebView\" (ও Chrome) আপডেট করে আবার চেষ্টা করুন। নইলে সব সাইটের লগইন মিশে যেত, তাই খোলা হচ্ছে না।")
                .setPositiveButton("ঠিক আছে", null).show()
            return null
        }
        val ex = tabs.firstOrNull { it.site == id }
        if (ex != null) { select(ex); if (fresh) ex.web.loadUrl(site.url); return ex }
        return newTab(site.url, id)
    }

    private fun editSite(site: Site?) {
        val list = Sites.all(this)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val n = EditText(this).apply { hint = "নাম (যেমন Claude)"; setText(site?.name ?: "") }
        val u = EditText(this).apply { hint = "ওয়েবসাইট (যেমন claude.ai)"; setText(site?.url ?: ""); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        l.addView(n); l.addView(u)
        val d = AlertDialog.Builder(this).setTitle(if (site == null) "নতুন ওয়ার্কস্পেস" else "ওয়ার্কস্পেস সম্পাদনা").setView(l)
            .setPositiveButton("সেভ") { _, _ ->
                val name = n.text.toString().trim(); val url = Sites.normalizeUrl(u.text.toString())
                if (name.isEmpty() || url.isEmpty()) return@setPositiveButton
                if (site == null) list.add(Site("s${System.currentTimeMillis()}", name, url))
                else list.firstOrNull { it.id == site.id }?.let { it.name = name; it.url = url }
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
            val t = cur
            if (t != null && t.site != null && overlay == null && !limitDialogOpen && System.currentTimeMillis() - t.limitAt > 600000L) {
                t.web.evaluateJavascript(Sites.LIMIT_JS) { raw ->
                    val hit = Sites.unquote(raw)
                    if (hit.isBlank() || t !== cur) t.limitHits = 0
                    else {
                        t.limitHits++
                        if (t.limitHits >= 2 && !limitDialogOpen && System.currentTimeMillis() - t.limitAt > 600000L) {
                            t.limitHits = 0; t.limitAt = System.currentTimeMillis(); onLimit(t, hit)
                        }
                    }
                }
            }
            handler.postDelayed(this, 6000L)
        }
    }

    /** তালিকায় এর পরের সেই ওয়ার্কস্পেস যার লিমিট সম্প্রতি শেষ হয়নি; না থাকলে null (দুটো সাইটের মধ্যে অনন্ত দৌড় এড়ায়) */
    private fun nextAvailable(list: List<Site>, id: String?): Site? {
        if (list.size < 2) return null
        val i = list.indexOfFirst { it.id == id }; val now = System.currentTimeMillis()
        for (k in 1 until list.size) {
            val s = list[((i + k) % list.size + list.size) % list.size]
            if ((siteLimited[s.id] ?: 0L) < now) return s
        }
        return null
    }

    private fun onLimit(t: Tab, hit: String) {
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
        from.web.evaluateJavascript(Sites.EXTRACT_JS) { raw ->
            val chat = Sites.unquote(raw).trim()
            val payload = if (chat.isBlank()) "" else Sites.handoffText(fromName, chat)
            if (payload.isNotEmpty()) (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("chat", payload))
            val tab = openSite(to.id, true)
            if (tab != null) {
                if (payload.isEmpty()) Toast.makeText(this, "কপি করার মতো লেখা পাওয়া যায়নি — শুধু ${to.name} খোলা হয়েছে", Toast.LENGTH_LONG).show()
                else {
                    if (!send) Toast.makeText(this, "চ্যাট কপি হয়েছে — ${to.name}-এর ঘরে বসানোর চেষ্টা চলছে (না বসলে পেস্ট করুন)", Toast.LENGTH_LONG).show()
                    fillInput(tab, payload, 0, send)
                }
            }
        }
    }

    private fun fillInput(t: Tab, text: String, n: Int, send: Boolean) {
        if (n >= 6) { if (send) Toast.makeText(this, "ঘরে বসানো যায়নি — চ্যাট ক্লিপবোর্ডে আছে, পেস্ট করে পাঠান", Toast.LENGTH_LONG).show(); return }
        handler.postDelayed({
            if (!tabs.contains(t)) return@postDelayed
            t.web.evaluateJavascript(Sites.fillJs(text)) { r ->
                if (r == null || !r.contains("ok")) fillInput(t, text, n + 1, send)
                else if (send) sendInput(t, 0)
            }
        }, 4000L)
    }

    /** বসানো লেখা পাঠায়: বোতাম চেপে, না পেলে Enter দিয়ে (সাইটভেদে বোতাম তৈরি হতে একটু সময় লাগে, তাই কয়েকবার চেষ্টা) */
    private fun sendInput(t: Tab, n: Int) {
        if (n >= 3) return
        handler.postDelayed({
            if (!tabs.contains(t)) return@postDelayed
            t.web.evaluateJavascript(Sites.SEND_JS) { r -> if (r == null || r.contains("no") || r.contains("err")) sendInput(t, n + 1) }
        }, 1500L)
    }

    private fun refreshHomes() { tabs.filter { it.isHome }.forEach { loadHome(it) } }

    // ---------- ট্যাব তালিকা ----------
    private fun hideSwitcher() { overlay?.let { content.removeView(it) }; overlay = null }

    private fun showSwitcher() {
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

    // ---------- মেনু ----------
    private fun menu(anchor: View) {
        val m = PopupMenu(this, anchor)
        if (upd != null) m.menu.add(0, 8, 0, "⬆ অ্যাপ আপডেট করুন")
        m.menu.add(0, 1, 0, "নতুন ট্যাব")
        m.menu.add(0, 2, 1, "রিলোড")
        m.menu.add(0, 3, 2, "ডেস্কটপ সাইট").apply { isCheckable = true; isChecked = desktop }
        m.menu.add(0, 4, 3, "Termux কমান্ড ম্যানেজ")
        m.menu.add(0, 5, 4, "Termux সেটআপ ও অবস্থা")
        m.menu.add(0, 6, 5, "অ্যাপ অনুমতি (সেটিংস)")
        m.menu.add(0, 7, 6, "ব্রাউজিং ডেটা মুছুন")
        cur?.takeIf { it.site != null }?.let { m.menu.add(0, 9, 0, "🔁 এই চ্যাট অন্য ওয়ার্কস্পেসে নিন") }
        m.menu.add(0, 10, 7, "⚡ লিমিটে স্বয়ংক্রিয় সুইচ").apply { isCheckable = true; isChecked = autoSwitch }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                8 -> startUpdate()
                9 -> cur?.let { t -> pickHandoff(t) }
                10 -> { autoSwitch = !autoSwitch; Store.prefs(this).edit().putBoolean("autoswitch", autoSwitch).apply()
                    Toast.makeText(this, if (autoSwitch) "স্বয়ংক্রিয় সুইচ চালু" else "স্বয়ংক্রিয় সুইচ বন্ধ — শুধু প্রস্তাব আসবে", Toast.LENGTH_SHORT).show() }
                1 -> { newTab(null); openHome() }
                2 -> cur?.let { t -> if (t.isHome) loadHome(t) else t.web.reload() }
                3 -> { desktop = !desktop; Store.prefs(this).edit().putBoolean("desktop", desktop).apply()
                    tabs.forEach { t -> applyDesktop(t) }; cur?.let { t -> if (!t.isHome) t.web.reload() } }
                4 -> manage()
                5 -> status()
                6 -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                7 -> { WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null)
                    tabs.forEach { t -> t.web.clearCache(true); t.web.clearHistory() }; Toast.makeText(this, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show() }
            }
            true
        }
        m.show()
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
            append("Termux-এ একবার চালান:\nmkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties\nতারপর Termux রিস্টার্ট করুন।\n\nTermux-এ ওয়েব সার্ভার চালালে (যেমন code-server) এখানে localhost:পোর্ট লিখে ব্রাউজারেই ব্যবহার করুন।")
        }
        AlertDialog.Builder(this).setTitle("Termux অবস্থা").setMessage(msg).setPositiveButton("ঠিক আছে", null).show()
    }

    // ---------- সেশন ----------
    private fun save() {
        val a = JSONArray()
        val keep = tabs.filter { it.jobKey == null }
        keep.forEach { a.put(if (it.isHome || it.url.isBlank()) "home" else it.url) }
        val sa = JSONArray(); keep.forEach { sa.put(it.site ?: "") }
        val jt = JSONArray(); tabs.forEach { t -> t.jobKey?.let { jt.put(it) } }
        Store.prefs(this).edit().putString("tabs", a.toString()).putInt("cur", maxOf(0, keep.indexOf(cur))).putString("jobtabs", jt.toString()).putString("tabsite", sa.toString()).apply()
        Jobs.save(this)
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
                if (u == "home" || (sid != null && t.site == null)) loadHome(t) else t.web.loadUrl(u)
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
            // Termux ১ মিনিটেও সাড়া না দিলে কাজটা আর চলছে না ধরে নিই — নইলে চিরকাল "চলছে…" দেখাত আর ফোরগ্রাউন্ড সার্ভিস/ওয়েকলক ছাড়ত না
            handler.postDelayed({
                var changed = false
                Jobs.list.filter { it.status == "running" && it.reattach }.forEach {
                    it.status = "fail"; it.end = System.currentTimeMillis(); it.reattach = false
                    it.out = (it.out + "\n(Termux থেকে সাড়া মেলেনি — কাজটি সম্ভবত আর চলছে না)").trim(); changed = true
                }
                if (changed) { Jobs.save(this); scheduleRefresh() }
            }, 60000L)
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
            overlay != null -> hideSwitcher()
            t != null && t.web.canGoBack() -> t.web.goBack()
            t != null && !t.isHome -> loadHome(t)
            tabs.size > 1 && t != null -> closeTab(t)
            else -> super.onBackPressed()
        }
    }
}
