package com.rahcare.termuxbrowser

import android.content.Context
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

    /** পাতার শেষ অংশে "লিমিট শেষ" ধরনের বাক্য খোঁজে; পেলে মিলে যাওয়া লেখা ফেরত দেয়, নইলে খালি। যেকোনো সাইটে একই নিয়ম */
    const val LIMIT_JS = """(function(){try{var t=(document.body&&document.body.innerText)||'';t=t.slice(-3000);var re=/(you['’]?ve|you have)\s+(reached|hit|exceeded)[^.\n]{0,60}\b(limit|quota|cap)\b|\b(usage|message|rate)\s+limit\s+(reached|exceeded)|reached\s+(the|your|our)\s+[^.\n]{0,30}limit|too many (requests|messages)|limit (reached|exceeded)|free plan limit/i;var m=t.match(re);return m?m[0]:''}catch(e){return ''}})()"""

    /** চলতি চ্যাটের শেষ অংশের লেখা (main অংশ, না পেলে পুরো পাতা) */
    const val EXTRACT_JS = """(function(){try{var r=document.querySelector('main')||document.body;var t=(r.innerText||'').replace(/\n{3,}/g,'\n\n').trim();return t.slice(-6000)}catch(e){return ''}})()"""

    /** প্রথম দৃশ্যমান ইনপুট ঘরে লেখা বসায় (পাঠায় না); বসলে "ok" */
    fun fillJs(text: String) = """(function(txt){try{var els=document.querySelectorAll('textarea,[contenteditable="true"],div[role="textbox"],input[type="text"]');var el=null;for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();if(r.width>0&&r.height>0){el=els[i];break}}if(!el)return 'no';el.focus();if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');d.set.call(el,txt);el.dispatchEvent(new Event('input',{bubbles:true}))}else{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,txt)}return 'ok'}catch(e){return 'err'}})(${JSONObject.quote(text)})"""

    /** পাঠানোর বোতাম খুঁজে চাপে (না পেলে Enter চাপ দেয়); "clicked"/"enter"/"no" */
    const val SEND_JS = """(function(){try{var b=null,c=document.querySelectorAll('button,[role="button"]');for(var i=0;i<c.length;i++){var e=c[i],l=((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('data-testid')||'')+' '+(e.type||'')).toLowerCase();var r=e.getBoundingClientRect();if(r.width>0&&r.height>0&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'&&(l.indexOf('send')>=0||l.indexOf('submit')>=0||l.indexOf('পাঠ')>=0)){b=e;break}}if(b){b.click();return 'clicked'}var el=document.activeElement;if(el){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};el.dispatchEvent(new KeyboardEvent('keydown',o));el.dispatchEvent(new KeyboardEvent('keyup',o));return 'enter'}return 'no'}catch(e){return 'err'}})()"""

    /** evaluateJavascript-এর ফল (JSON-স্ট্রিং) থেকে সাধারণ লেখা */
    fun unquote(raw: String?): String = try { (JSONTokener(raw ?: "").nextValue() as? String) ?: "" } catch (_: Exception) { "" }

    fun handoffText(from: String, chat: String) =
        "[এটা আগের কথোপকথন ($from থেকে নেওয়া)। এটা পড়ে কাজ যেখানে থেমেছিল সেখান থেকেই চালিয়ে যাও। আমার সর্বশেষ প্রশ্নের উত্তর যদি না পেয়ে থাকি, সেটারই উত্তর দাও।]\n\n$chat"
}
