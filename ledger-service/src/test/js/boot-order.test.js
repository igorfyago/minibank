/**
 * THE FIRST PAINT, RUN FOR REAL.
 *
 * Run with:  node --test src/test/js
 *
 * Why this file exists.
 *
 * The app tab used to take two seconds to fill in. Not because anything was
 * slow · measured on the box, /api/accounts answers in 4ms, /api/portfolio in
 * 7ms, /api/networth in 6ms, /api/card/activity in 19ms · but because of the
 * ORDER the browser asked in. loadAccounts is the only function that resolves
 * `me`, and every other loader opens with `if (me === null) return`. The page
 * fired them side by side at load, so on that first pass the dependent five
 * each ran straight to their early return, and the statement, the portfolio,
 * the wealth card and the card rail all waited for the 2s poller instead.
 *
 * That is a scheduling bug, and scheduling bugs are the kind that come back:
 * the fix is four statements long and any of them can be undone by somebody
 * tidying the tail of the file. So this does not grep index.html. A regex
 * would pass on a page that never boots at all, and this repo has already
 * shipped a green test that matched a string in a skeleton nothing renders.
 * It lifts the page's OWN boot block, the exact text between the BOOT-PURE
 * markers, into a vm, hands it stub loaders that model the one thing that
 * matters · accounts resolves the identity, the rest early-return without it ·
 * and drives it.
 *
 * What is pinned:
 *   · the dependent loaders do not run while the identity is unknown
 *   · they all run once it is known, in the SAME pass, with no tick in between
 *   · they run CONCURRENTLY, not as a waterfall of five round trips
 *   · one boot pass issues exactly one accounts request, not two
 *   · boot is idempotent · a second call stacks no second interval
 *   · a failed identity fetch retries on the next pass instead of leaving the
 *     page permanently blank
 *   · a hidden tab's poller is still skipped, and a visible one still runs
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const BANK_FILE = path.join(__dirname, '..', '..', 'main', 'resources', 'web', 'index.html');
const SRC = fs.readFileSync(BANK_FILE, 'utf8');
const MB = require('../../main/resources/web/lib.js');

/**
 * The page's own boot block, not a transcription of it. Rename a function or
 * delete a marker and this file fails loudly rather than testing a copy that
 * has quietly drifted from what ships.
 */
function bootSource() {
  const start = SRC.indexOf('/* ===================== BOOT-PURE-START');
  const end = SRC.indexOf('/* ====================== BOOT-PURE-END');
  assert.ok(start > -1, 'the BOOT-PURE-START marker is gone from index.html');
  assert.ok(end > start, 'the BOOT-PURE-END marker is gone from index.html');
  return SRC.slice(start, end);
}

/** Let every already-resolved promise in the queue run. */
const settle = async (n = 12) => { for (let i = 0; i < n; i++) await new Promise(r => setImmediate(r)); };

/**
 * A page, stubbed down to the only thing this test is about: who ran, when,
 * and whether `me` was known at the time.
 *
 * `tabs` says which tab is on screen. `accountsFails` makes the first identity
 * fetch reject, the way a 503 from the ledger would. `hold` leaves the
 * dependent loaders pending forever so that "did they all start" can be asked
 * separately from "did they all finish", which is the whole difference between
 * concurrent and a waterfall. `holdAccounts` stalls the identity fetch itself,
 * which is what a fetch with no AbortSignal does on a dead connection: it does
 * not fail, it just never answers. The clock is stubbed so that state can be
 * reached without the test taking ten seconds.
 */
