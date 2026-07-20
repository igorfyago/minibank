package dev.minibank.broker;

import dev.minibank.ledger.Cache;
import dev.minibank.ledger.Json;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE OPTION CHAIN · Yahoo's, resolved, gated and cached.
 *
 * Two jobs, one upstream:
 *
 *   LISTING   an OCC contract nobody has listed yet is resolved against its
 *             underlying's chain and listed on first trade · Catalog.list,
 *             the whole act, registry first. A contract the chain does not
 *             carry is REFUSED, never guessed at. See listOnFirstTrade.
 *
 *   SERVING   the chain screen's data · expirations, one expiry's calls and
 *             puts, and the underlying's intraday chart · proxied server-side
 *             so the browser never talks to Yahoo and this service never
 *             becomes an open proxy: only the hardcoded underlyings below are
 *             ever forwarded upstream.
 *
 * THE UNDERLYINGS ARE AN ALLOWLIST, not a discovery. Free-form symbols never
 * reach the server-to-Yahoo path: the UI picks from what this class offers,
 * an unknown underlying is a 404, and the only OCC symbols that can list are
 * members of an allowlisted underlying's own chain. That membership test is
 * also what keeps the listing honest · the contract's size and expiry come
 * from the venue's data about THAT contract, not from a default.
 *
 * AUTH. Yahoo's v7 options endpoint stopped being keyless: it wants a session
 * cookie plus a "crumb" tied to it, or it answers 401 Invalid Crumb. The
 * handshake (fc.yahoo.com sets the cookie, v1/test/getcrumb mints the crumb)
 * is done lazily, held in-process, and redone exactly once when a call comes
 * back 401 · designed as refresh-on-401 rather than a TTL, because the
 * lifetime is not documented and guessing one would just be a slower 401.
 * The v8 chart endpoint the chart route uses is still keyless, exactly as
 * PriceFeed's equity path has always found it.
 *
 * FAILURE SHAPES, observed not assumed: v7 answers 200 for every DATA
 * problem · a bad symbol is result:[], a real symbol with no options is
 * empty arrays, and an unlisted ?date= is ECHOED BACK with empty calls and
 * puts. Only auth 401s. So nothing here trusts a 200; everything inspects,
 * and an expiry is validated against the chain's own expiration list before
 * it is ever forwarded, because the alternative is a silently empty screen.
 */
public final class OptionChain {

    /** One tradable underlying: how the UI names it, how Yahoo quotes it. */
    public record Underlying(String root, String yahooSymbol) {}

    /**
     * THE ALLOWLIST. XSP is the trap that makes the mapping load-bearing:
     * bare "XSP" resolves at Yahoo to a non-tradable ECNQUOTE with an empty
     * chain and a null price · no error, just nothing · while "^XSP" is the
     * index with the full chain. The chain's own contractSymbols use the OCC
     * root WITHOUT the caret, which is why root and yahooSymbol are two
     * fields and not one.
     */
    private static final Map<String, Underlying> UNDERLYINGS = new LinkedHashMap<>();
    static {
        UNDERLYINGS.put("XSP", new Underlying("XSP", "^XSP"));
        UNDERLYINGS.put("AAPL", new Underlying("AAPL", "AAPL"));
    }

    private OptionChain() {}

    public static List<String> underlyings() {
        return List.copyOf(UNDERLYINGS.keySet());
    }

