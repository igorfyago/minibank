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
 * was answering, a subtotal that blamed a live mark for a missing prior close,
 * and a holding that was drawn outside the container it was supposed to be laid
 * out in while a regex over the source still matched somewhere else in the file.
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
 * A fresh page per test · `open`, `snap`, `sortCol` and `lastMsg` are module
 * state on the real page and a test that shared them would depend on its
 * neighbours.
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
    // The page persists the sort preference asynchronously, and this fake
    // browser had document and location but no localStorage, so every test
    // passed and then an after-test rejection fired on the missing global.
    // A harness that fakes a browser owes the page the WHOLE surface the page
    // touches; remembering nothing (getItem null) is the honest default.
    localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
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

const unesc = s => s.replace(/&#39;/g, "'").replace(/&quot;/g, '"').replace(/&amp;/g, '&');

/** The `title` of the one .muted the given html withholds through. */
function titleOf(html) {
  const m = html.match(/title="([^"]*)"/);
  return m ? unesc(m[1]) : null;
}

/** THE THIRD COLUMN of a row · today's P&L, the one the reference gives its
 *  own heading. `.tx-main tx-right` is the only right-aligned column, so this
 *  cannot silently start reading a different cell. */
function dayCell(rowHtml) {
  const i = rowHtml.indexOf('class="tx-main tx-right"');
  assert.ok(i > -1, 'a holdings row must carry a right-aligned P&L column');
  return rowHtml.slice(i);
}

/** THE SECOND COLUMN · value over quantity. */
function valueCell(rowHtml) {
  const cells = rowHtml.split('class="tx-main"');
  assert.ok(cells.length >= 3, 'a holdings row must carry an instrument and a value column');
  return cells[2];
}

/**
 * DIV NESTING, actually walked · this is the whole point of the file.
 * Returns, for every <div> carrying `needle` in its class list, the class
 * lists of its div ancestors, outermost first. A row moved inside a grid comes
 * back with a 'prod-grid' in its ancestry and no regex can hide it.
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
  const html = p.call('holdingRow', holding({ prevClose: null, dayChange: null, dayChangePct: null }));

  const why = titleOf(dayCell(html));
  assert.ok(why, 'a withheld Day must say why');
  assert.doesNotMatch(why, /live mark/,
    'the mark IS live · this is the exact false reason that shipped: ' + JSON.stringify(why));
  assert.match(why, /prior close/,
    'the reason must name the prior close, which is what is actually missing');
  // and the column beside it is still showing the live value it was priced at
  assert.match(valueCell(html), /class="prod-val">€975\.00</,
    'the same row values the holding live · that is why blaming the feed is a lie');
});

test('the four withheld-Day conditions produce four different reasons', () => {
  const p = newPage();
  const why = h => titleOf(dayCell(p.call('holdingRow', h)));

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
  p.run('open = "AAPL"');
  const html = p.call('holdingRow', h);
  const titles = (html.match(/title="([^"]*)"/g) || []).map(t => unesc(t));
  assert.ok(titles.length, 'an expired contract withholds several figures and explains each');
  for (const why of titles) {
    assert.match(why, /expired/,
      'an expired contract explains itself as expired, not as a feed outage: ' + JSON.stringify(why));
  }
});

// ================================================================== the UNIT
// unitFor read `kind === 'crypto' ? '' : ' sh'`, so everything that was not
// crypto was counted in shares. A contract is not a share · it is a claim on
// `multiplier` of them, and the tile says so itself two rows down in the
// 'Contract size' cell. The sub-line understated an option position by 100x.

/** The quantity sub-line of a ROW · the first bare .prod-sub, which is the
 *  Value/Shares cell's second line. The Instrument cell's name line is
 *  'prod-sub tick-main' and does not match. Ported from the tile version,
 *  where the same tests guarded the same unit semantics. */
function subOf(html) {
  const m = html.match(/<div class="prod-sub">([\s\S]*?)<\/div>/);
  return m ? m[1] : null;
}

