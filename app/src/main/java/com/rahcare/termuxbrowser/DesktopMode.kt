package com.rahcare.termuxbrowser

/** ডেস্কটপ মোড: UA ছাড়াও ডেস্কটপ-ভিউপোর্ট, navigator.platform ও userAgentData বদলায় (গুগল ক্রোমের "ডেস্কটপ সাইট"-এর মতো) */
object DesktopMode {
    const val JS = "(function(){try{" +
        "if(!/^https?:/.test(location.href)||location.hostname==='appassets.androidplatform.net')return;" +
        "if(/^accounts\\.(google\\.|youtube\\.com)/.test(location.hostname))return;" +
        "var W=1280;" +
        "function vp(){try{var h=document.head||document.documentElement;if(!h)return;var ms=document.querySelectorAll('meta[name=viewport]');" +
        "if(!ms.length){var m=document.createElement('meta');m.name='viewport';h.appendChild(m);ms=[m]}for(var i=0;i<ms.length;i++){if(ms[i].content!=='width='+W)ms[i].content='width='+W}}catch(e){}}" +
        "vp();try{new MutationObserver(vp).observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content']})}catch(e){}" +
        "document.addEventListener('DOMContentLoaded',vp);" +
        "try{Object.defineProperty(Navigator.prototype,'platform',{get:function(){return 'Linux x86_64'},configurable:true})}catch(e){}" +
        "try{Object.defineProperty(Navigator.prototype,'maxTouchPoints',{get:function(){return 0},configurable:true})}catch(e){}" +
        "try{var sd={width:1920,height:1080,availWidth:1920,availHeight:1040};Object.keys(sd).forEach(function(k){Object.defineProperty(Screen.prototype,k,{get:function(){return sd[k]},configurable:true})})}catch(e){}" +
        "try{var mm=window.matchMedia.bind(window),NV='(max-width:0px) and (min-width:1px)',AL='(min-width:0px)';window.matchMedia=function(q){try{q=String(q)" +
        ".replace(/\\(\\s*(any-)?pointer\\s*:\\s*coarse\\s*\\)/gi,NV).replace(/\\(\\s*(any-)?pointer\\s*:\\s*fine\\s*\\)/gi,AL)" +
        ".replace(/\\(\\s*(any-)?hover\\s*:\\s*none\\s*\\)/gi,NV).replace(/\\(\\s*(any-)?hover\\s*:\\s*hover\\s*\\)/gi,AL)}catch(e){}return mm(q)}}catch(e){}" +
        "try{if(navigator.userAgentData&&navigator.userAgentData.mobile){var b=navigator.userAgentData.brands||[];" +
        "var d={brands:b,mobile:false,platform:'Linux'," +
        "getHighEntropyValues:function(h){return Promise.resolve({brands:b,mobile:false,platform:'Linux',platformVersion:'6.5.0',architecture:'x86',bitness:'64',model:'',uaFullVersion:''})}," +
        "toJSON:function(){return{brands:b,mobile:false,platform:'Linux'}}};" +
        "Object.defineProperty(Navigator.prototype,'userAgentData',{get:function(){return d},configurable:true})}}catch(e){}" +
        "}catch(e){}})()"
}
