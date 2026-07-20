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

// ==================================================================== makeApi
/**
 * THE BUG THESE WERE WRITTEN FOR.
 *
 * const api = (p, opt) => fetch(p, opt).then(r => r.json());
 *
 * That helper never looked at r.ok, so a non-2xx body was handed back as if it
 * were data. The server's error shape is {"error": "..."}, so:
 *
 *   loadAccounts   accounts = await api('/api/accounts')   then accounts.filter(...)
 *                  -> TypeError: accounts.filter is not a function, every 2s,
 *                     and the module-global accounts was left holding the error
 *                     object so later reads failed too.
 *   loadPortfolio  Number(pf.main) + Number(pf.savings) + ...
 *                  -> the total tile rendered NaN.
 *
 * Reproduced live: 90 concurrent GET /api/accounts returned 48 x 429 from the
 * token bucket, which is exactly what clicking around the demo quickly does.
 *
 * The fix turns on a distinction worth stating: 400 and 409 are ANSWERS about
 * the request ("insufficient funds", "relocating, retry"), and their bodies are
 * meaningful, so callers keep receiving them. 429 and 5xx are not answers at
 * all: the token bucket never looked at the request and the 500 never finished
 * it. Returning those bodies as data is the bug.
 */
test('makeApi returns the parsed body on success', async () => {
  const api = MB.makeApi(async () => ({ ok: true, status: 200, json: async () => ({ a: 1 }) }));
  assert.deepEqual(await api('/api/x'), { a: 1 });
});

test('makeApi returns the body for 400 and 409, which are real answers about the request', async () => {
  for (const status of [400, 409]) {
    const api = MB.makeApi(async () => ({ ok: false, status, json: async () => ({ result: 'relocating', error: 'moving' }) }));
    const r = await api('/api/transfer', { method: 'POST' });
    assert.equal(r.result, 'relocating', status + ' must still reach the caller that reads .result');
  }
});

test('makeApi THROWS on 429 rather than handing the error body back as data', async () => {
  const api = MB.makeApi(async () => ({ ok: false, status: 429, json: async () => ({ error: 'rate limited' }) }));
  await assert.rejects(() => api('/api/accounts'), e => {
    assert.equal(e.status, 429);
    assert.match(e.message, /rate limited/);
    return true;
  });
});

test('makeApi throws on 500, so a NaN total is impossible', async () => {
  const api = MB.makeApi(async () => ({ ok: false, status: 500, json: async () => ({ error: 'boom' }) }));
  await assert.rejects(() => api('/api/portfolio?customer=abc'), { status: 500 });
});

test('makeApi survives a non-JSON error body instead of throwing a parse error', async () => {
  // A 502 from the proxy is HTML, not JSON. The caller should still get a
  // usable error rather than "Unexpected token < in JSON".
  const api = MB.makeApi(async () => ({ ok: false, status: 502, json: async () => { throw new Error('bad json'); } }));
  await assert.rejects(() => api('/api/accounts'), e => {
    assert.equal(e.status, 502);
    assert.match(e.message, /502/);
    return true;
  });
});

test('a thrown api call leaves the last good value in place, which is the point', async () => {
  // This is the behaviour that matters to someone looking at the page: the
  // account list keeps showing the last known good data instead of breaking.
  // Stale and correct beats fresh and garbage.
  let accounts = [{ id: 1, kind: 'customer' }];
  const api = MB.makeApi(async () => ({ ok: false, status: 429, json: async () => ({ error: 'rate limited' }) }));
  try { accounts = await api('/api/accounts'); } catch (e) { /* poller swallows */ }
  assert.ok(Array.isArray(accounts), 'the assignment never happened, so accounts is still an array');
  assert.equal(accounts[0].kind, 'customer');
});

// =============================================================== panelLegend
/**
 * WHY A PANEL IS ALLOWED TO SAY SOMETHING WHEN NOTHING IS HAPPENING.
 *
 * "Ledger events by kind" plots a RATE over a 3 second window. This bank has no
 * background scheduler: nothing moves money unless a person clicks. So the
 * honest rate is almost always zero, the series are dropped as empty, and the
 * panel renders "quiet · no activity in this window" forever. Two very
 * different states, a bank that is idle and a counter nobody wired up, looked
 * identical from the outside, which is exactly how the counter stayed dead.
 *
 * The fix is NOT to invent traffic. It is to fall back to the cumulative
 * totals, which are real numbers the process has been keeping all along, and to
 * say plainly that they are totals since start rather than a current rate.
 * A panel may be quiet. It may not be blank when it has something true to say.
 */
test('panelLegend prefers the live series whenever anything is actually moving', () => {
  const r = MB.panelLegend(['transfer_local'], { transfer_local: 412, trade: 88 });
  assert.equal(r.mode, 'live');
});

test('panelLegend falls back to cumulative totals when the window is quiet', () => {
  const r = MB.panelLegend([], { transfer_local: 412, trade: 88, saga_arrive: 5 });
  assert.equal(r.mode, 'total');
  assert.deepEqual(r.kinds, ['transfer_local', 'trade', 'saga_arrive'], 'biggest first, so the legend leads with the real story');
});

test('panelLegend ignores kinds that have never happened, rather than listing a row of zeros', () => {
  const r = MB.panelLegend([], { transfer_local: 3, trade: 0, saga_refund: 0 });
  assert.deepEqual(r.kinds, ['transfer_local']);
});

test('panelLegend reports empty only when the counter genuinely has nothing, which is the dead-instrumentation case', () => {
  assert.equal(MB.panelLegend([], {}).mode, 'empty');
  assert.equal(MB.panelLegend([], { trade: 0 }).mode, 'empty');
  assert.equal(MB.panelLegend([], null).mode, 'empty');
});

test('panelLegend caps the fallback so one busy kind cannot push the legend off the panel', () => {
  const many = {}; for (let i = 0; i < 20; i++) many['kind' + i] = 20 - i;
  const r = MB.panelLegend([], many);
  assert.equal(r.kinds.length, 6);
  assert.equal(r.kinds[0], 'kind0', 'the largest survives the cap');
  assert.equal(r.hiddenCount, 14, 'and the panel says how many it is not showing, rather than silently truncating');
});

// ------------------------------------------------------------- payee picker
// Regression: the "To" dropdown is repainted by a 2s poll, and repainting a
// <select> with innerHTML resets it to the first option. Choosing coco went
// back to oscar seconds later, so a transfer could leave for the wrong person.

const ACCTS = [
  { id: 1, kind: 'customer', owner: 'oscar', region: 'eu' },
  { id: 2, kind: 'customer', owner: 'coco', region: 'uk' },
  { id: 3, kind: 'customer', owner: 'igor', region: 'eu' },
  { id: 9, kind: 'system', owner: 'world', region: 'eu' },
  { id: 10, kind: 'system', owner: 'in_transit', region: 'eu' },
];

test('payeeOptions offers the other customers, never myself', () => {
  assert.deepEqual(MB.payeeOptions(ACCTS, 1).map(a => a.owner), ['coco', 'igor']);
  assert.deepEqual(MB.payeeOptions(ACCTS, 2).map(a => a.owner), ['oscar', 'igor']);
});

test('payeeOptions never offers a system account as a destination', () => {
  const owners = MB.payeeOptions(ACCTS, 1).map(a => a.owner);
  assert.ok(!owners.includes('world'), 'world is book-keeping, not a payee');
  assert.ok(!owners.includes('in_transit'), 'in_transit is a clearing account');
});

test('payeeOptions survives a missing or empty account list', () => {
  assert.deepEqual(MB.payeeOptions(null, 1), []);
  assert.deepEqual(MB.payeeOptions([], 1), []);
});

test('payeeSig is stable across polls that changed nothing', () => {
  const a = MB.payeeSig(MB.payeeOptions(ACCTS, 1));
  const b = MB.payeeSig(MB.payeeOptions(ACCTS.slice(), 1));
  assert.equal(a, b, 'an unchanged roster must not trigger a repaint');
});

test('payeeSig changes when a payee joins, leaves or is renamed', () => {
  const base = MB.payeeSig(MB.payeeOptions(ACCTS, 1));
  const joined = MB.payeeSig(MB.payeeOptions(
    ACCTS.concat([{ id: 4, kind: 'customer', owner: 'nova', region: 'uk' }]), 1));
  const left = MB.payeeSig(MB.payeeOptions(ACCTS.filter(a => a.id !== 2), 1));
  const renamed = MB.payeeSig(MB.payeeOptions(
    ACCTS.map(a => (a.id === 2 ? { ...a, owner: 'coco2' } : a)), 1));
  assert.notEqual(joined, base);
  assert.notEqual(left, base);
  assert.notEqual(renamed, base, 'a rename must repaint or the label goes stale');
});

test('keepPayee carries the chosen payee across a repaint', () => {
  const peers = MB.payeeOptions(ACCTS, 1);
  assert.equal(MB.keepPayee(peers, '2'), '2', 'coco must stay selected');
  assert.equal(MB.keepPayee(peers, 2), '2', 'a numeric value works too');
});

test('keepPayee blanks the field rather than aiming at someone else', () => {
  // coco left the bank between polls: the safe answer is "nobody", never the
  // person who happens to take her place at the top of the list.
  const peers = MB.payeeOptions(ACCTS.filter(a => a.id !== 2), 1);
  assert.equal(MB.keepPayee(peers, '2'), '');
});

test('keepPayee treats an empty selection as no selection', () => {
  const peers = MB.payeeOptions(ACCTS, 1);
  assert.equal(MB.keepPayee(peers, ''), '');
  assert.equal(MB.keepPayee(peers, null), '');
  assert.equal(MB.keepPayee(peers, undefined), '');
});

test('THE BUG: a poll that changes nothing leaves the chosen payee alone', () => {
  // Reproduces the report end to end against a fake <select>: oscar is logged
  // in, the user picks coco, then the 2s poll fires with identical accounts.
  const select = { value: '', options: [] };
  let prevSig = '';
  function poll(accounts, me) {
    const peers = MB.payeeOptions(accounts, me);
    const sig = MB.payeeSig(peers);
    if (sig === prevSig) return;              // nothing changed: do not repaint
    const keep = MB.keepPayee(peers, select.value);
    prevSig = sig;
    select.options = peers.map(a => String(a.id));   // innerHTML would do this
    select.value = keep || '';                       // ...and reset without keep
  }

  poll(ACCTS, 1);
  assert.equal(select.value, '', 'first paint selects nobody');
  select.value = '2';                                // the user clicks coco
  for (let i = 0; i < 10; i++) poll(ACCTS, 1);       // 20 seconds of polling
  assert.equal(select.value, '2', 'coco reverted to oscar again');
});

test('THE BUG: the choice also survives a poll that DOES repaint', () => {
  const select = { value: '', options: [] };
  let prevSig = '';
  function poll(accounts, me) {
    const peers = MB.payeeOptions(accounts, me);
    const sig = MB.payeeSig(peers);
    if (sig === prevSig) return;
    const keep = MB.keepPayee(peers, select.value);
    prevSig = sig;
    select.options = peers.map(a => String(a.id));
    select.value = keep || '';
  }

  poll(ACCTS, 1);
  select.value = '2';                                       // the user picks coco
  poll(ACCTS.concat([{ id: 5, kind: 'customer', owner: 'nova', region: 'eu' }]), 1);
  assert.equal(select.value, '2', 'a new customer joining must not steal the choice');
  assert.ok(select.options.includes('5'), 'and the newcomer is now on offer');
});

test('the page actually USES the payee guard · the wiring, not just the helpers', () => {
  // The helpers above are correct in isolation, which is exactly what the
  // original code was too. What shipped the bug was the CALL SITE: a bare
  // innerHTML repaint of #send-to inside a 2s poll. Pin that, or a future edit
  // reintroduces it while every test above still passes.
  const fs = require('node:fs');
  const path = require('node:path');
  const page = fs.readFileSync(
    path.join(__dirname, '../../main/resources/web/index.html'), 'utf8');

  assert.match(page, /MB\.payeeOptions\(/, 'loadAccounts must build peers via lib.js');
  assert.match(page, /MB\.payeeSig\(/, 'the repaint must be gated on a signature');
  assert.match(page, /MB\.keepPayee\(/, 'the selection must be carried across a repaint');

  // and no unguarded repaint of the dropdown anywhere on the page
  const bare = page.match(/\$\('#send-to'\)\.innerHTML\s*=/g) || [];
  assert.equal(bare.length, 1, 'exactly one place may repaint #send-to');
  const idx = page.indexOf("$('#send-to').innerHTML");
  const before = page.slice(Math.max(0, idx - 400), idx);
  assert.match(before, /prevPayeeSig/,
    'the single repaint must sit behind the changed-signature guard');
});

// ============================================================ THE MAP GRAPH
/**
 * THE REFACTOR THIS MATRIX EXISTS TO DRIVE.
 *
 * The x-ray map had TWO implementations of "what does this event look like on
 * the map": flowFor/animateEvent for live events, and STEP_EDGES/STEP_NODES for
 * replays. They disagreed on the step vocabulary (transfer_local versus
 * transfer), on the edges walked, on which nodes lit and when, on colour and on
 * timing. Neither validated its edges against the map actually drawn. So:
 *
 *   - flowFor walked ['browser_api','caddy_api','api_shardN'], and the edge
 *     NAMED browser_api actually draws browser -> caddy, so anything reading
 *     that name lit the wrong node
 *   - a step could name an edge that does not exist and simply draw no ball
 *   - the live poller animated up to 6 events, 3 balls each, on a 2 second
 *     timer, with no idea a replay was running, using raw setTimeout that
 *     stopPlay() could not cancel, so a replay got a second differently-timed
 *     animation painted over the top of it
 *
 * The fix is one model. Edges are declared as [from, to] PAIRS and the name is
 * derived, so a name can never contradict the topology. A step declares a PATH
 * of nodes and hops are consecutive pairs, so contiguity holds by construction:
 * a ball can only start where a ball has already arrived.
 */
/**
 * THE FIXTURE IS THE MAP, not a copy of it.
 *
 * This used to be a hand-transcribed duplicate of MAP_PAIRS in index.html,
 * carrying a comment that hoped it "keeps saying the same thing as the map it
 * is a copy of". Hope is not a mechanism. A test that asserts against its own
 * copy of the declaration under test asserts nothing about the shipped page:
 * add an edge to the page and the suite stays green while the map is wrong, and
 * remove one and the suite stays green while the map is wrong the other way.
 * The declaration moved into lib.js and both readers now read it.
 *
 * The three extra pairs are NOT part of the map. They are here so the tests
 * that check what happens on an edge the graph does not know about have
 * something to work with, and they are asserted below to be absent from the
 * real map · otherwise they would quietly become part of it.
 */
const EXTRA_PAIRS = [['api', 'jwks'], ['sso', 'directory'], ['api', 'notif']];
const GRAPH_PAIRS = MB.MAP_PAIRS.concat(EXTRA_PAIRS);

test('the test fixture IS the shipped map, not a transcription of it', () => {
  assert.ok(MB.MAP_PAIRS.length >= 20, 'the map is declared in lib.js');
  const real = new Set(MB.MAP_PAIRS.map(p => p[0] + '>' + p[1]));
  for (const [a, b] of EXTRA_PAIRS) {
    assert.equal(real.has(a + '>' + b), false,
      `${a}->${b} is a fixture-only pair and must not be in the shipped map`);
  }
});

test('mapGraph derives an edge name from its endpoints, so the name cannot lie', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  assert.equal(g.name('browser', 'caddy'), 'browser_caddy');
  assert.equal(g.name('shard0', 'kafka'), 'shard0_kafka');
  assert.ok(g.has('kafka', 'notif'));
  assert.ok(!g.has('shard1', 'notif'), 'uk does not talk to notifications');
  assert.ok(!g.has('kafka', 'shard1'), 'kafka reaches uk only through the applier');
});

test('mapGraph is directed, uk publishes to kafka and kafka does not publish to uk', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  assert.ok(g.has('shard1', 'kafka'));
  assert.ok(!g.has('kafka', 'shard1'));
});

// ============================================================== THE JOURNEY
// The cartesian matrix of everything this bank can actually do.
const CASES = [
  // Proven against the live server: a local transfer publishes too. It is one
  // ACID transaction for the MONEY, and the echo still goes out through the
  // outbox, so the steps are transfer, published, notify.
  { name: 'local transfer in eu, igor to oscar',
    steps: [{ step: 'transfer', region: 'eu' }, { step: 'published', region: 'eu' },
            { step: 'notify', region: 'notifications' }] },
  { name: 'local transfer in uk',
    steps: [{ step: 'transfer', region: 'uk' }, { step: 'published', region: 'uk' },
            { step: 'notify', region: 'notifications' }] },
  { name: 'cross-region eu to uk, igor to coco',
    steps: [{ step: 'depart', region: 'eu' }, { step: 'published', region: 'eu' },
            { step: 'arrive', region: 'uk' }, { step: 'notify', region: 'notifications' }] },
  { name: 'cross-region uk to eu',
    steps: [{ step: 'depart', region: 'uk' }, { step: 'published', region: 'uk' },
            { step: 'arrive', region: 'eu' }, { step: 'notify', region: 'notifications' }] },
  // A bounce writes TWO outbox rows, "departed:<tx>" and "bounced:<tx>", and
  // the trace query matches both. So ONE user action legitimately produces two
  // published steps and two notify steps, and the shard->kafka->notif leg is
  // walked twice. That is not the "second loop" bug, it is the compensating
  // transaction being honest about itself.
  { name: 'bounced saga, the refund path',
    steps: [{ step: 'depart', region: 'eu' }, { step: 'published', region: 'eu' },
            { step: 'notify', region: 'notifications' }, { step: 'refund', region: 'eu' },
            { step: 'published', region: 'eu' }, { step: 'notify', region: 'notifications' }] },
  { name: 'relocation leg, a colon-bearing kind that used to animate as nothing',
    steps: [{ step: 'relocate:depart', region: 'eu' }, { step: 'relocate:arrive', region: 'uk' }] },
  { name: 'a single step on its own',
    steps: [{ step: 'transfer', region: 'eu' }] },
  { name: 'live vocabulary, transfer_local must mean the same as transfer',
    steps: [{ step: 'transfer_local', region: 'eu' }] },
  /* THE SECURITIES TRADE. Products.settleFill claims ONE transaction, so this
     is genuinely what the trace endpoint returns for a buy: a single step whose
     kind carries the asset in the middle. It matched no FLOW key, so it drew
     nothing · the broker boxes were on the diagram and no ball ever went near
     them. Buy and sell and both regions are all here because the route is the
     claim being made, and a route that quietly depended on the asset or the
     side would be the same bug in a new place. */
  { name: 'buy AAPL settled in eu, the whole securities loop',
    steps: [{ step: 'settle:aapl:buy', region: 'eu' }] },
  { name: 'sell AAPL settled in uk, the kind from the playTrace comment',
    steps: [{ step: 'settle:aapl:sell', region: 'uk' }] },
  { name: 'an asset nobody hardcoded, because the registry is a table',
    steps: [{ step: 'settle:btc:buy', region: 'eu' }] },
  /* The venue filled and the money said no. No entries are written, so the walk
     must stop at the broker database where the compensation lands. */
  { name: 'settlement refused, the compensation branch',
    steps: [{ step: 'settle-refused', region: 'eu' }] },
  /* The deprecated path. It writes the trade in the ledger itself and tells no
     broker, so it must NOT walk the broker loop · the diagram is what makes
     that difference visible. */
  { name: 'tradeWithoutBroker, the deprecated local-only kind',
    steps: [{ step: 'trade:aapl:buy', region: 'eu' }] },
  /* What the LIVE feed shows for one buy: the shard transaction, then the relay
     publishing that shard's outbox row onto topic settlements. Written as two
     steps rather than one because that is two rows in two tables and the feed
     emits them separately · and a publish is a CONTINUATION, so on its own it
     would start at a database no ball had reached, which is the spontaneous
     ball the contiguity invariant exists to forbid. */
  { name: 'live trade, the settlement publish following the shard transaction',
    steps: [{ step: 'settle:aapl:buy', region: 'eu' },
            { step: 'published:settlements', region: 'eu' }] },
];

/* The trade cases above, by name, so the trade-specific tests below stay
   pointed at the right rows if the table is reordered or extended again. */
const TRADE_CASES = CASES.filter(c => /securities loop|playTrace comment|registry is a table|refused|deprecated local-only|live trade/.test(c.name));

test('every case walks only edges that exist on the map', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    assert.equal(j.unknown.length, 0, c.name + ': unknown ' + JSON.stringify(j.unknown));
    for (const h of j.hops)
      assert.ok(g.has(h.from, h.to), c.name + ': hop ' + h.from + '->' + h.to + ' is not on the map');
  }
});

