/* b4rruf3t estate navigation · the one bar every app includes.
 * Canonical delivery: <script src="https://auth.b4rruf3t.com/eco-nav.js" defer></script>
 * Progressive enhancement: if auth. is unreachable the page simply has no estate bar.
 * Replaces the old eco-nav.html fragments that were vendored per app and drifted. */
(function () {
  'use strict';

  var TOKEN_KEY = 'b4rruf3t_token';
  var REFRESH_KEY = 'b4rruf3t_refresh';
  var API = 'https://auth.b4rruf3t.com';

  var TABS = [
    { id: 'bank',   label: 'Bank',   href: 'https://bank.b4rruf3t.com' },
    { id: 'broker', label: 'Invest', href: 'https://broker.b4rruf3t.com' },
    { id: 'mart',   label: 'Shop',   href: 'https://mart.b4rruf3t.com' },
    { id: 'pay',    label: 'Pay',    href: 'https://pay.b4rruf3t.com' },
    { id: 'desk',   label: 'Trade',  href: 'https://desk.b4rruf3t.com' },
    { id: 'gex',    label: 'Data',   href: 'https://gex.b4rruf3t.com' },
    { id: 'stats',  label: 'Stats',  href: 'https://analytics.b4rruf3t.com' },
    { id: 'obs',    label: 'Agents', href: 'https://obs.b4rruf3t.com' }
  ];

  /* The bar is a DIV, not a <nav>, and its geometry rides inline on the
   * element: host pages style bare element selectors (the desk keeps
   * nav{position:fixed;inset:0 auto 0 0;width:58px} for its mobile drawer,
   * and another breakpoint says nav{display:none}), and a shared bar cannot
   * assume anything about the page it lands on. Cosmetics stay in the
   * stylesheet, where #eco-nav outranks any element or class rule. */
  /* One geometry for every host. --eco-nav-h lets host subheaders do
   * top: var(--eco-nav-h, 48px) so sticky bars ride under the estate bar. */
  var NAV_H = 48;
  var GEOMETRY = 'position:fixed;top:0;left:0;right:0;bottom:auto;' +
    'width:auto;height:' + NAV_H + 'px;min-width:0;max-width:none;margin:0;' +
    'display:flex;flex-direction:row;align-items:center;gap:4px;' +
    'padding:0 20px;z-index:2147483000;overflow:visible;transform:none;';

  var CSS = [
    ':root{--eco-nav-h:' + NAV_H + 'px}',
    /* Reserve the band BEFORE first paint: the page never shifts when the
     * bar lands. Padding on html (not body) so hosts with their own
     * body margin/padding reset don't drop the reservation. */
    'html{padding-top:var(--eco-nav-h) !important}',
    '#eco-nav{top:0;background:rgba(13,17,23,.95);backdrop-filter:blur(12px);',
    'border-bottom:1px solid #21262d;box-sizing:border-box;',
    "font:13px/1.5 system-ui,'Segoe UI',sans-serif}",
    '#eco-nav .eco-brand{color:#e6edf3;font-weight:650;margin-right:12px;text-decoration:none}',
    '#eco-nav .eco-brand:hover{color:#58a6ff}',
    '#eco-nav .eco-tab{color:#8b949e;text-decoration:none;padding:5px 14px;',
    'border-radius:999px;transition:all .2s}',
    '#eco-nav .eco-tab:hover{color:#e6edf3;background:#21262d}',
    '#eco-nav .eco-tab.active{color:#58a6ff;background:rgba(88,166,255,.1)}',
    /* Fixed min-width so the Sign-in -> avatar swap never reseats the bar */
    '#eco-nav .eco-user{margin-left:auto;color:#8b949e;font-size:12px;',
    'display:flex;align-items:center;justify-content:flex-end;gap:8px;min-width:140px}',
    '#eco-nav .eco-user a{color:#58a6ff;text-decoration:none}',
    '#eco-nav .eco-user a:hover{text-decoration:underline}',
    '#eco-nav .eco-avatar{width:24px;height:24px;border-radius:50%;',
    'background:#58a6ff;color:#fff;display:flex;align-items:center;',
    'justify-content:center;font-size:11px;font-weight:600}',
    /* THE ESTATE TAPE · the bank's markee, shared. Docked at the bottom of
     * every app that loads this bar, silent and hidden until an event
     * arrives, and fed by the host app through a DOM event ("eco:tick",
     * detail {icon, text, amount?, tone?}) — each app puts its own money
     * moments on it in its own words, and an app with nothing to say
     * advertises nothing. */
    '#eco-tape{display:flex;align-items:center;gap:10px;overflow:hidden;',
    'background:rgba(7,12,16,.96);border-top:1px solid #143024;backdrop-filter:blur(8px);',
    "font:11.5px 'Cascadia Code',Consolas,monospace;box-sizing:border-box;padding:0 14px;",
    'transform:translateY(110%);transition:transform .45s ease}',
    '#eco-tape.on{transform:none}',
    '#eco-tape .tp-i{font-size:13px}',
    '#eco-tape .tp-t{color:#5da878;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '#eco-tape .tp-a{margin-left:auto;font-weight:750;white-space:nowrap}',
    '#eco-tape .tp-a.pos{color:#31e981;text-shadow:0 0 11px rgba(49,233,129,.5)}',
    '#eco-tape .tp-a.neg{color:#ff5d6c;text-shadow:0 0 11px rgba(255,93,108,.4)}',
    '#eco-tape .tp-a.amb{color:#d29922}'
  ].join('');

  /* The tape lives beside the bar: one fixture, one look, every app. Events
   * queue so a burst paints in order; the tape dims itself back to silence a
   * few seconds after the last one, exactly like the bank's own. */
  var TAPE_H = 34, tapeEl = null, tapeTimer = null;
  function buildTape() {
    if (tapeEl || !document.body) return;
    /* A host with its own money tape keeps the floor: the bank's train-tape
     * IS its operations room, and two markees on one screen is one too many.
     * There the bank's events still feed OTHER apps through eco:tick. */
    if (document.getElementById('ticker-bar')) return;
    tapeEl = document.createElement('div');
    tapeEl.id = 'eco-tape';
    tapeEl.style.cssText = 'position:fixed;left:0;right:0;bottom:0;height:' + TAPE_H + 'px;' +
      'z-index:2147482999;transform:translateY(110%)';
    document.body.appendChild(tapeEl);
    document.addEventListener('eco:tick', function (ev) {
      var d = (ev && ev.detail) || {};
      if (!d.text) return;
      tapeEl.replaceChildren();
      var icon = document.createElement('span');
      icon.className = 'tp-i'; icon.textContent = d.icon || '💸';
      var text = document.createElement('span');
      text.className = 'tp-t'; text.textContent = d.text;
      tapeEl.appendChild(icon); tapeEl.appendChild(text);
      if (d.amount) {
        var amt = document.createElement('span');
        amt.className = 'tp-a ' + (d.tone === 'pos' || d.tone === 'neg' || d.tone === 'amb' ? d.tone : 'amb');
        amt.textContent = d.amount;
        tapeEl.appendChild(amt);
      }
      tapeEl.classList.add('on');
      if (tapeTimer) clearTimeout(tapeTimer);
      tapeTimer = setTimeout(function () { tapeEl.classList.remove('on'); }, d.hold || 6000);
    });
  }

  function build() {
    if (document.getElementById('eco-nav')) return;

    var style = document.createElement('style');
    style.textContent = CSS;
    document.head.appendChild(style);

    var nav = document.createElement('div');
    nav.id = 'eco-nav';
    nav.setAttribute('role', 'navigation');
    nav.setAttribute('aria-label', 'b4rruf3t estate');
    nav.style.cssText = GEOMETRY;

    var brand = document.createElement('a');
    brand.className = 'eco-brand';
    brand.href = 'https://b4rruf3t.com';
    brand.textContent = 'b4rruf3t';
    nav.appendChild(brand);

    var host = location.hostname;
    TABS.forEach(function (t) {
      var a = document.createElement('a');
      a.className = 'eco-tab';
      a.href = t.href;
      a.dataset.eco = t.id;
      a.textContent = t.label;
      /* exact hostname match: href.includes(host) lit every tab on the apex */
      if (new URL(t.href).hostname === host) a.classList.add('active');
      nav.appendChild(a);
    });

    var user = document.createElement('div');
    user.className = 'eco-user';
    user.id = 'eco-user-widget';
    nav.appendChild(user);

    document.body.insertBefore(nav, document.body.firstChild);
    buildTape();
    paintUser(user, nav);
  }

  /* The band is reserved by the stylesheet (html{padding-top}), and the bar's
   * height is a constant — no measuring, no re-seating, no layout churn. */
  function seat(nav) {}

  function signInLink() {
    return '<a href="' + API + '/login?next=' + encodeURIComponent(location.href) + '">Sign in</a>';
  }

  /* Stable placeholder so the async /users/me paint never moves anything. */
  function paintUser(widget, nav) {
    var done = function () {};
    var token = localStorage.getItem(TOKEN_KEY);
    if (!token) { widget.innerHTML = signInLink(); done(); return; }

    fetch(API + '/v1/users/me', { headers: { 'Authorization': 'Bearer ' + token } })
      .then(function (r) {
        if (r.status !== 401) return r;
        return refreshToken().then(function (ok) {
          if (ok) return fetch(API + '/v1/users/me', {
            headers: { 'Authorization': 'Bearer ' + localStorage.getItem(TOKEN_KEY) }
          });
          localStorage.removeItem(TOKEN_KEY);
          localStorage.removeItem(REFRESH_KEY);
          throw new Error('session expired');
        });
      })
      .then(function (r) { return r.json(); })
      .then(function (u) {
        var name = u.name || u.email || '?';
        var avatar = document.createElement('span');
        avatar.className = 'eco-avatar';
        avatar.textContent = name[0].toUpperCase();
        var label = document.createElement('span');
        label.textContent = name;
        var out = document.createElement('a');
        out.href = '#';
        out.style.marginLeft = '8px';
        out.textContent = 'Sign out';
        out.onclick = function () { window.ecoLogout(); return false; };
        widget.replaceChildren(avatar, label, out);
        done();
      })
      .catch(function () { widget.innerHTML = signInLink(); done(); });
  }

  function refreshToken() {
    var refresh = localStorage.getItem(REFRESH_KEY);
    if (!refresh) return Promise.resolve(false);
    return fetch(API + '/v1/tokens/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: refresh })
    }).then(function (r) {
      if (!r.ok) return false;
      return r.json().then(function (d) {
        localStorage.setItem(TOKEN_KEY, d.access_token);
        localStorage.setItem(REFRESH_KEY, d.refresh_token);
        return true;
      });
    }).catch(function () { return false; });
  }

  window.ecoLogout = function () {
    var refresh = localStorage.getItem(REFRESH_KEY);
    if (refresh) {
      fetch(API + '/v1/tokens/revoke', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: refresh })
      });
    }
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    location.reload();
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', build);
  } else {
    build();
  }
})();
