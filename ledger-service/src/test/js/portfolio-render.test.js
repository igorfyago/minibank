/**
 * THE PORTFOLIO'S RENDERERS, RUN FOR REAL.
 *
 * Run with:  node --test src/test/js
 *
 * Why this file exists, and why it does not grep.
 *
 * theme-parity.test.js compares STYLESHEETS and the class combinations the
 * markup reaches for. It cannot see what a renderer says, and three of the
 * defects this file pins were sentences: a Day figure that blamed a feed that
 * was answering, a band subtotal that blamed a live mark for a missing prior
 * close, and a tile that was drawn outside the grid it was supposed to be laid
 * out on while `/class="prod-grid"/` still matched somewhere else in the file.
 * A regex over source text passed through all three · one of them passed
 * BECAUSE the string it looked for survived in a skeleton that is never on
 * screen once data arrives.
 *
 * So this lifts the page's own <script> into a vm, gives it a document that
 * records what was written to it, and asserts on the HTML the real functions
 * actually produce. No spelling of any line can make these pass; only the
 * right output can.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const PORT_FILE = path.join(__dirname, '..', '..', 'main', 'resources', 'web-broker', 'portfolio.html');
const SRC = fs.readFileSync(PORT_FILE, 'utf8');

// ============================================================== the harness
/**
 * A fresh page per test · `open`, `snap` and `lastMsg` are module state on the
 * real page and a test that shared them would depend on its neighbours.
 *
 * The document is the smallest thing the script will accept: getElementById
 * hands back a recording stub, so `$('holdings').innerHTML = html` is readable
 * afterwards as `page.el('holdings').innerHTML`. The tail of the script calls
 * bankHref() and load(); load() awaits a fetch that is stubbed to reject, which
 * api() is written to survive, so construction is quiet.
 */
function newPage() {
  const els = new Map();
  const el = id => {
    if (!els.has(id)) els.set(id, { id, innerHTML: '', textContent: '', className: '', href: '' });
    return els.get(id);
  };
  const ctx = {
    console,
    document: {
      getElementById: el,
      querySelector: () => null,
      querySelectorAll: () => [],
    },
    location: { search: '?customer=10', hostname: 'localhost', protocol: 'http:' },
    history: {},
    navigator: {},
    URLSearchParams,
    setTimeout: () => 0,
    crypto: { randomUUID: () => 'test-uuid' },
    fetch: () => Promise.reject(new Error('no network in a unit test')),
  };
  ctx.window = ctx;
  ctx.globalThis = ctx;
  vm.createContext(ctx);

  const m = SRC.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(m, 'portfolio.html must carry exactly one inline <script>');
  vm.runInContext(m[1], ctx);

  return {
    ctx,
    el,
    run: expr => vm.runInContext(expr, ctx),
    call: (fn, ...args) => {
      ctx.__args = args;
      return vm.runInContext(`${fn}(...__args)`, ctx);
    },
  };
}

// A holding the way BrokerApi ships one: money as STRINGS, absent as null.
const holding = over => Object.assign({
  symbol: 'AAPL', name: 'Apple Inc.', exchange: 'NASDAQ', kind: 'equity',
  qty: '5', avgCost: '175.00', price: '195.00', priceSource: 'live',
  value: '975.00', costBasis: '875.00',
  unrealized: '100.00', unrealizedPct: '11.43', realized: '0.00',
  prevClose: '190.00', dayChange: '25.00', dayChangePct: '2.63',
  dayBasis: 'session', multiplier: '1', expiresOn: null,
}, over);

const group = over => Object.assign({
  kind: 'equity', label: 'Stocks', holdings: 1,
  marketValue: '975.00', costBasis: '875.00',
  unrealized: '100.00', unrealizedPct: '11.43',
  dayChange: '25.00', dayChangePct: '2.63',
  unpriced: 0, withoutPrevClose: 0, fabricated: 0, stale: 0, expired: 0,
}, over);

const aggregate = over => Object.assign({
  marketValue: '975.00', costBasis: '875.00', unrealized: '100.00', unrealizedPct: '11.43',
  realized: '0.00', dayChange: '25.00', dayChangePct: '2.63',
  holdings: 1, unpriced: 0, withoutPrevClose: 0, fabricated: 0, stale: 0,
  closedPositions: 0, expired: 0,
}, over);

/** The `title` of the one .muted the given html withholds through. */
function titleOf(html) {
  const m = html.match(/title="([^"]*)"/);
  return m ? m[1].replace(/&#39;/g, "'").replace(/&quot;/g, '"').replace(/&amp;/g, '&') : null;
}

