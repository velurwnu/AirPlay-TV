/* Fills the saved Apple ID e-mail / password into the sign-in form.
 *
 * Runs in every frame of music.apple.com and idmsa.apple.com (the sign-in sheet and
 * Apple's login form inside it). The app hands the login over only to those origins,
 * via window.tvLogin. Each field is filled once; the user just presses the step's button.
 * Anything else (verification code) is left empty: the keyboard opens for it.
 */
(function () {
  if (window.__tvfill || !window.tvLogin) return;
  if (window.top === window && location.hostname === 'music.apple.com') return;   // tv.js owns the page
  window.__tvfill = true;

  const EMAIL = 'input#accountName, input#account_name_text_field, input[type=email], input[name=accountName], input[autocomplete~=username]';
  const OTHER = 'input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=password])';
  const STEP_BUTTON = /^(continue|продолжить|sign in|войти|next|далее)$/i;
  const own = location.hostname !== 'music.apple.com';   // Apple's login frame: we also move the focus there

  let login = null;
  window.tvLogin.onmessage = e => { try { login = JSON.parse(e.data); } catch (_) { /* ignore */ } };
  window.tvLogin.postMessage('get');

  const shown = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && !el.disabled && !el.readOnly; };
  const filled = new WeakSet();

  function put(el, value) {
    // Through the native setter + input event, so the page's framework sees the change.
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    filled.add(el);
  }
  function stepButton() {
    const bs = Array.prototype.filter.call(document.querySelectorAll('button'), shown);
    return bs.find(b => STEP_BUTTON.test((b.textContent || '').trim())) || bs.find(b => b.type === 'submit') || null;
  }

  let asked = null;
  function tick() {
    if (!login || !document.body) return;   // login = {} when nothing is saved
    const email = Array.prototype.find.call(document.querySelectorAll(EMAIL), shown);
    const pass = Array.prototype.find.call(document.querySelectorAll('input[type=password]'), shown);
    let done = false;
    if (email && !email.value && !filled.has(email) && login.email) { put(email, login.email); done = true; }
    if (pass && !pass.value && !filled.has(pass) && login.password) { put(pass, login.password); done = true; }
    if (!own) return;
    if (done) {
      const b = stepButton();
      if (b) b.focus();
      return;
    }
    // Verification code and similar: focus the first empty field and ask the app for the keyboard.
    const other = Array.prototype.find.call(document.querySelectorAll(OTHER), el => shown(el) && !el.value && !el.matches(EMAIL));
    if (other && other !== asked) {
      asked = other;
      other.focus();
      window.tvLogin.postMessage('keyboard');
    }
  }
  setInterval(tick, 400);
})();