function makePage(opts) {
  const o = opts || {};
  const calls = [];             // every loader call, in order
  const starts = [];            // dependent loaders that have begun
  const releases = [];          // resolvers for the held dependents
  const accountHolds = [];      // resolvers for a stalled identity fetch
  let accountsCalls = 0;
  let clock = 1000;
  const intervals = [];

  const dependent = name => async () => {
    calls.push(name);
    if (ctx.me === null) return;        // the real loaders' early return
    starts.push(name);
    if (o.hold) await new Promise(r => releases.push(r));
    ctx.painted[name] = true;
  };

  const ctx = {
    me: null, MB, console,
    painted: Object.create(null),
    setInterval: (fn, ms) => { intervals.push({ fn, ms }); return intervals.length; },
    clearInterval: id => { intervals[id - 1] = null; },
    $: sel => ({ classList: { contains: cls => {
      assert.equal(cls, 'hidden', 'the tab check still asks about the hidden class');
      const tabs = o.tabs || {};
      if (sel === '#tab-xray') return !tabs.xray;
      if (sel === '#tab-console') return !tabs.console;
      return true;
    } } }),
    Date: { now: () => clock },
    async loadAccounts() {
      accountsCalls++;
      calls.push('accounts');
      if (o.accountsFails && accountsCalls <= (o.failTimes || 1)) throw new Error('503 from the ledger');
      if (o.holdAccounts && accountsCalls <= (o.stallTimes || 1)) {
        await new Promise(r => accountHolds.push(r));   // a fetch that never answers
      }
      await Promise.resolve();
      ctx.me = 10;
      ctx.painted.accounts = true;
    },
    loadStatement: dependent('statement'),
    loadPortfolio: dependent('portfolio'),
    loadNetworth: dependent('networth'),
    loadCardActivity: dependent('cardActivity'),
    renderExt: dependent('ext'),
    async xrayTick() { calls.push('xray'); },
    async traceRefreshTick() { calls.push('traceRefresh'); },
    async kafkaTick() { calls.push('kafka'); },
  };
  ctx.globalThis = ctx;
  vm.createContext(ctx);
  vm.runInContext(bootSource() + '\nglobalThis.boot = boot; globalThis.pollAll = pollAll;',
    ctx, { filename: 'index.html:BOOT-PURE' });

  return {
    ctx, calls, starts, intervals,
    accountsCalls: () => accountsCalls,
    advance: ms => { clock += ms; },
    releaseAll: () => { releases.splice(0).forEach(r => r()); },
    releaseAccounts: () => { accountHolds.splice(0).forEach(r => r()); },
    boot: () => ctx.boot(),
    poll: () => ctx.pollAll(),
    interval: () => { const live = intervals.filter(Boolean); assert.equal(live.length, 1,
      'exactly one poll interval is registered'); return live[0]; },
    painted: name => !!ctx.painted[name],
  };
}

const DEPENDENTS = ['statement', 'portfolio', 'networth', 'cardActivity', 'ext'];

// ------------------------------------------------------------------ the boot

test('boot paints without waiting for a single interval tick', async () => {
  const p = makePage();
  p.boot();
  await settle();
  assert.ok(p.painted('accounts'), 'the balance is there');
  for (const d of DEPENDENTS) assert.ok(p.painted(d), d + ' painted on the boot pass, no tick needed');
});

test('the dependent loaders do not run while the identity is unknown', async () => {
  const p = makePage({ hold: true });
  p.boot();
  // the very first thing the pass does, before any await has resolved
  assert.deepEqual(p.calls, ['accounts'],
    'nothing that needs `me` is even attempted before the accounts fetch');
  assert.equal(p.starts.length, 0);
});

test('the dependents all run once the identity lands, in the same pass', async () => {
  const p = makePage({ hold: true });
  p.boot();
  await settle();
  assert.deepEqual(p.starts.slice().sort(), DEPENDENTS.slice().sort(),
    'every identity-dependent loader got past its early return');
});

test('the dependents run CONCURRENTLY, not as a waterfall', async () => {
  const p = makePage({ hold: true });
  p.boot();
  await settle();
  // Every one of them has begun while not one of them has been allowed to
  // finish. A waterfall could only have started the first.
  assert.equal(p.starts.length, DEPENDENTS.length,
    'all five are in flight at once · a waterfall would show one');
  for (const d of DEPENDENTS) assert.equal(p.painted(d), false, d + ' has not finished yet');
  p.releaseAll();
  await settle();
  for (const d of DEPENDENTS) assert.ok(p.painted(d), d + ' finished once released');
});

test('one boot pass issues exactly ONE accounts request', async () => {
  const p = makePage();
  p.boot();
  await settle();
  assert.equal(p.accountsCalls(), 1,
    'the identity fetch and the accounts refresh are the same request, not two');
});

// ------------------------------------------------------------- idempotence

test('boot is idempotent · a second call stacks no second interval', async () => {
  const p = makePage();
  p.boot();
  await settle();
  const after = p.accountsCalls();
  p.boot(); p.boot();
  await settle();
  assert.equal(p.intervals.filter(Boolean).length, 1, 'still one interval, not three');
  assert.equal(p.accountsCalls(), after, 'the second boot fetched nothing');
});

test('the poll interval is still the 2s one', () => {
  const p = makePage();
  p.boot();
  assert.equal(p.interval().ms, 2000);
});

// ------------------------------------------------------------------ failure

