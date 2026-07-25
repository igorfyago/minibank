/**
 * THE WEALTH CARD'S DECISIONS, RUN FOR REAL.
 *
 * Run with:  node --test src/test/js
 *
 * Why this file exists, and why it does not grep.
 *
 * The net worth card used to render its breakdown as a wrapping flex run of
 * label and value pairs. Three things were wrong with that, and only one of
 * them was visual: the summary rendered smaller than one of its own
 * components, the run read as a sentence and orphaned its last leg onto a line
 * of its own, and assets and debts were interleaved with a minus sign carrying
 * the entire semantic load. Fixing the third meant moving classification out
 * of "did I notice a minus" and onto the server's DECLARED kind field, which
 * is a decision, and decisions are what regress.
 *
 * A regex over index.html would pass on any spelling of these rules, including
 * the wrong one. It has produced a false green in this repo before, because the
 * string it matched survived in a skeleton that is never on screen once data
 * arrives. So this lifts the page's OWN source, the exact text between the
 * NETWORTH-PURE markers, into a vm and calls the shipped functions. Nothing
 * here can pass because a line was worded a certain way; it passes only if the
 * code computes the right answer.
 *
 * What is pinned:
 *   · how the total formats, including a negative and a withheld one
 *   · which group a leg lands in, and that it is the server's kind that decides
 *   · what a leg the page has never heard of does (it renders, it is not
 *     dropped, and it cannot inject markup)
 *   · what a zero leg and a missing amount look like
 *   · that the mark age survives a withheld total
 *   · that bar geometry is a share and never a euro figure
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const BANK_FILE = path.join(__dirname, '..', '..', 'main', 'resources', 'web', 'index.html');
const SRC = fs.readFileSync(BANK_FILE, 'utf8');

// =============================================================== the harness
/**
 * The page's own text, not a transcription of it. If someone deletes a marker
 * or renames a function, this file fails loudly rather than testing a copy
 * that has quietly drifted from what ships.
 */
function pureSource() {
  const start = SRC.indexOf('/* ===================== NETWORTH-PURE-START');
  const end = SRC.indexOf('/* ====================== NETWORTH-PURE-END');
  assert.ok(start > -1, 'the NETWORTH-PURE-START marker is gone from index.html');
  assert.ok(end > start, 'the NETWORTH-PURE-END marker is gone from index.html');
  return SRC.slice(start, end);
}

/**
 * fmt() is the page's real one, copied here on purpose rather than stubbed:
 * the euro sign and the two fixed decimals are part of what "how the total
 * formats" means, and a stub that returned the raw number would let a
 * formatting regression through.
 */
const FMT = "const fmt = v => '€' + Number(v).toLocaleString('en-US', "
  + '{ minimumFractionDigits: 2, maximumFractionDigits: 2 });';

const NW = (() => {
  const ctx = { console };
  vm.createContext(ctx);
  vm.runInContext(
    FMT + '\n' + pureSource()
      + '\n;this.out = { nwPlan, nwWhy, nwRow, nwGroups, nwMeta, nwMoney, nwAge, nwEsc, NW_LEG };',
    ctx, { filename: 'index.html:networth-pure' });
  return ctx.out;
})();

/** the shape /api/networth actually returns, with overridable legs */
function payload(over) {
  return Object.assign({
    total: '-1804.79',
    breakdown: [
      { label: 'main', amount: '2578.55', kind: 'asset' },
      { label: 'savings', amount: '1350.00', kind: 'asset' },
      { label: 'invested', amount: '2637.66', kind: 'asset' },
      { label: 'card', amount: '-471.00', kind: 'liability' },
      { label: 'loan', amount: '-7900.00', kind: 'liability' }
    ],
    unpriced: 0,
    expired: 0,
    priceAgeSeconds: null
  }, over || {});
}

const rowsOf = (plan, key) => plan.groups.find(g => g.key === key).rows;

// ================================================================= the total
test('the total formats as euros with the minus ahead of the sign', () => {
  const plan = NW.nwPlan(payload());
  assert.equal(plan.total.text, '−€1,804.79');
  assert.equal(plan.total.neg, true, 'a negative total is flagged for the red');
  assert.equal(plan.total.unknown, false);
});

test('a positive total is not flagged negative, so red stays spent on the one case that earns it', () => {
  const plan = NW.nwPlan(payload({ total: '6120.21' }));
  assert.equal(plan.total.text, '€6,120.21');
  assert.equal(plan.total.neg, false);
});

