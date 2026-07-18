/**
 * Unit tests for the browser logic that is pure enough to test without a
 * browser · web/lib.js. Run with:  node --test src/test/js
 *
 * These exist because three bugs shipped in code that "obviously worked":
 * a gauge panel that hid its own healthy zero, a statement fetch that ran
 * once per card per refresh, and a marquee that measured the DOM on every
 * animation frame. All three are arithmetic, and arithmetic is testable.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const MB = require('../../main/resources/web/lib.js');

// ---------------------------------------------------------------- parseProm
test('parseProm reads Prometheus text exposition, labels and all', () => {
  const m = MB.parseProm(`# HELP minibank_outbox_pending Unpublished outbox rows, by region.
# TYPE minibank_outbox_pending gauge
minibank_outbox_pending{region="eu"} 0
minibank_outbox_pending{region="uk"} 3
minibank_inflight_eur 12.5
`);
  assert.equal(m['minibank_outbox_pending{region="eu"}'], 0);
  assert.equal(m['minibank_outbox_pending{region="uk"}'], 3);
  assert.equal(m['minibank_inflight_eur'], 12.5);
  assert.equal(Object.keys(m).length, 3, 'comments are not series');
});

test('parseProm ignores blank lines and tolerates extra whitespace', () => {
  const m = MB.parseProm('\n\nminibank_pool_busy{region="eu"}   2\n\n');
  assert.equal(m['minibank_pool_busy{region="eu"}'], 2);
});

// ------------------------------------------------------------- gaugeByLabel
test('gaugeByLabel groups a gauge family by its label, keeping zeros', () => {
  const m = MB.parseProm('minibank_outbox_pending{region="eu"} 0\nminibank_outbox_pending{region="uk"} 0\n');
  assert.deepEqual(MB.gaugeByLabel(m, 'minibank_outbox_pending', 'region'), { eu: 0, uk: 0 });
});

test('gaugeByLabel does not match a family whose name merely shares a prefix', () => {
  const m = MB.parseProm('minibank_outbox_pending_total{region="eu"} 9\nminibank_outbox_pending{region="eu"} 1\n');
  assert.deepEqual(MB.gaugeByLabel(m, 'minibank_outbox_pending', 'region'), { eu: 1 });
});

// -------------------------------------------------------------- rateByLabel
test('rateByLabel needs two scrapes · the first only primes the counters', () => {
  const prev = {};
  const a = MB.parseProm('minibank_http_requests_total{route="/x"} 100\n');
  assert.deepEqual(MB.rateByLabel(a, 'minibank_http_requests_total', 'route', 1, prev), {});
  const b = MB.parseProm('minibank_http_requests_total{route="/x"} 110\n');
  assert.deepEqual(MB.rateByLabel(b, 'minibank_http_requests_total', 'route', 2, prev), { '/x': 5 });
});

test('rateByLabel reports no rate when a counter resets · a restart is not a negative spike', () => {
  const prev = {};
  MB.rateByLabel(MB.parseProm('c{route="/x"} 100\n'), 'c', 'route', 1, prev);
  assert.deepEqual(MB.rateByLabel(MB.parseProm('c{route="/x"} 5\n'), 'c', 'route', 1, prev), { '/x': 0 });
});

// ---------------------------------------------------------------- pushSeries
test('pushSeries appends one sample per label and pads labels that went absent', () => {
  const series = {};
  MB.pushSeries(series, { eu: 1, uk: 2 });
  MB.pushSeries(series, { eu: 3 });
  assert.deepEqual(series, { eu: [1, 3], uk: [2, 0] });
});

test('pushSeries caps the window so memory cannot grow without bound', () => {
  const series = {};
  for (let i = 0; i < 50; i++) MB.pushSeries(series, { eu: i }, 40);
  assert.equal(series.eu.length, 40);
  assert.equal(series.eu[39], 49, 'the newest sample is kept');
  assert.equal(series.eu[0], 10, 'the oldest are dropped');
});

// -------------------------------------------------------------- chartSeries
// THE REGRESSION. A backlog of zero is the healthy steady state, not an
// absence of data · the panel must draw it.
test('chartSeries keeps an all-zero GAUGE series · a drained outbox is a reading', () => {
  const c = MB.chartSeries({ eu: [0, 0, 0], uk: [0, 0, 0] }, { gauge: true, floor: 1 });
  assert.deepEqual(c.names, ['eu', 'uk'], 'both regions must be drawn at zero');
  assert.equal(c.quiet, false, 'this panel is not "quiet", it is healthy');
});

test('chartSeries drops an all-zero RATE series · nothing happened is nothing to draw', () => {
  const c = MB.chartSeries({ '/api/x': [0, 0, 0] }, {});
  assert.deepEqual(c.names, []);
  assert.equal(c.quiet, true);
});

test('chartSeries keeps a rate series that moved at all', () => {
  const c = MB.chartSeries({ a: [0, 0, 2], b: [0, 0, 0] }, {});
  assert.deepEqual(c.names, ['a']);
});

test('chartSeries floors the y-scale so one pending row does not fill the panel', () => {
  const flat = MB.chartSeries({ eu: [0, 0], uk: [0, 0] }, { gauge: true, floor: 1 });
  assert.equal(flat.max, 1, 'an all-zero gauge still scales against the floor');
  const spike = MB.chartSeries({ eu: [0, 7], uk: [0, 0] }, { gauge: true, floor: 1 });
  assert.equal(spike.max, 7, 'a real spike sets the scale');
});

test('chartSeries reports names sorted, so colours are stable across ticks', () => {
  const c = MB.chartSeries({ uk: [1, 1], eu: [1, 1] }, { gauge: true });
  assert.deepEqual(c.names, ['eu', 'uk']);
});

// ------------------------------------------------------------------ points
test('chartPoints maps a flat zero series onto the baseline', () => {
  const pts = MB.chartPoints([0, 0, 0], 1, 400, 118);
  assert.equal(pts.length, 3);
  assert.equal(pts[0].y, 114, 'H - 4, the baseline inset');
  assert.equal(pts[0].x, 0);
  assert.equal(pts[2].x, 400, 'the last sample sits at the right edge');
});

test('chartPoints puts the maximum at the top of the panel', () => {
  const pts = MB.chartPoints([0, 8], 8, 400, 118);
  assert.equal(pts[1].y, 4, 'H - (H-8) - 4');
});

test('chartPoints never divides by zero on a single sample', () => {
  const pts = MB.chartPoints([3], 3, 400, 118);
  assert.equal(pts.length, 1);
  assert.ok(Number.isFinite(pts[0].x), 'x must be a real number, not NaN or Infinity');
});

// ------------------------------------------------------------------- swrCache
// Stale-while-revalidate + single flight. The product cards each wanted a
// statement; six cards on every refresh was six queries deep enough to feel.
test('swrCache: a miss fetches once and returns the value', async () => {
  let calls = 0;
  const c = MB.swrCache({ ttl: 1000, now: () => 0 });
  const v = await c.get('k', async () => { calls++; return 'v1'; });
  assert.equal(v, 'v1');
  assert.equal(calls, 1);
});

test('swrCache: concurrent misses for one key make ONE request (single flight)', async () => {
  let calls = 0;
  const c = MB.swrCache({ ttl: 1000, now: () => 0 });
  const load = async () => { calls++; await new Promise(r => setTimeout(r, 10)); return 'v'; };
  const [a, b, d] = await Promise.all([c.get('k', load), c.get('k', load), c.get('k', load)]);
  assert.deepEqual([a, b, d], ['v', 'v', 'v']);
  assert.equal(calls, 1, 'three callers, one query');
});

test('swrCache: a fresh hit does not refetch', async () => {
  let calls = 0, t = 0;
  const c = MB.swrCache({ ttl: 1000, now: () => t });
  const load = async () => { calls++; return 'v' + calls; };
  assert.equal(await c.get('k', load), 'v1');
  t = 500;
  assert.equal(await c.get('k', load), 'v1');
  assert.equal(calls, 1);
});

test('swrCache: a stale hit serves the OLD value immediately and refreshes behind it', async () => {
  let calls = 0, t = 0;
  const c = MB.swrCache({ ttl: 1000, now: () => t });
  const load = async () => { calls++; return 'v' + calls; };
  assert.equal(await c.get('k', load), 'v1');
  t = 2000;                                   // now stale
  assert.equal(await c.get('k', load), 'v1', 'stale is served instantly · no await on the network');
  await c.idle();                             // let the background refresh land
  assert.equal(calls, 2, 'and it was refreshed');
  assert.equal(await c.get('k', load), 'v2', 'the next read sees the new value');
});

test('swrCache: a failed background refresh keeps serving the last good value', async () => {
  let t = 0, fail = false;
  const c = MB.swrCache({ ttl: 100, now: () => t });
  const load = async () => { if (fail) throw new Error('offline'); return 'good'; };
  assert.equal(await c.get('k', load), 'good');
  t = 1000; fail = true;
  assert.equal(await c.get('k', load), 'good');
  await c.idle();
  assert.equal(await c.get('k', load), 'good', 'an outage must not blank the UI');
});

test('swrCache: a failed FIRST load rejects · there is nothing to serve', async () => {
  const c = MB.swrCache({ ttl: 100, now: () => 0 });
  await assert.rejects(() => c.get('k', async () => { throw new Error('boom'); }));
});

test('swrCache: invalidate forces the next read to refetch', async () => {
  let calls = 0;
  const c = MB.swrCache({ ttl: 1e9, now: () => 0 });
  const load = async () => { calls++; return 'v' + calls; };
  await c.get('k', load);
  c.invalidate();
  assert.equal(await c.get('k', load), 'v2');
  assert.equal(calls, 2);
});

// -------------------------------------------------------------- tapeLayout
// The marquee. Every number here used to come from the DOM, once per frame,
// which is what made it stutter.
test('tapeLayout: a train entering an empty belt starts at the right edge', () => {
  const t = MB.tapeLayout({ beltWidth: 800, gap: 76 });
  assert.equal(t.place(120), 800, 'first train is placed just off the right edge');
});

// The bar has no layout at parse time, so a beltWidth captured once is 0 and
// every train launches from the LEFT edge instead of sliding in from the
// right. place() must read whatever the caller set most recently.
test('tapeLayout: place uses the CURRENT belt width, not the one it was built with', () => {
  const t = MB.tapeLayout({ beltWidth: 0, gap: 76 });
  assert.equal(t.place(100), 0, 'an unmeasured belt places at the origin');
  t.trains.length = 0; t.cursor = 0; t.x = 0;
  t.beltWidth = 1200;                       // measured once the bar has layout
  assert.equal(t.place(100), 1200, 'now it enters at the right edge');
});

test('tapeLayout: trains queue behind each other with the gap', () => {
  const t = MB.tapeLayout({ beltWidth: 800, gap: 76 });
  t.place(120);
  assert.equal(t.place(50), 800 + 120 + 76);
});

test('tapeLayout: after scrolling, a new train still enters at the belt edge', () => {
  const t = MB.tapeLayout({ beltWidth: 800, gap: 76 });
  t.place(120);
  t.advance(2000);                 // everything has scrolled well off
  assert.equal(t.place(50), 2000 + 800, 'placed at the right edge in track space');
});

test('tapeLayout: advance moves by dt * speed and reports the offset', () => {
  const t = MB.tapeLayout({ beltWidth: 800, gap: 76, speed: 0.1 });
  t.place(100);
  assert.equal(t.advance(100), -10, '100ms at 0.1px/ms');
  assert.equal(t.x, -10);
});

test('tapeLayout: a long frame is clamped, so a background tab does not teleport the tape', () => {
  const t = MB.tapeLayout({ beltWidth: 800, gap: 76, speed: 0.1, maxFrame: 120 });
  t.place(100);
  t.advance(5000);
  assert.equal(t.x, -12, 'clamped to 120ms of travel');
});

test('tapeLayout: retire drops only trains fully past the left edge, and never shifts the rest', () => {
  const t = MB.tapeLayout({ beltWidth: 100, gap: 10 });
  const a = t.place(50), b = t.place(50);
  assert.equal(a, 100);
  assert.equal(b, 160);
  t.advance(0);
  t.x = -(a + 50 + 20);            // a is fully off, b is not
  const gone = t.retire();
  assert.deepEqual(gone, [0], 'the head index retires');
  assert.equal(t.count, 1);
  assert.equal(t.trains[0].left, b, 'the survivor did not move · absolute placement');
});

test('tapeLayout: retire is a no-op while trains are still on screen', () => {
  const t = MB.tapeLayout({ beltWidth: 100, gap: 10 });
  t.place(50);
  assert.deepEqual(t.retire(), []);
  assert.equal(t.count, 1);
});

test('tapeLayout: an emptied belt resets to zero so coordinates cannot drift', () => {
  const t = MB.tapeLayout({ beltWidth: 100, gap: 10 });
  t.place(50);
  t.x = -100000;
  t.retire();
  assert.equal(t.count, 0);
  assert.equal(t.x, 0, 'a quiet tape starts from a clean origin');
  assert.equal(t.place(10), 100, 'and the next train enters at the belt edge again');
});

test('tapeLayout: the frame loop performs no measurement · place() is the only input', () => {
  const t = MB.tapeLayout({ beltWidth: 800, gap: 76, speed: 0.104 });
  t.place(200);
  for (let i = 0; i < 1000; i++) t.advance(16);
  assert.ok(Number.isFinite(t.x), 'pure arithmetic, 1000 frames, still a number');
});
