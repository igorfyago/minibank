/**
 * THE BROKER PAGES ARE THE BANK'S THEME · a test that compares COMPONENT RULES.
 *
 * web/index.html (the bank) and the pages under web-broker (portfolio.html,
 * instrument.html) cannot share a stylesheet today: BrokerApi roots its static
 * files at /web-broker, HttpApi at /web, two processes, two ports. So each
 * broker page carries a COPY of the bank's rules. A copy drifts, and this
 * drift has shipped twice while a source-level check said it had not.
 *
 * Why a token check was not enough. Two pages can hold identical :root
 * variables and still look like different products, because what a reader sees
 * is the COMPONENT: the radius on a section, the padding in a tile, the grid
 * that money is laid out on. --panel being #161b22 on both pages says nothing
 * about whether a card is 10px or 18px round. So this test compares the
 * declarations of every selector the pages share, not their variables.
 *
 * It fails if a radius, colour, padding or any other declaration changes on
 * one side only. Mutation-verified: changing `.prod` padding in the portfolio
 * from `11px 13px` to `12px 13px` fails this file, and putting it back passes.
 *
 * PARAMETERISED over every broker page, so instrument.html is gated by the
 * same machinery the day it exists rather than by a copy of this file · the
 * suite below runs once per page against the same parsed bank sheet.
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

/** Every page that carries a copy of the bank's stylesheet, and the <h2>
 *  sections on it that must sit in the bank's Products wrapper. */
const PAGES = [
  { name: 'portfolio', file: path.join(RES, 'web-broker', 'portfolio.html'),
    sections: ['Holdings', 'Watchlist'] },
  { name: 'instrument', file: path.join(RES, 'web-broker', 'instrument.html'),
    sections: ['Chart', 'Ticket', 'Option chain'] },
];

// ============================================================ the CSS parser
// Deliberately small and deliberately not a real CSS engine. It has to handle
// exactly what these files contain: nested at-rules, multi-selector preludes,
// and the same selector declared more than once.

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
 * and `overflow-x:auto` in a one-liner at the very bottom · while the broker
 * pages declare it once with both. Those are the same computed rule, and a
 * parser that compared rule-by-rule would report a difference that does not
 * exist on screen. What a reader sees is the merge, so the merge is compared.
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

const selOf = key => key.slice(key.indexOf('|') + 1);
const ctxOf = key => key.slice(0, key.indexOf('|'));

/** A COMPONENT rule is one that names a class or an id. `*`, `body`, `th`,
 *  `@keyframes` and the like are resets and element defaults · policy, not
 *  components · and a broker page is allowed to carry a policy the bank does
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
 * `bankOnly` are properties the bank sets and the page deliberately does not.
 * `pageOnly` is the mirror. A property both set to DIFFERENT values can never
 * be listed here · that is drift by definition and always fails.
 */
const ALLOWED = new Map([
  ['|main', {
    reason: 'The bank reserves 46px at the foot of <main> for #ticker-bar, its '
          + 'fixed operations tape. The broker pages have no ticker bar, so the '
          + 'same reservation would be 46px of dead scroll under the last card.',
    bankOnly: ['padding-bottom'], pageOnly: []
  }]
]);

/** Every identifier-ish token appearing in any class attribute or id, plus the
 *  ids the script reaches for with $('…'). Deliberately over-collected ·
 *  over-collecting can only ever ADD requirements, and only for names the
 *  bank also styles. */