test('a total of exactly zero is neither negative nor withheld', () => {
  const plan = NW.nwPlan(payload({ total: '0.00' }));
  assert.equal(plan.total.text, '€0.00');
  assert.equal(plan.total.neg, false);
  assert.equal(plan.total.unknown, false);
});

test('a withheld total says so instead of rendering a partial sum', () => {
  const plan = NW.nwPlan(payload({ total: null, unpriced: 1 }));
  assert.equal(plan.total.text, 'not available');
  assert.equal(plan.total.unknown, true);
  assert.equal(plan.total.neg, false, 'nothing may be red on a number that does not exist');
  assert.match(plan.why, /no live price/);
  assert.match(plan.why, /withheld rather than partly summed/);
});

test('the withheld reason names expired and unpriced separately, and counts them', () => {
  assert.match(NW.nwPlan(payload({ total: null, expired: 2 })).why,
    /^2 expired contracts cannot be valued/);
  assert.match(NW.nwPlan(payload({ total: null, expired: 1 })).why,
    /^1 expired contract cannot be valued/);
  assert.match(NW.nwPlan(payload({ total: null, expired: 1, unpriced: 1 })).why,
    /^1 expired and 1 unpriced holding\b/);
  assert.match(NW.nwPlan(payload({ total: null, expired: 1, unpriced: 3 })).why,
    /^1 expired and 3 unpriced holdings\b/);
});

test('a summed total carries no withheld reason', () => {
  assert.equal(NW.nwPlan(payload()).why, null);
});

// ========================================================== classification
test('a leg is grouped by the kind the SERVER declared, not by its sign', () => {
  // a liability that happens to be paid off is still a liability, and an asset
  // that happens to be overdrawn is still an asset. This is the whole reason
  // kind is a field instead of a heuristic.
  const plan = NW.nwPlan(payload({
    breakdown: [
      { label: 'main', amount: '-25.00', kind: 'asset' },
      { label: 'card', amount: '0.00', kind: 'liability' }
    ]
  }));
  assert.deepEqual(rowsOf(plan, 'own').map(r => r.key), ['main']);
  assert.deepEqual(rowsOf(plan, 'owe').map(r => r.key), ['card']);
  assert.equal(rowsOf(plan, 'own')[0].debt, false);
  assert.equal(rowsOf(plan, 'owe')[0].debt, true);
});

test('the five legs the bank ships land three assets and two debts, in server order', () => {
  const plan = NW.nwPlan(payload());
  assert.deepEqual(rowsOf(plan, 'own').map(r => r.title), ['Main', 'Savings', 'Investments']);
  assert.deepEqual(rowsOf(plan, 'owe').map(r => r.title), ['Credit card', 'Loan']);
});

test('a group with no legs is empty rather than absent, so the card can hide it', () => {
  const plan = NW.nwPlan(payload({
    breakdown: [{ label: 'main', amount: '10.00', kind: 'asset' }]
  }));
  assert.equal(rowsOf(plan, 'owe').length, 0);
  assert.match(NW.nwGroups(plan), /class="nw-group hidden"/,
    'a debt-free customer gets no empty "What you owe" heading');
});

// =================================================== the leg nobody knows of
test('a leg the page has never heard of is RENDERED, not filtered away', () => {
  // The card promotes a server-summed total to hero size. If an unrecognised
  // leg were dropped, that total would silently stop equalling its own visible
  // parts the day a sixth product shipped, on a bank screen, with no warning.
  const plan = NW.nwPlan(payload({
    total: '100.00',
    breakdown: [
      { label: 'main', amount: '60.00', kind: 'asset' },
      { label: 'pension', amount: '40.00', kind: 'asset' }
    ]
  }));
  const own = rowsOf(plan, 'own');
  assert.deepEqual(own.map(r => r.key), ['main', 'pension']);
  assert.equal(own[1].title, 'pension', 'it falls back to the label the server sent');
  assert.equal(own[1].go, null, 'there is no tile to open, so there is nowhere to tap');
  assert.equal(own[1].text, '€40.00', 'and it is still a real figure');
});

test('an unknown leg renders without a chevron and cannot inject markup', () => {
  const plan = NW.nwPlan(payload({
    breakdown: [{ label: '<img src=x onerror=alert(1)>', amount: '1.00', kind: 'asset' }]
  }));
  const html = NW.nwGroups(plan);
  assert.ok(!html.includes('<img'), 'a server label reaches innerHTML escaped');
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.ok(!html.includes('data-go='), 'an unknown leg is not clickable');
  assert.match(html, /<span class="nw-chev"><\/span>/, 'and shows no chevron');
});

