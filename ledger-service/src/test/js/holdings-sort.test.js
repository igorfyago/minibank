/**
 * THE SORT, RUN AS ARITHMETIC.
 *
 * Run with:  node --test src/test/js
 *
 * Sorting a portfolio screen is presentation, so it is written as pure
 * functions over the holdings array and tested without a browser · the same
 * doctrine as web/lib.js. What is asserted here is the three properties that a
 * naive `arr.sort((a,b) => a.value - b.value)` gets wrong, and each of them is
 * a lie the rest of this page already refuses to tell:
 *
 *   an unpriced holding is not the cheapest thing you own
 *   two level holdings do not swap places every time the page redraws
 *   a descending sort is not an ascending sort read backwards, because the
 *   missing rows must stay at the bottom in both
 *
 * The functions are lifted out of portfolio.html's own <script> and run in a
 * vm, so nothing here can pass against a copy of the logic that has drifted
 * from the page.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const PORT_FILE = path.join(__dirname, '..', '..', 'main', 'resources', 'web-broker', 'portfolio.html');
const SRC = fs.readFileSync(PORT_FILE, 'utf8');

/** The page's script, in a context with just enough document to construct. */
function newPage() {
  const els = new Map();
  const el = id => {
    if (!els.has(id)) els.set(id, { id, innerHTML: '', textContent: '', className: '', href: '' });
    return els.get(id);
  };
  const ctx = {
    console,
    document: { getElementById: el, querySelector: () => null, querySelectorAll: () => [] },
    location: { search: '?customer=10', hostname: 'localhost', protocol: 'http:' },
    history: {}, navigator: {}, URLSearchParams,
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
    call: (fn, ...args) => { ctx.__args = args; return vm.runInContext(`${fn}(...__args)`, ctx); },
  };
}

/** Money crosses the wire as STRINGS, absent as null · so does everything the
 *  comparators read. */
const h = (symbol, value, dayChange) => ({ symbol, value, dayChange, name: symbol + ' Inc.' });

const syms = rows => rows.map(r => r.symbol);
const sort = (p, rows, col, dir) => syms(p.call('sortRows', rows, col, dir));

// ===================================================== each column, both ways

test('the instrument column sorts by ticker, ascending and descending', () => {
  const p = newPage();
  const rows = [h('META', '2', '2'), h('AAPL', '1', '1'), h('U100', '3', '3')];
  assert.deepEqual(sort(p, rows, 'instrument', 'asc'), ['AAPL', 'META', 'U100']);
  assert.deepEqual(sort(p, rows, 'instrument', 'desc'), ['U100', 'META', 'AAPL']);
});

test('the value column sorts by market value, ascending and descending', () => {
  const p = newPage();
  // deliberately NOT in ticker order, so a comparator that fell through to the
  // symbol would be caught
  const rows = [h('AAPL', '10659.00', '1'), h('META', '12277.00', '2'), h('U100', '86085.00', '3')];
  assert.deepEqual(sort(p, rows, 'value', 'asc'), ['AAPL', 'META', 'U100']);
  assert.deepEqual(sort(p, rows, 'value', 'desc'), ['U100', 'META', 'AAPL']);
});

test('the value column compares NUMBERS, not the strings they arrived as', () => {
  const p = newPage();
  // '9.00' > '10659.00' lexicographically, and a string compare would put the
  // nine-euro position at the top of a descending sort by value
  const rows = [h('AAPL', '10659.00', '1'), h('NINE', '9.00', '2')];
  assert.deepEqual(sort(p, rows, 'value', 'desc'), ['AAPL', 'NINE']);
});

test("the today's P&L column sorts by the day figure, ascending and descending", () => {
  const p = newPage();
  const rows = [h('AAPL', '1', '-64.34'), h('META', '2', '68.96'), h('U100', '3', '1470.00')];
  assert.deepEqual(sort(p, rows, 'day', 'asc'), ['AAPL', 'META', 'U100']);
  assert.deepEqual(sort(p, rows, 'day', 'desc'), ['U100', 'META', 'AAPL']);
});

