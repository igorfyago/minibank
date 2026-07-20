/**
 * THE TWO PAGES ARE ONE THEME · a test that compares COMPONENT RULES.
 *
 * web/index.html (the bank) and web-broker/portfolio.html (the portfolio)
 * cannot share a stylesheet today: BrokerApi roots its static files at
 * /web-broker and HttpApi roots at /web, two processes, two ports. So the
 * portfolio carries a COPY of the bank's rules. A copy drifts, and this drift
 * has shipped twice while a source-level check said it had not.
 *
 * Why a token check was not enough. Both pages can hold identical :root
 * variables and still look like different products, because what a reader sees
 * is the COMPONENT: the radius on a section, the padding in a tile, the grid
 * that money is laid out on. --panel being #161b22 on both pages says nothing
 * about whether a card is 10px or 18px round. So this test compares the
 * declarations of every selector the two pages share, not their variables.
 *
 * It fails if a radius, colour, padding or any other declaration changes on
 * one side only. Mutation-verified: changing `.prod` padding in the portfolio
 * from `11px 13px` to `12px 13px` fails this file, and putting it back passes.
 *
 * No npm, no package.json, nothing to install · a hand-rolled CSS parser and
 * node's own test runner, the same way lib.test.js works.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const RES = path.join(__dirname, '..', '..', 'main', 'resources');
const BANK_FILE = path.join(RES, 'web', 'index.html');
const PORT_FILE = path.join(RES, 'web-broker', 'portfolio.html');

// ============================================================ the CSS parser
// Deliberately small and deliberately not a real CSS engine. It has to handle
// exactly what these two files contain: nested at-rules, multi-selector
// preludes, and the same selector declared more than once.

const stripComments = css => css.replace(/\/\*[\s\S]*?\*\//g, '');

/** The <style> block, comments stripped. There is exactly one per page. */
function styleBlock(file) {
  const src = fs.readFileSync(file, 'utf8');
  const m = src.match(/<style>([\s\S]*?)<\/style>/);
  assert.ok(m, `no <style> block in ${path.basename(file)}`);
  return stripComments(m[1]);
}

/** `a:1; b:2` -> Map { a => '1', b => '2' }, whitespace normalised so that a
 *  reformat is not reported as a change but a real edit is. Splits on `;` at
 *  paren depth zero, because a value like radial-gradient(...) contains
 *  commas and could contain a semicolon in a data: url. */
function parseDecls(body) {
  const out = new Map();
  let depth = 0, cur = '';
  const flush = () => {
    const s = cur.trim(); cur = '';
    if (!s) return;
    const i = s.indexOf(':');
    if (i === -1) return;
    const prop = s.slice(0, i).trim().toLowerCase();
    const val = s.slice(i + 1).trim().replace(/\s+/g, ' ');
    out.set(prop, val);           // later declaration of the same prop wins
  };
  for (const ch of body) {
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
    if (ch === ';' && depth === 0) flush();
    else cur += ch;
  }
  flush();
  return out;
}

/**
 * Walk a stylesheet into flat rules, one per selector.
 * `context` is the at-rule nesting a rule sits under, so a declaration inside
 * `@media (max-width:760px)` is never compared against the same selector's
 * declarations at top level · they are different rules and a test that
 * conflated them would pass while the phone layouts diverged.
 */
function parseRules(css, context = '') {
  const rules = [];
  let i = 0;
  for (;;) {
    const open = css.indexOf('{', i);
    if (open === -1) break;
    const prelude = css.slice(i, open).trim();
    let depth = 1, j = open + 1;
    while (j < css.length && depth > 0) {
      if (css[j] === '{') depth++;
      else if (css[j] === '}') depth--;
      j++;
    }
    const body = css.slice(open + 1, j - 1);
    if (prelude.startsWith('@')) {
      const at = prelude.replace(/\s+/g, ' ');
      if (/^@(media|supports)\b/i.test(at)) {
        rules.push(...parseRules(body, context ? `${context} && ${at}` : at));
      } else {
        // @keyframes and friends: compare the whole body as one opaque value,
        // so a changed keyframe still fails the test.
        rules.push({ context, selector: at,
                     decls: new Map([['<block>', body.replace(/\s+/g, ' ').trim()]]) });
      }
    } else {
      const decls = parseDecls(body);
      for (const sel of prelude.split(',').map(s => s.replace(/\s+/g, ' ').trim())) {
        if (sel) rules.push({ context, selector: sel, decls });
      }
    }
    i = j;
  }
  return rules;
}

