package dev.minibank.broker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WHAT CAN BE TRADED, and how it maps onto the ledger.
 *
 * asset_code is the join to the bank: it is the CURRENCY the ledger uses for
 * the asset leg of the trade. The broker says "0.0013 BTC"; the ledger holds
 * an account denominated in BTC. Neither has to know the other's schema, and
 * the only thing that crosses the boundary is a symbol and a number.
 *
 * The catalog is seeded, not user-editable. Listing an instrument is a
 * business decision with regulatory weight in a real brokerage, so an
 * endpoint that lets anyone add one would be the wrong shape even in a demo.
 */
public final class Catalog {

    /**
     * multiplier is how many units of the underlying ONE unit of this
     * instrument controls · 1 for a share or a coin, 100 for an option
     * contract. Every money number a screen shows is qty * price * multiplier,
     * so a stock is not a special case: it is the ordinary case with the
     * ordinary multiplier of one, and no valuation anywhere asks "is this an
     * option?" before deciding how to multiply.
     *
     * expiresOn is null for anything that does not expire.
     */
    public record Instrument(String symbol, String kind, String assetCode, String settleCcy,
                            String displayName, String exchange,
                            java.math.BigDecimal multiplier, java.time.LocalDate expiresOn) {

        /** Inclusive of the expiry date itself · a contract trades ON its
         *  expiry day and stops afterwards. */
        public boolean expiredAsOf(java.time.LocalDate asOf) {
            return expiresOn != null && asOf.isAfter(expiresOn);
        }
    }

    private Catalog() {}

    /**
     * Idempotent, safe on every boot · the same habit as the system accounts.
     *
     * WHY STILL EXACTLY TWO, now that the ledger can carry more.
     *
     * This comment used to explain that a third row here would be actively
     * dangerous: Products.settleFill mapped a symbol onto an account with
     * `customerId + ("btc".equals(asset) ? BTC : AAPL)`, so every symbol that
     * was not bitcoin settled into the customer's APPLE account, and the
     * books still summed to zero in every currency · a wrong holding that
     * passed every check the bank runs. It closed by saying the third
     * instrument was a ledger change, not a row in this table.
     *
     * That ledger change has been made. Asset account numbering now comes
     * from AssetRegistry, an unlisted symbol raises instead of defaulting,
     * and a customer's holding account is allocated on first trade. So the
     * hazard is gone, and what is left is the ordinary reason a brokerage
     * does not list things casually: listing is a business decision with
     * regulatory weight, and it is not this method's to make. Use list()
     * below, which lists an instrument in BOTH places at once · the broker's
     * catalog and the ledger's registry · because an instrument present in
     * only one of them is exactly the asymmetry that caused the old bug.
     */
    public static void seed() throws SQLException {
        try (Connection c = BrokerDb.open()) {
            // no venue: bitcoin has no exchange, and naming one we do not use
            // would be a lie told in a column heading
            //
            // Both carry multiplier 1 and no expiry: one share is one share,
            // one bitcoin is one bitcoin, and neither of them ever stops
            // existing. No option is seeded here · see the class comment.
            put(c, "BTC", "crypto", "BTC", "Bitcoin", "CRYPTO", java.math.BigDecimal.ONE, null);
            put(c, "AAPL", "equity", "AAPL", "Apple Inc.", "NASDAQ.NMS", java.math.BigDecimal.ONE, null);
        }
    }

    /**
     * LIST an instrument · the whole act, both halves.
     *
     * A row in this table makes a symbol routable. An entry in the ledger's
     * asset registry makes it SETTLEABLE. An instrument that has the first
     * and not the second is a tradable thing whose fills the ledger will
     * refuse, and before the registry existed it was a tradable thing whose
     * fills landed in the wrong account. So the two are done together, here,
     * and the ledger half goes first: if the registry refuses the symbol (a
     * slot collision, a currency contradiction), nothing becomes tradable.
     *
     * assetCode is the ledger CURRENCY of the asset leg · it is what the
     * registry keys its clearing account on, and until now it was written
     * into this table and never read by anything that mattered.
     */
    public static void list(String symbol, String kind, String assetCode,
                            String displayName, String exchange) throws SQLException {
        list(symbol, kind, assetCode, displayName, exchange, java.math.BigDecimal.ONE, null);
    }