test('a ball only starts where a ball has already arrived, or at the entry point', () => {
  // The "spontaneous ball" invariant. flowFor violated it by walking
  // browser->api and then starting again at caddy, where nothing had arrived.
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    const reached = new Set(['browser']);
    for (const h of j.hops) {
      assert.ok(reached.has(h.from),
        c.name + ': a ball starts at ' + h.from + ', where nothing has arrived yet');
      reached.add(h.to);
    }
  }
});

test('no hop starts before the previous one lands, unless it is a declared fork', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    for (let i = 1; i < j.hops.length; i++) {
      const prev = j.hops[i - 1], cur = j.hops[i];
      if (cur.wave === prev.wave) continue;
      assert.ok(cur.start >= prev.end - 1,
        c.name + ': ' + cur.from + '->' + cur.to + ' starts ' + cur.start +
        ' but ' + prev.from + '->' + prev.to + ' lands ' + prev.end);
    }
  }
});

test('a fork shares an origin and a start time, which is what two consumer groups look like', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const j = MB.journey(CASES[2].steps, g);
  const byWave = {};
  j.hops.forEach(h => (byWave[h.wave] = byWave[h.wave] || []).push(h));
  const forks = Object.keys(byWave).map(k => byWave[k])
    .filter(hs => new Set(hs.map(h => h.step)).size > 1);
  assert.equal(forks.length, 1, 'exactly one fork: applier and notifications reading one message');
  // A branch may be several hops long, so compare where each STEP begins.
  const firstOfStep = {};
  forks[0].forEach(h => { if (!firstOfStep[h.step]) firstOfStep[h.step] = h; });
  const heads = Object.keys(firstOfStep).map(k => firstOfStep[k]);
  assert.equal(new Set(heads.map(h => h.from)).size, 1, 'both branches leave the same node');
  assert.equal(heads[0].from, 'kafka');
  assert.equal(new Set(heads.map(h => h.start)).size, 1, 'and leave at the same instant');
});

test('every light is justified by a ball arriving, or by a declared state', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    const arrivals = new Set(j.hops.map(h => h.to));
    for (const l of j.lights)
      assert.ok(arrivals.has(l.node) || l.reason === 'state',
        c.name + ': ' + l.node + ' lights for no reason (' + l.reason + ')');
  }
});

test('a node lights when the ball gets there, never before', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    for (const l of j.lights) {
      if (l.reason === 'state') continue;
      const hop = j.hops.find(h => h.to === l.node && h.end <= l.at + 1);
      assert.ok(hop, c.name + ': ' + l.node + ' lights at ' + l.at + ' before any ball reaches it');
    }
  }
});

test('a hop repeats only when the SYSTEM repeated it, never because we drew it twice', () => {
  // The first version of this invariant asserted no hop may ever repeat, and it
  // was wrong: a bounce really does publish twice and notify twice, because it
  // writes a second outbox row for the compensating transaction. An invariant
  // that forbids the truth is worse than no invariant. What must hold is that
  // the animation repeats a hop exactly as often as the trace does, no more.
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    const stepCount = {};
    c.steps.forEach(s => { stepCount[MB.stepName(s.step)] = (stepCount[MB.stepName(s.step)] || 0) + 1; });
    /* The key is joined on '|', not on ':'. It used to be ':' and then had to
       un-split itself with a special case for the one step name that contained
       a colon, which is a parser for a format we control · and it would have
       silently mis-attributed every trade hop, since "settle:buy" splits into
       "settle". A separator that cannot occur in either half needs no rule. */
    const hopCount = {};
    j.hops.forEach(h => { const k = h.step + '|' + h.from + '>' + h.to;
                          hopCount[k] = (hopCount[k] || 0) + 1; });
    for (const k of Object.keys(hopCount)) {
      const step = k.split('|')[0];
      assert.equal(hopCount[k], stepCount[step] || 0,
        c.name + ': ' + k + ' drawn ' + hopCount[k] + ' times for ' + (stepCount[step]||0) + ' step(s)');
    }
  }
});

test('time only moves forwards and the whole journey is bounded', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    for (const h of j.hops) assert.ok(h.end > h.start, c.name + ': a hop with no duration');
    assert.ok(j.total > 0 && j.total <= 12000, c.name + ': total ' + j.total + 'ms out of bounds');
  }
});

test('the same input always produces the same journey', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES)
    assert.deepEqual(MB.journey(c.steps, g), MB.journey(c.steps, g), c.name);
});

test('an unrecognised step is reported, not silently dropped', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const j = MB.journey([{ step: 'teleport', region: 'eu' }], g);
  assert.deepEqual(j.unknown, ['teleport']);
  assert.equal(j.hops.length, 0);
});

test('local and cross-region genuinely differ, oscar is not coco', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const local = MB.journey(CASES[0].steps, g);
  const cross = MB.journey(CASES[2].steps, g);
  // Both publish. What separates them is that the MONEY never leaves one
  // database locally: no applier, no in-transit, no second region.
  assert.ok(!local.hops.some(h => h.from === 'applier' || h.to === 'applier'),
    'a local transfer needs no applier, the money never left its database');
  assert.ok(cross.hops.some(h => h.to === 'applier'), 'a saga does');
  assert.ok(!local.lights.some(l => l.node === 'intransit'),
    'and nothing is ever in flight in a local transfer');
  assert.ok(cross.lights.some(l => l.node === 'intransit'));
});

test('the two vocabularies agree, transfer_local walks exactly what transfer walks', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const a = MB.journey([{ step: 'transfer', region: 'eu' }], g);
  const b = MB.journey([{ step: 'transfer_local', region: 'eu' }], g);
  assert.deepEqual(b.hops.map(h => h.from + '>' + h.to), a.hops.map(h => h.from + '>' + h.to));
});

test('region maps to the right database, both ways round', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  assert.ok(MB.journey([{ step: 'depart', region: 'eu' }], g).hops.some(h => h.to === 'shard0'));
  assert.ok(MB.journey([{ step: 'depart', region: 'uk' }], g).hops.some(h => h.to === 'shard1'));
  const odd = MB.journey([{ step: 'depart', region: 'mars' }], g);
  assert.ok(odd.unknown.length > 0 || odd.hops.length === 0, 'an unknown region is not eu by default');
});

// ==================================================== THE SECURITIES TRADE
/**
 * The bug these exist for: buying AAPL changed NOTHING on the map. The broker
 * nodes had been added to the SVG and declared in MAP_PAIRS, and no FLOW step
 * walked a single one of their edges, so the journey did not exist. It failed
 * in the quietest way the page has · FLOW has no key, journey reports it in
 * `unknown`, and playTrace tells the user the trade "happens off this diagram",
 * blaming the trade for the diagram's gap.
 *
 * The invariants above (contiguity, lights, ordering, determinism) already run
 * over the trade rows because they were added to CASES rather than to a table
 * of their own. What is left here is what is specific to the trade.
 */
test('every trade kind resolves to hops · the bug was that they resolved to nothing', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  assert.ok(TRADE_CASES.length >= 6, 'the trade rows must actually be found in CASES');
  for (const c of TRADE_CASES) {
    const j = MB.journey(c.steps, g);
    assert.ok(j.hops.length > 0, c.name + ': still animates as nothing');
    assert.deepEqual(j.unknown, [], c.name + ': unknown ' + JSON.stringify(j.unknown));
  }
});

test('a trade walks the whole saga · api, shard, both topics, both consumers, the broker db, and home', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const j = MB.journey([{ step: 'settle:aapl:buy', region: 'eu' }], g);
  assert.deepEqual(j.hops.map(h => h.from + '>' + h.to), [
    'browser>caddy',        // the click
    'caddy>api',
    'api>shard0',           // the money lands, four entries in two currencies
    'shard0>ksettle',       // the shard relay publishes onto topic settlements
    'ksettle>brokercons',   // the broker's consumer group reads it
    'brokercons>brokerdb',  // the position, in the fifth database
    'brokerdb>korders',     // the broker relay publishes onto topic orders
    'korders>settle',       // the ledger's Settlement group reads it back
    'settle>shard0',        // and the money is home
  ]);
});

test('the trade route follows the REGION, so a uk trade never touches the eu database', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const uk = MB.journey([{ step: 'settle:aapl:sell', region: 'uk' }], g);
  const touched = new Set(uk.hops.flatMap(h => [h.from, h.to]));
  assert.ok(touched.has('shard1'), 'a uk trade settles in uk');
  assert.ok(!touched.has('shard0'), 'and must not walk through eu on the way');
  assert.ok(touched.has('ksettle') && touched.has('korders'), 'both topics either way');
});

test('buy and sell and every asset walk the identical route · the diagram is not the ledger', () => {
  // What differs between a buy and a sell is the SIGN of the entries, which is
  // a fact about the money and not about the topology. A route that quietly
  // depended on the asset would mean the next listing animates as nothing,
  // which is the whole bug, one level down.
  const g = MB.mapGraph(GRAPH_PAIRS);
  const route = s => MB.journey([{ step: s, region: 'eu' }], g).hops.map(h => h.from + '>' + h.to);
  const want = route('settle:aapl:buy');
  for (const k of ['settle:aapl:sell', 'settle:btc:buy', 'settle:msft:sell',
                   'settle:eth:buy', 'SETTLE:AAPL:BUY'])
    assert.deepEqual(route(k), want, k + ' walks a different route');
});

test('a refused settlement stops at the broker · no money moved, so none walks back', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const j = MB.journey([{ step: 'settle-refused', region: 'eu' }], g);
  const last = j.hops[j.hops.length - 1];
  assert.equal(last.to, 'brokerdb', 'the compensation lands in the broker database and stops');
  assert.ok(!j.hops.some(h => h.from === 'settle'),
    'nothing may travel settle -> shard: recordSettlementRefusal writes no entries, ' +
    'and drawing money returning would show a balance change that never happened');
  assert.ok(!j.hops.some(h => h.to === 'korders'), 'and the broker publishes no order back');
});

test('the deprecated local trade must NOT walk the broker loop', () => {
  // tradeWithoutBroker writes the four entries in the ledger itself and tells
  // no broker · that is the drift Reconciliation exists to catch. If the map
  // drew it going through the broker it would be showing the very thing whose
  // absence is the bug.
  const g = MB.mapGraph(GRAPH_PAIRS);
  const j = MB.journey([{ step: 'trade:aapl:buy', region: 'eu' }], g);
  const touched = new Set(j.hops.flatMap(h => [h.from, h.to]));
  for (const n of ['ksettle', 'brokercons', 'brokerdb', 'korders', 'settle'])
    assert.ok(!touched.has(n), 'the broker-free path must not reach ' + n);
  assert.deepEqual(j.hops.map(h => h.from + '>' + h.to),
    MB.journey([{ step: 'transfer', region: 'eu' }], g).hops.map(h => h.from + '>' + h.to),
    'it is one local ACID transaction, exactly like a local transfer');
});

test('a settlement publish goes to topic settlements, never to the payments topic', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const j = MB.journey([{ step: 'published:settlements', region: 'eu' }], g);
  assert.deepEqual(j.hops.map(h => h.from + '>' + h.to), ['shard0>ksettle']);
  // and the plain one is untouched, still the payments topic
  const p = MB.journey([{ step: 'published', region: 'eu' }], g);
  assert.deepEqual(p.hops.map(h => h.from + '>' + h.to), ['shard0>kafka']);
});

test('THE CONTIGUITY CLAIM, stated for the trade on its own', () => {
  // The general invariant runs over every case; this says it about the nine-hop
  // walk specifically, because a nine hop path is where a gap would first hide.
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of TRADE_CASES) {
    const j = MB.journey(c.steps, g);
    const reached = new Set(['browser']);
    for (const h of j.hops) {
      assert.ok(reached.has(h.from), c.name + ': ball starts at ' + h.from + ', unreached');
      reached.add(h.to);
    }
    for (let i = 1; i < j.hops.length; i++)
      assert.equal(j.hops[i].from, j.hops[i - 1].to,
        c.name + ': the walk breaks between hop ' + (i - 1) + ' and ' + i);
  }
});

test('the hop count is exactly the step count · every step drawn, none drawn twice', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  // legs per normalised step name, read off the FLOW paths declared in lib.js
  const LEGS = { 'settle:buy': 9, 'settle:sell': 9, 'settle-refused': 6,
                 'trade:buy': 3, 'trade:sell': 3, 'published:settlements': 1,
                 transfer: 3, published: 1, notify: 1, arrive: 2, depart: 3, refund: 2,
                 'relocate:depart': 3, 'relocate:arrive': 1 };
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    const expected = c.steps.reduce((n, s) => {
      const legs = LEGS[MB.stepName(s.step)];
      assert.notEqual(legs, undefined, c.name + ': no leg count declared for ' + s.step);
      return n + legs;
    }, 0);
    assert.equal(j.hops.length, expected,
      c.name + ': ' + j.hops.length + ' hops drawn for ' + expected + ' expected');
  }
});

test('stepName collapses the asset and swallows nothing else', () => {
  assert.equal(MB.stepName('settle:aapl:buy'), 'settle:buy');
  assert.equal(MB.stepName('settle:btc:sell'), 'settle:sell');
  assert.equal(MB.stepName('trade:aapl:buy'), 'trade:buy');
  // anchored: near misses must pass straight through rather than be mangled
  assert.equal(MB.stepName('settle-refused'), 'settle-refused');
  assert.equal(MB.stepName('settle:aapl:short'), 'settle:aapl:short');
  assert.equal(MB.stepName('presettle:aapl:buy'), 'presettle:aapl:buy');
  assert.equal(MB.stepName('settle:aapl:buy:extra'), 'settle:aapl:buy:extra');
  assert.equal(MB.stepName('settle::buy'), 'settle::buy');
  // and the pre-existing vocabulary is untouched
  assert.equal(MB.stepName('transfer_local'), 'transfer');
  assert.equal(MB.stepName('bounced'), 'refund');
  assert.equal(MB.stepName('relocate:depart'), 'relocate:depart');
});

test('an unlisted asset animates rather than falling silent · the registry is a table', () => {
  // The failure being prevented: someone lists a new symbol, and the map goes
  // quiet for it alone, with nothing anywhere saying so.
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const sym of ['aapl', 'msft', 'btc', 'eth', 'vwce', 'brk.b', 'tsla-eu', 'x'])
    for (const side of ['buy', 'sell']) {
      const j = MB.journey([{ step: `settle:${sym}:${side}`, region: 'eu' }], g);
      assert.equal(j.hops.length, 9, sym + ':' + side + ' drew ' + j.hops.length + ' hops');
      assert.deepEqual(j.unknown, []);
    }
});

/* ==========================================================================
 * THE PLANNED ROUTE · where the money is ABOUT to go.
 *
 * The reported bug: selecting a transaction lit a couple of boxes green and
 * left the rest dark, so the map answered "where has it got to" without ever
 * answering "where is it going". The page was marking the route from a hand
 * written guess:
 *
 *   ['api', regions.has('eu') && 'shard0', regions.has('uk') && 'shard1',
 *    steps.some(s => s.step === 'published') && 'kafka',
 *    steps.some(s => s.step === 'arrive' || s.step === 'refund') && 'applier',
 *    regions.has('notifications') && 'notif']
 *
 * a second, cruder copy of a topology MB.journey already owned. It could name
 * six nodes out of sixteen and no edges at all, so a local transfer marked two
 * boxes and a nine hop securities settlement marked the same two.
 *
 * MB.plannedPath derives the route from the journey instead. These tests are
 * about the one property that matters and that a guess can never have: the
 * predicted path and the animated path are the same path.
 * ======================================================================== */

/* What the ANIMATION actually marks, simulated from the journey.
 *
 * runJourney sets a timer per light that calls reach(l.node), and a timer per
 * hop that calls reachEdge(h.edge) when the ball lands. So this is the honest
 * answer to "which nodes and edges end up green", derived the same way the
 * page derives it and NOT from plannedPath · a test where both sides come from
 * the function under test proves only that it agrees with itself. */
function animationMarks(j) {
  return {
    nodes: new Set(j.lights.map(l => l.node)),
    edges: new Set(j.hops.map(h => h.edge)),
  };
}

test('the planned route is exactly what the animation goes on to mark · no more, no less', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    const ran = animationMarks(j);
    const planned = MB.plannedPath(j);

    for (const n of planned.nodes)
      assert.ok(ran.nodes.has(n),
        c.name + ': ' + n + ' is planned violet and no ball ever arrives there, ' +
        'so it would sit unresolved after the journey finished');
    for (const n of ran.nodes)
      assert.ok(planned.nodes.includes(n),
        c.name + ': ' + n + ' turns green having never been planned · the route ' +
        'the visitor was shown was not the route that ran');
    for (const e of planned.edges)
      assert.ok(ran.edges.has(e), c.name + ': edge ' + e + ' planned, never travelled');
    for (const e of ran.edges)
      assert.ok(planned.edges.includes(e), c.name + ': edge ' + e + ' travelled, never planned');
  }
});

test('a cross-region payment plans ALL of its route, not the first two boxes', () => {
  // The reported symptom, stated as the number it should be. eu -> uk is eight
  // boxes and seven lines; the old guess named api and shard0 and stopped.
  const g = MB.mapGraph(GRAPH_PAIRS);
  const cross = CASES.find(c => c.name === 'cross-region eu to uk, igor to coco');
  assert.ok(cross, 'the cross-region row must be in CASES');
  const p = MB.plannedPath(MB.journey(cross.steps, g));

  assert.deepEqual(p.nodes.slice().sort(),
    ['api', 'applier', 'caddy', 'intransit', 'kafka', 'notif', 'shard0', 'shard1'],
    'every box the saga touches, including the in-flight state and the notifier');
  assert.deepEqual(p.edges.slice().sort(),
    ['api_shard0', 'applier_shard1', 'browser_caddy', 'caddy_api',
     'kafka_applier', 'kafka_notif', 'shard0_kafka'],
    'and every line between them · the old code marked no edges whatsoever');
});

test('a securities trade plans the whole broker loop · the boxes nothing used to mark', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const buy = CASES.find(c => c.name === 'buy AAPL settled in eu, the whole securities loop');
  assert.ok(buy, 'the trade row must be in CASES');
  const p = MB.plannedPath(MB.journey(buy.steps, g));

  assert.deepEqual(p.nodes.slice().sort(),
    ['api', 'brokercons', 'brokerdb', 'caddy', 'korders', 'ksettle', 'settle', 'shard0']);
  assert.deepEqual(p.edges.slice().sort(),
    ['api_shard0', 'brokercons_brokerdb', 'brokerdb_korders', 'browser_caddy',
     'caddy_api', 'korders_settle', 'ksettle_brokercons', 'settle_shard0',
     'shard0_ksettle'],
    'nine hops, nine planned lines');
  for (const n of ['ksettle', 'brokercons', 'brokerdb', 'korders', 'settle'])
    assert.ok(p.nodes.includes(n),
      n + ' is on the route and the old guess had never heard of it');
});

