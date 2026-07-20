/**
 * THE INSTRUMENT SCREEN'S ARITHMETIC, RUN FOR REAL.
 *
 * Run with:  node --test src/test/js
 *
 * The ticket's numbers are the point of the page: what an order is estimated
 * to cost, what a premium multiplies out to, and what the screen refuses to
 * claim when a price does not exist. Those rules live in the pure section at
 * the top of instrument.html's <script> · no DOM, no network · so this file
 * lifts the page's own script into a vm and calls the real functions. No
 * spelling of any line can make these pass; only the right arithmetic can.
 *
 * The honesty cases matter most here and each one has a test: an unpriced
 * instrument produces NO estimate (null, never €0.00), a fractional contract
 * count produces NO premium (the UI mirror of Broker.place's gate), a missing
 * prior close produces NO day figure, and the stock chart offers ONLY the
 * ranges its endpoint can truthfully serve.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const PAGE_FILE = path.join(__dirname, '..', '..', 'main', 'resources', 'web-broker', 'instrument.html');
const SRC = fs.readFileSync(PAGE_FILE, 'utf8');

/**
 * A fresh page per test. The document is the smallest thing the script will
 * accept: getElementById hands back a recording stub. localStorage is stubbed
 * EMPTY so ensureToken resolves quietly · fetch rejects, api() survives that
 * by design, and construction stays silent.
 */
function newPage(search) {
  const els = new Map();
  const el = id => {
    if (!els.has(id)) els.set(id, { id, innerHTML: '', textContent: '', className: '',
                                    href: '', style: {}, classList: { add() {}, remove() {} } });
    return els.get(id);
  };
  const ctx = {
    console,
    document: {
      getElementById: el,
      querySelector: () => null,
      querySelectorAll: () => [],
      title: '',
      activeElement: null,
    },
    location: { search: search || '?customer=10', hostname: 'localhost', protocol: 'http:' },
    history: {},
    navigator: {},
    URLSearchParams,
    setTimeout: () => 0,
    crypto: { randomUUID: () => 'test-uuid' },
    localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
    fetch: () => Promise.reject(new Error('no network in a unit test')),
  };
  ctx.window = ctx;
  ctx.globalThis = ctx;
  vm.createContext(ctx);

  const m = SRC.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(m, 'instrument.html must carry exactly one inline <script>');
  vm.runInContext(m[1], ctx);

  return {
    ctx, el,
    run: expr => vm.runInContext(expr, ctx),
    // results are JSON-normalised: objects born inside the vm carry the vm's
    // own prototypes, and deepStrictEqual rightly refuses to call a
    // cross-realm array equal to a host one. The tests compare VALUES.
    call: (fn, ...args) => {
      ctx.__args = args;
      const out = vm.runInContext(`${fn}(...__args)`, ctx);
      return out === undefined ? undefined : JSON.parse(JSON.stringify(out));
    },
  };
}

const page = newPage();
const call = (fn, ...args) => page.call(fn, ...args);

// ========================================================= the stock ticket

test('EUR-sized order: the estimate carries the cost and the units it buys', () => {
  const e = call('estimate', 'eur', '50', '332.10');
  assert.equal(e.eur, 50);
  assert.ok(Math.abs(e.units - 50 / 332.10) < 1e-12);
});

test('unit-sized order: the estimate is units times the mark', () => {
  const e = call('estimate', 'units', '2', '10.50');
  assert.equal(e.eur, 21);
  assert.equal(e.units, 2);
});

test('an UNPRICED instrument produces NO estimate · null, never €0.00', () => {
  // /api/instruments fails soft to zero, so 0 and null are the same fact:
  // there is no mark. An estimate of €0.00 would be a fabricated price.
  assert.equal(call('estimate', 'eur', '50', null), null);
  assert.equal(call('estimate', 'eur', '50', '0'), null);
  assert.equal(call('estimate', 'units', '2', undefined), null);
});

test('a non-positive or unparseable amount produces no estimate either', () => {
  assert.equal(call('estimate', 'eur', '0', '100'), null);
  assert.equal(call('estimate', 'eur', '-5', '100'), null);
  assert.equal(call('estimate', 'eur', 'abc', '100'), null);
  assert.equal(call('estimate', 'eur', '', '100'), null);
});