    /**
     * LIST a contract · the full form, with the contract size and the expiry.
     *
     * THE MULTIPLIER IS WRITTEN IN BOTH PLACES, BY THIS METHOD, DELIBERATELY.
     *
     * It has to be in the ledger's registry because HttpApi values a
     * customer's holdings on a shard connection and cannot read this database.
     * It has to be here because the broker is what computes the consideration
     * of a fill. That is a duplicated fact across a service boundary, and the
     * only thing keeping the two halves in step is that this method writes
     * both of them in one call · exactly the argument the currency half of
     * this method has always made. An instrument whose multiplier is 100 in
     * one database and 1 in the other misvalues by a hundred on whichever side
     * reads the stale copy, and the books still sum to zero either way.
     *
     * The ledger half goes FIRST, for the reason it always has: if the
     * registry refuses the listing, nothing becomes tradable.
     */
    public static void list(String symbol, String kind, String assetCode,
                            String displayName, String exchange,
                            java.math.BigDecimal multiplier,
                            java.time.LocalDate expiresOn) throws SQLException {
        dev.minibank.ledger.AssetRegistry.register(
                symbol, assetCode, displayName.toLowerCase(), multiplier, kind, expiresOn);
        try (Connection c = BrokerDb.open()) {
            put(c, symbol, kind, assetCode, displayName, exchange, multiplier, expiresOn);
        }
    }

    /**
     * Upsert the display fields, insert the rest.
     *
     * DO NOTHING would leave the name and exchange NULL forever on any
     * database that already has these rows · which is every database this
     * service has ever run against.
     */
    private static void put(Connection c, String symbol, String kind, String assetCode,
                            String displayName, String exchange,
                            java.math.BigDecimal multiplier,
                            java.time.LocalDate expiresOn) throws SQLException {
        // multiplier and expires_on are upserted alongside the display fields
        // and not left to DO NOTHING, for the same reason the display fields
        // were: DO NOTHING would leave the contract size at its V1 default of
        // 1 forever on every database that already has these rows, which is
        // every database this service has ever run against.
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO instruments(symbol, kind, asset_code, settle_ccy, display_name, exchange,
                                        multiplier, expires_on)
                VALUES (?,?,?, 'EUR', ?,?,?,?)
                ON CONFLICT (symbol) DO UPDATE
                   SET display_name = EXCLUDED.display_name,
                       exchange     = EXCLUDED.exchange,
                       multiplier   = EXCLUDED.multiplier,
                       expires_on   = EXCLUDED.expires_on""")) {
            ps.setString(1, symbol);
            ps.setString(2, kind);
            ps.setString(3, assetCode);
            ps.setString(4, displayName);
            ps.setString(5, exchange);
            ps.setBigDecimal(6, multiplier);
            ps.setDate(7, expiresOn == null ? null : java.sql.Date.valueOf(expiresOn));
            ps.executeUpdate();
        }
    }

    private static final String COLUMNS =
            "SELECT symbol, kind, asset_code, settle_ccy, display_name, exchange,"
                    + " multiplier, expires_on FROM instruments";

    public static List<Instrument> all() throws SQLException {
        List<Instrument> out = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(COLUMNS + " ORDER BY symbol");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(read(rs));
        }
        return out;
    }

    /** Symbol -> instrument, for a screen that has to label every row it draws. */
    public static Map<String, Instrument> bySymbol() throws SQLException {
        Map<String, Instrument> out = new LinkedHashMap<>();
        for (Instrument i : all()) out.put(i.symbol(), i);
        return out;
    }

    private static Instrument read(ResultSet rs) throws SQLException {
        String symbol = rs.getString(1);
        // NOT NULL DEFAULT 1 in the schema, so this cannot be null · but a
        // null contract size means "unknown", and valuing on an unknown
        // contract size is the 100x error this column exists to prevent
        java.math.BigDecimal multiplier = rs.getBigDecimal(7);
        if (multiplier == null || multiplier.signum() <= 0)
            throw new IllegalStateException("'" + symbol + "' has multiplier " + multiplier
                    + " · a position cannot be valued without a positive contract size");
        java.sql.Date exp = rs.getDate(8);
        return new Instrument(symbol, rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                multiplier, exp == null ? null : exp.toLocalDate());
    }

    /**
     * ONE instrument, or null · a single-row lookup rather than bySymbol()'s
     * whole-table scan.
     *
     * It exists because the settlement path asks for the contract size on
     * every fill, and building a map of the entire catalog to read one number
     * out of it is a table scan and a fresh connection per fill.
     */
    public static Instrument find(String symbol) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    public static boolean exists(String symbol) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM instruments WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
