package dev.b4rruf3t.sso;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/**
 * Refresh token store. Postgres-backed, one row per active session.
 * Tokens are opaque strings, hashed before storage.
 */
public final class SessionStore {
    private final ConnectionSource db;

    public SessionStore(ConnectionSource db) {
        this.db = db;
    }

    /** Create a new session. Returns the refresh token. */
    public String createSession(String userId, int ttlDays) {
        String token = "ref_" + generateId();
        String hash = sha256(token);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO sessions (id, user_id, refresh_token_hash, expires_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, "ses_" + generateId());
            ps.setString(2, userId);
            ps.setString(3, hash);
            ps.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(ttlDays * 86400L)));
            ps.executeUpdate();
            return token;
        } catch (SQLException e) {
            throw new RuntimeException("session creation failed", e);
        }
    }

    /** Validate a refresh token. Returns the user ID if valid and not expired. */
    public Optional<String> validate(String token) {
        String hash = sha256(token);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT user_id FROM sessions WHERE refresh_token_hash = ? AND expires_at > NOW() AND revoked_at IS NULL")) {
            ps.setString(1, hash);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(rs.getString("user_id")) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("session validation failed", e);
        }
    }

    /** Revoke a session (logout). */
    public void revoke(String token) {
        String hash = sha256(token);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE sessions SET revoked_at = NOW() WHERE refresh_token_hash = ?")) {
            ps.setString(1, hash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("session revocation failed", e);
        }
    }

    /** Revoke all sessions for a user. */
    public void revokeAll(String userId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE sessions SET revoked_at = NOW() WHERE user_id = ? AND revoked_at IS NULL")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("session revocation failed", e);
        }
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String generateId() {
        return Long.toHexString(System.currentTimeMillis()) +
               Long.toHexString((long) (Math.random() * Long.MAX_VALUE)).substring(0, 8);
    }
}
