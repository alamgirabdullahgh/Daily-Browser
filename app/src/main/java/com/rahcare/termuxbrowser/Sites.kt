package com.rahcare.termuxbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/** একটি ওয়ার্কস্পেস = যেকোনো ওয়েবসাইট + তার নিজস্ব আলাদা ও স্থায়ী লগইন (কুকি/সেশন)। কোডে কোনো সাইটের নাম নেই — ব্যবহারকারী যোগ/বদল/মোছে */
data class Site(val id: String, var name: String, var url: String)

object Sites {
    private fun sp(c: Context) = c.getSharedPreferences("sites", Context.MODE_PRIVATE)

    /** শুরুতে তালিকা খালি — কোনো সাইটের নাম আগে থেকে বসানো নেই; ব্যবহারকারী নিজে যেকোনো সাইট যোগ করে */
    private val defaults = emptyList<Site>()

    fun all(c: Context): MutableList<Site> {
        val raw = sp(c).getString("list", null) ?: return defaults.map { it.copy() }.toMutableList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { val o = a.getJSONObject(it); Site(o.getString("id"), o.getString("n"), o.getString("u")) }.toMutableList()
        } catch (_: Exception) { defaults.map { it.copy() }.toMutableList() }
    }

    fun save(c: Context, list: List<Site>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("u", it.url)) }
        sp(c).edit().putString("list", a.toString()).apply()
    }

    /** প্রতিটি ওয়ার্কস্পেসের নিজস্ব WebView-প্রোফাইলের নাম (কুকি, লগইন, স্টোরেজ আলাদা থাকে) */
    fun profile(id: String) = "p_" + id.filter { it.isLetterOrDigit() || it == '_' }

    /** তালিকার পরের ওয়ার্কস্পেস (শেষে গেলে প্রথমটা); একটাই থাকলে null */
    fun next(list: List<Site>, id: String?): Site? {
        if (list.size < 2) return null
        val i = list.indexOfFirst { it.id == id }
        return list[(i + 1) % list.size]
    }

    fun normalizeUrl(s: String): String {
        val t = s.trim()
        return if (t.isEmpty()) "" else if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    /** পাতার ব্যানার/UI-র শেষ ৩০ লাইনে আসল "লিমিট শেষ" বাক্য খোঁজে। বাদ: ব্যবহারকারীর নিজের মেসেজ (হ্যান্ডঅফে পেস্ট করা পুরনো লিমিট-বার্তা সহ), মডেলের উত্তরের লেখা (markdown/prose), কোট, কোড, ইনপুট ঘর, স্ট্রিম হতে থাকা উত্তর ও আগাম সতর্কতা।
     *  সাইটের নিজের ব্যানার/ত্রুটি-বার্তা চ্যাটের article-এর ভেতরে বসলেও ধরে (আগের সংস্করণে article বাদ দেওয়ায় আসল লিমিট-বার্তা চাপা পড়ত)। পেলে লেখাটা ফেরত দেয়, নইলে খালি */
    private const val SCAN = """var skip='[data-message-author-role="user"],[data-testid="user-message"],[data-testid*="user-message"],[class*="user-message"],[data-is-streaming="true"],[class*="font-claude"],[class*="markdown"],[class*="prose"],blockquote,pre,code,textarea,input,[contenteditable],script,style,noscript';var warn=/approach|close to|nearly|almost|running (low|out)|about to|will (reach|hit)|\b[1-9]\d*\s+(messages?|requests?)\s+(left|remaining)/i;var re=/\bhit your[^.\n]{0,40}\b(limit|quota)\b|upgrade to keep chatting|(you['’]?ve|you have|you['’]?re|you are)\s+(reached|hit|exceeded|used (up )?all|out of)[^.\n]{0,60}\b(limit|quota|cap|messages?|usage|requests?|credits)\b|\b(usage|message|rate|daily|weekly|hourly|free plan|plan)\s+(limit|cap|quota)s?\b[^.\n]{0,40}\b(reached|exceeded|hit|resets?|until)\b|\b(limit|quota)\s+(reached|exceeded|will reset|resets?)\b|reached\s+(the|your|our)\s+[^.\n]{0,30}\b(limit|quota)\b|too many (requests|messages)|(out of|no more)\s+(free\s+)?(messages|usage|credits|requests)\b/i;var r=(function(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),seen=new Set(),out=[],n;while(n=w.nextNode()){var p=n.parentElement;if(!p||seen.has(p)||p.closest(skip))continue;seen.add(p);var b=p.getBoundingClientRect();if(b.width<=0||b.height<=0)continue;var s=(p.innerText||'').trim();if(s.length>140)s=(n.nodeValue||'').trim();if(s.length>3&&s.length<=140)out.push(s)}var L=out.slice(-30),hit='';for(var i=L.length-1;i>=0;i--){var s=L[i];if(/[“”"«»]/.test(s)||warn.test(s))continue;if(re.test(s)){hit=s;break}}return {hit:hit,lines:L}})();"""
    const val LIMIT_JS = "(function(){try{" + SCAN + "return r.hit}catch(e){return ''}})()"
    /** নেট ধীর হলে সাইট যে "Checking for a response…"/"Reconnecting" ধরনের আটকে-থাকা অবস্থা দেখায় তা ধরে (উত্তর/কোট/কোডের ভেতরের লেখা বাদ)। পেলে লেখাটা, নইলে খালি */
    const val STUCK_JS = """(function(){try{var skip='[data-message-author-role="user"],[data-testid*="user-message"],[class*="user-message"],[class*="font-claude"],[class*="markdown"],[class*="prose"],blockquote,pre,code,textarea,input,[contenteditable],script,style,noscript';var re=/checking for a response to your message|reconnecting|connection (was )?(lost|interrupted)|lost connection|network error/i;var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n;while(n=w.nextNode()){var p=n.parentElement;if(!p||p.closest(skip))continue;var s=(n.nodeValue||'').trim();if(s.length<4||s.length>140||!re.test(s))continue;var b=p.getBoundingClientRect();if(b.width<=0||b.height<=0)continue;return s}return ''}catch(e){return ''}})()"""

    /** আটকে-থাকা অবস্থায় প্রথম নরম চেষ্টা: online/focus সংকেত + "Retry/Try again/Reconnect" বোতাম থাকলে চাপ */
    const val STUCK_SOFT_JS = """(function(){try{window.dispatchEvent(new Event('online'));window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'));var c=document.querySelectorAll('button,[role="button"],a');for(var i=0;i<c.length;i++){var e=c[i],r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)continue;var t=(e.innerText||'').trim();if(/^(retry|try again|reconnect|refresh)$/i.test(t)){e.click();return 'clicked'}}return 'events'}catch(e){return 'err'}})()"""

    /** "Upgrade to keep chatting"-জাতীয় আপগ্রেড পপআপ থাকলে ওপরের ✕ (না থাকলে "Not now") চেপে সরায়, যাতে পেছনের কাজ/সুইচ আটকে না থাকে। ফল: closed / খালি */
    const val DISMISS_JS = """(function(){try{var vis=function(e){var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'};var re=/upgrade to keep chatting|hit your[^.\n]{0,40}limit|message limit|usage limit/i;var ds=document.querySelectorAll('[role="dialog"],[aria-modal="true"],dialog');for(var i=0;i<ds.length;i++){var d=ds[i];if(!vis(d)||!re.test(d.innerText||''))continue;var bs=d.querySelectorAll('button,[role="button"],a'),dr=d.getBoundingClientRect(),x=null,y=null,z=null;for(var j=0;j<bs.length;j++){var b=bs[j];if(!vis(b))continue;var l=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.getAttribute('data-testid')||'')).toLowerCase(),t=(b.innerText||'').trim();if(!x&&/close|dismiss|বন্ধ/.test(l))x=b;if(!y&&t===''&&b.querySelector('svg')){var r=b.getBoundingClientRect();if(r.top-dr.top<120&&dr.right-r.right<120)y=b}if(!z&&/^(not now|maybe later|no thanks|dismiss|close)$/i.test(t))z=b}var k=x||y||z;if(k){k.click();return 'closed'}}return ''}catch(e){return ''}})()"""

    /** ⋮ › "লিমিট শনাক্তকরণ পরীক্ষা": ডিটেক্টর এই মুহূর্তে কী দেখছে — ফল ও শেষ ১০ লাইন */
    const val LIMIT_DEBUG_JS = "(function(){try{" + SCAN + "return 'ফল: '+(r.hit||'(কিছু শনাক্ত হয়নি)')+'\\n---\\n'+r.lines.slice(-10).join('\\n')}catch(e){return 'ERR '+e}})()"

    /** চলতি চ্যাটের শেষ অংশের লেখা (main অংশ, না পেলে পুরো পাতা) */
    const val EXTRACT_JS = """(function(){try{var r=document.querySelector('main')||document.body;var t=(r.innerText||'').replace(/\n{3,}/g,'\n\n').trim();return t.slice(-6000)}catch(e){return ''}})()"""

    /** সুইচের পর চ্যাটের ঘরের আগে যদি প্ল্যান/স্বাগত পেজ আটকায়: "Use Claude for free"-জাতীয় বোতাম চাপে, না থাকলে ওপরের বাম তীর (←) চাপে।
     *  ফল: input (ঘর আছে, কিছু করেনি) / free / back / plans (পেজ আটকে, বোতাম মেলেনি) / none */
    const val PASS_JS = """(function(){try{var vis=function(e){var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'};var inp=document.querySelectorAll('textarea,[contenteditable="true"],div[role="textbox"]');for(var i=0;i<inp.length;i++){if(vis(inp[i]))return 'input'}var free=/^\s*(use claude for free|continue with free|continue for free|try claude for free|get started for free|start for free|use for free)\s*$/i;var c=document.querySelectorAll('button,a,[role="button"]');for(var i=0;i<c.length;i++){var e=c[i];if(!vis(e)||e.disabled)continue;var t=(e.innerText||e.textContent||'').trim();if(t.length<40&&free.test(t)){e.click();return 'free'}}var plans=/plans that grow|choose your plan|pricing|upgrade (your )?plan|use claude for free/i.test((document.body.innerText||'').slice(0,3000));if(!plans)return 'none';var best=null;for(var i=0;i<c.length;i++){var e=c[i];if(!vis(e))continue;var r=e.getBoundingClientRect();if(r.top>160||r.left>130)continue;var l=((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('data-testid')||'')).toLowerCase();var t=(e.innerText||'').trim();if(/back|previous|পিছ/.test(l)){best=e;break}if(t===''&&e.querySelector('svg')&&r.width<90&&!best)best=e}if(best){best.click();return 'back'}return 'plans'}catch(x){return 'err'}})()"""

    /** ক্লিপবোর্ডের ছবি ফোকাস করা ঘরে "পেস্ট" ঘটনা হিসেবে পাঠায় (সাইট নিজের মতো সংযুক্ত করে নেয়); সাইট না ধরলে প্রথম ফাইল-ইনপুটে বসায়। ফল: pasted / input / err… */
    fun pasteImageJs(b64: String, mime: String, name: String) = """(function(b,m,n){try{var bin=atob(b),k=bin.length,u=new Uint8Array(k);for(var i=0;i<k;i++)u[i]=bin.charCodeAt(i);var f=new File([u],n,{type:m});var dt=new DataTransfer();dt.items.add(f);var el=document.activeElement||document.body;var ev=new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true});var handled=!el.dispatchEvent(ev);if(!handled){var inp=document.querySelector('input[type="file"]');if(inp){inp.files=dt.files;inp.dispatchEvent(new Event('change',{bubbles:true}));return 'input'}}return 'pasted'}catch(e){return 'err '+e}})(${JSONObject.quote(b64)},${JSONObject.quote(mime)},${JSONObject.quote(name)})"""

    /** evaluateJavascript-এর ফল (JSON-স্ট্রিং) থেকে সাধারণ লেখা */
    fun unquote(raw: String?): String = try { (JSONTokener(raw ?: "").nextValue() as? String) ?: "" } catch (_: Exception) { "" }

    /** চ্যাটের লেখা থেকে সবচেয়ে শেষে উল্লেখ করা GitHub রিপো (মালিক/রিপো) বের করে — github.com লিংক বা আগের হ্যান্ডঅফের "GitHub রিপো:" লাইন থেকে; না পেলে খালি */
    fun repoFromChat(chat: String): String {
        val bad = setOf("settings", "apps", "login", "orgs", "marketplace", "features", "topics", "sponsors", "notifications", "user", "users")
        val found = ArrayList<Pair<Int, String>>()
        for (re in listOf(Regex("GitHub রিপো:\\s*([\\w.-]+/[\\w.-]+)"), Regex("github\\.com/([\\w.-]+/[\\w.-]+)", RegexOption.IGNORE_CASE))) {
            for (m in re.findAll(chat)) {
                var r = m.groupValues[1].removeSuffix(".git").trimEnd('.', '-')
                val owner = r.substringBefore('/')
                if (r.contains('/') && owner.lowercase() !in bad && r.substringAfter('/').isNotEmpty()) found.add(m.range.first to r)
            }
        }
        return found.maxByOrNull { it.first }?.second ?: ""
    }

    /** চ্যাটটা GitHub/রিপোর কাজ নিয়ে কিনা (টোকেন ও রিপো-তথ্য শুধু তখনই যায়) */
    fun looksGit(chat: String): Boolean {
        val t = chat.lowercase()
        return listOf("github", "repo", "রিপো", "commit", "কমিট", "clone", "git push", "বিল্ড", "apk", "workflow").any { t.contains(it) }
    }

    /** টোকেনধারীর সাম্প্রতিক পুশ-করা রিপোর তালিকা (নতুন থেকে পুরনো, সর্বোচ্চ ৬)। নেটওয়ার্ক-কাজ, তাই ব্যাকগ্রাউন্ড থ্রেডে ডাকতে হবে; ব্যর্থ হলে খালি */
    fun recentRepos(token: String): List<String> = try {
        val cn = URL("https://api.github.com/user/repos?per_page=6&sort=pushed").openConnection() as HttpURLConnection
        cn.connectTimeout = 6000; cn.readTimeout = 6000
        cn.setRequestProperty("Authorization", "Bearer $token")
        cn.setRequestProperty("Accept", "application/vnd.github+json")
        if (cn.responseCode != 200) emptyList() else {
            val a = JSONArray(cn.inputStream.bufferedReader().readText())
            (0 until a.length()).map { a.getJSONObject(it).getString("full_name") }
        }
    } catch (_: Exception) { emptyList() }

    /** repo জানা থাকলে বার্তায় রিপোর নাম যায়; জানা না থাকলে (কিন্তু টোকেন আছে) cands-এর সাম্প্রতিক রিপোর তালিকা যায়, যাতে নতুন সেশন নিজেই ঠিক রিপোটা বেছে নেয় */
    fun handoffText(from: String, chat: String, repo: String = "", token: String = "", cands: List<String> = emptyList()): String {
        val head = "[এটা আগের কথোপকথন ($from থেকে নেওয়া)। এটা পড়ে কাজ যেখানে থেমেছিল সেখান থেকেই চালিয়ে যাও। আমার সর্বশেষ প্রশ্নের উত্তর যদি না পেয়ে থাকি, সেটারই উত্তর দাও।]"
        val gh = when {
            repo.isNotBlank() -> "\n[GitHub রিপো: $repo — এই কাজ এই রিপোতেই। এই সেশনে রিপোটা যোগ (add_repo) করে ক্লোন করে নাও, তারপর কাজ চালাও।]"
            cands.isNotEmpty() -> "\n[GitHub রিপো নির্দিষ্ট করা নেই। আমার সাম্প্রতিক পুশ-করা রিপো (নতুন থেকে পুরনো): ${cands.joinToString(", ")} — চ্যাটের কাজ যে রিপোর, সেটা এখান থেকে বেছে নিয়ে ক্লোন করো; মিল না পেলে আমাকে জিজ্ঞেস করো।]"
            else -> ""
        }
        val tk = if (token.isBlank()) "" else "\n[GitHub টোকেন (আমার নিজের, এই কাজের জন্যই): $token — রিপো clone/pull/push করতে এটা ব্যবহার করো (যেমন https://x-access-token:টোকেন@github.com/মালিক/রিপো.git)। এটা গোপন: কোনো ফাইল, কমিট, লগ বা উত্তরে কখনো লিখবে না, রিপোর remote URL-এও রেখে দেবে না। কাজ শেষ হলে আমাকে জানাবে যেন আমি টোকেনটা বাতিল করি।]"
        return "$head$gh$tk\n\n$chat"
    }

    /** আগের ট্যাবটা Claude Code চ্যাট (ঠিকানার পথ /code দিয়ে শুরু) এবং গন্তব্য একই সাইটের (যেমন claude.ai) অন্য ওয়ার্কস্পেস কিনা */
    fun isCodeHandoff(fromUrl: String?, to: Site): Boolean = try {
        val f = Uri.parse(fromUrl ?: ""); val t = Uri.parse(to.url)
        val fh = (f.host ?: "").removePrefix("www."); val th = (t.host ?: "").removePrefix("www.")
        fh.isNotEmpty() && fh == th && (f.path ?: "").startsWith("/code")
    } catch (_: Exception) { false }

    /** গন্তব্যে খোলার ঠিকানা: Claude Code চ্যাট হলে ওই সাইটের /code, নইলে ওয়ার্কস্পেসের নিজের ঠিকানা */
    fun handoffUrl(fromUrl: String?, to: Site): String =
        if (isCodeHandoff(fromUrl, to)) "https://" + (Uri.parse(to.url).host ?: "") + "/code" else to.url

    /** লগইন করা অ্যাকাউন্টের ইমেইল ওই সাইটের নিজের (একই ডোমেইনের) API থেকে আনতে শুরু করে; ফল window.__tbEmail-এ বসে (কিছুক্ষণ পর EMAIL_READ_JS দিয়ে পড়তে হয়) */
    const val EMAIL_JS = """(function(){try{window.__tbEmail='';var ep=['/api/account','/api/bootstrap','/api/auth/session','/api/me','/api/user','/api/users/me','/api/v1/me'];var re=/"(?:email_address|email|emailAddress|user_email)"\s*:\s*"([^"@\s\\]+@[^"\s\\]+\.[^"\s\\]+)"/;var dom=function(){try{if(location.hostname.indexOf('google.')<0)return;var els=document.querySelectorAll('[aria-label*="@"],[title*="@"]');var r2=/([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})/;for(var k=0;k<els.length;k++){var tx=els[k].getAttribute('aria-label')||els[k].getAttribute('title')||'';var mm=r2.exec(tx);if(mm){window.__tbEmail=mm[1];return}}}catch(e){}};var go=function(i){if(window.__tbEmail)return;if(i>=ep.length){dom();return}fetch(ep[i],{credentials:'include',headers:{'Accept':'application/json'}}).then(function(r){return r.ok?r.text():''}).then(function(t){var m=re.exec(t||'');if(m)window.__tbEmail=m[1];else go(i+1)}).catch(function(){go(i+1)})};go(0);return 'started'}catch(e){return 'err'}})()"""
    const val EMAIL_READ_JS = """(function(){try{return window.__tbEmail||''}catch(e){return ''}})()"""

    /** পাতায় থাকা প্রথম GitHub রিপোর লিংক থেকে "মালিক/রিপো" বের করে (সেটিংসে রিপো না দেওয়া থাকলে চেষ্টা করে); না পেলে খালি */
    const val REPO_JS = """(function(){try{var bad=['settings','apps','login','orgs','marketplace','features','topics','sponsors','notifications'];var a=document.querySelectorAll('a[href*="github.com/"]');for(var i=0;i<a.length;i++){var m=(a[i].href||'').match(/^https:\/\/github\.com\/([\w.-]+)\/([\w.-]+)/);if(m&&bad.indexOf(m[1])<0)return m[1]+'/'+m[2]}return ''}catch(e){return ''}})()"""
}