test('a failed identity fetch retries on the next pass instead of going blank', async () => {
  const p = makePage({ accountsFails: true, failTimes: 1 });
  p.boot();
  await settle();
  assert.equal(p.painted('accounts'), false, 'the first fetch failed, as arranged');
  for (const d of DEPENDENTS) assert.equal(p.painted(d), false, d + ' had no identity to load for');
  // no unhandled rejection, no thrown boot · the page is alive and the timer
  // is still armed, which is the entire recovery
  p.interval().fn();
  await settle();
  assert.ok(p.painted('accounts'), 'the next pass refetched the identity');
  for (const d of DEPENDENTS) assert.ok(p.painted(d), d + ' painted on the retry');
});

test('a failing identity does not wedge on one rejected promise forever', async () => {
  const p = makePage({ accountsFails: true, failTimes: 2 });
  p.boot();
  await settle();
  p.interval().fn();          // second failure
  await settle();
  assert.equal(p.painted('accounts'), false);
  p.interval().fn();          // third attempt succeeds
  await settle();
  assert.ok(p.painted('accounts'), 'a fresh fetch is made each pass, not a cached rejection re-awaited');
  assert.equal(p.accountsCalls(), 3);
});

test('a slow identity does not stack a release per waiting pass', async () => {
  // Three passes queued behind one fetch used to mean each of them fired all
  // five dependents when it landed · fifteen requests to fill five panels.
  const p = makePage({ holdAccounts: true });
  p.boot();
  await settle();
  p.advance(2000); p.interval().fn(); await settle();
  p.advance(2000); p.interval().fn(); await settle();
  assert.equal(p.accountsCalls(), 1, 'the later passes joined the fetch, they did not repeat it');
  p.releaseAccounts();
  await settle();
  for (const d of DEPENDENTS) {
    assert.equal(p.calls.filter(x => x === d).length, 1,
      d + ' was loaded once, not once per pass that was waiting');
  }
});

test('a stalled identity fetch is abandoned and asked again, not awaited forever', async () => {
  // fetch() with no AbortSignal never settles on a dead connection, so the
  // held promise can be dead without ever failing. The old code was immune by
  // accident · it held nothing and simply asked again. This must be too.
  const p = makePage({ holdAccounts: true, stallTimes: 1 });
  p.boot();
  await settle();
  p.advance(2000); p.interval().fn(); await settle();
  assert.equal(p.accountsCalls(), 1, 'two seconds in, it is still reasonable to wait');
  p.advance(9000);                    // eleven seconds · past any honest latency
  p.interval().fn(); await settle();
  assert.equal(p.accountsCalls(), 2, 'the stalled attempt was abandoned and a fresh one made');
  assert.ok(p.painted('accounts'), 'and the fresh one answered');
  for (const d of DEPENDENTS) assert.ok(p.painted(d), d + ' painted after the retry');
});

// -------------------------------------------------------------- steady state

test('once the identity is known a pass fires everything, accounts included', async () => {
  const p = makePage();
  p.boot();
  await settle();
  const before = p.accountsCalls();
  p.calls.length = 0;
  p.interval().fn();
  await settle();
  assert.equal(p.accountsCalls(), before + 1, 'accounts still refreshes every poll');
  for (const d of DEPENDENTS) assert.ok(p.calls.includes(d), d + ' still polls');
});

// ------------------------------------------------------------- tab-gated work

test('a hidden tab is still not polled', async () => {
  const p = makePage();
  p.boot();
  await settle();
  for (const t of ['xray', 'traceRefresh', 'kafka']) {
    assert.equal(p.calls.includes(t), false, t + ' belongs to a tab nobody is looking at');
  }
});

test('a visible x-ray tab is polled, and the console tab is not', async () => {
  const p = makePage({ tabs: { xray: true } });
  p.boot();
  await settle();
  assert.ok(p.calls.includes('xray'));
  assert.ok(p.calls.includes('traceRefresh'));
  assert.equal(p.calls.includes('kafka'), false);
});

test('a visible console tab is polled', async () => {
  const p = makePage({ tabs: { console: true } });
  p.boot();
  await settle();
  assert.ok(p.calls.includes('kafka'));
  assert.equal(p.calls.includes('xray'), false);
});

// ------------------------------------------------------- what boot replaced

test('the tail of the page no longer schedules the loaders behind a timer', () => {
  // The old shape · a bare setInterval carrying the loader list · is exactly
  // what made first paint wait. If it comes back, the extraction was undone.
  const tail = SRC.slice(SRC.indexOf('BOOT-PURE-END'));
  assert.equal(/setInterval\(\(\)\s*=>\s*\{\s*tick\(loadAccounts\)/.test(tail), false,
    'the loader list is scheduled by boot(), not by an inline setInterval');
});
