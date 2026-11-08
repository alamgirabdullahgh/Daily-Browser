package com.rahcare.termuxbrowser

/** Google লগইন মোড ২: WebView-এ থাকে না কিন্তু আসল Chrome-এ থাকে এমন কয়েকটি বৈশিষ্ট্য পেজ লোডের শুরুতেই বসায় */
object ChromeShim {
    const val JS = "(function(){try{" +
        "if(!/^https?:/.test(location.href)||location.hostname==='appassets.androidplatform.net')return;" +
        "if(!window.chrome){window.chrome={app:{isInstalled:false},runtime:{},loadTimes:function(){return{}},csi:function(){return{}}}}" +
        "}catch(e){}})()"
}
