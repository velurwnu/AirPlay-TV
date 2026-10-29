/* TV layer for music.apple.com.
 *
 * The site stays the app: sign-in, data, playback. This script only adds
 *  - a plain tab bar (Главное / Новое / Медиатека / Поиск) driving the site's routes,
 *  - library sub-tabs (Песни / Альбомы / Артисты) and "play / shuffle whole library",
 *  - a home hero and pinned "Made for You" / "Heavy Rotation" shelves,
 *  - D-pad spatial navigation over the site's own links and buttons,
 *  - remote media keys and the Back button,
 *  - a remote-friendly Apple ID sign-in sheet (the app fills the saved e-mail/password, see fill.js).
 * It never reads tokens or calls Apple's APIs itself; it only presses the site's buttons.
 */
(function () {
  if (window.__tvjs) return;
  window.__tvjs = true;

  const C = window.APP_CONFIG || {};
  const html = document.documentElement;
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.prototype.slice.call((r || document).querySelectorAll(s));
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  html.classList.add('tv');

  // ---------- routes ----------
  let lastSf = 'us';
  function sf() {
    const m = location.pathname.match(/^\/([a-z]{2})(?=\/|$)/);
    if (m) lastSf = m[1];
    return lastSf;
  }
  let libKind = 'songs';
  const TABS = [
    ['home', 'Главное', () => `/${sf()}/home`],
    ['new', 'Новое', () => `/${sf()}/new`],
    ['library', 'Медиатека', () => C.libraryRoutes[libKind]],
    ['search', 'Поиск', () => `/${sf()}/search`]
  ];
  const LIB = [['songs', 'Песни'], ['albums', 'Альбомы'], ['artists', 'Артисты']];

  function tabOf(path) {
    if (/^\/library\//.test(path)) return 'library';
    if (/^\/[a-z]{2}\/new\/?$/.test(path)) return 'new';
    if (/^\/[a-z]{2}\/search/.test(path)) return 'search';
    if (/^\/([a-z]{2}\/?)?(home|listen-now)?\/?$/.test(path) || /^\/[a-z]{2}\/(home|listen-now)\/?$/.test(path)) return 'home';
    return null;
  }
  // Tab roots: Back from here goes to the tab bar instead of history.
  function isRoot(path) {
    if (Object.values(C.libraryRoutes).indexOf(path.replace(/\/$/, '')) >= 0) return true;
    return tabOf(path) !== null && !/^\/library\//.test(path);
  }

  // Navigate with the site's own router: click a matching link (or a temporary one).
  function go(path) {
    if (location.pathname.replace(/\/$/, '') === path) return;
    const link = $$('a[href]').find(a => { try { return new URL(a.href).pathname.replace(/\/$/, '') === path; } catch (e) { return false; } });
    if (link) return link.click();
    const a = document.createElement('a');
    a.href = path; a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  // ---------- tab bar ----------
  const bar = document.createElement('div');
  bar.id = 'tv-bar';
  bar.innerHTML = `
    <nav class="tv-pill tv-glass tv-tabs">${TABS.map(([k, n]) => `<button data-tab="${k}">${n}</button>`).join('')}</nav>
    <div class="tv-pill tv-glass tv-sub" hidden>
      ${LIB.map(([k, n]) => `<button data-lib="${k}">${n}</button>`).join('')}
      <button data-act="play-lib">▶ Всё</button><button data-act="shuffle-lib">⤨ Перемешать</button>
    </div>
    <button class="tv-signin" data-act="signin" hidden>Войти</button>`;
  html.appendChild(bar);   // sibling of <body>: the site's re-renders can't remove it

  const toastEl = document.createElement('div');
  toastEl.id = 'tv-toast';
  toastEl.className = 'tv-glass';
  toastEl.hidden = true;
  html.appendChild(toastEl);
  let toastTimer;
  function toast(msg, ms) {
    clearTimeout(toastTimer);
    toastEl.textContent = msg || '';
    toastEl.hidden = !msg;
    if (msg) toastTimer = setTimeout(() => (toastEl.hidden = true), ms || 3500);
  }

  bar.addEventListener('click', e => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.tab) return go(TABS.find(t => t[0] === b.dataset.tab)[2]());
    if (b.dataset.lib) { libKind = b.dataset.lib; return go(C.libraryRoutes[libKind]); }
    act(b.dataset.act);
  });

  function act(name) {
    if (name === 'play-lib') return playLibrary(false);
    if (name === 'shuffle-lib') return playLibrary(true);
    if (name === 'signin') {
      // The app first asks for the e-mail/password to save (once), then calls tvOpenSignIn.
      if (window.tvHost) window.tvHost.postMessage(JSON.stringify({ signin: true }));
      else window.tvOpenSignIn();
    }
  }
  window.tvOpenSignIn = function () {
    const b = signInButton();
    if (b) b.click(); else toast('Сайт ещё загружается, попробуйте через пару секунд');
  };

  // ---------- signed-in state ----------
  function signInButton() {
    return $$('button.signin, [data-testid="auth-content"] button, button')
      .filter(b => !bar.contains(b))
      .find(b => /^(sign in|войти)$/i.test((b.textContent || '').trim()) || /sign in|войти/i.test(b.getAttribute('aria-label') || ''));
  }
  function signedIn() {
    return !!$('a[href*="/library/"]');   // the site only renders library links for a signed-in user
  }

  // ---------- play / shuffle the whole library ----------
  function libraryAction(shuffle) {
    if (location.pathname.replace(/\/$/, '') !== C.libraryRoutes.songs) return null;
    const exact = $(shuffle ? '.primary-actions__button--shuffle button' : '.primary-actions__button--play button');
    if (exact) return exact;
    const re = shuffle ? /shuffle|перемеш/i : /^(play|воспроизв|слушать)/i;
    return $$('.scrollable-page button').find(b => re.test(((b.getAttribute('aria-label') || '') + ' ' + b.textContent).trim()));
  }
  async function playLibrary(shuffle) {
    if (!signedIn()) return toast('Сначала войдите в Apple ID');
    toast(shuffle ? 'Перемешиваю медиатеку…' : 'Включаю медиатеку…');
    const wasThere = location.pathname.replace(/\/$/, '') === C.libraryRoutes.songs;
    libKind = 'songs';
    go(C.libraryRoutes.songs);
    if (!wasThere) await sleep(600);           // let the old page unmount
    const t0 = Date.now();
    let b = null;
    while (!(b = libraryAction(shuffle)) && Date.now() - t0 < 10000) await sleep(250);
    if (b) { b.click(); toast(''); } else toast('Не нашёл кнопку на странице «Песни»');
  }

  // ---------- home: hero + pinned shelves ----------
  const hero = document.createElement('section');
  hero.id = 'tv-hero';
  hero.className = 'tv-glass';
  hero.innerHTML = `<div><h1>Медиатека</h1><p>Вся ваша музыка одной кнопкой</p></div>
    <div class="tv-actions"><button class="tv-glass" data-act="play-lib">▶ Слушать всё</button>
    <button class="tv-glass" data-act="shuffle-lib">⤨ Перемешать</button></div>`;
  hero.addEventListener('click', e => { const b = e.target.closest('button'); if (b) act(b.dataset.act); });

  function decorate() {
    const path = location.pathname;
    const tab = tabOf(path);
    $$('button[data-tab]', bar).forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    const sub = $('.tv-sub', bar);
    sub.hidden = tab !== 'library';
    $$('button[data-lib]', bar).forEach(b => b.classList.toggle('active', C.libraryRoutes[b.dataset.lib] === path.replace(/\/$/, '')));
    $('.tv-signin', bar).hidden = signedIn();
    watchPlayer();

    const content = $('.content-container');
    if (tab === 'home' && content) {
      if (hero.parentNode !== content && signedIn()) content.insertBefore(hero, content.firstChild);
      $$(':scope > [data-testid="section-container"]', content).forEach(sec => {
        if (sec.dataset.tvPinned) return;
        const t = $('[data-testid="header-title"]', sec);
        const title = t ? t.textContent.trim() : (sec.getAttribute('aria-label') || '');
        (C.pinShelves || []).forEach((re, i) => { if (re.test(title)) { sec.classList.add('tv-pin-' + i); sec.dataset.tvPinned = '1'; } });
      });
    } else if (hero.parentNode) {
      hero.remove();
    }
  }

  // Route changes: patch the History API + popstate, debounce DOM changes.
  let lastPath = location.pathname, decoTimer;
  function onRoute() {
    if (location.pathname !== lastPath) {
      lastPath = location.pathname;
      html.classList.remove('tv-scrolled');
      pendingFocus = true;
    }
    clearTimeout(decoTimer);
    decoTimer = setTimeout(decorate, 120);
  }
  ['pushState', 'replaceState'].forEach(k => {
    const orig = history[k];
    history[k] = function () { const r = orig.apply(this, arguments); onRoute(); return r; };
  });
  window.addEventListener('popstate', onRoute);
  // Ignore the player bar: its progress/time re-renders every second.
  new MutationObserver(list => {
    if (list.some(m => !(m.target.closest && m.target.closest('.player-bar')))) onRoute();
  }).observe(document.body, { childList: true, subtree: true });

  // Hide the player bar while it is empty (its own observer: it re-renders every second when playing).
  let playerObs = null;
  function watchPlayer() {
    const pb = $('.player-bar');
    if (!pb || pb.__tvWatched) return;
    pb.__tvWatched = true;
    const check = () => pb.classList.toggle('tv-empty', !pb.querySelector('button, img'));
    check();
    if (playerObs) playerObs.disconnect();
    playerObs = new MutationObserver(check);
    playerObs.observe(pb, { childList: true, subtree: true });
  }

  // Hide the tab bar while scrolled down (it comes back when focused).
  document.addEventListener('scroll', e => {
    if (e.target && e.target.classList && e.target.classList.contains('scrollable-page')) {
      html.classList.toggle('tv-scrolled', e.target.scrollTop > 40);
    }
  }, true);

  // ---------- D-pad spatial navigation ----------
  const FOCUSABLE = 'a[href], button, input, textarea, select, [role="button"], [role="link"], [tabindex="0"]';
  const ROW = '.songs-list-row, [data-testid="track-list-item"], [role="row"]';
  const SKIP = [
    '.product-lockup__play-button', '.product-lockup__context-button', '.product-lockup__subtitle-links',
    '.product-lockup__subtitle', '.more-button', 'amp-contextual-menu-button', '.shelf-grid-nav__arrow',
    '.see-all-button', '.title__button', '.track-lockup__context-menu',
    // Note: no [aria-hidden] here — the site marks off-screen shelf items aria-hidden.
    '[data-testid="navigation"]', '[data-testid="header"]', '[data-testid="footer"]'
  ].join(',');
  const GROUP = '.product-lockup, .track-lockup, ' + ROW;   // one focus stop per card / row

  function dialogRoot() {
    return $$('[role="dialog"], [aria-modal="true"], [role="menu"]').filter(d => {
      const r = d.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    }).pop() || null;
  }
  function roots() {
    const d = dialogRoot();
    if (d) return [d];
    return [bar, $('.scrollable-page'), $('.player-bar')].filter(Boolean);
  }

  function candidates() {
    const H = innerHeight, W = innerWidth, out = [], seen = new Set();
    roots().forEach(root => {
      $$(FOCUSABLE + ',' + ROW, root).forEach(el => {
        if (el.closest(SKIP) && !bar.contains(el)) return;
        if (el.disabled || el.hidden) return;
        const g = el.closest(GROUP);
        const key = g || el;
        if (seen.has(key)) return;
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) return;
        if (r.bottom < -H || r.top > 2 * H || r.right < -2 * W || r.left > 3 * W) return;
        seen.add(key);
        out.push({ el: g && g.matches(ROW) ? g : el, r });
      });
    });
    return out;
  }

  function gap(a0, a1, b0, b1, p) {
    if (b1 > a0 && b0 < a1) return 0;
    return Math.min(Math.abs(b0 - p), Math.abs(b1 - p));
  }
  function pick(cur, list, dir) {
    let best = null, bestScore = Infinity;
    const cx = cur.left + cur.width / 2, cy = cur.top + cur.height / 2;
    for (const c of list) {
      const r = c.r;
      let main, cross;
      if (dir === 'down') { main = r.top - cur.bottom; cross = gap(cur.left, cur.right, r.left, r.right, cx); }
      else if (dir === 'up') { main = cur.top - r.bottom; cross = gap(cur.left, cur.right, r.left, r.right, cx); }
      else if (dir === 'right') { main = r.left - cur.right; cross = gap(cur.top, cur.bottom, r.top, r.bottom, cy); }
      else { main = cur.left - r.right; cross = gap(cur.top, cur.bottom, r.top, r.bottom, cy); }
      if (main < -6) continue;
      const score = Math.max(main, 0) + cross * 2.5;
      if (score < bestScore) { bestScore = score; best = c.el; }
    }
    return best;
  }

  let current = null, pendingFocus = true;
  function setFocus(el) {
    if (!el) return;
    if (current) {
      current.classList.remove('tv-focus');
      const g = current.closest('.product-lockup');
      if (g) g.classList.remove('tv-focus-within');
    }
    current = el;
    if (!el.matches(FOCUSABLE)) el.setAttribute('tabindex', '-1');
    el.focus({ preventScroll: true });
    el.classList.add('tv-focus');
    const g = el.closest('.product-lockup');
    if (g) g.classList.add('tv-focus-within');
    if (bar.contains(el) || el.closest('.player-bar')) return;
    el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: C.smoothScroll ? 'smooth' : 'auto' });
  }

  function focusFirst() {
    const d = dialogRoot();
    const list = candidates().filter(c => !bar.contains(c.el) && !c.el.closest('.player-bar'));
    const inView = list.filter(c => c.r.top >= 0 && c.r.top < innerHeight);
    // Prefer the main action of the page: home hero, album/playlist "Play".
    const main = !d && list.find(c => c.el.closest('#tv-hero, .primary-actions__button--play'));
    const first = main || (d ? list[0] : (inView[0] || list[0]));
    setFocus(first ? first.el : $('button.active', bar) || $('button', bar));
  }

  function move(dir) {
    if (!current || !document.contains(current)) return focusFirst();
    const cur = current.getBoundingClientRect();
    const next = pick(cur, candidates().filter(c => c.el !== current), dir);
    if (next) setFocus(next);
  }

  // Keep a valid focus after route changes / re-renders (cheap check).
  setInterval(() => {
    if (html.classList.contains('tv-modal')) return;   // sign-in sheet owns the focus
    if (document.activeElement && document.activeElement.tagName === 'IFRAME') return;   // focus is inside a frame
    if (pendingFocus || !current || !document.contains(current)) {
      if ($('.content-container') && candidates().length) { pendingFocus = false; focusFirst(); }
    }
  }, 600);

  function activate(el) {
    if (el.matches(ROW)) {
      const play = $$('button', el).find(b => /play|воспроизв/i.test(b.getAttribute('aria-label') || '')) || $('button', el);
      if (play) play.click();
      else el.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      return true;
    }
    if (!el.matches('a[href], button, input, textarea, select')) { el.click(); return true; }
    return false;   // native Enter handles links and buttons
  }

  // ---------- player / media keys ----------
  function playerButton(re) {
    return $$('.player-bar button').find(b => re.test(b.getAttribute('aria-label') || '') || re.test(b.title || ''));
  }
  function media(key) {
    let b = null;
    if (/PlayPause|^MediaPlay$|^MediaPause$/.test(key)) b = playerButton(/^(play|pause|воспроизв|пауза)/i);
    else if (/TrackNext|FastForward/.test(key)) b = playerButton(/next|следующ|далее/i);
    else if (/TrackPrevious|Rewind/.test(key)) b = playerButton(/previous|предыдущ|назад/i);
    if (b) b.click();
    return !!b || /^Media/.test(key);
  }

  // ---------- Apple ID sign-in sheet ----------
  // The site opens sign-in in a same-origin iframe (/includes/commerce/…) covering the screen.
  // We shrink it to a centered panel, and walk the focus field by field: e-mail, then
  // password, then the verification code — opening the on-screen keyboard each time.
  // Only focus is touched; field values are never read.
  const SIGNIN_CSS = `
    .cc-modal { align-items: center !important; justify-content: center !important; background: rgba(0,0,0,.62) !important; }
    .cc-modal__modal {
      width: 560px !important; max-width: 70vw !important; height: auto !important; max-height: 88vh !important;
      border-radius: 26px !important; overflow-y: auto !important; scrollbar-width: none;
      border: 1px solid rgba(255,255,255,.16) !important;
      box-shadow: inset 0 1px 0 rgba(255,255,255,.22), 0 30px 80px rgba(0,0,0,.6) !important;
    }
    .cc-modal__modal::-webkit-scrollbar { display: none; }
    .cc-modal__body, .page-container { height: auto !important; min-height: 0 !important; }
    .tv-field-focus { outline: 3px solid rgba(255,255,255,.9) !important; outline-offset: 3px !important; }`;
  const FIELD = 'input:not([type=hidden]):not([type=checkbox]):not([type=radio]), textarea';

  function signInFrame() {
    return $$('iframe').find(f => {
      try { return /\/includes\/commerce\//.test(new URL(f.src, location.href).pathname) && f.getBoundingClientRect().width > 0; }
      catch (e) { return false; }
    }) || null;
  }
  const bigFrame = el => { const r = el.getBoundingClientRect(); return r.width > 100 && r.height > 100; };
  const shown = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && !el.disabled; };

  // Does the app have a saved e-mail/password? Then fill.js fills those fields and we only
  // focus the step's button, so one OK press submits it.
  let hasLogin = false;
  if (window.tvLogin) {
    window.tvLogin.onmessage = e => { try { hasLogin = !!JSON.parse(e.data).has; } catch (_) { /* ignore */ } };
    window.tvLogin.postMessage('has');
  }
  window.tvLoginChanged = () => { if (window.tvLogin) window.tvLogin.postMessage('has'); };
  const LOGIN_FIELD = 'input[type=password], input[type=email], input#accountName, input[autocomplete~=username]';
  const STEP_BUTTON = /^(continue|продолжить|sign in|войти|next|далее)$/i;

  // Programmatic focus doesn't open the keyboard; the app opens it for us.
  function showKeyboard() {
    try { if (window.tvHost) window.tvHost.postMessage(JSON.stringify({ keyboard: true })); } catch (e) { /* not in the app */ }
  }

  function focusField(frame, el, noKeyboard) {
    frame.focus();
    el.focus();
    el.scrollIntoView({ block: 'center' });
    if (noKeyboard || !el.matches(FIELD)) return;
    // The keyboard's action key submits the step (e-mail → Continue, password → Sign In).
    el.setAttribute('enterkeyhint', 'go');
    showKeyboard();
  }
  function stepButton(doc) {
    const bs = $$('button', doc).filter(shown);
    return bs.find(b => STEP_BUTTON.test((b.textContent || '').trim())) || bs.find(b => b.type === 'submit') || null;
  }

  // D-pad inside the sheet: Up/Down walk fields and buttons in order, Back closes it.
  function hookSheetKeys(frame, win, doc) {
    win.addEventListener('keydown', e => {
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
      const items = $$(FIELD + ', button, a[href]', doc).filter(shown);
      const i = items.indexOf(doc.activeElement);
      const next = items[e.key === 'ArrowDown' ? i + 1 : i - 1];
      if (!next) return;
      e.preventDefault();
      e.stopPropagation();
      items.forEach(x => x.classList.remove('tv-field-focus'));
      next.focus();
      next.classList.add('tv-field-focus');
      next.scrollIntoView({ block: 'nearest' });
      if (next.matches(FIELD)) { next.setAttribute('enterkeyhint', 'go'); showKeyboard(); }
    }, true);
  }

  let sheetFields = '', sheetTarget = null, sheetRetries = 0, sheetGone = 0;
  function watchSignIn() {
    const f = signInFrame();
    if (!f) {
      // The frame can be 0×0 for a moment while the sheet animates: only treat ~1 s of absence as closed.
      if (html.classList.contains('tv-modal') && ++sheetGone < 3) return;
      if (html.classList.contains('tv-modal')) pendingFocus = true;   // sheet just closed
      html.classList.remove('tv-modal');
      sheetFields = ''; sheetTarget = null;
      return;
    }
    sheetGone = 0;
    html.classList.add('tv-modal');
    let doc;
    try { doc = f.contentDocument; } catch (e) { return; }
    if (!doc || !doc.head || !doc.body) return;
    if (!doc.getElementById('tv-signin-style')) {
      const st = doc.createElement('style');
      st.id = 'tv-signin-style';
      st.textContent = SIGNIN_CSS;
      doc.head.appendChild(st);
      hookSheetKeys(f, f.contentWindow, doc);
    }
    // A new step of the flow (or a field just got filled): focus the first field left to type,
    // or the step's button when everything is filled in (saved login).
    const fields = $$(FIELD, doc).filter(shown);
    if (!fields.length) return;
    const key = fields.map(i => (i.id || i.name || i.type) + (i.value ? '+' : '-')).join('|');
    if (key !== sheetFields) {
      sheetFields = key;
      const todo = fields.filter(i => !i.value && !(hasLogin && i.matches(LOGIN_FIELD)));
      const waiting = fields.find(i => !i.value && hasLogin && i.matches(LOGIN_FIELD));   // fill.js is about to fill it
      sheetTarget = todo.find(i => i.type === 'password') || todo[0] || waiting || stepButton(doc);
      sheetRetries = 0;
      if (sheetTarget) focusField(f, sheetTarget, sheetTarget === waiting);
      return;
    }
    // Apple's hidden auth iframe grabs the focus right after a step appears: take it back
    // (but leave it alone once the user has moved with the D-pad or works in a visible frame).
    const a = doc.activeElement;
    if (sheetTarget && doc.contains(sheetTarget) && shown(sheetTarget) && sheetRetries < 6 &&
        (document.activeElement !== f || a === doc.body || (a.tagName === 'IFRAME' && !bigFrame(a)))) {
      sheetRetries++;
      focusField(f, sheetTarget, hasLogin && sheetTarget.matches(LOGIN_FIELD) && !sheetTarget.value);
    }
  }
  setInterval(watchSignIn, 400);

  function closeSignIn() {
    const f = signInFrame();
    if (!f) return false;
    try {
      const b = f.contentDocument.querySelector('.cc-modal__close-button, button[aria-label="Close"]');
      if (b) { b.click(); pendingFocus = true; return true; }
    } catch (e) { /* ignore */ }
    return false;
  }

  // ---------- Back ----------
  window.tvBack = function () {
    if (closeSignIn()) return true;
    const d = dialogRoot();
    if (d) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      const close = $$('button', d).find(b => /close|закрыть|cancel|отмена/i.test((b.getAttribute('aria-label') || '') + b.textContent));
      if (close) close.click();
      pendingFocus = true;
      return true;
    }
    const path = location.pathname;
    if (!isRoot(path) && history.length > 1) { history.back(); pendingFocus = true; return true; }
    if (!current || !bar.contains(current)) {
      const page = $('.scrollable-page');
      if (page) page.scrollTop = 0;
      setFocus($('button[data-tab].active', bar) || $('button[data-tab]', bar));
      return true;
    }
    if (tabOf(path) !== 'home') { go(TABS[0][2]()); pendingFocus = true; return true; }
    return false;   // exit the app
  };

  // ---------- keys (capture phase, before the site's shortcuts) ----------
  const DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  function eat(e) { e.preventDefault(); e.stopImmediatePropagation(); }
  window.addEventListener('keydown', e => {
    const t = e.target;
    const typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA');
    const dir = DIRS[e.key];
    if (dir) {
      if (typing && (dir === 'left' || dir === 'right')) {
        const atEdge = dir === 'left' ? t.selectionStart === 0 : t.selectionEnd === t.value.length;
        if (!atEdge) return;
      }
      eat(e); move(dir); return;
    }
    if (e.key === 'Enter' && current && !typing) {
      if (activate(current)) eat(e);
      return;
    }
    if ((e.key === 'Escape' || e.key === 'GoBack' || (e.key === 'Backspace' && !typing))) {
      eat(e); window.tvBack(); return;
    }
    if (/^Media|^MediaPlayPause$/.test(e.key) && media(e.key)) eat(e);
  }, true);

  // Mouse/touch users (emulator) keep working: clicking something moves our focus there.
  document.addEventListener('focusin', e => {
    const el = e.target;
    if (el === current || !(el instanceof Element)) return;
    if (bar.contains(el) || (el.closest('.scrollable-page, .player-bar, [role="dialog"]') && !el.closest(SKIP))) {
      if (current) current.classList.remove('tv-focus');
      current = el;
      el.classList.add('tv-focus');
    }
  }, true);

  decorate();
})();
