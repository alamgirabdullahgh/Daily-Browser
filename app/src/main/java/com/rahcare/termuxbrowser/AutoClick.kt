package com.rahcare.termuxbrowser

import org.json.JSONArray
import org.json.JSONObject

/**
 * অটো-ক্লিক / অটো-ফিল / অটো-সেন্ড — সব এজেন্টের স্ক্রিপ্ট এক জায়গায়, এজেন্ট অনুযায়ী সাজানো।
 *
 * কাঠামো:
 *   ১) শেয়ার্ড হেল্পার (JS)        — দৃশ্যমান কিনা, লেখা পড়া, আসল ক্লিক-ঘটনা পাঠানো
 *   ২) Profile                       — একটা এজেন্টের জন্য: কোন ঘরে লেখা বসবে, কোন বোতাম চাপলে যাবে, না পেলে কোন কিবোর্ড-ঘটনা
 *   ৩) এজেন্টের প্রোফাইল            — CLAUDE / GEMINI / GROK / IMAGE / AUDIO / VIDEO / GENERIC
 *   ৪) Claude-এর অনুমতি-কার্ড অটো-ক্লিক (পাতা লোডের পর চলে)
 *   ৫) প্রবেশদ্বার                   — profileFor(agent), fillJs(agent, text), sendJs(agent), fallbackJs(agent), pageJs(url)
 *
 * নতুন এজেন্ট যোগ করতে: শুধু একটা Profile বানিয়ে profileFor()-এর when-এ বসান।
 */
object AutoClick {

    // ───────────────────────── ১) শেয়ার্ড হেল্পার ─────────────────────────
    private const val HELPERS = """
function vis(e){var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'}
function tx(e){return(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim()}
function on(e){return !e.disabled&&e.getAttribute('aria-disabled')!=='true'}
function all(sels){var out=[];for(var i=0;i<sels.length;i++){try{var l=document.querySelectorAll(sels[i]);for(var k=0;k<l.length;k++){if(out.indexOf(l[k])<0)out.push(l[k])}}catch(e){}}return out}
function press(b){try{var o={bubbles:true,cancelable:true,view:window};b.dispatchEvent(new PointerEvent('pointerdown',o));b.dispatchEvent(new MouseEvent('mousedown',o));b.dispatchEvent(new PointerEvent('pointerup',o));b.dispatchEvent(new MouseEvent('mouseup',o))}catch(e){}b.click()}
"""

    // ───────────────────────── ২) Profile ─────────────────────────
    /**
     * @param fillSel   লেখা বসানোর ঘর (CSS সিলেক্টর, আগেরটা অগ্রাধিকার)
     * @param largest   true হলে দৃশ্যমান ঘরগুলোর মধ্যে সবচেয়ে বড়টা (যেমন স্ক্রিপ্ট-বক্স আর ছোট স্টাইল-বক্স একসাথে থাকলে)
     * @param sendSel   পাঠানোর/চালানোর বোতামের সিলেক্টর (আগে এগুলো দেখা হয়)
     * @param sendRe    সিলেক্টরে না মিললে বোতামের লেখা/aria-label মেলানোর রেজেক্স
     * @param notRe     যে লেখার বোতাম কখনো চাপা হবে না (feedback, stop ইত্যাদি)
     * @param modEnter  সব বোতাম ব্যর্থ হলে শেষ ভরসা Ctrl+Enter (মিডিয়া টুলে "Run" শর্টকাট); false হলে সাধারণ Enter
     */
    class Profile(
        val name: String,
        val fillSel: List<String>,
        val largest: Boolean,
        val sendSel: List<String>,
        val sendRe: String,
        val notRe: String = "feedback|stop|cancel|attach|upload|বাতিল",
        val modEnter: Boolean = false,
        /** ইনপুট ঘর না থাকলে (শুরুর পাতা) যে লেখার বোতাম/কার্ড চাপলে ঘর আসে — পুরো লেখা মিলতে হবে; খালি = কিছু চাপে না */
        val prepRe: String = ""
    )

    // ───────────────────────── ৩) এজেন্টের প্রোফাইল ─────────────────────────
    /** শুরুর পাতার "নতুন তৈরি" ধরনের বোতাম (পুরো লেখা মিলতে হবে) */
    private const val PREP = "^(create new dialog|new dialog|create dialog|add dialog|create new|new prompt|new script|new project|create project|start creating|start new|get started|start now|try it|open playground)[.!]?$"
    /** অডিও (Text to speech Playground): শুরুর পাতায় "Create new dialog" নয়, "The Everyday Assistant" কার্ডে চাপলে স্ক্রিপ্ট-ঘর খোলে */
    private const val AUDIO_PREP = "^the every ?day assistant$"
    private val CLAUDE = Profile(
        "claude",
        listOf("div.ProseMirror[contenteditable=\"true\"]", "div[contenteditable=\"true\"][role=\"textbox\"]", "[contenteditable=\"true\"]", "textarea"),
        false,
        listOf("button[aria-label*=\"Send\" i]", "button[data-testid*=\"send\" i]"),
        "send|submit|পাঠ"
    )