    public static Underlying underlying(String name) {
        return name == null ? null : UNDERLYINGS.get(name.toUpperCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // OCC symbols · ROOT + yymmdd + C/P + strike*1000 zero-padded to 8
    // ------------------------------------------------------------------

    /** Letters-only root, deliberately: every root this allowlist carries is
     *  alphabetic, and letting digits into the root would make the boundary
     *  against the six date digits ambiguous. */
    private static final Pattern OCC = Pattern.compile("([A-Z.]{1,6})(\\d{6})([CP])(\\d{8})");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    public record Occ(String root, LocalDate expiry, boolean call, BigDecimal strike) {
        public String kindWord() { return call ? "call" : "put"; }
    }

    /** Parse an OCC contract symbol, or null when the shape is not OCC. */
    public static Occ parseOcc(String symbol) {
        if (symbol == null) return null;
        Matcher m = OCC.matcher(symbol.trim().toUpperCase(Locale.ROOT));
        if (!m.matches()) return null;
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(m.group(2), YYMMDD);
        } catch (Exception e) {
            return null;    // 999999 is date-shaped and not a date
        }
        BigDecimal strike = new BigDecimal(m.group(4)).movePointLeft(3).stripTrailingZeros();
        return new Occ(m.group(1), expiry, "C".equals(m.group(3)), strike);
    }

    public static boolean isOcc(String symbol) {
        return parseOcc(symbol) != null;
    }

    /** A listing this class declined to make, with the reason the customer
     *  gets to read. Never thrown for infrastructure failures · those stay
     *  exceptions, because "we refuse" and "we could not ask" are different
     *  answers and only the first one is final. */
    public static final class Refused extends RuntimeException {
        public Refused(String reason) { super(reason); }
    }

    // ------------------------------------------------------------------
    // the upstream · seam first, Yahoo second
    // ------------------------------------------------------------------

    /**
     * WHERE CHAIN AND CHART BODIES COME FROM · a seam, so the lessons can
     * hand this class a body they wrote and assert what it does with it,
     * rather than asserting whatever Yahoo happened to be serving while the
     * suite ran. Production never touches the setter.
     */
    interface Source {
        String chainBody(String yahooSymbol, Long epochDate) throws Exception;
        /** rangeQuery is one of CHART_RANGES' values, already validated ·
         *  the seam never sees a free-form string. */
        String chartBody(String yahooSymbol, String rangeQuery) throws Exception;
    }

    private static volatile Source source = new YahooSource();

    static void setSource(Source s) {
        source = s == null ? new YahooSource() : s;
    }

    /** The real upstream, with the cookie-and-crumb handshake. */
    static final class YahooSource implements Source {

        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(3)).build();
        private volatile String crumb;

        @Override
        public String chainBody(String yahooSymbol, Long epochDate) throws Exception {
            String c = crumb;
            if (c == null) c = handshake();
            HttpResponse<String> r = get(chainUrl(yahooSymbol, epochDate, c));
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                // the crumb died with its cookie · one fresh handshake, one
                // retry, and a second failure is a real failure
                c = handshake();
                r = get(chainUrl(yahooSymbol, epochDate, c));
            }
            if (r.statusCode() != 200) throw new IllegalStateException("chain HTTP " + r.statusCode());
            return r.body();
        }

        @Override
        public String chartBody(String yahooSymbol, String rangeQuery) throws Exception {
            // keyless, same endpoint family PriceFeed already uses in prod
            HttpResponse<String> r = get("https://query1.finance.yahoo.com/v8/finance/chart/"
                    + URLEncoder.encode(yahooSymbol, StandardCharsets.UTF_8)
                    + "?" + rangeQuery);
            if (r.statusCode() != 200) throw new IllegalStateException("chart HTTP " + r.statusCode());
            return r.body();
        }

        private String chainUrl(String yahooSymbol, Long epochDate, String crumb) {
            // the crumb is URL-ENCODED because it can contain a slash ·
            // observed ("KgMv/NOUwtC"), not hypothetical
            return "https://query1.finance.yahoo.com/v7/finance/options/"
                    + URLEncoder.encode(yahooSymbol, StandardCharsets.UTF_8)
                    + "?crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8)
                    + (epochDate == null ? "" : "&date=" + epochDate);
        }

        private synchronized String handshake() throws Exception {
            // fc.yahoo.com answers 404 AND sets the session cookie · the
            // status is not the point, the Set-Cookie is, so no status check
            get("https://fc.yahoo.com");
            HttpResponse<String> r = get("https://query1.finance.yahoo.com/v1/test/getcrumb");
            if (r.statusCode() != 200 || r.body() == null || r.body().isBlank())
                throw new IllegalStateException("no crumb (HTTP " + r.statusCode() + ")");
            crumb = r.body().trim();
            return crumb;
        }

