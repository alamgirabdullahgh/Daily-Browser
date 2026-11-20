/* অটো-রেকর্ডার: পেজে আপনার চাপ/লেখা ধরে Kotlin-এ (DailyMacro.rec) পাঠায়। __TOKEN__ প্রতি রেকর্ডিংয়ে নতুন;
   টোকেন না মিললে ব্রিজ কিছু নেয় না। পাসওয়ার্ডের লেখা কখনো নেওয়া হয় না। */
(function (T) {
  if (window.__dmRecOn === T) return;
  window.__dmRecOn = T;
  var BAR = '__dm_bar', last = 0, seq = 0, tm = {}, pe = {};

  function gap() { var n = Date.now(), g = last ? n - last : 0; last = n; return Math.min(g, 20000); }
  function cnt(n) { var e = document.getElementById('__dm_n'); if (e && n >= 0) e.textContent = n; }
  function send(o) {
    try { o.g = gap(); var n = DailyMacro.rec(T, JSON.stringify(o)); cnt(n); return n; } catch (e) { return -1; }
  }
  function inBar(el) { return !!(el && el.closest && el.closest('#' + BAR)); }
  function target(e) { var t = e.target; if (t && t.nodeType !== 1) t = t.parentElement; return t; }
  function path(el) {
    var p = [], n = 0;
    while (el && el.nodeType === 1 && el !== document.body && n < 5) {
      var s = el.tagName.toLowerCase();
      if (el.id && !/\d{4,}|:/.test(el.id) && document.querySelectorAll('#' + CSS.escape(el.id)).length === 1) { p.unshift('#' + CSS.escape(el.id)); break; }
      var par = el.parentElement;
      if (par) {
        var same = [].filter.call(par.children, function (c) { return c.tagName === el.tagName; });
        if (same.length > 1) s += ':nth-of-type(' + (same.indexOf(el) + 1) + ')';
      }
      p.unshift(s); el = par; n++;
    }
    return p.join('>');
  }
  function desc(el) {
    var o = { tag: el.tagName.toLowerCase() }, v;
    if (el.id && !/\d{4,}|:/.test(el.id) && document.querySelectorAll('#' + CSS.escape(el.id)).length === 1) o.id = el.id;
    v = el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-qa'); if (v) o.tid = v;
    v = el.getAttribute('aria-label'); if (v) o.al = v;
    v = el.getAttribute('name'); if (v) o.nm = v;
    v = el.getAttribute('placeholder'); if (v) o.ph = v;
    v = el.getAttribute('type'); if (v) o.ty = v;
    if (o.tag !== 'input' && o.tag !== 'textarea' && o.tag !== 'select') {
      v = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      if (v && v.length <= 60) o.tx = v;
    } else if (o.tag === 'input' && /^(button|submit)$/.test(o.ty || '')) {
      v = (el.value || '').trim(); if (v) o.tx = v;
    }
    o.css = path(el);
    return o;
  }

  var ACT = 'a,button,input,select,textarea,label,summary,[role=button],[role=tab],[role=menuitem],[role=option],[role=link],[onclick],[tabindex]';
  document.addEventListener('click', function (e) {
    var t = target(e); if (!t || inBar(t)) return;
    var el = t.closest(ACT) || t;
    var fi = (el.tagName === 'INPUT' && el.type === 'file') ? el
      : (el.tagName === 'LABEL' && el.control && el.control.type === 'file' ? el.control : null);
    if (fi) { send({ t: 'file', c: desc(el), mul: !!fi.multiple, acc: fi.accept || '' }); return; }
    send({ t: 'click', c: desc(el) });
  }, true);

  /* সাইটের নিজের বোতাম input.click() ডাকলে: আগের "চাপ" ধাপটাই "ফাইল দিন" ধাপ হয়ে যায় (Kotlin জোড়া লাগায়) */
  try {
    var oc = HTMLInputElement.prototype.click;
    HTMLInputElement.prototype.click = function () {
      if (this.type === 'file') send({ t: 'file', prev: 1, mul: !!this.multiple, acc: this.accept || '' });
      return oc.apply(this, arguments);
    };
  } catch (e) {}

  function flush(el) {
    var k = el.__dmk;
    if (k && tm[k]) { clearTimeout(tm[k]); delete tm[k]; delete pe[k]; }
    var ty = (el.type || '').toLowerCase();
    if (ty === 'file' || ty === 'checkbox' || ty === 'radio' || ty === 'button' || ty === 'submit' || ty === 'reset') return;
    if (ty === 'password') { send({ t: 'type', c: desc(el), v: '', sec: 1 }); return; }
    var ce = el.isContentEditable && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA';
    var v = ce ? el.textContent : el.value;
    send({ t: 'type', c: desc(el), v: String(v == null ? '' : v) });
  }
  document.addEventListener('input', function (e) {
    var el = target(e); if (!el || inBar(el)) return;
    var tg = el.tagName;
    if (!(tg === 'INPUT' || tg === 'TEXTAREA' || el.isContentEditable)) return;
    if (!el.__dmk) el.__dmk = 'k' + (++seq);
    var k = el.__dmk; if (tm[k]) clearTimeout(tm[k]);
    pe[k] = el;
    tm[k] = setTimeout(function () { flush(el); }, 900);
  }, true);
  document.addEventListener('change', function (e) {
    var el = target(e); if (!el || inBar(el)) return;
    if (el.tagName === 'SELECT') { send({ t: 'select', c: desc(el), v: el.value }); return; }
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') flush(el);
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    var el = target(e); if (!el || inBar(el) || el.tagName === 'TEXTAREA') return;
    if (el.tagName === 'INPUT' || el.isContentEditable) {
      if (el.__dmk && tm[el.__dmk]) flush(el);
      send({ t: 'key', k: 'Enter', c: desc(el) });
    }
  }, true);
  window.addEventListener('pagehide', function () { for (var k in pe) { try { flush(pe[k]); } catch (e) {} } });

  function bar() {
    if (document.getElementById(BAR) || !(document.body || document.documentElement)) return;
    var b = document.createElement('div'); b.id = BAR;
    b.style.cssText = 'position:fixed;top:6px;right:6px;z-index:2147483647;background:#dc2626;color:#fff;font:600 13px/1.2 system-ui,sans-serif;padding:7px 10px;border-radius:18px;box-shadow:0 2px 8px rgba(0,0,0,.35);display:flex;gap:8px;align-items:center';
    b.innerHTML = '<span>🔴 রেকর্ড <b id="__dm_n">0</b></span><button id="__dm_s" type="button" style="all:unset;background:#fff;color:#dc2626;padding:3px 9px;border-radius:12px;font-weight:700">থামান</button>';
    (document.body || document.documentElement).appendChild(b);
    document.getElementById('__dm_s').addEventListener('click', function (e) {
      e.stopPropagation(); e.preventDefault();
      try { DailyMacro.stop(T); } catch (x) {}
    }, true);
  }
  bar(); setInterval(bar, 1500);
  send({ t: 'page', u: location.href });
})("__TOKEN__");