    /** Gemini: Quill এডিটর (rich-textarea .ql-editor); পাঠানোর বোতাম "Send message" (লেখা বসার পর চালু হয়) */
    private val GEMINI = Profile(
        "gemini",
        listOf("rich-textarea .ql-editor", "div.ql-editor[contenteditable=\"true\"]", "div[contenteditable=\"true\"][role=\"textbox\"]", "textarea"),
        false,
        listOf("button.send-button", "button[aria-label*=\"Send message\" i]", "button[aria-label=\"Send\" i]", "button[mattooltip*=\"Send\" i]"),
        "send message|send|submit|পাঠ"
    )

    private val GROK = Profile(
        "grok",
        listOf("textarea", "div[contenteditable=\"true\"]", "[contenteditable=\"true\"]"),
        false,
        listOf("button[type=\"submit\"]", "button[aria-label*=\"Submit\" i]", "button[aria-label*=\"Send\" i]"),
        "send|submit|পাঠ"
    )

    /** ইমেজ এজেন্ট (AI Studio / ImageFX / Whisk ইত্যাদি): প্রম্পট-বক্সে প্রম্পট বসে, "Run/Generate/Create" চাপে, না পেলে Ctrl+Enter */
    private val IMAGE = Profile(
        "image",
        listOf("textarea", "div[contenteditable=\"true\"][role=\"textbox\"]", "[contenteditable=\"true\"]", "input[type=\"text\"]"),
        true,
        listOf("button[type=\"submit\"]", "button[aria-label*=\"Run\" i]", "button[aria-label*=\"Generate\" i]", "button[aria-label*=\"Create\" i]"),
        "generate image|generate|create|imagine|run|submit|send|তৈরি",
        modEnter = true,
        prepRe = PREP
    )

    /** অডিও এজেন্ট (AI Studio speech / TTS / Suno ইত্যাদি): সবচেয়ে বড় ঘরে (স্ক্রিপ্ট) লেখা বসে, "Run/Generate" চাপে, না পেলে Ctrl+Enter */
    private val AUDIO = Profile(
        "audio",
        listOf("textarea", "div[contenteditable=\"true\"][role=\"textbox\"]", "[contenteditable=\"true\"]", "input[type=\"text\"]"),
        true,
        listOf("button[type=\"submit\"]", "button[aria-label*=\"Run\" i]", "button[aria-label*=\"Generate\" i]", "button[aria-label*=\"Create\" i]"),
        "generate speech|generate audio|generate|create|run|submit|send|তৈরি",
        modEnter = true,
        prepRe = AUDIO_PREP
    )

    private val VIDEO = Profile(
        "video",
        listOf("textarea", "div[contenteditable=\"true\"][role=\"textbox\"]", "[contenteditable=\"true\"]", "input[type=\"text\"]"),
        true,
        listOf("button[type=\"submit\"]", "button[aria-label*=\"Generate\" i]", "button[aria-label*=\"Create\" i]", "button[aria-label*=\"Run\" i]"),
        "generate video|generate|create|run|submit|send|তৈরি",
        modEnter = true,
        prepRe = PREP
    )

    /** অচেনা সাইট: আগের সাধারণ আচরণ (প্রথম দৃশ্যমান ঘর, "send/submit" বোতাম) */
    private val GENERIC = Profile(
        "generic",
        listOf("textarea", "[contenteditable=\"true\"]", "div[role=\"textbox\"]", "input[type=\"text\"]"),
        false,
        emptyList(),
        "send|submit|পাঠ",
        prepRe = PREP
    )