// ======================================================== the option ticket

test('whole contracts pass the gate; fractions, zero and junk do not', () => {
  assert.equal(call('wholeContracts', '2'), 2);
  assert.equal(call('wholeContracts', '1'), 1);
  assert.equal(call('wholeContracts', '0.37'), null);   // the gate the server also holds
  assert.equal(call('wholeContracts', '0'), null);
  assert.equal(call('wholeContracts', '-1'), null);
  assert.equal(call('wholeContracts', 'abc'), null);
  assert.equal(call('wholeContracts', ''), null);
});

test('estimated premium = qty × price × 100 · the stated contract size', () => {
  assert.equal(call('optionPremium', '2', 0.06), 12);
  assert.equal(call('optionPremium', '1', 3.1), 310);
});

test('a fractional qty or an absent price yields NO premium estimate', () => {
  assert.equal(call('optionPremium', '0.5', 0.06), null);
  assert.equal(call('optionPremium', '2', null), null);
});

test('the estimate stands on the side\'s own price, and says which', () => {
  const row = { bid: '3.0', ask: '3.2', last: '3.1' };
  assert.deepEqual(call('premiumBasis', 'buy', row), { px: 3.2, label: 'at ask' });
  assert.deepEqual(call('premiumBasis', 'sell', row), { px: 3.0, label: 'at bid' });
});

test('with the side\'s price absent the LAST stands in, labelled as the last', () => {
  // a stale print offered as a quote would be a lie · the label is the fix
  const row = { bid: null, ask: null, last: '1.9' };
  assert.deepEqual(call('premiumBasis', 'buy', row), { px: 1.9, label: 'at last' });
  assert.deepEqual(call('premiumBasis', 'sell', row), { px: 1.9, label: 'at last' });
});

test('no bid, no ask, no last: no basis, so the ticket shows no estimate', () => {
  assert.equal(call('premiumBasis', 'buy', { bid: null, ask: null, last: null }), null);
  assert.equal(call('premiumBasis', 'buy', null), null);
});

// =============================================================== the ladder

test('the ladder merges calls and puts by strike, ascending', () => {
  const rows = call('mergeChain',
    [{ strike: '710.0', contractSymbol: 'C710' }, { strike: '700.0', contractSymbol: 'C700' }],
    [{ strike: '705.0', contractSymbol: 'P705' }, { strike: '700.0', contractSymbol: 'P700' }]);
  assert.deepEqual(rows.map(r => r.strike), [700, 705, 710]);
  assert.equal(rows[0].call.contractSymbol, 'C700');
  assert.equal(rows[0].put.contractSymbol, 'P700');
});

test('a strike quoted on one side only still gets its row, the silent side a gap', () => {
  const rows = call('mergeChain', [{ strike: '710.0', contractSymbol: 'C710' }],
                                  [{ strike: '705.0', contractSymbol: 'P705' }]);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].call, null);       // 705 has no call · a gap, not a zero
  assert.equal(rows[1].put, null);
});

test('the phone drops LAST from each side and keeps bid/ask · the crossing prices', () => {
  assert.deepEqual(call('chainCols', true), ['bid', 'ask']);
  assert.deepEqual(call('chainCols', false), ['bid', 'ask', 'last']);
});

// ================================================================ the chart

test('chart geometry: min-max scaled, first and last points span the width', () => {
  const g = call('chartPath', [[0, 10], [1, 20], [2, 15]], 400, 118);
  assert.equal(g.min, 10);
  assert.equal(g.max, 20);
  assert.equal(g.up, true);               // 15 >= 10 · the series closed above its open
  const pts = g.pts.split(' ');
  assert.equal(pts.length, 3);
  assert.ok(pts[0].startsWith('0.0,'));
  assert.ok(pts[2].startsWith('400.0,'));
  // min maps near the bottom (H-4), max near the top (4) · the bank's own
  // 4px breathing room at each edge
  assert.equal(pts[0].split(',')[1], '114.0');
  assert.equal(pts[1].split(',')[1], '4.0');
});