        private HttpResponse<String> get(String url) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(4))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("User-Agent", "Mozilla/5.0 (minibank-demo)")
                            .header("Accept", "application/json,text/*").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    // ------------------------------------------------------------------
    // parsing · a scanner in the house style, not a JSON library
    // ------------------------------------------------------------------

    /**
     * One contract row as the chain states it. Every numeric field except
     * strike and expiration is NULLABLE, because Yahoo omits fields per row
     * (volume and openInterest observed absent) and an absent number must
     * stay absent · rendering it as 0 is the exact fabrication the honesty
     * rules exist to stop.
     */
    record Row(String contractSymbol, BigDecimal strike, BigDecimal bid, BigDecimal ask,
               BigDecimal lastPrice, BigDecimal impliedVolatility, Boolean inTheMoney,
               Long volume, Long openInterest, long expiration, String contractSize) {}

    record Chain(String underlying, List<Long> expirations, BigDecimal underlyingPrice,
                 String marketState, BigDecimal change, BigDecimal changePercent,
                 Long marketTime, String currency, Long expiry, List<Row> calls, List<Row> puts) {}

    private static final Pattern ROW = Pattern.compile("\\{[^{}]*\\}");

    /** Parse a v7 options body. Null when Yahoo has no such symbol at all
     *  (result:[] · a 200, because every v7 data failure is a 200). */
    static Chain parse(String underlying, String body) {
        if (body == null || body.contains("\"result\":[]")) return null;
        List<Long> expirations = longs(str(body, "\"expirationDates\":\\[([0-9,]*)\\]"));
        BigDecimal price = num(body, "\"regularMarketPrice\":([0-9.eE+-]+)");
        String state = str(body, "\"marketState\":\"([A-Z_]+)\"");
        // THE QUOTE'S DAY, for the screen's header · all four observed live in
        // the quote block on both ^XSP and AAPL (2026-07-20), all four
        // NULLABLE, because a field Yahoo does not state is a fact this
        // payload does not claim. First-match-wins over the whole body is
        // safe for the same reason it is for regularMarketPrice: the quote
        // block precedes options[], the per-contract rows spell their fields
        // "change"/"percentChange" without the regularMarket prefix, and the
        // first "currency" in the body is the quote's.
        BigDecimal change = num(body, "\"regularMarketChange\":([0-9.eE+-]+)");
        BigDecimal changePct = num(body, "\"regularMarketChangePercent\":([0-9.eE+-]+)");
        String marketTime = str(body, "\"regularMarketTime\":(\\d+)");
        String currency = str(body, "\"currency\":\"([A-Z]+)\"");

        int opts = body.indexOf("\"options\":[");
        Long expiry = null;
        List<Row> calls = List.of(), puts = List.of();
        if (opts >= 0) {
            String block = body.substring(opts);
            String exp = str(block, "\"expirationDate\":(\\d+)");
            expiry = exp == null ? null : Long.valueOf(exp);
            calls = rows(array(block, "\"calls\":["));
            puts = rows(array(block, "\"puts\":["));
        }
        return new Chain(underlying, expirations, price, state, change, changePct,
                marketTime == null ? null : Long.valueOf(marketTime), currency, expiry, calls, puts);
    }

    /** The array's inner text · rows are flat objects, so the first ']' after
     *  the opener really is the closer. */
    private static String array(String body, String opener) {
        int i = body.indexOf(opener);
        if (i < 0) return "";
        int end = body.indexOf(']', i + opener.length());
        return end < 0 ? "" : body.substring(i + opener.length(), end);
    }

    private static List<Row> rows(String array) {
        List<Row> out = new ArrayList<>();
        Matcher m = ROW.matcher(array);
        while (m.find()) {
            String r = m.group();
            String sym = str(r, "\"contractSymbol\":\"([^\"]+)\"");
            BigDecimal strike = num(r, "\"strike\":([0-9.eE+-]+)");
            String exp = str(r, "\"expiration\":(\\d+)");
            if (sym == null || strike == null || exp == null) continue;   // not a contract row
            String itm = str(r, "\"inTheMoney\":(true|false)");
            String vol = str(r, "\"volume\":(\\d+)");
            String oi = str(r, "\"openInterest\":(\\d+)");
            out.add(new Row(sym, strike,
                    num(r, "\"bid\":([0-9.eE+-]+)"),
                    num(r, "\"ask\":([0-9.eE+-]+)"),
                    num(r, "\"lastPrice\":([0-9.eE+-]+)"),
                    num(r, "\"impliedVolatility\":([0-9.eE+-]+)"),
                    itm == null ? null : Boolean.valueOf(itm),
                    vol == null ? null : Long.valueOf(vol),
                    oi == null ? null : Long.valueOf(oi),
                    Long.parseLong(exp),
                    str(r, "\"contractSize\":\"([^\"]+)\"")));
        }
        return out;
    }

    private static String str(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static BigDecimal num(String body, String regex) {
        String v = str(body, regex);
        try {
            return v == null ? null : new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Long> longs(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) out.add(Long.parseLong(s.trim()));
        return out;
    }

    // ------------------------------------------------------------------
    // LIST ON FIRST TRADE
    // ------------------------------------------------------------------

    /** Contract multiplier for the one contract size this bank supports.
     *  Anything else · or an ABSENT size · refuses; a defaulted multiplier is
     *  a 100x cash error wearing a convenience's clothes. */
    private static final BigDecimal REGULAR = new BigDecimal("100");

    /**
     * Resolve one OCC contract against its underlying's chain and LIST it ·
     * kind option, the chain's contract size, the expiry both the symbol and
     * the chain agree on · on every shard first, then in the broker's
     * catalog, which is Catalog.list's ordering and not repeated here.
     *
     * EXACTLY ONE CONTRACT LISTS, never the chain it came from. A chain is
     * sixteen hundred instruments a customer did not ask for; the trade names
     * one, and one is what becomes real.
     *
     * Refusals ({@link Refused}) are final answers with reasons: not OCC, not
     * an allowlisted underlying, already expired, absent from the chain, a
     * date contradiction between symbol and chain, an unstated or non-REGULAR
     * contract size. Registry refusals (slot collision, currency or
     * multiplier contradiction) surface as IllegalStateException from
     * register(), and both kinds mean NO order and NO fill · the caller gates
     * before any money path runs.
     */
    public static Catalog.Instrument listOnFirstTrade(String symbol) throws SQLException {
        Occ occ = parseOcc(symbol);
        if (occ == null) throw new Refused("'" + symbol + "' is not an OCC option symbol");
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        Underlying u = UNDERLYINGS.get(occ.root());
        if (u == null)
            throw new Refused("options on '" + occ.root() + "' are not supported · known underlyings: "
                    + String.join(", ", UNDERLYINGS.keySet()));

        String displayName = occ.root() + " " + occ.expiry() + " "
                + occ.strike().toPlainString() + " " + occ.kindWord();
        // THE SAME inclusive boundary the whole estate uses · build the
        // candidate and ask IT, rather than keeping a second copy of the rule
        Catalog.Instrument candidate = new Catalog.Instrument(sym, "option", sym, "EUR",
                displayName, "OPR", REGULAR, occ.expiry());
        if (candidate.expiredAsOf(LocalDate.now(ZoneOffset.UTC)))
            throw new Refused("'" + sym + "' expired on " + occ.expiry()
                    + " · an expired contract cannot be listed for trading");

        // the chain FOR THIS EXPIRY · Yahoo echoes an unlisted date back with
        // empty calls and puts, so "absent from the chain" covers both a
        // wrong strike and a wrong date, with the same honest refusal
        long epoch = occ.expiry().toEpochDay() * 86_400L;
        String body;
        try {
            body = source.chainBody(u.yahooSymbol(), epoch);
        } catch (Exception e) {
            // could not ASK is not "no" · but it is not a listing either, and
            // it must not fill. Surface as unavailability, not refusal.
            throw new IllegalStateException("option chain unavailable for " + u.yahooSymbol()
                    + " · cannot resolve '" + sym + "': " + e.getMessage(), e);
        }
        Chain chain = parse(occ.root(), body);
        if (chain == null)
            throw new Refused("no option chain for '" + u.yahooSymbol() + "'");

        Row row = null;
        for (Row r : occ.call() ? chain.calls() : chain.puts())
            if (sym.equals(r.contractSymbol())) { row = r; break; }
        if (row == null)
            throw new Refused("'" + sym + "' is not in the " + u.yahooSymbol()
                    + " chain for " + occ.expiry() + " · only a contract the chain lists can be traded");

        // the chain's own expiration must agree with the OCC-encoded date ·
        // a contradiction means somebody's data is wrong, and money must not
        // move until a human knows whose
        LocalDate chainExpiry = LocalDate.ofEpochDay(Math.floorDiv(row.expiration(), 86_400L));
        if (!chainExpiry.equals(occ.expiry()))
            throw new Refused("'" + sym + "' encodes expiry " + occ.expiry()
                    + " but the chain says " + chainExpiry + " · refusing to list a contradiction");

        if (row.contractSize() == null)
            throw new Refused("the chain states no contract size for '" + sym
                    + "' · a defaulted multiplier misstates every cash leg by exactly the default");
        if (!"REGULAR".equals(row.contractSize()))
            throw new Refused("unsupported contract size '" + row.contractSize() + "' for '" + sym
                    + "' · only REGULAR (100) contracts are supported");

        // the whole act: every shard's registry first, broker catalog second ·
        // if the registry refuses (slot collision, contradiction), nothing
        // becomes tradable and the refusal carries the registry's reason
        Catalog.list(sym, "option", sym, displayName, "OPR", REGULAR, occ.expiry());
        return Catalog.find(sym);
    }

    // ------------------------------------------------------------------
    // THE CHAIN PROXY · trimmed payloads, cached, allowlist-gated
    // ------------------------------------------------------------------

    /** Last good payload per key, served · marked · when the upstream fails.
     *  The same serve-stale doctrine PriceFeed.historyJson already applies. */
    private static final Map<String, String> lastGood = new ConcurrentHashMap<>();

    /** Chains are delayed quotes upstream (15 minutes), so 60 seconds of
     *  cache costs no real freshness and caps Yahoo at one request per
     *  minute per (underlying, expiry) however many browsers are open. */
    private static final int CHAIN_TTL_S = 60;

    /** in-process caches dropped, for tests that need a cold start */
    static void resetLocalCaches() {
        lastGood.clear();
    }

    /**
     * The chain screen's payload for one (underlying, expiry) · or the
     * nearest expiry when none is named. Null only when the upstream failed
     * AND nothing was ever cached, which the API answers as an unavailability
     * rather than an empty chain.
     *
     * A named expiry is VALIDATED against the chain's own expiration list
     * before it is forwarded · Yahoo echoes a bogus date back silently with
     * empty calls and puts, and the difference between "that expiry does not
     * exist" and "this chain is empty" is the difference between a 400 and a
     * lie. Validation failure is {@link Refused}.
     */
    public static String chainJson(String underlyingName, Long expiry) {
        Underlying u = underlying(underlyingName);
        if (u == null)
            throw new Refused("unknown underlying '" + underlyingName + "' · known: "
                    + String.join(", ", UNDERLYINGS.keySet()));
        if (expiry != null) {
            String front = chainJson(underlyingName, null);
            if (front == null) return null;    // cannot validate against a chain we do not have
            List<Long> known = longs(str(front, "\"expirations\":\\[([0-9,]*)\\]"));
            if (!known.contains(expiry))
                throw new Refused(expiry + " is not an expiration of " + u.root()
                        + " · ask /api/options/chain?underlying=" + u.root() + " for the list");
        }
        String key = u.root() + ':' + (expiry == null ? "front" : expiry.toString());
        return Cache.getOrLoad("options:chain", key, CHAIN_TTL_S, () -> buildOrStale(u, expiry, key));
    }

    private static String buildOrStale(Underlying u, Long expiry, String key) {
        try {
            Chain chain = parse(u.root(), source.chainBody(u.yahooSymbol(), expiry));
            if (chain == null) throw new IllegalStateException("no chain in the answer");
            String built = buildChainJson(u, chain, "live");
            lastGood.put(key, built);
            return built;
        } catch (Refused r) {
            throw r;
        } catch (Exception e) {
            // upstream down or answering garbage · the last good chain, MARKED
            // as cached, is more honest than an error and infinitely more
            // honest than an empty one. Nothing cached: null, and the API
            // says unavailable rather than drawing a silent void.
            String stale = lastGood.get(key);
            return stale == null ? null : stale.replaceFirst("\"fetched\":\"live\"", "\"fetched\":\"cached\"");
        }
    }

    private static String buildChainJson(Underlying u, Chain chain, String fetched) {
        StringBuilder b = new StringBuilder(16_384);
        b.append("{\"underlying\":\"").append(u.root())
         .append("\",\"yahooSymbol\":\"").append(Json.esc(u.yahooSymbol()))
         .append("\",\"fetched\":\"").append(fetched)
         .append("\",\"expirations\":[");
        for (int i = 0; i < chain.expirations().size(); i++) {
            if (i > 0) b.append(',');
            b.append(chain.expirations().get(i));
        }
        b.append("],\"expiry\":").append(chain.expiry() == null ? "null" : chain.expiry())
         // the underlying's mark rides along · null when Yahoo stated none
         // (bare XSP's ECNQUOTE does exactly that), NEVER zero. The day
         // fields ride with it for the screen's header · same rule: a change
         // the quote did not state is null, never 0.00, because "unchanged"
         // is a specific claim and "unstated" is not it.
         .append(",\"quote\":{\"price\":").append(money(chain.underlyingPrice()))
         .append(",\"change\":").append(money(chain.change()))
         .append(",\"changePercent\":").append(money(chain.changePercent()))
         .append(",\"time\":").append(chain.marketTime() == null ? "null" : chain.marketTime())
         .append(",\"currency\":").append(chain.currency() == null
                 ? "null" : "\"" + Json.esc(chain.currency()) + "\"")
         .append(",\"marketState\":").append(chain.marketState() == null
                 ? "null" : "\"" + Json.esc(chain.marketState()) + "\"")
         .append("},\"calls\":");
        rowsJson(b, chain.calls());
        b.append(",\"puts\":");
        rowsJson(b, chain.puts());
        return b.append('}').toString();
    }

    private static void rowsJson(StringBuilder b, List<Row> rows) {
        b.append('[');
        boolean first = true;
        for (Row r : rows) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"contractSymbol\":\"").append(Json.esc(r.contractSymbol()))
             .append("\",\"strike\":\"").append(r.strike().toPlainString())
             // bid/ask/last/iv: null when the chain omitted them · a gap the
             // screen renders as a gap. A 0.00 Yahoo actually stated passes
             // through, because a zero bid on a dead contract is a real quote.
             .append("\",\"bid\":").append(money(r.bid()))
             .append(",\"ask\":").append(money(r.ask()))
             .append(",\"last\":").append(money(r.lastPrice()))
             .append(",\"iv\":").append(money(r.impliedVolatility()))
             .append(",\"inTheMoney\":").append(r.inTheMoney() == null ? "null" : r.inTheMoney())
             .append(",\"expiration\":").append(r.expiration());
            // ABSENT KEYS STAY ABSENT · volume and openInterest are omitted
            // per row by Yahoo, and emitting 0 would state "traded, none"
            // about a row that stated nothing
            if (r.volume() != null) b.append(",\"volume\":").append(r.volume());
            if (r.openInterest() != null) b.append(",\"openInterest\":").append(r.openInterest());
            b.append('}');
        }
        b.append(']');
    }

    // ------------------------------------------------------------------
    // the underlying's chart · keyless v8, same gating, same cache doctrine
    // ------------------------------------------------------------------

    /**
     * THE CHART RANGES · an allowlist for the same reason the underlyings are
     * one: the value rides into Yahoo's URL, and a free-form string in an
     * upstream URL is an open proxy one parameter wide. Keys are what the
     * screen's buttons say; values are the venue's own range/interval pairs.
     * 1d/5m was observed live (2026-07-20, 21 intraday points on ^XSP);
     * 1mo/1d is the pair PriceFeed's equity history has always shipped on.
     * The 5d and 3mo pairs follow the venue's documented granularities and
     * were not exercised in that session · if one ever answers without a
     * series, the failure is a marked-stale serve or an honest 502, never an
     * invented chart.
     */
    private static final Map<String, String> CHART_RANGES = new LinkedHashMap<>();
    static {
        CHART_RANGES.put("1d", "range=1d&interval=5m");
        CHART_RANGES.put("1w", "range=5d&interval=15m");
        CHART_RANGES.put("1mo", "range=1mo&interval=1d");
        CHART_RANGES.put("3mo", "range=3mo&interval=1d");
    }

    public static List<String> chartRanges() {
        return List.copyOf(CHART_RANGES.keySet());
    }

    /**
     * Points for the instrument screen's underlying chart, in the currency
     * Yahoo quotes (carried in the payload, not assumed) · [[ms,px]] exactly
     * like /api/prices/history, but NOT through PriceFeed: this screen quotes
     * the option market's own currency, and converting a chain screen to
     * euros through the FX service would put an FX opinion inside every
     * strike comparison. A null range means the intraday default; an unknown
     * one is {@link Refused}, never forwarded.
     */
    public static String chartJson(String underlyingName, String range) {
        Underlying u = underlying(underlyingName);
        if (u == null)
            throw new Refused("unknown underlying '" + underlyingName + "' · known: "
                    + String.join(", ", UNDERLYINGS.keySet()));
        String r = range == null ? "1d" : range.toLowerCase(Locale.ROOT);
        String rangeQuery = CHART_RANGES.get(r);
        if (rangeQuery == null)
            throw new Refused("unknown chart range '" + range + "' · known: "
                    + String.join(", ", CHART_RANGES.keySet()));
        String key = "chart:" + u.root() + ':' + r;
        return Cache.getOrLoad("options:uchart", u.root() + ':' + r, CHAIN_TTL_S, () -> {
            try {
                String body = source.chartBody(u.yahooSymbol(), rangeQuery);
                String built = buildChartJson(u, body, "live", r);
                lastGood.put(key, built);
                return built;
            } catch (Exception e) {
                String stale = lastGood.get(key);
                return stale == null ? null
                        : stale.replaceFirst("\"fetched\":\"live\"", "\"fetched\":\"cached\"");
            }
        });
    }

    private static String buildChartJson(Underlying u, String body, String fetched, String range) {
        Matcher ts = Pattern.compile("\"timestamp\":\\[([0-9,]+)\\]").matcher(body);
        Matcher cl = Pattern.compile("\"close\":\\[([0-9.,null \\-eE]+)\\]").matcher(body);
        if (!ts.find() || !cl.find()) throw new IllegalStateException("no series");
        String currency = str(body, "\"currency\":\"([A-Z]+)\"");
        String[] times = ts.group(1).split(",");
        String[] closes = cl.group(1).split(",");
        StringBuilder b = new StringBuilder("{\"underlying\":\"").append(u.root())
                .append("\",\"yahooSymbol\":\"").append(Json.esc(u.yahooSymbol()))
                .append("\",\"fetched\":\"").append(fetched)
                .append("\",\"range\":\"").append(range)
                .append("\",\"currency\":").append(currency == null ? "null" : "\"" + currency + "\"")
                .append(",\"points\":[");
        boolean first = true;
        for (int i = 0; i < Math.min(times.length, closes.length); i++) {
            if (closes[i].contains("null") || closes[i].isBlank()) continue;   // a gap stays a gap
            if (!first) b.append(',');
            first = false;
            b.append('[').append(times[i].trim()).append("000,")
             .append(new BigDecimal(closes[i].trim()).toPlainString()).append(']');
        }
        return b.append("]}").toString();
    }

    /** Money as a quoted string, absence as a bare null · BrokerApi's rule,
     *  needed here because the payload is assembled here. */
    private static String money(BigDecimal v) {
        return v == null ? "null" : "\"" + v.toPlainString() + "\"";
    }
}