    // ───────────────────────── ৪) Claude-এর অনুমতি-কার্ড অটো-ক্লিক ─────────────────────────
    /**
     * শুধু claude.ai-তে: নির্দিষ্ট দুটো অনুমতি-কার্ড হুবহু মিললে নিজে চাপে; আর কিছুতেই না।
     *  ১) "Claude wants to list your repositories"  → "Always allow"
     *  ২) "Claude wants to add a repository to this session" এবং Access = read → "Allow once"
     *  ৩) একই কার্ড, Access = push, কিন্তু Owner হুবহু alamgirabdullahgh (আপনার নিজের অ্যাকাউন্ট) → "Allow once"
     *     (অন্য কারও রিপোতে push চাইলে এটা চাপে না — আপনাকেই চাপতে হবে)
     * কার্ডের লেখা এই দুইয়ের একটাও না মিললে (কমান্ড চালানো, পুশ, মুছে ফেলা, ক্যামেরা ইত্যাদি) কিছুই চাপে না — আপনাকেই চাপতে হবে।
     * শুধু কার্ডের ভেতরের আসল বোতাম চাপে, তাই চ্যাটের লেখায় একই বাক্য থাকলেও কিছু হয় না।
     */
    const val CLAUDE_PERMS_JS = """(function(){try{
if(location.hostname!=='claude.ai'&&location.hostname!=='www.claude.ai')return;
if(window.__rcAllow)return;window.__rcAllow=1;
function tx(e){return(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();}
function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
var OWN=/(Owner\s*:?\s*alamgirabdullahgh(?=\s|\/|Repo|Access|\s*$)|Repo(sitory)?\s*:?\s*alamgirabdullahgh\/)/i;
function cardOf(btn){var c=btn;for(var k=0;k<12&&c.parentElement;k++){c=c.parentElement;var t=tx(c);if(t.length>1200)return null;if(t.indexOf('Claude wants to')>-1)return c;}return null;}
function decide(s){
if(s.indexOf('Claude wants to list your repositories')>-1)return {ok:1,why:'repo-list',click:['Always allow','Allow once']};
if(s.indexOf('Claude wants to add a repository to this session')>-1){
var push=/Access\s*:?\s*push/i.test(s),read=/Access\s*:?\s*read/i.test(s);
if(read&&!push)return {ok:1,why:'add-repo read',click:['Allow once']};
if(push&&OWN.test(s))return {ok:1,why:'add-repo push, own owner',click:['Allow once']};
if(push)return {ok:0,why:'push but owner is not alamgirabdullahgh'};
return {ok:0,why:'access type (read/push) not found in card'};}
return {ok:0,why:'other card, never auto-clicked'};}
function findBtn(card,label){var bs=card.querySelectorAll('button,[role=button]');for(var i=0;i<bs.length;i++){if(vis(bs[i])&&tx(bs[i])===label)return bs[i];}return null;}
function press(B,n){try{B.click();}catch(e){}
if(n>0){try{var o={bubbles:true,cancelable:true,view:window};B.dispatchEvent(new PointerEvent('pointerdown',o));B.dispatchEvent(new MouseEvent('mousedown',o));B.dispatchEvent(new PointerEvent('pointerup',o));B.dispatchEvent(new MouseEvent('mouseup',o));B.dispatchEvent(new MouseEvent('click',o));}catch(e){}}}
function scan(){
var bs=document.querySelectorAll('button,[role=button]');
for(var i=0;i<bs.length;i++){
var t=tx(bs[i]);
if(t!=='Allow once'&&t!=='Always allow')continue;
if(!vis(bs[i]))continue;
var card=cardOf(bs[i]);if(!card)continue;
var tries=card.__rcTry||0;
if(tries>=3||(card.__rcAt&&Date.now()-card.__rcAt<1200))continue;
var d=decide(tx(card));if(!d.ok)continue;
for(var q=0;q<d.click.length;q++){var B=findBtn(card,d.click[q]);if(B){card.__rcTry=tries+1;card.__rcAt=Date.now();press(B,tries);break;}}
}}
window.__rcDbg=function(){var out=[],bs=document.querySelectorAll('button,[role=button]');
for(var i=0;i<bs.length;i++){var t=tx(bs[i]);if(t!=='Allow once'&&t!=='Always allow')continue;if(!vis(bs[i]))continue;
var c=cardOf(bs[i]);if(!c){out.push('বোতাম পেলাম ('+t+') কিন্তু কার্ড চিনতে পারিনি');continue;}
var s=tx(c),d=decide(s);out.push((d.ok?'চাপবে':'চাপবে না')+' — '+d.why+'\nচেষ্টা: '+(c.__rcTry||0)+'\nকার্ডের লেখা: '+s.slice(0,350));}
return 'স্ক্রিপ্ট চালু আছে। দৃশ্যমান অনুমতি-কার্ড: '+out.length+'\n\n'+out.join('\n---\n');};
new MutationObserver(function(){scan()}).observe(document.documentElement,{childList:true,subtree:true});
setInterval(scan,1000);scan();
}catch(e){}})();"""

    // ───────────────────────── ৫) প্রবেশদ্বার ─────────────────────────
    fun profileFor(agent: String?): Profile = when (agent) {
        "claude" -> CLAUDE; "gemini" -> GEMINI; "grok" -> GROK
        "image" -> IMAGE; "audio" -> AUDIO; "video" -> VIDEO
        else -> GENERIC
    }