test('a known leg is clickable and names where it goes', () => {
  const html = NW.nwGroups(NW.nwPlan(payload()));
  assert.match(html, /data-go="ln"[^>]*onclick="nwGo\('ln'\)"/);
  assert.match(html, /data-go="acct"/, 'Main aims at the balance card, which has no tile');
  assert.equal((html.match(/data-go=/g) || []).length, 5);
});

// ================================================= zero and missing amounts
test('a zero leg is quiet rather than shouting, including a paid-off card', () => {
  const plan = NW.nwPlan(payload({
    breakdown: [
      { label: 'savings', amount: '0.00', kind: 'asset' },
      { label: 'card', amount: '0.00', kind: 'liability' }
    ]
  }));
  assert.equal(rowsOf(plan, 'own')[0].quiet, true);
  assert.equal(rowsOf(plan, 'owe')[0].quiet, true, 'an untouched card does not sit there glowing red');
  assert.equal(rowsOf(plan, 'own')[0].text, '€0.00', 'quiet is not hidden · the figure is still stated');
});

test('a leg the server could not value says so and is not confused with zero', () => {
  const plan = NW.nwPlan(payload({
    total: null, unpriced: 1,
    breakdown: [
      { label: 'main', amount: '2578.55', kind: 'asset' },
      { label: 'invested', amount: null, kind: 'asset' }
    ]
  }));
  const inv = rowsOf(plan, 'own')[1];
  assert.equal(inv.known, false);
  assert.equal(inv.amount, null);
  assert.equal(inv.text, 'not valued');
  assert.notEqual(inv.text, '€0.00', 'unvalued and zero are different claims');
  assert.equal(inv.quiet, true);
});

test('the known legs survive a withheld total instead of blanking the breakdown', () => {
  // the old strip blanked everything when one holding could not be valued,
  // which told the customer less than the server actually knows
  const plan = NW.nwPlan(payload({
    total: null, unpriced: 1,
    breakdown: payload().breakdown.map(l =>
      l.label === 'invested' ? { label: 'invested', amount: null, kind: 'asset' } : l)
  }));
  assert.equal(rowsOf(plan, 'own').length, 3);
  assert.equal(rowsOf(plan, 'owe').length, 2);
  assert.equal(rowsOf(plan, 'own')[0].text, '€2,578.55');
  assert.equal(rowsOf(plan, 'owe')[1].text, '−€7,900.00');
});

// ========================================================= the stale marks
test('the mark age surfaces even when the total was withheld', () => {
  // balances are live from the ledger and only the marks are cached, so an old
  // mark has to be able to state its age in the run where the sum did not come
  // out. It used to be the last item in the same run as the figures, where it
  // was both misreadable as a leg and lost whenever the total was withheld.
  const plan = NW.nwPlan(payload({ total: null, unpriced: 1, priceAgeSeconds: 240 }));
  assert.ok(plan.asof, 'the age is not tied to the total having been computed');
  assert.equal(plan.asof.text, 'marks 4 min old');
  assert.equal(plan.asof.stale, true);
});

test('the age reads in seconds below 90 and in minutes above, and goes amber at a minute', () => {
  assert.equal(NW.nwPlan(payload({ priceAgeSeconds: 42 })).asof.text, 'marks 42s old');
  assert.equal(NW.nwPlan(payload({ priceAgeSeconds: 42 })).asof.stale, false);
  assert.equal(NW.nwPlan(payload({ priceAgeSeconds: 60 })).asof.stale, true);
  assert.equal(NW.nwPlan(payload({ priceAgeSeconds: 89 })).asof.text, 'marks 89s old');
  assert.equal(NW.nwPlan(payload({ priceAgeSeconds: 90 })).asof.text, 'marks 2 min old');
});

test('live marks say nothing at all rather than saying "0s old"', () => {
  assert.equal(NW.nwPlan(payload({ priceAgeSeconds: null })).asof, null);
});

test('the cached-mark note lands on the leg the marks actually value', () => {
  const plan = NW.nwPlan(payload({ priceAgeSeconds: 300 }));
  const own = rowsOf(plan, 'own');
  assert.equal(own[0].flag, null, 'a ledger balance is live and carries no mark age');
  assert.equal(own[1].flag, null);
  // field by field · the plan is built inside the vm, so its objects do not
  // share a prototype with this realm's and deepStrictEqual would fail on
  // identity rather than on content
  assert.ok(own[2].flag, 'Investments does');
  assert.equal(own[2].flag.text, 'cached mark');
  assert.equal(own[2].flag.stale, true);
  assert.equal(rowsOf(plan, 'owe')[0].flag, null);
});

