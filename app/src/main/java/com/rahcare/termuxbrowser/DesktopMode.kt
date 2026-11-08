package com.rahcare.termuxbrowser

/** ডেস্কটপ মোড: UA ছাড়াও ডেস্কটপ-ভিউপোর্ট, navigator.platform ও userAgentData বদলায় (গুগল ক্রোমের "ডেস্কটপ সাইট"-এর মতো) */
object DesktopMode {
    const val JS = "(function(){try{" +
        "if(!/^https?:/.test(location.href)||location.hostname==='appassets.androidplatform.net')return;" +
        "if(/^accounts\\.(google\\.|youtube\\.com)/.test(location.hostname))return;" +
        "var W=1280;" +
        "function vp(){try{var h=document.head||document.documentElement;if(!h)return;var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';h.appendChild(m)}if(m.content!=='width='+W)m.content='width='+W}catch(e){}}" +
        "vp();try{new MutationObserver(vp).observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content']})}catch(e){}" +
        "document.addEventListener('DOMContentLoaded',vp);" +
        "try{Object.defineProperty(Navigator.prototype,'platform',{get:function(){return 'Linux x86_64'},configurable:true})}catch(e){}" +
        "try{if(navigator.userAgentData&&navigator.userAgentData.mobile){var b=navigator.userAgentData.brands||[];" +
        "var d={brands:b,mobile:false,platform:'Linux'," +
        "getHighEntropyValues:function(h){return Promise.resolve({brands:b,mobile:false,platform:'Linux',platformVersion:'6.5.0',architecture:'x86',bitness:'64',model:'',uaFullVersion:''})}," +
        "toJSON:function(){return{brands:b,mobile:false,platform:'Linux'}}};" +
        "Object.defineProperty(Navigator.prototype,'userAgentData',{get:function(){return d},configurable:true})}}catch(e){}" +
        "}catch(e){}})()"
}