function namesUsed(src) {
  const used = new Set();
  const add = s => { for (const t of s.match(/[A-Za-z][A-Za-z0-9_-]*/g) || []) used.add(t); };
  for (const m of src.matchAll(/class=(?:"([^"]*)"|\\?'([^']*)')/g)) add(m[1] || m[2] || '');
  for (const m of src.matchAll(/\bclassName\s*=\s*[`'"]([^`'"]*)/g)) add(m[1]);
  for (const m of src.matchAll(/\bid="([^"]*)"/g)) add(m[1].replace(/\$\{[\s\S]*?\}/g, ' '));
  for (const m of src.matchAll(/\$\('([^']+)'/g)) add(m[1]);
  return used;
}

/** The class/id names a selector depends on. `.prod.open .prod-chev` -> the
 *  three of them; a rule is required only when the page uses them ALL. */
const namesIn = sel => (sel.match(/[.#]([A-Za-z][A-Za-z0-9_-]*)/g) || []).map(s => s.slice(1));

/** The ELEMENT types a selector also depends on · `.card h2` wants an h2 and
 *  `.muted code` wants a code. Without this the test demands rules for
 *  elements the page never draws. */
function tagsIn(sel) {
  return sel.replace(/::?[a-z-]+(\([^)]*\))?/gi, ' ')      // pseudos are not tags
            .split(/[\s>+~,]+/).filter(Boolean)
            .map(c => (c.match(/^([a-zA-Z][a-zA-Z0-9]*)/) || [])[1])
            .filter(Boolean);
}

const bankSrc = fs.readFileSync(BANK_FILE, 'utf8');
const classCombos = src => [...src.matchAll(/class="([^"]*\bcard\b[^"]*)"/g)]
  .map(m => m[1].split(/\s+/).filter(Boolean).sort().join(' '));

// ==================================================================== tests
// One suite per broker page, all against the same bank sheet.

for (const page of PAGES) {
  const PAGE = sheet(page.file);
  const pageSrc = fs.readFileSync(page.file, 'utf8');
  const PAGE_USES = namesUsed(pageSrc);
  const PAGE_TAGS = new Set([...pageSrc.matchAll(/<([a-zA-Z][a-zA-Z0-9]*)[\s>/]/g)]
    .map(m => m[1].toLowerCase()));

  test(`${page.name}: every selector both pages define declares exactly the same thing`, () => {
    const drift = [];
    for (const [key, pageDecls] of PAGE) {
      const bankDecls = BANK.get(key);
      if (!bankDecls) continue;                  // covered by the next test
      const allow = ALLOWED.get(key) || { bankOnly: [], pageOnly: [] };
      const props = new Set([...bankDecls.keys(), ...pageDecls.keys()]);
      for (const p of props) {
        const b = bankDecls.get(p), q = pageDecls.get(p);
        if (b === q) continue;
        if (b !== undefined && q === undefined && allow.bankOnly.includes(p)) continue;
        if (q !== undefined && b === undefined && allow.pageOnly.includes(p)) continue;
        drift.push(`${key}  {${p}}  bank=${JSON.stringify(b)}  ${page.name}=${JSON.stringify(q)}`);
      }
    }
    assert.deepEqual(drift, [],
      `the two pages disagree about a shared component:\n  ` + drift.join('\n  '));
  });

  // ------------------------------------------------------ DELETION IS DRIFT
  /**
   * THE HOLE THIS CLOSES. The test above walks the page and skips any key the
   * bank does not have; the invented-component test walks the page for keys
   * the bank does not have. Neither direction ever walks BANK. So a rule the
   * bank DEFINES and the page DELETES was compared by nothing.
   *
   * Mutation-verified on the portfolio, before the fix: deleting all fourteen
   * money-tile rules while leaving every scrap of markup in place left the
   * suite green, with the holdings rendering as unstyled stacked divs.
   *
   * WHAT IT CANNOT BE. "The page must define every rule the bank defines" is
   * absurd: the bank has a Kubernetes diagram, a quiz and a ticker tape. The
   * honest requirement follows from what a copy is FOR · if the page puts a
   * class on an element, it has signed up for the way the bank styles that
   * class, in every context, including the phone breakpoint. So the required
   * set is: every bank rule all of whose classes and ids the page actually
   * uses in its markup.
   */
  test(`${page.name}: a rule the bank has and the page USES may not be silently deleted`, () => {
    const missing = [], different = [];
    for (const [key, bankDecls] of BANK) {
      const sel = selOf(key);
      const names = namesIn(sel);
      if (!names.length) continue;                 // resets and element defaults
      if (!names.every(n => PAGE_USES.has(n))) continue;   // the page never draws it
      if (!tagsIn(sel).every(t => PAGE_TAGS.has(t.toLowerCase()))) continue;

      const pageDecls = PAGE.get(key);
      if (!pageDecls) { missing.push((ctxOf(key) ? ctxOf(key) + ' ' : '') + sel); continue; }
      const allow = ALLOWED.get(key) || { bankOnly: [], pageOnly: [] };
      for (const [p, b] of bankDecls) {
        if (pageDecls.get(p) === b) continue;
        if (!pageDecls.has(p) && allow.bankOnly.includes(p)) continue;
        different.push(`${key}  {${p}}  bank=${JSON.stringify(b)}  ${page.name}=${JSON.stringify(pageDecls.get(p))}`);
      }
    }
    assert.deepEqual(missing, [],
      `${page.name} uses these classes and has dropped the bank's rule for them:\n  `
      + missing.join('\n  '));
    assert.deepEqual(different, [],
      `${page.name} kept the selector and lost the declaration:\n  ` + different.join('\n  '));
  });

  test(`${page.name}: an animation the page names must still be defined on it`, () => {
    // @keyframes has no class to key off, so the deletion test above cannot
    // see it · a stylesheet that says `animation:skm` and defines no skm
    // shimmers nothing, silently.
    const pageCss = styleBlock(page.file);
    const wanted = [...BANK.keys()].map(selOf).filter(s => /^@keyframes\b/i.test(s));
    for (const at of wanted) {
      const name = at.split(/\s+/)[1];
      if (!new RegExp(`animation[^;}]*\\b${name}\\b`).test(pageCss)) continue;
      const key = [...PAGE.keys()].find(k => selOf(k).replace(/\s+/g, ' ') === at);
      assert.ok(key, `${page.name} animates with ${name} and no longer defines it`);
      assert.deepEqual([...PAGE.get(key)], [...BANK.get('|' + at)],
        `@keyframes ${name} has drifted on ${page.name}`);
    }
  });

  test(`${page.name}: every declared divergence is still real · no stale allowances`, () => {
    for (const [key, allow] of ALLOWED) {
      const bankDecls = BANK.get(key), pageDecls = PAGE.get(key);
      assert.ok(bankDecls && pageDecls, `${key} is allow-listed but one page no longer defines it`);
      for (const p of allow.bankOnly) {
        assert.ok(bankDecls.has(p) && !pageDecls.has(p),
          `stale allowance: ${key} {${p}} is no longer bank-only on ${page.name} · delete the entry (${allow.reason})`);
      }
      for (const p of allow.pageOnly) {
        assert.ok(pageDecls.has(p) && !bankDecls.has(p),
          `stale allowance: ${key} {${p}} is no longer ${page.name}-only · delete the entry`);
      }
    }
  });

  test(`${page.name}: invents no component the bank does not have`, () => {
    const invented = [...PAGE.keys()]
      .filter(k => !BANK.has(k) && isComponent(selOf(k)))
      .map(k => (ctxOf(k) ? `${ctxOf(k)} ` : '') + selOf(k));
    assert.deepEqual(invented, [],
      `these exist only on ${page.name} · every class must already exist in the bank:\n  `
      + invented.join('\n  '));
  });

  // -------------------------------------------------------- shapes, not rules
  // The rules above prove the two stylesheets agree. They cannot prove the
  // page USES the bank's shapes · a page can hold a perfect copy of .prod
  // and .prod-grid and still draw everything as a list, which is exactly the
  // state the portfolio work started from. Containment of RENDERED output is
  // asserted in portfolio-render.test.js; what stays here is what a source
  // scan can honestly claim.

  test(`${page.name}: keeps NO transaction-list idiom for money`, () => {
    // Positions and instruments are money, not a log. .tx-row is the bank's
    // shape for a PAYMENT and drawing a position with it was the whole
    // complaint. Comments may discuss it; markup may not use it.
    assert.doesNotMatch(pageSrc, /class="tx-row/,
      `${page.name} has regressed to the bank's transaction-list idiom`);
  });

  test(`${page.name}: wraps sections the way the bank wraps Products`, () => {
    // NOT "at least one plain .card". Measured in a browser on both live
    // pages: the bank's phone surface contains ZERO plain .card · Products
    // and Transactions are both `card tx-card`, computed border-radius 18px.
    // So the parity that matters is: every card combination the page uses is
    // one the bank also uses. Asserting a plain .card here would manufacture
    // the 10px-vs-18px drift this file exists to prevent.
    const bank = new Set(classCombos(bankSrc));
    const strange = [...new Set(classCombos(pageSrc))].filter(c => !bank.has(c));
    assert.deepEqual(strange, [],
      `${page.name} wraps a section in a card combination the bank never uses:\n  `
      + strange.join('\n  '));

    // AND IT BINDS TO THE SECTIONS THEMSELVES. A presence check over the
    // whole file is satisfied by any one element anywhere · mutation-verified
    // on the portfolio: two plain 10px cards plus one empty decoy passed it.
    // A decoy cannot carry an <h2>, so the wrapper is looked up BY the
    // heading it wraps. The class attribute sits last in the tag on these
    // wrappers for exactly this look-up.
    const wrappers = new Map();
    for (const m of pageSrc.matchAll(/<div [^>]*class="([^"]*)">\s*<h2>([^<]*)<\/h2>/g)) {
      wrappers.set(m[2].trim(), m[1].split(/\s+/).filter(Boolean).sort().join(' '));
    }
    for (const m of pageSrc.matchAll(/<div class="([^"]*)">\s*<h2>([^<]*)<\/h2>/g)) {
      wrappers.set(m[2].trim(), m[1].split(/\s+/).filter(Boolean).sort().join(' '));
    }
    for (const section of page.sections) {
      assert.equal(wrappers.get(section), 'card tx-card',
        `the ${section} section on ${page.name} must be wrapped in the bank's Products wrapper, `
        + '`card tx-card` · 18px, not a plain 10px .card');
    }
  });
}
