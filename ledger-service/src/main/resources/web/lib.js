/**
 * lib.js · the parts of the front end that are ARITHMETIC, not painting.
 *
 * Everything here is pure: it takes numbers and returns numbers, touches no
 * DOM and no network. That is the whole point · this is the code that was
 * wrong three times in a row while "obviously working", so it lives where a
 * unit test can reach it (src/test/js/lib.test.js, run by `node --test`).
 *
 * The browser loads it with a plain <script> and finds it on window.MB.
 * Node requires it. No bundler, no build step · the same doctrine as the
 * rest of this bank: no framework unless the framework is load-bearing.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.MB = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ================================================================ metrics

  /** Prometheus text exposition -> { 'name{labels}': number }. */
  function parseProm(text) {
    const out = {};
    for (const raw of String(text).split('\n')) {
      const line = raw.trim();
      if (!line || line.charAt(0) === '#') continue;
      // the value is always last, so split there · label values may hold spaces
      const sp = line.lastIndexOf(' ');
      if (sp < 0) continue;
      const v = Number(line.slice(sp + 1));
      if (Number.isFinite(v)) out[line.slice(0, sp).trim()] = v;
    }
    return out;
  }

  function familyMatches(key, prefix) {
    return key === prefix || key.indexOf(prefix + '{') === 0;
  }

  function labelOf(key, labelKey) {
    const m = key.match(new RegExp(labelKey + '="([^"]+)"'));
    return m ? m[1] : 'all';
  }

  /** Current VALUE of a gauge family, grouped by one label. Zeros included:
   *  a gauge's zero is a reading, not an absence. */
  function gaugeByLabel(metrics, prefix, labelKey) {
    const out = {};
    for (const k of Object.keys(metrics)) {
      if (!familyMatches(k, prefix)) continue;
      out[labelOf(k, labelKey)] = metrics[k];
    }
    return out;
  }

  /** Per-second RATE of a counter family, grouped by one label. Needs the
   *  previous scrape, so the caller owns that state and passes it in. */
  function rateByLabel(metrics, prefix, labelKey, dtSec, prev) {
    const out = {};
    for (const k of Object.keys(metrics)) {
      if (!familyMatches(k, prefix)) continue;
      const label = labelOf(k, labelKey);
      const was = prev[k];
      prev[k] = metrics[k];
      // a counter that went backwards means the process restarted · report
      // no rate rather than a negative spike
      if (was != null && dtSec > 0) out[label] = (out[label] || 0) + Math.max(0, (metrics[k] - was) / dtSec);
    }
    return out;
  }

  /** Append one sample per label to a rolling window. A label that stopped
   *  reporting is padded with 0 so every series stays the same length. */
  function pushSeries(series, sample, cap) {
    const limit = cap == null ? 40 : cap;
    const names = new Set(Object.keys(series).concat(Object.keys(sample)));
    for (const n of names) {
      const arr = series[n] || (series[n] = []);
      arr.push(sample[n] || 0);
      if (arr.length > limit) arr.shift();
    }
    return series;
  }

  /**
   * Which series a panel should draw, and at what scale.
   *
   * opts.gauge  this panel plots a LEVEL. Zero is a real reading (a drained
   *             outbox, an idle pool) so the series is drawn flat on the
   *             baseline. For a RATE, an all-zero series really does mean
   *             nothing happened, and dropping it keeps the legend honest.
   * opts.floor  minimum y-scale, so a healthy flat zero sits on the baseline
   *             instead of the first single pending row filling the panel.
   */
  function chartSeries(series, opts) {
    const o = opts || {};
    const names = Object.keys(series)
      .filter(n => (o.gauge ? series[n].length > 0 : series[n].some(v => v > 0)))
      .sort();
    let max = o.floor || 0.001, len = 1;
    for (const n of names) {
      len = Math.max(len, series[n].length);
      for (const v of series[n]) if (v > max) max = v;
    }
    return { names, max, len, quiet: names.length === 0 };
  }

  /** Sample array -> polyline points, in SVG user units. */
  function chartPoints(values, max, W, H, len) {
    const span = (len == null ? values.length : len) - 1;
    const div = span > 0 ? span : 1;          // one sample must not divide by zero
    const scale = max > 0 ? max : 1;
    return values.map((v, i) => ({
      x: (i / div) * W,
      y: H - (v / scale) * (H - 8) - 4,
    }));
  }

  // ================================================================== cache

  /**
   * Stale-while-revalidate cache with single-flight.
   *
   *   FRESH  (age < ttl)   -> serve from memory, no request
   *   STALE  (age >= ttl)  -> serve the old value IMMEDIATELY, refresh behind
   *   MISS                 -> await the loader, and coalesce concurrent
   *                           callers for the same key into ONE request
   *
   * The point is that a render never waits on the network for data it
   * already has. A failed refresh keeps serving the last good value, so a
   * blip degrades to slightly-stale rather than to a blank panel · the same
   * "fails open" rule the read-through price cache follows server-side.
   */
  function swrCache(opts) {
    const o = opts || {};
    const ttl = o.ttl == null ? 5000 : o.ttl;
    const now = o.now || function () { return Date.now(); };
    const entries = new Map();     // key -> { value, at }
    const inflight = new Map();    // key -> promise   (the single-flight gate)
    const pending = new Set();     // background refreshes, for idle()

    function fetchOnce(key, loader) {
      const running = inflight.get(key);
      if (running) return running;
      const p = Promise.resolve().then(loader).then(
        function (v) { entries.set(key, { value: v, at: now() }); inflight.delete(key); return v; },
        function (e) { inflight.delete(key); throw e; }
      );
      inflight.set(key, p);
      return p;
    }

    return {
      get: function (key, loader) {
        const hit = entries.get(key);
        if (hit && now() - hit.at < ttl) return Promise.resolve(hit.value);
        if (hit) {
          // stale: refresh behind the render, and swallow failures · we
          // still have a value, and showing it beats showing nothing
          const bg = fetchOnce(key, loader).catch(function () { return hit.value; });
          pending.add(bg);
          bg.then(function () { pending.delete(bg); });
          return Promise.resolve(hit.value);
        }
        return fetchOnce(key, loader);
      },
      /** tests: settle every in-flight and background refresh */
      idle: function () {
        return Promise.all(Array.from(pending).concat(Array.from(inflight.values())))
          .catch(function () {});
      },
      peek: function (key) { const e = entries.get(key); return e ? e.value : undefined; },
      invalidate: function (key) { if (key == null) entries.clear(); else entries.delete(key); },
    };
  }

  // =================================================================== tape

  /**
   * The operations tape's geometry, in track coordinates.
   *
   * Every number the animation needs is kept HERE, in JS. The frame loop
   * used to ask the DOM (offsetLeft, offsetWidth, getBoundingClientRect)
   * once per train per frame, which forces a synchronous layout · that is
   * what made the tape micro-stutter. Now a train is measured exactly once,
   * when it is created, and the frame loop is pure arithmetic plus a single
   * transform write.
   *
   * Trains are placed at ABSOLUTE track offsets, so retiring the head never
   * shifts the survivors and nothing has to be re-measured to compensate.
   */
  function tapeLayout(opts) {
    const o = opts || {};
    return {
      beltWidth: o.beltWidth || 0,
      gap: o.gap == null ? 76 : o.gap,
      speed: o.speed == null ? 1 : o.speed,          // px per ms
      maxFrame: o.maxFrame == null ? Infinity : o.maxFrame,
      offscreen: o.offscreen == null ? 10 : o.offscreen,
      x: 0,                                          // track offset, goes negative
      cursor: 0,                                     // next free track position
      trains: [],
      get count() { return this.trains.length; },

      /** Reserve a slot for a train of this width; returns its track offset. */
      place: function (width) {
        const enter = -this.x + this.beltWidth;      // the belt's right edge, in track space
        const left = Math.max(this.cursor, enter);
        this.trains.push({ left: left, width: width });
        this.cursor = left + width + this.gap;
        return left;
      },

      /** Scroll by one frame. A long frame (background tab, GC pause) is
       *  clamped so the tape never teleports on the way back. */
      advance: function (dt) {
        this.x -= Math.min(this.maxFrame, Math.max(0, dt)) * this.speed;
        return this.x;
      },

      /** Drop trains that are fully past the left edge. Returns their head
       *  indices so the caller can remove exactly that many DOM nodes. */
      retire: function () {
        const gone = [];
        while (this.trains.length) {
          const head = this.trains[0];
          if (head.left + head.width + this.x >= -this.offscreen) break;
          this.trains.shift();
          gone.push(gone.length);
        }
        // an empty belt restarts from a clean origin, so the coordinates
        // cannot drift towards the float precision cliff over days of uptime
        if (!this.trains.length) { this.x = 0; this.cursor = 0; }
        return gone;
      },
    };
  }

  // ==================================================================== api

  /**
   * The fetch helper, with the one line that was missing.
   *
   * It used to be `fetch(p, opt).then(r => r.json())`, which never looked at
   * r.ok and so handed a non-2xx body back as if it were data. The server's
   * error shape is {"error": "..."}, so an account list became an object with
   * no .filter and a portfolio total became NaN. It reproduced under nothing
   * more exotic than clicking around quickly, because the token bucket starts
   * answering 429 at 25 requests a second.
   *
   * The distinction it now makes is the useful part:
   *
   *   400, 409  are ANSWERS about the request. "insufficient funds",
   *             "relocating, retry in a moment". The body is the point, and
   *             callers read .error and .result deliberately, so it is returned.
   *   429, 5xx  are not answers about the request at all. The bucket never
   *             looked at it; the 500 never finished it. There is no data here,
   *             and pretending otherwise is what broke the page.
   *
   * Throwing rather than returning also means `x = await api(...)` simply does
   * not assign, so the caller keeps its last good value. Stale and correct is a
   * much better failure than fresh and garbage.
   */
  function makeApi(fetchImpl) {
    return async function api(path, opt) {
      var r = await fetchImpl(path, opt);
      var body = null;
      try { body = await r.json(); } catch (e) { body = null; }
      if (r.ok || r.status === 400 || r.status === 409) return body;
      var err = new Error((body && body.error) || ('HTTP ' + r.status));
      err.status = r.status;
      err.body = body;
      throw err;
    };
  }

  /**
   * What a panel should say when its live window is quiet.
   *
   * "Ledger events by kind" plots a rate. This bank has no background
   * scheduler, so nothing moves money unless a person clicks, and the honest
   * rate is zero almost all the time. The panel therefore sat on
   * "quiet · no activity in this window" permanently, which is precisely why
   * nobody noticed the counter behind it had never been incremented at all.
   * A dead metric and an idle bank looked exactly the same.
   *
   * So when there is no live series, fall back to the cumulative totals the
   * process has been keeping since it started. Those are real numbers, and the
   * caller labels them as totals rather than as a rate. This does not invent
   * traffic; it stops a panel being blank when it has something true to say.
   *
   * Returns 'live' (draw the series), 'total' (show cumulative kinds, biggest
   * first, capped) or 'empty' (genuinely nothing, which now means the
   * instrumentation is dead rather than the bank being quiet).
   */
  function panelLegend(activeNames, totals, cap) {
    if (activeNames && activeNames.length) return { mode: 'live' };
    var keys = [];
    for (var k in (totals || {})) if (totals[k] > 0) keys.push(k);
    if (!keys.length) return { mode: 'empty' };
    keys.sort(function (a, b) { return totals[b] - totals[a]; });
    var limit = cap || 6;
    // Say how many are hidden rather than truncating silently. A legend that
    // quietly drops rows reads as "this is everything", which it is not.
    return { mode: 'total', kinds: keys.slice(0, limit), hiddenCount: Math.max(0, keys.length - limit) };
  }

  // ============================================================ payee picker
  //
  // The "To" dropdown is repainted by a 2 second poll. Rebuilding a <select>
  // with innerHTML destroys its <option> nodes, and a select whose options are
  // all new falls back to the FIRST one · so choosing coco silently reverted
  // to oscar about two seconds later, and the money aimed at whoever sorted
  // first. Two rules fix it, both pure enough to test here: only repaint when
  // the offered payees actually changed, and carry the chosen payee across a
  // repaint that does happen.

  // Who this customer may pay: every OTHER customer. System accounts (world,
  // in_transit, card hold) are book-keeping, never a destination a human picks.
  function payeeOptions(accounts, me) {
    return (accounts || []).filter(function (a) {
      return a && a.kind === 'customer' && a.id !== me;
    });
  }

  // Identity of the offer itself. An unchanged signature means the dropdown
  // already shows the right thing, so leave the DOM, and the user's choice,
  // alone.
  function payeeSig(peers) {
    return (peers || []).map(function (a) {
      return a.id + ':' + a.owner + ':' + a.region;
    }).join('|');
  }

  // What to re-select after a repaint: the chosen payee if they are still on
  // offer, otherwise nothing. Never silently fall through to another person ·
  // if coco is gone the field goes blank rather than aiming money at whoever
  // replaced her in the list.
  function keepPayee(peers, current) {
    var want = String(current === null || current === undefined ? '' : current);
    if (!want) return '';
    var still = (peers || []).some(function (a) { return String(a.id) === want; });
    return still ? want : '';
  }


  // ================================================================== the map

  /**
   * THE MAP, AS A GRAPH. One declaration, from which everything else follows.
   *
   * Edges are declared as [from, to] PAIRS and the name is DERIVED. That is the
   * whole point. The old code stored edge names as strings and read the
   * topology back out of them, which went wrong the moment a name disagreed
   * with what it drew: the edge named "browser_api" actually connects browser
   * to caddy, so every reader that split the name lit the wrong node. A derived
   * name cannot contradict its own endpoints.
   */
  function mapGraph(pairs) {
    var out = {}, nodes = {};
    for (var i = 0; i < pairs.length; i++) {
      var a = pairs[i][0], b = pairs[i][1];
      out[a + '>' + b] = true; nodes[a] = true; nodes[b] = true;
    }
    return {
      has: function (a, b) { return out[a + '>' + b] === true; },
      name: function (a, b) { return a + '_' + b; },
      nodes: Object.keys(nodes),
      edges: pairs.map(function (e) { return e[0] + '_' + e[1]; }),
    };
  }

  // ============================================================== the journey

  /* A region is a database, and nothing else is. An unrecognised region is
     reported rather than quietly treated as eu, because silently animating the
     wrong country is worse than animating nothing. */
  var REGION_NODE = { eu: 'shard0', uk: 'shard1' };

  /**
   * What each step DOES, as a path of nodes the money walks.
   *
   * A path, not an edge list. Hops are consecutive pairs of this path, so a
   * ball can only ever start where the previous ball arrived: contiguity is a
   * property of the shape rather than something a test has to police. The two
   * old tables (edges to draw, nodes to light) could and did drift apart; here
   * the lights fall out of the walk.
   *
   * `state` is for something true of a step that is not a journey. Money
   * entering the in-transit account is a state the depart step creates, not
   * somewhere a ball travels to.
   */
  var FLOW = {
    // one ACID transaction. It never touches Kafka, and that is the lesson.
    transfer:  { path: function (r) { return ['browser', 'caddy', 'api', r]; } },
    // the saga leaves. Same walk, plus the money is now in the pipe.
    depart:    { path: function (r) { return ['browser', 'caddy', 'api', r]; }, state: ['intransit'] },
    published: { path: function (r) { return [r, 'kafka']; } },
    arrive:    { path: function (r) { return ['kafka', 'applier', r]; } },
    /* A refund is what happens when the ARRIVAL fails, so the message still
       travelled kafka -> applier; the applier then sends the money back to the
       region it came from. Starting this walk at the applier would spawn a ball
       on a node nothing had reached, which is one of the ways balls appeared
       out of nowhere. */
    refund:    { path: function (r) { return ['kafka', 'applier', r]; } },
    // notifications is a SECOND consumer group on the same topic, so it starts
    // at kafka like the applier does, not at whatever the applier reached.
    notify:    { path: function () { return ['kafka', 'notif']; }, region: 'kafka' },
    /* A relocation moves a customer's whole shelf, one product account at a
       time, and each leg claims a transaction kind with a COLON in it. Neither
       old animation table had a key for these, so every shelf leg drew
       absolutely nothing: up to six departures and six arrivals, silent. They
       write no outbox row (Shard.java has no append on these paths), so they
       produce no publish and no notification, and the API talks to the region
       directly rather than through Kafka. */
    'relocate:depart': { path: function (r) { return ['browser', 'caddy', 'api', r]; }, state: ['intransit'] },
    /* The arrival is called DIRECTLY by the relocation, not delivered by Kafka
       (Relocation.java calls arrive() itself for promptness), so it comes from
       the API, which the departing leg has already reached. */
    'relocate:arrive': { path: function (r) { return ['api', r]; } },
  };

  /* The live feed and the trace endpoint name the same things differently.
     Normalising here means there is one vocabulary below this line. */
  var STEP_ALIAS = { transfer_local: 'transfer', bounced: 'refund', notified: 'notify' };

  var HOP_MS = 700;          // how long one ball takes to cross one edge
  var HOP_MAX = 1700;        // and the most it may take, so no hop eats the show
  var SHOW_MS = 5000;        // the budget the real gaps are scaled into

  /**
   * Turn trace steps into an ordered list of hops and lights.
   *
   * Pure: same steps in, same journey out, no DOM, no clock. Everything the
   * animation needs is decided here and unit tested, which is the point.
   */
  /** The one true name for a step, whichever vocabulary it arrived in. */
  function stepName(s) { return STEP_ALIAS[s] || s; }

  function journey(steps, graph, opts) {
    var o = opts || {};
    var showMs = o.showMs || SHOW_MS;
    var hops = [], lights = [], unknown = [], cursor = 0, wave = 0;
    var prevOrigin = null, prevWaveStart = 0, prevWaveEnd = 0;

    var t0 = steps.length && steps[0].ts ? new Date(steps[0].ts).getTime() : null;
    var span = 1;
    if (t0 != null && steps[steps.length - 1].ts)
      span = Math.max(new Date(steps[steps.length - 1].ts).getTime() - t0, 1);

    for (var i = 0; i < steps.length; i++) {
      var st = steps[i];
      var name = STEP_ALIAS[st.step] || st.step;
      var flow = FLOW[name];
      if (!flow) { unknown.push(st.step); continue; }

      var regionNode = flow.region || REGION_NODE[st.region];
      if (!regionNode) { unknown.push(st.region); continue; }

      var path = flow.path(regionNode);
      var legs = path.length - 1;
      if (legs < 1) continue;

      // Real gaps decide the pace, within bounds, and the bounds are PER HOP so
      // a two hop step is not crammed into one hop's worth of time.
      var per = HOP_MS;
      if (t0 != null && i > 0 && st.ts && steps[i - 1].ts) {
        var gap = (new Date(st.ts).getTime() - new Date(steps[i - 1].ts).getTime()) / span * showMs;
        per = Math.min(HOP_MAX, Math.max(HOP_MS, gap / legs));
      }

      // THE FORK. Steps that leave the same node leave it at the same moment,
      // because that is what two consumer groups reading one message is. The
      // applier and the notifications service both start at kafka; drawing them
      // in series is what made the ball look like it jumped back to fetch the
      // notification.
      var origin = path[0];
      var start = (origin === prevOrigin) ? prevWaveStart : cursor;
      if (origin === prevOrigin) { /* same wave */ } else { wave++; }

      for (var k = 0; k < legs; k++) {
        var from = path[k], to = path[k + 1];
        if (!graph.has(from, to)) { unknown.push(from + '_' + to); continue; }
        var s = start + k * per, e = s + per * 0.88;
        hops.push({ step: name, edge: graph.name(from, to), from: from, to: to,
                    start: Math.round(s), end: Math.round(e), wave: wave });
        lights.push({ node: to, at: Math.round(e), reason: 'arrival' });
      }

      var stepEnd = start + legs * per;
      (flow.state || []).forEach(function (nd) {
        lights.push({ node: nd, at: Math.round(stepEnd - per), reason: 'state' });
      });

      prevOrigin = origin;
      prevWaveStart = start;
      prevWaveEnd = Math.max(prevWaveEnd, stepEnd);
      cursor = Math.max(cursor, stepEnd);
    }

    return { hops: hops, lights: lights, unknown: unknown, total: Math.round(cursor) };
  }

  return {
    makeApi: makeApi,
    mapGraph: mapGraph,
    journey: journey,
    stepName: stepName,
    panelLegend: panelLegend,
    parseProm: parseProm,
    gaugeByLabel: gaugeByLabel,
    rateByLabel: rateByLabel,
    pushSeries: pushSeries,
    chartSeries: chartSeries,
    chartPoints: chartPoints,
    swrCache: swrCache,
    tapeLayout: tapeLayout,
    payeeOptions: payeeOptions,
    payeeSig: payeeSig,
    keepPayee: keepPayee,
  };
});
