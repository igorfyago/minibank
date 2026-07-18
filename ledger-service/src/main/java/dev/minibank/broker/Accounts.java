package dev.minibank.broker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * IDENTITY, AND THE SEAM WHERE REAL AUTH GOES.
 *
 * The desk knows an anonymous browser string. The bank knows a customer id.
 * Something has to hold the mapping, and it belongs to the service that
 * needs it rather than to either end: the desk should not learn about
 * customer ids, and the ledger should not learn that a browser exists.
 *
 * Being honest about what this is: a CLAIM, not authentication. Anyone who
 * knows a session string can bind it to a customer. That is fine for a demo
 * whose cast is public, and it is not fine for anything else. The point of
 * putting it behind one table and two methods is that replacing it with real
 * credentials changes this file and nothing else · every other query already
 * speaks customer_id.
 */
public final class Accounts {

    private Accounts() {}

    /** Bind a desk session to a bank customer. Re-binding is allowed: the
     *  demo lets you switch who you are, and pretending otherwise would just
     *  strand sessions. */
    public static void link(String deskSession, long customerId) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO account_link(desk_session, customer_id) VALUES (?,?)
                     ON CONFLICT (desk_session) DO UPDATE SET customer_id = EXCLUDED.customer_id,
                                                             linked_at = now()""")) {
            ps.setString(1, deskSession);
            ps.setLong(2, customerId);
            ps.executeUpdate();
        }
    }

    /** Which customer is this session, or null if it has never claimed one. */
    public static Long customerFor(String deskSession) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT customer_id FROM account_link WHERE desk_session = ?")) {
            ps.setString(1, deskSession);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------
    // the watchlist · it was localStorage on one browser, which meant it
    // was not data, it was a preference that died with a cache clear
    // ------------------------------------------------------------------

    public static void watch(long customerId, String symbol) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO watchlist(customer_id, symbol) VALUES (?,?) ON CONFLICT DO NOTHING")) {
            ps.setLong(1, customerId);
            ps.setString(2, symbol.toUpperCase());
            ps.executeUpdate();
        }
    }

    public static void unwatch(long customerId, String symbol) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM watchlist WHERE customer_id = ? AND symbol = ?")) {
            ps.setLong(1, customerId);
            ps.setString(2, symbol.toUpperCase());
            ps.executeUpdate();
        }
    }

    public static List<String> watchlist(long customerId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT symbol FROM watchlist WHERE customer_id = ? ORDER BY added_at")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }
}
