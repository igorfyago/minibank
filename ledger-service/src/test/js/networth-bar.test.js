/**
 * THE BAR MUST FILL THE TRACK IT IS GIVEN.
 *
 * Every leg carries its share of the GROSS magnitude, which is the right
 * number for sizing own against owe across the whole bar and the wrong number
 * to hand to flex-grow inside one side. CSS distributes free space in
 * proportion to flex-grow only when the values sum to at least 1; below that
 * it hands out exactly that fraction and leaves the rest unfilled.
 *
 * Measured on the live page before the fix: the own side was 219px holding
 * 94px of segments, the owe side 278px holding 155px. Roughly 250px of bare
 * track, reported as "the transparent areas look weird".
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const MB = require(path.join(__dirname, '..', '..', 'main', 'resources', 'web', 'lib.js'));

test('a side is filled exactly, whatever fraction of the bar it owns', () => {
  // igor, live: own 0.44 of the gross, owe 0.56
  for (const side of [[0.1726, 0.0904, 0.1767], [0.0315, 0.5288]]) {
    const g = MB.barGrows(side);
    const sum = g.reduce((a, b) => a + b, 0);
    assert.ok(Math.abs(sum - 1) < 1e-9,
      'segments must sum to 1 so CSS fills the side · got ' + sum);
  }
});

test('the proportions inside the side are preserved, only the scale changes', () => {
  const g = MB.barGrows([0.0315, 0.5288]);
  // the loan is 16.79x the card before and must still be after
  assert.ok(Math.abs((g[1] / g[0]) - (0.5288 / 0.0315)) < 1e-6);
});

test('nothing to draw is not a division by zero', () => {
  assert.deepEqual(MB.barGrows([]), []);
  assert.deepEqual(MB.barGrows([0, 0]), [0, 0]);
  assert.deepEqual(MB.barGrows(null), []);
});

test('an unvalued leg claims no width and does not distort the others', () => {
  // a leg the server could not value arrives as share 0
  const g = MB.barGrows([0.5, 0, 0.5]);
  assert.equal(g[1], 0, 'no share, no width');
  assert.ok(Math.abs(g[0] - 0.5) < 1e-9 && Math.abs(g[2] - 0.5) < 1e-9);
});