test('an option position is counted in CONTRACTS, not shares', () => {
  const p = newPage();
  const opt = holding({ symbol: 'AAPL260821C250', kind: 'option', qty: '2',
                        name: "AAPL Aug 21 '26 250 Call",
                        multiplier: '100', expiresOn: '2026-08-21' });
  const sub = subOf(p.call('holdingRow', opt));

  assert.ok(sub, 'the row must carry a quantity sub-line');
  assert.doesNotMatch(sub, /\bsh\b/,
    'this is the defect: two contracts control 200 shares and the row said '
    + '"2 sh" · ' + JSON.stringify(sub));
  assert.match(sub, /^2 contracts\b/,
    'the quantity leads the sub-line and names what it counts: ' + JSON.stringify(sub));
  // and the expansion that explains the multiplier is still the same fact
  p.run('open = "AAPL260821C250"');
  assert.match(p.call('holdingRow', opt), /Contract size<\/span><b[^>]*>100 × underlying/,
    'the unit on the face and the contract size in the expansion are one claim');
});

test('one contract is singular · and a share is still a share', () => {
  const p = newPage();
  const sub = h => subOf(p.call('holdingRow', h));

  // \b after 'contract' is the assertion · 'contracts' has no boundary there
  assert.match(sub(holding({ kind: 'option', qty: '1', multiplier: '100' })),
    /^1 contract\b/, 'a lone contract does not take the plural');
  assert.match(sub(holding({ kind: 'equity', qty: '5' })), /^5 sh\b/,
    'equities are unchanged · this fix is not a licence to restyle them');
  assert.match(sub(holding({ symbol: 'BTC', kind: 'crypto', qty: '0.5', multiplier: '1' })),
    /^0\.5$/, 'crypto carries no unit, and in the table the name lives on the Instrument column, not here');
});

test('an UNCATALOGUED holding claims no unit at all', () => {
  const p = newPage();
  // no registry entry means no multiplier and no kind · the row that already
  // has nothing true to say about its size used to say "shares" anyway
  const sub = subOf(p.call('holdingRow',
    holding({ kind: null, multiplier: null, name: null, exchange: null })));
  assert.doesNotMatch(sub, /\bsh\b|contract/,
    'guessing a unit for an unknown kind is the same invention, quieter: '
    + JSON.stringify(sub));
  assert.match(sub, /^5\b/, 'the quantity itself is known and still leads');
});

// ================================== NEITHER A ZERO NOR A DENOMINATOR-LESS PCT
test('a day move with no honest percentage prints no percentage', () => {
  const p = newPage();
  const html = dayCell(p.call('holdingRow', holding({ dayChangePct: null })));
  assert.match(html, /\+€25\.00/, 'the euro figure is real and is shown');
  assert.doesNotMatch(html, /%/,
    "'0.00%' is a claim that it did not move, made about something that moved");
});

test('both P&L lines carry the sign and the loss colour', () => {
  const p = newPage();
  const html = dayCell(p.call('holdingRow', holding({ dayChange: '-64.34', dayChangePct: '-0.04' })));
  assert.match(html, /<span class="amount-neg">−€64\.34<\/span>/, 'U+2212, never a hyphen');
  assert.match(html, /<span class="amount-neg">−0\.04%<\/span>/);
});

test('the colour is on a span, never on the line · .prod-sub would win and dim it', () => {
  const p = newPage();
  const html = dayCell(p.call('holdingRow', holding({ dayChange: '25.00', dayChangePct: '2.63' })));
  // .prod-sub sets a dim colour and is declared AFTER .amount-pos in this
  // stylesheet exactly as it is in the bank's, so `class="prod-sub amount-pos"`
  // renders dim and the gain silently loses its green.
  assert.doesNotMatch(html, /class="prod-(sub|val) amount-(pos|neg)"/,
    'the sign colour must be on a nested span, not merged into the line\'s class');
  assert.match(html, /class="prod-sub"><span class="amount-pos">/);
});

// ================================================= ONE FLAT LIST, NO BANDS
/* Global Trader does not band this screen by instrument type · it sorts it.
 * The tile version drew one .prod-grid per asset class, which left a customer
 * holding one stock and one crypto with two grids of one tile each and a
 * subtotal of one floating at the far right of a half-empty row. */

test('every holding is one row in ONE list, whatever its asset class', () => {
  const p = newPage();
  const el = p.el('holdings');
  p.call('drawHoldings', {
    holdings: [holding({ symbol: 'AAPL' }), holding({ symbol: 'MSFT' }),
               holding({ symbol: 'BTC', kind: 'crypto' })],
    groups: [group({ kind: 'equity', label: 'Stocks', holdings: 2 }),
             group({ kind: 'crypto', label: 'Crypto', holdings: 1 })],
  });
  const html = el.innerHTML;

  // one header row plus one row per holding, and nothing nested inside
  // anything else
  const rows = divAncestry(html, 'stat');
  assert.equal(rows.length, 4, 'a header row and three holdings');
  for (const ancestors of rows) {
    assert.deepEqual(ancestors, [], 'the list is flat · a row sits in no container');
  }
  for (const sym of ['AAPL', 'MSFT', 'BTC']) assert.ok(html.includes(sym), sym + ' must be drawn');
});

