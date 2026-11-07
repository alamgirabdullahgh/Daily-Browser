package com.rahcare.termuxbrowser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** GitHub ইমেইল+পাসওয়ার্ড ফোনের Android Keystore-এ এনক্রিপ্ট করে রাখে (খোলা লেখায় কোথাও থাকে না)। শুধু github.com-এর লগইন পাতায় বসে। */
object GitCred {
    private const val ALIAS = "tb_gh_cred"
    private const val PREF = "ghcred_enc"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun put(c: Context, email: String, pass: String): Boolean = try {
        val sp = Store.prefs(c)
        if (email.isBlank() && pass.isBlank()) { sp.edit().remove(PREF).apply(); true }
        else {
            val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key())
            val ct = ci.doFinal(JSONObject().put("e", email.trim()).put("p", pass).toString().toByteArray(Charsets.UTF_8))
            sp.edit().putString(PREF, Base64.encodeToString(ci.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply(); true
        }
    } catch (_: Exception) { false }
    /** (ইমেইল, পাসওয়ার্ড); না থাকলে null */
    fun get(c: Context): Pair<String, String>? = try {
        val raw = Store.prefs(c).getString(PREF, null)
        if (raw.isNullOrEmpty()) null else {
            val parts = raw.split(":")
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val j = JSONObject(String(ci.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            val e = j.optString("e"); val p = j.optString("p")
            if (e.isBlank() || p.isEmpty()) null else e to p
        }
    } catch (_: Exception) { null }
    fun has(c: Context) = !Store.prefs(c).getString(PREF, null).isNullOrEmpty()
}

/**
 * Claude যখন বলে "আমার GitHub টুল/কানেকশন নেই", তখন নিজে থেকে: Customize → Connectors → GitHub Integration → Connect,
 * GitHub-এ সংরক্ষিত ইমেইল/পাসওয়ার্ড দিয়ে লগইন, Authorize/Install চেপে কানেক্ট করে চ্যাটে ফিরে কাজ চালু রাখে।
 * আগে থেকে ইনস্টল থাকলে রিপোজিটরি-অ্যাক্সেস পাতায় Only select repositories → All repositories → Save (এটা না করলে কানেক্ট অসম্পূর্ণ থাকে),
 * আর পারমিশন-আপডেটের হলুদ বারে Review request / Accept new permissions-ও চাপে।
 * সীমা: পাসওয়ার্ড শুধু github.com/login বা /session পাতায় বসে; ২-ধাপ যাচাই/ক্যাপচা এলে থেমে আপনাকে জানায়।
 */
object GitConnect {
    const val CONNECTORS = "https://claude.ai/customize/connectors/integration-github"

    /** শেষ উত্তরে (Claude-এর নিজের লেখায়) GitHub টুল/কানেকশন নেই বোঝালে লেখাটা ফেরত দেয়, নইলে '' */
    const val DETECT_JS = """(function(){try{
if(location.hostname.indexOf('claude.ai')<0)return '';
var els=document.querySelectorAll('[class*="font-claude"]');if(!els.length)return '';
var e=els[els.length-1];if(e.closest('[data-is-streaming="true"]')||e.getAttribute('data-is-streaming')==='true')return '';
var s=(e.innerText||'').replace(/\s+/g,' ').trim();if(!s)return '';s=s.slice(-1800);
var re=/(don['’]?t|do not|can['’]?t|cannot|unable to|not able to|no)\b[^.\n]{0,70}\b(access|connect|connection|tool|tools|integration)\b[^.\n]{0,50}github|github[^.\n]{0,60}(not connected|isn['’]?t connected|is not connected|not available|not enabled|no (tool|tools|access|connection)|disconnected)|no github (tool|tools|access|connection|integration)|(connect|enable|authorize|reconnect) (your )?github|গিটহাব[^.\n।]{0,50}(কানেক্ট হয়নি|সংযুক্ত নয়|অ্যাক্সেস নেই|সংযোগ নেই)/i;
var m=s.match(re);return m?s.slice(Math.max(0,m.index-20),m.index+160):'';
}catch(x){return ''}})()"""

    /** claude.ai-এর GitHub Integration পাতায়: কানেক্ট আছে কিনা দেখে, না থাকলে Connect, পরে Install on GitHub চাপে */
    fun claudeJs(allowInstall: Boolean) = """(function(){try{
function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}
function tx(e){return(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim()}
var bs=[].slice.call(document.querySelectorAll('button,[role=button],a')).filter(vis);
function by(re){for(var i=0;i<bs.length;i++){var t=tx(bs[i]);if(t.length<40&&re.test(t)&&!bs[i].disabled)return bs[i]}return null}
var dis=by(/^(disconnect|disconnect github)${'$'}/i);
var conn=by(/^(connect|connect github|install github account|connect account)${'$'}/i);
if(conn){conn.click();return 'clicked:Connect'}
var ins=by(/^(install on github|install github app|install claude github app|install)${'$'}/i);
if(dis&&ins&&$allowInstall){ins.click();return 'clicked:Install'}
if(dis)return 'connected';
return 'waiting'}catch(x){return 'err'}})()"""

    /** github.com পাতায়: লগইন ফর্ম (শুধু cred != null হলে), ২-ধাপ/যাচাই শনাক্ত, Authorize/Install চাপা */
    fun githubJs(email: String?, pass: String?, code: String? = null) = """(function(){try{
var CODE=${JSONObject.quote(code ?: "")};
function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}
function tx(e){return(e.innerText||e.value||e.textContent||'').replace(/\s+/g,' ').trim()}
var p=location.pathname,body=(document.body.innerText||'').slice(0,4000);
var otp=document.getElementById('otp');
if(otp&&vis(otp)&&/e-?mail/i.test((document.body.innerText||'').slice(0,4000))&&!/captcha|prove you are human|verify you are human/i.test((document.body.innerText||'').slice(0,4000))){
 if(!CODE)return 'needcode';
 var d0=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d0.set.call(otp,CODE);otp.dispatchEvent(new Event('input',{bubbles:true}));otp.dispatchEvent(new Event('change',{bubbles:true}));
 var vb=document.querySelector('form button[type=submit],form input[type=submit],button[type=submit]');if(vb){vb.click();return 'codefilled'}
 var fm=otp.form;if(fm){if(fm.requestSubmit)fm.requestSubmit();else fm.submit();return 'codefilled'}return 'waiting'}
if(/two-factor|verified-device|recovery|\/sessions\/(sudo|verify)/i.test(p)||/verify your device|two-factor authentication|authentication code|captcha|prove you are human|verify you are human/i.test(body))return 'manual';
var lf=document.getElementById('login_field'),pw=document.getElementById('password');
if(lf&&pw&&vis(lf)){
 if(!${JSONObject.quote(email ?: "")})return 'nocred';
 function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))}
 setv(lf,${JSONObject.quote(email ?: "")});setv(pw,${JSONObject.quote(pass ?: "")});
 var sb=document.querySelector('input[name=commit],button[type=submit],input[type=submit]');if(sb){sb.click();return 'login'}return 'waiting'}
var bs=[].slice.call(document.querySelectorAll('button,input[type=submit],a.btn,[role=button]')).filter(vis);
function btn(re){for(var k=0;k<bs.length;k++){var u=tx(bs[k]);if(u.length<45&&re.test(u)&&!bs[k].disabled&&!/uninstall|suspend|delete|cancel|revoke|deny/i.test(u))return bs[k]}return null}
/* ১) হলুদ বার "Claude is requesting an update to its permissions" → Review request / Accept new permissions */
var rv=btn(/^(review request|accept new permissions)${'$'}/i);
if(rv){
 var RK='drGhRev',rc={n:0,t:0};
 try{var rq=JSON.parse(sessionStorage.getItem(RK)||'null');if(rq&&Date.now()-rq.t<600000)rc=rq}catch(e){}
 if(rc.n<3){try{sessionStorage.setItem(RK,JSON.stringify({n:rc.n+1,t:rc.t||Date.now()}))}catch(e){}rv.click();return 'clicked:'+tx(rv).slice(0,30)}}
/* ২) রিপোজিটরি অ্যাক্সেস পাতা: Only select repositories → আবার All repositories → Save (এরপরই পুরো কানেক্ট হয়)।
   ধাপ sessionStorage-এ মনে রাখা হয় (৩ সেকেন্ডের প্রতি টিকে একটা করে ক্লিক); ১০ মিনিট পুরোনো হলে নতুন করে শুরু */
function rlab(r){var l=r.id?document.querySelector('label[for="'+r.id+'"]'):null;var t=l?tx(l):'';if(!t){var c=r.closest('label,.form-checkbox,li,div');t=c?tx(c):''}return t}
var allR=null,selR=null;
[].slice.call(document.querySelectorAll('input[type=radio]')).forEach(function(r){var t=rlab(r);if(/^all repositories/i.test(t))allR=r;else if(/^only select repositories/i.test(t))selR=r});
if(allR&&selR&&/\/installations\//.test(p)){
 var KEY='drGhRepo',o={s:'',t:0};
 try{var q=JSON.parse(sessionStorage.getItem(KEY)||'null');if(q&&Date.now()-q.t<600000)o=q}catch(e){}
 function sst(s,x){try{sessionStorage.setItem(KEY,JSON.stringify({s:s,t:Date.now(),o:x||0}))}catch(e){}}
 if(o.s==='done')return 'waiting';
 if(o.s==='saved'){
  if(o.o&&performance.timeOrigin===o.o)return 'waiting';
  if(allR.checked){sst('done');return 'repodone'}
  sst('');o={s:'',t:0}}
 if(o.s===''){
  if(allR.checked){selR.click();sst('sel');return 'clicked:OnlySelect'}
  sst('sel');o.s='sel'}
 if(o.s==='sel'){allR.click();sst('all');return 'clicked:AllRepos'}
 if(o.s==='all'){
  if(!allR.checked){allR.click();return 'clicked:AllRepos'}
  var sv=btn(/^(save|install|install & authorize|install and authorize|update access)${'$'}/i);
  if(sv){var tt=tx(sv).slice(0,30);sst('saved',performance.timeOrigin);sv.click();return 'clicked:'+tt}
  return 'waiting'}}
/* ৩) বাকি সব (Authorize / Install / Save…) আগের মতো */
for(var i=0;i<bs.length;i++){var t=tx(bs[i]);
 if(/uninstall|suspend|delete|cancel|revoke|deny/i.test(t))continue;
 if(/^authorize\b/i.test(t)||/^(install|install & authorize|install and authorize|save|update access)${'$'}/i.test(t)){if(bs[i].disabled)continue;bs[i].click();return 'clicked:'+t.slice(0,30)}}
return 'waiting'}catch(x){return 'err'}})()"""
}