// ============================================================== NULLS LAST
/* An unpriced holding has no market value. Sorted as a number it becomes 0 and
 * leads an ascending sort as the cheapest position on the screen · a
 * fabricated zero, dressed as an ordering. */

test('an unpriced holding sorts LAST ascending · it is not the cheapest', () => {
  const p = newPage();
  const rows = [h('AAPL', '10659.00', '1'), h('DARK', null, null), h('META', '12277.00', '2')];
  assert.deepEqual(sort(p, rows, 'value', 'asc'), ['AAPL', 'META', 'DARK']);
});

test('an unpriced holding sorts LAST descending too · the direction does not move it', () => {
  const p = newPage();
  const rows = [h('AAPL', '10659.00', '1'), h('DARK', null, null), h('META', '12277.00', '2')];
  assert.deepEqual(sort(p, rows, 'value', 'desc'), ['META', 'AAPL', 'DARK']);
});

test('a holding with no day figure sorts last in both directions', () => {
  const p = newPage();
  // a position opened today: priced live, real value, and nothing to measure
  // the day against
  const rows = [h('AAPL', '1', '-64.34'), h('NEW', '2', null), h('META', '3', '68.96')];
  assert.deepEqual(sort(p, rows, 'day', 'asc'), ['AAPL', 'META', 'NEW']);
  assert.deepEqual(sort(p, rows, 'day', 'desc'), ['META', 'AAPL', 'NEW']);
});

test('every shape of missing is treated as missing, not as a number', () => {
  const p = newPage();
  const rows = [
    h('REAL', '5.00', '1'),
    h('NUL', null, null),
    h('UNDEF', undefined, undefined),
    h('EMPTY', '', ''),
  ];
  const asc = sort(p, rows, 'value', 'asc');
  assert.equal(asc[0], 'REAL', 'the one priced holding leads');
  assert.deepEqual(asc.slice(1).sort(), ['EMPTY', 'NUL', 'UNDEF']);
});

test('several unpriced holdings keep catalogue order among themselves', () => {
  const p = newPage();
  const rows = [h('ZED', null, null), h('ALPHA', null, null), h('MID', '1.00', '1')];
  assert.deepEqual(sort(p, rows, 'value', 'asc'), ['MID', 'ZED', 'ALPHA']);
  assert.deepEqual(sort(p, rows, 'value', 'desc'), ['MID', 'ZED', 'ALPHA'],
    'the missing block is not reversed either · it is not an ordering');
});

// ================================================================== STABLE
/* Equal values keep the order the server sent. Without it two level holdings
 * trade places on every poll, which reads as movement on a screen whose whole
 * job is to report movement. */

test('equal values keep catalogue order, ascending', () => {
  const p = newPage();
  const rows = [h('ZED', '100.00', '1'), h('ALPHA', '100.00', '2'), h('MID', '100.00', '3')];
  assert.deepEqual(sort(p, rows, 'value', 'asc'), ['ZED', 'ALPHA', 'MID']);
});

test('equal values keep catalogue order DESCENDING too · negation must not reverse ties', () => {
  const p = newPage();
  const rows = [h('ZED', '100.00', '1'), h('ALPHA', '100.00', '2'), h('MID', '100.00', '3')];
  assert.deepEqual(sort(p, rows, 'value', 'desc'), ['ZED', 'ALPHA', 'MID']);
});

test('ties inside a sorted list keep catalogue order', () => {
  const p = newPage();
  const rows = [h('B', '2.00', '0'), h('A', '1.00', '0'), h('C', '2.00', '0'), h('D', '1.00', '0')];
  assert.deepEqual(sort(p, rows, 'value', 'asc'), ['A', 'D', 'B', 'C']);
  assert.deepEqual(sort(p, rows, 'value', 'desc'), ['B', 'C', 'A', 'D']);
});