test('the asset-class bands and their subtotals are gone, not merely unused', () => {
  const p = newPage();
  const el = p.el('holdings');
  p.call('drawHoldings', {
    holdings: [holding({ symbol: 'AAPL' }), holding({ symbol: 'BTC', kind: 'crypto' })],
    groups: [group({ kind: 'equity', label: 'Stocks', holdings: 1 }),
             group({ kind: 'crypto', label: 'Crypto', holdings: 1 })],
  });
  const html = el.innerHTML;
  assert.doesNotMatch(html, /Stocks|Crypto/,
    'a band label on this screen is a report about a database, not a portfolio');
  assert.doesNotMatch(html, /tx-day|txg-head/,
    'the band classes must be gone from the output as well as the stylesheet');
  assert.equal(p.run('typeof band'), 'undefined', 'the band renderer is retired, not orphaned');
  assert.equal(p.run('typeof bandPnl'), 'undefined');
  assert.equal(p.run('typeof groupWhy'), 'undefined');
  assert.equal(p.run('typeof groupDayWhy'), 'undefined');
  assert.equal(p.run('typeof holdingTile'), 'undefined', 'and so is the tile');
});

test('holdings are not drawn in a grid · a grid of one is the shape that was wrong', () => {
  const p = newPage();
  const el = p.el('holdings');
  p.call('drawHoldings', {
    holdings: [holding({ symbol: 'AAPL' })],
    groups: [group({ holdings: 1 })],
  });
  assert.doesNotMatch(el.innerHTML, /prod-grid/,
    'the Watchlist below is still a shelf of tiles; the holdings are a list');
});

test('a holding with no group still gets a row', () => {
  const p = newPage();
  const el = p.el('holdings');
  // p.groups is not read at all any more · a row cannot go missing because the
  // server did or did not band it
  p.call('drawHoldings', { holdings: [holding({ symbol: 'BTC', kind: 'crypto' })] });
  assert.equal(divAncestry(el.innerHTML, 'stat').length, 2, 'the header row and one holding');
  assert.ok(el.innerHTML.includes('BTC'));
});

test('an empty portfolio says so, and draws no header row to sort', () => {
  const p = newPage();
  const el = p.el('holdings');
  p.call('drawHoldings', { holdings: [], groups: [] });
  assert.match(el.innerHTML, /Nothing invested yet/);
  assert.doesNotMatch(el.innerHTML, /Instrument/, 'there is nothing to sort');
});

// ============================================ the ROW, and what it carries
test('the row is three columns of two lines, in the reference\'s order', () => {
  const p = newPage();
  const html = p.call('holdingRow', holding({
    symbol: 'AAPL', exchange: 'NASDAQ.NMS', name: 'Apple Inc.',
    qty: '39.7141', value: '10659.00', dayChange: '-64.34', dayChangePct: '-0.04' }));

  assert.match(html, /AAPL <span class="muted">NASDAQ\.NMS<\/span>/,
    'the ticker leads, the exchange follows it small and dim');
  assert.match(html, /class="prod-sub tick-main">Apple Inc\.</,
    'the company name is the second line, and it elides rather than wrapping');
  assert.match(valueCell(html), /€10,659\.00/);
  assert.match(valueCell(html), /39\.7141 sh/);
  assert.match(dayCell(html), /−€64\.34/);
  assert.match(dayCell(html), /−0\.04%/);
});

test('a price disclosure sits beside the quantity, where it cannot be elided away', () => {
  const p = newPage();
  const html = p.call('holdingRow', holding({ priceSource: 'cached' }));
  // the company-name line is nowrap + ellipsis; a disclosure put there would
  // silently vanish on a narrow phone. The quantity line is free to wrap.
  assert.match(valueCell(html), /last known price/);
  assert.doesNotMatch(html.slice(0, html.indexOf('class="tx-main"', 20)), /last known price/);
});

test('an expired holding says so on the row, not only in its detail', () => {
  const p = newPage();
  const html = p.call('holdingRow', holding({ priceSource: 'expired', price: null, value: null }));
  assert.match(valueCell(html), /expired/);
});