/**
 * Collapse a stylesheet to what the CASCADE actually produces for each
 * (context, selector): declarations merged in source order.
 *
 * This matters. The bank declares `.card` twice · the component near the top
 * and `overflow-x:auto` in a one-liner at the very bottom · while the
 * portfolio declares it once with both. Those are the same computed rule, and
 * a parser that compared rule-by-rule would report a difference that does not
 * exist on screen. What a reader sees is the merge, so the merge is what is
 * compared.
 */
function sheet(file) {
  const merged = new Map();     // "context|selector" -> Map(prop -> value)
  for (const r of parseRules(styleBlock(file))) {
    const key = `${r.context}|${r.selector}`;
    if (!merged.has(key)) merged.set(key, new Map());
    const into = merged.get(key);
    for (const [p, v] of r.decls) into.set(p, v);
  }
  return merged;
}

const BANK = sheet(BANK_FILE);
const PORT = sheet(PORT_FILE);

const selOf = key => key.slice(key.indexOf('|') + 1);
const ctxOf = key => key.slice(0, key.indexOf('|'));

/** A COMPONENT rule is one that names a class or an id. `*`, `body`, `th`,
 *  `@keyframes` and the like are resets and element defaults · policy, not
 *  components · and the portfolio is allowed to carry a policy the bank does
 *  not (it has a reduced-motion kill the bank has no equivalent for). They are
 *  still compared for equality when BOTH pages define them; they are simply
 *  not required to exist on both. */
