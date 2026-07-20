package dev.minibank.broker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minibank.ledger.Json;
import dev.minibank.ledger.PriceFeed;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * THE BROKER'S HTTP CONTRACT.
 *
 * Same shape as the rest of the bank: the JDK's HttpServer, a virtual thread
 * per request, hand-written JSON, no framework. The service is reachable
 * only by name inside the compose network and through the app, which is why
 * it carries no auth of its own · the honest note being that "not exposed"
 * is a deployment property, not a security property, and the day this is
 * public it needs a real gate. Accounts.link is where that goes.
 */
public final class BrokerApi {

    private final Broker broker;
    private final CallerIdentity identity;

    /** Anonymous: the behaviour this service shipped with. */
    public BrokerApi(Broker broker) {
        this(broker, CallerIdentity.ANONYMOUS);
    }

    public BrokerApi(Broker broker, CallerIdentity identity) {
        this.broker = broker;
        this.identity = identity;
    }

    /**
     * Which book this request is allowed to touch.
     *
     * A token identifies its own customer and that wins; without one the
     * query parameter stands, exactly as before. See CallerIdentity.resolve
     * for why this is settled now rather than on activation day.
     */
    private Long caller(HttpExchange ex, Long requested) {
        Optional<Long> identified = identity.customerFor(ex.getRequestHeaders().getFirst("Authorization"));
        if (identified.isPresent()) return identified.get();
        return requested;
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        route(server, "/health", ex -> json(ex, 200, "{\"ok\":true,\"venue\":\"" + broker.venue().name() + "\"}"));
        route(server, "/api/instruments", this::instruments);
        route(server, "/api/orders", this::orders);
        route(server, "/api/positions", this::positions);
        route(server, "/api/portfolio", this::portfolio);
        route(server, "/api/watchlist", this::watchlist);
        route(server, "/api/link", this::link);
        route(server, "/api/audit", this::audit);
        // the chart the portfolio screen draws · served from THIS process
        // rather than fetched from the ledger's copy, because a page on :8091
        // calling :8080 is cross-origin and the cure for that is CORS, which
        // is a much bigger door than this needs. Same jar, same Redis cache,
        // no HTTP hop.
        route(server, "/api/prices/history", BrokerApi::priceHistory);
        // last, and a prefix rather than an exact path, so it never shadows
        // anything above it
        route(server, "/portfolio", BrokerApi::staticFile);

        server.start();
        return server;
    }

    // ------------------------------------------------------------------ routes

