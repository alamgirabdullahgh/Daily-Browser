package com.rahcare.termuxbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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

    /** পাতার ব্যানার/UI-র শেষ ৩০ লাইনে আসল "লিমিট শেষ" বাক্য খোঁজে। চ্যাটের মেসেজ, কোট, কোড, ইনপুট ঘর ও আগাম সতর্কতা ("প্রায় শেষ", "X বাকি", "৯০%") বাদ। পেলে লেখাটা ফেরত দেয়, নইলে খালি */
    const val LIMIT_JS = """(function(){try{var skip='[data-testid*="message"],[data-message-author-role],[data-is-streaming],[class*="font-claude"],[class*="markdown"],[class*="prose"],article,[role="article"],blockquote,pre,code,textarea,[contenteditable],script,style,noscript';var warn=/approach|close to|nearly|almost|running (low|out)|remaining|\d+\s*%|\d+\s+(messages?|requests?)\s+left|will (reach|hit)|about to/i;var re=/(you['’]?ve|you have|you['’]?re|you are)\s+(reached|hit|exceeded|used (up )?all)[^.\n]{0,60}\b(limit|quota|cap)\b|\b(usage|message|rate|daily|weekly|hourly|free plan|plan)\s+(limit|cap|quota)s?\s+(reached|exceeded|hit)\b|\b(limit|quota)\s+(reached|exceeded)\b|reached\s+(the|your|our)\s+[^.\n]{0,30}\b(limit|quota)\b|(out of|no more)\s+(free\s+)?(messages|usage|credits|requests)\b/i;var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),seen=[],out=[],n;while(n=w.nextNode()){var p=n.parentElement;if(!p||seen.indexOf(p)>=0||p.closest(skip))continue;seen.push(p);var r=p.getBoundingClientRect();if(r.width<=0||r.height<=0)continue;var s=(p.innerText||'').trim();if(s.length>140)s=(n.nodeValue||'').trim();if(s.length>3&&s.length<=140)out.push(s)}var L=out.slice(-30);for(var i=L.length-1;i>=0;i--){var s=L[i];if(/[“”"«»]/.test(s)||warn.test(s))continue;if(re.test(s))return s}return ''}catch(e){return ''}})()"""

    /** চলতি চ্যাটের শেষ অংশের লেখা (main অংশ, না পেলে পুরো পাতা) */
    const val EXTRACT_JS = """(function(){try{var r=document.querySelector('main')||document.body;var t=(r.innerText||'').replace(/\n{3,}/g,'\n\n').trim();return t.slice(-6000)}catch(e){return ''}})()"""

    /** প্রথম দৃশ্যমান ইনপুট ঘরে লেখা বসায় (পাঠায় না); বসলে "ok" */
    fun fillJs(text: String) = """(function(txt){try{var els=document.querySelectorAll('textarea,[contenteditable="true"],div[role="textbox"],input[type="text"]');var el=null;for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();if(r.width>0&&r.height>0){el=els[i];break}}if(!el)return 'no';el.focus();if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');d.set.call(el,txt);el.dispatchEvent(new Event('input',{bubbles:true}))}else{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,txt)}return 'ok'}catch(e){return 'err'}})(${JSONObject.quote(text)})"""

    /** পাঠানোর বোতাম খুঁজে চাপে (না পেলে Enter চাপ দেয়); "clicked"/"enter"/"no" */
    const val SEND_JS = """(function(){try{var b=null,c=document.querySelectorAll('button,[role="button"]');for(var i=0;i<c.length;i++){var e=c[i],l=((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('data-testid')||'')+' '+(e.type||'')).toLowerCase();var r=e.getBoundingClientRect();if(r.width>0&&r.height>0&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'&&(l.indexOf('send')>=0||l.indexOf('submit')>=0||l.indexOf('পাঠ')>=0)){b=e;break}}if(b){b.click();return 'clicked'}var el=document.activeElement;if(el){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};el.dispatchEvent(new KeyboardEvent('keydown',o));el.dispatchEvent(new KeyboardEvent('keyup',o));return 'enter'}return 'no'}catch(e){return 'err'}})()"""

    /** evaluateJavascript-এর ফল (JSON-স্ট্রিং) থেকে সাধারণ লেখা */
    fun unquote(raw: String?): String = try { (JSONTokener(raw ?: "").nextValue() as? String) ?: "" } catch (_: Exception) { "" }

    /** repo খালি না হলে (Claude Code চ্যাট) হ্যান্ডঅফের লেখায় রিপোর নাম যায়, যাতে নতুন সেশন নিজেই রিপোটা যোগ করে নেয় */
    fun handoffText(from: String, chat: String, repo: String = ""): String {
        val head = "[এটা আগের কথোপকথন ($from থেকে নেওয়া)। এটা পড়ে কাজ যেখানে থেমেছিল সেখান থেকেই চালিয়ে যাও। আমার সর্বশেষ প্রশ্নের উত্তর যদি না পেয়ে থাকি, সেটারই উত্তর দাও।]"
        val gh = if (repo.isBlank()) "" else "\n[GitHub রিপো: $repo — এই সেশনে রিপোটা যোগ (add_repo) করে ক্লোন করে নাও, তারপর কাজ চালাও।]"
        return "$head$gh\n\n$chat"
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
    const val EMAIL_JS = """(function(){try{window.__tbEmail='';var ep=['/api/account','/api/bootstrap','/api/auth/session','/api/me','/api/user','/api/users/me','/api/v1/me'];var re=/"(?:email_address|email|emailAddress|user_email)"\s*:\s*"([^"@\s\\]+@[^"\s\\]+\.[^"\s\\]+)"/;var go=function(i){if(i>=ep.length||window.__tbEmail)return;fetch(ep[i],{credentials:'include',headers:{'Accept':'application/json'}}).then(function(r){return r.ok?r.text():''}).then(function(t){var m=re.exec(t||'');if(m)window.__tbEmail=m[1];else go(i+1)}).catch(function(){go(i+1)})};go(0);return 'started'}catch(e){return 'err'}})()"""
    const val EMAIL_READ_JS = """(function(){try{return window.__tbEmail||''}catch(e){return ''}})()"""

    /** পাতায় থাকা প্রথম GitHub রিপোর লিংক থেকে "মালিক/রিপো" বের করে (সেটিংসে রিপো না দেওয়া থাকলে চেষ্টা করে); না পেলে খালি */
    const val REPO_JS = """(function(){try{var bad=['settings','apps','login','orgs','marketplace','features','topics','sponsors','notifications'];var a=document.querySelectorAll('a[href*="github.com/"]');for(var i=0;i<a.length;i++){var m=(a[i].href||'').match(/^https:\/\/github\.com\/([\w.-]+)\/([\w.-]+)/);if(m&&bad.indexOf(m[1])<0)return m[1]+'/'+m[2]}return ''}catch(e){return ''}})()"""
}
