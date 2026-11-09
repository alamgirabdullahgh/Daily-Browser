package com.rahcare.termuxbrowser

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * blob: ও data: ডাউনলোড।
 * - data: লিংক সরাসরি ডিকোড করে Downloads-এ রাখা হয়।
 * - blob: লিংক শুধু পেজের ভেতর থেকেই পড়া যায়; তাই পেজে একটি ছোট স্ক্রিপ্ট চালিয়ে ফাইলটি ৩৮৪ KB করে টুকরো-টুকরো করে এখানে আনা হয়।
 * নিরাপত্তা: ব্রিজের মেথড (chunk/done/fail) শুধু এই অ্যাপের বানানো এক-বারের টোকেন নিয়ে কাজ করে।
 * টোকেন ছাড়া ডাকলে কিছুই হয় না, তাই অন্য কোনো সাইট নিজে থেকে ফাইল লেখাতে পারে না।
 */
class BlobBridge(private val ctx: Context, private val onMain: (() -> Unit) -> Unit) {

    private class Job(val tmp: File, val out: FileOutputStream, val name: String, val born: Long) { var bytes = 0L }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val maxBytes = 200L * 1024 * 1024

    // ---------- অ্যাপের ভেতর থেকে ডাকা (JS থেকে নয়) ----------

    fun saveData(url: String, cd: String?, mime: String?) {
        try {
            val c = url.indexOf(',')
            if (c < 0) throw IllegalArgumentException("bad data url")
            val head = url.substring(5, c)
            val body = url.substring(c + 1)
            val m = head.substringBefore(';').ifBlank { mime ?: "text/plain" }
            val bytes = if (head.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT)
            else URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").toByteArray()
            if (bytes.size > maxBytes) { toast("ফাইল অনেক বড় (সর্বোচ্চ ২০০ MB)"); return }
            val tmp = File(ctx.cacheDir, "dl_" + System.nanoTime() + ".part")
            tmp.writeBytes(bytes)
            val name = finalName(fileName(url, cd, m), m)
            finish(tmp, name, m)
        } catch (e: Exception) { toast("ডাউনলোড ব্যর্থ") }
    }

    fun start(web: WebView, url: String, cd: String?, mime: String?) {
        purge()
        val name = fileName(url, cd, mime)
        val tok = java.util.UUID.randomUUID().toString()
        val tmp = File(ctx.cacheDir, "dl_$tok.part")
        try { jobs[tok] = Job(tmp, FileOutputStream(tmp), name, System.currentTimeMillis()) }
        catch (e: Exception) { toast("ডাউনলোড ব্যর্থ"); return }
        val js = "(function(T,U){var D=window.DailyDl;function F(m){try{D.fail(T,String(m))}catch(e){}}" +
            "try{fetch(U).then(function(r){return r.blob()}).then(function(b){var C=393216,i=0;" +
            "function next(){if(i>=b.size){D.done(T,b.type||'');return}" +
            "var rd=new FileReader();" +
            "rd.onload=function(){var s=String(rd.result),k=s.indexOf(',');D.chunk(T,s.substring(k+1));i+=C;next()};" +
            "rd.onerror=function(){F('read')};rd.readAsDataURL(b.slice(i,i+C))}" +
            "next()}).catch(function(e){F(e)})}catch(e){F(e)}})(" + JSONObject.quote(tok) + "," + JSONObject.quote(url) + ")"
        web.post { web.evaluateJavascript(js, null) }
        toast("ডাউনলোড শুরু হচ্ছে: $name")
    }

    // ---------- পেজের স্ক্রিপ্ট যা ডাকে (শুধু বৈধ টোকেনসহ) ----------

    @JavascriptInterface
    fun chunk(tok: String, b64: String) {
        val j = jobs[tok] ?: return
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            j.bytes += bytes.size
            if (j.bytes > maxBytes) { abort(tok, "ফাইল অনেক বড় (সর্বোচ্চ ২০০ MB)"); return }
            j.out.write(bytes)
        } catch (e: Exception) { abort(tok, "ডাউনলোড ব্যর্থ") }
    }

    @JavascriptInterface
    fun done(tok: String, mime: String?) {
        val j = jobs.remove(tok) ?: return
        try {
            j.out.close()
            val m = if (mime.isNullOrBlank()) null else mime
            finish(j.tmp, finalName(j.name, m), m)
        } catch (e: Exception) { j.tmp.delete(); toast("ডাউনলোড ব্যর্থ") }
    }

    @JavascriptInterface
    fun fail(tok: String, msg: String?) { abort(tok, "ডাউনলোড ব্যর্থ — পেজটি ফাইল পড়তে দেয়নি") }

    // ---------- ভেতরের সাহায্যকারী ----------

    private fun abort(tok: String, msg: String) {
        val j = jobs.remove(tok) ?: return
        try { j.out.close() } catch (_: Exception) {}
        j.tmp.delete()
        toast(msg)
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        jobs.entries.filter { now - it.value.born > 10 * 60 * 1000 }.forEach { abort(it.key, "ডাউনলোড সময়সীমা পেরিয়ে গেছে") }
    }

    private fun ext(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';').trim())
    }

    private fun fileName(url: String, cd: String?, mime: String?): String {
        var n = try { URLUtil.guessFileName(url, cd, mime) } catch (_: Exception) { "" }
        if (n.isBlank() || n.startsWith("downloadfile")) n = "download_" + System.currentTimeMillis() + (ext(mime)?.let { ".$it" } ?: "")
        return n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120)
    }

    /** নামে এক্সটেনশন না থাকলে বা .bin হলে আসল MIME থেকে ঠিক করা */
    private fun finalName(name: String, mime: String?): String {
        val e = ext(mime) ?: return name
        val dot = name.lastIndexOf('.')
        return when {
            dot < 0 -> "$name.$e"
            name.substring(dot).equals(".bin", true) && !e.equals("bin", true) -> name.substring(0, dot) + "." + e
            else -> name
        }
    }

    private fun finish(tmp: File, name: String, mime: String?) {
        val m = if (mime.isNullOrBlank()) "application/octet-stream" else mime
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, m)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val r = ctx.contentResolver
                val u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("insert")
                r.openOutputStream(u)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0); r.update(u, cv, null, null)
                tmp.delete()
                toast("ডাউনলোড সম্পন্ন: $name (Downloads ফোল্ডারে)")
                return
            } catch (_: Exception) { /* নিচের ফোল্ডারে চেষ্টা */ }
        }
        try {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            dir.mkdirs()
            var f = File(dir, name); var i = 1
            while (f.exists()) { f = File(dir, name.substringBeforeLast('.', name) + "_" + i + (name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })); i++ }
            tmp.copyTo(f); tmp.delete()
            toast("ডাউনলোড সম্পন্ন: ${f.name} (অ্যাপের Downloads ফোল্ডারে)")
        } catch (e: Exception) { tmp.delete(); toast("ডাউনলোড ব্যর্থ — ফাইল সংরক্ষণ করা যায়নি") }
    }

    private fun toast(msg: String) { onMain { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } }
}
