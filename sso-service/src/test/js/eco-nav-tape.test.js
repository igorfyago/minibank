// THE ESTATE TAPE · the bank's markee, shared by every app through the bar.
//
// The bank has a money ticker at the bottom of its screen; the directive is
// that this is an ESTATE fixture: any app that loads eco-nav.js gets the
// same tape, and any app can put an event on it by emitting a DOM event
// ("eco:tick" with {icon, text, amount?, tone?} in detail). Apps without an
// event stream of their own show nothing — an empty tape stays hidden,
// never a bar advertising silence.
//
// Run: node --test src/test/js/eco-nav-tape.test.js  (from sso-service/)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const src = readFileSync(new URL('../../main/resources/web/eco-nav.js', import.meta.url), 'utf8');

function makeWorld() {
  const listeners = {};
  const made = [];
  const el = tag => ({
    tag, children: [], style: {}, dataset: {}, classList: {
      _s: new Set(),
      add(...c) { c.forEach(x => this._s.add(x)); },
      remove(...c) { c.forEach(x => this._s.delete(x)); },
      toggle(c, f) { f === undefined ? (this._s.has(c) ? this._s.delete(c) : this._s.add(c)) : f ? this._s.add(c) : this._s.delete(c); },
      contains(c) { return this._s.has(c); },
    },
    setAttribute() {}, appendChild(c) { this.children.push(c); },
    insertBefore(c) { this.children.unshift(c); },
    replaceChildren(...cs) { this.children = cs; },
    addEventListener(t, f) { (listeners[t] ||= []).push(f); },
    set textContent(v) { this._text = v; }, get textContent() { return this._text; },
    set innerHTML(v) { this._html = v; }, get innerHTML() { return this._html || ''; },
    set cssText(v) { this._css = v; }, get cssText() { return this._css; },
    querySelector() { return null; }, remove() {},
    offsetWidth: 120, clientWidth: 800,
  });
  const document = {
    readyState: 'complete',
    createElement: t => { const e = el(t); made.push(e); return e; },
    getElementById: () => null,
    addEventListener(t, f) { (listeners[t] ||= []).push(f); },
    head: el('head'), body: el('body'),
  };
  const sandbox = {
    document, location: { hostname: 'mart.b4rruf3t.com', href: 'https://mart.b4rruf3t.com/' },
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    fetch: () => Promise.reject(new Error('no network in test')),
    console, setTimeout, clearTimeout, requestAnimationFrame: () => {},
    URL, encodeURIComponent,
  };
  sandbox.window = sandbox;
  return { sandbox, listeners, made, document };
}

function boot(w) {
  vm.createContext(w.sandbox);
  vm.runInContext(src, w.sandbox);
}

test('the bar carries a tape element, hidden until an event arrives', () => {
  const w = makeWorld();
  boot(w);
  const tape = w.made.find(e => e.id === 'eco-tape');
  assert.ok(tape, 'eco-nav builds the estate tape');
  assert.ok(!tape.classList.contains('on'), 'silent until the first event');
});

test('an eco:tick event lights the tape with the app\'s own words', () => {
  const w = makeWorld();
  boot(w);
  const fire = w.listeners['eco:tick'];
  assert.ok(fire && fire.length, 'the tape listens for eco:tick');
  fire[0]({ detail: { icon: '💳', text: 'minimart · card captured', amount: '€168.00', tone: 'neg' } });
  const tape = w.made.find(e => e.id === 'eco-tape');
  assert.ok(tape.classList.contains('on'), 'the tape shows on an event');
  const flat = JSON.stringify(w.made.map(e => [e._text, e._html]).filter(Boolean));
  assert.ok(flat.includes('minimart · card captured'), 'the event text crosses the tape · got ' + flat.slice(0, 200));
  assert.ok(flat.includes('€168.00'), 'and the amount with it');
});

test('a host with its own tape keeps the floor: no second markee', () => {
  const w = makeWorld();
  const own = w.document.createElement('div');
  own.id = 'ticker-bar';
  w.document.getElementById = id => id === 'ticker-bar' ? own : null;
  vm.createContext(w.sandbox);
  vm.runInContext(src, w.sandbox);
  assert.ok(!w.made.find(e => e.id === 'eco-tape'),
    'the bank keeps its train-tape; the estate tape yields to it');
});

test('the host page keeps its own bottom: the tape is fixed and reserves nothing it did not own', () => {
  const w = makeWorld();
  boot(w);
  const tape = w.made.find(e => e.id === 'eco-tape');
  const css = tape.style.cssText;
  assert.ok(css.includes('position:fixed'), 'fixed like the bar itself');
  assert.ok(css.includes('bottom:0'), 'and docked at the bottom');
});