test('ONE step that expands to NINE hops plans all nine · the screenshotted bug', () => {
  /* The exact trace the owner photographed: a settle:btc:buy with "1 recorded
   * steps, 0ms end to end". Before the animation, two boxes were violet · API
   * and the eu database · and after it, seven were green. The prediction and
   * the performance disagreed by five boxes.
   *
   * This case is the one that exposes it, and a payment would have hidden it: a
   * payment is three steps and about three hops, so anything counting steps
   * lands close enough to look right. A trade is ONE step carrying the entire
   * nine hop saga, so the two numbers are 1 and 9 and there is nowhere to hide.
   *
   * The step count is asserted first, because it is the premise. If the trace
   * endpoint ever starts recording a step per leg this test still holds, but it
   * would no longer be testing what it was written for.
   */
  const g = MB.mapGraph(GRAPH_PAIRS);
  const btc = CASES.find(c => c.name === 'an asset nobody hardcoded, because the registry is a table');
  assert.ok(btc, 'the settle:btc:buy row must be in CASES');
  assert.equal(btc.steps.length, 1, 'the premise: the server records ONE step for a trade');
  assert.equal(btc.steps[0].step, 'settle:btc:buy');

  const j = MB.journey(btc.steps, g);
  assert.equal(j.hops.length, 9, 'and that one step expands to the whole saga');

  const p = MB.plannedPath(j);
  assert.equal(p.edges.length, 9, 'nine hops must plan nine lines, not one');
  assert.deepEqual(p.nodes.slice().sort(),
    ['api', 'brokercons', 'brokerdb', 'caddy', 'korders', 'ksettle', 'settle', 'shard0'],
    'eight boxes · the version that read the STEP list marked api and shard0 and stopped');
  // stated as the delta, so the failure message names the missing boxes
  assert.deepEqual(p.nodes.filter(n => n !== 'api' && n !== 'shard0').sort(),
    ['brokercons', 'brokerdb', 'caddy', 'korders', 'ksettle', 'settle'],
    'the six boxes the screenshot showed dark before the animation and green after');
});

test('the origin is not planned, because nothing ever arrives there to green it', () => {
  // The browser is where the walk STARTS. A violet mark on it would still be
  // violet when the journey finished, which is the one thing the two states
  // must never say together: planned means about to happen.
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    const p = MB.plannedPath(j);
    const arrivals = new Set(j.lights.map(l => l.node));
    for (const n of p.nodes)
      assert.ok(arrivals.has(n), c.name + ': ' + n + ' would never resolve to green');
  }
  const local = MB.plannedPath(MB.journey(CASES[0].steps, g));
  assert.ok(!local.nodes.includes('browser'), 'the origin is where you are, not where you are going');
  assert.ok(local.edges.includes('browser_caddy'), 'but the line leaving it is still travelled');
});

test('a journey the map cannot draw plans NOTHING, rather than a partial route', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const undrawable = MB.journey([{ step: 'teleport', region: 'eu' }], g);
  assert.equal(undrawable.hops.length, 0, 'the premise: this really is undrawable');
  assert.deepEqual(MB.plannedPath(undrawable), { nodes: [], edges: [] });

  // and the degenerate inputs, because this runs on whatever a selection produced
  assert.deepEqual(MB.plannedPath(undefined), { nodes: [], edges: [] });
  assert.deepEqual(MB.plannedPath({}), { nodes: [], edges: [] });
  assert.deepEqual(MB.plannedPath({ hops: [], lights: [{ node: 'intransit', at: 0, reason: 'state' }] }),
    { nodes: [], edges: [] },
    'no hops is no route · a state light on its own must not mark a lone box');
});

test('the planned route names each node and each edge once, however often it is walked', () => {
  // A bounce publishes twice and notifies twice, so shard0_kafka and kafka_notif
  // are genuinely travelled twice. That is two balls and one line.
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const p = MB.plannedPath(MB.journey(c.steps, g));
    assert.equal(new Set(p.nodes).size, p.nodes.length, c.name + ': a node planned twice');
    assert.equal(new Set(p.edges).size, p.edges.length, c.name + ': an edge planned twice');
  }
  const bounce = CASES.find(c => c.name === 'bounced saga, the refund path');
  const j = MB.journey(bounce.steps, g);
  assert.ok(j.hops.filter(h => h.edge === 'kafka_notif').length === 2, 'the premise: it notifies twice');
  assert.equal(MB.plannedPath(j).edges.filter(e => e === 'kafka_notif').length, 1);
});

test('the same journey always plans the same route', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    assert.deepEqual(MB.plannedPath(j), MB.plannedPath(j), c.name);
  }
});

test('every planned node and edge exists on the map, or the mark lands on nothing', () => {
  // querySelector returns null and classList.add is never reached, silently.
  const g = MB.mapGraph(GRAPH_PAIRS);
  const nodes = drawnNodes(), edges = drawnEdges();
  for (const c of CASES) {
    const p = MB.plannedPath(MB.journey(c.steps, MAP_GRAPH_FROM_PAGE()));
    for (const n of p.nodes) assert.ok(nodes.has(n), c.name + ': no data-node="' + n + '" on the map');
    for (const e of p.edges) assert.ok(edges.has(e), c.name + ': no data-edge="' + e + '" on the map');
  }
  assert.ok(g, 'the fixture graph is still built, so the two stay comparable');
});

/* ==========================================================================
 * AND THE PAGE ACTUALLY DOES IT.
 *
 * plannedPath being right is half of it. The other half is the renderer, and
 * the temptation here is to grep index.html for the word "planned" · which is
 * exactly the mistake that once made a guard go GREEN on the commit that
 * reintroduced the bug it existed to catch. So the real functions are lifted
 * out of the page and run against a fake map, and what is asserted is which
 * classes end up on which elements.
 * ======================================================================== */
function fakeMap(nodeIds, edgeIds) {
  const mk = (kind, id) => {
    const cls = new Set([kind]);
    return { kind, id, cls, classList: {
      add: c => cls.add(c), remove: c => cls.delete(c), contains: c => cls.has(c) } };
  };
  const els = nodeIds.map(n => mk('node', n)).concat(edgeIds.map(e => mk('edge', e)));
  const matches = (el, sel) => sel.trim().slice(1).split('.').every(c => el.cls.has(c));
  return {
    els,
    document: {
      querySelector: sel => {
        const m = /^\.(node|edge)\[data-(?:node|edge)="([^"]+)"\]$/.exec(sel);
        return m ? (els.find(el => el.kind === m[1] && el.id === m[2]) || null) : null;
      },
      querySelectorAll: sel => els.filter(el => sel.split(',').some(s => matches(el, s))),
    },
    marked: cls => els.filter(el => el.cls.has(cls)).map(el => el.id).sort(),
  };
}

/** Run the page's own top level functions in a vm, over a fake map. */
function liftMapMarking(dom) {
  const vm = require('node:vm');
  /* playTimers is the page's cancellation registry, and it is part of the
     contract these functions are being tested against: a timer an animation
     creates that is NOT in here cannot be cancelled by stopPlay, which is how
     an abandoned journey's un-glow fired into the journey that replaced it. The
     fake is an array so the tests can count what was registered. */
  let nextTimer = 0;
  const ctx = { MB, document: dom.document, playTimers: [],
                setTimeout: () => ++nextTimer, console };
  vm.createContext(ctx);
  for (const name of ['clearRoute', 'markPlanned', 'glow', 'reach', 'reachEdge']) {
    const start = INDEX.indexOf('function ' + name + '(');
    assert.ok(start > 0, name + ' must exist as a top level function in the page');
    const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
    assert.ok(close, name + ' must be top level to be liftable');
    vm.runInContext(INDEX.slice(start, start + close.index + close[0].length), ctx);
  }
  return {
    plan: j => { ctx.j = j; return vm.runInContext('markPlanned(j)', ctx); },
    reach: n => { ctx.n = n; vm.runInContext('reach(n)', ctx); },
    reachEdge: e => { ctx.e = e; vm.runInContext('reachEdge(e)', ctx); },
    clear: () => vm.runInContext('clearRoute()', ctx),
    timers: () => ctx.playTimers,
  };
}

function pageMap() {
  return fakeMap([...drawnNodes()], [...drawnEdges()]);
}
/* The page's own MAP_PAIRS, so the marking test runs on the real topology
   rather than on the fixture copy of it. The two are already policed against
   each other by the three-copies tests above. */
function MAP_GRAPH_FROM_PAGE() {
  /* The page builds MAP = MB.mapGraph(MB.MAP_PAIRS). So does this. There is no
     longer a "page copy" to read back out of the source. */
  return MB.mapGraph(MB.MAP_PAIRS);
}

test('selecting marks the WHOLE route planned, before a single ball moves', () => {
  const dom = pageMap(), map = liftMapMarking(dom);
  const cross = CASES.find(c => c.name === 'cross-region eu to uk, igor to coco');
  const j = MB.journey(cross.steps, MAP_GRAPH_FROM_PAGE());

  map.plan(j);
  assert.deepEqual(dom.marked('planned'),
    ['api', 'api_shard0', 'applier', 'applier_shard1', 'browser_caddy', 'caddy',
     'caddy_api', 'intransit', 'kafka', 'kafka_applier', 'kafka_notif', 'notif',
     'shard0', 'shard0_kafka', 'shard1'].sort(),
    'the whole route, boxes and lines, marked at selection time');
  assert.deepEqual(dom.marked('visited'), [],
    'and NOTHING is green yet · green is a claim that the money has arrived');
});

test('the screenshotted trade marks eight boxes at selection, not two', () => {
  // The page half of the case above, driven through the page's own marking
  // functions on the page's own topology.
  const dom = pageMap(), map = liftMapMarking(dom);
  const j = MB.journey([{ step: 'settle:btc:buy', region: 'eu' }], MAP_GRAPH_FROM_PAGE());
  map.plan(j);

  const boxes = dom.els.filter(el => el.kind === 'node' && el.cls.has('planned')).map(el => el.id).sort();
  assert.deepEqual(boxes,
    ['api', 'brokercons', 'brokerdb', 'caddy', 'korders', 'ksettle', 'settle', 'shard0'],
    'the screenshot showed api and shard0 violet and the other six dark');
  assert.equal(dom.els.filter(el => el.kind === 'edge' && el.cls.has('planned')).length, 9,
    'and no line was marked at all');
  assert.deepEqual(dom.marked('visited'), [], 'nothing green before the balls move');
});

test('each node and edge flips planned -> reached as the ball gets there, in order', () => {
  const dom = pageMap(), map = liftMapMarking(dom);
  const trade = CASES.find(c => c.name === 'buy AAPL settled in eu, the whole securities loop');
  const j = MB.journey(trade.steps, MAP_GRAPH_FROM_PAGE());
  const p = map.plan(j);

  // replay the journey in the order runJourney's timers would fire
  const events = j.hops.map(h => ({ at: h.end, run: () => map.reachEdge(h.edge) })
    ).concat(j.lights.map(l => ({ at: l.at, run: () => map.reach(l.node) })))
     .sort((a, b) => a.at - b.at);

  let seen = 0;
  for (const ev of events) {
    ev.run(); seen++;
    // whatever has already arrived is green and no longer violet, and the rest
    // of the route is still violet · never both, never neither
    for (const el of dom.els) {
      const onRoute = p.nodes.includes(el.id) || p.edges.includes(el.id);
      if (!onRoute) continue;
      assert.ok(el.cls.has('planned') !== el.cls.has('visited'),
        `after ${seen} arrivals, ${el.id} is both planned and reached, or neither`);
    }
  }

  assert.deepEqual(dom.marked('visited'), [...p.nodes, ...p.edges].sort(),
    'at the end the whole route is green');
  assert.deepEqual(dom.marked('planned'), [], 'and nothing is left saying "about to happen"');
});

test('a repaint mid-flight re-plans only what has NOT happened yet', () => {
  // renderTrace runs again while a journey is in the air · a live event
  // selecting itself, a followed payment polling. Rewinding the green part back
  // to violet would claim the money un-arrived.
  const dom = pageMap(), map = liftMapMarking(dom);
  const cross = CASES.find(c => c.name === 'cross-region eu to uk, igor to coco');
  const j = MB.journey(cross.steps, MAP_GRAPH_FROM_PAGE());
  map.plan(j);
  map.reach('caddy'); map.reachEdge('browser_caddy');

  map.plan(j);
  assert.deepEqual(dom.marked('visited'), ['browser_caddy', 'caddy'],
    'what has arrived stays arrived across a repaint');
  assert.ok(!dom.document.querySelector('.node[data-node="caddy"]').cls.has('planned'));
});

test('stopping clears BOTH colours, so the next selection starts on an empty map', () => {
  const dom = pageMap(), map = liftMapMarking(dom);
  const j = MB.journey(CASES[2].steps, MAP_GRAPH_FROM_PAGE());
  map.plan(j);
  map.reach('caddy'); map.reachEdge('browser_caddy');
  assert.ok(dom.marked('planned').length > 0 && dom.marked('visited').length > 0, 'the premise');

  map.clear();
  assert.deepEqual(dom.marked('planned'), [], 'a violet route left behind describes a transaction that is gone');
  assert.deepEqual(dom.marked('visited'), [], 'and so does a green one');
});

test('a LOOP lap starts fully planned again, so the second lap is as legible as the first', () => {
  // playTrace calls stopPlay (which clears) and then markPlanned before
  // runJourney. Without the re-plan a lap would begin on a map that is already
  // entirely green and therefore says nothing at all.
  const dom = pageMap(), map = liftMapMarking(dom);
  const j = MB.journey(CASES[2].steps, MAP_GRAPH_FROM_PAGE());
  const p = map.plan(j);
  [...p.nodes].forEach(map.reach); [...p.edges].forEach(map.reachEdge);
  assert.deepEqual(dom.marked('planned'), [], 'lap one finished green');

  map.clear(); map.plan(j);                        // what playTrace does per lap
  assert.deepEqual(dom.marked('planned'), [...p.nodes, ...p.edges].sort(),
    'lap two begins from a fully violet route');
  assert.deepEqual(dom.marked('visited'), []);
});

test('an undrawable selection leaves the map completely unmarked', () => {
  const dom = pageMap(), map = liftMapMarking(dom);
  map.plan(MB.journey([{ step: 'teleport', region: 'eu' }], MAP_GRAPH_FROM_PAGE()));
  assert.deepEqual(dom.marked('planned'), []);
  assert.deepEqual(dom.marked('visited'), []);
});

/**
 * THE LIVE PATH, which is a different path from the replay.
 *
 * playTrace feeds journey() the TRACE endpoint's steps; xrayTick feeds it
 * eventToStep(ev) over the EVENTS feed. A trade that replays correctly can
 * still animate as nothing live if eventToStep hands over a name FLOW has no
 * key for, and that half lives in index.html where the journey tests cannot
 * reach it. So it is lifted out of the page and driven with real feed rows,
 * shaped exactly as HttpApi.xrayEvents writes them.
 */
function liftEventToStep() {
  const vm = require('node:vm');
  const start = INDEX.indexOf('function eventToStep(ev)');
  assert.ok(start > 0, 'eventToStep must exist · it is the whole live vocabulary');
  const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
  assert.ok(close, 'eventToStep must be a top level function to be liftable');
  const ctx = {};
  vm.createContext(ctx);
  vm.runInContext(INDEX.slice(start, start + close.index + close[0].length), ctx);
  return ev => vm.runInContext('eventToStep(' + JSON.stringify(ev) + ')', ctx);
}

test('a LIVE trade animates · the events feed vocabulary reaches a real journey', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const eventToStep = liftEventToStep();
  // exactly what xrayEvents emits for a settled buy: type IS the transaction
  // kind, shard is the index, region is the name
  for (const [kind, shard, node] of [['settle:aapl:buy', 0, 'shard0'],
                                     ['settle:aapl:sell', 1, 'shard1'],
                                     ['settle:btc:buy', 0, 'shard0'],
                                     ['settle-refused', 0, 'shard0']]) {
    const step = eventToStep({ type: kind, shard, region: shard ? 'uk' : 'eu', tx: 'x', ts: '2026-07-20T10:00:00Z' });
    const j = MB.journey([step], g);
    assert.ok(j.hops.length > 0, kind + ' animates as nothing on the LIVE path');
    assert.deepEqual(j.unknown, [], kind + ': ' + JSON.stringify(j.unknown));
    assert.ok(j.hops.some(h => h.to === node), kind + ' must settle in ' + node);
    assert.ok(j.hops.some(h => h.to === 'brokerdb'), kind + ' must reach the broker database');
  }
});

test('a settlement publish is told apart from a payments publish by its PAYLOAD', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  const eventToStep = liftEventToStep();
  const pub = payload => eventToStep({ type: 'published', shard: 0, region: 'eu', tx: 'x', payload });

  // a trade settlement rode topic settlements
  const settled = pub('{"type":"trade.settled","fillId":"f1","customer":1,"symbol":"AAPL","units":"2","cash":"400.00"}');
  assert.equal(settled.step, 'published:settlements');
  assert.deepEqual(MB.journey([settled], g).hops.map(h => h.from + '>' + h.to), ['shard0>ksettle']);

  // so did a refusal
  assert.equal(pub('{"type":"trade.rejected","fillId":"f1","reason":"insufficient funds"}').step,
    'published:settlements');

  // and an ordinary payment did NOT · this is the byte-identity of the payment
  // path, asserted at the one place where a trade could have contaminated it
  for (const p of ['{"type":"transfer.executed","tx":"t1","amount":"10.00"}',
                   '{"type":"mortgage.approved","tx":"t2"}', undefined, null, ''])
    assert.equal(pub(p).step, 'published', 'a payments publish must stay on the payments topic');
});

test('the live feed region rule is unchanged by any of this', () => {
  const eventToStep = liftEventToStep();
  assert.equal(eventToStep({ type: 'transfer_local', shard: 0 }).region, 'eu');
  assert.equal(eventToStep({ type: 'transfer_local', shard: 1 }).region, 'uk');
  // notifications arrive with shard -1 and must not become uk
  assert.equal(eventToStep({ type: 'notify', shard: -1 }).region, 'eu');
});

// ====================================================== THE THREE COPIES
/**
 * The map exists three times: as SVG <line data-edge> markup you can see, as
 * the EDGES coordinate table the balls travel along, and as MAP_PAIRS which the
 * animation reasons about. Three copies of one graph is two chances to drift,
 * and drift here is invisible until someone watches a replay closely: a ball
 * flies along a route that is no longer drawn, or a drawn edge never carries
 * anything.
 *
 * This is the guard. It is a test about a file rather than about a function,
 * which is unusual and is the right tool: the failure being prevented is two
 * declarations disagreeing, and no unit test of either one alone can see it.
 */
const fs = require('node:fs');
const path = require('node:path');
const INDEX = fs.readFileSync(
  path.join(__dirname, '../../main/resources/web/index.html'), 'utf8');

function drawnEdges() {
  // Matched on the width only. The map grew a securities loop and the viewBox
  // got taller; a selector that pinned the HEIGHT made a layout change look
  // like eleven unrelated test failures.
  const svg = INDEX.slice(INDEX.indexOf('<svg viewBox="0 0 720 '),
                          INDEX.indexOf('<g id="pulse-layer">'));
  return new Set([...svg.matchAll(/data-edge="([a-z0-9_]+)"/g)].map(m => m[1]));
}
/* NOT PARSED OUT OF THE PAGE ANY MORE. These two used to regex index.html for
   `const MAP_PAIRS` and `const EDGES = {`, which made every one of these guards
   a test about source text: reformat the declaration and the guard silently
   stops checking anything while staying green. Both declarations live in lib.js
   now and the page reads them, so the test reads the same object the browser
   does. What still comes out of the markup is the MARKUP · the <line> and
   <rect> elements · because that genuinely exists nowhere else. */
function declaredEdges() {
  return new Set(MB.MAP_PAIRS.map(p => p[0] + '_' + p[1]));
}
function pulsePaths() {
  return new Set(Object.keys(MB.EDGE_XY));
}
function declaredNodes() {
  const n = new Set();
  for (const [a, b] of MB.MAP_PAIRS) { n.add(a); n.add(b); }
  return n;
}

test('every edge drawn on the map is declared in the graph', () => {
  const missing = [...drawnEdges()].filter(e => !declaredEdges().has(e));
  assert.deepEqual(missing, [], 'drawn but the animation does not know about them');
});

test('every edge in the graph is actually drawn', () => {
  const missing = [...declaredEdges()].filter(e => !drawnEdges().has(e));
  assert.deepEqual(missing, [], 'declared but invisible, so a ball would fly through empty space');
});

test('every drawn edge has pulse coordinates, or its ball has nowhere to travel', () => {
  const missing = [...drawnEdges()].filter(e => !pulsePaths().has(e));
  assert.deepEqual(missing, [], 'no coordinates: pulse() returns silently and no ball appears');
});