/**
 * DIV NESTING, actually walked · this is the whole point of the file.
 * Returns, for every <div> carrying `needle` in its class list, the class
 * lists of its div ancestors, outermost first. A tile moved out of the grid
 * comes back with no 'prod-grid' in its ancestry and no regex can hide it.
 */
function divAncestry(html, needle) {
  const found = [];
  const stack = [];
  const re = /<div\b([^>]*)>|<\/div>/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    if (m[0] === '</div>') { stack.pop(); continue; }
    const cls = (m[1].match(/class="([^"]*)"/) || [, ''])[1].split(/\s+/).filter(Boolean);
    if (cls.includes(needle)) found.push(stack.map(s => s.slice()));
    stack.push(cls);
  }
  assert.equal(stack.length, 0, 'the rendered html must be balanced <div>s');
  return found;
}

// ======================================================= the withheld REASON
// Portfolio.java gates the day block on `q.observed() && q.prevClose() != null
// && valuable`, and the value block on isExpired / multiplier / observed. Those
// are four different systems. One hardcoded sentence named one of them.

test('a holding priced live with NO prior close does not blame the feed', () => {
  const p = newPage();
  // exactly what a position opened today looks like: a live mark, a real
  // market value, and nothing to measure a day against
  const html = p.call('holdingTile', holding({ prevClose: null, dayChange: null, dayChangePct: null }));

  const day = html.match(/<span>Day<\/span><b[^>]*>([\s\S]*?)<\/b>/);
  assert.ok(day, 'the tile face must carry a Day row');
  const why = titleOf(day[1]);
  assert.ok(why, 'a withheld Day must say why');
  assert.doesNotMatch(why, /live mark/,
    'the mark IS live · this is the exact false reason that shipped: ' + JSON.stringify(why));
  assert.match(why, /prior close/,
    'the reason must name the prior close, which is what is actually missing');
  // and the tile above it is still showing the live value it was priced at
  assert.match(html, /class="prod-val">€975\.00</,
    'the same tile values the holding live · that is why blaming the feed is a lie');
});

test('the four withheld-Day conditions produce four different reasons', () => {
  const p = newPage();
  const why = h => titleOf(p.call('holdingTile', h).match(/<span>Day<\/span><b[^>]*>([\s\S]*?)<\/b>/)[1]);

  const noPrevClose = why(holding({ prevClose: null, dayChange: null, dayChangePct: null }));
  const noMark      = why(holding({ price: null, priceSource: 'unavailable', value: null,
                                    unrealized: null, unrealizedPct: null,
                                    prevClose: null, dayChange: null, dayChangePct: null }));
  const expired     = why(holding({ priceSource: 'expired', price: null, value: null,
                                    unrealized: null, unrealizedPct: null,
                                    prevClose: null, dayChange: null, dayChangePct: null }));
  const fabricated  = why(holding({ priceSource: 'fallback', value: null,
                                    unrealized: null, unrealizedPct: null,
                                    dayChange: null, dayChangePct: null }));
  const uncatalogued = why(holding({ multiplier: null, kind: null, name: null, exchange: null,
                                     value: null, unrealized: null, unrealizedPct: null,
                                     dayChange: null, dayChangePct: null }));

  const all = [noPrevClose, noMark, expired, fabricated, uncatalogued];
  assert.equal(new Set(all).size, all.length,
    'each condition is a different fact about a different system · ' + JSON.stringify(all));
  assert.match(expired, /expired/);
  assert.match(noMark, /no mark|did not|returned no/);
  assert.match(fabricated, /observed/);
  assert.match(uncatalogued, /catalog/);
});

test('an expired contract is not described as needing a live mark', () => {
  const p = newPage();
  const h = holding({ priceSource: 'expired', price: null, value: null, prevClose: null,
                      unrealized: null, unrealizedPct: null, dayChange: null, dayChangePct: null,
                      kind: 'option', multiplier: '100', expiresOn: '2020-01-17' });
  const html = p.call('holdingTile', h);
  for (const b of html.match(/<b[^>]*>[\s\S]*?<\/b>/g) || []) {
    const why = titleOf(b);
    if (why) assert.match(why, /expired/,
      'an expired contract explains itself as expired, not as a feed outage: ' + JSON.stringify(why));
  }
});

