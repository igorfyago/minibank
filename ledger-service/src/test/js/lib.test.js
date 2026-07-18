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
const GRAPH_PAIRS = [
  ['browser', 'caddy'], ['caddy', 'api'], ['api', 'directory'],
  ['api', 'shard0'], ['api', 'shard1'],
  ['shard0', 'kafka'], ['shard1', 'kafka'],
  ['kafka', 'applier'], ['kafka', 'notif'],
  ['applier', 'shard0'], ['applier', 'shard1'],
];

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
  { name: 'bounced saga, the refund path',
    steps: [{ step: 'depart', region: 'eu' }, { step: 'published', region: 'eu' },
            { step: 'refund', region: 'eu' }] },
  { name: 'a single step on its own',
    steps: [{ step: 'transfer', region: 'eu' }] },
  { name: 'live vocabulary, transfer_local must mean the same as transfer',
    steps: [{ step: 'transfer_local', region: 'eu' }] },
];

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

test('no hop is walked twice, there is no second loop', () => {
  const g = MB.mapGraph(GRAPH_PAIRS);
  for (const c of CASES) {
    const j = MB.journey(c.steps, g);
    const seen = new Set();
    for (const h of j.hops) {
      const k = h.step + ':' + h.from + '>' + h.to;
      assert.ok(!seen.has(k), c.name + ': ' + k + ' is animated twice');
      seen.add(k);
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
  const svg = INDEX.slice(INDEX.indexOf('<svg viewBox="0 0 720 600"'),
                          INDEX.indexOf('<g id="pulse-layer">'));
  return new Set([...svg.matchAll(/data-edge="([a-z0-9_]+)"/g)].map(m => m[1]));
}
function declaredEdges() {
  const block = INDEX.slice(INDEX.indexOf('const MAP_PAIRS'), INDEX.indexOf('const MAP ='));
  return new Set([...block.matchAll(/\['([a-z0-9]+)', '([a-z0-9]+)'\]/g)].map(m => m[1] + '_' + m[2]));
}
function pulsePaths() {
  const i = INDEX.indexOf('const EDGES = {');
  const block = INDEX.slice(i, INDEX.indexOf('};', i));
  return new Set([...block.matchAll(/(\w+):\[/g)].map(m => m[1]));
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
  const svg = INDEX.slice(INDEX.indexOf('<svg viewBox="0 0 720 600"'),
                          INDEX.indexOf('<g id="pulse-layer">'));
  const nodes = new Set([...svg.matchAll(/data-node="([a-z0-9]+)"/g)].map(m => m[1]));
  for (const e of drawnEdges()) {
    const from = [...nodes].filter(n => e.startsWith(n + '_')).sort((a, b) => b.length - a.length)[0];
    const to = [...nodes].filter(n => e.endsWith('_' + n)).sort((a, b) => b.length - a.length)[0];
    assert.ok(from && to, `edge ${e} does not name two real nodes`);
    assert.equal(`${from}_${to}`, e, `edge ${e} does not say what it connects`);
  }
});

test('the live poller cannot animate over a running replay', () => {
  // The regression that produced "an unexpected second loop": the 2s poller
  // called animateEvent with no idea a replay was in progress.
  assert.ok(/Date\.now\(\) > playingUntil/.test(INDEX),
    'the poller must yield while a deliberate animation owns the map');
  assert.ok(!/fresh\.slice\(0, 6\)[\s\S]{0,60}animateEvent/.test(INDEX),
    'the poller must not fan six events onto the map at once');
});