test('fewer than two points is NO chart, not a fabricated line', () => {
  assert.equal(call('chartPath', [[0, 10]], 400, 118), null);
  assert.equal(call('chartPath', [], 400, 118), null);
  assert.equal(call('chartPath', null, 400, 118), null);
});

test('a flat series still draws · the zero span divides by 1, not by 0', () => {
  const g = call('chartPath', [[0, 5], [1, 5]], 400, 118);
  assert.ok(g.pts.length > 0);
  assert.equal(g.up, true);
});

test('sliceDays keeps only the tail the range names', () => {
  const day = 86400000;
  const pts = [[0, 1], [10 * day, 2], [29 * day, 3], [30 * day, 4]];
  assert.deepEqual(call('sliceDays', pts, 7).map(p => p[1]), [3, 4]);
  assert.deepEqual(call('sliceDays', pts, 31).map(p => p[1]), [1, 2, 3, 4]);
});

test('the ranges offered are the ranges the endpoints can honestly serve', () => {
  // underlying mode rides /api/options/chart, which serves the venue's own
  // four ranges · stock mode rides /api/prices/history, 30 days of daily
  // closes, so 1W and 1M are honest slices and intraday or 3M would be an
  // invention. The missing buttons ARE the honesty.
  assert.deepEqual(call('rangesFor', 'underlying').map(r => r.key), ['1d', '1w', '1mo', '3mo']);
  assert.deepEqual(call('rangesFor', 'symbol').map(r => r.key), ['1w', '1mo']);
});

// =============================================================== the header

test('the day needs a mark AND a prior close · anything less is no day, not 0.00', () => {
  const d = call('dayParts', '332.10', '333.74');
  assert.ok(Math.abs(d.change - -1.64) < 1e-9);
  assert.ok(Math.abs(d.pct - (-1.64 / 333.74 * 100)) < 1e-9);
  assert.equal(call('dayParts', '332.10', null), null);
  assert.equal(call('dayParts', null, '333.74'), null);
  assert.equal(call('dayParts', '332.10', '0'), null);   // no denominator, no percent
});

test('signed money: U+2212 minus, and exactly flat carries NO sign', () => {
  assert.equal(call('fmtSignedMoney', '-1.5', 'USD'), '−$1.50');
  assert.equal(call('fmtSignedMoney', '1.5', 'EUR'), '+€1.50');
  assert.equal(call('fmtSignedMoney', '0', 'EUR'), '€0.00');
  assert.equal(call('fmtSignedMoney', null, 'EUR'), null);
});

test('money renders in the currency the payload stated, and claims none unstated', () => {
  assert.equal(call('fmtMoney', '746.81', 'USD'), '$746.81');
  assert.equal(call('fmtMoney', '746.81', 'EUR'), '€746.81');
  // an unstated currency gets no symbol · a bare figure claims less than a
  // wrong sign would
  assert.equal(call('fmtMoney', '3.2', null), '3.20');
  assert.equal(call('fmtMoney', null, 'USD'), null);
});

test('a zero mark from the soft-to-zero endpoints means NO price, not free', () => {
  assert.equal(call('markUnavailable', '0'), true);
  assert.equal(call('markUnavailable', null), true);
  assert.equal(call('markUnavailable', '12.5'), false);
});

test('percent: sign on movement only, zero takes none', () => {
  assert.equal(call('fmtPct', '-2.7386541'), '−2.74%');
  assert.equal(call('fmtPct', '0.14'), '+0.14%');
  assert.equal(call('fmtPct', '0'), '0.00%');
  assert.equal(call('fmtPct', null), null);
});

test('an expiry chip is the ISO day, the spelling the portfolio already uses', () => {
  assert.equal(call('isoDay', 1784505600), '2026-07-20');
});

test('mark age spells seconds under 90 and minutes above, like the bank tile', () => {
  assert.equal(call('ageWord', 45), '45s');
  assert.equal(call('ageWord', 600), '10 min');
  assert.equal(call('ageWord', null), null);
  assert.equal(call('ageWord', -1), null);
});
