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
  /* THE SECURITIES SAGA, as one walk.
   *
   * A trade is ONE row on the customer's shard · Products.settleFill claims
   * exactly one transaction · so the trace hands the map a single step and that
   * step has to carry the entire loop, or the loop is not on the map at all.
   * The nodes, in the order the map draws them:
   *
   *   api        the click reaches /api/trade
   *   shard      the money lands, four entries in two currencies
   *   ksettle    the shard's relay publishes onto topic settlements
   *   brokercons the broker's consumer group reads it
   *   brokerdb   the position is written, the fifth database
   *   korders    the broker's relay publishes onto topic orders
   *   settle     the ledger's Settlement group reads it back
   *   shard      and the money is home
   *
   * Nine hops, and every one of them is an edge the map already drew and no
   * step had ever walked. Declared once here because buy and sell travel the
   * identical route · what differs between them is the SIGN of the entries,
   * which is a fact about the ledger and not about the diagram.
   */
  function tradeLoop(r) {
    return ['browser', 'caddy', 'api', r, 'ksettle', 'brokercons', 'brokerdb',
            'korders', 'settle', r];
  }

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

    /* THE TRADE. Same disease the relocation legs had, one size worse: the
       kind carries the ASSET in the middle · "settle:aapl:buy" · so no fixed
       key could ever have matched it, and every securities trade this bank has
       ever settled animated as absolutely nothing. The broker boxes were on the
       diagram; nothing walked to them. stepName() collapses the asset out
       (assets are rows in a registry table, not constants this file is allowed
       to know) and both sides land here. */
    'settle:buy':  { path: tradeLoop },
    'settle:sell': { path: tradeLoop },

    /* THE REFUSAL, and it is deliberately NOT the loop. The venue already
       filled, then the money said no, so Products.recordSettlementRefusal
       writes a claim and an outbox row and MOVES NOTHING. The message still
       travels to the broker, which compensates in its own database · and then
       it stops. Walking back to the shard would draw money returning to a
       customer whose balance never changed. */
    'settle-refused': { path: function (r) {
      return ['browser', 'caddy', 'api', r, 'ksettle', 'brokercons', 'brokerdb'];
    } },

    /* The deprecated path, kept animatable because it is still reachable from
       the reconciliation lesson. tradeWithoutBroker writes the four entries in
       the ledger itself and tells no broker, which is exactly why it walks the
       short local route and never reaches a broker node. The diagram saying so
       is the point: this is the drift the securities loop above exists to end. */
    'trade:buy':  { path: function (r) { return ['browser', 'caddy', 'api', r]; } },
    'trade:sell': { path: function (r) { return ['browser', 'caddy', 'api', r]; } },

    /* A publish onto topic SETTLEMENTS. The events feed calls every relay
       publish "published" regardless of topic, and the plain `published` step
       above draws shard -> kafka, which is topic payments. A trade settlement
       never goes near that topic, so drawing it there would put a ball on a
       line the message did not travel. eventToStep reads the outbox payload and
       routes it here instead. */
    'published:settlements': { path: function (r) { return [r, 'ksettle']; } },
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
  /**
   * Transaction kinds with a VARIABLE middle segment.
   *
   * "settle:aapl:buy", "settle:btc:sell", "trade:msft:buy" · the asset is a row
   * in the registry, and the registry is a table someone can insert into
   * without touching this file. Listing the assets here would mean every new
   * listing silently animates as nothing, which is precisely the bug being
   * fixed, one level up. So the shape is MATCHED and the asset is dropped:
   * what the map draws depends on settle-versus-trade and buy-versus-sell, and
   * on nothing else. Anchored both ends so it can swallow nothing it was not
   * aimed at · "settle-refused" has no colons and passes straight through.
   */
  var ASSET_KIND = /^(settle|trade):[a-z0-9._-]+:(buy|sell)$/i;

  /** The one true name for a step, whichever vocabulary it arrived in. */
  function stepName(s) {
    var k = String(s == null ? '' : s);
    if (STEP_ALIAS[k]) return STEP_ALIAS[k];
    var m = k.match(ASSET_KIND);
    return m ? m[1].toLowerCase() + ':' + m[2].toLowerCase() : k;
  }

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
      /* stepName(), not a second copy of the alias lookup. The inline version
         that used to be here knew about STEP_ALIAS and nothing else, so the
         asset-bearing kinds normalised correctly for the caller reading
         MB.stepName and not for the journey itself · the two would have
         disagreed about what a trade step is called. One function, one answer. */
      var name = stepName(st.step);
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

  // ================================================== the route, before it runs
  /**
   * WHERE THIS JOURNEY IS GOING, decided before anything moves.
   *
   * Selecting a transaction should mark the WHOLE route first, so the visitor
   * can see where the money is about to go, and then watch each node and edge
   * flip to reached as the ball actually arrives. The page used to guess the
   * route from a hand written list:
   *
   *     ['api', regions.has('eu') && 'shard0', regions.has('uk') && 'shard1',
   *      steps.some(s => s.step === 'published') && 'kafka', ...]
   *
   * which is a second, cruder copy of the topology that journey() already owns.
   * A local transfer has one region and no `published` match after aliasing, so
   * it marked api and shard0 and stopped · the reported "only the first two".
   * A securities settlement marked exactly the same two and none of the five
   * broker nodes it walks, because the list had never heard of them, and no
   * edge was ever marked at all.
   *
   * So the prediction is DERIVED from the journey rather than restated. The
   * predicted path and the animated path cannot disagree, because they are the
   * same list read twice.
   *
   * Nodes come from the LIGHTS, not from the hops' endpoints, and that is the
   * one subtle decision here. A hop's `from` includes the origin the walk
   * starts at · the browser · and nothing ever ARRIVES there, so it never turns
   * green. Marking it violet forever would leave a journey that finished with a
   * node still saying "about to happen", which is exactly the disagreement this
   * function exists to remove. What is planned is precisely what gets reached.
   *
   * A journey with no hops is not a route, so it plans nothing: an undrawable
   * transaction must leave the map alone rather than mark a state node on its
   * own.
   */
  function plannedPath(j) {
    var src = j || {};
    var hops = src.hops || [], lights = src.lights || [];
    if (!hops.length) return { nodes: [], edges: [] };
    var nodes = [], edges = [], seenNode = {}, seenEdge = {};
    for (var i = 0; i < hops.length; i++) {
      var e = hops[i] && hops[i].edge;
      if (e && !seenEdge[e]) { seenEdge[e] = true; edges.push(e); }
    }
    for (var k = 0; k < lights.length; k++) {
      var n = lights[k] && lights[k].node;
      if (n && !seenNode[n]) { seenNode[n] = true; nodes.push(n); }
    }
    return { nodes: nodes, edges: edges };
  }

  // ======================================================= what a click MEANS
  /*
   * SELECTING A TRANSACTION IS A REQUEST TO SEE IT MOVE.
   *
   * The page used to decide this inline, with `auto && steps.length > 1`. A
   * step COUNT was standing in for drawability, and it is not the same
   * question: the securities saga is one row on the customer's shard, so
   * Products.settleFill claims a single transaction and the whole nine hop
   * loop arrives as ONE step. Every settlement therefore selected, highlighted
   * a row, and sat perfectly still. So did every purely local transfer.
   *
   * The honest test is "does this trace produce hops on this graph", which is
   * exactly what journey() already answers. Asking it is the fix.
   *
   * The second half matters as much: stopFirst is unconditional. The old
   * branch skipped teardown whenever it skipped play, so choosing an
   * undrawable transaction left the PREVIOUS journey's balls flying under a
   * panel describing the new one · the map and the panel disagreeing is the
   * one state this view must never be in.
   */
  function playDecision(opts) {
    var o = opts || {};
    var steps = o.steps || [];
    if (!steps.length) {
      return { play: false, stopFirst: true, canDraw: false,
               reason: 'no recorded steps for this transaction' };
    }
    var canDraw = journey(steps, o.graph).hops.length > 0;
    if (!canDraw) {
      return { play: false, stopFirst: true, canDraw: false,
               reason: 'no map animation for this one · ' +
                       steps.map(function (s) { return s.step; }).join(', ') +
                       ' happens off this diagram' };
    }
    return { play: !!o.auto, stopFirst: true, canDraw: true, reason: '' };
  }

  /** A control that cannot act says so, rather than being a silent no-op. */
  function controlState(opts) {
    var o = opts || {};
    if (!o.hasSelection) {
      return { replay: false, loop: false, reason: 'select a transaction first' };
    }
    if (!o.canDraw) {
      return { replay: false, loop: false,
               reason: 'nothing to animate · this transaction has no drawable hops' };
    }
    return { replay: true, loop: true, reason: '' };
  }

  /*
   * WHO OWNS THE MAP WHEN A LIVE EVENT LANDS.
   *
   * Three rules, and the page had none of them in one place:
   *   a hidden tab is not watched, so nothing animates and nothing is owed,
   *   a running journey is being watched, so the event waits its turn,
   *   an explicit selection outranks passing traffic, so live events do not
   *   paint another transaction's balls across the path the visitor chose.
   * Only the idle, visible, unselected map auto plays.
   */
  function liveEventPolicy(opts) {
    var o = opts || {};
    if (!o.tabVisible) return { animate: false, select: false, keep: false };
    if (o.running)     return { animate: false, select: false, keep: true };
    /* An idle map with a trace OPEN belongs to that trace. Keeping these to
       replay later would mean the visitor closes the panel and is immediately
       shown a burst of unrelated traffic, so they are not owed either. */
    if (o.hasSelection) return { animate: false, select: false, keep: false };
    return { animate: true, select: true, keep: true };
  }

  /** Auto pick opens the tab on something, and yields to every explicit intent. */
  function autoPickPlan(opts) {
    var o = opts || {};
    if (o.autoPicked)   return { pick: false, retry: false, reason: '' };
    if (o.hasSelection) return { pick: false, retry: false, reason: '' };
    if (o.deepLinkTx)   return { pick: false, retry: false, reason: '' };
    if (o.following)    return { pick: false, retry: false, reason: '' };
    if (o.rows === 0) {
      var attempt = o.attempt || 0;
      return attempt >= 6
        ? { pick: false, retry: false, reason: 'no traces yet · send some money on the App tab' }
        : { pick: false, retry: true, reason: '' };
    }
    return { pick: true, retry: false, reason: '' };
  }

  /* A deep link is a request to SEE a thing, and the thing may be a specific
     transaction. `#xray` alone behaves as an ordinary tab switch. */
  var HASH_TABS = ['xray', 'quiz', 'console', 'app'];
  function parseXrayHash(hash) {
    var h = String(hash || '').replace(/^#/, '');
    if (!h) return { tab: null, tx: null };
    var tx = null;
    var q = h.indexOf('?');
    if (q >= 0) {
      var m = /(?:^|&)tx=([^&]+)/.exec(h.slice(q + 1));
      if (m) tx = decodeURIComponent(m[1]);
      h = h.slice(0, q);
    }
    var slash = h.indexOf('/');
    if (slash >= 0) { if (!tx && h.slice(slash + 1)) tx = decodeURIComponent(h.slice(slash + 1)); h = h.slice(0, slash); }
    return { tab: HASH_TABS.indexOf(h) >= 0 ? h : null, tx: tx };
  }

  /* Clicking a box on the map is an INSPECT gesture that becomes a play only
     when it is unambiguous. One transaction touching the node is a journey the
     visitor plainly means; several is a filter, and filtering must never stop
     the balls that are already flying. */
  function mapClickPlan(opts) {
    var o = opts || {};
    var uniq = [];
    (o.txs || []).forEach(function (t) { if (t && uniq.indexOf(t) < 0) uniq.push(t); });
    if (uniq.length === 1) return { mode: 'play', tx: uniq[0], txs: uniq };
    if (uniq.length > 1)   return { mode: 'filter', tx: null, txs: uniq };
    return { mode: 'inspect', tx: null, txs: [] };
  }

  /* Leaving is a STOP, not a pause with a backlog: the queued remainder of an
     old run must never fire on return. What survives is the selection and the
     visitor's standing request to keep looping. */
  function tabLeavePlan(opts) {
    var o = opts || {};
    return { stop: true, keepSelection: true, loopArmed: !!o.looping };
  }

  function tabEnterPlan(opts) {
    var o = opts || {};
    if (o.hasSelection) return { play: !!o.loopArmed, autoPick: false };
    return { play: false, autoPick: true };
  }

  return {
    playDecision: playDecision,
    controlState: controlState,
    liveEventPolicy: liveEventPolicy,
    autoPickPlan: autoPickPlan,
    parseXrayHash: parseXrayHash,
    mapClickPlan: mapClickPlan,
    tabLeavePlan: tabLeavePlan,
    tabEnterPlan: tabEnterPlan,
    makeApi: makeApi,
    mapGraph: mapGraph,
    journey: journey,
    plannedPath: plannedPath,
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