// ================================================= the DETAIL under the row
test('tapping a row opens a detail block underneath it, outside the row', () => {
  const p = newPage();
  p.run('open = "AAPL"');
  const el = p.el('holdings');
  p.call('drawHoldings', {
    holdings: [holding({ symbol: 'AAPL' }), holding({ symbol: 'MSFT' })],
  });
  const html = el.innerHTML;

  const x = divAncestry(html, 'prod-x');
  assert.equal(x.length, 1, 'exactly one row is open');
  assert.deepEqual(x[0], [],
    'the detail is a sibling of the row · a .stat is a flex row and cannot hold a block of figures');
  // it belongs to the row that was tapped: it follows AAPL and precedes MSFT
  assert.ok(html.indexOf('prod-x') > html.indexOf('AAPL'));
  assert.ok(html.indexOf('prod-x') < html.indexOf('MSFT'));
  // ...and the order ticket is in it
  assert.match(html, /type="range"[^>]*min="10"[^>]*max="250"/);
  assert.match(html, /placeOrder\('AAPL','buy'\)/);
});

test('the detail carries unrealized in BOTH units, and the things a row has no room for', () => {
  const p = newPage();
  const html = p.call('expansion', holding({}));
  assert.match(html, /<span>Unrealized<\/span><b[^>]*>\+€100\.00 · \+11\.43%<\/b>/,
    'the euro figure and its percentage are read together or not at all');
  for (const label of ['Avg cost', 'Last', 'Cost basis', 'Realized', 'Day measured over']) {
    assert.ok(html.includes(`<span>${label}</span>`), `the detail must carry ${label}`);
  }
});

test('the detail does not restate Today · the row above it is still on screen', () => {
  const p = newPage();
  const html = p.call('expansion', holding({}));
  assert.doesNotMatch(html, /<span>Day<\/span>/);
});

test('a closed row draws no detail at all', () => {
  const p = newPage();
  const html = p.call('holdingRow', holding({}));
  assert.doesNotMatch(html, /prod-x|type="range"/);
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

  const pnl = p.el('pnl').innerHTML;
  const unrealizedRow = pnl.slice(pnl.indexOf('<span>Unrealized</span>'));
  const why = titleOf(unrealizedRow);
  assert.ok(why, 'a withheld Unrealized must say why');
  assert.doesNotMatch(why, /needs a live mark/,
    'every feed answered · the cause is an expired contract: ' + JSON.stringify(why));
  assert.match(why, /1 expired/);
});

test('the withheld day names the prior close, not the feed', () => {
  const p = newPage();
  p.call('drawHeader', { aggregate: aggregate({
    holdings: 2, dayChange: null, dayChangePct: null, withoutPrevClose: 1 }) });
  const pnl = p.el('pnl').innerHTML;
  const why = titleOf(pnl.slice(pnl.indexOf('<span>Today</span>')));
  assert.match(why, /prior close/);
  assert.doesNotMatch(why, /live mark/);
});

test('Today is a labelled row carrying both units, as the reference lays it out', () => {
  const p = newPage();
  p.call('drawHeader', { aggregate: aggregate({ dayChange: '1758.00', dayChangePct: '0.83' }) });
  const pnl = p.el('pnl').innerHTML;
  assert.match(pnl, /<span>Today<\/span><b class="amount-pos">\+€1,758\.00 · \+0\.83%<\/b>/);
  // and the sub-line under the balance is no longer where the day lives
  assert.doesNotMatch(p.el('day').innerHTML, /1,758/);
});

test('Unrealized is NOT relabelled "Total" while a Realized row sits under it', () => {
  const p = newPage();
  p.call('drawHeader', { aggregate: aggregate({}) });
  const pnl = p.el('pnl').innerHTML;
  assert.ok(pnl.includes('<span>Unrealized</span>'));
  assert.ok(pnl.includes('<span>Realized</span>'));
  assert.doesNotMatch(pnl, /<span>Total<\/span>/,
    'a reader who adds a row labelled Total to the row beneath it is doing '
    + 'arithmetic the label already claimed to have done');
});

test('the balance sub-line still qualifies a total marked at stale prices', () => {
  const p = newPage();
  p.call('drawHeader', { aggregate: aggregate({ holdings: 3, stale: 3 }) });
  assert.match(p.el('day').innerHTML, /at last known prices/);
});
