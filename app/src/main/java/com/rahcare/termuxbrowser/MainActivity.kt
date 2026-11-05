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
        setContentView(root)

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
        if (rc == 4711) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null }
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
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rx, IntentFilter(REFRESH), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rx, IntentFilter(REFRESH))
        Live.onUpdate = { _ -> handler.post { scheduleRefresh() } }
        // অনুমতি/ইনস্টল বদলে থাকলে হোম পেজ নতুন করে আঁকি
        cur?.let { if (it.isHome) loadHome(it) }
        scheduleRefresh()
        askRunPermission()
        askBackgroundSetup()
        handler.postDelayed({ if (!isFinishing) offerTermuxSetup() }, 2000L)
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
    private fun newTab(url: String?, site: String? = null, incognito: Boolean = false, reuseProfile: String? = null): Tab {
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
        w.setFindListener { idx, n, _ -> if (t === cur) findCount.text = if (n == 0) "০" else "${idx + 1}/$n" }
        w.setOnLongClickListener { _ -> handleLongPress(t) }
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
            // সংযোগ/DNS/SSL ত্রুটিতে Chrome-এর মতো ত্রুটির পাতা (আবার চেষ্টার লিংকসহ)
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                val c = e.errorCode
                if (c == WebViewClient.ERROR_HOST_LOOKUP || c == WebViewClient.ERROR_CONNECT || c == WebViewClient.ERROR_TIMEOUT || c == WebViewClient.ERROR_IO || c == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                    val u = r.url.toString()
                    v.loadDataWithBaseURL(u, Browse.errorHtml(u, e.description.toString()), "text/html", "utf-8", u)
                }
            }
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                h.cancel()
                if (e.url == v.url) v.loadDataWithBaseURL(e.url, Browse.errorHtml(e.url, "সংযোগ নিরাপদ নয় (SSL): সাইটের সার্টিফিকেট যাচাই করা যায়নি"), "text/html", "utf-8", e.url)
            }
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { t.url = u; if (t === cur) updateBar() }
            override fun onPageFinished(v: WebView, u: String) {
                t.url = u; if (t.isHome) t.title = "Termux হোম" else if (t.jobKey == null && !v.title.isNullOrBlank()) t.title = v.title!!
                t.site?.let { sid -> if (!(Uri.parse(u).host ?: "").endsWith("github.com")) detectEmail(t, sid, true) }
                if (!t.incognito && !t.isHome && t.jobKey == null && u.startsWith("http") && v.title != Browse.ERR_TITLE) History.add(this@MainActivity, v.title ?: u, u)
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
        t.profileName?.let { pn -> if (tabs.none { o -> o.profileName == pn }) try { ProfileStore.getInstance().deleteProfile(pn) } catch (_: Exception) {} }
        if (tabs.isEmpty()) { newTab(null); openHome(); return }
        if (cur === t) select(tabs[minOf(i, tabs.size - 1)]) else updateBar()
        if (overlay != null) showSwitcher()
    }

    private fun updateBar() {
        countBox.text = tabs.size.toString()
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

    private fun termuxAction(u: Uri, t: Tab) {
        val segs = u.pathSegments.ifEmpty { listOf(u.host ?: "") }
        val kind = u.host ?: ""
        val id = segs.lastOrNull() ?: ""
        when (kind) {
            "setup" -> setupTermux()
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
        l.addView(n); l.addView(u)
        val d = AlertDialog.Builder(this).setTitle(if (site == null) "নতুন ওয়ার্কস্পেস" else "ওয়ার্কস্পেস সম্পাদনা").setView(l)
            .setPositiveButton("সেভ") { _, _ ->
                val typed = n.text.toString().trim(); val url = Sites.normalizeUrl(u.text.toString())
                if (url.isEmpty()) return@setPositiveButton
                val sp = Store.prefs(this)
                if (site == null) {
                    val id = "s${System.currentTimeMillis()}"
                    val host = (Uri.parse(url).host ?: url).removePrefix("www.")
                    list.add(Site(id, typed.ifEmpty { host }, url))
                    sp.edit().putBoolean("autoname_$id", typed.isEmpty()).apply()
                } else list.firstOrNull { it.id == site.id }?.let { s ->
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
            } catch (_: Exception) {
            } finally {
                handler.postDelayed(this, 6000L)
            }
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
        // আগের ট্যাব Claude Code চ্যাট হলে গন্তব্যেও সাধারণ চ্যাট নয়, Claude Code (/code) পেজ খোলে এবং রিপোর নাম সাথে পাঠায়
        val fromUrl = from.web.url ?: from.url
        val isCode = Sites.isCodeHandoff(fromUrl, to)
        val destUrl = Sites.handoffUrl(fromUrl, to)
        from.web.evaluateJavascript(Sites.EXTRACT_JS) { raw ->
            val chat = Sites.unquote(raw).trim()
            fun proceed(repo: String) {
                val payload = if (chat.isBlank()) "" else Sites.handoffText(fromName, chat, repo)
                if (payload.isNotEmpty()) (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("chat", payload))
                val tab = openSite(to.id, true, destUrl)
                if (tab != null) {
                    if (payload.isEmpty()) Toast.makeText(this, "কপি করার মতো লেখা পাওয়া যায়নি — শুধু ${to.name} খোলা হয়েছে", Toast.LENGTH_LONG).show()
                    else {
                        if (!send) Toast.makeText(this, "চ্যাট কপি হয়েছে — ${to.name}-এর ঘরে বসানোর চেষ্টা চলছে (না বসলে পেস্ট করুন)", Toast.LENGTH_LONG).show()
                        fillInput(tab, payload, 0, send)
                    }
                }
            }
            if (!isCode) proceed("")
            else {
                // রিপো: মেনু থেকে দেওয়া থাকলে সেটা, নইলে আগের পাতার GitHub লিংক থেকে খুঁজে নেয়
                val saved = Store.prefs(this).getString("gitrepo", "")?.trim().orEmpty()
                if (saved.isNotEmpty()) proceed(saved)
                else from.web.evaluateJavascript(Sites.REPO_JS) { r -> proceed(Sites.unquote(r).trim()) }
            }
        }
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
        // বসানোর পর পাঠানোর বোতাম চালু হতে সময় লাগে (লেখা লম্বা হলে আরও) — তাই চালু বোতাম পাওয়া পর্যন্ত ১২ বার চেষ্টা; শেষে Enter
        if (n > 12) return
        handler.postDelayed({
            if (!tabs.contains(t)) return@postDelayed
            val js = if (n == 12) Sites.ENTER_JS else Sites.SEND_JS
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
        if (cur?.web?.canGoForward() == true) m.menu.add(0, 12, 1, "এগিয়ে যান →")
        m.menu.add(0, 3, 2, "ডেস্কটপ সাইট").apply { isCheckable = true; isChecked = desktop }
        m.menu.add(0, 4, 3, "Termux কমান্ড ম্যানেজ")
        m.menu.add(0, 5, 4, "Termux সেটআপ ও অবস্থা")
        m.menu.add(0, 6, 5, "অ্যাপ অনুমতি (সেটিংস)")
        m.menu.add(0, 7, 6, "ব্রাউজিং ডেটা মুছুন")
        cur?.takeIf { it.site != null }?.let { m.menu.add(0, 9, 0, "🔁 এই চ্যাট অন্য ওয়ার্কস্পেসে নিন") }
        cur?.takeIf { it.site == null && it.jobKey == null && !it.isHome && it.url.startsWith("http") }?.let { m.menu.add(0, 11, 1, "🌐 এই সাইটকে ওয়ার্কস্পেস বানান (আলাদা লগইন)") }
        cur?.takeIf { it.site != null }?.let { m.menu.add(0, 14, 9, "🔍 লিমিট শনাক্তকরণ পরীক্ষা") }
        m.menu.add(0, 10, 7, "⚡ লিমিটে স্বয়ংক্রিয় সুইচ").apply { isCheckable = true; isChecked = autoSwitch }
        m.menu.add(0, 13, 8, "🔗 ডিফল্ট GitHub রিপো (Claude Code)")
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
        m.menu.add(0, 21, 2, "📚 বুকমার্ক তালিকা")
        m.menu.add(0, 22, 2, "🕘 ইতিহাস")
        m.menu.add(0, 27, 2, "🕶 নতুন ইনকগনিটো ট্যাব")
        m.menu.add(0, 29, 2, "🔠 লেখার আকার")
        m.menu.add(0, 30, 2, "⬇ ডাউনলোড তালিকা")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                13 -> editRepo()
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
                10 -> { autoSwitch = !autoSwitch; Store.prefs(this).edit().putBoolean("autoswitch", autoSwitch).apply()
                    Toast.makeText(this, if (autoSwitch) "স্বয়ংক্রিয় সুইচ চালু" else "স্বয়ংক্রিয় সুইচ বন্ধ — শুধু প্রস্তাব আসবে", Toast.LENGTH_SHORT).show() }
                1 -> { newTab(null); openHome() }
                2 -> reloadCur()
                12 -> cur?.web?.goForward()
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
            append(if (Runner.verified(this@MainActivity)) "✓ Termux থেকে সাড়া পাওয়া গেছে — সংযোগ ঠিক আছে\n\n" else "✗ এখনো সংযোগ নিশ্চিত হয়নি — নিচের \"এক চাপে সেটআপ\" চাপুন, Termux-এ Paste করে Enter দিন, তারপর যেকোনো কমান্ড চালান\n\n")
            append("Termux-এ ওয়েব সার্ভার চালালে (যেমন code-server) এখানে localhost:পোর্ট লিখে ব্রাউজারেই ব্যবহার করুন।")
        }
        AlertDialog.Builder(this).setTitle("Termux অবস্থা").setMessage(msg).setPositiveButton("ঠিক আছে", null)
            .setNeutralButton("⚡ এক চাপে সেটআপ") { _, _ -> setupTermux() }.show()
    }

    // ---------- সেশন ----------
    private fun save() {
        val a = JSONArray()
        val keep = tabs.filter { it.jobKey == null && !it.incognito }
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