test('an edge name always matches the nodes it connects', () => {
  // browser_api actually drew browser -> caddy, so every reader that split the
  // name on the underscore lit the wrong node. Names are derived now; this
  // makes sure nobody hand-writes a contradicting one back in.
  // Matched on the width only. The map grew a securities loop and the viewBox
  // got taller; a selector that pinned the HEIGHT made a layout change look
  // like eleven unrelated test failures.
  const svg = INDEX.slice(INDEX.indexOf('<svg viewBox="0 0 720 '),
                          INDEX.indexOf('<g id="pulse-layer">'));
  const nodes = new Set([...svg.matchAll(/data-node="([a-z0-9]+)"/g)].map(m => m[1]));
  for (const e of drawnEdges()) {
    const from = [...nodes].filter(n => e.startsWith(n + '_')).sort((a, b) => b.length - a.length)[0];
    const to = [...nodes].filter(n => e.endsWith('_' + n)).sort((a, b) => b.length - a.length)[0];
    assert.ok(from && to, `edge ${e} does not name two real nodes`);
    assert.equal(`${from}_${to}`, e, `edge ${e} does not say what it connects`);
  }
});

/**
 * THE FOURTH COPY: the node ids.
 *
 * The three tests above police the EDGES. Nothing policed the endpoints, and a
 * pair naming a node the SVG does not have fails in the quietest way this page
 * has: querySelector returns null, glow() returns early, and the animation
 * draws precisely nothing with no error anywhere. A typo in one identifier is
 * indistinguishable from "that step never happened".
 *
 * The reverse direction is pinned too, but as a LIST rather than as zero. Some
 * boxes on this map genuinely have no edges · they are context (what else runs
 * on the machine) or they are state the journey lights up without travelling to
 * (in flight). Naming them makes adding a twelfth one a deliberate act.
 */
const UNWIRED_NODES = [
  'intransit',                                                  // lit as state, never travelled to
  // The identity service and its parts are drawn as CONTEXT, the same way redis
  // and fx are: really running on this box, with no edge claimed into the bank.
  // The bank does not validate its tokens yet, and a drawn line would say it did.
  'sso', 'ssodb', 'jwks',
  'rita', 'pricefeed', 'fx', 'redis', 'prometheus', 'grafana',  // context: also running on the box
];

function drawnNodes() {
  const svg = INDEX.slice(INDEX.indexOf('<svg viewBox="0 0 720 '),
                          INDEX.indexOf('<g id="pulse-layer">'));
  return new Set([...svg.matchAll(/data-node="([a-z0-9]+)"/g)].map(m => m[1]));
}

test('every node the graph names is actually drawn, or its ball lands on nothing', () => {
  const drawn = drawnNodes();
  const named = declaredNodes();
  assert.ok(named.size > 0, 'the graph must name nodes, or this test proves nothing');
  assert.deepEqual([...named].filter(n => !drawn.has(n)), [],
    'declared in MAP_PAIRS but no such data-node: the animation would silently draw nothing');
});

test('every node drawn is either wired into the graph or a declared unwired one', () => {
  const named = declaredNodes();
  const orphans = [...drawnNodes()].filter(n => !named.has(n) && !UNWIRED_NODES.includes(n));
  assert.deepEqual(orphans, [], 'drawn with no edges and not on the unwired list · add the edge, or the node to the list');
});

/*
 * A LIVE EVENT ARRIVING MID-ANIMATION IS QUEUED, NOT DROPPED.
 *
 * This test used to read `assert.ok(/Date\.now\(\) > playingUntil/.test(INDEX))`,
 * which asserted that one particular LINE OF SOURCE TEXT appeared somewhere in
 * the page. That line was the dropping logic: mark the event seen, then discard
 * it if an animation is running. So when a stale copy of index.html reverted the
 * queue and put the dropping logic back, this test went GREEN, on the exact
 * commit that reintroduced the bug it exists to catch. A guard that passes
 * BECAUSE of the regression is worse than no guard, because it is also a signal
 * that nothing is wrong.
 *
 * So it asserts behaviour now. It lifts the real drainLiveQueue out of the page
 * and runs it in a vm against a virtual clock, a stub runJourney, and a real
 * queue, then checks what actually happens to events that arrive while a replay
 * owns the map. Rewriting the scheduler to drop them fails this test; no
 * spelling of any line can make it pass.
 *
 * COVERS: the drain scheduler · that a queued event survives a busy map, that
 * it is drawn once the map frees up, that ordering is preserved, and that the
 * queue empties rather than growing forever.
 *
 * DOES NOT COVER: the enqueue site itself. That lives in xrayTick, which is
 * bound to fetch, the DOM and the SVG and cannot be driven from node, so the
 * one thing checked here structurally is that xrayTick still hands its events
 * to this scheduler. If someone changes xrayTick to build journeys and drop
 * them without calling drainLiveQueue, only the browser will notice.
 */
test('a live event arriving while an animation runs is queued and drawn later', () => {
  const vm = require('node:vm');

  const start = INDEX.indexOf('function drainLiveQueue()');
  assert.ok(start > 0, 'drainLiveQueue must exist · the live queue is the fix');
  // the page is CRLF · anchor on the first closing brace in column one, either way
  const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
  assert.ok(close, 'drainLiveQueue must be a top level function to be liftable');
  const src = INDEX.slice(start, start + close.index + close[0].length);

  // a virtual clock, so the test is deterministic rather than a sleep race
  let now = 10_000, seq = 0;
  const timers = [];
  const ctx = {
    liveQueue: [], drainTimer: null, playingUntil: 0, drawn: [], selected: [], Math,
    Date: { now: () => now },
    setTimeout: (fn, ms) => { timers.push({ id: ++seq, at: now + Math.max(0, ms), fn }); return seq; },
    runJourney: j => ctx.drawn.push(j.name),
    noteLiveSelection: tx => ctx.selected.push(tx),
    // the page asks lib.js who owns the map · here the real function, driven by
    // a state the test controls, so the scheduler is tested against the real rule
    tabVisible: true, hasSelection: false,
    livePolicy: () => MB.liveEventPolicy({
      tabVisible: ctx.tabVisible, running: now < ctx.playingUntil, hasSelection: ctx.hasSelection }),
  };
  vm.createContext(ctx);
  vm.runInContext(src, ctx);

  const advanceTo = target => {
    for (;;) {
      const due = timers.filter(t => t.at <= target).sort((a, b) => a.at - b.at)[0];
      if (!due) break;
      timers.splice(timers.indexOf(due), 1);
      now = due.at;
      due.fn();
    }
    now = target;
  };

  // A replay claims the map for five seconds. Then a payment happens: three
  // events about a hundred milliseconds apart · the commit, the relay
  // publishing, the notification · all landing inside that window.
  ctx.playingUntil = now + 5000;
  ctx.liveQueue.push({ name: 'committed', total: 300 },
                     { name: 'published', total: 300 },
                     { name: 'notified',  total: 300 });
  vm.runInContext('drainLiveQueue()', ctx);

  assert.deepEqual(ctx.drawn, [],
    'nothing may be drawn on top of the replay that owns the map');
  assert.equal(ctx.liveQueue.length, 3,
    'events arriving mid-animation must be KEPT · the regression marked them ' +
    'seen and threw them away, so a payment lit the eu database and its Kafka ' +
    'and notification steps were never drawn at all');

  advanceTo(now + 10_000);

  assert.deepEqual(ctx.drawn, ['committed', 'published', 'notified'],
    'every queued event must reach the map, in the order it happened');
  assert.equal(ctx.liveQueue.length, 0, 'the queue must drain, not accumulate');
  assert.equal(ctx.drainTimer, null, 'an empty queue must not hold a live timer');
});