test('a flat day of exactly zero is a value and sorts as one', () => {
  const p = newPage();
  // 0 is not missing · a position that did not move today HAS a day figure
  const rows = [h('UP', '1', '5.00'), h('FLAT', '2', '0.00'), h('DOWN', '3', '-5.00')];
  assert.deepEqual(sort(p, rows, 'day', 'asc'), ['DOWN', 'FLAT', 'UP']);
});

// ============================================================ PURE, and SAFE

test('sortRows does not mutate the array it was given', () => {
  const p = newPage();
  const rows = [h('ZED', '1.00', '1'), h('ALPHA', '2.00', '2')];
  const before = syms(rows);
  p.call('sortRows', rows, 'instrument', 'asc');
  assert.deepEqual(syms(rows), before, 'the caller\'s snapshot is not the view\'s to reorder');
});

test('an unknown column falls back to instrument rather than throwing', () => {
  const p = newPage();
  const rows = [h('META', '2', '2'), h('AAPL', '1', '1')];
  assert.deepEqual(sort(p, rows, 'nonsense', 'asc'), ['AAPL', 'META']);
});

// ================================================== the CONTROL, and its state

test('the list defaults to Instrument ascending', () => {
  const p = newPage();
  assert.equal(p.run('sortCol'), 'instrument');
  assert.equal(p.run('sortDir'), 'asc');
});

test('tapping the active column toggles direction; tapping another starts ascending', () => {
  const p = newPage();
  p.run('snap = null');                      // redraw() returns early with no snapshot
  p.run("sortBy('instrument')");
  assert.equal(p.run('sortDir'), 'desc', 'the same column toggles');
  p.run("sortBy('instrument')");
  assert.equal(p.run('sortDir'), 'asc', 'and toggles back');
  p.run("sortBy('value')");
  assert.equal(p.run('sortCol'), 'value');
  assert.equal(p.run('sortDir'), 'asc', 'a new column starts ascending');
});

test('sorting does not refetch and does not close an expanded row', () => {
  const p = newPage();
  let fetches = 0;
  p.ctx.fetch = () => { fetches++; return Promise.reject(new Error('no network')); };
  p.run('open = "AAPL"');
  p.run(`snap = { aggregate: { marketValue: '1.00', unrealized: '0.00', unrealizedPct: '0.00',
                               realized: '0.00', dayChange: '0.00', dayChangePct: '0.00',
                               holdings: 1, unpriced: 0, withoutPrevClose: 0, fabricated: 0,
                               stale: 0, expired: 0 },
                  holdings: [{ symbol: 'AAPL', value: '1.00', dayChange: '0.00' }] }`);
  p.run("sortBy('value')");
  assert.equal(fetches, 0, 'a sort is not new information · it must not go to the network');
  assert.equal(p.run('open'), 'AAPL', 'the row the reader expanded is still expanded');
});

// ===================================================== the arrow on the header

test('the arrow is on the active column only, and it points the sort', () => {
  const p = newPage();
  p.run('snap = null');
  const head = () => p.call('headerRow');

  let html = head();
  assert.match(html, /Instrument ▲/, 'the default column carries an ascending arrow');
  assert.doesNotMatch(html, /Value\/Shares ▲|Value\/Shares ▼/, 'and only that column carries one');

  p.run("sortBy('instrument')");
  assert.match(head(), /Instrument ▼/, 'toggling turns the arrow over');

  p.run("sortBy('day')");
  html = head();
  assert.doesNotMatch(html, /Instrument ▲|Instrument ▼/, 'the arrow moves with the sort');
  assert.match(html, /P&amp;L ▲/);
});

test('every header cell is a sort control', () => {
  const p = newPage();
  const html = p.call('headerRow');
  for (const col of ['instrument', 'value', 'day']) {
    assert.ok(html.includes(`sortBy('${col}')`), `the ${col} header must sort`);
  }
});