const isComponent = sel => /[.#]/.test(sel);

/**
 * DIVERGENCES THAT ARE NOT DRIFT · every one needs a reason, and the reason is
 * checked. The test asserts the actual difference EQUALS the declared one, so
 * this list cannot rot in either direction: an undeclared change fails, and a
 * declared change that someone has since fixed also fails, which forces the
 * entry to be deleted rather than left lying.
 *
 * `bankOnly` are properties the bank sets and the portfolio deliberately does
 * not. `portfolioOnly` is the mirror. A property both set to DIFFERENT values
 * can never be listed here · that is drift by definition and always fails.
 */
const ALLOWED = new Map([
  ['|main', {
    reason: 'The bank reserves 46px at the foot of <main> for #ticker-bar, its '
          + 'fixed operations tape. The portfolio has no ticker bar, so the same '
          + 'reservation would be 46px of dead scroll under the last card.',
    bankOnly: ['padding-bottom'], portfolioOnly: []
  }]
  // The `.prod.open { grid-column:1/-1 }` allowance that used to sit here is
  // GONE, and its deletion is the point rather than a tidy-up. It bought the
  // expanded TILE a full grid row so that a 65px range input could become a
  // 234px one. Holdings are not tiles any more · they are rows in a flat
  // sorted list, each already the full width of the card, and the order ticket
  // opens underneath one in a .prod-x that was never in a grid. Nothing is
  // spanning anything, so there is nothing left to allow. The Watchlist below
  // still draws .prod tiles in a .prod-grid, and none of them opens.
]);

// ==================================================================== tests

test('every selector both pages define declares exactly the same thing', () => {
  const drift = [];
  for (const [key, portDecls] of PORT) {
    const bankDecls = BANK.get(key);
    if (!bankDecls) continue;                  // covered by the next test
    const allow = ALLOWED.get(key) || { bankOnly: [], portfolioOnly: [] };
    const props = new Set([...bankDecls.keys(), ...portDecls.keys()]);
    for (const p of props) {
      const b = bankDecls.get(p), q = portDecls.get(p);
      if (b === q) continue;
      if (b !== undefined && q === undefined && allow.bankOnly.includes(p)) continue;
      if (q !== undefined && b === undefined && allow.portfolioOnly.includes(p)) continue;
      drift.push(`${key}  {${p}}  bank=${JSON.stringify(b)}  portfolio=${JSON.stringify(q)}`);
    }
  }
  assert.deepEqual(drift, [],
    'the two pages disagree about a shared component:\n  ' + drift.join('\n  '));
});

// ------------------------------------------------------ DELETION IS DRIFT
/**
 * THE HOLE THIS CLOSES. The test above walks PORT and skips any key the bank
 * does not have; the invented-component test walks PORT for keys the bank does
 * not have. Neither direction ever walks BANK. So a rule the bank DEFINES and
 * the portfolio DELETES was compared by nothing.
 *
 * Mutation-verified, before the fix: deleting all fourteen money-tile rules
 * from the portfolio · .prod-grid through .prod-x · while leaving every scrap
 * of markup in place left the suite at 6 pass, 0 fail, with the holdings
 * rendering as unstyled stacked divs. Deleting `.prod-chev` alone, `.prod-grid`
 * alone, and the phone `.balance-num{font-size:38px}` line were each missed the
 * same way. One deletion WAS caught · the single `.prod{border/radius/padding}`
 * line · and only because `.prod` happens to be declared twice, so the merged
 * key survived. An accident of source layout is not a guard.
 *
 * WHAT IT CANNOT BE. "The portfolio must define every rule the bank defines"
 * is absurd: the bank has a Kubernetes diagram, a quiz and a ticker tape, and
 * hundreds of rules for them. The honest requirement is narrower and follows
 * from what a copy is FOR · if the portfolio puts a class on an element, it has
 * signed up for the way the bank styles that class, in every context, including
 * the phone breakpoint. So the required set is: every bank rule all of whose
 * classes and ids the portfolio actually uses in its markup.
 *
 * The used-name set is deliberately over-collected · every identifier-shaped
 * token inside any class="…" attribute, interpolation and all. Over-collecting
 * can only ever ADD requirements, and only for names the bank also styles.
 */

/** Every identifier-ish token appearing in any class attribute or id, plus the
 *  ids the script reaches for with $('…'). */
function namesUsed(src) {
  const used = new Set();
  const add = s => { for (const t of s.match(/[A-Za-z][A-Za-z0-9_-]*/g) || []) used.add(t); };
  for (const m of src.matchAll(/class=(?:"([^"]*)"|\\?'([^']*)')/g)) add(m[1] || m[2] || '');
  for (const m of src.matchAll(/\bclassName\s*=\s*[`'"]([^`'"]*)/g)) add(m[1]);
  for (const m of src.matchAll(/\bid="([^"]*)"/g)) add(m[1].replace(/\$\{[\s\S]*?\}/g, ' '));
  for (const m of src.matchAll(/\$\('([^']+)'/g)) add(m[1]);
  return used;
}

const PORT_USES = namesUsed(portSrcRaw());
function portSrcRaw() { return fs.readFileSync(PORT_FILE, 'utf8'); }

/** The class/id names a selector depends on. `.prod.open .prod-chev` -> the
 *  three of them; a rule is required only when the portfolio uses them ALL. */
const namesIn = sel => (sel.match(/[.#]([A-Za-z][A-Za-z0-9_-]*)/g) || []).map(s => s.slice(1));

/** The ELEMENT types a selector also depends on · `.card h2` wants an h2 and
 *  `.muted code` wants a code, and the portfolio renders one of those. Without
 *  this the test demands rules for elements the page never draws. */
function tagsIn(sel) {
  return sel.replace(/::?[a-z-]+(\([^)]*\))?/gi, ' ')      // pseudos are not tags
            .split(/[\s>+~,]+/).filter(Boolean)
            .map(c => (c.match(/^([a-zA-Z][a-zA-Z0-9]*)/) || [])[1])
            .filter(Boolean);
}
const PORT_TAGS = new Set([...portSrcRaw().matchAll(/<([a-zA-Z][a-zA-Z0-9]*)[\s>/]/g)].map(m => m[1].toLowerCase()));

test('a rule the bank has and the portfolio USES may not be silently deleted', () => {
  const missing = [], different = [];
  for (const [key, bankDecls] of BANK) {
    const sel = selOf(key);
    const names = namesIn(sel);
    if (!names.length) continue;                 // resets and element defaults
    if (!names.every(n => PORT_USES.has(n))) continue;   // the portfolio never draws it
    if (!tagsIn(sel).every(t => PORT_TAGS.has(t.toLowerCase()))) continue;

    const portDecls = PORT.get(key);
    if (!portDecls) { missing.push((ctxOf(key) ? ctxOf(key) + ' ' : '') + sel); continue; }
    const allow = ALLOWED.get(key) || { bankOnly: [], portfolioOnly: [] };
    for (const [p, b] of bankDecls) {
      if (portDecls.get(p) === b) continue;
      if (!portDecls.has(p) && allow.bankOnly.includes(p)) continue;
      different.push(`${key}  {${p}}  bank=${JSON.stringify(b)}  portfolio=${JSON.stringify(portDecls.get(p))}`);
    }
  }
  assert.deepEqual(missing, [],
    'the portfolio uses these classes and has dropped the bank\'s rule for them:\n  '
    + missing.join('\n  '));
  assert.deepEqual(different, [],
    'the portfolio kept the selector and lost the declaration:\n  ' + different.join('\n  '));
});

test('an animation the portfolio names must still be defined on it', () => {
  // @keyframes has no class to key off, so the deletion test above cannot see
  // it · a stylesheet that says `animation:skm` and defines no skm shimmers
  // nothing, silently.
  const portCss = styleBlock(PORT_FILE);
  const wanted = [...BANK.keys()].map(selOf).filter(s => /^@keyframes\b/i.test(s));
  for (const at of wanted) {
    const name = at.split(/\s+/)[1];
    if (!new RegExp(`animation[^;}]*\\b${name}\\b`).test(portCss)) continue;
    const key = [...PORT.keys()].find(k => selOf(k).replace(/\s+/g, ' ') === at);
    assert.ok(key, `the portfolio animates with ${name} and no longer defines it`);
    assert.deepEqual([...PORT.get(key)], [...BANK.get('|' + at)],
      `@keyframes ${name} has drifted`);
  }
});

test('every declared divergence is still real · no stale allowances', () => {
  for (const [key, allow] of ALLOWED) {
    const bankDecls = BANK.get(key), portDecls = PORT.get(key);
    assert.ok(bankDecls && portDecls, `${key} is allow-listed but one page no longer defines it`);
    for (const p of allow.bankOnly) {
      assert.ok(bankDecls.has(p) && !portDecls.has(p),
        `stale allowance: ${key} {${p}} is no longer bank-only · delete the entry (${allow.reason})`);
    }
    for (const p of allow.portfolioOnly) {
      assert.ok(portDecls.has(p) && !bankDecls.has(p),
        `stale allowance: ${key} {${p}} is no longer portfolio-only · delete the entry`);
    }
  }
});

test('the portfolio invents no component the bank does not have', () => {
  const invented = [...PORT.keys()]
    .filter(k => !BANK.has(k) && isComponent(selOf(k)))
    .map(k => (ctxOf(k) ? `${ctxOf(k)} ` : '') + selOf(k));
  assert.deepEqual(invented, [],
    'these exist only on the portfolio · every class must already exist in the bank:\n  '
    + invented.join('\n  '));
});

// -------------------------------------------------------- shapes, not rules
// The rules above prove the two stylesheets agree. They cannot prove the
// portfolio USES the bank's shapes · a page can hold a perfect copy of .prod
// and .prod-grid and still draw everything as a list, which is exactly the
// state this work started from.

const portSrc = fs.readFileSync(PORT_FILE, 'utf8');
const bankSrc = fs.readFileSync(BANK_FILE, 'utf8');
const classCombos = src => [...src.matchAll(/class="([^"]*\bcard\b[^"]*)"/g)]
  .map(m => m[1].split(/\s+/).filter(Boolean).sort().join(' '));

/* THE TILES-ON-A-GRID TEST HAS MOVED, and it moved because it was a lie.
 *
 * It used to live here as two independent regexes over the raw source ·
 * `/class="prod-grid"/` and `/class="prod expandable/` · under a name that
 * promised containment. Neither expresses containment, and the strings they
 * looked for appear four more times in this file: the loading skeleton, the
 * watchlist and the empty-watchlist offer all carry a `class="prod-grid"`.
 * Mutation-verified: wrapping the holdings in `<div class="tx-day">` instead
 * of the grid · which stacks every position full width, the exact list shape
 * this work removed · left the suite green.
 *
 * Containment is a property of RENDERED OUTPUT, so it is asserted on rendered
 * output now. portfolio-render.test.js runs the page's own drawHoldings in a
 * vm and walks the div stack of what comes back, so a tile whose ancestry does
 * not contain a .prod-grid fails no matter how the source is spelled. */

test('the portfolio keeps NO transaction-list idiom for its holdings', () => {
  // Holdings are money, not a log. .tx-row is the bank's shape for a PAYMENT
  // and drawing a position with it was the whole complaint. Comments may
  // discuss it (they explain why it went); markup may not use it.
  assert.doesNotMatch(portSrc, /class="tx-row/,
    'holdings have regressed to the bank\'s transaction-list idiom');
});

test('the portfolio wraps sections the way the bank wraps Products', () => {
  // NOT "at least one plain .card". Measured in a browser on both live pages:
  // the bank's phone surface contains ZERO plain .card · Products and
  // Transactions are both `card tx-card`, computed border-radius 18px, and
  // `document.querySelector('.phone .card:not(.acct-card):not(.tx-card)')` is
  // null on the bank exactly as it is on the portfolio. The bank's 14 plain
  // 10px .cards all live on its OTHER tabs (x-ray, console, guide, quiz),
  // which are desktop documentary surfaces and not the phone.
  //
  // So the parity that matters is: every card combination the portfolio uses
  // is one the bank also uses. Asserting a plain .card here would have forced
  // the portfolio to 10px sections while the bank's equivalent sections are
  // 18px · manufacturing the drift this file exists to prevent.
  const bank = new Set(classCombos(bankSrc));
  const strange = [...new Set(classCombos(portSrc))].filter(c => !bank.has(c));
  assert.deepEqual(strange, [],
    'the portfolio wraps a section in a card combination the bank never uses:\n  '
    + strange.join('\n  '));

  // AND IT BINDS TO THE SECTIONS THEMSELVES. `classCombos(portSrc).includes(
  // 'card tx-card')` used to close this test · a presence check over the whole
  // file, satisfied by any one element anywhere. Mutation-verified: turning
  // BOTH money sections into plain `<div class="card">` (10px) and dropping a
  // single empty `<div class="card tx-card"></div>` after <body> left the
  // suite green while producing exactly the 10px-vs-18px section drift the
  // comment above says this test exists to prevent. A decoy cannot carry an
  // <h2>, so the wrapper is looked up BY the heading it wraps.
  const wrappers = new Map();
  for (const m of portSrc.matchAll(/<div class="([^"]*)">\s*<h2>([^<]*)<\/h2>/g)) {
    wrappers.set(m[2].trim(), m[1].split(/\s+/).filter(Boolean).sort().join(' '));
  }
  for (const section of ['Holdings', 'Watchlist']) {
    assert.equal(wrappers.get(section), 'card tx-card',
      `the ${section} section must be wrapped in the bank's Products wrapper, `
      + '`card tx-card` · 18px, not a plain 10px .card');
  }
});