test('the live poller hands its events to the queue rather than drawing directly', () => {
  // The structural half, and deliberately narrow: it only checks the WIRING
  // that the behaviour test above cannot reach into. See the note above.
  const i = INDEX.indexOf('async function xrayTick()');
  const j = INDEX.indexOf('window.tickClick', i);
  assert.ok(i > 0 && j > i, 'xrayTick must still be findable in the page');
  const tick = INDEX.slice(i, j);
  assert.ok(/liveQueue\.push\(/.test(tick),
    'xrayTick must enqueue the events it fetched');
  assert.ok(/drainLiveQueue\(\)/.test(tick),
    'xrayTick must ask the queue to drain');
  assert.ok(!/runJourney\(/.test(tick),
    'xrayTick must not animate directly · that is how it used to race replays');
});

/* ==========================================================================
 * SELECTING A TRANSACTION MUST PLAY IT.
 *
 * The reported bug: clicking a row highlighted it and the map sat still. The
 * cause was a step COUNT test standing in for a drawability test · a trace
 * with one step was assumed to be unanimatable, but the securities saga puts
 * its whole nine hop loop inside a single step, so every settlement selected
 * without ever moving. Worse, that branch also skipped the teardown, so the
 * previous journey kept flying under a panel describing a new transaction.
 *
 * The decision is now one pure function. What the page keeps is the drawing.
 * ======================================================================== */
const SEL_MAP = MB.mapGraph([
  ['browser', 'caddy'], ['caddy', 'api'], ['api', 'shard0'], ['api', 'shard1'],
  ['shard0', 'kafka'], ['shard1', 'kafka'], ['kafka', 'applier'],
  ['applier', 'shard0'], ['applier', 'shard1'], ['kafka', 'notif'],
  ['shard0', 'ksettle'], ['shard1', 'ksettle'], ['ksettle', 'brokercons'],
  ['brokercons', 'brokerdb'], ['brokerdb', 'korders'],
  ['korders', 'settle'], ['settle', 'shard0'], ['settle', 'shard1'],
]);
const oneStep = kind => [{ step: kind, region: 'eu', ts: '2026-07-20T10:00:00Z', detail: 'x' }];

test('a ONE STEP trace that the map can draw still plays · the reported bug', () => {
  const d = MB.playDecision({ steps: oneStep('settle:aapl:buy'), graph: SEL_MAP, auto: true });
  assert.equal(d.play, true,
    'a securities settle carries nine hops in ONE step · counting steps called it undrawable');
  assert.equal(d.canDraw, true);
});

test('a one step LOCAL transfer plays too · step count is not drawability', () => {
  const d = MB.playDecision({ steps: oneStep('transfer'), graph: SEL_MAP, auto: true });
  assert.equal(d.play, true);
});

test('every selection decision tears down the previous journey first', () => {
  for (const steps of [oneStep('transfer'), oneStep('settle:aapl:buy'), [], oneStep('nonsense:kind')]) {
    const d = MB.playDecision({ steps, graph: SEL_MAP, auto: true });
    assert.equal(d.stopFirst, true,
      'the old balls must go even when the new trace cannot be drawn · otherwise ' +
      'the map shows one transaction while the panel describes another');
  }
});

test('an undrawable trace does not play and says why, rather than looking ignored', () => {
  const d = MB.playDecision({ steps: oneStep('nonsense:kind'), graph: SEL_MAP, auto: true });
  assert.equal(d.play, false);
  assert.equal(d.canDraw, false);
  assert.ok(d.reason && /nonsense:kind/.test(d.reason), 'the reason must name what happened');
});

test('a trace with no steps at all is a stated empty, not a silent return', () => {
  const d = MB.playDecision({ steps: [], graph: SEL_MAP, auto: true });
  assert.equal(d.play, false);
  assert.equal(d.stopFirst, true);
  assert.ok(/no recorded steps/.test(d.reason));
});

test('a non auto selection still refuses to play but keeps the teardown', () => {
  const d = MB.playDecision({ steps: oneStep('transfer'), graph: SEL_MAP, auto: false });
  assert.equal(d.play, false, 'auto false is an explicit "select without playing"');
  assert.equal(d.canDraw, true, 'drawability is a fact about the trace, not about the caller');
  assert.equal(d.stopFirst, true);
});

// ------------------------------------------------------- controls tell truth
test('replay and loop are disabled until a drawable trace is selected', () => {
  const none = MB.controlState({ hasSelection: false, canDraw: false });
  assert.equal(none.replay, false); assert.equal(none.loop, false);
  assert.ok(none.reason, 'a disabled control must carry a reason for its hover');

  const dead = MB.controlState({ hasSelection: true, canDraw: false });
  assert.equal(dead.replay, false); assert.equal(dead.loop, false);
  assert.ok(/nothing to animate|no map/i.test(dead.reason));

  const live = MB.controlState({ hasSelection: true, canDraw: true });
  assert.equal(live.replay, true); assert.equal(live.loop, true);
  assert.equal(live.reason, '');
});

// ------------------------------------------------------- live event autoplay
test('a live event auto plays only on an idle, VISIBLE x-ray tab', () => {
  assert.deepEqual(
    MB.liveEventPolicy({ tabVisible: true, running: false, hasSelection: false }),
    { animate: true, select: true, keep: true });
});

test('a live event never animates onto a hidden x-ray tab', () => {
  const p = MB.liveEventPolicy({ tabVisible: false, running: false, hasSelection: false });
  assert.equal(p.animate, false, 'no animation runs while the tab is hidden');
  assert.equal(p.select, false, 'and it must not force a selection nobody can see');
  assert.equal(p.keep, false,
    'nor is the animation OWED · it must not fire on return to the tab');
});

test('a live event never hijacks a running replay', () => {
  const p = MB.liveEventPolicy({ tabVisible: true, running: true, hasSelection: true });
  assert.equal(p.animate, false);
  assert.equal(p.select, false);
  assert.equal(p.keep, true, 'it waits its turn rather than being dropped');
});

test('a live event does not paint over a trace the visitor deliberately chose', () => {
  const p = MB.liveEventPolicy({ tabVisible: true, running: false, hasSelection: true });
  assert.equal(p.animate, false,
    'an idle SELECTED path must not have another transaction balls drawn across it');
  assert.equal(p.select, false, 'explicit selection outranks live traffic');
  assert.equal(p.keep, false,
    'nor owed later · closing the panel must not release a burst of stored traffic');
});

// -------------------------------------------------------------- auto pick
test('auto pick fires once per visit and never over an explicit selection', () => {
  assert.equal(MB.autoPickPlan({ autoPicked: false, hasSelection: false }).pick, true);
  assert.equal(MB.autoPickPlan({ autoPicked: true, hasSelection: false }).pick, false);
  assert.equal(MB.autoPickPlan({ autoPicked: false, hasSelection: true }).pick, false);
});

test('auto pick stands down for a deep linked tx and for a payment being followed', () => {
  assert.equal(MB.autoPickPlan({ autoPicked: false, hasSelection: false, deepLinkTx: 'abc' }).pick, false,
    'a deep link is an explicit request · auto pick must not race it');
  assert.equal(MB.autoPickPlan({ autoPicked: false, hasSelection: false, following: 'abc' }).pick, false,
    'followTx clicks the x-ray tab itself · auto pick used to steal the visitor own payment');
});

test('auto pick gives up on an empty feed with a stated empty, not silence', () => {
  const p = MB.autoPickPlan({ autoPicked: false, hasSelection: false, rows: 0, attempt: 6 });
  assert.equal(p.pick, false);
  assert.equal(p.retry, false);
  assert.ok(/no traces yet/i.test(p.reason));
  assert.equal(MB.autoPickPlan({ autoPicked: false, hasSelection: false, rows: 0, attempt: 1 }).retry, true);
});

// ---------------------------------------------------------------- deep link
test('a deep link can carry a transaction id', () => {
  assert.deepEqual(MB.parseXrayHash('#xray?tx=abc123'), { tab: 'xray', tx: 'abc123' });
  assert.deepEqual(MB.parseXrayHash('#xray/abc123'), { tab: 'xray', tx: 'abc123' });
  assert.deepEqual(MB.parseXrayHash('#xray'), { tab: 'xray', tx: null });
  assert.deepEqual(MB.parseXrayHash('#quiz'), { tab: 'quiz', tx: null });
  assert.deepEqual(MB.parseXrayHash('#nope'), { tab: null, tx: null });
  assert.deepEqual(MB.parseXrayHash(''), { tab: null, tx: null });
});

// ------------------------------------------------------------- map clicks
test('clicking a map node that resolves to exactly one transaction plays it', () => {
  const p = MB.mapClickPlan({ node: 'shard0', txs: ['t1'] });
  assert.equal(p.mode, 'play'); assert.equal(p.tx, 't1');
});

test('clicking a map node with several candidates filters and leaves the balls alone', () => {
  const p = MB.mapClickPlan({ node: 'kafka', txs: ['t1', 't2'] });
  assert.equal(p.mode, 'filter');
  assert.deepEqual(p.txs, ['t1', 't2']);
  assert.equal(p.tx, null, 'filtering must never cancel a running journey');
});

test('clicking a map node nothing has touched inspects rather than animating', () => {
  assert.equal(MB.mapClickPlan({ node: 'issuer', txs: [] }).mode, 'inspect');
});

test('a repeated transaction id resolves to one candidate, so the node plays it', () => {
  assert.equal(MB.mapClickPlan({ node: 'shard0', txs: ['t1', 't1', 't1'] }).mode, 'play');
});

// ------------------------------------------------- leaving and returning
test('leaving the x-ray tab stops playback but remembers the selection', () => {
  const s = MB.tabLeavePlan({ looping: true });
  assert.equal(s.stop, true, 'nothing may burn frames on a tab nobody is looking at');
  assert.equal(s.keepSelection, true);
  assert.equal(s.loopArmed, true, 'loop is an explicit standing request · remember it');
});

test('returning restores rather than replaying, unless loop was left on', () => {
  assert.equal(MB.tabEnterPlan({ hasSelection: true, loopArmed: false }).play, false,
    'a restored selection must sit at rest with replay offered, not replay itself');
  assert.equal(MB.tabEnterPlan({ hasSelection: true, loopArmed: false }).autoPick, false,
    'an explicit selection always beats auto pick');
  assert.equal(MB.tabEnterPlan({ hasSelection: true, loopArmed: true }).play, true,
    'loop on means keep playing');
  assert.equal(MB.tabEnterPlan({ hasSelection: false, loopArmed: false }).autoPick, true);
});

/*
 * THE DRAIN SCHEDULER OBEYS THE OWNERSHIP RULE.
 *
 * The lifted-function technique from the queue test above, pointed at the gate
 * rather than at the ordering. The scheduler used to consult only playingUntil,
 * so live traffic animated onto a hidden x-ray tab (burning frames nobody was
 * watching) and drew another transaction's balls across a path the visitor had
 * deliberately selected. Both are now decisions of MB.liveEventPolicy, and the
 * real function is what this drives the scheduler with.
 */
function liftDrain() {
  const vm = require('node:vm');
  const start = INDEX.indexOf('function drainLiveQueue()');
  const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
  const src = INDEX.slice(start, start + close.index + close[0].length);
  let now = 10_000, seq = 0;
  const timers = [];
  const ctx = {
    liveQueue: [], drainTimer: null, playingUntil: 0, drawn: [], selected: [], Math,
    tabVisible: true, hasSelection: false,
    Date: { now: () => now },
    setTimeout: (fn, ms) => { timers.push({ id: ++seq, at: now + Math.max(0, ms), fn }); return seq; },
    runJourney: j => ctx.drawn.push(j.name),
    noteLiveSelection: tx => ctx.selected.push(tx),
    livePolicy: () => MB.liveEventPolicy({
      tabVisible: ctx.tabVisible, running: now < ctx.playingUntil, hasSelection: ctx.hasSelection }),
  };
  vm.createContext(ctx);
  vm.runInContext(src, ctx);
  ctx.run = () => vm.runInContext('drainLiveQueue()', ctx);
  ctx.advance = ms => {
    const target = now + ms;
    for (;;) {
      const due = timers.filter(t => t.at <= target).sort((a, b) => a.at - b.at)[0];
      if (!due) break;
      timers.splice(timers.indexOf(due), 1);
      now = due.at; due.fn();
    }
    now = target;
  };
  return ctx;
}

test('live traffic does not animate onto a hidden x-ray tab, and is not owed later', () => {
  const ctx = liftDrain();
  ctx.tabVisible = false;
  ctx.liveQueue.push({ name: 'a', total: 300 }, { name: 'b', total: 300 });
  ctx.run();
  assert.deepEqual(ctx.drawn, [], 'no animation runs while the x-ray tab is hidden');
  assert.equal(ctx.liveQueue.length, 0,
    'and the backlog is dropped · returning to the tab must not fire a burst of ' +
    'journeys the visitor was never waiting for');
});

test('live traffic does not paint over the trace the visitor chose', () => {
  const ctx = liftDrain();
  ctx.hasSelection = true;
  ctx.liveQueue.push({ name: 'a', total: 300 });
  ctx.run();
  ctx.advance(10_000);
  assert.deepEqual(ctx.drawn, [],
    'the selected path owns the map · this is the selection-matches-map invariant');
});

test('live traffic resumes normally on a visible, unselected, idle map', () => {
  const ctx = liftDrain();
  ctx.liveQueue.push({ name: 'a', total: 300 }, { name: 'b', total: 300 });
  ctx.run();
  ctx.advance(10_000);
  assert.deepEqual(ctx.drawn, ['a', 'b']);
  assert.equal(ctx.drainTimer, null, 'an empty queue must not hold a live timer');
});

/*
 * THE WIRING THAT THE LIFTED TESTS CANNOT REACH.
 *
 * Deliberately narrow, and each one names a specific regression rather than a
 * spelling. The false-green this repo has already had came from asserting that
 * a line of source text existed; these assert that a decision is DELEGATED to
 * the tested function, which is the only thing a source read can honestly say.
 */
test('traceTx decides by drawability, never by counting steps', () => {
  const i = INDEX.indexOf('async function traceTx(');
  assert.ok(i > 0, 'traceTx must still be findable in the page');
  const body = INDEX.slice(i, INDEX.indexOf('\n}', i));
  assert.ok(/MB\.playDecision\(/.test(body),
    'the play-or-not decision must come from the tested pure function');
  assert.ok(!/steps\.length > 1/.test(body),
    'a step COUNT is not drawability · this exact test is the reported bug, ' +
    'because a securities settle carries nine hops inside ONE step');
});

test('the loop guard reads the panel the tab switcher actually hides', () => {
  const i = INDEX.indexOf('function loopAgain()');
  const body = INDEX.slice(i, INDEX.indexOf('\n}', i));
  assert.ok(!/#trace-card'\)\.hidden/.test(body),
    'loopAgain used to test #trace-card.hidden, which the tab switcher never ' +
    'sets · .hidden goes on the ancestor #tab-xray, so a loop cycled invisibly forever');
  assert.ok(/xrayVisible\(\)/.test(body), 'it must ask whether the map is on screen');
});

test('a cancelled journey cannot leave a ball spawn timer behind', () => {
  const i = INDEX.indexOf('function pulse(');
  const body = INDEX.slice(i, INDEX.indexOf('\nfunction glow', i));
  assert.ok(/playTimers\.push\(setTimeout\(/.test(body),
    'pulse used to spawn its ball on a bare setTimeout invisible to stopPlay, ' +
    'so up to nine balls of a cancelled trade loop still landed on the next run');
});

test('stopping a journey also resets the bar and hands back the in-flight box', () => {
  const i = INDEX.indexOf('function stopPlay()');
  const body = INDEX.slice(i, INDEX.indexOf('\n}', i));
  assert.ok(/#play-fill/.test(body),
    'the bar used to freeze wherever its transition had reached and reappear ' +
    'at the previous transaction position on the next selection');
  assert.ok(/showReplayInFlight\(null\)/.test(body),
    'the null timer lived INSIDE playTimers, so a stop between depart and ' +
    'arrive left replayInFlight set and blocked every future live update');
});

test('a live event on an idle map SELECTS what it animates', () => {
  const ctx = liftDrain();
  ctx.liveQueue.push({ name: 'a', total: 300, tx: 't-a' });
  ctx.run();
  ctx.advance(5_000);
  assert.deepEqual(ctx.drawn, ['a']);
  assert.deepEqual(ctx.selected, ['t-a'],
    'the live path used to be animation-only · the map moved while the panel ' +
    'and the row still described whatever was there before');
});

test('a live event arriving mid-replay selects nothing', () => {
  const ctx = liftDrain();
  ctx.playingUntil = 20_000;
  ctx.liveQueue.push({ name: 'a', total: 300, tx: 't-a' });
  ctx.run();
  ctx.advance(1_000);
  assert.deepEqual(ctx.selected, [], 'passive data never steals the selection');
  assert.deepEqual(ctx.drawn, []);
});

test('an auto-picked selection is treated as explicit and outranks live traffic', () => {
  const i = INDEX.indexOf('async function traceTx(');
  const body = INDEX.slice(i, INDEX.indexOf('\n}', i));
  assert.ok(/selectionExplicit = true/.test(body),
    'every path through traceTx is somebody asking for THAT transaction');
  const n = INDEX.indexOf('function noteLiveSelection(');
  const note = INDEX.slice(n, INDEX.indexOf('\n}', n));
  assert.ok(/selectionExplicit = false/.test(note),
    'a selection live traffic made for you must not then silence the next event');
});

test('the page settles its controls before anything is selected', () => {
  assert.ok(/setReplayable\(false\);\s*\/\/ nothing is selected yet/.test(INDEX),
    'setReplayable used to run only from renderTrace and playTrace, so at load ' +
    'replay was a silent no-op and loop lit up while playing nothing');
});

// ======================================================= THE VISITOR'S OWN TX
/**
 * The reported bug: the owner asked Rita to send €50 to oscar, the payment
 * succeeded, the events showed up in the live list · and the trace panel stayed
 * on an unrelated settle:aapl:buy while the map replayed that old journey. The
 * transaction he had just caused sat in the list, unselected, not moving.
 *
 * Two separate things were wrong and only one of them is obvious. Nine of the
 * eleven money-moving paths followed nothing at all, which is the easy half.
 * The interesting half is why the fix is NOT "make autoPickLatest take over":
 * auto-pick is a background poll and has to keep standing down, or it yanks the
 * panel away from whatever somebody chose to look at. So the SOURCE of the
 * intent is an input to the decision, and both cases are tested here.
 */
const FOLLOW_MAP = MB.mapGraph(GRAPH_PAIRS);
const LOCAL_DONE = [
  { step: 'transfer', region: 'eu', ts: '2026-07-20T10:00:00.000Z', detail: 'committed' },
  { step: 'published', region: 'eu', ts: '2026-07-20T10:00:00.090Z', detail: 'relayed' },
  { step: 'notify', region: 'notifications', ts: '2026-07-20T10:00:00.180Z', detail: 'notified' },
];
const fp = o => MB.followPlan(Object.assign({ graph: FOLLOW_MAP }, o));

test('an action the visitor PERFORMED overrides whatever was selected', () => {
  const plan = fp({ source: 'user', hasSelection: true, steps: LOCAL_DONE, prevCount: 3 });
  assert.equal(plan.supersede, true,
    'causing a payment is the most explicit selection there is · this is the ' +
    'reported bug, where the panel stayed on a stranger settle:aapl:buy');
  assert.equal(plan.select, true);
  assert.equal(plan.play, true, 'and it PLAYS · selecting without animating was half the symptom');
});

test('a background pick still stands down for a selection · the rule is not weakened', () => {
  const plan = fp({ source: 'auto', hasSelection: true, steps: LOCAL_DONE, prevCount: 3 });
  assert.equal(plan.supersede, false);
  assert.equal(plan.select, false);
  assert.equal(plan.play, false);
  assert.equal(plan.retry, false, 'and it does not keep trying either');
  // the same trace, the same completeness, the opposite answer · the source of
  // the intent is the only difference, which is the distinction that was missing
  assert.equal(fp({ source: 'user', hasSelection: true, steps: LOCAL_DONE, prevCount: 3 }).play, true);
});

test('an automatic pick on an unselected map still gets to act', () => {
  // standing down is about not stealing · with nothing selected there is
  // nothing to steal, and auto-pick has always been allowed to open the panel
  assert.equal(fp({ source: 'auto', hasSelection: false, steps: LOCAL_DONE, prevCount: 3 }).play, true);
});

test('a departure with no arrival is a saga still moving, and is never played', () => {
  const half = [
    { step: 'depart', region: 'eu', ts: '2026-07-20T10:00:00.000Z' },
    { step: 'published', region: 'eu', ts: '2026-07-20T10:00:00.050Z' },
  ];
  // asked twice with the same answer · "it stopped growing" must not outrank
  // "it has demonstrably not finished"
  const plan = fp({ source: 'user', steps: half, prevCount: 2, attempt: 3 });
  assert.equal(plan.play, false, 'animating here draws money leaving and never landing');
  assert.equal(plan.retry, true);
});

test('a publish with no notification still owes the second consumer group', () => {
  const plan = fp({ source: 'user', steps: LOCAL_DONE.slice(0, 2), prevCount: 2, attempt: 2 });
  assert.equal(plan.play, false,
    'notifications is a separate group on the same topic · its hop is part of ' +
    'the journey, and drawing the payment without it draws two thirds of one');
  assert.equal(plan.retry, true);
});

test('the complete payment plays, and claims nothing is missing', () => {
  const plan = fp({ source: 'user', steps: LOCAL_DONE, prevCount: 3, attempt: 2 });
  assert.equal(plan.play, true);
  assert.equal(plan.partial, false);
  assert.equal(plan.reason, '');
});

test('a transaction that owes nothing plays as soon as it stops growing', () => {
  // a card hold and a savings move write no outbox row, so they are never
  // notified and would otherwise sit out every retry before drawing a journey
  // that was ready on the first poll
  const one = [{ step: 'transfer', region: 'eu', ts: '2026-07-20T10:00:00.000Z' }];
  assert.equal(fp({ source: 'user', steps: one, prevCount: 0 }).play, false,
    'the FIRST sighting is not evidence that nothing more is coming');
  assert.equal(fp({ source: 'user', steps: one, prevCount: 0 }).retry, true);
  assert.equal(fp({ source: 'user', steps: one, prevCount: 1 }).play, true,
    'the second identical answer is');
});

test('the retry interval widens, and the retries run out', () => {
  const waits = [];
  for (let a = 0; a < 10; a++) {
    const p = fp({ source: 'user', steps: [], attempt: a, maxAttempts: 10 });
    assert.equal(p.retry, true, 'attempt ' + a + ' must still be trying');
    waits.push(p.waitMs);
  }
  assert.ok(waits[0] <= 200, 'the first look is prompt · ' + waits[0] + 'ms');
  assert.ok(waits.every((w, i) => i === 0 || w >= waits[i - 1]), 'never narrows: ' + waits);
  assert.ok(waits[3] > waits[0], 'and genuinely widens: ' + waits);
  assert.ok(waits.every(w => w <= 2000), 'no single wait is a hang: ' + waits);
  assert.ok(waits.reduce((a, b) => a + b, 0) < 15000, 'the budget is seconds, not minutes');
  assert.equal(fp({ source: 'user', steps: [], attempt: 10, maxAttempts: 10 }).retry, false,
    'and it does not spin forever');
});

test('when the retries run out it plays what landed rather than nothing', () => {
  const plan = fp({ source: 'user', steps: LOCAL_DONE.slice(0, 2), attempt: 10, maxAttempts: 10 });
  assert.equal(plan.play, true, 'a partial journey the visitor caused beats a stranger complete one');
  assert.equal(plan.partial, true);
  assert.ok(plan.reason.length > 0, 'and it says so rather than claiming to have settled');
});

test('a transaction with no trace at all is stated, not animated', () => {
  const plan = fp({ source: 'user', steps: [], attempt: 10, maxAttempts: 10 });
  assert.equal(plan.play, false);
  assert.equal(plan.select, false, 'there is nothing to select · the panel must not lie');
  assert.ok(plan.reason.length > 0);
});

test('a transaction that happens off this diagram is selected but not played', () => {
  const off = [{ step: 'somebody-elses-subsystem', region: 'eu', ts: '2026-07-20T10:00:00Z' }];
  const plan = fp({ source: 'user', steps: off, attempt: 10, maxAttempts: 10 });
  assert.equal(plan.select, true, 'the steps are real and the panel should show them');
  assert.equal(plan.play, false, 'but nothing on this map corresponds to them');
});

// --------------------------------------------------------------- discoverTx
/**
 * A trade's ledger transaction is claimed by Products.settleFill under the
 * BROKER's fill id. The txId the browser sends to /api/trade belongs to the
 * ORDER and never becomes a transaction, so following it would poll a trace
 * that is never going to exist. Relocation legs have the same shape.
 */
const EVENTS = [
  { type: 'settle:aapl:buy', tx: 'fill-new', ts: '2026-07-20T10:00:02Z', payer: 'igor', payee: 'igor', region: 'eu' },
  { type: 'transfer_local', tx: 'pay-1', ts: '2026-07-20T10:00:01Z', payer: 'igor', payee: 'oscar', region: 'eu' },
  { type: 'settle:btc:sell', tx: 'fill-old', ts: '2026-07-20T09:00:00Z', payer: 'igor', payee: 'igor', region: 'eu' },
];

test('a trade is found by what was NOT in the feed when the visitor acted', () => {
  assert.equal(MB.discoverTx(EVENTS, { kinds: ['settle'], owner: 'igor', known: ['fill-old'] }), 'fill-new');
  assert.equal(MB.discoverTx(EVENTS, { kinds: ['settle'], owner: 'igor', known: ['fill-old', 'fill-new'] }), null,
    'nothing new means nothing to follow · never fall back to an older one');
});

test('the asset in the middle of the kind does not hide the settlement', () => {
  // settle:aapl:buy, settle:btc:sell · the asset is a row in a registry, so an
  // instrument listed tomorrow must be discoverable without touching this file
  assert.equal(MB.discoverTx([EVENTS[2]], { kinds: ['settle'], owner: 'igor', known: [] }), 'fill-old');
  assert.equal(MB.discoverTx(EVENTS, { kinds: ['transfer'], owner: 'igor', known: [] }), 'pay-1',
    'and transfer_local is reached by its normalised name, not its feed name');
});

test('another customer transaction is never followed', () => {
  const theirs = [{ type: 'settle:aapl:buy', tx: 'not-mine', ts: '2026-07-20T10:00:02Z', payer: 'coco', payee: 'coco' }];
  assert.equal(MB.discoverTx(theirs, { kinds: ['settle'], owner: 'igor', known: [] }), null);
  assert.equal(MB.discoverTx(theirs, { kinds: ['settle'], owner: 'coco', known: [] }), 'not-mine');
});

test('discovery does not need two machines to agree about the time', () => {
  // the events carry the database clock and the caller has the browser one ·
  // membership rather than comparison is why this works at all
  const noClock = [{ type: 'relocate:depart', tx: 'leg-1', ts: 'not a date', payer: 'igor', payee: 'igor' }];
  assert.equal(MB.discoverTx(noClock, { kinds: ['relocate'], owner: 'igor', known: [] }), 'leg-1');
  assert.equal(MB.discoverTx([], { kinds: ['relocate'], owner: 'igor', known: [] }), null);
});

// ------------------------------------------- the follow loop, lifted and run
/**
 * The page's own follow, driven on a fake clock against a fake API.
 *
 * Not a source grep. This file has been bitten once by a test that read the
 * page as text and went green BECAUSE of the regression it existed to catch, so
 * what is asserted here is what the functions DO: how many times they poll, in
 * what order, what they hand to the player, and whether a second action leaves
 * the first one's retries alive behind it.
 */
function liftFollow(replies) {
  const vm = require('node:vm');
  let now = 10_000, seq = 0;
  const timers = [];
  const el = () => ({ style: {}, textContent: '', innerHTML: '', hidden: true,
                      scrollIntoView() {}, classList: { contains: () => false } });
  const ctx = {
    MB, Date, Math, JSON, console, encodeURIComponent, String, Number,
    // page state the lifted functions read and write as globals
    followToken: 0, followTimer: null, followSteps: 0, followFind: null,
    following: null, deepLinkTx: null, loopTimer: null,
    activeTrace: null, selectionExplicit: false, latestEvents: [],
    MAP: FOLLOW_MAP, pendingFollow: null,
    // what the test watches
    polled: [], rendered: [], played: [], captions: [], stopped: 0,
    document: { querySelector: () => null, querySelectorAll: () => [] },
    $: () => el(),
    setTimeout: (fn, ms) => { timers.push({ id: ++seq, at: now + Math.max(0, ms), fn }); return seq; },
    clearTimeout: id => { const i = timers.findIndex(t => t.id === id); if (i >= 0) timers.splice(i, 1); },
    stopPlay: () => { ctx.stopped++; vm.runInContext('cancelFollow()', ctx); },
    renderTrace: (tr, tx) => { ctx.activeTrace = tr; ctx.rendered.push(tx); },
    playTrace: tr => ctx.played.push(tr.tx),
    gShow: () => {},
    setReplayable: () => {},
    api: async path => {
      const tx = decodeURIComponent(path.slice(path.indexOf('tx=') + 3));
      ctx.polled.push(tx);
      const list = replies[tx];
      const nth = ctx.polled.filter(t => t === tx).length - 1;
      if (!list) throw new Error('no such trace');
      return { tx, steps: list[Math.min(nth, list.length - 1)] };
    },
  };
  vm.createContext(ctx);
  for (const name of ['cancelFollow', 'followStart', 'followTx', 'followPoll',
                      'willFollow', 'followed']) {
    const start = INDEX.search(new RegExp('(?:async )?function ' + name + '\\('));
    assert.ok(start > 0, name + ' must exist as a top level function in the page');
    const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
    assert.ok(close, name + ' must be top level to be liftable');
    vm.runInContext(INDEX.slice(start, start + close.index + close[0].length), ctx);
  }
  ctx.start = spec => { ctx.spec = spec; return vm.runInContext('followStart(spec)', ctx); };
  ctx.settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
  ctx.advance = async ms => {
    const target = now + ms;
    for (;;) {
      const due = timers.filter(t => t.at <= target).sort((a, b) => a.at - b.at)[0];
      if (!due) break;
      timers.splice(timers.indexOf(due), 1);
      now = due.at;
      await due.fn();
      await ctx.settle();
    }
    now = target;
  };
  ctx.pending = () => timers.length;
  ctx.armed = () => timers.map(t => t.id);
  return ctx;
}

const GROWING = [[], [LOCAL_DONE[0]], LOCAL_DONE.slice(0, 2), LOCAL_DONE];

test('the follow WAITS for the trace, then plays it exactly once', async () => {
  const ctx = liftFollow({ 'my-tx': GROWING });
  await ctx.start({ tx: 'my-tx' });
  await ctx.settle();
  assert.deepEqual(ctx.played, [],
    'the API answered on the local commit · the publish, the notification and ' +
    'the arrival had not happened yet, so there was nothing complete to draw');
  await ctx.advance(20_000);
  assert.deepEqual(ctx.played, ['my-tx'],
    'one play, once the journey is actually there · not one per poll');
  assert.ok(ctx.polled.length >= 4, 'it kept looking: ' + ctx.polled.length + ' polls');
  assert.ok(ctx.polled.length <= 12, 'and it did not spin: ' + ctx.polled.length + ' polls');
  assert.equal(ctx.pending(), 0, 'nothing is left scheduled once it has played');
});

test('the followed transaction is SELECTED, not merely animated', async () => {
  const ctx = liftFollow({ 'my-tx': GROWING });
  await ctx.start({ tx: 'my-tx' });
  await ctx.advance(20_000);
  assert.ok(ctx.rendered.includes('my-tx'),
    'renderTrace is what fills the panel and marks the matching row picked · ' +
    'the reported bug is a transaction sitting in the list unselected');
  assert.equal(ctx.selectionExplicit, true,
    'and it is an EXPLICIT selection, so live traffic does not paint over it');
  assert.equal(ctx.activeTrace.tx, 'my-tx');
});

test('a follow overrides a selection that was already open', async () => {
  const ctx = liftFollow({ 'my-tx': [LOCAL_DONE, LOCAL_DONE] });
  ctx.activeTrace = { tx: 'somebody-elses-settle', steps: [] };
  ctx.selectionExplicit = true;
  await ctx.start({ tx: 'my-tx' });
  await ctx.advance(20_000);
  assert.equal(ctx.activeTrace.tx, 'my-tx',
    'this is the whole bug · an old settle:aapl:buy kept the panel and the map');
  assert.deepEqual(ctx.played, ['my-tx']);
});

test('a second action supersedes the first and cancels its pending retries', async () => {
  const ctx = liftFollow({ slow: [[], [], [], [], [], []], quick: [LOCAL_DONE] });
  await ctx.start({ tx: 'slow' });
  await ctx.advance(400);
  const slowPolls = ctx.polled.filter(t => t === 'slow').length;
  assert.ok(slowPolls >= 1, 'the first follow got going');
  assert.equal(ctx.pending(), 1, 'exactly one retry is armed · never a fan of them');
  const stale = ctx.armed();

  await ctx.start({ tx: 'quick' });
  assert.deepEqual(ctx.armed().filter(id => stale.includes(id)), [],
    'the earlier follow\'s armed retry is CANCELLED the moment a second action ' +
    'takes over · disowning it and leaving it to wake up is racing it, not stopping it');
  await ctx.advance(20_000);
  assert.equal(ctx.polled.filter(t => t === 'slow').length, slowPolls,
    'and it polled no further');
  assert.deepEqual(ctx.played, ['quick'], 'and only the newest action animated');
  assert.equal(ctx.pending(), 0);
});

test('a trade is followed even though its id belongs to the order', async () => {
  const ctx = liftFollow({ 'fill-new': [LOCAL_DONE] });
  ctx.latestEvents = EVENTS;
  await ctx.start({ find: { kinds: ['settle'], owner: 'igor', known: ['fill-old'] } });
  await ctx.advance(20_000);
  assert.deepEqual(ctx.played, ['fill-new'],
    'the browser never sees the fill id · it is discovered from the feed');
  assert.ok(ctx.rendered.includes('fill-new'));
});

test('running a tool follows what it created, and a failed tool follows nothing', () => {
  const ctx = liftFollow({ made: [LOCAL_DONE] });
  const started = [];
  ctx.followStart = spec => started.push(spec && (spec.tx || 'find'));
  const vm = require('node:vm');
  // exactly the shape prodRun and actAllow use: the tool names its target as
  // it mints the id, the caller hands the tool's own result back
  ctx.tx = vm.runInContext('willFollow({ tx: "made" })', ctx);
  assert.equal(ctx.tx, 'made', 'the id is handed straight back so it can be sent to the API');
  vm.runInContext('followed("sent EUR 50 to oscar")', ctx);
  assert.deepEqual(started, ['made']);

  vm.runInContext('willFollow({ tx: "made" }); followed({ err: "insufficient funds" })', ctx);
  assert.deepEqual(started, ['made'], 'a refused action created no transaction to watch');

  vm.runInContext('followed("a success with nothing pending")', ctx);
  assert.deepEqual(started, ['made'], 'and a stale target is never followed twice');
});

/* ==========================================================================
 * PROPERTIES, OVER THE WHOLE MATRIX.
 *
 * Everything above this line is a case: one trace, one expectation, written
 * after somebody found the bug by using the site. That is how all of this
 * week's defects were found · one at a time, by the owner, in the browser · and
 * it does not scale, because the matrix is triggers x kinds x states and a case
 * covers one cell of it.
 *
 * What follows is the other kind. Each one states a rule that must hold for
 * EVERY transaction kind the server can emit, in EVERY region, and then checks
 * it exhaustively. The point is not to cover today's kinds. It is that adding a
 * kind server-side and forgetting the map turns the suite RED instead of
 * turning the animation silent, which is the only failure mode this view has:
 * it does not crash, it does not warn, it just quietly draws nothing while
 * looking entirely healthy.
 *
 * MB.SERVER_KINDS is the registry that makes that possible. It is a declaration
 * of what the wire can carry, and the properties below are quantified over it.
 * ======================================================================== */

const PROP_GRAPH = MB.mapGraph(MB.MAP_PAIRS);
const PROP_REGIONS = MB.REGIONS;
const propStep = (kind, region) => [{ step: kind, region, ts: '2024-01-01T00:00:00.000Z' }];

/* PROPERTY 1 · the one that would have caught the most of them.
 *
 * Every kind the server can put on the wire either draws, or is on the
 * explicitly declared unwired list with a reason. "mortgage" was neither: it
 * had no FLOW key, journey() dropped it into unknown[], and because the same
 * trace carried a `published` step that DID draw, the honest "happens off this
 * diagram" caption never fired either. A loan animated as a ball leaving a
 * shard box that nothing had ever arrived at. This property fails on that. */
test('PROPERTY · every kind the server can emit either draws or is declared unwired', () => {
  const gaps = [];
  for (const k of MB.SERVER_KINDS) {
    const cov = MB.kindCoverage(k.probe, PROP_GRAPH, PROP_REGIONS);
    if (!cov.ok) {
      gaps.push(k.probe + ' (' + k.source + '): ' + (cov.declaredUnwired
        ? 'declared unwired but it DOES draw'
        : 'draws in ' + cov.perRegion.filter(r => r.hops > 0).length + '/' + PROP_REGIONS.length +
          ' regions · unknown=' + JSON.stringify(cov.perRegion[0].unknown)));
    }
  }
  assert.deepEqual(gaps, [],
    'a kind that draws nothing and is not declared to draw nothing is a silent map · ' +
    'wire it into FLOW, or add it to MB.UNWIRED_KINDS with the reason why not');
});

test('PROPERTY · a kind draws in EVERY region or in none · shard1 customers are not second class', () => {
  for (const k of MB.SERVER_KINDS) {
    const cov = MB.kindCoverage(k.probe, PROP_GRAPH, PROP_REGIONS);
    assert.ok(cov.drawsEverywhere || cov.drawsNowhere,
      k.probe + ' draws in some regions and not others: ' + JSON.stringify(cov.perRegion));
  }
});

test('PROPERTY · nothing is on the unwired list without a reason', () => {
  for (const k of Object.keys(MB.UNWIRED_KINDS)) {
    assert.equal(typeof MB.UNWIRED_KINDS[k], 'string');
    assert.ok(MB.UNWIRED_KINDS[k].length > 30,
      k + ' is excused from the map with no explanation · say why, or wire it');
  }
});

test('PROPERTY · every FLOW key is reachable from something the server emits', () => {
  // The reverse gap: a route declared, maintained, and walked by nothing. It is
  // dead code that looks like coverage, which is worse than an obvious hole.
  const reachable = new Set(MB.SERVER_KINDS.map(k => MB.stepName(k.probe)));
  const orphans = MB.FLOW_KINDS.filter(f => !reachable.has(f));
  assert.deepEqual(orphans, [],
    'a FLOW route no server kind resolves to · either the registry is stale or the route is');
});

/* PROPERTY 2 · INV-07 and INV-08 together, quantified over kinds rather than
 * over the handful of paths somebody remembered to check.
 *
 * A hop whose edge is not in the graph is dropped into unknown[] and draws
 * nothing. A hop whose edge IS in the graph but has no coordinate entry makes
 * pulse() return silently · no ball, no warning, and journey() still reports the
 * hop as drawable, so every other check says the map is fine. */
test('PROPERTY · every hop any kind can produce travels a declared edge with real coordinates', () => {
  for (const k of MB.SERVER_KINDS) {
    for (const region of PROP_REGIONS) {
      const j = MB.journey(propStep(k.probe, region), PROP_GRAPH);
      for (const h of j.hops) {
        assert.ok(PROP_GRAPH.has(h.from, h.to),
          k.probe + '/' + region + ': hop ' + h.from + '->' + h.to + ' is not an edge of the map');
        assert.ok(MB.EDGE_XY[h.edge],
          k.probe + '/' + region + ': edge ' + h.edge +
          ' has no coordinates · pulse() would draw nothing, silently');
      }
    }
  }
});

test('PROPERTY · a wired kind reports no unknowns at all', () => {
  for (const k of MB.SERVER_KINDS) {
    if (MB.UNWIRED_KINDS[MB.stepName(k.probe)]) continue;
    for (const region of PROP_REGIONS) {
      assert.deepEqual(MB.journey(propStep(k.probe, region), PROP_GRAPH).unknown, [],
        k.probe + '/' + region + ' draws, but reports something it did not understand');
    }
  }
});

test('PROPERTY · every coordinate belongs to an edge the graph declares', () => {
  // The other direction. A coordinate for an edge nobody can walk is a line on
  // the diagram that claims a connection the model does not have.
  const declared = new Set(MB.MAP_PAIRS.map(p => p[0] + '_' + p[1]));
  for (const e of Object.keys(MB.EDGE_XY)) {
    assert.ok(declared.has(e), e + ' has coordinates but is not an edge of the graph');
  }
});

/* PROPERTY 3 · INV-02, for every kind.
 *
 * The predicted route and the animated route are the same list read twice, and
 * this checks that they still are for every kind, not for the two that had
 * tests. A kind whose plan and animation disagree shows violet boxes the money
 * never reaches, or turns boxes green that were never announced. */
test('PROPERTY · for every kind, the planned route IS the animated route', () => {
  for (const k of MB.SERVER_KINDS) {
    for (const region of PROP_REGIONS) {
      const j = MB.journey(propStep(k.probe, region), PROP_GRAPH);
      const p = MB.plannedPath(j);
      const label = k.probe + '/' + region;
      assert.deepEqual(new Set(p.edges), new Set(j.hops.map(h => h.edge)),
        label + ': planned edges are not the edges walked');
      assert.deepEqual(new Set(p.nodes), new Set(j.lights.map(l => l.node)),
        label + ': planned nodes are not the nodes lit');
      // and never the walk's origin, which nothing arrives at, so nothing ever
      // turns it green · a node left violet forever after the journey finished
      for (const h of j.hops) {
        if (!j.hops.some(x => x.to === h.from)) {
          assert.equal(p.nodes.includes(h.from), false,
            label + ': ' + h.from + ' is planned but nothing ever arrives there');
        }
      }
    }
  }
});

/* PROPERTY 4 · INV-24, for every kind.
 *
 * Contiguity is meant to be a property of the SHAPE · FLOW declares a path, not
 * an edge list · so this is checking that the shape is still what it claims to
 * be. A ball must land before the next one leaves, or the map shows three hops
 * happening at once, which is the specific lie a replay must not tell because a
 * replay is a claim about ORDER. */
test('PROPERTY · for every kind, hops are contiguous and land before the next leaves', () => {
  for (const k of MB.SERVER_KINDS) {
    for (const region of PROP_REGIONS) {
      const j = MB.journey(propStep(k.probe, region), PROP_GRAPH);
      const label = k.probe + '/' + region;
      for (let i = 1; i < j.hops.length; i++) {
        const prev = j.hops[i - 1], cur = j.hops[i];
        assert.equal(prev.to, cur.from,
          label + ': hop ' + i + ' starts at ' + cur.from + ', but the previous one landed at ' + prev.to);
        assert.ok(prev.end <= cur.start,
          label + ': hop ' + i + ' leaves at ' + cur.start + ' before hop ' + (i - 1) + ' lands at ' + prev.end);
        assert.ok(cur.start >= prev.start, label + ': hops must be ordered in time');
      }
      for (const h of j.hops) {
        assert.ok(h.end > h.start, label + ': a hop must take time');
        assert.ok(h.start >= 0, label + ': a hop cannot leave before the journey starts');
        assert.ok(h.end <= j.total + 1, label + ': a hop cannot land after the journey ends');
      }
    }
  }
});

test('PROPERTY · for every kind, every node lit is a node some hop actually reaches', () => {
  for (const k of MB.SERVER_KINDS) {
    for (const region of PROP_REGIONS) {
      const j = MB.journey(propStep(k.probe, region), PROP_GRAPH);
      const arrivals = new Set(j.hops.map(h => h.to));
      for (const l of j.lights) {
        if (l.reason === 'state') continue;      // state is not a destination
        assert.ok(arrivals.has(l.node),
          k.probe + '/' + region + ': ' + l.node + ' lights up as an arrival nothing arrives at');
      }
    }
  }
});

/* PROPERTY 5 · INV-25, and it needs pairs of steps rather than one.
 *
 * Two consumer groups reading one message leave Kafka at the same instant.
 * Drawing them in series made the ball look like it jumped back to fetch the
 * notification. Quantified over every pair of kinds that can share an origin. */
test('PROPERTY · any two steps leaving the same node leave together', () => {
  const kinds = MB.SERVER_KINDS.map(k => k.probe);
  for (const a of kinds) {
    for (const b of kinds) {
      for (const region of PROP_REGIONS) {
        const j = MB.journey(
          [{ step: a, region, ts: '2024-01-01T00:00:00.000Z' },
           { step: b, region, ts: '2024-01-01T00:00:00.100Z' }], PROP_GRAPH);
        const byWave = {};
        for (const h of j.hops) (byWave[h.wave] = byWave[h.wave] || []).push(h);
        for (const w of Object.keys(byWave)) {
          const firsts = byWave[w].filter(h => !byWave[w].some(x => x.to === h.from));
          const starts = new Set(firsts.map(h => h.start));
          assert.ok(starts.size <= 1,
            a + ' then ' + b + ' in ' + region + ': wave ' + w + ' leaves at ' + [...starts].join(' and '));
        }
      }
    }
  }
});

/* PROPERTY 6 · INV-26. stepName is the single normaliser, so it has to be total
 * and idempotent over everything that can reach it · which now includes every
 * registered kind, both alias vocabularies, and the junk a malformed feed row
 * can produce. A second application must be a no-op or the caller and the
 * journey can disagree about what a step is called. */
test('PROPERTY · stepName is total and idempotent over every kind and every junk input', () => {
  const junk = [null, undefined, '', 0, false, [], {}, 'teleport', 'SETTLE:AAPL:BUY',
                'settle:aapl:', 'settle::buy', 'settle-refused', 'trade:a.b-c_d:sell'];
  const all = MB.SERVER_KINDS.map(k => k.probe).concat(junk);
  for (const x of all) {
    const once = MB.stepName(x);
    assert.equal(typeof once, 'string', String(x) + ' must normalise to a string');
    assert.equal(MB.stepName(once), once, String(x) + ' is not idempotent under stepName');
  }
  assert.equal(MB.stepName(null), '');
  assert.equal(MB.stepName(undefined), '');
});

/* PROPERTY 7 · INV-12 and INV-19, over every kind AND over the degenerate
 * traces. stopFirst unconditional is what keeps the previous journey's balls
 * from flying under a panel describing a different transaction, and a decision
 * not to play must always carry a reason a visitor can read. */
test('PROPERTY · every play decision tears down first, and every refusal says why', () => {
  const traces = MB.SERVER_KINDS.map(k => propStep(k.probe, 'eu'))
    .concat([[], [{ step: 'teleport', region: 'eu' }], [{ step: 'transfer', region: 'mars' }]]);
  for (const steps of traces) {
    for (const auto of [true, false]) {
      const d = MB.playDecision({ steps, graph: PROP_GRAPH, auto });
      assert.equal(d.stopFirst, true, 'teardown is unconditional, always');
      if (!d.canDraw) {
        assert.ok(d.reason && d.reason.length > 10,
          'an undrawable transaction must state a reason, never be silent');
        assert.equal(d.play, false);
      } else {
        assert.equal(d.reason, '', 'a drawable transaction has nothing to apologise for');
        assert.equal(d.play, auto);
      }
    }
  }
});

/* PROPERTY 8 · INV-13, over the whole truth table rather than the three
 * combinations somebody thought of. A control is enabled exactly when it can
 * act, and a disabled one always carries the reason in its title. */
test('PROPERTY · a control is enabled iff it can act, and disabled always says why', () => {
  for (const hasSelection of [true, false]) {
    for (const canDraw of [true, false]) {
      for (const following of [true, false]) {
        const st = MB.controlState({ hasSelection, canDraw, following });
        const should = !following && hasSelection && canDraw;
        assert.equal(st.replay, should, JSON.stringify({ hasSelection, canDraw, following }));
        assert.equal(st.loop, st.replay, 'replay and loop are enabled together');
        if (!st.replay) assert.ok(st.reason.length > 10, 'a dead control must say why it is dead');
        else assert.equal(st.reason, '');
      }
    }
  }
});

/* PROPERTY 9 · INV-04 and INV-09, as truth tables. A background poll must never
 * outrank a chosen transaction, and a tab nobody is looking at must never be
 * animated onto nor owed a backlog. */
test('PROPERTY · a hidden tab is never animated onto and is never owed a backlog', () => {
  for (const running of [true, false]) {
    for (const hasSelection of [true, false]) {
      const p = MB.liveEventPolicy({ tabVisible: false, running, hasSelection });
      assert.deepEqual(p, { animate: false, select: false, keep: false },
        'hidden means nothing animates and nothing is queued for later');
    }
  }
});

test('PROPERTY · only the idle, visible, unselected map auto-plays live traffic', () => {
  for (const tabVisible of [true, false]) {
    for (const running of [true, false]) {
      for (const hasSelection of [true, false]) {
        const p = MB.liveEventPolicy({ tabVisible, running, hasSelection });
        assert.equal(p.animate, tabVisible && !running && !hasSelection,
          JSON.stringify({ tabVisible, running, hasSelection }));
        assert.equal(p.select, p.animate, 'what it animates is what it selects');
        if (p.animate) assert.equal(p.keep, true, 'what it may animate it may also queue');
      }
    }
  }
});

test('PROPERTY · auto-pick yields to every explicit intent, over the whole table', () => {
  for (const autoPicked of [true, false]) {
    for (const hasSelection of [true, false]) {
      for (const deepLinkTx of [null, 'abc']) {
        for (const following of [null, 'abc']) {
          const p = MB.autoPickPlan({ autoPicked, hasSelection, deepLinkTx, following, rows: 3 });
          const shouldStandDown = autoPicked || hasSelection || !!deepLinkTx || !!following;
          assert.equal(p.pick, !shouldStandDown,
            JSON.stringify({ autoPicked, hasSelection, deepLinkTx, following }));
          if (!p.pick) assert.equal(p.retry, false, 'standing down is not retrying');
        }
      }
    }
  }
});

/* PROPERTY 10 · a follow is the most explicit intent this page has, so nothing
 * automatic may take the tab, the controls or the map away from it. This is the
 * whole shape of the worst bug of the week, stated as a rule rather than as the
 * one sequence that reproduced it. */
test('PROPERTY · a follow in flight outranks a standing loop and auto-pick, in every state', () => {
  for (const hasSelection of [true, false]) {
    for (const loopArmed of [true, false]) {
      const p = MB.tabEnterPlan({ hasSelection, loopArmed, following: true });
      assert.deepEqual(p, { play: false, autoPick: false },
        JSON.stringify({ hasSelection, loopArmed }) +
        ': entering the tab a follow just opened must not replay the PREVIOUS transaction');
      const c = MB.controlState({ hasSelection, canDraw: true, following: true });
      assert.equal(c.replay, false, 'and replay must not be armed on a panel with nothing in it yet');
    }
  }
});

/* PROPERTY 11 · a REALISTIC trace, per kind, rather than a single step. This is
 * what the follow and the replay actually receive: the product write plus
 * whatever the outbox and the notifier added to it. The mortgage bug lived
 * exactly here · one step drew nothing while its siblings drew fine. */
test('PROPERTY · a kind travelling with its outbox siblings still draws its own origin', () => {
  const siblings = [{ step: 'published', region: 'eu', ts: '2024-01-01T00:00:00.200Z' },
                    { step: 'notify', region: 'eu', ts: '2024-01-01T00:00:00.300Z' }];
  for (const k of MB.SERVER_KINDS) {
    if (MB.UNWIRED_KINDS[MB.stepName(k.probe)]) continue;
    if (/^(published|notify|notified)/.test(MB.stepName(k.probe))) continue;
    const j = MB.journey(propStep(k.probe, 'eu').concat(siblings), PROP_GRAPH);
    const own = j.hops.filter(h => h.step === MB.stepName(k.probe));
    assert.ok(own.length > 0,
      k.probe + ' contributes no hop of its own while its siblings do · the map would ' +
      'animate a publish leaving a box the money was never shown reaching');
    // and every box a later hop departs from is a box some earlier hop reached
    const reached = new Set([j.hops[0].from]);
    for (const h of j.hops) {
      assert.ok(reached.has(h.from),
        k.probe + ': a ball departs ' + h.from + ', which nothing in this journey ever reached');
      reached.add(h.to);
    }
  }
});

/* ==========================================================================
 * REGRESSIONS · one per confirmed violation, each stating the specific lie.
 * ======================================================================== */

test('a loan disbursement draws the walk it actually made', () => {
  // It had no FLOW key, so journey() dropped it into unknown[] and drew nothing
  // · while the outbox row Products.mortgage writes under the SAME txId gave the
  // trace a `published` step that DID draw. The visitor saw money springing out
  // of a Postgres box on a journey in which nothing had arrived there.
  const j = MB.journey([{ step: 'mortgage', region: 'eu', ts: '2024-01-01T00:00:00.000Z' },
                        { step: 'published', region: 'eu', ts: '2024-01-01T00:00:00.100Z' }],
                       PROP_GRAPH);
  assert.deepEqual(j.unknown, [], 'mortgage is a kind this map understands');
  assert.deepEqual(j.hops.filter(h => h.step === 'mortgage').map(h => h.edge),
    ['browser_caddy', 'caddy_api', 'api_shard0'],
    'the loan walks the local route, like every other single-commit product write');
  assert.equal(j.hops[0].from, 'browser', 'and the journey starts where the click did');
});

test('a relocation leg is discoverable, even though no entry carries the customer name', () => {
  // Every relocate leg's two entries are (shelf account, in_transit), and shelf
  // accounts are owned by their LABEL · "savings", "card" · never by the
  // customer. So the owner gate rejected all of them, discoverTx returned null
  // on every poll, and the visitor was told the read model had not caught up
  // about a read model that was perfectly current.
  const feed = [
    { tx: 'leg1', type: 'relocate:depart', payer: 'savings', payee: 'in_transit', ts: '2024-01-01T00:00:01Z' },
    { tx: 'leg2', type: 'relocate:arrive', payer: 'in_transit', payee: 'card', ts: '2024-01-01T00:00:02Z' },
  ];
  assert.equal(MB.discoverTx(feed, { kinds: ['relocate'], owner: 'anna', known: [] }), null,
    'the premise: the plain owner gate can never match a relocation leg');
  assert.equal(
    MB.discoverTx(feed, { kinds: ['relocate'], owner: 'anna', known: [],
                          ownerOptional: ['relocate'] }),
    'leg2', 'declared unattributable, so the newest new leg is the one to watch');
});

test('a settlement is discoverable by a customer whose name sorts below "broker"', () => {
  /* settleFill writes four entries and the feed reduces each side with max(),
     so the payee is always "broker" and the payer is max(customer, "broker").
     Signup accepts a plain lowercase name, so anna, alice and bob are all
     silently unfollowable · invisible only because the seeded cast (igor, coco,
     oscar) happens to sort above "broker". */
  const feed = [{ tx: 'fill1', type: 'settle:aapl:buy', payer: 'broker', payee: 'broker',
                  ts: '2024-01-01T00:00:01Z' }];
  assert.equal(MB.discoverTx(feed, { kinds: ['settle'], owner: 'anna', known: [] }), null,
    'the premise: max() over the entries erased the customer');
  assert.equal(MB.discoverTx(feed, { kinds: ['settle'], owner: 'anna', known: [],
                                     ownerOptional: ['settle'] }), 'fill1');
  // and the gate is not simply gone: an event that WAS in the feed before the
  // visitor acted is still never followed
  assert.equal(MB.discoverTx(feed, { kinds: ['settle'], owner: 'anna', known: ['fill1'],
                                     ownerOptional: ['settle'] }), null);
});

test("another customer's payment is still never followed", () => {
  // The relaxation is per-kind and must not leak into the kinds the server CAN
  // attribute · a plain transfer between two other people stays invisible.
  const feed = [{ tx: 't1', type: 'transfer_local', payer: 'coco', payee: 'oscar',
                  ts: '2024-01-01T00:00:01Z' }];
  assert.equal(MB.discoverTx(feed, { kinds: ['transfer'], owner: 'anna', known: [],
                                     ownerOptional: ['relocate', 'settle'] }), null);
});

test('entering the x-ray tab a follow just opened replays nothing', () => {
  /* followStart clicks the tab button, which runs tabEnterPlan. stopPlay does
     not clear activeTrace, so with loop left armed the plan said "play" and
     replayed the PREVIOUS transaction · whose playTrace called stopPlay ->
     cancelFollow, killing the follow the visitor was waiting on. The panel then
     read "your payment, live" over a map looping a stranger, permanently. */
  assert.deepEqual(MB.tabEnterPlan({ hasSelection: true, loopArmed: true, following: 'abc' }),
    { play: false, autoPick: false });
  // and with no follow the standing loop request still works exactly as before
  assert.deepEqual(MB.tabEnterPlan({ hasSelection: true, loopArmed: true }),
    { play: true, autoPick: false });
});

test('replay and loop are dead while a follow is still looking for its transaction', () => {
  /* followStart repaints the card to describe a transaction that does not exist
     yet while activeTrace still holds the previous one, so for ~9 seconds of
     widening polls the buttons kept the enabled state that selection left them
     in. Pressing replay then animated the old journey under a panel captioned
     "watching it happen…" and, through playTrace -> stopPlay -> cancelFollow,
     permanently killed the wait for the visitor's own transaction. */
  const st = MB.controlState({ hasSelection: true, canDraw: true, following: true });
  assert.equal(st.replay, false);
  assert.equal(st.loop, false);
  assert.match(st.reason, /waiting for the transaction you just caused/);
});

/* ==========================================================================
 * THE LIVE FEED IS RECONCILED, NOT REBUILT.
 *
 * xrayTick used to assign #ticker's innerHTML whenever the top-N signature
 * changed. Every two seconds, on a busy bank, every row the reader was looking
 * at was destroyed and replaced: hover died under the cursor, a click that
 * straddled the repaint landed on nothing, the scroll position snapped back,
 * and the dim filter a map click had applied evaporated because it lived only
 * in the old HTML. The audit called it out and it was deferred as "means
 * diffing rows rather than replacing wholesale". This is that diff.
 *
 * The DECISION · given the keys on screen and the keys the feed now claims,
 * which rows are inserted, kept and removed, and in what order · is
 * MB.reconcileRows, pure and tested here over every shape of feed change. The
 * page's renderTicker just executes the plan, and it is lifted and driven
 * below so that reintroducing the wholesale rebuild fails on behaviour, not on
 * a source grep.
 * ======================================================================== */
const RECON = [
  { name: 'identical feed · nothing moves',
    old: ['a', 'b', 'c'], now: ['a', 'b', 'c'],
    rows: [['a', 'keep'], ['b', 'keep'], ['c', 'keep']], removed: [], changed: false },
  { name: 'first paint · an empty list fills up',
    old: [], now: ['a', 'b'],
    rows: [['a', 'insert'], ['b', 'insert']], removed: [], changed: true },
  { name: 'one event lands on top',
    old: ['a', 'b', 'c'], now: ['x', 'a', 'b', 'c'],
    rows: [['x', 'insert'], ['a', 'keep'], ['b', 'keep'], ['c', 'keep']], removed: [], changed: true },
  { name: 'the window slides · newest in on top, oldest out at the bottom',
    old: ['a', 'b', 'c'], now: ['x', 'a', 'b'],
    rows: [['x', 'insert'], ['a', 'keep'], ['b', 'keep']], removed: ['c'], changed: true },
  { name: 'a burst · three in, three out, one survivor',
    old: ['a', 'b', 'c', 'd'], now: ['x', 'y', 'z', 'a'],
    rows: [['x', 'insert'], ['y', 'insert'], ['z', 'insert'], ['a', 'keep']],
    removed: ['b', 'c', 'd'], changed: true },
  { name: 'complete turnover · a reader away long enough gets a fresh list',
    old: ['a', 'b'], now: ['x', 'y'],
    rows: [['x', 'insert'], ['y', 'insert']], removed: ['a', 'b'], changed: true },
  { name: 'the feed empties',
    old: ['a', 'b'], now: [],
    rows: [], removed: ['a', 'b'], changed: true },
  { name: 'both empty · nothing owes anybody a repaint',
    old: [], now: [], rows: [], removed: [], changed: false },
  { name: 'reorder only · every row survives, in the order the feed now claims',
    old: ['a', 'b', 'c'], now: ['c', 'a', 'b'],
    rows: [['c', 'keep'], ['a', 'keep'], ['b', 'keep']], removed: [], changed: true },
];

test('reconcileRows decides insert, keep and remove for every shape of feed change', () => {
  for (const c of RECON) {
    const p = MB.reconcileRows(c.old, c.now);
    assert.deepEqual(p.rows.map(r => [r.key, r.act]), c.rows, c.name);
    assert.deepEqual(p.removed, c.removed, c.name + ': removed');
    assert.equal(p.changed, c.changed, c.name + ': changed');
  }
});

test('reconcileRows accounts for every key exactly once, across the whole table', () => {
  for (const c of RECON) {
    const p = MB.reconcileRows(c.old, c.now);
    assert.deepEqual(p.rows.map(r => r.key), c.now,
      c.name + ': the plan IS the new order · nothing more, nothing less');
    const kept = p.rows.filter(r => r.act === 'keep').map(r => r.key);
    assert.deepEqual(kept.concat(p.removed).sort(), c.old.slice().sort(),
      c.name + ': every old row is either kept or removed, never both, never neither');
    for (const k of p.removed) assert.ok(!c.now.includes(k),
      c.name + ': a removed key must not be in the new feed');
    for (const r of p.rows) assert.equal(r.act === 'keep', c.old.includes(r.key),
      c.name + ': keep means it was on screen, insert means it was not');
  }
});

test('reconcileRows collapses duplicate keys to their first appearance, both sides', () => {
  const p = MB.reconcileRows(['a', 'a', 'b'], ['b', 'b', 'x', 'x']);
  assert.deepEqual(p.rows.map(r => [r.key, r.act]), [['b', 'keep'], ['x', 'insert']]);
  assert.deepEqual(p.removed, ['a']);
});

test('reconcileRows treats null and undefined as empty lists, not a crash', () => {
  assert.deepEqual(MB.reconcileRows(null, undefined), { rows: [], removed: [], changed: false });
  const p = MB.reconcileRows(undefined, ['a']);
  assert.deepEqual(p.rows, [{ key: 'a', act: 'insert' }]);
  assert.equal(p.changed, true);
});

// ------------------------------------ the page's renderTicker, lifted and run
/** A DOM small enough to fake honestly: children, classes, datasets, and a
 *  scrollTop that a wholesale innerHTML rebuild ZEROES · which is exactly the
 *  behaviour of a real scroll container when its content is destroyed. */
class FakeEl {
  constructor(tag) {
    this.tagName = String(tag || 'div').toUpperCase();
    this.nodeType = 1;
    this.children = [];
    this.dataset = {};
    this.parentNode = null;
    this.onclick = null;
    this.scrollTop = 0;
    this._cls = new Set();
    this._html = '';
    const self = this;
    this.classList = {
      add: c => self._cls.add(c),
      remove: c => self._cls.delete(c),
      contains: c => self._cls.has(c),
      toggle: (c, on) => { if (on) self._cls.add(c); else self._cls.delete(c); return self._cls.has(c); },
    };
  }
  get className() { return [...this._cls].join(' '); }
  set className(v) { this._cls = new Set(String(v).split(/\s+/).filter(Boolean)); }
  get innerHTML() { return this._html; }
  set innerHTML(v) {
    this._html = String(v);
    this.children.forEach(c => { c.parentNode = null; });
    this.children.length = 0;
    this.scrollTop = 0;             // the reader's place, gone with the rebuild
  }
  insertBefore(node, ref) {
    if (node.parentNode) node.remove();
    const i = ref ? this.children.indexOf(ref) : this.children.length;
    this.children.splice(i < 0 ? this.children.length : i, 0, node);
    node.parentNode = this;
    return node;
  }
  remove() {
    if (!this.parentNode) return;
    const p = this.parentNode;
    p.children.splice(p.children.indexOf(this), 1);
    this.parentNode = null;
  }
  querySelectorAll(sel) {
    if (sel === '.tick-item[data-key]')
      return this.children.filter(c => c.classList.contains('tick-item') && 'key' in c.dataset);
    return [];
  }
}

function liftTicker() {
  const vm = require('node:vm');
  const ticker = new FakeEl('div');
  const ctx = {
    MB, Date, JSON, console, String, Number,
    document: { createElement: tag => new FakeEl(tag) },
    $: sel => (sel === '#ticker' ? ticker : null),
    tickIds: new Set(), firstPoll: true,
    activeTrace: null, dimTxs: null,
    clicked: [],
    tickClick: j => ctx.clicked.push(JSON.parse(j)),
    iconFor: () => 'ic',
    evLine: e => 'ev ' + e.tx,
  };
  vm.createContext(ctx);
  for (const name of ['tickKey', 'buildTickRow', 'renderTicker']) {
    const start = INDEX.indexOf('function ' + name + '(');
    assert.ok(start > 0, name + ' must exist as a top level function in the page');
    const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
    assert.ok(close, name + ' must be top level to be liftable');
    vm.runInContext(INDEX.slice(start, start + close.index + close[0].length), ctx);
  }
  ctx.ticker = ticker;
  /* Exactly what xrayTick does around the call: paint first, THEN remember the
     keys as seen and drop the first-poll flag. The order matters · `new` is
     "not seen before this paint". */
  ctx.paint = top => {
    ctx.top = top;
    vm.runInContext('renderTicker(top)', ctx);
    top.forEach(e => ctx.tickIds.add(e.type + e.tx + e.ts));
    ctx.firstPoll = false;
  };
  return ctx;
}

const tev = (tx, ts) => ({ type: 'transfer_local', tx, ts: ts || '2026-07-20T10:00:00Z',
                           region: 'eu', payer: 'igor', payee: 'oscar', amount: '5.00' });

test('the ticker KEEPS the reader\'s rows · same nodes, same scroll, new rows on top', () => {
  const ctx = liftTicker();
  ctx.paint([tev('a'), tev('b'), tev('c')]);
  assert.equal(ctx.ticker.children.length, 3, 'three rows drawn');
  const [rowA, rowB, rowC] = ctx.ticker.children;
  assert.ok(!rowA.classList.contains('new'), 'the first paint animates nothing · it is history, not news');

  ctx.ticker.scrollTop = 120;                       // the reader is mid-list
  ctx.paint([tev('x'), tev('a'), tev('b')]);        // one in on top, one out at the bottom

  assert.deepEqual(ctx.ticker.children.map(el => el.dataset.tx), ['x', 'a', 'b']);
  assert.equal(ctx.ticker.children[1], rowA,
    'the row the reader was looking at is the SAME node, not a lookalike · ' +
    'a wholesale innerHTML rebuild fails exactly here');
  assert.equal(ctx.ticker.children[2], rowB, 'and so is its neighbour');
  assert.equal(rowC.parentNode, null, 'the row that left the window is gone');
  assert.equal(ctx.ticker.scrollTop, 120,
    'and the scroll position survives the repaint · the rebuild zeroed it');
  assert.ok(ctx.ticker.children[0].classList.contains('new'),
    'only the genuinely new row gets the entry animation');
  assert.ok(!rowA.classList.contains('new'), 'a kept row must not slide in again');

  // an unchanged feed leaves everything exactly where it is
  const before = ctx.ticker.children.slice();
  ctx.paint([tev('x'), tev('a'), tev('b')]);
  assert.deepEqual(ctx.ticker.children, before, 'no change means no DOM churn at all');
});

test('picked and dim are DERIVED from state on every paint, never inherited from old HTML', () => {
  const ctx = liftTicker();
  ctx.paint([tev('a'), tev('b'), tev('c')]);

  // a map click filtered to b and c, and a trace of b is open
  ctx.activeTrace = { tx: 'b' };
  ctx.dimTxs = ['b', 'c'];
  ctx.paint([tev('x'), tev('a'), tev('b'), tev('c')]);
  const cls = Object.fromEntries(ctx.ticker.children.map(el =>
    [el.dataset.tx, { picked: el.classList.contains('picked'), dim: el.classList.contains('dim') }]));
  assert.deepEqual(cls, {
    x: { picked: false, dim: true },   // NEW row outside the filter dims too ·
    a: { picked: false, dim: true },   // the old rebuild forgot the filter here
    b: { picked: true, dim: false },
    c: { picked: false, dim: false },
  });

  // the filter ends and the selection moves · same feed, classes follow STATE
  ctx.dimTxs = null;
  ctx.activeTrace = { tx: 'a' };
  ctx.paint([tev('x'), tev('a'), tev('b'), tev('c')]);
  for (const el of ctx.ticker.children) {
    assert.equal(el.classList.contains('dim'), false, el.dataset.tx + ' must shed the dim');
    assert.equal(el.classList.contains('picked'), el.dataset.tx === 'a');
  }
});

test('a reconciled row still opens the transaction it describes', () => {
  const ctx = liftTicker();
  const a = tev('a');
  ctx.paint([a, tev('b')]);
  ctx.paint([tev('x'), a, tev('b')]);       // survive one reconcile first
  ctx.ticker.children[1].onclick();
  assert.equal(ctx.clicked.length, 1);
  assert.deepEqual(ctx.clicked[0], a,
    'the kept node must hand tickClick the event it was built for');
});

test('an emptied feed states the quiet rather than stranding stale rows', () => {
  const ctx = liftTicker();
  ctx.paint([tev('a')]);
  ctx.paint([]);
  assert.equal(ctx.ticker.children.length, 0);
  assert.match(ctx.ticker.innerHTML, /quiet/, 'the placeholder names the state');
});

test('the live poller reconciles the ticker rather than rebuilding it', () => {
  /* The structural half, narrow on purpose, like the drainLiveQueue wiring
     check above: the behaviour tests drive renderTicker itself, and the one
     thing they cannot see is whether xrayTick still CALLS it. */
  const i = INDEX.indexOf('async function xrayTick()');
  const j = INDEX.indexOf('window.tickClick', i);
  assert.ok(i > 0 && j > i, 'xrayTick must still be findable in the page');
  const tick = INDEX.slice(i, j);
  assert.ok(/renderTicker\(/.test(tick), 'xrayTick must hand the feed to the reconciler');
  assert.ok(!/\$\('#ticker'\)\.innerHTML\s*=/.test(tick),
    'and must not assign the list wholesale · that is the rebuild that dropped the reader');
});

/* ==========================================================================
 * THE SELECTED TRANSACTION'S PANEL FOLLOWS ITS TRANSACTION.
 *
 * Once a trace was selected, nothing ever re-read /api/xray/trace for it: a
 * payment selected mid-flight kept showing its half-finished step list
 * forever (audit trigger 15, "no code path re-reads"). The decision of
 * whether this tick should re-fetch is MB.traceRefreshPlan, and it reuses
 * followPlan's completeness reading · traceOwing's owed/settled, plus the
 * "stopped growing" confirmation · rather than inventing a second one.
 *
 *   fetch  { fetch: true,  stop: false }  the trace may still be moving, look
 *   skip   { fetch: false, stop: false }  not now: hidden tab, running replay,
 *                                         or a follow already owns the polling
 *   stop   { fetch: false, stop: true }   terminal · nothing more is coming
 * ======================================================================== */
const trp = o => MB.traceRefreshPlan(o);
const MIDFLIGHT = [
  { step: 'depart', region: 'eu', ts: '2026-07-20T10:00:00.000Z' },
  { step: 'published', region: 'eu', ts: '2026-07-20T10:00:00.050Z' },
  { step: 'notify', region: 'notifications', ts: '2026-07-20T10:00:00.120Z' },
];

test('a half-arrived selection keeps being fetched · mid-flight means look again', () => {
  const p = trp({ steps: [LOCAL_DONE[0]], tabVisible: true, playing: false, unchanged: 0 });
  assert.equal(p.fetch, true, 'one step on a fresh selection is not evidence that nothing more is coming');
  assert.equal(p.stop, false);
});

test('notify alone is not settlement · money still in the pipe keeps the panel live', () => {
  // the departed notification arrived, the money has not · the naive reading
  // "notify present means done" would freeze the panel exactly mid-flight
  const p = trp({ steps: MIDFLIGHT, tabVisible: true, playing: false, unchanged: 0 });
  assert.equal(p.fetch, true, 'depart without arrive is a saga still moving');
  assert.equal(p.stop, false);
});

test('a settled trace stops the refresh before a single fetch', () => {
  const p = trp({ steps: LOCAL_DONE, tabVisible: true, playing: false, unchanged: 0 });
  assert.equal(p.stop, true, 'notified and landed · a replayed old payment is never re-fetched at all');
  assert.equal(p.fetch, false);
  const bounce = CASES.find(c => c.name === 'bounced saga, the refund path');
  assert.equal(trp({ steps: bounce.steps, tabVisible: true, playing: false, unchanged: 0 }).stop, true,
    'the refund landing IS the arrival · a completed bounce owes nothing');
});

test('a refused settlement is the end of its journey', () => {
  const p = trp({ steps: [{ step: 'settle-refused', region: 'eu' }],
                  tabVisible: true, playing: false, unchanged: 0 });
  assert.equal(p.stop, true, 'the compensation is terminal · there is nothing left to wait for');
  assert.equal(p.fetch, false);
});

test('a hidden tab skips without abandoning the watch', () => {
  const p = trp({ steps: [LOCAL_DONE[0]], tabVisible: false, playing: false, unchanged: 0 });
  assert.equal(p.fetch, false, 'nobody is looking · fetching for a hidden panel is waste');
  assert.equal(p.stop, false, 'but the trace is still mid-flight · resume when the tab returns');
});

test('a running replay owns the panel · the refresh waits its turn', () => {
  const p = trp({ steps: [LOCAL_DONE[0]], tabVisible: true, playing: true, unchanged: 0 });
  assert.equal(p.fetch, false, 'repainting the step list mid-narration steals the replay');
  assert.equal(p.stop, false, 'the next tick after it finishes catches up');
});

test('a pending follow owns the polling · the refresher stands down entirely', () => {
  const p = trp({ steps: [LOCAL_DONE[0]], tabVisible: true, playing: false,
                  unchanged: 0, following: true });
  assert.equal(p.fetch, false, 'followPoll is already watching this panel · two pollers race');
  assert.equal(p.stop, false);
});

test('a quiet trace is confirmed exactly once, then left alone', () => {
  // the card hold and the savings move write no outbox row, so they are never
  // "settled" in the notified sense · followPlan's stable rule, reused
  const one = [{ step: 'transfer', region: 'eu' }];
  assert.equal(trp({ steps: one, tabVisible: true, playing: false, unchanged: 0 }).fetch, true,
    'the first look is owed · the selection may have raced the outbox');
  const p = trp({ steps: one, tabVisible: true, playing: false, unchanged: 1 });
  assert.equal(p.stop, true, 'a second identical answer · it owes nothing and stopped growing');
  assert.equal(p.fetch, false);
});

test('a trace that owes a step is watched past one identical answer, but not forever', () => {
  // a live trade publishes to settlements and never notifies, so traceOwing
  // calls it owed for good · the budget is what stops the polling, exactly as
  // maxAttempts is what stops followPlan
  const trade = [{ step: 'settle:aapl:buy', region: 'eu' },
                 { step: 'published:settlements', region: 'eu' }];
  for (const n of [1, 2, 3, 4]) {
    assert.equal(trp({ steps: trade, tabVisible: true, playing: false, unchanged: n }).fetch, true,
      'unchanged x' + n + ': a declared owing outranks "it stopped growing"');
  }
  const p = trp({ steps: trade, tabVisible: true, playing: false, unchanged: 5 });
  assert.equal(p.stop, true, 'five identical answers is parked, not pending · stop politely');
  assert.equal(trp({ steps: trade, tabVisible: true, playing: false,
                     unchanged: 2, maxUnchanged: 2 }).stop, true, 'and the budget is a parameter');
});

test('an empty or absent selection has nothing to watch', () => {
  assert.equal(trp({ steps: [], tabVisible: true, playing: false, unchanged: 0 }).stop, true);
  assert.equal(trp({}).stop, true);
});

test('PROPERTY · the refresh plan is coherent over the whole cartesian table', () => {
  for (const c of CASES) {
    // what the plan says on a visible, idle tab is its verdict on the TRACE ·
    // visibility and animation may only delay it, never reverse it
    const base = trp({ steps: c.steps, tabVisible: true, playing: false, unchanged: 0 });
    for (const tabVisible of [true, false]) {
      for (const playing of [true, false]) {
        for (const unchanged of [0, 1, 5]) {
          const p = trp({ steps: c.steps, tabVisible, playing, unchanged });
          const label = c.name + ' · ' + JSON.stringify({ tabVisible, playing, unchanged });
          assert.ok(!(p.fetch && p.stop), label + ': fetch and stop are opposite verdicts');
          if (p.fetch) assert.ok(tabVisible && !playing,
            label + ': a fetch is only owed to a visible, unclaimed panel');
          if (base.stop) assert.equal(p.stop, true,
            label + ': terminal on an idle tab is terminal everywhere');
        }
      }
    }
  }
});

// -------------------------------- the page's refresher tick, lifted and run
function liftRefresh(replies) {
  const vm = require('node:vm');
  const ctx = {
    MB, Date, JSON, console, encodeURIComponent, String, Number, Math,
    MAP: MB.mapGraph(MB.MAP_PAIRS),
    activeTrace: null, following: null, traceWatch: null, playingUntil: 0,
    visible: true,
    polled: [], painted: [], planned: [], replayable: [], played: [], stopped: 0,
    xrayVisible: () => ctx.visible,
    paintTracePanel: (tr, tx) => { ctx.painted.push(tx); return 0; },
    markPlanned: j => ctx.planned.push(j.hops.length),
    setReplayable: can => ctx.replayable.push(can),
    playTrace: tr => ctx.played.push(tr.tx),
    stopPlay: () => { ctx.stopped++; },
    api: async path => {
      const tx = decodeURIComponent(path.slice(path.indexOf('tx=') + 3));
      ctx.polled.push(tx);
      const r = replies[tx];
      if (typeof r === 'function') return r();
      const nth = ctx.polled.filter(t => t === tx).length - 1;
      return { tx, steps: r[Math.min(nth, r.length - 1)] };
    },
  };
  vm.createContext(ctx);
  const start = INDEX.indexOf('async function traceRefreshTick()');
  assert.ok(start > 0, 'traceRefreshTick must exist as a top level function in the page');
  const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
  assert.ok(close, 'traceRefreshTick must be top level to be liftable');
  vm.runInContext(INDEX.slice(start, start + close.index + close[0].length), ctx);
  ctx.tick = () => vm.runInContext('traceRefreshTick()', ctx);
  return ctx;
}

test('the refresher completes a mid-flight panel without restarting the animation', async () => {
  const ctx = liftRefresh({ t1: [LOCAL_DONE] });
  ctx.activeTrace = { tx: 't1', steps: [LOCAL_DONE[0]] };   // selected on the commit alone
  ctx.traceWatch = { tx: 't1', unchanged: 0 };

  await ctx.tick();
  assert.deepEqual(ctx.polled, ['t1'], 'the panel is re-read on the tick');
  assert.deepEqual(ctx.painted, ['t1'], 'the grown step list is re-rendered');
  assert.equal(ctx.activeTrace.steps.length, 3, 'the selection now holds the full trace');
  assert.equal(ctx.planned.length, 1, 'the route is extended for the new steps');
  assert.ok(ctx.planned[0] > 0, 'and the extension is a real journey, not an empty one');
  assert.deepEqual(ctx.replayable, [true], 'the controls re-evaluate against the grown trace');
  assert.deepEqual(ctx.played, [], 'the animation is NOT restarted · the map is not stolen');
  assert.equal(ctx.stopped, 0, 'and nothing running is torn down');
  assert.equal(ctx.playingUntil, 0, 'the refresher never claims the map for itself');

  await ctx.tick();
  assert.deepEqual(ctx.polled, ['t1'], 'settled now · the very next tick stops fetching');
  await ctx.tick();
  assert.deepEqual(ctx.polled, ['t1'], 'and it stays stopped');
});

test('a replayed quiet trace is confirmed once at page level, then never again', async () => {
  const one = [{ step: 'transfer', region: 'eu', ts: '2026-07-20T10:00:00.000Z', detail: 'x' }];
  const ctx = liftRefresh({ old: [one] });
  ctx.activeTrace = { tx: 'old', steps: one };
  ctx.traceWatch = { tx: 'old', unchanged: 0 };

  await ctx.tick();
  assert.deepEqual(ctx.polled, ['old'], 'one confirming look');
  assert.deepEqual(ctx.painted, [], 'nothing grew, so nothing repaints');
  await ctx.tick();
  await ctx.tick();
  assert.deepEqual(ctx.polled, ['old'], 'confirmed terminal · never re-fetched again');
});

test('the refresher yields: hidden tab, running replay, pending follow', async () => {
  const ctx = liftRefresh({ t: [LOCAL_DONE] });
  ctx.activeTrace = { tx: 't', steps: [LOCAL_DONE[0]] };
  ctx.traceWatch = { tx: 't', unchanged: 0 };

  ctx.visible = false;
  await ctx.tick();
  assert.deepEqual(ctx.polled, [], 'a hidden tab is not watched · nothing is fetched');

  ctx.visible = true;
  ctx.playingUntil = Date.now() + 60000;
  await ctx.tick();
  assert.deepEqual(ctx.polled, [], 'a running replay owns the panel · the refresh waits');

  ctx.playingUntil = 0;
  ctx.following = 'something';
  await ctx.tick();
  assert.deepEqual(ctx.polled, [], 'a follow in flight already polls this panel');

  ctx.following = null;
  await ctx.tick();
  assert.deepEqual(ctx.polled, ['t'], 'and the moment nothing outranks it, it looks');
});

test('a selection made mid-fetch discards the stale answer', async () => {
  const ctx = liftRefresh({
    t: () => { ctx.activeTrace = { tx: 'other', steps: [LOCAL_DONE[0]] };
               return { tx: 't', steps: LOCAL_DONE }; },
  });
  ctx.activeTrace = { tx: 't', steps: [LOCAL_DONE[0]] };
  ctx.traceWatch = { tx: 't', unchanged: 0 };
  await ctx.tick();
  assert.deepEqual(ctx.painted, [], 'the answer describes a panel that is no longer open');
  assert.equal(ctx.activeTrace.tx, 'other', 'the newer selection is untouched');
  assert.equal(ctx.activeTrace.steps.length, 1, 'and its steps are not overwritten by the stale trace');
});

test('the 2s cadence drives the refresher', () => {
  /* Structural and narrow, like the xrayTick wiring check: the behaviour tests
     drive traceRefreshTick itself, and the one thing they cannot see is
     whether the page's interval still calls it. */
  const line = INDEX.split(/\r?\n/).find(l => l.includes('setInterval') && l.includes('xrayTick'));
  assert.ok(line, 'the 2s interval must still be findable in the page');
  assert.ok(line.includes('traceRefreshTick'),
    'the interval must tick the selected panel refresher alongside the feed poller');
});

/* ==========================================================================
 * CLICKING A LINE ON THE MAP.
 *
 * The audit found the boxes clickable and the pipes between them inert: no
 * handler on any .edge element, and a hairline is an impossible click target
 * anyway. The page now lays an invisible wide hit-line over every mapped edge
 * and routes the click through the SAME machinery a node click uses: the
 * transactions whose journeys traverse that edge, then MB.mapClickPlan · one
 * match traces it, several filter the ticker, none explains the component the
 * pipe feeds. The traversal question is MB.edgeTxs, pure and tested here.
 * ======================================================================== */
const EDGE_GRAPH = MB.mapGraph(MB.MAP_PAIRS);
const eit = (tx, step, region) => ({ tx, step: { step, region: region || 'eu' } });

test('edgeTxs: one transaction traversing the pipe is found by its journey', () => {
  const items = [eit('t1', 'transfer', 'eu'), eit('t2', 'transfer', 'uk')];
  assert.deepEqual(MB.edgeTxs('api_shard0', items, EDGE_GRAPH), ['t1'],
    'the eu commit walks api -> shard0 · the uk one walks the other pillar');
  assert.deepEqual(MB.edgeTxs('api_shard1', items, EDGE_GRAPH), ['t2']);
});

test('edgeTxs: several traversing transactions all answer, once each', () => {
  const items = [eit('t1', 'transfer', 'eu'), eit('t2', 'transfer', 'uk'),
                 eit('t3', 'settle:aapl:buy', 'eu'), eit('t1', 'transfer', 'eu')];
  assert.deepEqual(MB.edgeTxs('api_shard0', items, EDGE_GRAPH), ['t1', 't3'],
    'the settle loop crosses api_shard0 too, and the duplicate t1 collapses');
  assert.deepEqual(MB.edgeTxs('ksettle_brokercons', items, EDGE_GRAPH), ['t3'],
    'the broker leg belongs to the trade alone');
});

test('edgeTxs: a pipe nothing recent travelled answers empty, and junk answers empty', () => {
  const items = [eit('t1', 'transfer', 'eu')];
  assert.deepEqual(MB.edgeTxs('kafka_notif', items, EDGE_GRAPH), [],
    'a bare commit never reaches the notification pipe');
  assert.deepEqual(MB.edgeTxs('no_such_edge', items, EDGE_GRAPH), []);
  assert.deepEqual(MB.edgeTxs('api_shard0', [], EDGE_GRAPH), []);
  assert.deepEqual(MB.edgeTxs('api_shard0', null, EDGE_GRAPH), []);
});

test('edgeTxs feeds mapClickPlan the same way a node click does', () => {
  // the whole point of reuse: one tx is a play, several are a filter, none inspects
  const one = MB.mapClickPlan({ txs: MB.edgeTxs('api_shard1', [eit('t2', 'transfer', 'uk')], EDGE_GRAPH) });
  assert.equal(one.mode, 'play');
  assert.equal(one.tx, 't2');
  const many = MB.mapClickPlan({ txs: MB.edgeTxs('browser_caddy',
    [eit('a', 'transfer', 'eu'), eit('b', 'transfer', 'uk')], EDGE_GRAPH) });
  assert.equal(many.mode, 'filter');
  assert.deepEqual(many.txs, ['a', 'b']);
  const none = MB.mapClickPlan({ txs: MB.edgeTxs('kafka_notif', [], EDGE_GRAPH) });
  assert.equal(none.mode, 'inspect');
});

// ------------------------------------- the page's edge click, lifted and run
function liftEdgeClick(events) {
  const vm = require('node:vm');
  const rows = events.map(e => { const r = new FakeEl('div'); r.classList.add('tick-item'); r.dataset.tx = e.tx; return r; });
  const ctx = {
    MB, JSON, String, console,
    MAP: MB.mapGraph(MB.MAP_PAIRS),
    latestEvents: events, dimTxs: ['leftover'],   // a stale filter, to prove clearing
    traced: [], shown: [],
    traceTx: tx => { ctx.traced.push(tx); return { catch: () => {} }; },
    gShow: k => ctx.shown.push(k),
    document: {
      querySelectorAll: sel => {
        if (sel === '#ticker .tick-item') return rows;
        if (sel === '#ticker .tick-item.dim') return rows.filter(r => r.classList.contains('dim'));
        return [];
      },
    },
  };
  vm.createContext(ctx);
  for (const name of ['eventToStep', 'edgeItems', 'edgeClick']) {
    const start = INDEX.indexOf('function ' + name + '(');
    assert.ok(start > 0, name + ' must exist as a top level function in the page');
    const close = /\r?\n\}\r?\n/.exec(INDEX.slice(start));
    assert.ok(close, name + ' must be top level to be liftable');
    vm.runInContext(INDEX.slice(start, start + close.index + close[0].length), ctx);
  }
  ctx.rows = rows;
  ctx.click = name => { ctx.name = name; vm.runInContext('edgeClick(name)', ctx); };
  return ctx;
}

const FEED3 = [
  { type: 'transfer_local', tx: 't1', shard: 0, ts: '2026-07-20T10:00:02Z', region: 'eu' },
  { type: 'transfer_local', tx: 't2', shard: 1, ts: '2026-07-20T10:00:01Z', region: 'uk' },
  { type: 'transfer_local', tx: 't3', shard: 0, ts: '2026-07-20T10:00:00Z', region: 'eu' },
];

test('clicking a pipe one transaction travelled traces that transaction', () => {
  const ctx = liftEdgeClick(FEED3);
  ctx.click('api_shard1');
  assert.deepEqual(ctx.traced, ['t2'], 'exactly the uk commit crossed api -> shard1');
});

test('clicking a pipe several transactions travelled filters the ticker, as STATE', () => {
  const ctx = liftEdgeClick(FEED3);
  ctx.click('api_shard0');
  assert.deepEqual(ctx.traced, [], 'two candidates is a filter, never a guess at one');
  assert.deepEqual(ctx.dimTxs, ['t1', 't3'], 'the filter survives repaints because it is state');
  assert.deepEqual(ctx.rows.map(r => [r.dataset.tx, r.classList.contains('dim')]),
    [['t1', false], ['t2', true], ['t3', false]],
    'the rows outside the pipe recede, the travellers stay');
});

test('clicking a quiet pipe explains the component it feeds and ends any stale filter', () => {
  const ctx = liftEdgeClick(FEED3);
  ctx.rows[1].classList.add('dim');            // residue of an earlier filter
  ctx.click('kafka_notif');
  assert.deepEqual(ctx.traced, []);
  assert.deepEqual(ctx.shown, ['notif'], 'the pipe feeds notifications · that is what gets explained');
  assert.equal(ctx.dimTxs, null);
  assert.ok(!ctx.rows[1].classList.contains('dim'), 'the stale dim goes with the stale filter');
});

test('the hit layer is generated at runtime · the drawn map markup is untouched', () => {
  /* The geometry guards above parse the static SVG for data-edge lines. The
     click affordance must not become new markup they would have to be taught
     about, so it is cloned from the visible lines at load. */
  const svg = INDEX.slice(INDEX.indexOf('<svg viewBox="0 0 720 '),
                          INDEX.indexOf('<g id="pulse-layer">'));
  assert.ok(!svg.includes('edge-hit'), 'no hit lines in the source markup');
  assert.ok(INDEX.includes('function armEdgeClicks('),
    'the page must arm the pipes · that is the audit finding');
  assert.ok(/armEdgeClicks\(\);/.test(INDEX), 'and must actually call it');
});

/* ==========================================================================
 * THE CAPTION NAMES THE ROW, NOT A PACE.
 *
 * The replay caption read "step 1/1" under a securities settle whose single
 * step animates nine hops over seconds: the number contradicted what the eye
 * saw crossing the map. The counting unit stays the STEP · it is the unit the
 * panel rows show and highlight · but the caption no longer claims the word,
 * and a one-step trace gets no counter at all, because "1/1" beside nine
 * moving balls is the lie the audit flagged.
 * ======================================================================== */
test('the caption counts panel rows plainly when there are rows to count', () => {
  assert.equal(MB.playCaption({ index: 1, count: 4, detail: 'relayed', region: 'eu' }),
    '2/4 · relayed · eu');
  assert.equal(MB.playCaption({ index: 0, count: 3, detail: 'committed', region: 'uk' }),
    '1/3 · committed · uk');
});

test('a one-step trace gets no counter · "1/1" under nine hops was the lie', () => {
  const c = MB.playCaption({ index: 0, count: 1, detail: 'settled aapl buy', region: 'eu' });
  assert.equal(c, 'settled aapl buy · eu');
  assert.ok(!/1\/1/.test(c), 'no fraction with nothing to count');
  assert.ok(!/step/i.test(c), 'and no claim to be a "step" of the animation');
});

test('the caption never says "step" · the pacing walks hops, not steps', () => {
  for (const o of [{ index: 0, count: 1, detail: 'd', region: 'eu' },
                   { index: 2, count: 6, detail: 'd', region: 'uk' }]) {
    assert.ok(!/step/i.test(MB.playCaption(o)), JSON.stringify(o));
  }
});

test('the player narrates through the shared caption, not an inline template', () => {
  // narrow wiring, same doctrine as the other structural checks
  const i = INDEX.indexOf('function playTrace(');
  const j = INDEX.indexOf('function cancelFollow(');
  assert.ok(i > 0 && j > i, 'playTrace must still be findable in the page');
  const src = INDEX.slice(i, j);
  assert.ok(/MB\.playCaption\(/.test(src), 'the caption text must come from the tested function');
  assert.ok(!src.includes('`step ${'), 'the inline "step i/n" template is the bug · it must be gone');
});