    private void instruments(HttpExchange ex) throws Exception {
        List<Catalog.Instrument> shelf = Catalog.all();
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        // the same fan-out the watchlist uses, and for the same reason · an
        // expired contract is not asked about at all, because Yahoo answers
        // 404 for one and PriceFeed's upstream-down branch would hand back its
        // final premium labelled 'cached'
        Map<String, PriceFeed.Px> marks = PriceFeed.getAll(shelf.stream()
                .filter(i -> !i.expiredAsOf(asOf))
                .map(i -> i.symbol().toLowerCase(Locale.ROOT)).toList());
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Catalog.Instrument i : shelf) {
            if (!first) b.append(',');
            first = false;
            boolean expired = i.expiredAsOf(asOf);
            PriceFeed.Px px = expired ? null : marks.get(i.symbol().toLowerCase(Locale.ROOT));
            b.append("{\"symbol\":\"").append(i.symbol())
             .append("\",\"name\":").append(text(i.displayName()))
             .append(",\"exchange\":").append(text(i.exchange()))
             .append(",\"kind\":\"").append(i.kind())
             .append("\",\"assetCode\":\"").append(i.assetCode())
             .append("\",\"settleCcy\":\"").append(i.settleCcy())
             .append("\",\"price\":").append(money(px == null ? null : px.price()))
             // the shelf offers these for purchase, so "what is this worth"
             // and "when did we last really see that" have to travel together
             .append(",\"priceSource\":").append(text(
                     expired ? "expired" : px == null ? "unavailable" : px.source()))
             .append(",\"multiplier\":\"").append(plain(i.multiplier())).append('"')
             .append(",\"expiresOn\":").append(i.expiresOn() == null
                     ? "null" : "\"" + i.expiresOn() + "\"")
             .append("}");
        }
        json(ex, 200, b.append(']').toString());
    }

    /** POST places an order, GET lists them. */
    private void orders(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) {
            Long customer = caller(ex, longParam(ex, "customer"));
            if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Broker.Order o : Broker.orders(customer, 50)) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":\"").append(o.id())
                 .append("\",\"clientOrderId\":\"").append(Json.esc(o.clientOrderId()))
                 .append("\",\"symbol\":\"").append(o.symbol())
                 .append("\",\"side\":\"").append(o.side())
                 .append("\",\"status\":\"").append(o.status())
                 .append("\",\"venue\":\"").append(Json.esc(o.venueName()))
                 .append("\",\"qty\":").append(o.qty() == null ? "null" : "\"" + plain(o.qty()) + "\"")
                 .append(",\"notional\":").append(o.notional() == null ? "null" : "\"" + plain(o.notional()) + "\"")
                 .append(",\"rejectReason\":").append(o.rejectReason() == null
                         ? "null" : "\"" + Json.esc(o.rejectReason()) + "\"")
                 .append('}');
            }
            json(ex, 200, b.append(']').toString());
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"GET or POST\"}"); return; }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String clientOrderId = Json.str(body, "clientOrderId");
        String symbol = Json.str(body, "symbol");
        String side = Json.str(body, "side");
        String type = Json.str(body, "type");
        Long customer = caller(ex, asLong(Json.num(body, "customer")));
        if (clientOrderId == null || symbol == null || side == null || customer == null) {
            json(ex, 400, "{\"error\":\"need clientOrderId, customer, symbol, side\"}");
            return;
        }
        if (!"buy".equals(side) && !"sell".equals(side)) {
            json(ex, 400, "{\"error\":\"side must be buy or sell\"}");
            return;
        }
        symbol = symbol.toUpperCase();
        // LISTED IS NOT THE SAME AS TRADABLE, and this used to ask only the
        // first question. The row is not deleted when a contract expires · it
        // cannot be, because a customer may still hold the position and every
        // screen needs its name, its contract size and its expiry date to say
        // so. So Catalog.exists stayed true for a dead contract and it went on
        // being buyable. Broker.place holds the authoritative gate; this one
        // is the pre-flight, and it exists for the same reason the registry
        // check in the bank's /api/trade does: refusing here costs the
        // customer a 400 instead of a rejected order they have to go and read.
        Catalog.Instrument listed = Catalog.find(symbol);
        if (listed == null) { json(ex, 400, "{\"error\":\"not a listed instrument: " + Json.esc(symbol) + "\"}"); return; }
        if (listed.expiredAsOf(LocalDate.now(ZoneOffset.UTC))) {
            json(ex, 400, "{\"error\":\"" + Json.esc(symbol) + " expired on " + listed.expiresOn()
                    + " · an expired contract no longer trades\"}");
            return;
        }

        BigDecimal qty = decimal(Json.str(body, "qty"));
        BigDecimal notional = decimal(Json.str(body, "notional"));
        if (qty == null && notional == null) {
            json(ex, 400, "{\"error\":\"need qty or notional\"}");
            return;
        }
        BigDecimal limitPx = decimal(Json.str(body, "limitPx"));
        String orderType = type == null ? "market" : type;
        if ("limit".equals(orderType) && limitPx == null) {
            json(ex, 400, "{\"error\":\"a limit order needs limitPx\"}");
            return;
        }

        try {
            Broker.Order o = broker.place(clientOrderId, customer, symbol, side,
                    qty, notional, orderType, limitPx);
            json(ex, 200, "{\"result\":\"" + o.status() + "\",\"id\":\"" + o.id()
                    + "\",\"symbol\":\"" + o.symbol() + "\",\"side\":\"" + o.side()
                    + "\",\"venue\":\"" + Json.esc(o.venueName()) + "\""
                    + (o.rejectReason() == null ? "" : ",\"error\":\"" + Json.esc(o.rejectReason()) + "\"")
                    + "}");
        } catch (IllegalArgumentException e) {
            json(ex, 409, "{\"result\":\"rejected\",\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /**
     * Positions, marked to the live price.
     *
     * The stored numbers are qty, cost basis and realised P&L · all facts
     * about the past. Market value and unrealised P&L are computed HERE, on
     * every read, because they are opinions about right now and storing an
     * opinion is how a stale one gets believed.
     */
    private void positions(HttpExchange ex) throws Exception {
        Long customer = caller(ex, longParam(ex, "customer"));
        if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }

        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        Map<String, Catalog.Instrument> catalog = Catalog.bySymbol();
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        for (Broker.Position p : Broker.positions(customer)) {
            Portfolio.Quote q = quote(p.symbol());
            Catalog.Instrument meta = catalog.get(p.symbol());
            // THE SAME THREE REFUSALS THE PORTFOLIO PATH MAKES, and they have
            // to be made here too: this endpoint is a second consumer, and a
            // multiplier honoured in one valuation and not the other is how a
            // 100x error survives being fixed once. No mark, no known contract
            // size, or an expired contract · each of them means we cannot state
            // a value, and none of them means the value is zero.
            boolean expired = meta != null && meta.expiredAsOf(asOf);
            BigDecimal px = q.priced() && !expired ? q.price() : null;
            BigDecimal value = (px == null || meta == null) ? null
                    : p.qty().multiply(px).multiply(meta.multiplier()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal unrealized = value == null ? null : value.subtract(p.costBasis()).setScale(2, RoundingMode.HALF_UP);
            if (!first) b.append(',');
            first = false;
            b.append("{\"symbol\":\"").append(p.symbol())
             .append("\",\"qty\":\"").append(plain(p.qty()))
             // AVG COST IS PER UNIT OF THE INSTRUMENT · costBasis/qty, and
             // costBasis carries the multiplier because Broker.consideration
             // applied it at fill time. So for an option this is euros per
             // CONTRACT while `price` one field down is the premium per
             // SHARE, and the two differ by the contract size. They are
             // adjacent and both denominated in euros, which is exactly how a
             // reader concludes a flat position has collapsed 99%.
             //
             // multiplier and expiresOn are shipped so that is checkable:
             // without them a consumer of this endpoint cannot reconcile
             // qty * price against value by any means, and /api/portfolio has
             // carried both all along. The field names cannot be changed
             // without breaking every caller, so the reconciliation has to be
             // possible from the payload instead.
             .append("\",\"avgCost\":\"").append(plain(p.averageCost().setScale(2, RoundingMode.HALF_UP)))
             .append("\",\"multiplier\":").append(meta == null
                     ? "null" : "\"" + plain(meta.multiplier()) + "\"")
             .append(",\"expiresOn\":").append(meta == null || meta.expiresOn() == null
                     ? "null" : "\"" + meta.expiresOn() + "\"")
             .append(",\"costBasis\":\"").append(plain(p.costBasis().setScale(2, RoundingMode.HALF_UP)))
             .append("\",\"price\":").append(money(px))
             .append(",\"value\":").append(money(value))
             .append(",\"unrealized\":").append(money(unrealized))
             .append(",\"realized\":\"").append(plain(p.realizedPnl().setScale(2, RoundingMode.HALF_UP)))
             // where the mark came from · without this a 'fallback' price
             // (a hardcoded constant used when every feed is down) is
             // indistinguishable from a live one, and the value beside it
             // looks like a fact
             .append("\",\"priceSource\":\"").append(expired ? "expired" : q.source())
             .append("\"}");
        }
        json(ex, 200, b.append(']').toString());
    }

    /**
     * THE PORTFOLIO SCREEN, in one response.
     *
     * One request because the alternative is a screen that renders in four
     * stages and shows a different total in each · the totals are computed
     * here, from unrounded numbers, rather than re-derived on the client from
     * a column of already-rounded cents.
     *
     * What is NOT in here is cash, and that is the interesting part. Net
     * liquidation is securities plus cash; cash is a balance; balances live
     * in the ledger and the broker has no way to read them. It could call the
     * ledger over HTTP, and that would turn a clean service boundary into a
     * synchronous dependency for a read that is allowed to be incomplete. So
     * cash comes back null with a note saying why, the totals are labeled
     * securities-only, and the screen can show a dash. An invented cash
     * balance would be the single most expensive lie on this page.
     */
    private void portfolio(HttpExchange ex) throws Exception {
        Long customer = caller(ex, longParam(ex, "customer"));
        if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }

        List<Broker.Position> positions = Broker.positions(customer);
        Instant since = dayStart();
        Map<String, Broker.DayFlow> flows = Broker.flowsSince(customer, since);

        // WHAT NEEDS A QUOTE IS NOT THE SAME AS WHAT IS DRAWN, and gating on
        // qty alone made the closed-position day-change branch in
        // Portfolio.build unreachable in production.
        //
        // Broker.positions deliberately returns qty=0 rows (WHERE qty <> 0 OR
        // realized_pnl <> 0), because a position sold to flat today still
        // moved money today and its day P&L belongs in the headline. That
        // branch needs a mark and a prior close to compute the leg it traded
        // out of. Skipping the quote handed it Quote.none(), which failed
        // q.observed(), which incremented withoutPrevClose, which nulls the
        // WHOLE book's day change for the rest of the UTC day · while the
        // remaining holdings each displayed their own day change perfectly
        // well, because the per-row block computes independently. The page
        // then blamed the missing headline on "1 holding(s) had no prior
        // close" and drew no such holding, because there is no row for a
        // position you no longer hold.
        //
        // So: quote anything still held, and anything that TRADED inside the
        // window, which is exactly the set Portfolio.build asks about. flows
        // is already in hand above, so this costs no extra query.
        Map<String, Portfolio.Quote> quotes = new HashMap<>();
        for (String symbol : symbolsNeedingQuotes(positions, flows)) quotes.put(symbol, quote(symbol));

        // the SAME day boundary the flows were taken from · asking the clock a
        // second time here would let a request straddling UTC midnight expire
        // a contract against one date while valuing its day against another
        LocalDate asOf = LocalDate.ofInstant(since, ZoneOffset.UTC);
        Portfolio.Snapshot snap = Portfolio.build(positions, Catalog.bySymbol(), quotes, flows, asOf);
        Portfolio.Aggregate a = snap.aggregate();

        StringBuilder b = new StringBuilder();
        b.append("{\"customer\":").append(customer)
         .append(",\"baseCcy\":\"EUR\"")
         .append(",\"asOf\":\"").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append('"')
         .append(",\"dayStart\":\"").append(since).append('"')
         .append(",\"aggregate\":{")
         .append("\"marketValue\":").append(money(a.marketValue()))
         .append(",\"costBasis\":").append(money(a.costBasis()))
         .append(",\"unrealized\":").append(money(a.unrealized()))
         .append(",\"unrealizedPct\":").append(money(a.unrealizedPct()))
         .append(",\"realized\":").append(money(a.realized()))
         .append(",\"dayChange\":").append(money(a.dayChange()))
         // the day as a percentage of the prior close's value · withheld with
         // dayChange, and also when the book was opened entirely today and so
         // has no prior-close value to be a fraction of
         .append(",\"dayChangePct\":").append(money(a.dayChangePct()))
         .append(",\"cash\":null")
         .append(",\"cashNote\":\"cash is a ledger balance and the broker cannot read the ledger's "
                 + "database · totals below are securities only\"")
         .append(",\"holdings\":").append(a.holdings())
         .append(",\"unpriced\":").append(a.unpriced())
         .append(",\"withoutPrevClose\":").append(a.withoutPrevClose())
         // provenance, so the screen can say WHY a total is missing or soft
         .append(",\"fabricated\":").append(a.fabricated())
         .append(",\"stale\":").append(a.stale())
         .append(",\"closedPositions\":").append(a.closedPositions())
         // contracts past their expiry · held, not valued, and the reason a
         // total may be withheld even when every feed answered
         .append(",\"expired\":").append(a.expired())
         .append("},\"holdings\":[");

        boolean first = true;
        for (Portfolio.Holding h : snap.holdings()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"symbol\":\"").append(Json.esc(h.symbol())).append('"')
             .append(",\"name\":").append(text(h.name()))
             .append(",\"exchange\":").append(text(h.exchange()))
             .append(",\"kind\":").append(text(h.kind()))
             .append(",\"qty\":\"").append(plain(h.qty())).append('"')
             .append(",\"avgCost\":").append(money(h.avgCost()))
             .append(",\"price\":").append(money(h.price()))
             .append(",\"priceSource\":").append(text(h.priceSource()))
             .append(",\"value\":").append(money(h.value()))
             .append(",\"costBasis\":").append(money(h.costBasis()))
             .append(",\"unrealized\":").append(money(h.unrealized()))
             .append(",\"unrealizedPct\":").append(money(h.unrealizedPct()))
             .append(",\"realized\":").append(money(h.realized()))
             .append(",\"prevClose\":").append(money(h.prevClose()))
             .append(",\"dayChange\":").append(money(h.dayChange()))
             .append(",\"dayChangePct\":").append(money(h.dayChangePct()))
             .append(",\"dayBasis\":").append(text(h.dayBasis()))
             // how many units of the underlying one unit of this instrument
             // controls · a screen needs it to explain why value is not
             // qty * price, and it is 1 for everything that is not a contract
             .append(",\"multiplier\":").append(h.multiplier() == null
                     ? "null" : "\"" + plain(h.multiplier()) + "\"")
             .append(",\"expiresOn\":").append(h.expiresOn() == null
                     ? "null" : "\"" + h.expiresOn() + "\"")
             .append('}');
        }

        // THE BANDS. Subtotals travel with the rows in the same response for
        // the same reason the aggregate does: a screen that reduced the rows
        // itself would be summing a column of already-rounded cents and would
        // have to re-implement the withholding rule in JavaScript, where it
        // would eventually be re-implemented slightly differently. Note there
        // are no holdings nested in here · a group names its kind and the rows
        // carry theirs, so nothing is serialised twice and the two cannot
        // disagree about which band a row is in.
        b.append("],\"groups\":[");
        first = true;
        for (Portfolio.Group g : snap.groups()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"kind\":").append(text(g.kind()))
             .append(",\"label\":\"").append(Json.esc(g.label())).append('"')
             .append(",\"holdings\":").append(g.holdings())
             .append(",\"marketValue\":").append(money(g.marketValue()))
             .append(",\"costBasis\":").append(money(g.costBasis()))
             .append(",\"unrealized\":").append(money(g.unrealized()))
             .append(",\"unrealizedPct\":").append(money(g.unrealizedPct()))
             .append(",\"dayChange\":").append(money(g.dayChange()))
             .append(",\"dayChangePct\":").append(money(g.dayChangePct()))
             // why THIS band's subtotal is missing, when it is · the aggregate's
             // counts cannot answer that, they are about the whole book
             .append(",\"unpriced\":").append(g.unpriced())
             .append(",\"withoutPrevClose\":").append(g.withoutPrevClose())
             // the third clause of Acc.whole(). Without it a band whose
             // subtotal is withheld only because a constant stood in for a
             // price could name no count and read "0 holding(s)".
             .append(",\"fabricated\":").append(g.fabricated())
             .append(",\"stale\":").append(g.stale())
             .append(",\"expired\":").append(g.expired())
             .append('}');
        }
        json(ex, 200, b.append("]}").toString());
    }

    /**
     * The window the day change is measured over · UTC midnight.
     *
     * Stated plainly because it does not match either instrument perfectly:
     * an equity's prior close is a US session close and bitcoin's reference
     * is a rolling 24 hours. Picking one boundary and publishing it as
     * dayStart is honest; quietly using three different ones per row and
     * aligning them in a table is not. Each holding also carries its own
     * dayBasis so the screen can say which window it actually means.
     */
    private static Instant dayStart() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * WHICH SYMBOLS NEED A MARK · everything held, and everything that traded
     * inside the day window.
     *
     * Pure and package-visible so it can be asserted directly, because this
     * one-line predicate is what made a carefully-written branch of
     * Portfolio.build unreachable in production for as long as it existed.
     *
     * Gating on qty alone looks obviously right: you cannot value what you do
     * not hold. But Broker.positions deliberately returns qty=0 rows, and
     * Portfolio.build has a whole branch for them, because a position sold to
     * flat today still moved money today and its day P&L belongs in the
     * headline. That branch needs a mark and a prior close. Without a quote it
     * got Quote.none(), fell to `agg.withoutPrevClose++`, and Acc.day()
     * returns null whenever that counter is non-zero · so ONE position closed
     * to flat withheld the whole book's day change for the rest of the UTC
     * day, while every remaining holding displayed its own day change
     * perfectly well, and the page blamed a holding that was never drawn.
     *
     * The existing day-honesty test passed throughout, because it hands
     * Portfolio.build a quotes map directly and never exercises this line.
     */
    static java.util.Set<String> symbolsNeedingQuotes(List<Broker.Position> positions,
                                                      Map<String, Broker.DayFlow> flows) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Broker.Position p : positions) {
            Broker.DayFlow f = flows.get(p.symbol());
            boolean tradedToday = f != null && f.qty().signum() != 0;
            if (p.qty().signum() != 0 || tradedToday) out.add(p.symbol());
        }
        return out;
    }

    /** A quote, or an admission that there is not one. Never a zero. */
    private static Portfolio.Quote quote(String symbol) {
        try {
            PriceFeed.Px px = PriceFeed.get(symbol.toLowerCase());
            if (px == null || !px.priced()) return Portfolio.Quote.none();
            return new Portfolio.Quote(px.price(), px.prevClose(), px.source());
        } catch (Exception e) {
            return Portfolio.Quote.none();
        }
    }

    /**
     * THE WATCHLIST · one list, however many browsers.
     *
     * WATCHING IS NOT TRADING, and the schema says so before this handler
     * does. positions.symbol and fills.symbol both REFERENCE instruments;
     * watchlist.symbol is bare TEXT with no foreign key, on purpose. So a
     * customer can follow SPY forever on a shelf that lists two things, and
     * the gate that matters · Catalog.exists in orders() · still refuses to
     * route an order for it.
     *
     * DO NOT ADD A CATALOG CHECK TO THE WRITE PATH BELOW. It looks like a
     * correctness improvement and it is a data-loss bug: the desk's rail is
     * roughly a hundred and twenty tickers against a catalog of two, so
     * validating on insert would silently drop about ninety-eight per cent of
     * what a customer asked to watch. The catalog is consulted on the READ
     * instead, to LABEL each row `tradable`, so a screen can offer "follow"
     * on everything and "buy" only where a buy would actually fill.
     */
    private void watchlist(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) {
            Long customer = caller(ex, longParam(ex, "customer"));
            if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }
            // one catalog read for the whole list, not one per row · the
            // answer is the same for every symbol on the page
            Map<String, Catalog.Instrument> shelf = Catalog.bySymbol();
            List<String> watched = Accounts.watchlist(customer);
            // ONE FAN-OUT, NOT ONE ROUND TRIP PER ROW.
            //
            // This loop used to call PriceFeed.get per symbol, sequentially,
            // each one a live upstream call with a 3s connect and 4s read
            // timeout. The desk's rail is a hundred and nine tickers and its
            // client budget is two seconds, so the shared watchlist could not
            // answer inside it even when every upstream call failed fast ·
            // measured at 5.3s for that rail with every call failing in ~40ms,
            // which is the floor and not the bad case. It timed out on every
            // load, so the desk's bootstrap never reached the branch that
            // adopts the shared list, and the migration behind it never ran.
            // PriceFeed also now backs off symbols it could not price, so a
            // rail full of unlistable indices stops re-asking upstream about
            // every one of them on every poll.
            Map<String, PriceFeed.Px> marks = PriceFeed.getAll(
                    watched.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (String s : watched) {
                if (!first) b.append(',');
                first = false;
                Catalog.Instrument i = shelf.get(s);
                PriceFeed.Px px = marks.get(s.toLowerCase(Locale.ROOT));
                // price stays money(): null for an unpriceable symbol, never
                // 0.00. An index the venue cannot fill is the ordinary case
                // here, so "we have no mark" has to render as a gap
                b.append("{\"symbol\":\"").append(Json.esc(s))
                 .append("\",\"price\":").append(money(px == null ? null : px.price()))
                 // WHERE THE MARK CAME FROM · this used to be thrown away, and
                 // throwing it away is what made this panel the one liar on
                 // the page. A 'cached' mark has no age bound: PriceFeed keeps
                 // serving the last price it really saw for as long as the
                 // upstream stays unreachable, which can be days. The holdings
                 // rows on this same screen badge exactly that condition as
                 // 'last known price'; the watchlist tiles rendered it as a
                 // plain figure, indistinguishable from a live one. Two
                 // honesty standards for one feed on one screen is not a
                 // standard, so the source travels with the number here too.
                 .append(",\"priceSource\":").append(text(px == null ? "unavailable" : px.source()))
                 .append(",\"tradable\":").append(i != null)
                 .append(",\"name\":").append(text(i == null ? null : i.displayName()))
                 .append(",\"exchange\":").append(text(i == null ? null : i.exchange()))
                 .append("}");
            }
            json(ex, 200, b.append(']').toString());
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Long customer = caller(ex, asLong(Json.num(body, "customer")));
        String action = Json.str(body, "action");
        if (customer == null) { json(ex, 400, "{\"error\":\"need customer\"}"); return; }

        // "import" adopts a browser's existing list in one commit · see
        // Accounts.watchAll for why it is additive and safe to repeat.
        // Json.each is a scanner, not a parser, so the symbols arrive as
        // [{"symbol":"SPY"},…] rather than a bare array of strings: the same
        // shape every other batch in this bank uses, for the same reason.
        if ("import".equals(action)) {
            List<String> symbols = Json.each(body, "symbol");
            Accounts.watchAll(customer, symbols);
            json(ex, 200, "{\"result\":\"ok\",\"imported\":" + symbols.size() + "}");
            return;
        }

        String symbol = Json.str(body, "symbol");
        if (symbol == null) { json(ex, 400, "{\"error\":\"need customer, symbol\"}"); return; }
        if ("remove".equals(action)) Accounts.unwatch(customer, symbol);
        else Accounts.watch(customer, symbol);
        json(ex, 200, "{\"result\":\"ok\"}");
    }

    private void link(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) {
            String session = param(ex, "session");
            if (session == null) { json(ex, 400, "{\"error\":\"need ?session=\"}"); return; }
            Long customer = Accounts.customerFor(session);
            json(ex, 200, customer == null ? "{\"customer\":null}" : "{\"customer\":" + customer + "}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String session = Json.str(body, "session");
        // THE SAME PRECEDENCE RULE AS EVERY OTHER WRITE HERE, and this route
        // needed it most. Binding a session to a customer is the one call
        // that decides WHOSE BOOK a session acts on, so trusting the body's
        // customer id let anyone who knew a session string rebind it to any
        // customer they liked · Accounts.link upserts, so it would not even
        // need to win a race. It was the only write that skipped caller(),
        // which is exactly how this kind of hole survives review.
        Long customer = caller(ex, asLong(Json.num(body, "customer")));
        if (session == null || customer == null) { json(ex, 400, "{\"error\":\"need session, customer\"}"); return; }
        Accounts.link(session, customer);
        json(ex, 200, "{\"result\":\"ok\",\"customer\":" + customer + "}");
    }

    /**
     * THE AUDITS, over HTTP · the same shape the ledger's X-ray already uses
     * for sum-zero and drift, so a third invariant reads like the other two.
     *
     * `drifted` is the PROJECTION audit: does the stored position match its
     * own fills? `positionDivergences` is the RECONCILIATION: does the
     * broker's position match the ledger's custody of the same asset? Those
     * are different questions and a service can pass one while failing the
     * other · a position perfectly rebuilt from perfectly recorded fills is
     * still wrong if the customer's holding came from somewhere else
     * entirely. The old audit could not have caught that, because it never
     * looked outside this database.
     *
     * Reported, never repaired. An invariant that fixes what it measures
     * cannot be trusted to measure it, so repair is a separate deliberate act
     * ({@link Backfill}) and this endpoint only ever reads.
     */
    private void audit(HttpExchange ex) throws Exception {
        List<String> drift = Broker.audit();
        StringBuilder b = new StringBuilder("{\"drifted\":").append(drift.size()).append(",\"detail\":[");
        for (int i = 0; i < drift.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(Json.esc(drift.get(i))).append('"');
        }
        b.append(']');

        // The reconciliation needs BOTH books. This process can always read
        // its own; the ledger's shards may not be configured here, and the
        // honest answer to that is null rather than zero · "we did not look"
        // and "we looked and everything agreed" must never render the same.
        try {
            List<Reconciliation.Divergence> d = Reconciliation.divergences();
            b.append(",\"positionDivergences\":").append(d.size()).append(",\"divergenceDetail\":[");
            for (int i = 0; i < d.size(); i++) {
                if (i > 0) b.append(',');
                b.append('"').append(Json.esc(d.get(i).toString())).append('"');
            }
            b.append(']');
            List<String> stuck = Reconciliation.stalled(java.time.Duration.ofMinutes(5));
            b.append(",\"stalledSettlements\":").append(stuck.size());
            dev.minibank.ledger.Metrics.gauge("minibank_position_divergences", "", d.size());
            dev.minibank.ledger.Metrics.gauge("minibank_settlements_stalled", "", stuck.size());

            // THE FOURTH LIST · saga steps that would not complete and were
            // recorded rather than printed. A divergence says the books
            // disagree; this says which step failed to make them agree, which
            // is the difference between knowing there is a problem and knowing
            // where it is.
            try (java.sql.Connection c = BrokerDb.open()) {
                List<dev.minibank.ledger.DeadLetter.Entry> dead = dev.minibank.ledger.DeadLetter.all(c);
                b.append(",\"deadLetters\":").append(dead.size()).append(",\"deadLetterDetail\":[");
                for (int i = 0; i < dead.size(); i++) {
                    if (i > 0) b.append(',');
                    b.append('"').append(Json.esc(dead.get(i).toString())).append('"');
                }
                b.append(']');
                dev.minibank.ledger.Metrics.gauge("minibank_saga_dead_letters", "", dead.size());
            }
        } catch (Exception e) {
            b.append(",\"positionDivergences\":null,\"divergenceUnavailable\":\"")
             .append(Json.esc(String.valueOf(e.getMessage()))).append('"');
        }
        json(ex, 200, b.append('}').toString());
    }

    /** The chart series, straight off the feed this process already caches. */
    private static void priceHistory(HttpExchange ex) throws Exception {
        String asset = param(ex, "asset");
        if (asset == null) { json(ex, 400, "{\"error\":\"need ?asset=\"}"); return; }
        asset = asset.toLowerCase(Locale.ROOT);
        // the whitelist is the CATALOG · an instrument we do not list is not
        // an instrument we draw, and it keeps this route from becoming an
        // open proxy to Yahoo with our IP on it
        if (!Catalog.exists(asset.toUpperCase(Locale.ROOT))) {
            json(ex, 400, "{\"error\":\"not a listed instrument\"}");
            return;
        }
        json(ex, 200, "{\"asset\":\"" + Json.esc(asset) + "\",\"points\":"
                + PriceFeed.historyJson(asset) + "}");
    }

    // ------------------------------------------------------------------ static

    /**
     * The screen itself, off the classpath · the ledger's staticFile, one
     * service over.
     *
     * ROOTED AT /web-broker, NOT /web, and that is load-bearing. The broker
     * and the ledger are the same Maven module, so /web/index.html on the
     * classpath is the BANK's page; a broker rooted at /web would serve the
     * bank's UI on the broker's port, which is confusing at best and a
     * spoofing surface at worst. Its own prefix keeps the two apart.
     */
    private static void staticFile(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/portfolio") || path.equals("/portfolio/")) path = "/portfolio.html";
        else path = path.substring("/portfolio".length());
        if (path.contains("..")) { send(ex, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8)); return; }
        try (InputStream in = BrokerApi.class.getResourceAsStream("/web-broker" + path)) {
            if (in == null) {
                send(ex, 404, "application/json; charset=utf-8",
                        "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, contentType(path), in.readAllBytes());
        }
    }

    private static String contentType(String path) {
        return path.endsWith(".html") ? "text/html; charset=utf-8"
             : path.endsWith(".js") ? "application/javascript"
             : path.endsWith(".css") ? "text/css"
             : path.endsWith(".svg") ? "image/svg+xml"
             : path.endsWith(".png") ? "image/png"
             : path.endsWith(".json") ? "application/manifest+json"
             : "application/octet-stream";
    }

    // ------------------------------------------------------------------ plumbing

    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private static void route(HttpServer server, String path, Handler h) {
        server.createContext(path, ex -> {
            try (InputStream ignored = ex.getRequestBody()) {
                h.handle(ex);
            } catch (Exception e) {
                try {
                    json(ex, 500, "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}");
                } catch (IOException io) {
                    ex.close();
                }
            }
        });
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    /** The write that is not necessarily JSON · a page has to come out somehow. */
    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * Money as a quoted string, absence as a bare null.
     *
     * The quotes are the house style and they are not decoration: a price
     * that goes through a JSON number is a price that went through a double,
     * and the eighth decimal of a bitcoin position is real money. null is
     * unquoted because it is not a value · "null" as a string would sort,
     * concatenate and render as text, which is exactly the bug this is
     * trying to avoid.
     *
     * Trailing zeros are KEPT, unlike plain() · these arrive already scaled
     * to cents and "1005.00" is what a money column shows. plain() would
     * strip it to "1005" and the screen would grow a ragged decimal point.
     */
    private static String money(BigDecimal v) {
        return v == null ? "null" : "\"" + v.toPlainString() + "\"";
    }

    private static String text(String v) {
        return v == null ? "null" : "\"" + Json.esc(v) + "\"";
    }

    private static String param(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String p : q.split("&"))
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }

    private static Long longParam(HttpExchange ex, String name) {
        String v = param(ex, name);
        try {
            return v == null ? null : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(String v) {
        try {
            return v == null || v.isBlank() ? null : Long.valueOf(new BigDecimal(v).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String v) {
        try {
            return v == null || v.isBlank() ? null : new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // mark() lived here: one symbol, one upstream call, price only. Both of
    // its callers now go through PriceFeed.getAll, which fans the batch out
    // across virtual threads and keeps the Px so the SOURCE survives the trip
    // to the screen. Dropping the source was the whole of the watchlist's
    // honesty bug, and a helper that structurally could not carry it was the
    // reason the bug was easy to write.

    private static String plain(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