    private fun arr(l: List<String>) = JSONArray(l).toString()

    /** ঘরে লেখা বসায় (পাঠায় না)। ফল: ok / no (ঘর নেই) / err */
    fun fillJs(agent: String?, text: String): String {
        val p = profileFor(agent)
        return """(function(txt,sels,largest){try{$HELPERS
var els=[],c=all(sels);for(var i=0;i<c.length;i++){if(vis(c[i]))els.push(c[i])}
if(!els.length)return 'no';
var el=els[0];
if(largest){var best=-1;for(var j=0;j<els.length;j++){var r=els[j].getBoundingClientRect(),a=r.width*r.height;if(a>best){best=a;el=els[j]}}}
el.focus();
if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');d.set.call(el,txt);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))}
else{var okk=false;try{document.execCommand('selectAll',false,null);okk=document.execCommand('insertText',false,txt)}catch(x){}if(!okk||!(el.innerText||el.textContent||'').trim()){el.textContent=txt;el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:txt}))}}
return 'ok'}catch(e){return 'err'}})(${JSONObject.quote(text)},${arr(p.fillSel)},${p.largest})"""
    }

    /** পাঠানোর/চালানোর বোতাম খুঁজে চাপে। ফল: clicked / no (চালু বোতাম মেলেনি) / err */
    fun sendJs(agent: String?): String {
        val p = profileFor(agent)
        return """(function(sels,re,nre){try{$HELPERS
var R=new RegExp(re,'i'),N=new RegExp(nre,'i'),cand=all(sels);
var bs=document.querySelectorAll('button,[role="button"]');
for(var i=0;i<bs.length;i++){var l=((bs[i].getAttribute('aria-label')||'')+' '+(bs[i].getAttribute('title')||'')+' '+(bs[i].getAttribute('data-testid')||'')+' '+tx(bs[i])).trim();if(l&&R.test(l)&&!N.test(l)&&cand.indexOf(bs[i])<0)cand.push(bs[i])}
for(var k=0;k<cand.length;k++){var b=cand[k];if(vis(b)&&on(b)){press(b);return 'clicked'}}
return 'no'}catch(e){return 'err'}})(${arr(p.sendSel)},${JSONObject.quote(p.sendRe)},${JSONObject.quote(p.notRe)})"""
    }

    /** ইনপুট ঘর নেই এমন শুরুর পাতায় "Create new dialog"-জাতীয় বোতাম/কার্ড চাপে (প্রতি পাতায় সর্বোচ্চ ৪ বার)। ফল: clicked:<লেখা> / none / limit / skip */
    fun prepJs(agent: String?): String {
        val re = profileFor(agent).prepRe
        if (re.isEmpty()) return "(function(){return 'skip'})()"
        return """(function(re){try{$HELPERS
if((window.__acPrepN||0)>=4)return 'limit';
var R=new RegExp(re,'i'),els=document.querySelectorAll('button,[role="button"],a,li,div,span'),c=[];
for(var i=0;i<els.length;i++){var e=els[i];if(!vis(e))continue;var t=tx(e);if(!t||t.length>40)continue;if(R.test(t))c.push(e)}
c=c.filter(function(e){return !c.some(function(o){return o!==e&&e.contains(o)})});
if(!c.length)return 'none';
var e=c[0],b=e.closest('button,[role="button"],a')||e;window.__acPrepN=(window.__acPrepN||0)+1;press(b);return 'clicked:'+tx(e).slice(0,30)}catch(x){return 'err'}})(${JSONObject.quote(re)})"""
    }

    /** শেষ ভরসা (বোতাম একেবারে না মিললে): মিডিয়া টুলে Ctrl+Enter, নইলে Enter। ফল: enter */
    fun fallbackJs(agent: String?): String {
        val mod = profileFor(agent).modEnter
        return """(function(mod){try{var el=document.activeElement;if(el){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,ctrlKey:mod,metaKey:mod};el.dispatchEvent(new KeyboardEvent('keydown',o));el.dispatchEvent(new KeyboardEvent('keypress',o));el.dispatchEvent(new KeyboardEvent('keyup',o));return 'enter'}return 'no'}catch(e){return 'err'}})($mod)"""
    }

    /** পাতা লোড শেষে যে অটো-ক্লিক স্ক্রিপ্ট চলবে (না থাকলে null)। স্ক্রিপ্ট নিজেও হোস্ট যাচাই করে */
    fun pageJs(url: String): String? = when {
        url.startsWith("https://claude.ai/") || url.startsWith("https://www.claude.ai/") -> CLAUDE_PERMS_JS
        else -> null
    }
}
