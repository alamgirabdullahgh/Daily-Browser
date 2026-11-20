/* অটো-রেকর্ডার রিপ্লে: এক ধাপ চালায় এবং JSON লেখা ফেরত দেয়:
   {s:'ok'} | {s:'wait',w:'কারণ'} (পরে আবার চেষ্টা) | {s:'tap',x,y,iw} (Kotlin আসল আঙুলের চাপ দেবে — ফাইল-বাছাই/ডাউনলোডের জন্য দরকার) */
window.__dmRun = function (s) {
  function J(o) { return JSON.stringify(o); }
  function q(sel) { try { return [].slice.call(document.querySelectorAll(sel)); } catch (e) { return []; } }
  function qa(a, v) { return a + '="' + String(v).replace(/["\\]/g, '\\$&') + '"'; }
  function norm(t) { return String(t || '').replace(/\s+/g, ' ').trim(); }
  function vis(e) {
    var r = e.getBoundingClientRect(); if (r.width < 1 || r.height < 1) return false;
    var cs = getComputedStyle(e); return cs.visibility !== 'hidden' && cs.display !== 'none';
  }
  function pick(a, hid) {
    if (!a.length) return null; if (hid) return a[0];
    for (var i = 0; i < a.length; i++) if (vis(a[i])) return a[i];
    return null;
  }
  function find(c, hid) {
    var tag = c.tag || '*', e, r;
    if (c.id) { e = document.getElementById(c.id); if (e && (hid || vis(e))) return e; }
    if (c.tid) { r = q('[' + qa('data-testid', c.tid) + '],[' + qa('data-test', c.tid) + '],[' + qa('data-qa', c.tid) + ']'); e = pick(r, hid); if (e) return e; }
    if (c.al) { r = q(tag + '[' + qa('aria-label', c.al) + ']'); e = pick(r, hid); if (e) return e; }
    if (c.nm) { r = q(tag + '[' + qa('name', c.nm) + ']'); e = pick(r, hid); if (e) return e; }
    if (c.ph) { r = q(tag + '[' + qa('placeholder', c.ph) + ']'); e = pick(r, hid); if (e) return e; }
    if (c.tx) {
      var all = q(tag === '*' ? 'a,button,[role=button],label,span,div,li,p' : tag), m;
      m = all.filter(function (x) { return norm(x.tagName === 'INPUT' ? x.value : (x.innerText || x.textContent)) === c.tx; });
      e = pick(m, hid); if (e) return e;
      m = all.filter(function (x) { var t = norm(x.innerText || x.textContent); return t && t.length <= c.tx.length + 25 && t.indexOf(c.tx) === 0; });
      e = pick(m, hid); if (e) return e;
    }
    if (c.css) { r = q(c.css); e = pick(r, hid); if (e) return e; }
    return null;
  }
  var t = s.t, c = s.c;
  if (t !== 'click' && t !== 'file' && t !== 'type' && t !== 'select' && t !== 'key') return J({ s: 'ok' });
  if (t === 'type' && s.sec) return J({ s: 'ok', skip: 1 });
  var el = c ? find(c, t === 'file') : null;
  if (!el && t === 'key') el = document.activeElement;
  if (!el) return J({ s: 'wait', w: 'এলিমেন্ট নেই' });

  if (t === 'click' || t === 'file') {
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return J({ s: 'wait', w: 'নিষ্ক্রিয়' });
    try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e) { el.scrollIntoView(); }
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) { el.click(); return J({ s: 'ok', j: 1 }); }
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2, top = document.elementFromPoint(cx, cy);
    if (top && (top === el || el.contains(top) || top.contains(el))) {
      var vv = window.visualViewport;
      return J({ s: 'tap', x: cx - (vv ? vv.offsetLeft : 0), y: cy - (vv ? vv.offsetTop : 0), iw: vv ? vv.width : window.innerWidth });
    }
    el.click(); return J({ s: 'ok', j: 1 });
  }
  if (t === 'type') {
    el.focus();
    var v = s.v == null ? '' : String(s.v);
    if (el.isContentEditable && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') el.textContent = v;
    else {
      var P = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(P, 'value');
      if (d && d.set) d.set.call(el, v); else el.value = v;
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return J({ s: 'ok' });
  }
  if (t === 'select') {
    el.value = s.v; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
    return J({ s: 'ok' });
  }
  /* key: Enter */
  el.focus();
  var o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
  var go = el.dispatchEvent(new KeyboardEvent('keydown', o));
  el.dispatchEvent(new KeyboardEvent('keypress', o)); el.dispatchEvent(new KeyboardEvent('keyup', o));
  if (go && el.form) { try { el.form.requestSubmit(); } catch (e) { try { el.form.submit(); } catch (e2) {} } }
  return J({ s: 'ok' });
};
