package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Test-only peek into the notifications service's own database. */
final class NotificationsProbe {

    private NotificationsProbe() {}

    static int countByKey(String eventKey) throws SQLException {
        String base = System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
        String url = base.substring(0, base.lastIndexOf('/') + 1) + "minibank_notifications";
        try (Connection c = DriverManager.getConnection(url, "minibank", "minibank");
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM notifications WHERE event_key = ?")) {
            ps.setString(1, eventKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