// ================================================================== the UNIT
// unitFor read `kind === 'crypto' ? '' : ' sh'`, so everything that was not
// crypto was counted in shares. A contract is not a share · it is a claim on
// `multiplier` of them, and the tile says so itself two rows down in the
// 'Contract size' cell. The sub-line understated an option position by 100x.

/** The .prod-sub of a tile · the sub-line that carries the quantity. */
function subOf(html) {
  const m = html.match(/<div class="prod-sub">([\s\S]*?)<\/div>/);
  return m ? m[1] : null;
}

test('an option position is counted in CONTRACTS, not shares', () => {
  const p = newPage();
  const opt = holding({ symbol: 'AAPL260821C250', kind: 'option', qty: '2',
                        name: "AAPL Aug 21 '26 250 Call",
                        multiplier: '100', expiresOn: '2026-08-21' });
  const sub = subOf(p.call('holdingTile', opt));

  assert.ok(sub, 'the tile must carry a sub-line');
  assert.doesNotMatch(sub, /\bsh\b/,
    'this is the defect: two contracts control 200 shares and the tile said '
    + '"2 sh" · ' + JSON.stringify(sub));
  assert.match(sub, /^2 contracts\b/,
    'the quantity leads the sub-line and names what it counts: ' + JSON.stringify(sub));
  // and the expansion that explains the multiplier is still the same fact
  p.run('open = "AAPL260821C250"');
  assert.match(p.call('holdingTile', opt), /Contract size<\/span><b[^>]*>100 × underlying/,
    'the unit on the face and the contract size in the expansion are one claim');
});

test('one contract is singular · and a share is still a share', () => {
  const p = newPage();
  const sub = h => subOf(p.call('holdingTile', h));

  // \b after 'contract' is the assertion · 'contracts' has no boundary there
  assert.match(sub(holding({ kind: 'option', qty: '1', multiplier: '100' })),
    /^1 contract\b/, 'a lone contract does not take the plural');
  assert.match(sub(holding({ kind: 'equity', qty: '5' })), /^5 sh\b/,
    'equities are unchanged · this fix is not a licence to restyle them');
  assert.match(sub(holding({ symbol: 'BTC', kind: 'crypto', qty: '0.5', multiplier: '1' })),
    /^0\.5 ·/, 'crypto carries no unit but the symbol · unchanged');
});

test('an UNCATALOGUED holding claims no unit at all', () => {
  const p = newPage();
  // no registry entry means no multiplier and no kind · the row that already
  // has nothing true to say about its size used to say "shares" anyway
  const sub = subOf(p.call('holdingTile',
    holding({ kind: null, multiplier: null, name: null, exchange: null })));
  assert.doesNotMatch(sub, /\bsh\b|contract/,
    'guessing a unit for an unknown kind is the same invention, quieter: '
    + JSON.stringify(sub));
  assert.match(sub, /^5\b/, 'the quantity itself is known and still leads');
});

// ================================================================= the BAND
test('a band withholds its DAY subtotal for the prior close, not for the mark', () => {
  const p = newPage();
  // every feed answering, one position opened today · Acc.day() returns null
  // purely on withoutPrevClose, and every mark under this band is live
  const html = p.call('band', group({ holdings: 3, withoutPrevClose: 1,
                                      dayChange: null, dayChangePct: null }));
  const withheld = html.match(/<span class="muted" title="([^"]*)">·<\/span>/g) || [];
  assert.equal(withheld.length, 1, 'only the day subtotal is missing here');
  const why = titleOf(withheld[0]);
  assert.doesNotMatch(why, /live mark/,
    'the marks are live · this is the false reason that shipped: ' + JSON.stringify(why));
  assert.match(why, /prior close/);
  assert.match(why, /^1 position/, 'and it says how many');
});

test('a band withholding for a FABRICATED price says so instead of counting zero', () => {
  const p = newPage();
  // whole() is unpriced == 0 && fabricated == 0 && expired == 0. With only
  // `fabricated` set, the band used to report "0 holding(s) could not be
  // valued" · the drawHeader bug, one scale down.
  const html = p.call('band', group({ holdings: 2, fabricated: 1,
                                      marketValue: null, unrealized: null, unrealizedPct: null }));
  for (const w of html.match(/title="([^"]*)"/g) || []) {
    assert.doesNotMatch(w, /\b0 /, 'a clause that did not fire must not be named: ' + w);
  }
  assert.match(html, /1 with no observed price/);
});

