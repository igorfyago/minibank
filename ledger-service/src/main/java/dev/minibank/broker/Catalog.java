package dev.minibank.broker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

    public record Instrument(String symbol, String kind, String assetCode, String settleCcy) {}

    private Catalog() {}

    /** Idempotent, safe on every boot · the same habit as the system accounts. */
    public static void seed() throws SQLException {
        try (Connection c = BrokerDb.open()) {
            put(c, "BTC", "crypto", "BTC");
            put(c, "AAPL", "equity", "AAPL");
        }
    }

    private static void put(Connection c, String symbol, String kind, String assetCode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO instruments(symbol, kind, asset_code, settle_ccy)
                VALUES (?,?,?, 'EUR') ON CONFLICT (symbol) DO NOTHING""")) {
            ps.setString(1, symbol);
            ps.setString(2, kind);
            ps.setString(3, assetCode);
            ps.executeUpdate();
        }
    }

    public static List<Instrument> all() throws SQLException {
        List<Instrument> out = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT symbol, kind, asset_code, settle_ccy FROM instruments ORDER BY symbol");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                out.add(new Instrument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }
        return out;
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