test('with live marks no leg carries the note', () => {
  const plan = NW.nwPlan(payload({ priceAgeSeconds: null }));
  assert.deepEqual(rowsOf(plan, 'own').map(r => r.flag), [null, null, null]);
});

// ============================================================ bar geometry
test('bar shares are a fraction of the gross magnitude and sum to one', () => {
  const plan = NW.nwPlan(payload());
  const all = plan.bar.own.concat(plan.bar.owe);
  const sum = all.reduce((s, x) => s + x.share, 0);
  assert.ok(Math.abs(sum - 1) < 1e-12, 'every leg accounted for exactly once');
  // gross is 2578.55 + 1350 + 2637.66 + 471 + 7900 = 14937.21
  assert.ok(Math.abs(plan.bar.owe[1].share - 7900 / 14937.21) < 1e-12,
    'the loan is sized by its magnitude, so debts have width and not just a sign');
  all.forEach(s => assert.ok(s.share >= 0, 'a segment width is never negative'));
});

test('a share is a width and never a euro figure', () => {
  const plan = NW.nwPlan(payload());
  plan.bar.own.concat(plan.bar.owe).forEach(s => {
    assert.equal(typeof s.share, 'number');
    assert.ok(s.share <= 1, 'a share of the whole cannot exceed the whole');
  });
  const html = NW.nwGroups(plan);
  assert.ok(!/0\.\d{6}/.test(html), 'no ratio ever reaches the screen as text');
});

test('an unvalued leg claims no width, and the bar flags that it is incomplete', () => {
  const plan = NW.nwPlan(payload({
    total: null, unpriced: 1,
    breakdown: [
      { label: 'main', amount: '100.00', kind: 'asset' },
      { label: 'invested', amount: null, kind: 'asset' }
    ]
  }));
  assert.equal(plan.bar.own[1].share, 0, 'inventing a width invents the size the server refused to state');
  assert.equal(plan.bar.own[0].share, 1);
  assert.equal(plan.bar.unknown, true);
});

test('a customer with nothing at all divides by no zero', () => {
  const plan = NW.nwPlan(payload({
    total: '0.00',
    breakdown: [
      { label: 'main', amount: '0.00', kind: 'asset' },
      { label: 'card', amount: '0.00', kind: 'liability' }
    ]
  }));
  plan.bar.own.concat(plan.bar.owe).forEach(s =>
    assert.equal(s.share, 0, 'zero over zero is zero here, not NaN'));
  assert.equal(plan.total.text, '€0.00');
});

// ================================================== subtotals the server owns
test('a group subtotal renders only when the SERVER sends one', () => {
  // /api/networth has no assets/liabilities key today. A reduce in this file
  // would be a second, softer source of truth one line under the authoritative
  // one, so the headers stand bare until the server answers.
  const bare = NW.nwGroups(NW.nwPlan(payload()));
  assert.ok(!bare.includes('nw-gsum'), 'no subtotal is invented in the browser');

  const served = NW.nwGroups(NW.nwPlan(payload({ assets: '6566.21', liabilities: '-8371.00' })));
  assert.match(served, /<span class="nw-gsum">€6,566.21<\/span>/);
  assert.match(served, /<span class="nw-gsum">−€8,371.00<\/span>/);
});

// ================================================== structure, not prose
test('the breakdown is rows, not a wrapping run of label and value pairs', () => {
  const html = NW.nwGroups(NW.nwPlan(payload()));
  // the character class matters · class="nw-rows" is the container, not a row
  assert.equal((html.match(/class="nw-row[ "]/g) || []).length, 5, 'one row per leg, always');
  assert.equal((html.match(/class="nw-amt"/g) || []).length, 5,
    'every amount is its own cell on the right edge, so none can be orphaned by a wrap');
  assert.ok(!html.includes('nw-break'), 'the flex run that orphaned the loan is gone');
});

test('a garbage payload does not throw · an empty card beats a broken tab', () => {
  [undefined, null, {}, { total: null }, { breakdown: null }, { breakdown: [] }].forEach(p => {
    const plan = NW.nwPlan(p);
    assert.equal(plan.total.unknown, true);
    assert.equal(rowsOf(plan, 'own').length, 0);
    assert.ok(typeof NW.nwGroups(plan) === 'string');
  });
});