test('a band keeps every figure on ONE line · no percentage beside a euro figure', () => {
  const p = newPage();
  // MEASURED, and this guards the measurement. At 390px the band is 328px and
  // flex-wrap is nowrap. With '+€110.00 · +0.74%' and '+€1,876.54 · +23.45%'
  // in it the fixed content wanted ~374px: both P&L spans wrapped internally
  // to 37.5px against an 18.75px line box, and the flexing .muted that carries
  // the holding count was squeezed to width 0.0 with a scrollWidth of 6.5 ·
  // erased, not truncated, so no ellipsis could render either.
  const html = p.call('band', group({ holdings: 3 }));
  assert.doesNotMatch(html, /%/,
    'a percentage on the band is what erased the holding count at 390px');
  assert.match(html, /<span class="muted">3<\/span>/,
    'the count is the band\'s flexing spacer and must still be in it');
  assert.equal((html.match(/€/g) || []).length, 3,
    'exactly three figures: the subtotal, the day and the unrealized');
});

// ============================================== the tiles are on the GRID
test('every holding tile is drawn INSIDE a .prod-grid', () => {
  const p = newPage();
  const el = p.el('holdings');
  p.call('drawHoldings', {
    holdings: [holding({ symbol: 'AAPL' }), holding({ symbol: 'MSFT' }),
               holding({ symbol: 'BTC', kind: 'crypto' })],
    groups: [group({ kind: 'equity', label: 'Stocks', holdings: 2 }),
             group({ kind: 'crypto', label: 'Crypto', holdings: 1 })],
  });

  const tiles = divAncestry(el.innerHTML, 'prod');
  assert.equal(tiles.length, 3, 'one tile per holding');
  for (const ancestors of tiles) {
    assert.ok(ancestors.some(a => a.includes('prod-grid')),
      'a holding drawn outside .prod-grid stacks full width · that is the list '
      + 'shape this work removed, and `/class="prod-grid"/` still matches the '
      + 'skeleton and the watchlist while it happens');
  }
});

test('the open tile is still a tile on the grid, not a row beside it', () => {
  const p = newPage();
  p.run('open = "AAPL"');
  const el = p.el('holdings');
  p.call('drawHoldings', {
    holdings: [holding({ symbol: 'AAPL' }), holding({ symbol: 'MSFT' })],
    groups: [group({ holdings: 2 })],
  });
  const html = el.innerHTML;
  assert.match(html, /class="prod expandable open"/);
  // the expansion is INSIDE the tile · .prod-x is the bank's shape for that
  const x = divAncestry(html, 'prod-x');
  assert.equal(x.length, 1);
  assert.ok(x[0].some(a => a.includes('prod') && a.includes('open')),
    'the expansion belongs to the tile that was tapped');
  // ...and the order slider is in it, which is what the row span is for
  assert.match(html, /type="range"[^>]*min="10"[^>]*max="250"/);
});

test('drawHoldings draws nothing for a group whose rows are all elsewhere', () => {
  const p = newPage();
  const el = p.el('holdings');
  p.call('drawHoldings', {
    holdings: [holding({ symbol: 'BTC', kind: 'crypto' })],
    groups: [group({ kind: 'equity', label: 'Stocks', holdings: 1 }),
             group({ kind: 'crypto', label: 'Crypto', holdings: 1 })],
  });
  assert.doesNotMatch(el.innerHTML, /Stocks/,
    'an empty band is a report about a database');
  assert.equal(divAncestry(el.innerHTML, 'prod').length, 1);
});

// ================================================================== header
test('the headline P&L blames the condition that actually fired', () => {
  const p = newPage();
  // one expired contract, every feed answering: whole() is false, so BOTH the
  // total and Unrealized are withheld · and neither is about a live mark
  p.call('drawHeader', { aggregate: aggregate({
    marketValue: null, unrealized: null, unrealizedPct: null,
    holdings: 2, expired: 1, dayChange: null, dayChangePct: null, withoutPrevClose: 1 }) });

  const pv = titleOf(p.el('pv').innerHTML);
  assert.match(pv, /1 expired/);
  assert.doesNotMatch(pv, /0 /, 'a clause that did not fire must not be counted');

  const unrealized = titleOf(p.el('pnl').innerHTML);
  assert.ok(unrealized, 'a withheld Unrealized must say why');
  assert.doesNotMatch(unrealized, /needs a live mark/,
    'every feed answered · the cause is an expired contract: ' + JSON.stringify(unrealized));
  assert.match(unrealized, /1 expired/);
});
